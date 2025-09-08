# OpenAI Realtime Starter (Flask)

An easy way to try OpenAI’s Realtime API locally or in Docker. This app:

- Serves a simple UI at `/` to interact with a voice assistant.
- Exposes a server-side `/session` endpoint that creates Realtime sessions with OpenAI using your API key.
- Keeps your `OPENAI_API_KEY` on the server (never in client code).

## How It Works

- The browser loads `/` (served from `app/templates/realtime.html`).
- When starting a Realtime session, the browser fetches `/session` from this server.
- The server calls `POST https://api.openai.com/v1/realtime/sessions` with your `OPENAI_API_KEY` and returns the JSON response to the browser.
- The browser uses that session info to connect directly to OpenAI’s Realtime infrastructure (WebRTC / audio streaming as supported by your client code).

## Features

- Minimal Flask server with a clean `app/` package layout.
- Blueprinted API endpoint at `/session` with optional query params:
  - `model` (default `gpt-4o-realtime-preview`)
  - `voice` (default `verse`)
  - `modalities` (repeatable; default `text,audio`)
  - `instructions` (optional system prompt)
- Production-ready Gunicorn config (Docker-friendly).

## Prerequisites

- Python 3.11+
- An OpenAI API key with access to Realtime models: set `OPENAI_API_KEY`.

## Quick Start (Local)

1) Create a virtualenv and install dependencies

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

2) Set your API key

```bash
export OPENAI_API_KEY=sk-...  # never commit this
```

3) Run the app (bind to localhost only)

```bash
FLASK_APP=app flask run --host 127.0.0.1 --port 8000
# or
gunicorn wsgi:app --bind 127.0.0.1:8000
```

4) Open http://localhost:8000

## Docker (local only)

```bash
docker build -t openai-realtime-starter .
docker run --rm -p 127.0.0.1:8000:8000 -e OPENAI_API_KEY=sk-... openai-realtime-starter
```

Open http://localhost:8000

## API

- `GET /session`
  - Creates a Realtime session with OpenAI and returns the JSON.
  - Query params: `model`, `voice`, `modalities` (repeatable), `instructions`.
  - Requires `OPENAI_API_KEY` to be set on the server.

Example request:

```bash
curl "http://localhost:8000/session?model=gpt-4o-realtime-preview&voice=verse&modalities=text&modalities=audio"
```

## Configuration

- Env vars
  - `OPENAI_API_KEY` (required)

You can change defaults (model, voice, modalities) by passing query params to `/session` or by editing `app/api.py`.

## Project Structure

```
app/
  __init__.py          # Flask app factory, routes, healthz
  api.py               # /session endpoint
  templates/
    realtime.html      # UI served at '/'
wsgi.py                # Gunicorn entrypoint
requirements.txt       # Flask, requests, gunicorn
Dockerfile             # Container build
```

## Critical Security

- Do NOT expose this service directly to the internet.
- Always place it behind a reverse proxy with authentication and TLS.
- Restrict bind address to `127.0.0.1` and let the proxy connect locally.
- Never expose `OPENAI_API_KEY` in client code or commits.
- Lock down access to `/session` (auth, rate limiting, IP allowlists, CORS).

Recommended patterns:
- Nginx/Traefik/Caddy in front with:
  - Auth: Basic auth for demos, or OAuth/OIDC/SSO for production.
  - TLS termination and HSTS.
  - Rate limiting and request size caps.
  - CORS restricted to your origins.

Example (Nginx snippet):
```
location / {
  auth_basic "Restricted";
  auth_basic_user_file /etc/nginx/htpasswd;
  proxy_pass http://127.0.0.1:8000;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-Proto $scheme;
}
```

## Troubleshooting

- 401/403 from `/session`: verify `OPENAI_API_KEY` and that the key has access to Realtime models.
- Network/cert issues in Docker: ensure the container has internet access and correct time.
- UI not loading: check server logs and browser console for errors.

## License

This project is provided under the MIT License (see `LICENSE`).
