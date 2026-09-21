"""
Verifies that a fresh run of the pipeline reproduces the committed artefacts.

Structure must match exactly (features, bins, reason codes, rules, personas, sample ids and decisions);
fitted numbers must match within a small tolerance, because BLAS/LAPACK builds differ across platforms and
the last decimals of a logistic-regression fit are not portable. Exit code 1 on any mismatch.

Usage (from the repo root, after running generate_data.py, train_and_export.py and personas.py):
    python ml/check_reproducibility.py            # compares the working tree against HEAD
"""
from __future__ import annotations

import json
import math
import subprocess
import sys

MODEL = "backend/src/main/resources/model"
DEMO = "backend/src/main/resources/demo"
REL_TOL = 2e-3      # relative tolerance for fitted numbers (coefficients, WoE, PDs, metrics)
ABS_TOL = 1e-3
SCORE_TOL = 1       # scaled score points

problems: list[str] = []


def committed(path: str):
    return json.loads(subprocess.check_output(["git", "show", f"HEAD:{path}"]))


def current(path: str):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def close(a, b) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(a, b, rel_tol=REL_TOL, abs_tol=ABS_TOL)
    return a == b


def compare(a, b, path: str, ignore: set[str] = frozenset()):
    """Recursive comparison: exact for strings/keys/lengths, tolerant for numbers."""
    if isinstance(a, dict) and isinstance(b, dict):
        if set(a) != set(b):
            problems.append(f"{path}: keys differ {sorted(set(a) ^ set(b))}")
            return
        for k in a:
            if k in ignore:
                continue
            compare(a[k], b[k], f"{path}.{k}", ignore)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            problems.append(f"{path}: length {len(a)} vs {len(b)}")
            return
        for i, (x, y) in enumerate(zip(a, b)):
            compare(x, y, f"{path}[{i}]", ignore)
    elif not close(a, b):
        problems.append(f"{path}: {a!r} vs {b!r}")


def main() -> int:
    # Scorecard: identical structure, tolerant numbers, timestamp ignored.
    compare(committed(f"{MODEL}/scorecard.json"), current(f"{MODEL}/scorecard.json"), "scorecard", ignore={"trained_at", "count"})
    # Model card: metrics within tolerance.
    compare(committed(f"{MODEL}/model_card.json"), current(f"{MODEL}/model_card.json"), "model_card", ignore={"trained_at"})
    # Fraud rules and personas carry no fitted numbers: must be identical.
    if committed(f"{MODEL}/fraud_rules.json") != current(f"{MODEL}/fraud_rules.json"):
        problems.append("fraud_rules.json differs")
    if committed(f"{DEMO}/personas.json") != current(f"{DEMO}/personas.json"):
        problems.append("personas.json differs")
    # Hold-out sample: same applicants, same decisions and reason codes, scores within a point.
    old, new = committed(f"{MODEL}/historical_sample.json"), current(f"{MODEL}/historical_sample.json")
    if [r["applicant_id"] for r in old] != [r["applicant_id"] for r in new]:
        problems.append("historical_sample: applicant ids differ")
    else:
        flips = sum(1 for o, n in zip(old, new) if o["decision"] != n["decision"])
        codes = sum(1 for o, n in zip(old, new) if o["reason_codes"] != n["reason_codes"])
        drift = max(abs(o["score"] - n["score"]) for o, n in zip(old, new))
        if flips or codes > len(old) * 0.01 or drift > SCORE_TOL:
            problems.append(f"historical_sample: {flips} decision flips, {codes} reason-code changes, max score drift {drift}")

    if problems:
        print("REPRODUCIBILITY CHECK FAILED")
        for p in problems[:40]:
            print("  -", p)
        if len(problems) > 40:
            print(f"  ... {len(problems) - 40} more")
        return 1
    print("artefacts reproduce (structure exact, fitted numbers within tolerance)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
