"""Bounded HTTP read benchmark. Does not create requests, run LLMs or charge payments."""

import os
import statistics
import time
import httpx

url = (
    os.environ.get("BASE_URL", "http://localhost:18080")
    + "/v1/purchase-requests/"
    + os.environ["PURCHASE_REQUEST_ID"]
)
samples = []
with httpx.Client(timeout=5) as client:
    for _ in range(50):
        start = time.perf_counter()
        response = client.get(
            url, headers={"Authorization": "Bearer " + os.environ["ACCESS_TOKEN"]}
        )
        response.raise_for_status()
        samples.append((time.perf_counter() - start) * 1000)
        time.sleep(0.1)
print(
    {
        "requests": len(samples),
        "median_ms": round(statistics.median(samples), 2),
        "p95_ms": round(sorted(samples)[47], 2),
    }
)
