import os
import asyncio
from contextlib import asynccontextmanager
import httpx
from fastapi import FastAPI, Depends, Request
from platform_auth import require, configure
from adapters import ServiceClient, PlanStore
from schemas import PlanCommand, ExplanationInput
from application import build_plan, explain_quote
from rag import retrieve, ingest

store = PlanStore()
service = ServiceClient()


@asynccontextmanager
async def lifespan(app):
    await asyncio.to_thread(store.migrate)
    if not os.getenv("EMBEDDING_BASE_URL"):
        await ingest()
    async with httpx.AsyncClient(timeout=httpx.Timeout(10, connect=3)) as client:
        app.state.client = client
        yield


app = FastAPI(title="Food Agent", version="0.1.0", lifespan=lifespan)
configure(app, "agent-service")


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/internal/v1/plans", dependencies=[Depends(require("agent:plan"))])
async def plan(command: PlanCommand, request: Request):
    return await build_plan(
        command.input.model_dump(),
        command.excludedMerchants,
        command.requestId,
        request.app.state.client,
        service,
        store,
    )


@app.get("/internal/v1/evidence", dependencies=[Depends(require("agent:plan"))])
async def evidence(q: str, request: Request):
    return {"evidence": await retrieve(q, request.app.state.client)}


@app.post("/internal/v1/explanations", dependencies=[Depends(require("agent:plan"))])
async def explain(body: ExplanationInput, request: Request):
    return await explain_quote(
        body.quote, body.evaluatedCandidates, request.app.state.client
    )
