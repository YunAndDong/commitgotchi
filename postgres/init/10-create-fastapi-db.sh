#!/bin/bash
# Runs once on first container start (empty data volume).
#
# POSTGRES_DB already created the Spring Boot database. Here we create the
# separate FastAPI (Intelligence) database on the same instance, owned by the
# same role. Name comes from FASTAPI_DB_NAME (passed through in docker-compose).
set -euo pipefail

FASTAPI_DB_NAME="${FASTAPI_DB_NAME:-commitgotchi_ai}"

echo "[init] Ensuring FastAPI database '${FASTAPI_DB_NAME}' exists..."

# CREATE DATABASE cannot be parameterized or run conditionally in plain SQL,
# so we check the catalog first and create only if missing (idempotent-ish).
EXISTS="$(psql -tAc "SELECT 1 FROM pg_database WHERE datname = '${FASTAPI_DB_NAME}'" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}")"

if [ "${EXISTS}" != "1" ]; then
  psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c "CREATE DATABASE ${FASTAPI_DB_NAME} OWNER ${POSTGRES_USER};"
  echo "[init] Created database '${FASTAPI_DB_NAME}'."
else
  echo "[init] Database '${FASTAPI_DB_NAME}' already exists, skipping."
fi
