import concurrent.futures
import copy
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
import httpx
import psycopg
import pytest

BASE = os.getenv("BASE_URL", "http://gateway:8080")
TOKEN = "http://keycloak:8080/realms/food/protocol/openid-connect/token"
USER = "00000000-0000-0000-0000-000000000001"
# Retry connection establishment only; HTTP status and business assertions remain strict.
CLIENT = httpx.Client(
    timeout=40, transport=httpx.HTTPTransport(retries=2), trust_env=False
)


def token(client, username=None):
    data = {
        "client_id": client,
        "client_secret": client + "-dev-only",
        "grant_type": "password" if username else "client_credentials",
    }
    if username:
        data.update(username=username, password="demo-password")
    r = CLIENT.post(TOKEN, data=data)
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


class Tokens(dict):
    def __repr__(self):
        return "<test credentials redacted>"


def headers(t):
    return {"Authorization": "Bearer " + t}


@pytest.fixture(scope="session")
def auth():
    for _ in range(60):
        try:
            if CLIENT.get(BASE + "/actuator/health").status_code == 200:
                break
        except httpx.HTTPError:
            pass
        time.sleep(1)
    return Tokens(
        {
            "user": token("test-user", "demo"),
            "other": token("test-user", "other"),
            "worker": token("order-worker"),
            "admin": token("test-admin"),
            "agent": token("agent-service"),
        }
    )


def call(auth, service, path, body=None, actor="worker", method="POST"):
    url = (
        BASE
        if service == "gateway"
        else "http://"
        + service
        + (":8000" if service in ["agent-service", "merchant-simulator"] else ":8080")
    ) + path
    return CLIENT.request(method, url, headers=headers(auth[actor]), json=body)


def create(
    auth, message="배달비 포함 2만원 안에서 두 명 한식 점심", key=None, **fields
):
    body = {
        "message": message,
        "address": "서울시 중구 시연 주소",
        "region": "SEOUL",
        "budgetKrw": None,
        "people": None,
        "excludedIngredients": [],
        "cuisine": None,
        **fields,
    }
    r = CLIENT.post(
        BASE + "/v1/purchase-requests",
        headers={**headers(auth["user"]), "Idempotency-Key": key or str(uuid.uuid4())},
        json=body,
    )
    assert r.status_code == 202, r.text
    return r.json()


def wait(auth, id, statuses, timeout=100):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        r = CLIENT.get(
            BASE + "/v1/purchase-requests/" + id, headers=headers(auth["user"])
        )
        assert r.status_code == 200, r.text
        last = r.json()
        if last["status"] in statuses:
            return last
        if (
            last["status"] in ["FAILED", "NEEDS_INPUT"]
            and last["status"] not in statuses
        ):
            pytest.fail(str(last))
        time.sleep(0.4)
    pytest.fail("Timed out: " + str(last))


def proposal(auth, **kwargs):
    return wait(auth, create(auth, **kwargs)["id"], {"AWAITING_APPROVAL"})


def approve(auth, state, actor="user"):
    p = state["proposal"]
    return CLIENT.post(
        BASE + "/v1/proposals/" + p["id"] + "/approvals",
        headers=headers(auth[actor]),
        json={"version": p["version"]},
    )


def finish(auth, state):
    r = approve(auth, state)
    assert r.status_code == 202, r.text
    return wait(auth, state["id"], {"COMPLETED", "FAILED_REFUNDED"})


def payment(auth, p):
    return call(
        auth,
        "payment-service",
        "/internal/v1/payments/" + p["executionId"],
        method="GET",
    ).json()


def command(p):
    return {"executionId": p["executionId"], "proposal": p}


def fault(auth, service, name, count=1):
    r = call(
        auth,
        service,
        "/internal/v1/test/faults/" + name,
        {"count": count},
        actor="admin",
    )
    assert r.status_code == 200, r.text


def test_missing_auth_and_missing_constraints(auth):
    assert CLIENT.get(BASE + "/v1/purchase-requests/unknown").status_code == 401
    state = wait(auth, create(auth, message="점심 추천해줘")["id"], {"NEEDS_INPUT"})
    assert state["proposal"] is None


def test_request_idempotency_and_owner(auth):
    key = str(uuid.uuid4())
    a = create(auth, key=key)
    b = create(auth, key=key)
    assert a["id"] == b["id"]
    r = CLIENT.get(
        BASE + "/v1/purchase-requests/" + a["id"], headers=headers(auth["other"])
    )
    assert r.status_code == 404
    state = wait(auth, a["id"], {"AWAITING_APPROVAL"})
    assert approve(auth, state, "other").status_code in [403, 404]
    body = {**state["input"], "message": "다른 내용"}
    r = CLIENT.post(
        BASE + "/v1/purchase-requests",
        headers={**headers(auth["user"]), "Idempotency-Key": key},
        json=body,
    )
    assert r.status_code == 409


def test_full_flow_double_approval_and_replay(auth):
    state = proposal(auth)
    p = state["proposal"]
    q = p["quote"]
    assert q["chargeKrw"] <= p["constraints"]["budgetKrw"]
    assert (
        q["effectiveCostKrw"]
        == q["chargeKrw"]
        - q["statementDiscountKrw"]
        - q["cashbackKrw"]
        - q["pointValueKrw"]
    )
    assert q == min(
        state["candidates"],
        key=lambda x: (
            x["effectiveCostKrw"],
            x["chargeKrw"],
            x["basket"]["deliveryMinutes"],
            x["basket"]["merchantId"],
            x["paymentMethod"],
        ),
    )
    assert (
        call(auth, "payment-service", "/internal/v1/payments", command(p)).status_code
        == 403
    )
    assert (
        call(
            auth, "payment-service", "/internal/v1/payments", command(p), actor="agent"
        ).status_code
        == 403
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        responses = list(pool.map(lambda _: approve(auth, state), range(4)))
    assert all(r.status_code == 202 for r in responses), [r.text for r in responses]
    done = wait(auth, state["id"], {"COMPLETED"})
    assert payment(auth, p)["status"] == "PAID"
    assert (
        call(auth, "payment-service", "/internal/v1/payments", command(p)).status_code
        == 200
    )
    altered = copy.deepcopy(p)
    altered["quote"]["chargeKrw"] += 1
    assert (
        call(
            auth, "payment-service", "/internal/v1/payments", command(altered)
        ).status_code
        == 409
    )
    ledger = call(
        auth, "payment-service", "/internal/v1/test/ledger", actor="admin", method="GET"
    ).json()
    assert len([r for r in ledger if r["execution_id"] == p["executionId"]]) == 1


def test_price_change_requires_new_approval(auth):
    state = proposal(auth)
    p = state["proposal"]
    basket = p["quote"]["basket"]
    item = basket["items"][0]
    route = f"/internal/v1/test/catalog/{basket['merchantId']}/{item['menuId']}"
    try:
        r = call(
            auth,
            "merchant-simulator",
            route,
            {"priceKrw": item["unitPriceKrw"] + 1000},
            actor="admin",
        )
        assert r.status_code == 200
        assert approve(auth, state).status_code == 409
        fresh = wait(auth, state["id"], {"AWAITING_APPROVAL"})
        assert fresh["proposal"]["version"] == p["version"] + 1
        assert fresh["proposal"]["id"] != p["id"]
        assert (
            call(
                auth,
                "payment-service",
                "/internal/v1/payments/" + p["executionId"],
                method="GET",
            ).status_code
            == 404
        )
        assert approve(auth, state).status_code == 409
        assert finish(auth, fresh)["status"] == "COMPLETED"
    finally:
        call(
            auth,
            "merchant-simulator",
            route,
            {"priceKrw": item["unitPriceKrw"]},
            actor="admin",
        )


def test_out_of_stock_replans(auth):
    state = proposal(auth)
    p = state["proposal"]
    b = p["quote"]["basket"]
    i = b["items"][0]
    route = f"/internal/v1/test/catalog/{b['merchantId']}/{i['menuId']}"
    try:
        call(auth, "merchant-simulator", route, {"stock": 0}, actor="admin")
        assert approve(auth, state).status_code == 409
        fresh = wait(auth, state["id"], {"AWAITING_APPROVAL"})
        assert fresh["proposal"]["version"] > p["version"]
        assert all(
            x["menuId"] != i["menuId"]
            for x in fresh["proposal"]["quote"]["basket"]["items"]
        )
    finally:
        call(auth, "merchant-simulator", route, {"stock": 1000}, actor="admin")


def test_expired_and_revoked_approvals_are_rejected(auth):
    state = proposal(auth)
    p = copy.deepcopy(state["proposal"])
    p["id"] = str(uuid.uuid4())
    p["executionId"] = str(uuid.uuid4())
    p["expiresAt"] = (datetime.now(timezone.utc) + timedelta(seconds=3)).isoformat()
    r = call(auth, "control-service", "/internal/v1/validations", p)
    assert r.status_code == 200, r.text
    time.sleep(3.1)
    r = call(
        auth,
        "control-service",
        "/internal/v1/approvals/" + p["id"],
        {"version": p["version"]},
        actor="user",
    )
    assert r.status_code == 409, r.text
    p = state["proposal"]
    r = call(
        auth, "control-service", "/internal/v1/approvals/" + p["id"] + "/revoke", {}
    )
    assert r.status_code == 200
    assert approve(auth, state).status_code == 409


def test_response_loss_reconciles_without_double_charge(auth):
    state = proposal(auth)
    fault(auth, "payment-service", "charge_response_lost")
    assert finish(auth, state)["status"] == "COMPLETED"
    ledger = call(
        auth, "payment-service", "/internal/v1/test/ledger", actor="admin", method="GET"
    ).json()
    assert (
        len(
            [r for r in ledger if r["execution_id"] == state["proposal"]["executionId"]]
        )
        == 1
    )


def test_confirmation_response_loss_does_not_refund_success(auth):
    state = proposal(auth)
    fault(auth, "merchant-simulator", "confirm_response_lost")
    assert finish(auth, state)["status"] == "COMPLETED"
    assert payment(auth, state["proposal"])["status"] == "PAID"


def test_rejected_order_and_lost_refund_response(auth):
    state = proposal(auth)
    fault(auth, "merchant-simulator", "confirm_rejected")
    fault(auth, "payment-service", "refund_response_lost")
    done = finish(auth, state)
    assert done["status"] == "FAILED_REFUNDED"
    assert payment(auth, state["proposal"])["status"] == "REFUNDED"
    ledger = call(
        auth, "payment-service", "/internal/v1/test/ledger", actor="admin", method="GET"
    ).json()
    entries = [
        r for r in ledger if r["execution_id"] == state["proposal"]["executionId"]
    ]
    assert len(entries) == 2 and sum(r["amount_krw"] for r in entries) == 0


def test_database_isolation_and_ledger_balance(auth):
    schemas = ["orders", "agent", "benefits", "control", "payments"]
    tables = {
        "orders": "requests",
        "agent": "plan_runs",
        "benefits": "rules",
        "control": "approvals",
        "payments": "transactions",
    }
    for owner in schemas:
        with psycopg.connect(
            f"host=postgres dbname=food user={owner}_app password={owner}_dev_only",
            autocommit=True,
        ) as db:
            for other in schemas:
                if owner == other:
                    continue
                with pytest.raises(psycopg.errors.InsufficientPrivilege):
                    db.execute(f"SELECT * FROM {other}.{tables[other]} LIMIT 1")
    with psycopg.connect(
        "host=postgres dbname=food user=payments_app password=payments_dev_only options=-csearch_path=payments"
    ) as db:
        rows = db.execute(
            "SELECT w.user_id,w.method,w.balance_krw,500000+coalesce(sum(l.amount_krw),0) FROM wallets w LEFT JOIN transactions t ON t.user_id=w.user_id AND t.method=w.method LEFT JOIN ledger l ON l.execution_id=t.execution_id GROUP BY w.user_id,w.method,w.balance_krw"
        ).fetchall()
        assert all(actual == expected for _, _, actual, expected in rows)


def test_previous_month_eligibility_and_concurrent_quota(auth):
    state = proposal(auth)
    p = copy.deepcopy(state["proposal"])
    p["userId"] = "00000000-0000-0000-0000-000000000002"
    basket = p["quote"]["basket"]
    basket["items"] = [{**basket["items"][0], "quantity": 10}]
    result = call(
        auth,
        "benefit-service",
        "/internal/v1/benefit-quotes",
        {
            "userId": p["userId"],
            "region": "SEOUL",
            "baskets": [basket],
            "excludeExecutionId": None,
        },
    )
    assert result.status_code == 200, result.text
    quotes = result.json()["quotes"]
    assert {q["paymentMethod"] for q in quotes} == {"LOCAL"}
    p["quote"] = quotes[0]
    p["expiresAt"] = quotes[0]["expiresAt"]
    p["constraints"]["people"] = 10
    p["constraints"]["budgetKrw"] = 200000
    proposals = []
    for _ in range(5):
        candidate = copy.deepcopy(p)
        candidate["id"] = str(uuid.uuid4())
        candidate["executionId"] = str(uuid.uuid4())
        proposals.append(candidate)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            results = list(
                pool.map(
                    lambda candidate: call(
                        auth,
                        "benefit-service",
                        "/internal/v1/reservations",
                        command(candidate),
                    ),
                    proposals,
                )
            )
        assert sum(r.status_code == 200 for r in results) == 2, [
            r.text for r in results
        ]
        assert sum(r.status_code == 409 for r in results) == 3
    finally:
        for candidate in proposals:
            call(
                auth,
                "benefit-service",
                "/internal/v1/reservations/" + candidate["executionId"] + "/release",
                {},
            )


def test_rag_evidence_and_agent_scope(auth):
    result = call(
        auth, "agent-service", "/internal/v1/evidence?q=지역화폐", method="GET"
    )
    assert result.status_code == 200, result.text
    assert result.json()["evidence"][0]["source"] == "local-currency"
    assert (
        call(
            auth,
            "payment-service",
            "/internal/v1/wallets/" + USER,
            actor="agent",
            method="GET",
        ).status_code
        == 403
    )


def test_agent_executes_langgraph_and_persists_node_trace(auth):
    r = call(auth, "agent-service", "/internal/v1/runtime", method="GET")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["orchestrator"] == "langgraph" and data["mode"] == "fixture"
    assert {"extract", "validate", "catalog", "candidates", "persist"} <= set(
        data["planningNodes"]
    )
    with psycopg.connect(
        "host=postgres dbname=food user=agent_app password=agent_dev_only options=-csearch_path=agent"
    ) as db:
        row = db.execute(
            "SELECT runtime,node_trace FROM plan_runs WHERE result->>'status'='OK' ORDER BY id DESC LIMIT 1"
        ).fetchone()
        assert row and row[0]["orchestrator"] == "langgraph"
        assert row[1] == ["extract", "validate", "catalog", "candidates", "persist"]
