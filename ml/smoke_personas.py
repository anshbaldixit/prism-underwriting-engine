"""
Smoke test: submit every demo persona to a running backend and print the engine's decision next to the
persona's expected outcome. Usage:  python smoke_personas.py [http://localhost:8080] [password]
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("PRISM_SEED_PASSWORD", "prism-demo-2026")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def call(path: str, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST" if body is not None else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())


def wait_until_ready(timeout_s: int = 600) -> None:
    """The readiness probe turns UP only after start-up seeding (exemplars, policy chunks, historical applicants) is done."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if call("/actuator/health/readiness").get("status") == "UP":
                return
        except Exception:
            pass
        time.sleep(2)
    raise SystemExit("backend did not become ready in time")


def main():
    wait_until_ready()
    token = call("/api/auth/login", {"username": "applicant", "password": PASSWORD})["token"]
    personas = json.load(open(os.path.join(ROOT, "backend", "src", "main", "resources", "demo", "personas.json")))
    print(f"{'persona':24} {'expected':44} {'decision':8} {'basis':22} {'score':>5} {'PD':>6} {'fraud':8} {'limit':>7} {'ms':>4}")
    for p in personas:
        f = p["form"]
        body = {
            "applicant": {"fullName": f["fullName"], "email": f["email"], "phone": f["phone"], "nationalId": "123-45-6789",
                          "employmentType": f["employmentType"], "statedAnnualIncome": f["statedAnnualIncome"]},
            "loan": {"requestedAmount": f["requestedAmount"], "purpose": f["loanPurpose"]},
            "consent": {"bankData": f["consentBankData"], "altData": f["consentAltData"]},
            "bureau": p["bureau"],
            "behaviour": {**p["behaviour"], "deviceId": f"{p['behaviour']['deviceId']}-{os.urandom(3).hex()}"},
            "transactions": [{"date": t["date"], "description": t["description"], "amount": t["amount"], "balanceAfter": t["balance_after"]} for t in p["transactions"]],
            "personaId": p["id"],
        }
        d = call("/api/applications", body, token)["decision"]
        limit = "" if d["creditLimit"] is None else f"{d['creditLimit']:.0f}"
        print(f"{p['id']:24} {p['expected'][:44]:44} {d['decision']:8} {d['basis']:22} {d['score']:>5} {d['pd']:>6.3f} {d['fraud']['outcome']:8} {limit:>7} {d['scoringLatencyMs']:>4}")
        print("    reasons:", ", ".join(f"{r['code']} {r['text']}" for r in d["reasonCodes"]) or "-")
        print("    notice :", d["notice"]["summary"][:150], f"[{d['noticeProvider']}, validated={d['noticeValidated']}]")


if __name__ == "__main__":
    main()
