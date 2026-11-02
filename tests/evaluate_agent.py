"""Run separately from service load tests: python tests/evaluate_agent.py."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "services/agent-service"))
from domain import parse_request

CASES = [
    ("2만원 두 명 한식 점심", 20000, 2),
    ("15,000원 혼자 점심", 15000, 1),
    ("2.5만원 3인분", 25000, 3),
    ("점심 추천", None, None),
    ("3만원 네 명 땅콩 제외", 30000, 4),
]
correct = 0
for text, budget, people in CASES:
    result = parse_request({"message": text})
    ok = (result.budgetKrw, result.people) == (budget, people)
    correct += ok
    print({"case": text, "passed": ok})
print({"fixture_extraction_accuracy": correct / len(CASES), "cases": len(CASES)})
sys.exit(0 if correct == len(CASES) else 1)
