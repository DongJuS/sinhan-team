from domain import parse_request, candidates


def test_extract_korean_budget_people_and_exclusions():
    c = parse_request({"message": "2만원 안에 두 명 점심, 땅콩 알레르기"})
    assert c.budgetKrw == 20000 and c.people == 2 and c.excludedIngredients == ["땅콩"]


def test_missing_constraints_stay_missing():
    c = parse_request({"message": "점심 추천해줘"})
    assert c.budgetKrw is None and c.people is None


def test_explicit_budget_is_not_overridden_by_message():
    assert parse_request({"message": "100만원", "budgetKrw": 15000}).budgetKrw == 15000


def test_baskets_do_not_mix_merchants_or_excluded_ingredients():
    catalog = [
        {
            "id": "m",
            "name": "가게",
            "regions": ["SEOUL"],
            "cuisine": "한식",
            "deliveryFeeKrw": 1000,
            "deliveryMinutes": 20,
            "revision": 1,
            "localCurrencyAccepted": True,
            "menus": [
                {
                    "id": "a",
                    "name": "a",
                    "priceKrw": 10000,
                    "ingredients": [],
                    "stock": 2,
                },
                {
                    "id": "b",
                    "name": "b",
                    "priceKrw": 5000,
                    "ingredients": ["땅콩"],
                    "stock": 5,
                },
            ],
        }
    ]
    c = parse_request({"message": "2만원 두 명 땅콩 제외"})
    rows = candidates(catalog, c, "SEOUL", [])
    assert len(rows) == 1 and rows[0]["items"][0]["quantity"] == 2
    assert rows[0]["items"][0]["menuId"] == "a"
