# Flask + Docker scaffold

This repo serves your existing `realtime.html` and `api.py` via Flask and a containerized Gunicorn server.

## Layout

- `server.py` — Flask entrypoint (registers `api.py` blueprint and serves `realtime.html`).
- `api.py` — Your existing API blueprint. Exposes `/session`.
- `realtime.html` — Your existing HTML UI, served at `/`.
- `requirements.txt` — Python dependencies (Flask, requests, gunicorn).
- `Dockerfile` — Container build for production serving.
- `.dockerignore` — Excludes local files from the image.

## Local run (optional)

- Create a virtualenv and install deps:
  - `python -m venv .venv && . .venv/bin/activate`
  - `pip install -r requirements.txt`
- Set environment (if using the `/session` endpoint):
  - `export OPENAI_API_KEY=sk-...`
- Run:
  - `FLASK_APP=server.py flask run --port 8000` or `gunicorn server:app --bind 0.0.0.0:8000`
- Open `http://localhost:8000`.

## Docker

- Build: `docker build -t flask-realtime .`
- Run: `docker run --rm -p 8000:8000 -e OPENAI_API_KEY=sk-... flask-realtime`
- Open: `http://localhost:8000`

Notes:
- The app registers your `api.py` Blueprint at root, so `/session` is available at `http://localhost:8000/session`.
- `realtime.html` currently calls a remote session endpoint. You can update it to fetch `/session` from this server if desired.
