# ML pipeline

| Script | Purpose |
|---|---|
| `generate_data.py` | 30,000 synthetic applicants (thick / thin / new-to-credit) with bureau, cash-flow, application and behavioural signals, a fraud sub-population, fairness-testing attributes (never features) and 12-month outcomes. No real data. |
| `train_and_export.py` | WoE-binned logistic scorecard, bureau-only baseline (no-file = unscoreable), GBM benchmark; hold-out metrics by segment; policy simulation at a fixed portfolio bad rate; fairness harness (AIR, equal opportunity); fraud-gate evaluation; exports `scorecard.json`, `fraud_rules.json`, `model_card.json`, `historical_sample.json` into the backend and figures into `docs/figures`. |
| `personas.py` | The six demo personas with three-month statements (`backend/src/main/resources/demo/personas.json`). |
| `smoke_personas.py` | Submits every persona to a running backend and prints decision vs expectation. |

```bash
pip install -r requirements.txt
python generate_data.py && python train_and_export.py && python personas.py
```

The Java engine reproduces this pipeline's scores, PDs and reason codes exactly; `ScorecardEngineTest` checks it on 300 exported rows.
