import os
import time
import json
import psycopg
from pathlib import Path


class ServiceClient:
    def __init__(self):
        self.token = None
        self.until = 0

    async def headers(self, client, request_id):
        if time.monotonic() >= self.until:
            r = await client.post(
                os.getenv(
                    "OIDC_TOKEN_URL",
                    "http://keycloak:8080/realms/food/protocol/openid-connect/token",
                ),
                data={
                    "grant_type": "client_credentials",
                    "client_id": "agent-service",
                    "client_secret": os.environ["SERVICE_CLIENT_SECRET"],
                },
            )
            r.raise_for_status()
            data = r.json()
            self.token = data["access_token"]
            self.until = time.monotonic() + data["expires_in"] - 30
        return {"Authorization": "Bearer " + self.token, "X-Request-ID": request_id}


class PlanStore:
    def connect(self):
        return psycopg.connect(os.environ["DATABASE_URL"])

    def migrate(self):
        with self.connect() as db:
            import hashlib

            db.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended('agent-migrations',0))"
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations(version text PRIMARY KEY, checksum text NOT NULL)"
            )
            for path in sorted(Path("migrations").glob("*.sql")):
                checksum = hashlib.sha256(path.read_bytes()).hexdigest()
                row = db.execute(
                    "SELECT checksum FROM schema_migrations WHERE version=%s",
                    (path.name,),
                ).fetchone()
                if row:
                    if row[0] != checksum:
                        raise ValueError("Applied migration changed: " + path.name)
                else:
                    db.execute(path.read_text())
                    db.execute(
                        "INSERT INTO schema_migrations VALUES(%s,%s)",
                        (path.name, checksum),
                    )

    def save(self, request_id, result, runtime, node_trace):
        with self.connect() as db:
            db.execute(
                "INSERT INTO plan_runs(request_id,result,runtime,node_trace) VALUES (%s,%s::jsonb,%s::jsonb,%s::jsonb)",
                (
                    request_id,
                    json.dumps(result, ensure_ascii=False),
                    json.dumps(runtime),
                    json.dumps(node_trace),
                ),
            )
