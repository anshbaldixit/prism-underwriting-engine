# Fraud and Verification Gate

## Purpose
The gate runs before credit pricing and answers one question: is the applicant who they say they are, and is the information they gave true? It uses behavioural, device and veracity signals that are never used for pricing.

## Signals and points
Stated income more than 35 percent above bank-verified income: 40 points. Identity number pasted into the form: 25 points. Same device used for three or more applications in 30 days: 35 points. Email address created within the last 60 days: 15 points. VoIP phone number: 10 points. Application completed in under two minutes: 15 points. Income field edited three or more times: 10 points. Income could not be verified because no account was linked: 20 points.

## Outcomes
Total points of 60 or more: BLOCK, the application is declined pending manual fraud review and no credit decision is issued. 35 to 59 points: STEP_UP, the application is referred with specific verification tasks (identity document and liveness check, income document, or a call-back) before any credit decision. Below 35: PASS.

## Step-up verification tasks
Income veracity signals require a recent pay stub or tax document. Synthetic-identity signals require document plus liveness verification. Velocity signals require review of other applications from the same device. Behavioural signals require a call-back to confirm application details.

## False positives
Legitimate applicants must not be blocked by a single weak signal; thresholds are set so that a block requires at least two independent strong signals. Step-up friction on legitimate applicants is monitored and must remain below 1 percent.
