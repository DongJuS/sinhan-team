import json
import logging
import os
import re
import time
import uuid
import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from fastapi.responses import JSONResponse

issuer = os.getenv("OIDC_ISSUER", "http://localhost:18081/realms/food")
jwks = jwt.PyJWKClient(
    os.getenv(
        "OIDC_JWKS", "http://keycloak:8080/realms/food/protocol/openid-connect/certs"
    ),
    cache_keys=True,
)
bearer = HTTPBearer(auto_error=False)


def require(permission):
    def verify(credentials: HTTPAuthorizationCredentials = Depends(bearer)):
        if credentials is None:
            raise HTTPException(401, "Authentication required")
        try:
            key = jwks.get_signing_key_from_jwt(credentials.credentials).key
            claims = jwt.decode(
                credentials.credentials,
                key,
                algorithms=["RS256"],
                audience="sinhan-api",
                issuer=issuer,
                options={"require": ["exp", "iat", "sub"]},
            )
        except jwt.PyJWTError:
            raise HTTPException(401, "Invalid access token")
        if permission not in claims.get("permissions", "").split():
            raise HTTPException(403, "Permission denied")
        return claims

    return verify


class Problem(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


def configure(app, name):
    logger = logging.getLogger(name)
    logging.basicConfig(level=logging.INFO)
    if os.getenv("LOG_FILE"):
        from logging.handlers import RotatingFileHandler

        handler = RotatingFileHandler(
            os.environ["LOG_FILE"], maxBytes=5000000, backupCount=3
        )
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)

    @app.middleware("http")
    async def trace(request, call_next):
        supplied = request.headers.get("x-request-id", "")
        request.state.request_id = (
            supplied
            if re.fullmatch(r"[A-Za-z0-9-]{1,64}", supplied)
            else str(uuid.uuid4())
        )
        start = time.monotonic()
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.request_id
        logger.info(
            json.dumps(
                {
                    "service": name,
                    "requestId": request.state.request_id,
                    "method": request.method,
                    "status": response.status_code,
                    "durationMs": round((time.monotonic() - start) * 1000),
                }
            )
        )
        return response

    @app.exception_handler(Problem)
    async def problem(request, exc):
        return JSONResponse(
            status_code=exc.status,
            content={
                "code": exc.code,
                "message": exc.message,
                "traceId": getattr(request.state, "request_id", None),
            },
        )

    if os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"):
        from opentelemetry import trace
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
            OTLPSpanExporter,
        )
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

        provider = TracerProvider(resource=Resource.create({"service.name": name}))
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
        trace.set_tracer_provider(provider)
        FastAPIInstrumentor.instrument_app(app)
        HTTPXClientInstrumentor().instrument()
