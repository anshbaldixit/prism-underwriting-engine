# Adverse Action and Reason Codes

## Regulatory basis
When credit is declined, offered on less favourable terms, or an application cannot be completed, the applicant must receive a notice stating the specific principal reasons for the decision (Regulation B, implementing the Equal Credit Opportunity Act, section 1002.9). Reasons must be specific: "insufficient score" or "internal policy" is not acceptable. Using a complex or machine-learning model does not remove this obligation.

## How Prism derives reasons
The scorecard is additive: every feature contributes points that either raise or lower the applicant's risk. Principal reasons are the features with the largest risk-increasing contributions for that applicant, at most four, in descending order. Because the model is additive, these reasons are exact rather than approximated.

## Reason code catalogue
R01 Credit bureau score is below our threshold. R02 Length of credit history is limited. R03 Too few established credit accounts. R04 Number of recent credit inquiries. R05 Delinquent past or present credit obligations. R06 Income is irregular from month to month. R07 Verified income is insufficient for the amount requested. R08 Insufficient months of verified income. R09 Declining account balance trend. R10 Low minimum account balance relative to income. R11 Recent overdraft or insufficient-funds activity. R12 Irregular rent payments. R13 Irregular utility payments. R14 Irregular phone bill payments. R15 Existing financial obligations are high relative to income. R16 High share of discretionary spending. R17 Gambling-related account activity. R18 Amount requested is high relative to income.

## Language model use in notices
A language model may be used to render the reasons into plain language. It must restate the decision and every reason code exactly, may not add or omit reasons, and may not reference prohibited bases. Every generated notice is validated against the model's reason codes before release; a notice that fails validation is replaced by the deterministic template.

## Applicant rights
The notice must tell the applicant that they may request the specific reasons within 60 days if not already provided, and that they may obtain a free copy of any consumer report used, along with the name of the reporting agency.
