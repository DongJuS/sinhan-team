#!/usr/bin/env bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"  --set=orders_password="$ORDERS_DB_PASSWORD" --set=agent_password="$AGENT_DB_PASSWORD"  --set=benefits_password="$BENEFITS_DB_PASSWORD" --set=control_password="$CONTROL_DB_PASSWORD"  --set=payments_password="$PAYMENTS_DB_PASSWORD" --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
REVOKE ALL ON DATABASE food FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE ROLE orders_app LOGIN PASSWORD :'orders_password';
CREATE ROLE agent_app LOGIN PASSWORD :'agent_password';
CREATE ROLE benefits_app LOGIN PASSWORD :'benefits_password';
CREATE ROLE control_app LOGIN PASSWORD :'control_password';
CREATE ROLE payments_app LOGIN PASSWORD :'payments_password';
CREATE ROLE keycloak_app LOGIN PASSWORD :'keycloak_password';
GRANT CONNECT ON DATABASE food TO orders_app,agent_app,benefits_app,control_app,payments_app;
CREATE SCHEMA orders AUTHORIZATION orders_app;
CREATE SCHEMA agent AUTHORIZATION agent_app;
CREATE SCHEMA benefits AUTHORIZATION benefits_app;
CREATE SCHEMA control AUTHORIZATION control_app;
CREATE SCHEMA payments AUTHORIZATION payments_app;
CREATE DATABASE keycloak OWNER keycloak_app;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak TO keycloak_app;
SQL
