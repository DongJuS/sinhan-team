"""Live local-Qwen evaluation; separate from deterministic CI and load tests."""

import asyncio
import json
from pathlib import Path
import sys
import time

# Works both in the Agent image (/app) and a checked-out repository.
root = Path(__file__).resolve().parents[1]
if (root / "services/agent-service").exists():
    sys.path[:0] = [
        str(root / "services/agent-service"),
        str(root / "services/python-common"),
    ]

import httpx
from graphs import AgentWorkflow
from local_model import LocalQwen, LocalSettings

CATALOG = [
    {
        "id": "m",
        "name": "시연 식당",
        "regions": ["SEOUL"],
        "cuisine": "한식",
        "deliveryFeeKrw": 1000,
        "deliveryMinutes": 20,
        "revision": 1,
        "localCurrencyAccepted": True,
        "menus": [
            {
                "id": "rice",
                "name": "비빔밥",
                "priceKrw": 7000,
                "stock": 10,
                "ingredients": ["계란"],
            }
        ],
    }
]


class Store:
    def save(self, *args):
        pass


class Service:
    async def headers(self, client, request_id):
        return {"X-Request-ID": request_id}


async def evidence(query, client):
    return [
        {
            "source": "points",
            "text": "포인트는 신규 적립 예정 수량과 환산 가치를 구분합니다.",
        }
    ]


async def main():
    settings = LocalSettings.from_env()
    if settings.mode != "local":
        raise RuntimeError("This check requires AI_MODE=local")
    report = {"orchestrator": "langgraph", "model": settings.model, "cases": []}
    async with (
        httpx.AsyncClient(
            timeout=10, transport=httpx.AsyncHTTPTransport(retries=2), trust_env=False
        ) as model_http,
        httpx.AsyncClient(
            transport=httpx.MockTransport(lambda r: httpx.Response(200, json=CATALOG))
        ) as catalog_http,
    ):
        model = LocalQwen(model_http, settings)
        workflow = AgentWorkflow(
            catalog_http,
            Service(),
            Store(),
            settings=settings,
            model=model,
            retriever=evidence,
        )
        cases = [
            (
                {
                    "message": "혼자 먹을 한식 점심을 배달비까지 만오천 원 안으로 찾아줘. 땅콩은 못 먹어요.",
                    "region": "SEOUL",
                },
                "OK",
                15000,
                1,
            ),
            (
                {
                    "message": "배달비 포함 2만원 안에 두 명 한식 점심, 땅콩은 빼줘",
                    "region": "SEOUL",
                },
                "OK",
                20000,
                2,
            ),
            (
                {"message": "점심 추천해줘", "region": "SEOUL"},
                "NEEDS_INPUT",
                None,
                None,
            ),
            (
                {
                    "message": "2만원 두 명 한식",
                    "region": "SEOUL",
                    "budgetKrw": 15000,
                    "people": 1,
                },
                "OK",
                15000,
                1,
            ),
            (
                {
                    "message": "배달비까지 1.5만원 안으로 혼자 먹을 중식",
                    "region": "SEOUL",
                },
                "NO_CANDIDATES",
                15000,
                1,
            ),
        ]
        for index, (data, status, budget, people) in enumerate(cases):
            start = time.monotonic()
            result = await workflow.plan(data, [], f"local-eval-{index}")
            assert result["status"] == status, (index, result)
            if result["constraints"]:
                assert (
                    result["constraints"]["budgetKrw"] == budget
                    and result["constraints"]["people"] == people
                ), (index, result)
            if index == 0:
                assert "땅콩" in result["constraints"]["excludedIngredients"]
            report["cases"].append(
                {
                    "case": index,
                    "status": status,
                    "passed": True,
                    "seconds": round(time.monotonic() - start, 2),
                }
            )
        quoted = {
            "basket": {"merchantName": "시연 식당"},
            "paymentMethod": "CARD_B",
            "chargeKrw": 16000,
            "effectiveCostKrw": 13520,
            "pointsEarned": 80,
            "pointValueKrw": 80,
            "statementDiscountKrw": 800,
            "cashbackKrw": 1600,
        }
        explained = await workflow.explain(quoted, 3)
        assert "16,000원" in explained["text"] and "13,520원" in explained["text"]
        report["explanation_preserves_verified_amounts"] = True
        r = await model_http.get(settings.base_url + "/api/ps")
        r.raise_for_status()
        loaded = next(m for m in r.json()["models"] if m["name"] == settings.model)
        resident = loaded["size"]
        assert 10_000_000_000 <= resident <= 20_000_000_000, (
            f"Reported resident model memory outside budget: {resident}"
        )
        report["ollama_reported_resident_gb"] = round(resident / 1e9, 2)
        report["ollama_reported_gpu_gb"] = round(loaded.get("size_vram", 0) / 1e9, 2)
        report["model_digest"] = loaded.get("digest")
        report["context_length"] = loaded.get("context_length", settings.context_length)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
