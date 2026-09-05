# ClinicOS — opencode Agent Notes

## Run Locally (one shot)

```bash
./dev-up.ps1
```

`dev-up.ps1` bundles: `docker compose up -d postgres minio` (idempotent, tolerates pre-existing containers) → waits for postgres healthy → ensures the `app_rw` role has a password (fresh-cluster safe) → kills stale leftover ClinicOS java processes on port 8080 → runs `mvn -pl apps/api spring-boot:run` in the foreground.

- App: http://localhost:8080
- OpenAPI: http://localhost:8080/api-docs/ui
- Postgres: localhost:5432, db `clinicos`, user `postgres`/`app_rw` both `local-dev-only`
- MinIO: localhost:9000 (API) / 9001 (console), `minioadmin`/`minioadmin`

## Migrations

Flyway runs **inside the app** at startup (`spring-boot-starter-flyway`). Do not migrate manually; the old `migrate` docker-compose service was removed.

## Source of truth

Full project guide: `CLAUDE.md`. Phase status + plans: `docs/roadmap.md`.