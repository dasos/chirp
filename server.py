from flask import Flask, send_file

# Import the existing Blueprint from api.py (provided in the repo)
try:
    from api import bp as api_bp  # type: ignore
except Exception as e:
    api_bp = None  # Fallback if api.py is missing; app will still start


def create_app() -> Flask:
    app = Flask(__name__)

    # Register API blueprint at root so /session stays the same
    if api_bp is not None:
        app.register_blueprint(api_bp)

    @app.get("/")
    def index():
        # Serve the provided realtime.html from project root
        return send_file("realtime.html")

    @app.get("/healthz")
    def healthz():
        return {"ok": True}

    return app


# WSGI entrypoint
app = create_app()

