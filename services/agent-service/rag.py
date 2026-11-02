"""Evidence retrieval. Retrieved text never determines monetary amounts or authorizes actions."""

import math
import os
import httpx
import psycopg

DOCUMENTS = [
    (
        "menu-korean",
        "한식 메뉴에는 비빔밥, 불고기덮밥과 된장찌개가 있습니다. 알레르기 정보는 메뉴의 구조화된 ingredients 값을 확인합니다.",
    ),
    (
        "benefit-points",
        "포인트는 적립 예정 수량과 환산 가치를 분리합니다. 실제 결제금액과 예상 실질 지출은 다릅니다.",
    ),
    (
        "local-currency",
        "지역화폐는 사용 가능 지역과 가맹점에서 사용할 수 있습니다. 기존 충전 혜택을 결제 시 다시 할인으로 차감하지 않습니다.",
    ),
]


async def embed(client, texts):
    r = await client.post(
        os.environ["EMBEDDING_BASE_URL"].rstrip("/") + "/embeddings",
        headers={"Authorization": "Bearer " + os.environ["EMBEDDING_API_KEY"]},
        json={"model": os.environ["EMBEDDING_MODEL"], "input": texts},
    )
    r.raise_for_status()
    rows = sorted(r.json()["data"], key=lambda x: x["index"])
    if len(rows) != len(texts):
        raise ValueError("Embedding count mismatch")
    return [row["embedding"] for row in rows]


async def ingest():
    from adapters import PlanStore

    PlanStore().migrate()
    vectors = [None] * len(DOCUMENTS)
    model = "lexical-v1"
    if os.getenv("EMBEDDING_BASE_URL"):
        async with httpx.AsyncClient(timeout=30) as client:
            vectors = await embed(client, [text for _, text in DOCUMENTS])
        model = os.environ["EMBEDDING_MODEL"]
    with psycopg.connect(os.environ["DATABASE_URL"]) as db:
        db.execute("DELETE FROM evidence")
        for (source, text), vector in zip(DOCUMENTS, vectors):
            db.execute(
                "INSERT INTO evidence(source,content,embedding,model) VALUES (%s,%s,%s,%s)",
                (source, text, vector, model),
            )


async def retrieve(query, client):
    with psycopg.connect(os.environ["DATABASE_URL"]) as db:
        rows = db.execute(
            "SELECT source,content,embedding,model FROM evidence"
        ).fetchall()
    q = None
    if os.getenv("EMBEDDING_BASE_URL"):
        q = (await embed(client, [query]))[0]
    hits = []
    for source, content, vector, model in rows:
        if q is not None:
            if (
                model != os.environ["EMBEDDING_MODEL"]
                or vector is None
                or len(q) != len(vector)
            ):
                raise ValueError("Embedding model changed: reindex required")
            denom = math.sqrt(sum(x * x for x in q) * sum(x * x for x in vector))
            score = sum(x * y for x, y in zip(q, vector)) / denom if denom else 0
        else:
            score = sum(word in content for word in query.split())
        if score > 0:
            hits.append({"source": source, "text": content, "score": score})
    return sorted(hits, key=lambda h: h["score"], reverse=True)[:3]


if __name__ == "__main__":
    import asyncio

    asyncio.run(ingest())
