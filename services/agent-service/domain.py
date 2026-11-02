import itertools
import re
from pydantic import BaseModel, Field, ConfigDict


class Extraction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    budgetKrw: int | None = Field(default=None, ge=1000, le=1000000)
    people: int | None = Field(default=None, ge=1, le=10)
    excludedIngredients: list[str] = Field(default_factory=list)
    cuisine: str | None = None


def parse_request(data: dict) -> Extraction:
    message = data["message"].replace(",", "")
    budget = data.get("budgetKrw")
    if budget is None:
        match = re.search(r"(\d+(?:\.\d+)?)\s*만\s*원", message)
        if match:
            budget = int(float(match[1]) * 10000)
        else:
            match = re.search(r"(\d+)\s*원", message)
            if match:
                budget = int(match[1])
    people = data.get("people")
    if people is None:
        match = re.search(r"(\d+)\s*(?:명|인분|인)", message)
        if match:
            people = int(match[1])
        else:
            for word, value in {
                "혼자": 1,
                "한 명": 1,
                "두 명": 2,
                "세 명": 3,
                "네 명": 4,
            }.items():
                if word in message:
                    people = value
                    break
    exclusions = set(data.get("excludedIngredients") or [])
    for ingredient in ["땅콩", "우유", "밀", "계란", "새우", "돼지고기", "소고기"]:
        if ingredient in message and any(
            w in message for w in ["알레르기", "빼", "제외", "못 먹"]
        ):
            exclusions.add(ingredient)
    cuisine = data.get("cuisine") or next(
        (v for v in ["한식", "중식", "일식", "양식"] if v in message), None
    )
    return Extraction(
        budgetKrw=budget,
        people=people,
        excludedIngredients=sorted(exclusions),
        cuisine=cuisine,
    )


def candidates(catalog, conditions: Extraction, region: str, excluded: list[str]):
    result = []
    for merchant in catalog:
        if merchant["id"] in excluded or region not in merchant["regions"]:
            continue
        if conditions.cuisine and conditions.cuisine != merchant["cuisine"]:
            continue
        menus = [
            m
            for m in merchant["menus"]
            if not set(m["ingredients"]) & set(conditions.excludedIngredients)
            and m["stock"] > 0
        ]
        for combination in itertools.combinations_with_replacement(
            range(len(menus)), conditions.people
        ):
            counts = {i: combination.count(i) for i in set(combination)}
            if any(menus[i]["stock"] < n for i, n in counts.items()):
                continue
            items = [
                {
                    "menuId": menus[i]["id"],
                    "name": menus[i]["name"],
                    "quantity": n,
                    "unitPriceKrw": menus[i]["priceKrw"],
                    "options": {"spice": "normal"},
                    "ingredients": menus[i]["ingredients"],
                }
                for i, n in sorted(counts.items())
            ]
            # Do not filter by gross price: instant discounts can bring a basket under budget.
            result.append(
                {
                    "merchantId": merchant["id"],
                    "merchantName": merchant["name"],
                    "items": items,
                    "deliveryFeeKrw": merchant["deliveryFeeKrw"],
                    "deliveryMinutes": merchant["deliveryMinutes"],
                    "catalogVersion": str(merchant["revision"]),
                    "localCurrencyAccepted": merchant["localCurrencyAccepted"],
                }
            )
    return result
