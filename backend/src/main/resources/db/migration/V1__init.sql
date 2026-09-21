-- Prism underwriting engine: initial schema (PostgreSQL 16 + pgvector)
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------- identity
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- applications
-- PII is minimised by design: the national identifier is stored only as a salted hash used for
-- duplicate/synthetic-identity checks; it can never be recovered from the database.
CREATE TABLE applications (
    id                     UUID PRIMARY KEY,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             VARCHAR(64),
    status                 VARCHAR(20) NOT NULL,
    persona_id             VARCHAR(64),
    full_name              VARCHAR(120) NOT NULL,
    email                  VARCHAR(160) NOT NULL,
    phone                  VARCHAR(40),
    national_id_hash       VARCHAR(64),
    employment_type        VARCHAR(30) NOT NULL,
    stated_annual_income   NUMERIC(14,2) NOT NULL,
    requested_amount       NUMERIC(14,2) NOT NULL,
    loan_purpose           VARCHAR(300),
    consent_bank_data      BOOLEAN NOT NULL,
    consent_alt_data       BOOLEAN NOT NULL,
    bank_linked            BOOLEAN NOT NULL,
    file_type              VARCHAR(10) NOT NULL,
    bureau_score           NUMERIC(6,2),
    months_on_file         NUMERIC(8,2),
    tradelines             NUMERIC(6,2),
    inquiries_6m           NUMERIC(6,2),
    delinquencies_24m      NUMERIC(6,2),
    session_seconds        NUMERIC(10,1),
    income_field_edits     INTEGER,
    paste_ssn              BOOLEAN,
    paste_income           BOOLEAN,
    email_age_days         NUMERIC(10,1),
    voip_phone             BOOLEAN,
    device_id              VARCHAR(80),
    device_apps_30d        INTEGER,
    profile_text           TEXT,
    profile_embedding      vector(1024)
);
CREATE INDEX idx_applications_device_created ON applications (device_id, created_at);
CREATE INDEX idx_applications_status ON applications (status);
CREATE INDEX idx_applications_national_id_hash ON applications (national_id_hash);

CREATE TABLE bank_transactions (
    id              UUID PRIMARY KEY,
    application_id  UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    posted_date     DATE NOT NULL,
    description     VARCHAR(200) NOT NULL,
    amount          NUMERIC(14,2) NOT NULL,
    balance_after   NUMERIC(14,2),
    category        VARCHAR(30),
    category_confidence NUMERIC(5,4)
);
CREATE INDEX idx_bank_transactions_application ON bank_transactions (application_id);

-- ---------------------------------------------------------------- decisions (append-only audit)
CREATE TABLE decisions (
    id                    UUID PRIMARY KEY,
    application_id        UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    model_id              VARCHAR(60) NOT NULL,
    model_version         VARCHAR(20) NOT NULL,
    fraud_rules_version   VARCHAR(20) NOT NULL,
    fraud_outcome         VARCHAR(10) NOT NULL,
    fraud_points          INTEGER NOT NULL,
    fraud_rules_fired     TEXT NOT NULL,
    pd                    DOUBLE PRECISION NOT NULL,
    score                 INTEGER NOT NULL,
    decision              VARCHAR(10) NOT NULL,
    basis                 VARCHAR(30) NOT NULL,
    credit_limit          NUMERIC(14,2),
    apr                   NUMERIC(6,2),
    reason_codes          TEXT NOT NULL,
    contributions         TEXT NOT NULL,
    features_snapshot     TEXT NOT NULL,
    cashflow_features     TEXT,
    verification_items    TEXT NOT NULL,
    policy_notes          TEXT NOT NULL,
    notice_json           TEXT,
    notice_provider       VARCHAR(40),
    notice_validated      BOOLEAN,
    notice_fallback_used  BOOLEAN,
    summary_json          TEXT,
    summary_provider      VARCHAR(40),
    scoring_latency_ms    BIGINT NOT NULL
);
CREATE INDEX idx_decisions_application ON decisions (application_id, created_at DESC);

CREATE TABLE underwriter_actions (
    id              UUID PRIMARY KEY,
    application_id  UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    username        VARCHAR(64) NOT NULL,
    action          VARCHAR(30) NOT NULL,
    reason          VARCHAR(600) NOT NULL
);
CREATE INDEX idx_underwriter_actions_application ON underwriter_actions (application_id);

-- ---------------------------------------------------------------- AI audit trail
CREATE TABLE ai_invocations (
    id                 UUID PRIMARY KEY,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    task               VARCHAR(40) NOT NULL,
    provider           VARCHAR(40) NOT NULL,
    model              VARCHAR(120),
    application_id     UUID,
    prompt_sha256      VARCHAR(64) NOT NULL,
    input_tokens       INTEGER,
    output_tokens      INTEGER,
    latency_ms         BIGINT NOT NULL,
    output_valid       BOOLEAN NOT NULL,
    fallback_used      BOOLEAN NOT NULL,
    guardrail_action   VARCHAR(30),
    validation_errors  TEXT
);
CREATE INDEX idx_ai_invocations_created ON ai_invocations (created_at DESC);

-- ---------------------------------------------------------------- vector stores
-- Exemplar phrases per transaction category; a raw description is categorised by nearest exemplar.
CREATE TABLE category_exemplars (
    id          UUID PRIMARY KEY,
    category    VARCHAR(30) NOT NULL,
    text        VARCHAR(200) NOT NULL,
    provider    VARCHAR(40) NOT NULL,
    embedding   vector(1024) NOT NULL
);
CREATE INDEX idx_category_exemplars_embedding ON category_exemplars USING hnsw (embedding vector_cosine_ops);

-- Decided applications with known outcomes (from the ML hold-out), used for "applicants like this one".
CREATE TABLE historical_applicants (
    id             VARCHAR(20) PRIMARY KEY,
    file_type      VARCHAR(10) NOT NULL,
    bank_linked    BOOLEAN NOT NULL,
    score          INTEGER NOT NULL,
    pd             DOUBLE PRECISION NOT NULL,
    decision       VARCHAR(10) NOT NULL,
    defaulted_12m  BOOLEAN NOT NULL,
    reason_codes   TEXT NOT NULL,
    features       TEXT NOT NULL,
    profile_text   TEXT NOT NULL,
    provider       VARCHAR(40) NOT NULL,
    embedding      vector(1024) NOT NULL
);
CREATE INDEX idx_historical_applicants_embedding ON historical_applicants USING hnsw (embedding vector_cosine_ops);

-- Internal policy documents chunked by section for retrieval-augmented answers.
CREATE TABLE policy_chunks (
    id          UUID PRIMARY KEY,
    doc         VARCHAR(80) NOT NULL,
    section     VARCHAR(160) NOT NULL,
    content     TEXT NOT NULL,
    provider    VARCHAR(40) NOT NULL,
    embedding   vector(1024) NOT NULL
);
CREATE INDEX idx_policy_chunks_embedding ON policy_chunks USING hnsw (embedding vector_cosine_ops);
