import json
import os
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from fastapi import FastAPI, Depends
from platform_auth import require, configure, Problem

DB = os.getenv("SQLITE_PATH", "/data/merchant.db")


@contextmanager
def transaction():
    db = sqlite3.connect(DB, timeout=10, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute("BEGIN IMMEDIATE")
    try:
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


def initialize():
    Path(DB).parent.mkdir(parents=True, exist_ok=True)
    with transaction() as db:
        db.execute(
            "CREATE TABLE IF NOT EXISTS catalog(id TEXT PRIMARY KEY,payload TEXT NOT NULL)"
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS reservations(id TEXT PRIMARY KEY,basket TEXT NOT NULL,status TEXT NOT NULL)"
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS faults(name TEXT PRIMARY KEY,remaining INTEGER NOT NULL)"
        )
        for m in json.loads(Path("catalog.json").read_text()):
            db.execute(
                "INSERT OR IGNORE INTO catalog VALUES(?,?)",
                (m["id"], json.dumps(m, ensure_ascii=False)),
            )


initialize()
app = FastAPI(title="Merchant simulator", version="0.1.0")
configure(app, "merchant-simulator")


def catalog_rows(db):
    return [
        json.loads(r[0]) for r in db.execute("SELECT payload FROM catalog ORDER BY id")
    ]


def update(db, m):
    db.execute(
        "UPDATE catalog SET payload=? WHERE id=?",
        (json.dumps(m, ensure_ascii=False), m["id"]),
    )


def merchant(db, id):
    row = db.execute("SELECT payload FROM catalog WHERE id=?", (id,)).fetchone()
    if row is None:
        raise Problem(409, "INVALID_BASKET", "가맹점을 찾을 수 없습니다.")
    return json.loads(row[0])


def canonical(db, basket):
    m = merchant(db, basket["merchantId"])
    items = []
    seen = set()
    for line in basket["items"]:
        menu = next((v for v in m["menus"] if v["id"] == line["menuId"]), None)
        n = line["quantity"]
        if (
            menu is None
            or type(n) is not int
            or n < 1
            or n > 10
            or line["menuId"] in seen
        ):
            raise Problem(409, "INVALID_BASKET", "메뉴 또는 수량이 올바르지 않습니다.")
        seen.add(line["menuId"])
        if n > menu["stock"]:
            raise Problem(409, "OUT_OF_STOCK", "메뉴가 품절되었습니다.")
        options = line.get("options") or {}
        if set(options) != {"spice"} or options["spice"] not in [
            "mild",
            "normal",
            "hot",
        ]:
            raise Problem(409, "INVALID_BASKET", "메뉴 옵션이 올바르지 않습니다.")
        items.append(
            {
                "menuId": menu["id"],
                "name": menu["name"],
                "quantity": n,
                "unitPriceKrw": menu["priceKrw"],
                "options": options,
                "ingredients": menu["ingredients"],
            }
        )
    if not items:
        raise Problem(409, "INVALID_BASKET", "메뉴가 필요합니다.")
    return {
        "merchantId": m["id"],
        "merchantName": m["name"],
        "items": items,
        "deliveryFeeKrw": m["deliveryFeeKrw"],
        "deliveryMinutes": m["deliveryMinutes"],
        "catalogVersion": str(m["revision"]),
        "localCurrencyAccepted": m["localCurrencyAccepted"],
    }


def fault(db, name):
    return (
        db.execute(
            "UPDATE faults SET remaining=remaining-1 WHERE name=? AND remaining>0",
            (name,),
        ).rowcount
        > 0
    )


@app.get("/health")
def health():
    return {"status": "UP"}


@app.get("/internal/v1/catalog", dependencies=[Depends(require("merchant:read"))])
def catalog():
    with transaction() as db:
        return catalog_rows(db)


@app.post(
    "/internal/v1/baskets/validate", dependencies=[Depends(require("merchant:read"))]
)
def validate(basket: dict):
    with transaction() as db:
        return canonical(db, basket)


@app.post(
    "/internal/v1/reservations", dependencies=[Depends(require("merchant:write"))]
)
def reserve(command: dict):
    id = command["executionId"]
    p = command["proposal"]
    basket = p["quote"]["basket"]
    if id != p["executionId"]:
        raise Problem(409, "EXECUTION_MISMATCH", "실행 ID가 다릅니다.")
    with transaction() as db:
        old = db.execute("SELECT * FROM reservations WHERE id=?", (id,)).fetchone()
        if old:
            if json.loads(old["basket"]) != basket or old["status"] in [
                "RELEASED",
                "REJECTED",
            ]:
                raise Problem(409, "RESERVATION_CONFLICT", "예약을 사용할 수 없습니다.")
            return {"executionId": id, "status": old["status"]}
        actual = canonical(db, basket)
        if actual != basket:
            raise Problem(409, "PRICE_CHANGED", "가격 또는 메뉴가 변경되었습니다.")
        m = merchant(db, basket["merchantId"])
        for i in basket["items"]:
            next(v for v in m["menus"] if v["id"] == i["menuId"])["stock"] -= i[
                "quantity"
            ]
        update(db, m)
        db.execute(
            "INSERT INTO reservations VALUES(?,?,'RESERVED')",
            (id, json.dumps(basket, ensure_ascii=False)),
        )
    return {"executionId": id, "status": "RESERVED"}


@app.post(
    "/internal/v1/reservations/{id}/confirm",
    dependencies=[Depends(require("merchant:write"))],
)
def confirm(id: str):
    rejected = False
    lost = False
    delayed = False
    with transaction() as db:
        row = db.execute("SELECT * FROM reservations WHERE id=?", (id,)).fetchone()
        if row is None:
            raise Problem(404, "RESERVATION_NOT_FOUND", "예약 내역이 없습니다.")
        if row["status"] == "CONFIRMED":
            return {"executionId": id, "status": "CONFIRMED"}
        if row["status"] in ["REJECTED", "RELEASED"]:
            rejected = True
        elif fault(db, "confirm_rejected"):
            db.execute("UPDATE reservations SET status='REJECTED' WHERE id=?", (id,))
            rejected = True
        else:
            db.execute("UPDATE reservations SET status='CONFIRMED' WHERE id=?", (id,))
            lost = fault(db, "confirm_response_lost")
            delayed = fault(db, "confirm_response_delay")
    if delayed:
        time.sleep(15)
    if rejected:
        raise Problem(409, "MERCHANT_REJECTED", "가맹점이 주문을 거절했습니다.")
    if lost:
        raise Problem(
            503, "SIMULATED_RESPONSE_LOSS", "주문 확정 응답을 확인할 수 없습니다."
        )
    return {"executionId": id, "status": "CONFIRMED"}


@app.post(
    "/internal/v1/reservations/{id}/release",
    dependencies=[Depends(require("merchant:write"))],
)
def release(id: str):
    with transaction() as db:
        row = db.execute("SELECT * FROM reservations WHERE id=?", (id,)).fetchone()
        if row and row["status"] == "CONFIRMED":
            raise Problem(409, "ALREADY_CONFIRMED", "이미 확정된 주문입니다.")
        if row and row["status"] != "RELEASED":
            b = json.loads(row["basket"])
            m = merchant(db, b["merchantId"])
            for i in b["items"]:
                next(v for v in m["menus"] if v["id"] == i["menuId"])["stock"] += i[
                    "quantity"
                ]
            update(db, m)
            db.execute("UPDATE reservations SET status='RELEASED' WHERE id=?", (id,))
    return {"executionId": id, "status": "RELEASED"}


@app.post(
    "/internal/v1/test/faults/{name}", dependencies=[Depends(require("test:admin"))]
)
def set_fault(name: str, body: dict):
    if os.getenv("ENABLE_FAULTS") != "true":
        raise Problem(404, "NOT_FOUND", "찾을 수 없습니다.")
    if name not in [
        "confirm_rejected",
        "confirm_response_lost",
        "confirm_response_delay",
    ]:
        raise Problem(400, "INVALID_FAULT", "지원하지 않는 시나리오입니다.")
    with transaction() as db:
        db.execute(
            "INSERT INTO faults VALUES(?,?) ON CONFLICT(name) DO UPDATE SET remaining=excluded.remaining",
            (name, int(body.get("count", 1))),
        )
    return {"status": "OK"}


@app.post(
    "/internal/v1/test/catalog/{merchant_id}/{menu_id}",
    dependencies=[Depends(require("test:admin"))],
)
def change_menu(merchant_id: str, menu_id: str, body: dict):
    if os.getenv("ENABLE_FAULTS") != "true":
        raise Problem(404, "NOT_FOUND", "찾을 수 없습니다.")
    with transaction() as db:
        m = merchant(db, merchant_id)
        menu = next(v for v in m["menus"] if v["id"] == menu_id)
        for key in ["priceKrw", "stock"]:
            if key in body:
                menu[key] = max(0, int(body[key]))
        m["revision"] += 1
        update(db, m)
    return {"status": "OK"}


@app.get(
    "/internal/v1/test/reservations", dependencies=[Depends(require("test:admin"))]
)
def reservations():
    with transaction() as db:
        return [dict(r) for r in db.execute("SELECT * FROM reservations")]
