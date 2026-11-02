import os
import asyncio
from contextlib import asynccontextmanager
import httpx
from fastapi import FastAPI, Depends, Request
from platform_auth import require, configure
from adapters import ServiceClient, PlanStore
from schemas import PlanCommand, ExplanationInput
from graphs import AgentWorkflow
from rag import retrieve, ingest

store = PlanStore()
service = ServiceClient()


@asynccontextmanager
async def lifespan(app):
    await asyncio.to_thread(store.migrate)
    if not os.getenv("RAG_EMBEDDING_MODEL"):
        await ingest()
    async with httpx.AsyncClient(timeout=httpx.Timeout(10, connect=3)) as client:
        app.state.client = client
        app.state.workflow = AgentWorkflow(client, service, store)
        yield


app = FastAPI(title="Food Agent", version="0.1.0", lifespan=lifespan)
configure(app, "agent-service")


@app.get("/health")
def health():
    return {"status": "UP", "orchestrator": "langgraph"}


@app.post("/internal/v1/plans", dependencies=[Depends(require("agent:plan"))])
async def plan(command: PlanCommand, request: Request):
    return await request.app.state.workflow.plan(
        command.input.model_dump(), command.excludedMerchants, command.requestId
    )


@app.get("/internal/v1/evidence", dependencies=[Depends(require("agent:plan"))])
async def evidence(q: str, request: Request):
    return {"evidence": await retrieve(q, request.app.state.client)}


@app.post("/internal/v1/explanations", dependencies=[Depends(require("agent:plan"))])
async def explain(body: ExplanationInput, request: Request):
    return await request.app.state.workflow.explain(
        body.quote, body.evaluatedCandidates
    )


@app.get("/internal/v1/runtime", dependencies=[Depends(require("agent:plan"))])
def runtime(request: Request):
    return request.app.state.workflow.metadata()
