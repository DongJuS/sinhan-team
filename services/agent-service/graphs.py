"""LangGraph controls AI steps; Java retains approval and financial execution authority."""

import os

# Local-only operation: graph inputs must never be sent to hosted tracing services.
os.environ["LANGSMITH_TRACING"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"

import asyncio
import operator
from typing import Annotated, TypedDict

import httpx
from langgraph.graph import StateGraph, START, END
from pydantic import ValidationError

from domain import Extraction, candidates
from local_model import (
    FixtureModel,
    LocalQwen,
    LocalSettings,
    LocalAIUnavailable,
    ModelOutputError,
)
from platform_auth import Problem
from rag import retrieve


class PlanState(TypedDict, total=False):
    data: dict
    request_id: str
    excluded_merchants: list[str]
    attempts: int
    conditions: Extraction | None
    invalid_output: bool
    input_invalid: bool
    catalog: list[dict]
    result: dict
    trace: Annotated[list[str], operator.add]


class ExplanationState(TypedDict, total=False):
    quote: dict
    evaluated_candidates: int
    evidence: list[dict]
    reasons: list[str]
    attempts: int
    invalid_output: bool
    result: dict


class AgentWorkflow:
    def __init__(
        self, client, service, store, *, settings=None, model=None, retriever=retrieve
    ):
        self.client, self.service, self.store = client, service, store
        self.settings = settings or LocalSettings.from_env()
        self.model = model or (
            FixtureModel()
            if self.settings.mode == "fixture"
            else LocalQwen(client, self.settings)
        )
        self.retriever = retriever
        plan = StateGraph(PlanState)
        for name, node in [
            ("extract", self.extract),
            ("validate", self.validate),
            ("needs_input", self.needs_input),
            ("catalog", self.catalog),
            ("candidates", self.generate),
            ("persist", self.persist),
            ("invalid_model_output", self.invalid_output),
        ]:
            plan.add_node(name, node)
        plan.add_edge(START, "extract")
        plan.add_edge("extract", "validate")
        plan.add_conditional_edges(
            "validate",
            self.route_validation,
            {
                "retry": "extract",
                "missing": "needs_input",
                "ready": "catalog",
                "invalid": "invalid_model_output",
            },
        )
        plan.add_edge("needs_input", "persist")
        plan.add_edge("catalog", "candidates")
        plan.add_edge("candidates", "persist")
        plan.add_edge("persist", END)
        plan.add_edge("invalid_model_output", END)
        self.plan_graph = plan.compile(name="meal-planning")

        explanation = StateGraph(ExplanationState)
        explanation.add_node("retrieve_evidence", self.retrieve_evidence)
        explanation.add_node("select_reasons", self.select_reasons)
        explanation.add_node("render_verified_amounts", self.render)
        explanation.add_node("invalid_model_output", self.invalid_output)
        explanation.add_edge(START, "retrieve_evidence")
        explanation.add_edge("retrieve_evidence", "select_reasons")
        explanation.add_conditional_edges(
            "select_reasons",
            self.route_reasons,
            {
                "retry": "select_reasons",
                "valid": "render_verified_amounts",
                "invalid": "invalid_model_output",
            },
        )
        explanation.add_edge("render_verified_amounts", END)
        explanation.add_edge("invalid_model_output", END)
        self.explanation_graph = explanation.compile(name="grounded-explanation")

    async def plan(self, data, excluded_merchants, request_id):
        state = await self.plan_graph.ainvoke(
            {
                "data": data,
                "excluded_merchants": excluded_merchants,
                "request_id": request_id,
                "attempts": 0,
                "trace": [],
            },
            config={"recursion_limit": 16},
        )
        return state["result"]

    async def explain(self, quote, evaluated_candidates):
        state = await self.explanation_graph.ainvoke(
            {
                "quote": quote,
                "evaluated_candidates": evaluated_candidates,
                "attempts": 0,
            },
            config={"recursion_limit": 10},
        )
        return state["result"]

    def metadata(self):
        return {
            "orchestrator": "langgraph",
            **self.settings.metadata(),
            "planningNodes": [
                n for n in self.plan_graph.get_graph().nodes if not n.startswith("__")
            ],
            "explanationNodes": [
                n
                for n in self.explanation_graph.get_graph().nodes
                if not n.startswith("__")
            ],
        }

    async def extract(self, state):
        updates = {
            "attempts": state["attempts"] + 1,
            "trace": ["extract"],
            "invalid_output": False,
            "input_invalid": False,
            "conditions": None,
        }
        try:
            updates["conditions"] = await self.model.extract(state["data"])
        except ModelOutputError:
            updates["invalid_output"] = True
        except ValidationError:
            updates["input_invalid"] = True
        except LocalAIUnavailable as exc:
            raise Problem(
                503, "AI_UNAVAILABLE", "로컬 Qwen 서버를 사용할 수 없습니다."
            ) from exc
        return updates

    def validate(self, state):
        return {"trace": ["validate"]}

    def route_validation(self, state):
        if state.get("invalid_output"):
            return "retry" if state["attempts"] < 2 else "invalid"
        c = state.get("conditions")
        return (
            "missing"
            if state.get("input_invalid")
            or c is None
            or c.budgetKrw is None
            or c.people is None
            else "ready"
        )

    def needs_input(self, state):
        return {
            "result": {
                "status": "NEEDS_INPUT",
                "message": "예산과 인원을 구체적으로 알려주세요. 예: 2만원 이내 두 명의 점심",
                "constraints": None,
                "candidates": [],
            },
            "trace": ["needs_input"],
        }

    async def catalog(self, state):
        try:
            response = await self.client.get(
                os.getenv("MERCHANT_URL", "http://merchant-simulator:8000")
                + "/internal/v1/catalog",
                headers=await self.service.headers(self.client, state["request_id"]),
            )
            response.raise_for_status()
            return {"catalog": response.json(), "trace": ["catalog"]}
        except httpx.HTTPError as exc:
            raise Problem(
                503, "CATALOG_UNAVAILABLE", "메뉴 정보를 불러올 수 없습니다."
            ) from exc

    def generate(self, state):
        baskets = candidates(
            state["catalog"],
            state["conditions"],
            state["data"]["region"],
            state["excluded_merchants"],
        )
        return {
            "result": {
                "status": "OK" if baskets else "NO_CANDIDATES",
                "message": "후보 탐색 완료"
                if baskets
                else "조건을 만족하는 메뉴가 없습니다.",
                "constraints": state["conditions"].model_dump(),
                "candidates": baskets,
            },
            "trace": ["candidates"],
        }

    async def persist(self, state):
        await asyncio.to_thread(
            self.store.save,
            state["request_id"],
            state["result"],
            self.metadata(),
            state["trace"] + ["persist"],
        )
        return {"trace": ["persist"]}

    def invalid_output(self, state):
        raise Problem(
            503, "AI_INVALID_OUTPUT", "로컬 모델의 응답이 검증을 통과하지 못했습니다."
        )

    async def retrieve_evidence(self, state):
        try:
            return {"evidence": await self.retriever("포인트 지역화폐", self.client)}
        except (httpx.HTTPError, ValueError) as exc:
            raise Problem(
                503, "RAG_UNAVAILABLE", "로컬 근거 검색을 사용할 수 없습니다."
            ) from exc

    async def select_reasons(self, state):
        updates = {"attempts": state["attempts"] + 1, "invalid_output": False}
        try:
            updates["reasons"] = (
                await self.model.reasons(state["quote"], state["evidence"])
            ).reasons
        except ModelOutputError:
            updates["invalid_output"] = True
        except LocalAIUnavailable as exc:
            raise Problem(
                503, "AI_UNAVAILABLE", "로컬 Qwen 설명을 생성할 수 없습니다."
            ) from exc
        return updates

    def route_reasons(self, state):
        if not state.get("invalid_output"):
            return "valid"
        return "retry" if state["attempts"] < 2 else "invalid"

    def render(self, state):
        q = state["quote"]
        text = f"{state['evaluated_candidates']}개 결제 조합을 비교했습니다. {q['basket']['merchantName']} · {q['paymentMethod']} 조합의 결제금액은 {q['chargeKrw']:,}원, 예상 실질 지출은 {q['effectiveCostKrw']:,}원입니다."
        if "points" in state["reasons"] and q["pointsEarned"] > 0:
            text += f" 적립 예정 {q['pointsEarned']}P의 환산 가치는 {q['pointValueKrw']:,}원입니다."
        if "deferred_benefits" in state["reasons"]:
            text += " 청구 할인과 캐시백은 즉시 결제금액과 구분해 표시했습니다."
        return {"result": {"text": text, "evidence": state["evidence"]}}
