from pydantic import BaseModel, Field, ConfigDict


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message: str = Field(min_length=1, max_length=2000)
    address: str = Field(min_length=1, max_length=300)
    region: str
    budgetKrw: int | None = None
    people: int | None = None
    excludedIngredients: list[str] | None = None
    cuisine: str | None = None


class PlanCommand(BaseModel):
    requestId: str
    input: Input
    excludedMerchants: list[str] = Field(default_factory=list)


class ExplanationInput(BaseModel):
    quote: dict
    evaluatedCandidates: int = Field(ge=1)
