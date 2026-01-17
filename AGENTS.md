# Repository Guidelines

## Project Structure & Module Organization
- `app/` — Flask package.
  - `__init__.py` — app factory, routes (`/`, `/healthz`, PWA assets).
  - `api.py` — Blueprint exposing `GET /session` (calls OpenAI Realtime).
  - `templates/realtime.html` — minimal UI.
- `wsgi.py` — Gunicorn entrypoint (`wsgi:app`).
- `requirements.txt` — Flask and Gunicorn pins.
- `Dockerfile` — production-ready image (port 8000).

Keep new endpoints in Blueprints within `app/` and keep templates under `app/templates/`.

## Build, Test, and Development Commands
- Create venv and install deps:
  - `python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt`
- Run locally (requires `OPENAI_API_KEY`):
  - `FLASK_APP=app flask run --host 127.0.0.1 --port 8000`
  - or `gunicorn wsgi:app --bind 127.0.0.1:8000`
- Docker (local):
  - `docker build -t chirp .`
  - `docker run --rm -p 127.0.0.1:8000:8000 -e OPENAI_API_KEY=sk-... chirp`

GitHub Actions publishes images to Docker Hub on `main` and releases.

## Coding Style & Naming Conventions
- Language: Python 3.11+. Use type hints where practical.
- Indentation: 4 spaces; max line length 100–120 chars.
- Naming: modules and functions `snake_case`; classes `CamelCase`.
- Flask: register routes via Blueprints; keep handlers small and side‑effect free.
- Configuration comes from env vars (never commit secrets). Prefer `os.getenv`.

## Testing Guidelines
- No test framework is pinned yet. If adding tests, prefer `pytest` with:
  - Files: `tests/test_*.py`; name tests `test_*`.
  - Fast, isolated tests using Flask app factory (`create_app()`), exercising `/healthz` and `/session`.
  - Optional dev-only deps can go in a separate `requirements-dev.txt`.

## Commit & Pull Request Guidelines
- Commits: concise, imperative subject (e.g., “Add session query params”).
- Scope one logical change per commit; include brief body when relevant.
- PRs: include description, rationale, and testing notes; link issues.
  - Update `README.md` when endpoints, env vars, or Docker behavior change.
  - For UI tweaks, add a small screenshot of `realtime.html` if helpful.

## Security & Configuration
- Never expose `OPENAI_API_KEY` in client code or commits.
- Bind to `127.0.0.1`; put a reverse proxy with TLS/auth in front for any exposure.
- Lock down `/session` (auth, rate limits, CORS). Treat Docker images as production artifacts.
