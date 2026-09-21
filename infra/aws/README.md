# Deploying Prism on AWS

The prototype runs anywhere Docker runs; this is the reference topology for a real deployment. Everything the
application needs from AWS is reached through IAM roles - no access keys live in the containers.

```
                     ┌──────────────────────── VPC (2 AZs) ────────────────────────┐
  users ──HTTPS──▶  ALB (WAF, TLS)                                                  │
                     │        ┌───────────────┐        ┌──────────────────────┐    │
                     ├──────▶ │ ECS Fargate   │        │ ECS Fargate          │    │
                     │        │ prism-frontend│        │ prism-backend        │    │
                     │        │ (nginx + SPA) │ ─/api─▶│ (Spring Boot, JRE 21)│    │
                     │        └───────────────┘        └──────┬─────┬─────┬───┘    │
                     │                                        │     │     │        │
                     │   private subnets          JDBC/TLS ───┘     │     │        │
                     │        ┌───────────────────────┐             │     │        │
                     │        │ RDS PostgreSQL 16     │◀────────────┘     │        │
                     │        │ + pgvector, Multi-AZ  │                   │        │
                     │        └───────────────────────┘                   │        │
                     │   VPC interface endpoints (no internet egress):    │        │
                     │     bedrock-runtime ◀───────────────────────────────┘        │
                     │     secretsmanager, logs                                     │
                     └────────────────────────────────────────────────────────────┘
                              │ Amazon Bedrock: Claude (Converse), Titan Embeddings v2, Guardrails
                              │ CloudWatch Logs + metrics (Actuator/Micrometer)
                              │ Secrets Manager: prism/db, prism/jwt
```

## Components

| Layer | Service | Notes |
|---|---|---|
| Edge | ALB + AWS WAF + ACM certificate | TLS termination, rate limiting rules in front of `/api/auth/login`, `/api/applications`, `/api/copilot` (the in-app token bucket is a second line). |
| Frontend | ECS Fargate task from `frontend/Dockerfile` | nginx serves the SPA and proxies `/api` to the backend service via service discovery. |
| Backend | ECS Fargate task from `backend/Dockerfile` | 2 tasks minimum across AZs; task role = `iam-policy-prism-backend.json`; env vars injected from Secrets Manager (`PRISM_JWT_SECRET`, `PRISM_DB_PASSWORD`). |
| Database | RDS for PostgreSQL 16 with the `vector` extension | Multi-AZ, encrypted at rest (KMS), 7-day PITR. Flyway migrates on start-up. |
| AI | Amazon Bedrock | `PRISM_AI_PROVIDER=bedrock`, `PRISM_EMBEDDING_PROVIDER=bedrock`, `PRISM_BEDROCK_GUARDRAIL_ID` from `guardrail-prism-notices.json`. Reached through a VPC interface endpoint so prompts never traverse the public internet. Model invocation logging enabled to CloudWatch/S3 for audit. |
| Observability | CloudWatch Logs, CloudWatch metrics via Actuator | Alarms on: p95 scoring latency, LLM fallback rate > 5 %, validation-failure rate > 2 %, 5xx rate, RDS CPU/connections. |
| Secrets | AWS Secrets Manager | `prism/jwt` (rotated quarterly), `prism/db` (RDS-managed rotation). |

## Step by step (free-tier account)

1. **Bedrock model access** - console → Bedrock → Model access → enable *Claude Haiku 4.5* and *Titan Text Embeddings V2* in `us-east-1`.
2. **Guardrail** - `aws bedrock create-guardrail --cli-input-json file://guardrail-prism-notices.json` then
   `aws bedrock create-guardrail-version --guardrail-identifier <id>`; put the id/version in `PRISM_BEDROCK_GUARDRAIL_ID` / `PRISM_BEDROCK_GUARDRAIL_VERSION`.
3. **IAM** - create role `prism-backend-task` with `iam-policy-prism-backend.json` (trust: `ecs-tasks.amazonaws.com`). For local testing with the same permissions, attach the policy to a user and run `aws configure`.
4. **Database** - RDS PostgreSQL 16 (db.t4g.micro is inside the free plan), then `CREATE EXTENSION vector;` once as the master user (Flyway's `V1__init.sql` also does this if the role is allowed).
5. **Secrets** - `aws secretsmanager create-secret --name prism/jwt --secret-string "$(openssl rand -base64 48)"` and `prism/db`.
6. **Containers** - push both images to ECR (`docker build` with the Dockerfiles), create an ECS cluster + two Fargate services, map the environment from `.env.example` to task definition `environment` / `secrets`.
7. **Verify** - `GET https://<alb>/actuator/health` → `UP`; sign in; submit the *Priya* persona; the decision page should show `Amazon Bedrock · validated against model factors` on the notice.

## Cost at demo scale

Bedrock is pay-per-token: a decision notice is roughly 1.5 K input / 0.4 K output tokens, i.e. well under one cent on Claude Haiku 4.5, and Titan embeddings cost fractions of a cent per thousand transactions. A full demo run (six personas, a few copilot questions) costs cents; the AWS free plan's sign-up credits cover it many times over. RDS and Fargate are the meaningful line items - stop them when not demoing.

## What changes in production (beyond this prototype)

- Bureau pull and open-banking connection move server-side behind adapter interfaces (the request DTO already mirrors their shapes).
- Applicant authentication becomes the customer identity provider (Cognito / existing IdP); the seeded users disappear.
- Model artefacts are promoted through a model registry with the model card attached; the `decisions` table already records the version that scored each case.
- Fairness monitoring runs as a scheduled job over `decisions` with BISG proxies, feeding the same AIR dashboard.
