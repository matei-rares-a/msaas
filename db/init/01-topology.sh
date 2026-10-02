#!/usr/bin/env bash
# Database topology (REQ-DATA-04, REQ-DATA-05). Run once by the postgres entrypoint on an empty data directory.
# Passwords come from the container environment (see .env); psql variables keep them out of the SQL text.

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v auth_pw="$AUTH_DB_PASSWORD" \
  -v org_pw="$ORG_DB_PASSWORD" \
  -v billing_pw="$BILLING_DB_PASSWORD" \
  -v notif_pw="$NOTIF_DB_PASSWORD" \
  -v relay_pw="$OUTBOX_RELAY_DB_PASSWORD" <<'SQL'
-- One login role per service; the outbox relay is exempt from RLS and gets table grants in P3.2.
CREATE ROLE auth_svc LOGIN PASSWORD :'auth_pw';
CREATE ROLE org_svc LOGIN PASSWORD :'org_pw';
CREATE ROLE billing_svc LOGIN PASSWORD :'billing_pw';
CREATE ROLE notif_svc LOGIN PASSWORD :'notif_pw';
CREATE ROLE outbox_relay LOGIN BYPASSRLS PASSWORD :'relay_pw';

ALTER ROLE auth_svc SET search_path = auth;
ALTER ROLE org_svc SET search_path = org;
ALTER ROLE billing_svc SET search_path = billing;
ALTER ROLE notif_svc SET search_path = notif;

-- Global, non-sharded data: identities (later also catalogs and the tenant -> shard map).
CREATE DATABASE saas_global;
-- Tenant-scoped data, one schema per service.
CREATE DATABASE saas_shard_1;

REVOKE ALL ON DATABASE saas_global FROM PUBLIC;
REVOKE ALL ON DATABASE saas_shard_1 FROM PUBLIC;
GRANT CONNECT ON DATABASE saas_global TO auth_svc, outbox_relay;
GRANT CONNECT ON DATABASE saas_shard_1 TO org_svc, billing_svc, notif_svc, outbox_relay;

\connect saas_global
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA auth AUTHORIZATION auth_svc;

\connect saas_shard_1
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA org AUTHORIZATION org_svc;
CREATE SCHEMA billing AUTHORIZATION billing_svc;
CREATE SCHEMA notif AUTHORIZATION notif_svc;
SQL
