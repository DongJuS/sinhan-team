import json
import sys
import time
from pathlib import Path
from test_scenarios import token, Tokens, proposal, fault, approve, wait, call

auth = Tokens(
    user=token("test-user", "demo"),
    worker=token("order-worker"),
    admin=token("test-admin"),
)
path = Path("/artifacts/restart.json")
if sys.argv[1] == "prepare":
    state = proposal(auth)
    fault(auth, "merchant-simulator", "confirm_response_delay")
    r = approve(auth, state)
    assert r.status_code == 202, r.text
    from test_scenarios import CLIENT, BASE, headers

    for _ in range(100):
        current = CLIENT.get(
            BASE + "/v1/purchase-requests/" + state["id"], headers=headers(auth["user"])
        ).json()
        if current["stage"] == "CONFIRMING":
            break
        time.sleep(0.1)
    else:
        raise AssertionError("Worker never reached confirmation")
    path.write_text(
        json.dumps({"id": state["id"], "executionId": state["proposal"]["executionId"]})
    )
    print("Worker reached confirmation after payment; ready for forced restart")
else:
    saved = json.loads(path.read_text())
    state = wait(auth, saved["id"], {"COMPLETED"}, timeout=100)
    ledger = call(
        auth, "payment-service", "/internal/v1/test/ledger", actor="admin", method="GET"
    ).json()
    assert len([x for x in ledger if x["execution_id"] == saved["executionId"]]) == 1
    reservations = call(
        auth,
        "merchant-simulator",
        "/internal/v1/test/reservations",
        actor="admin",
        method="GET",
    ).json()
    rows = [x for x in reservations if x["id"] == saved["executionId"]]
    assert len(rows) == 1 and rows[0]["status"] == "CONFIRMED"
    print("PASS: expired lease recovered, one charge, one confirmed merchant order")
