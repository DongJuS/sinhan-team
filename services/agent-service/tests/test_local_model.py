import asyncio
import json

import httpx
import pytest

from local_model import LocalQwen, LocalSettings, ModelOutputError


def response(content, **fields):
    return httpx.Response(
        200,
        json={
            "done": True,
            "done_reason": "stop",
            "message": {"content": json.dumps(content)},
            **fields,
        },
    )


def test_local_schema_request_and_explicit_constraint_precedence():
    sent = []

    def handler(request):
        sent.append(request)
        return response(
            {
                "budgetKrw": 20000,
                "people": 2,
                "excludedIngredients": ["땅콩"],
                "cuisine": "한식",
            }
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as c:
            return await LocalQwen(c, LocalSettings()).extract(
                {
                    "message": "2만원 두 명 한식",
                    "budgetKrw": 15000,
                    "people": 1,
                    "excludedIngredients": ["우유"],
                    "address": "private address",
                    "token": "private token",
                }
            )

    result = asyncio.run(run())
    assert result.budgetKrw == 15000 and result.people == 1
    assert result.excludedIngredients == ["땅콩", "우유"]
    payload = json.loads(sent[0].content)
    assert payload["think"] is False and payload["stream"] is False
    assert (
        payload["options"]["num_ctx"] == 4096 and payload["format"]["type"] == "object"
    )
    assert "authorization" not in sent[0].headers
    assert (
        "private address" not in sent[0].content.decode()
        and "private token" not in sent[0].content.decode()
    )


@pytest.mark.parametrize(
    "base",
    [
        "https://api.example.com",
        "http://example.com",
        "http://user:secret@localhost:11435",
    ],
)
def test_nonlocal_or_credentialed_urls_are_rejected(base):
    with pytest.raises(ValueError):
        LocalSettings(base_url=base)


def test_cloud_models_and_excessive_context_are_rejected():
    with pytest.raises(ValueError):
        LocalSettings(model="qwen-cloud")
    with pytest.raises(ValueError):
        LocalSettings(context_length=32768)


@pytest.mark.parametrize(
    "payload",
    [
        {"budgetKrw": 20000, "people": 2, "pay_now": True},
        {"budgetKrw": -1, "people": 2},
    ],
)
def test_invalid_schema_is_not_accepted(payload):
    async def run():
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda r: response(payload))
        ) as c:
            return await LocalQwen(c, LocalSettings()).extract({"message": "점심"})

    with pytest.raises(ModelOutputError):
        asyncio.run(run())


def test_truncated_output_is_not_accepted_even_if_json_parses():
    async def run():
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda r: response(
                    {"budgetKrw": 20000, "people": 2}, done_reason="length"
                )
            )
        ) as c:
            return await LocalQwen(c, LocalSettings()).extract({"message": "점심"})

    with pytest.raises(ModelOutputError):
        asyncio.run(run())


def test_only_one_inference_request_runs_at_a_time():
    active = peak = 0

    async def handler(request):
        nonlocal active, peak
        active += 1
        peak = max(peak, active)
        await asyncio.sleep(0.02)
        active -= 1
        return response({"budgetKrw": 20000, "people": 2})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as c:
            model = LocalQwen(c, LocalSettings())
            await asyncio.gather(
                *(model.extract({"message": "점심"}) for _ in range(3))
            )

    asyncio.run(run())
    assert peak == 1
