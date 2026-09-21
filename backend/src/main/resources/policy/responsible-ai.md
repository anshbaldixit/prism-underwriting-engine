# Responsible AI Standard for Prism

## Roles of each component
The scorecard and the policy engine make credit decisions. The fraud gate makes verification decisions. The language model explains, summarises and retrieves; it does not decide, rank, price or override, and it has no access to identity fields.

## Explainability
Every decision stores the model version, the exact feature values, each feature's contribution, and the reason codes. An underwriter or auditor can reconstruct any decision from the stored record without re-running the model.

## Fairness testing
Before release, approval rates and true-positive rates are compared across age band, sex and region on hold-out data. An adverse impact ratio below 0.80 on any attribute blocks release until investigated. Results are published in the model card.

## Human oversight
Referred applications are always reviewed by a person. Underwriters can override in both directions and their reasons are recorded. Override patterns are reviewed weekly.

## Data minimisation and retention
Identity numbers are stored only as salted hashes. Bank transaction data is retained only for the duration required for the decision record and its audit. Prompts sent to a language model contain derived features and reason codes only; any incidental identifiers are masked before sending and never logged.

## Monitoring
Score distribution stability, realised bad rate against predicted PD, fairness metrics, model-call latency, validation-failure rate and fallback rate are tracked continuously and reviewed monthly.
