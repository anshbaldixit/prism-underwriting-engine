"""
Prism model training, evaluation, fairness testing and export.

What this produces
  * A Weight-of-Evidence (WoE) logistic-regression *scorecard* - the interpretable,
    regulator-friendly model family used across consumer lending. Every prediction is
    an additive sum of per-feature contributions, so adverse-action reason codes are
    exact, not approximated.
  * A bureau-only baseline (what a traditional lender can do) and a gradient-boosting
    benchmark (the accuracy ceiling), so the explainability trade-off is *measured*.
  * A policy simulation: approval rates at a fixed portfolio bad-rate, by file type.
  * A fairness harness: adverse-impact ratios and equal-opportunity gaps on attributes
    that are never model features.
  * A rule-based fraud gate evaluated on labelled fraud.
  * JSON artefacts consumed by the Spring Boot engine + PNG figures for the deck.

Run: python train_and_export.py   (after generate_data.py)
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, roc_curve
from sklearn.model_selection import train_test_split

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "ml", "data", "applicants.csv")
MODEL_OUT = os.path.join(ROOT, "backend", "src", "main", "resources", "model")
FIG_OUT = os.path.join(ROOT, "docs", "figures")
SEED = 42

# ---------------------------------------------------------------------------
# Feature catalogue: name -> (label, modality, reason code)
# Behavioural / veracity signals deliberately live in the FRAUD GATE, not the
# credit scorecard: "how you typed" must never change your price, only trigger
# verification.
# ---------------------------------------------------------------------------
BUREAU = {
    "bureau_score": ("Credit bureau score", "R01"),
    "months_on_file": ("Length of credit history", "R02"),
    "tradelines": ("Number of credit accounts", "R03"),
    "inquiries_6m": ("Recent credit inquiries", "R04"),
    "delinquencies_24m": ("Recent delinquencies on file", "R05"),
}
CASHFLOW = {
    "income_cv": ("Income stability (month-to-month volatility)", "R06"),
    "monthly_income": ("Verified monthly income", "R07"),
    "income_months_observed": ("Months of verified income history", "R08"),
    "balance_trend": ("Account balance trend", "R09"),
    "min_balance_ratio": ("Minimum balance buffer relative to income", "R10"),
    "nsf_count_6m": ("Overdraft / insufficient-funds events", "R11"),
    "rent_ontime_ratio": ("Rent payment regularity", "R12"),
    "utility_ontime_ratio": ("Utility payment regularity", "R13"),
    "telco_ontime_ratio": ("Phone bill payment regularity", "R14"),
    "obligation_ratio": ("Existing obligations relative to income", "R15"),
    "discretionary_ratio": ("Share of spending that is discretionary", "R16"),
    "gambling_flag": ("Gambling-related transactions", "R17"),
}
APPLICATION = {
    "amount_to_income": ("Requested amount relative to income", "R18"),
}
MODALITY = {**{k: "bureau" for k in BUREAU}, **{k: "cashflow" for k in CASHFLOW}, **{k: "application" for k in APPLICATION}}
CATALOGUE = {**BUREAU, **CASHFLOW, **APPLICATION}
PRISM_FEATURES = list(CATALOGUE.keys())
BASELINE_FEATURES = list(BUREAU.keys())

# Reg B "principal reasons" - consumer-facing wording the LLM is allowed to elaborate
# on but never contradict. Kept here so the model and the notice share one source.
REASON_TEXT = {
    "R01": "Credit bureau score is below our threshold",
    "R02": "Length of credit history is limited",
    "R03": "Too few established credit accounts",
    "R04": "Number of recent credit inquiries",
    "R05": "Delinquent past or present credit obligations",
    "R06": "Income is irregular from month to month",
    "R07": "Verified income is insufficient for the amount requested",
    "R08": "Insufficient months of verified income",
    "R09": "Declining account balance trend",
    "R10": "Low minimum account balance relative to income",
    "R11": "Recent overdraft or insufficient-funds activity",
    "R12": "Irregular rent payments",
    "R13": "Irregular utility payments",
    "R14": "Irregular phone bill payments",
    "R15": "Existing financial obligations are high relative to income",
    "R16": "High share of discretionary spending",
    "R17": "Gambling-related account activity",
    "R18": "Amount requested is high relative to income",
    # Reasons for *absent* evidence - a no-file applicant is not "below threshold", they are unscored on that modality.
    "R19": "No credit bureau file or insufficient credit history to evaluate",
    "R20": "Cash-flow information was not available (no linked bank account)",
}
MISSING_REASON = {"bureau": "R19", "cashflow": "R20", "application": "R18"}
MIN_REASON_CONTRIBUTION = 0.02  # logit units; below this a factor is noise, not a principal reason

# Palette (dataviz reference instance, light mode). Fixed order, never cycled:
#   slot1 blue = Prism, slot2 orange = bureau-only baseline, slot3 aqua = GBM benchmark
C_PRISM, C_BASE, C_GBM, C_YELLOW = "#2a78d6", "#eb6834", "#1baf7a", "#eda100"
C_TEXT, C_TEXT2, C_GRID, C_SURFACE = "#0b0b0b", "#52514e", "#e6e5e1", "#ffffff"


# ---------------------------------------------------------------------------
# WoE binning
# ---------------------------------------------------------------------------
class WoeBinner:
    """Quantile (or unique-value) bins with Laplace-smoothed Weight of Evidence.
    Convention: WoE = ln(P(bin|bad) / P(bin|good)) -> positive means riskier."""

    def __init__(self, max_bins: int = 6):
        self.max_bins = max_bins
        self.bins: dict[str, list[dict]] = {}
        self.missing_woe: dict[str, float] = {}
        self.iv: dict[str, float] = {}

    def fit(self, X: pd.DataFrame, y: pd.Series) -> "WoeBinner":
        bad_tot, good_tot = float(y.sum()), float((1 - y).sum())
        for col in X.columns:
            x = X[col]
            present = x.notna()
            edges = self._edges(x[present])
            k = len(edges) + 1 + 1  # bins + missing
            bins, iv = [], 0.0
            for lo, hi in self._ranges(edges):
                m = present & (x >= lo if lo is not None else True) & (x < hi if hi is not None else True)
                woe, iv_i = self._woe(y[m], bad_tot, good_tot, k)
                bins.append({"lo": lo, "hi": hi, "woe": round(woe, 4), "count": int(m.sum())})
                iv += iv_i
            woe_m, iv_m = self._woe(y[~present], bad_tot, good_tot, k) if (~present).sum() >= 30 else (0.0, 0.0)
            self.bins[col] = bins
            self.missing_woe[col] = round(woe_m, 4)
            self.iv[col] = round(iv + iv_m, 4)
        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        out = pd.DataFrame(index=X.index)
        for col, bins in self.bins.items():
            x = X[col]
            w = pd.Series(self.missing_woe[col], index=X.index, dtype=float)
            for b in bins:
                m = x.notna() & (x >= b["lo"] if b["lo"] is not None else True) & (x < b["hi"] if b["hi"] is not None else True)
                w[m] = b["woe"]
            out[col] = w
        return out

    def _edges(self, x: pd.Series) -> list[float]:
        uniq = np.unique(x)
        if len(uniq) <= self.max_bins:
            return [float(v) for v in uniq[1:]]
        qs = np.quantile(x, np.linspace(0, 1, self.max_bins + 1)[1:-1])
        return [float(v) for v in np.unique(np.round(qs, 4))]

    @staticmethod
    def _ranges(edges: list[float]):
        lo = None
        for e in edges:
            yield lo, e
            lo = e
        yield lo, None

    @staticmethod
    def _woe(y_bin: pd.Series, bad_tot: float, good_tot: float, k: int) -> tuple[float, float]:
        bad, good = float(y_bin.sum()), float((1 - y_bin).sum())
        p_bad = (bad + 0.5) / (bad_tot + 0.5 * k)
        p_good = (good + 0.5) / (good_tot + 0.5 * k)
        woe = float(np.log(p_bad / p_good))
        return woe, (p_bad - p_good) * woe


# ---------------------------------------------------------------------------
# Metrics helpers
# ---------------------------------------------------------------------------
def ks_stat(y: np.ndarray, p: np.ndarray) -> float:
    fpr, tpr, _ = roc_curve(y, p)
    return float(np.max(tpr - fpr))


def summarize(y: np.ndarray, p: np.ndarray) -> dict:
    ok = ~np.isnan(p)
    if ok.sum() == 0:
        return {"auc": None, "gini": None, "ks": None, "n": int(len(y)), "note": "unscoreable"}
    y, p = y[ok], p[ok]
    if len(np.unique(y)) < 2:
        return {"auc": None, "gini": None, "ks": None, "n": int(len(y))}
    auc = roc_auc_score(y, p)
    return {"auc": round(float(auc), 4), "gini": round(float(2 * auc - 1), 4), "ks": round(ks_stat(y, p), 4), "n": int(len(y))}


def policy_cutoff(pd_hat: np.ndarray, y: np.ndarray, target_bad_rate: float) -> float:
    """Largest PD cut-off such that the approved book's realised bad rate <= target.
    NaN PDs (unscoreable applicants) are never approved. Ties are admitted all-or-nothing."""
    scorable = ~np.isnan(pd_hat)
    pd_hat, y = pd_hat[scorable], y[scorable]
    order = np.argsort(pd_hat, kind="stable")
    p_sorted, y_sorted = pd_hat[order], y[order]
    cum_bad = np.cumsum(y_sorted) / np.arange(1, len(y) + 1)
    best = None
    i = 0
    while i < len(p_sorted):
        j = i
        while j + 1 < len(p_sorted) and p_sorted[j + 1] == p_sorted[i]:
            j += 1
        if cum_bad[j] <= target_bad_rate:
            best = float(p_sorted[j])
        else:
            break
        i = j + 1
    return best if best is not None else float(p_sorted[0]) - 1e-9


def score_from_logit(logit: np.ndarray, base_score=600, base_odds=30, pdo=20) -> np.ndarray:
    factor = pdo / np.log(2)
    offset = base_score - factor * np.log(base_odds)
    return np.round(offset - factor * logit).astype(int)


# ---------------------------------------------------------------------------
# Fraud gate (mirrors backend FraudGateService; the JSON below is its source of truth)
# ---------------------------------------------------------------------------
FRAUD_RULES = [
    {"id": "F01", "name": "Stated income inflated vs bank-verified income", "feature": "income_inflation_ratio", "op": ">", "value": 1.35, "points": 40, "signal": "veracity"},
    {"id": "F02", "name": "Identity number pasted into the form", "feature": "paste_ssn", "op": "==", "value": 1, "points": 25, "signal": "synthetic-identity"},
    {"id": "F03", "name": "Device used for 3+ applications in 30 days", "feature": "device_apps_30d", "op": ">=", "value": 3, "points": 35, "signal": "velocity"},
    {"id": "F04", "name": "Email address created within the last 60 days", "feature": "email_age_days", "op": "<", "value": 60, "points": 15, "signal": "synthetic-identity"},
    {"id": "F05", "name": "VoIP phone number", "feature": "voip_phone", "op": "==", "value": 1, "points": 10, "signal": "synthetic-identity"},
    {"id": "F06", "name": "Application completed in under 2 minutes", "feature": "session_seconds", "op": "<", "value": 120, "points": 15, "signal": "behavioural"},
    {"id": "F07", "name": "Income field edited 3 or more times", "feature": "income_field_edits", "op": ">=", "value": 3, "points": 10, "signal": "behavioural"},
    {"id": "F08", "name": "Income could not be verified (no linked account)", "feature": "bank_linked", "op": "==", "value": 0, "points": 20, "signal": "veracity"},
]
FRAUD_THRESHOLDS = {"step_up": 35, "block": 60}
_OPS = {">": np.greater, ">=": np.greater_equal, "<": np.less, "<=": np.less_equal, "==": np.equal}


def fraud_points(df: pd.DataFrame) -> pd.DataFrame:
    pts = pd.Series(0, index=df.index, dtype=int)
    fired = {}
    for r in FRAUD_RULES:
        x = df[r["feature"]]
        hit = x.notna() & _OPS[r["op"]](x.fillna(0), r["value"])
        pts += hit.astype(int) * r["points"]
        fired[r["id"]] = hit
    outcome = np.where(pts >= FRAUD_THRESHOLDS["block"], "BLOCK", np.where(pts >= FRAUD_THRESHOLDS["step_up"], "STEP_UP", "PASS"))
    return pd.DataFrame({"fraud_points": pts, "fraud_outcome": outcome, **fired})


# ---------------------------------------------------------------------------
# Plot style
# ---------------------------------------------------------------------------
def style_axes(ax, title: str, xlabel: str = "", ylabel: str = ""):
    ax.set_facecolor(C_SURFACE)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(C_GRID)
    ax.tick_params(colors=C_TEXT2, labelsize=9)
    ax.yaxis.grid(True, color=C_GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    ax.set_title(title, loc="left", fontsize=12, color=C_TEXT, fontweight="semibold", pad=12)
    ax.set_xlabel(xlabel, color=C_TEXT2, fontsize=9)
    ax.set_ylabel(ylabel, color=C_TEXT2, fontsize=9)


def new_fig(w=8, h=4.6):
    fig, ax = plt.subplots(figsize=(w, h), dpi=200)
    fig.patch.set_facecolor(C_SURFACE)
    return fig, ax


def save(fig, name: str):
    os.makedirs(FIG_OUT, exist_ok=True)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG_OUT, name), facecolor=C_SURFACE)
    plt.close(fig)


# ---------------------------------------------------------------------------
def main():
    plt.rcParams["font.family"] = "DejaVu Sans"
    df = pd.read_csv(DATA)
    df["amount_to_income"] = df["requested_amount"] / df["stated_annual_income"].clip(lower=1000)

    # Credit model: exclude labelled fraud (handled by the gate, not priced by the scorecard)
    credit = df[df.is_fraud == 0].copy()
    train, hold = train_test_split(credit, test_size=0.3, random_state=SEED, stratify=credit.file_type + credit.defaulted_12m.astype(str))
    y_tr, y_ho = train.defaulted_12m.values, hold.defaulted_12m.values

    # --- Prism scorecard ------------------------------------------------------
    binner = WoeBinner(max_bins=6).fit(train[PRISM_FEATURES], train.defaulted_12m)
    W_tr, W_ho = binner.transform(train[PRISM_FEATURES]), binner.transform(hold[PRISM_FEATURES])
    lr = LogisticRegression(C=1.0, max_iter=2000).fit(W_tr, y_tr)
    logit_ho = lr.decision_function(W_ho)
    p_prism = 1 / (1 + np.exp(-logit_ho))

    # --- Bureau-only baseline -------------------------------------------------
    b_binner = WoeBinner(max_bins=6).fit(train[BASELINE_FEATURES], train.defaulted_12m)
    b_lr = LogisticRegression(C=1.0, max_iter=2000).fit(b_binner.transform(train[BASELINE_FEATURES]), y_tr)
    p_base = b_lr.predict_proba(b_binner.transform(hold[BASELINE_FEATURES]))[:, 1]
    # A bureau-only lender cannot score an applicant with no file at all ("no-hit"):
    # industry practice is an automatic decline or refer. Model that faithfully.
    p_base = np.where((hold.file_type == "ntc").values, np.nan, p_base)

    # --- GBM benchmark (accuracy ceiling, not deployable as-is: no exact reason codes)
    gbm = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.05, max_leaf_nodes=15, random_state=SEED)
    gbm.fit(train[PRISM_FEATURES], y_tr)
    p_gbm = gbm.predict_proba(hold[PRISM_FEATURES])[:, 1]

    # --- Discrimination metrics overall and by segment ------------------------
    metrics = {}
    for name, p in [("bureau_only", p_base), ("prism_scorecard", p_prism), ("gbm_benchmark", p_gbm)]:
        metrics[name] = {"overall": summarize(y_ho, p)}
        for seg in ["thick", "thin", "ntc"]:
            m = (hold.file_type == seg).values
            metrics[name][seg] = summarize(y_ho[m], p[m])

    # --- Policy simulation: approvals at fixed portfolio bad rate -------------
    TARGET_BAD = 0.06
    policy = {"target_portfolio_bad_rate": TARGET_BAD, "models": {}}
    cutoffs = {}
    for name, p in [("bureau_only", p_base), ("prism_scorecard", p_prism), ("gbm_benchmark", p_gbm)]:
        c = policy_cutoff(p, y_ho, TARGET_BAD)
        cutoffs[name] = c
        approved = np.nan_to_num(p, nan=np.inf) <= c
        entry = {
            "pd_cutoff": round(c, 4),
            "approval_rate_overall": round(float(approved.mean()), 4),
            "realised_bad_rate_approved": round(float(y_ho[approved].mean()), 4) if approved.any() else None,
            "by_file_type": {},
        }
        for seg in ["thick", "thin", "ntc"]:
            m = (hold.file_type == seg).values
            a = approved & m
            entry["by_file_type"][seg] = {
                "approval_rate": round(float(a.sum() / m.sum()), 4),
                "realised_bad_rate_approved": round(float(y_ho[a].mean()), 4) if a.any() else None,
            }
        policy["models"][name] = entry
    swap_in = (p_prism <= cutoffs["prism_scorecard"]) & ~(np.nan_to_num(p_base, nan=np.inf) <= cutoffs["bureau_only"])
    policy["swap_in_set"] = {
        "description": "Approved by Prism, declined (or unscoreable) under bureau-only, at the same portfolio bad rate",
        "share_of_applicants": round(float(swap_in.mean()), 4),
        "realised_bad_rate": round(float(y_ho[swap_in].mean()), 4) if swap_in.any() else None,
        "share_ntc": round(float((hold.file_type.values[swap_in] == "ntc").mean()), 4) if swap_in.any() else None,
    }

    # Decision bands for the engine: approve <= c1, refer <= c2, decline above.
    c1 = cutoffs["prism_scorecard"]
    c2 = policy_cutoff(p_prism, y_ho, 0.10)
    decision_policy = {
        "approve_max_pd": round(c1, 4),
        "refer_max_pd": round(c2, 4),
        "notes": "Approve band calibrated to a 6% portfolio bad rate on hold-out; refer band to 10%. Above that: decline.",
        "limit_multipliers_of_monthly_income": [{"max_pd": 0.03, "multiplier": 3.0}, {"max_pd": 0.06, "multiplier": 2.0}, {"max_pd": 1.0, "multiplier": 1.0}],
        "apr_tiers": [{"min_score": 680, "apr": 14.99}, {"min_score": 640, "apr": 19.99}, {"min_score": 0, "apr": 24.99}],
        "min_evidence": "NTC applicants without a linked bank account are referred (not declined) with an invitation to link an account.",
    }

    # --- Fairness harness (attributes never used as features) ------------------
    fairness = {"note": "Adverse Impact Ratio (AIR) = min group approval rate / max group approval rate; 4/5ths rule flags AIR < 0.80. Equal-opportunity gap = max TPR - min TPR among non-defaulters.", "attributes": {}}
    good = y_ho == 0
    for attr in ["age_band", "sex", "region"]:
        groups = sorted(hold[attr].unique())
        fairness["attributes"][attr] = {}
        for name, p in [("bureau_only", p_base), ("prism_scorecard", p_prism)]:
            approved = np.nan_to_num(p, nan=np.inf) <= cutoffs[name]
            rates, tprs = {}, {}
            for g in groups:
                m = (hold[attr] == g).values
                rates[g] = round(float(approved[m].mean()), 4)
                tprs[g] = round(float(approved[m & good].mean()), 4)
            air = round(min(rates.values()) / max(rates.values()), 4) if max(rates.values()) > 0 else None
            fairness["attributes"][attr][name] = {"approval_rate": rates, "tpr_non_defaulters": tprs, "adverse_impact_ratio": air,
                                                   "equal_opportunity_gap": round(max(tprs.values()) - min(tprs.values()), 4),
                                                   "passes_four_fifths": bool(air is not None and air >= 0.8)}

    # --- Fraud gate evaluation (full population, fraud included) ---------------
    fg = fraud_points(df)
    is_f = df.is_fraud.values == 1
    flagged = fg.fraud_outcome.values != "PASS"
    blocked = fg.fraud_outcome.values == "BLOCK"
    fraud_eval = {
        "fraud_prevalence": round(float(is_f.mean()), 4),
        "block": {"precision": round(float(is_f[blocked].mean()), 4), "recall": round(float(blocked[is_f].mean()), 4)},
        "flagged_any": {"precision": round(float(is_f[flagged].mean()), 4), "recall": round(float(flagged[is_f].mean()), 4)},
        "friction_on_legitimate": {"step_up_rate": round(float((fg.fraud_outcome.values[~is_f] == "STEP_UP").mean()), 4),
                                    "block_rate": round(float(blocked[~is_f].mean()), 4)},
        "recall_by_fraud_type": {k: round(float(flagged[(df.fraud_type == k).values].mean()), 4) for k in ["synthetic_identity", "income_inflation", "device_ring"]},
    }

    # --- Contributions & reason codes on hold-out (for the historical sample) --
    coef = dict(zip(PRISM_FEATURES, lr.coef_[0]))
    contrib = W_ho.mul(pd.Series(coef))
    score_ho = score_from_logit(logit_ho)

    def top_reasons(row_contrib: pd.Series, row_features: pd.Series, k=4):
        """Mirror of ScorecardEngine: aggregate adverse contributions by reason code, mapping features that were
        not observed to the modality's missing-data code, drop noise, return the top k codes."""
        by_code: dict[str, float] = {}
        for f, c in row_contrib.items():
            if c <= 0:
                continue
            code = MISSING_REASON[MODALITY[f]] if pd.isna(row_features[f]) else CATALOGUE[f][1]
            by_code[code] = by_code.get(code, 0.0) + float(c)
        ranked = sorted(((v, c) for c, v in by_code.items() if v > MIN_REASON_CONTRIBUTION), reverse=True)
        return [c for _, c in ranked[:k]]

    hold = hold.assign(pd_hat=p_prism, score=score_ho,
                       decision=np.where(p_prism <= c1, "APPROVE", np.where(p_prism <= c2, "REFER", "DECLINE")))
    sample = hold.sample(n=min(1500, len(hold)), random_state=SEED)
    historical = []
    for idx, r in sample.iterrows():
        rec = {"applicant_id": r.applicant_id, "file_type": r.file_type, "bank_linked": int(r.bank_linked),
               "score": int(r.score), "pd": round(float(r.pd_hat), 4), "decision": r.decision,
               "defaulted_12m": int(r.defaulted_12m), "reason_codes": top_reasons(contrib.loc[idx], r),
               "features": {f: (None if pd.isna(r[f]) else round(float(r[f]), 4)) for f in PRISM_FEATURES + ["requested_amount", "stated_annual_income"]}}
        historical.append(rec)

    # ------------------------------------------------------------------ figures
    # 1. ROC curves: overall + NTC segment
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.4), dpi=200)
    fig.patch.set_facecolor(C_SURFACE)
    for ax, seg, ttl in [(axes[0], None, "All applicants"), (axes[1], "ntc", "New-to-Credit only")]:
        m = np.ones(len(y_ho), bool) if seg is None else (hold.file_type == seg).values
        for name, p, col in [("Prism scorecard", p_prism, C_PRISM), ("Bureau-only baseline", p_base, C_BASE), ("GBM benchmark", p_gbm, C_GBM)]:
            ok = m & ~np.isnan(p)
            if ok.sum() == 0:
                ax.plot([], [], color=col, linewidth=2, label=f"{name}  (cannot score: no file)")
                continue
            fpr, tpr, _ = roc_curve(y_ho[ok], p[ok])
            auc = roc_auc_score(y_ho[ok], p[ok])
            ax.plot(fpr, tpr, color=col, linewidth=2, label=f"{name}  AUC {auc:.3f}")
        ax.plot([0, 1], [0, 1], color=C_GRID, linewidth=1, linestyle="--")
        style_axes(ax, f"ROC - {ttl}", "False positive rate", "True positive rate")
        ax.legend(frameon=False, fontsize=8, loc="lower right", labelcolor=C_TEXT2)
    save(fig, "roc_curves.png")

    # 2. Approval lift by file type at equal portfolio bad rate
    fig, ax = new_fig(8, 4.4)
    segs = ["thick", "thin", "ntc"]
    labels = ["Thick file", "Thin file", "New-to-Credit"]
    x = np.arange(len(segs))
    wbar = 0.36
    base_rates = [policy["models"]["bureau_only"]["by_file_type"][s]["approval_rate"] * 100 for s in segs]
    prism_rates = [policy["models"]["prism_scorecard"]["by_file_type"][s]["approval_rate"] * 100 for s in segs]
    b1 = ax.bar(x - wbar / 2 - 0.01, base_rates, wbar, color=C_BASE, label="Bureau-only baseline", linewidth=0)
    b2 = ax.bar(x + wbar / 2 + 0.01, prism_rates, wbar, color=C_PRISM, label="Prism (bureau + cash-flow)", linewidth=0)
    for bars in (b1, b2):
        for b in bars:
            ax.text(b.get_x() + b.get_width() / 2, b.get_height() + 1, f"{b.get_height():.0f}%", ha="center", va="bottom", fontsize=9, color=C_TEXT2)
    ax.set_xticks(x, labels)
    ax.set_ylim(0, max(prism_rates + base_rates) * 1.25)
    style_axes(ax, f"Approval rate at the same {TARGET_BAD:.0%} portfolio bad rate", "", "Approval rate (%)")
    ax.legend(frameon=False, fontsize=9, loc="upper right", labelcolor=C_TEXT2)
    save(fig, "approval_lift.png")

    # 3. Information value by feature (colour = modality, fixed slots)
    iv = pd.Series(binner.iv).sort_values()
    fig, ax = new_fig(8, 6)
    colors = [{"bureau": C_BASE, "cashflow": C_PRISM, "application": C_GBM}[MODALITY[f]] for f in iv.index]
    ax.barh([CATALOGUE[f][0] for f in iv.index], iv.values, color=colors, linewidth=0, height=0.62)
    ax.xaxis.grid(True, color=C_GRID, linewidth=0.8)
    ax.yaxis.grid(False)
    style_axes(ax, "Information value by feature", "Information value (higher = more predictive)", "")
    ax.yaxis.grid(False)
    from matplotlib.patches import Patch
    ax.legend(handles=[Patch(color=C_BASE, label="Bureau"), Patch(color=C_PRISM, label="Cash-flow (open banking)"), Patch(color=C_GBM, label="Application")],
              frameon=False, fontsize=9, loc="lower right", labelcolor=C_TEXT2)
    save(fig, "information_value.png")

    # 4. Fairness: approval rate by age band, baseline vs Prism
    fig, ax = new_fig(8, 4.4)
    bands = ["18-24", "25-34", "35-44", "45-54", "55+"]
    fa = fairness["attributes"]["age_band"]
    x = np.arange(len(bands))
    ax.bar(x - wbar / 2 - 0.01, [fa["bureau_only"]["approval_rate"][b] * 100 for b in bands], wbar, color=C_BASE, label=f"Bureau-only  (AIR {fa['bureau_only']['adverse_impact_ratio']:.2f})", linewidth=0)
    ax.bar(x + wbar / 2 + 0.01, [fa["prism_scorecard"]["approval_rate"][b] * 100 for b in bands], wbar, color=C_PRISM, label=f"Prism  (AIR {fa['prism_scorecard']['adverse_impact_ratio']:.2f})", linewidth=0)
    ax.set_xticks(x, bands)
    style_axes(ax, "Approval rate by age band - age is never a model input", "Age band", "Approval rate (%)")
    ax.legend(frameon=False, fontsize=9, loc="upper left", labelcolor=C_TEXT2)
    save(fig, "fairness_age.png")

    # 5. Calibration (reliability) of Prism PD
    fig, ax = new_fig(6, 4.6)
    bins = np.quantile(p_prism, np.linspace(0, 1, 11))
    idx = np.clip(np.searchsorted(bins, p_prism, side="right") - 1, 0, 9)
    mp = [p_prism[idx == i].mean() for i in range(10)]
    ob = [y_ho[idx == i].mean() for i in range(10)]
    ax.plot([0, max(mp) * 1.05], [0, max(mp) * 1.05], color=C_GRID, linestyle="--", linewidth=1)
    ax.plot(mp, ob, color=C_PRISM, linewidth=2, marker="o", markersize=5)
    style_axes(ax, "Calibration - predicted vs realised default rate (deciles)", "Mean predicted PD", "Realised default rate")
    save(fig, "calibration.png")

    # 6. Fraud gate recall by type and friction on legitimate applicants
    fig, ax = new_fig(8, 4.2)
    cats = ["Synthetic identity", "Income inflation", "Device ring", "Legitimate: step-up", "Legitimate: blocked"]
    vals = [fraud_eval["recall_by_fraud_type"]["synthetic_identity"], fraud_eval["recall_by_fraud_type"]["income_inflation"], fraud_eval["recall_by_fraud_type"]["device_ring"],
            fraud_eval["friction_on_legitimate"]["step_up_rate"], fraud_eval["friction_on_legitimate"]["block_rate"]]
    cols = [C_PRISM, C_PRISM, C_PRISM, C_YELLOW, C_BASE]
    bars = ax.bar(cats, [v * 100 for v in vals], color=cols, linewidth=0, width=0.6)
    for b in bars:
        ax.text(b.get_x() + b.get_width() / 2, b.get_height() + 1, f"{b.get_height():.1f}%", ha="center", fontsize=9, color=C_TEXT2)
    ax.set_ylim(0, 110)
    ax.tick_params(axis="x", labelsize=8)
    style_axes(ax, "Fraud gate: share flagged (STEP_UP or BLOCK) by population", "", "% of population")
    save(fig, "fraud_gate.png")

    # 7. Score distribution by outcome
    fig, ax = new_fig(8, 4.2)
    ax.hist(score_ho[y_ho == 0], bins=40, color=C_PRISM, alpha=0.85, label="Repaid", linewidth=0)
    ax.hist(score_ho[y_ho == 1], bins=40, color=C_BASE, alpha=0.85, label="Defaulted", linewidth=0)
    style_axes(ax, "Prism score distribution by 12-month outcome (hold-out)", "Score (600 = 30:1 odds, 20 points to double)", "Applicants")
    ax.legend(frameon=False, fontsize=9, labelcolor=C_TEXT2)
    save(fig, "score_distribution.png")

    # ------------------------------------------------------------------ exports
    os.makedirs(MODEL_OUT, exist_ok=True)
    trained_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    scorecard = {
        "model_id": "prism-scorecard",
        "version": "1.0.0",
        "trained_at": trained_at,
        "algorithm": "Weight-of-Evidence binned logistic regression (additive scorecard)",
        "target": "12-month serious delinquency",
        "intercept": round(float(lr.intercept_[0]), 6),
        "scaling": {"base_score": 600, "base_odds": 30, "pdo": 20},
        "features": [
            {"name": f, "label": CATALOGUE[f][0], "modality": MODALITY[f], "reason_code": CATALOGUE[f][1],
             "missing_reason_code": MISSING_REASON[MODALITY[f]],
             "coefficient": round(float(coef[f]), 6), "information_value": binner.iv[f],
             "missing_woe": binner.missing_woe[f], "bins": binner.bins[f]}
            for f in PRISM_FEATURES
        ],
        "reason_codes": REASON_TEXT,
        "min_reason_contribution": MIN_REASON_CONTRIBUTION,
        "decision_policy": decision_policy,
    }
    with open(os.path.join(MODEL_OUT, "scorecard.json"), "w") as f:
        json.dump(scorecard, f, indent=2)
    with open(os.path.join(MODEL_OUT, "fraud_rules.json"), "w") as f:
        json.dump({"version": "1.0.0", "thresholds": FRAUD_THRESHOLDS, "rules": FRAUD_RULES,
                   "outcomes": {"PASS": "No action", "STEP_UP": "Verify identity / income before decision", "BLOCK": "Decline pending manual fraud review"}}, f, indent=2)
    model_card = {
        "model_name": "Prism Multi-Modal Underwriting Scorecard",
        "version": "1.0.0",
        "trained_at": trained_at,
        "intended_use": "Assist underwriting of small-ticket unsecured consumer credit for applicants with thick, thin or no bureau file. Produces a probability of 12-month serious delinquency, an additive score and exact adverse-action reason codes.",
        "not_intended_for": ["Automated decline of applicants without human-review path", "Pricing based on behavioural (typing / device) signals", "Use outside the population it was calibrated on without recalibration"],
        "training_data": {"source": "Synthetic population generated by ml/generate_data.py - no real customer data", "rows_total": int(len(df)), "rows_credit_model": int(len(credit)), "holdout_share": 0.3,
                          "file_type_mix": df.file_type.value_counts(normalize=True).round(3).to_dict(), "labelled_fraud_excluded_from_credit_model": True},
        "features_by_modality": {"bureau": list(BUREAU), "cashflow": list(CASHFLOW), "application": list(APPLICATION)},
        "excluded_attributes": {"age_band": "fairness testing only", "sex": "fairness testing only", "region": "fairness testing only",
                                "behavioural_signals": "fraud gate only - never affect price or limit"},
        "performance": metrics,
        "policy_simulation": policy,
        "fairness": fairness,
        "fraud_gate": fraud_eval,
        "limitations": [
            "Synthetic data: absolute metric values demonstrate the method, not production performance.",
            "Cash-flow features depend on consented bank linking; unlinked applicants are referred rather than declined.",
            "Scorecard is linear in WoE space; interactions are not modelled (GBM benchmark quantifies the cost).",
            "Fairness is tested on three proxies only; a production deployment would add BISG-style proxy analysis and ongoing monitoring.",
        ],
        "monitoring": ["Population stability index on score distribution (weekly)", "Realised bad rate vs predicted PD by decile (monthly)", "AIR by proxy group on decisions (monthly)", "Override rate and override outcomes by underwriter (monthly)"],
    }
    with open(os.path.join(MODEL_OUT, "model_card.json"), "w") as f:
        json.dump(model_card, f, indent=2)
    with open(os.path.join(MODEL_OUT, "historical_sample.json"), "w") as f:
        json.dump(historical, f)

    print(json.dumps({"metrics": metrics, "policy": policy, "fairness_age": fairness["attributes"]["age_band"], "fraud": fraud_eval}, indent=2))
    print("exports ->", MODEL_OUT, "| figures ->", FIG_OUT)


if __name__ == "__main__":
    main()
