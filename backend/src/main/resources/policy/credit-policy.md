# Prism Consumer Credit Policy (prototype)

## Scope and product
This policy governs unsecured consumer credit lines between $300 and $20,000 originated through the Prism digital channel. It applies to applicants with an established bureau file ("thick"), a limited file ("thin": fewer than 24 months of history or two or fewer tradelines), and applicants with no bureau file at all ("new-to-credit", NTC).

## Data used in a decision
A decision may use: (a) bureau attributes when a file exists; (b) cash-flow attributes derived from a bank account the applicant has explicitly consented to link, covering the most recent 3 to 12 months; (c) the amount requested relative to income. Behavioural signals captured during the application (typing, pasting, session duration, device reuse) are used ONLY by the fraud and verification gate and never influence the score, limit or price.

## Prohibited bases
Age, sex, gender identity, race, colour, religion, national origin, marital status, disability, receipt of public assistance and the exercise of consumer-protection rights are never inputs to any model, rule or manual decision. Fairness on these dimensions is tested on proxies before each model release and monitored monthly.

## Decision bands
The scorecard produces a probability of serious delinquency within 12 months (PD). Applications with PD at or below the approve threshold are approved automatically. Applications between the approve and refer thresholds are referred to an underwriter. Applications above the refer threshold are declined. The thresholds are calibrated so that the automatically approved book has an expected 12-month bad rate of 6 percent and are re-validated quarterly.

## New-to-credit path
An NTC applicant who has not linked a bank account cannot be scored adequately. Such applications are referred, never auto-declined, and the applicant is invited to link an account. An NTC applicant with linked cash-flow data is scored on the same scorecard as everyone else; there is no separate NTC penalty.

## Credit limit assignment
For approved applications the limit is the lower of the amount requested and a multiple of verified monthly income: 3x for PD up to 3 percent, 2x for PD up to 6 percent, otherwise 1x. Verified income means income observed in linked bank data; stated income is used only when no bank data exists, and then only up to 1x.

## Pricing tiers
APR is assigned from the score: 14.99 percent for scores of 680 and above, 19.99 percent for 640 to 679, and 24.99 percent below 640. Pricing never uses behavioural or device signals.

## Underwriter overrides
An underwriter may override the engine's decision in either direction with a written reason of at least 20 characters. Overrides are logged with the underwriter's identity, reviewed weekly, and used as labelled examples when the scorecard is retrained. An override may not introduce a prohibited basis.
