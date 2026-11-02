import time
import uuid
import httpx

with httpx.Client(timeout=15) as client:
    for _ in range(60):
        try:
            if client.get("http://gateway:8080/actuator/health").status_code == 200:
                break
        except httpx.TransportError:
            pass
        time.sleep(1)
    else:
        raise RuntimeError("Gateway did not become ready")
    r = client.post(
        "http://keycloak:8080/realms/food/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "test-user",
            "client_secret": "test-user-dev-only",
            "username": "demo",
            "password": "demo-password",
        },
    )
    r.raise_for_status()
    headers = {
        "Authorization": "Bearer " + r.json()["access_token"],
        "Idempotency-Key": str(uuid.uuid4()),
    }
    r = client.post(
        "http://gateway:8080/v1/purchase-requests",
        headers=headers,
        json={
            "message": "2만원 두 명 한식 점심",
            "address": "서울시 중구 시연 주소",
            "region": "SEOUL",
        },
    )
    assert r.status_code == 202, r.status_code
    request_id = r.json()["id"]
    for _ in range(240):
        result = client.get(
            "http://gateway:8080/v1/purchase-requests/" + request_id, headers=headers
        )
        result.raise_for_status()
        state = result.json()
        if state["status"] == "AWAITING_APPROVAL":
            assert state["proposal"]["quote"]["chargeKrw"] <= 20000
            print("Deployment smoke passed: login, planning, quote, approval gate")
            break
        if state["status"] in ["FAILED", "NEEDS_INPUT"]:
            raise RuntimeError(state["errorCode"])
        time.sleep(1)
    else:
        raise RuntimeError("Deployment did not reach approval gate")
