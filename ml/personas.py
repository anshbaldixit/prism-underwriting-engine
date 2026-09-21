"""
Demo personas for the Prism UI: six applicants that exercise every path of the engine.
Each persona ships with three months of synthetic bank transactions (raw descriptions,
the way an open-banking feed delivers them) so the cash-flow categoriser and feature
pipeline in the backend run on real text, not pre-computed numbers.

Output: backend/src/main/resources/demo/personas.json   Run: python personas.py
"""
from __future__ import annotations

import json
import os
import random
from datetime import date, timedelta

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "backend", "src", "main", "resources", "demo", "personas.json")
START = date(2026, 6, 1)  # three months: June, July, August 2026

rng = random.Random(7)


def month_days(m: int):
    d = date(START.year, START.month + m, 1)
    nxt = date(d.year + (d.month // 12), (d.month % 12) + 1, 1)
    return d, (nxt - timedelta(days=1)).day


def build_transactions(spec: dict) -> list[dict]:
    """spec keys: opening_balance, income[] (desc, amount, day, months|None, kind), rent, utility,
    telco, loans[], card_payment, discretionary_level (0-3), nsf_months[], gambling, subscriptions."""
    txns = []
    balance = spec["opening_balance"]
    for m in range(3):
        first, ndays = month_days(m)
        month_items = []
        for inc in spec["income"]:
            if inc.get("months") is not None and m not in inc["months"]:
                continue
            amt = inc["amount"] * rng.uniform(*inc.get("jitter", (1.0, 1.0)))
            for day in inc["days"]:
                month_items.append((day, inc["desc"], round(amt, 2)))
        if spec.get("rent") and m in spec["rent"].get("months", [0, 1, 2]):
            month_items.append((spec["rent"]["day"], spec["rent"]["desc"], -spec["rent"]["amount"]))
        if spec.get("utility") and m in spec["utility"].get("months", [0, 1, 2]):
            month_items.append((spec["utility"]["day"], spec["utility"]["desc"], -round(spec["utility"]["amount"] * rng.uniform(0.85, 1.15), 2)))
        if spec.get("telco") and m in spec["telco"].get("months", [0, 1, 2]):
            month_items.append((spec["telco"]["day"], spec["telco"]["desc"], -spec["telco"]["amount"]))
        for loan in spec.get("loans", []):
            month_items.append((loan["day"], loan["desc"], -loan["amount"]))
        if spec.get("card_payment"):
            month_items.append((spec["card_payment"]["day"], spec["card_payment"]["desc"], -spec["card_payment"]["amount"]))
        for sub in spec.get("subscriptions", []):
            month_items.append((sub["day"], sub["desc"], -sub["amount"]))
        # groceries weekly
        for wk in range(4):
            month_items.append((min(2 + wk * 7, ndays), rng.choice(["WALMART SUPERCENTER", "KROGER #1182", "ALDI 77", "COSTCO WHSE"]), -round(rng.uniform(55, 140), 2)))
        # discretionary
        lvl = spec.get("discretionary_level", 1)
        for _ in range(lvl * 3):
            month_items.append((rng.randint(1, ndays), rng.choice(["DOORDASH*ORDER", "STARBUCKS 2231", "AMC THEATRES", "UBER EATS", "CHIPOTLE 0442", "AMAZON MKTPLACE", "STEAM PURCHASE", "TICKETMASTER"]), -round(rng.uniform(9, 85), 2)))
        if spec.get("gambling"):
            for _ in range(2):
                month_items.append((rng.randint(1, ndays), rng.choice(["DRAFTKINGS DEPOSIT", "FANDUEL SPORTSBOOK", "BETMGM ONLINE"]), -round(rng.uniform(50, 250), 2)))
        if m in spec.get("nsf_months", []):
            month_items.append((rng.randint(10, ndays), "NSF RETURNED ITEM FEE", -34.00))
            month_items.append((rng.randint(10, ndays), "OVERDRAFT FEE", -35.00))
        if spec.get("atm"):
            month_items.append((rng.randint(1, ndays), "ATM WITHDRAWAL 7-ELEVEN", -round(rng.choice([40, 60, 100]), 2)))
        month_items.sort(key=lambda t: t[0])
        for day, desc, amt in month_items:
            balance = round(balance + amt, 2)
            txns.append({"date": (first + timedelta(days=day - 1)).isoformat(), "description": desc, "amount": amt, "balance_after": balance})
    return txns


PERSONAS = [
    {
        "id": "ntc-gig-worker",
        "name": "Priya - New-to-Credit gig worker",
        "story": "26, no bureau file at all, 14 months of consistent rideshare + delivery income, pays rent and phone on time every month. A bureau-only lender cannot score her.",
        "expected": "APPROVE - hero case for cash-flow underwriting",
        "form": {"fullName": "Priya Raman", "email": "priya.raman@example.com", "phone": "+1-512-555-0142", "employmentType": "SELF_EMPLOYED",
                 "statedAnnualIncome": 41000, "requestedAmount": 2500, "loanPurpose": "Replace laptop for freelance design work", "consentBankData": True, "consentAltData": True},
        "bureau": {"fileType": "ntc"},
        "behaviour": {"sessionSeconds": 510, "incomeFieldEdits": 1, "pasteSsn": False, "pasteIncome": False, "emailAgeDays": 1460, "voipPhone": False, "deviceId": "dev-priya-01"},
        "txn_spec": {"opening_balance": 1420, "income": [
            {"desc": "UBER TECHNOLOGIES DIRECT DEP", "amount": 1580, "days": [3, 17], "jitter": (0.9, 1.1), "kind": "gig"},
            {"desc": "DOORDASH INC PAYOUT", "amount": 310, "days": [10, 24], "jitter": (0.8, 1.2), "kind": "gig"}],
            "rent": {"desc": "ZELLE TO M PATEL RENT", "amount": 1100, "day": 1}, "utility": {"desc": "AUSTIN ENERGY BILLPAY", "amount": 95, "day": 12},
            "telco": {"desc": "T-MOBILE AUTOPAY", "amount": 60, "day": 15}, "subscriptions": [{"desc": "SPOTIFY USA", "amount": 10.99, "day": 6}], "discretionary_level": 1},
    },
    {
        "id": "thin-file-student",
        "name": "Marcus - thin file, irregular income",
        "story": "22, one student card opened 9 months ago, part-time campus job with gaps over summer, two overdraft fees. Not a decline, but not a clear approve either.",
        "expected": "REFER - underwriter review with verification items",
        "form": {"fullName": "Marcus Lee", "email": "mlee2026@example.edu", "phone": "+1-614-555-0199", "employmentType": "PART_TIME",
                 "statedAnnualIncome": 9000, "requestedAmount": 3000, "loanPurpose": "Cover tuition gap this semester", "consentBankData": True, "consentAltData": True},
        "bureau": {"fileType": "thin", "bureauScore": 641, "monthsOnFile": 9, "tradelines": 1, "inquiries6m": 2, "delinquencies24m": 0},
        "behaviour": {"sessionSeconds": 380, "incomeFieldEdits": 2, "pasteSsn": False, "pasteIncome": False, "emailAgeDays": 900, "voipPhone": False, "deviceId": "dev-marcus-01"},
        "txn_spec": {"opening_balance": 260, "income": [
            {"desc": "OHIO STATE UNIV PAYROLL", "amount": 720, "days": [15], "months": [0, 2], "jitter": (0.7, 1.3), "kind": "payroll"},
            {"desc": "VENMO CASHOUT", "amount": 200, "days": [20], "jitter": (0.5, 1.5), "kind": "transfer"}],
            "rent": {"desc": "ZELLE TO J OKAFOR RENT", "amount": 550, "day": 2, "months": [0, 2]}, "telco": {"desc": "VERIZON WIRELESS PMT", "amount": 45, "day": 18},
            "card_payment": {"desc": "DISCOVER CARD PAYMENT", "amount": 35, "day": 22}, "discretionary_level": 2, "nsf_months": [1], "atm": True},
    },
    {
        "id": "prime-thick-file",
        "name": "Elena - prime thick file",
        "story": "41, 15-year bureau history, score 748, salaried, mortgage and auto loan paid like clockwork. The easy case - shows the engine does not over-refer good customers.",
        "expected": "APPROVE - straight-through",
        "form": {"fullName": "Elena Petrova", "email": "elena.p@example.com", "phone": "+1-312-555-0117", "employmentType": "FULL_TIME",
                 "statedAnnualIncome": 94000, "requestedAmount": 6000, "loanPurpose": "Kitchen renovation", "consentBankData": True, "consentAltData": True},
        "bureau": {"fileType": "thick", "bureauScore": 748, "monthsOnFile": 182, "tradelines": 7, "inquiries6m": 0, "delinquencies24m": 0},
        "behaviour": {"sessionSeconds": 620, "incomeFieldEdits": 0, "pasteSsn": False, "pasteIncome": False, "emailAgeDays": 3800, "voipPhone": False, "deviceId": "dev-elena-01"},
        "txn_spec": {"opening_balance": 6200, "income": [{"desc": "ACH CREDIT NORTHWIND LLC PAYROLL", "amount": 3120, "days": [1, 15], "kind": "payroll"}],
                     "rent": {"desc": "WELLS FARGO HOME MTG PYMT", "amount": 1650, "day": 3}, "utility": {"desc": "COMED ELECTRIC BILL", "amount": 140, "day": 9},
                     "telco": {"desc": "AT&T WIRELESS AUTOPAY", "amount": 85, "day": 11}, "loans": [{"desc": "TOYOTA FINANCIAL AUTO LOAN", "amount": 410, "day": 5}],
                     "card_payment": {"desc": "CHASE CARD AUTOPAY", "amount": 600, "day": 20}, "subscriptions": [{"desc": "NETFLIX.COM", "amount": 15.49, "day": 7}], "discretionary_level": 2},
    },
    {
        "id": "stretched-thick-file",
        "name": "Daniel - thick file under strain",
        "story": "38, established bureau file with three recent delinquencies and six inquiries; income that swings month to month, a falling balance, overdraft fees, sports-betting deposits and loan payments eating most of what comes in. A decline the applicant deserves a clear explanation for.",
        "expected": "DECLINE - adverse-action notice with exact reasons",
        "form": {"fullName": "Daniel Ruiz", "email": "druiz@example.com", "phone": "+1-702-555-0163", "employmentType": "FULL_TIME",
                 "statedAnnualIncome": 36000, "requestedAmount": 8000, "loanPurpose": "Consolidate cards", "consentBankData": True, "consentAltData": True},
        "bureau": {"fileType": "thick", "bureauScore": 578, "monthsOnFile": 96, "tradelines": 9, "inquiries6m": 6, "delinquencies24m": 3},
        "behaviour": {"sessionSeconds": 300, "incomeFieldEdits": 1, "pasteSsn": False, "pasteIncome": False, "emailAgeDays": 2100, "voipPhone": False, "deviceId": "dev-daniel-01"},
        "txn_spec": {"opening_balance": 2600, "income": [{"desc": "CAESARS ENTERTAINMENT PAYROLL", "amount": 1500, "days": [5, 20], "jitter": (0.55, 1.15), "kind": "payroll"},
                                                             {"desc": "CAESARS ENTERTAINMENT PAYROLL", "amount": 900, "days": [12], "months": [0], "kind": "payroll"}],
                     "rent": {"desc": "ZELLE TO SUNRISE APTS RENT", "amount": 1450, "day": 4, "months": [0, 2]}, "utility": {"desc": "NV ENERGY PAYMENT", "amount": 160, "day": 14, "months": [1]},
                     "telco": {"desc": "CRICKET WIRELESS", "amount": 55, "day": 17, "months": [1]}, "loans": [{"desc": "ONEMAIN FINANCIAL LOAN PMT", "amount": 390, "day": 8}, {"desc": "AFFIRM INC INSTALLMENT", "amount": 145, "day": 12}, {"desc": "SANTANDER AUTO LOAN PMT", "amount": 465, "day": 9}],
                     "card_payment": {"desc": "CAPITAL ONE CARD PMT", "amount": 320, "day": 21}, "discretionary_level": 3, "gambling": True, "nsf_months": [1, 2], "atm": True},
    },
    {
        "id": "synthetic-identity",
        "name": "\"Alex Morgan\" - synthetic identity",
        "story": "Claims a thin file, but the identity number was pasted, the email is 12 days old, the phone is VoIP and the same device submitted four applications this month. Whatever the cash flow says, this never reaches pricing.",
        "expected": "BLOCK by fraud gate - manual fraud review",
        "form": {"fullName": "Alex Morgan", "email": "alex.morgan.9931@example.net", "phone": "+1-929-555-0100", "employmentType": "FULL_TIME",
                 "statedAnnualIncome": 78000, "requestedAmount": 9500, "loanPurpose": "Business inventory", "consentBankData": True, "consentAltData": True},
        "bureau": {"fileType": "thin", "bureauScore": None, "monthsOnFile": 4, "tradelines": 1, "inquiries6m": 3, "delinquencies24m": 0},
        "behaviour": {"sessionSeconds": 84, "incomeFieldEdits": 0, "pasteSsn": True, "pasteIncome": True, "emailAgeDays": 12, "voipPhone": True, "deviceId": "dev-ring-7f3a", "deviceAppsLast30dSeed": 4},
        "txn_spec": {"opening_balance": 2900, "income": [{"desc": "ACH CREDIT APEX HOLDINGS PAYROLL", "amount": 3250, "days": [1, 15], "kind": "payroll"}],
                     "rent": {"desc": "ZELLE TO K NGUYEN RENT", "amount": 1300, "day": 2}, "telco": {"desc": "MINT MOBILE", "amount": 30, "day": 10}, "discretionary_level": 1},
    },
    {
        "id": "income-inflator",
        "name": "Sofia - stated income does not match",
        "story": "Genuine thick-file customer, but stated $92k on the form after editing the field four times; the bank feed shows about $46k. Not fraud-blocked outright - a step-up verification with a document request.",
        "expected": "STEP-UP verification - refer with income proof request",
        "form": {"fullName": "Sofia Alvarez", "email": "sofia.alv@example.com", "phone": "+1-305-555-0188", "employmentType": "FULL_TIME",
                 "statedAnnualIncome": 92000, "requestedAmount": 12000, "loanPurpose": "Medical expenses", "consentBankData": True, "consentAltData": True},
        "bureau": {"fileType": "thick", "bureauScore": 688, "monthsOnFile": 130, "tradelines": 5, "inquiries6m": 1, "delinquencies24m": 0},
        "behaviour": {"sessionSeconds": 410, "incomeFieldEdits": 4, "pasteSsn": False, "pasteIncome": True, "emailAgeDays": 2600, "voipPhone": False, "deviceId": "dev-sofia-01"},
        "txn_spec": {"opening_balance": 1900, "income": [{"desc": "BAPTIST HEALTH PAYROLL DEP", "amount": 1910, "days": [7, 22], "jitter": (0.95, 1.05), "kind": "payroll"}],
                     "rent": {"desc": "ZELLE TO R GOMEZ RENT", "amount": 1250, "day": 1}, "utility": {"desc": "FPL ELECTRIC AUTOPAY", "amount": 130, "day": 13},
                     "telco": {"desc": "T-MOBILE AUTOPAY", "amount": 70, "day": 16}, "card_payment": {"desc": "CITI CARD PAYMENT", "amount": 250, "day": 24}, "discretionary_level": 2},
    },
]


if __name__ == "__main__":
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out = []
    for p in PERSONAS:
        rec = {k: v for k, v in p.items() if k != "txn_spec"}
        rec["transactions"] = build_transactions(p["txn_spec"])
        out.append(rec)
    with open(OUT, "w") as f:
        json.dump(out, f, indent=1)
    for p in out:
        print(p["id"], len(p["transactions"]), "transactions; final balance", p["transactions"][-1]["balance_after"])
    print("wrote", OUT)
