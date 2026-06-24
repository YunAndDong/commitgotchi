# Local FastAPI Worker Not Running Investigation

## Case Info

- Date: 2026-06-24
- Scope: Local Docker Compose report-analysis worker
- Question: Why is the local FastAPI report worker not running?

## Confirmed Evidence

- `docker-compose.yml:143-150` defines `fastapi-report-worker`, but `docker-compose.yml:144` gates it behind `profiles: ["worker"]`.
- `.env:31-33` explicitly says report flow A needs the worker to be started with `docker compose --profile worker up`.
- `docker compose config --services` returned only `postgres`, `fastapi`, `springboot`, and `vue`.
- `docker compose --profile worker config --services` included `fastapi-report-worker`.
- `docker compose ps --all` did not list a `fastapi-report-worker` container.
- `docker ps -a` did not show any `commitgotchi-fastapi-report-worker` container, so there is no evidence it started and then exited.
- `scripts/deploy.sh:626-644` contains logic that enables the compose `worker` profile when its deploy-time worker profile check is true.
- `scripts/README.md:165-166` documents that deploy auto-enables the `worker` profile when `REPORT_REQUEST_QUEUE_ENABLED=true`.

## Conclusion

The local FastAPI report worker is not running because the Docker Compose service is profile-gated and the current local Compose run did not enable the `worker` profile. This is not currently an observed worker crash or configuration failure; the worker container was never part of the active local Compose project.

## Fix Direction

- Start local report analysis with the worker profile enabled, for example `docker compose --profile worker up -d fastapi-report-worker`.
- For a smoother local demo, either document that startup command prominently or add `COMPOSE_PROFILES=worker` to the local run path when `REPORT_REQUEST_QUEUE_ENABLED=true`.
- Keep in mind that `REPORT_REQUEST_QUEUE_ENABLED=true` configures Spring/FastAPI env, but plain Docker Compose does not automatically activate a profile from that variable.
