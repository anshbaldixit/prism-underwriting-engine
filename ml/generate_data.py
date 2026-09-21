"""
Synthetic applicant population for the Prism underwriting prototype.

Design goals (so the downstream findings are honest, not cooked):
  * Three file types: thick (full bureau history), thin (<24 months / <=2 tradelines),
    NTC (no bureau file at all). Bureau features are genuinely missing for NTC.
  * A latent creditworthiness factor `z` drives BOTH bureau and cash-flow signals, so
    cash-flow data carries real (but noisy) information about repayment.
  * Young applicants are over-represented in NTC (as in reality). Age is generated for
    FAIRNESS TESTING ONLY and is never exported as a model feature.
  * A small fraud sub-population (synthetic identity, income inflation, device rings)
    with distinct behavioural signatures, handled by a separate fraud gate.

No real customer data is used anywhere. Run: python generate_data.py
"""
from __future__ import annotations

import json
import os

import numpy as np
import pandas as pd

SEED = 42
N = 30_000
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")

# Cash-flow features are only observable when the applicant links a bank account.
CASHFLOW_COLUMNS = [
    "monthly_income", "income_cv", "income_months_observed", "balance_trend", "min_balance_ratio",
    "nsf_count_6m", "rent_ontime_ratio", "utility_ontime_ratio", "telco_ontime_ratio",
    "obligation_ratio", "discretionary_ratio", "income_inflation_ratio",
]


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-x))


def generate(n: int = N, seed: int = SEED) -> pd.DataFrame:
    rng = np.random.default_rng(seed)

    # ---- demographics (fairness-testing only) --------------------------------
    age = rng.integers(18, 70, size=n)
    sex = rng.choice(["F", "M"], size=n)
    region = rng.choice(["urban", "suburban", "rural"], size=n, p=[0.5, 0.35, 0.15])

    # ---- file type: younger => more likely NTC / thin --------------------------
    p_ntc = np.clip(0.55 - 0.012 * (age - 18), 0.04, 0.55)
    p_thin = np.clip(0.30 - 0.004 * (age - 18), 0.08, 0.30)
    u = rng.random(n)
    file_type = np.where(u < p_ntc, "ntc", np.where(u < p_ntc + p_thin, "thin", "thick"))

    # ---- latent creditworthiness -----------------------------------------------
    # Independent of file type by construction: the ability to repay of a thin-file
    # applicant is drawn from the same distribution as a thick-file one. What differs
    # is how much evidence the bureau holds about it.
    z = rng.normal(0, 1, n)

    is_ntc = file_type == "ntc"
    is_thin = file_type == "thin"
    is_thick = file_type == "thick"

    # ---- bureau signals (missing for NTC) --------------------------------------
    bureau_score = np.full(n, np.nan)
    bureau_score[is_thick] = np.clip(690 + 55 * z[is_thick] + rng.normal(0, 25, is_thick.sum()), 300, 850)
    thin_idx = np.where(is_thin)[0]
    has_thin_score = rng.random(thin_idx.size) < 0.7  # thin files: score exists for ~70%, noisier
    bureau_score[thin_idx[has_thin_score]] = np.clip(
        655 + 35 * z[thin_idx[has_thin_score]] + rng.normal(0, 45, has_thin_score.sum()), 300, 850
    )

    # Longer, broader histories are mildly associated with better outcomes (as in real bureau data)
    months_on_file = np.where(is_thick, np.clip(rng.integers(36, 300, n) + 25 * z, 36, 300),
                              np.where(is_thin, np.clip(rng.integers(1, 24, n) + 3 * z, 1, 23), 0)).astype(float)
    tradelines = np.where(is_thick, np.clip(rng.integers(3, 15, n) + np.round(1.5 * z), 3, 15),
                          np.where(is_thin, rng.integers(0, 3, n), 0)).astype(float)
    inquiries_6m = rng.poisson(np.clip(1.0 - 0.35 * z, 0.05, None)).astype(float)
    delinquencies_24m = rng.poisson(np.clip(0.45 - 0.4 * z, 0.0, None)).astype(float)
    months_on_file[is_ntc] = np.nan
    tradelines[is_ntc] = np.nan
    delinquencies_24m[is_ntc] = np.nan
    inquiries_6m[is_ntc] = np.nan  # no file => no inquiry record either

    # ---- cash-flow signals (open-banking style, consented) -----------------------
    bank_linked = rng.random(n) < 0.88
    monthly_income = np.exp(rng.normal(np.log(3600), 0.45, n) + 0.12 * z)
    income_cv = np.clip(0.32 - 0.12 * z + rng.normal(0, 0.10, n), 0.02, 1.2)
    income_months_observed = rng.integers(3, 13, n).astype(float)
    balance_trend = np.clip(0.03 + 0.06 * z + rng.normal(0, 0.06, n), -0.5, 0.5)
    min_balance_ratio = np.clip(0.25 + 0.20 * z + rng.normal(0, 0.15, n), -0.3, 3.0)
    nsf_count_6m = rng.poisson(np.clip(0.55 - 0.45 * z, 0.0, None)).astype(float)
    rent_ontime_ratio = np.clip(rng.beta(6, 2, n) * 0.6 + 0.4 * sigmoid(1.2 * z + rng.normal(0, 0.5, n)), 0, 1)
    utility_ontime_ratio = np.clip(rng.beta(7, 2, n) * 0.5 + 0.5 * sigmoid(1.0 * z + rng.normal(0, 0.6, n)), 0, 1)
    telco_ontime_ratio = np.clip(rng.beta(8, 2, n) * 0.5 + 0.5 * sigmoid(0.9 * z + rng.normal(0, 0.6, n)), 0, 1)
    obligation_ratio = np.clip(0.28 - 0.08 * z + rng.normal(0, 0.10, n), 0.0, 1.2)
    discretionary_ratio = np.clip(0.30 - 0.05 * z + rng.normal(0, 0.10, n), 0.0, 0.9)
    gambling_flag = (rng.random(n) < np.clip(0.06 - 0.03 * z, 0.005, 0.25)).astype(int)

    # ---- application & behavioural signals ------------------------------------
    requested_amount = np.clip(np.round(np.exp(rng.normal(np.log(3000), 0.7, n)) / 50) * 50, 300, 20000)
    session_seconds = np.exp(rng.normal(np.log(420), 0.45, n))
    income_field_edits = rng.poisson(0.6, n)
    paste_ssn = (rng.random(n) < 0.03).astype(int)
    paste_income = (rng.random(n) < 0.05).astype(int)
    device_apps_30d = rng.poisson(0.15, n)
    email_age_days = np.exp(rng.normal(np.log(900), 1.0, n))
    voip_phone = (rng.random(n) < 0.04).astype(int)
    income_inflation = np.clip(rng.normal(1.02, 0.08, n), 0.7, 1.6)  # stated / bank-verified

    # ---- fraud sub-population ---------------------------------------------------
    is_fraud = np.zeros(n, dtype=int)
    fraud_type = np.array(["none"] * n, dtype=object)
    fraud_mask = rng.random(n) < np.where(is_ntc, 0.06, np.where(is_thin, 0.035, 0.012))
    fraud_idx = np.where(fraud_mask)[0]
    kinds = rng.choice(["synthetic_identity", "income_inflation", "device_ring"], size=fraud_idx.size, p=[0.4, 0.4, 0.2])
    for i, k in zip(fraud_idx, kinds):
        is_fraud[i] = 1
        fraud_type[i] = k
        if k == "synthetic_identity":
            paste_ssn[i] = int(rng.random() < 0.75)
            email_age_days[i] = np.exp(rng.normal(np.log(25), 0.6))
            voip_phone[i] = int(rng.random() < 0.6)
            session_seconds[i] = np.exp(rng.normal(np.log(110), 0.35))
            device_apps_30d[i] = rng.poisson(1.2)
        elif k == "income_inflation":
            income_inflation[i] = np.clip(rng.normal(1.75, 0.25), 1.35, 3.0)
            income_field_edits[i] = rng.poisson(2.5) + 1
            paste_income[i] = int(rng.random() < 0.5)
        else:  # device_ring
            device_apps_30d[i] = rng.poisson(4.0) + 2
            session_seconds[i] = np.exp(rng.normal(np.log(75), 0.3))
            email_age_days[i] = np.exp(rng.normal(np.log(40), 0.7))
            paste_ssn[i] = int(rng.random() < 0.5)

    stated_annual_income = np.round(monthly_income * 12 * income_inflation / 100) * 100

    # ---- outcome: 12-month serious delinquency ---------------------------------
    logit = (
        -3.2
        - 1.05 * z
        + 1.6 * (obligation_ratio - 0.28)
        + 1.2 * (income_cv - 0.32)
        + 0.25 * nsf_count_6m
        - 0.9 * (rent_ontime_ratio - 0.75)
        + 0.6 * gambling_flag
        + 0.35 * np.log1p(requested_amount / np.maximum(monthly_income, 200))
        + rng.normal(0, 0.55, n)
    )
    defaulted = (rng.random(n) < sigmoid(logit)).astype(int)
    defaulted[is_fraud == 1] = (rng.random(int((is_fraud == 1).sum())) < 0.85).astype(int)

    df = pd.DataFrame(
        {
            "applicant_id": [f"A{100000 + i}" for i in range(n)],
            # fairness-testing attributes (never model features)
            "age": age,
            "age_band": pd.cut(age, [17, 24, 34, 44, 54, 100], labels=["18-24", "25-34", "35-44", "45-54", "55+"]).astype(str),
            "sex": sex,
            "region": region,
            # segment
            "file_type": file_type,
            "bank_linked": bank_linked.astype(int),
            # bureau
            "bureau_score": bureau_score,
            "months_on_file": months_on_file,
            "tradelines": tradelines,
            "inquiries_6m": inquiries_6m,
            "delinquencies_24m": delinquencies_24m,
            # cash-flow
            "monthly_income": np.round(monthly_income, 2),
            "income_cv": np.round(income_cv, 4),
            "income_months_observed": income_months_observed,
            "balance_trend": np.round(balance_trend, 4),
            "min_balance_ratio": np.round(min_balance_ratio, 4),
            "nsf_count_6m": nsf_count_6m,
            "rent_ontime_ratio": np.round(rent_ontime_ratio, 4),
            "utility_ontime_ratio": np.round(utility_ontime_ratio, 4),
            "telco_ontime_ratio": np.round(telco_ontime_ratio, 4),
            "obligation_ratio": np.round(obligation_ratio, 4),
            "discretionary_ratio": np.round(discretionary_ratio, 4),
            "gambling_flag": gambling_flag,
            # application
            "requested_amount": requested_amount,
            "stated_annual_income": stated_annual_income,
            "income_inflation_ratio": np.round(income_inflation, 4),
            # behavioural (captured live in the application form)
            "session_seconds": np.round(session_seconds, 1),
            "income_field_edits": income_field_edits,
            "paste_ssn": paste_ssn,
            "paste_income": paste_income,
            "device_apps_30d": device_apps_30d,
            "email_age_days": np.round(email_age_days, 0),
            "voip_phone": voip_phone,
            # labels
            "is_fraud": is_fraud,
            "fraud_type": fraud_type,
            "defaulted_12m": defaulted,
        }
    )
    df.loc[df.bank_linked == 0, CASHFLOW_COLUMNS] = np.nan
    df.loc[df.bank_linked == 0, "gambling_flag"] = 0
    return df


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    df = generate()
    path = os.path.join(OUT_DIR, "applicants.csv")
    df.to_csv(path, index=False)
    summary = {
        "rows": int(len(df)),
        "file_type_mix": df.file_type.value_counts(normalize=True).round(3).to_dict(),
        "default_rate": round(float(df.defaulted_12m.mean()), 4),
        "default_rate_by_file_type": df.groupby("file_type").defaulted_12m.mean().round(4).to_dict(),
        "fraud_rate": round(float(df.is_fraud.mean()), 4),
        "bank_linked_rate": round(float(df.bank_linked.mean()), 3),
        "ntc_share_by_age_band": df.groupby("age_band")["file_type"].apply(lambda s: float((s == "ntc").mean())).round(3).to_dict(),
    }
    print(json.dumps(summary, indent=2))
    print(f"wrote {path}")
