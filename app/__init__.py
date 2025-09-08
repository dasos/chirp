from flask import Flask, render_template

try:
    # Import API blueprint from package module
    from .api import bp as api_bp  # type: ignore
except Exception:
    api_bp = None


def create_app() -> Flask:
    app = Flask(__name__)

    # Register API blueprint at root so /session remains unchanged
    if api_bp is not None:
        app.register_blueprint(api_bp)

    @app.get("/")
    def index():
        # Serve the page from templates
        return render_template("realtime.html")

    @app.get("/healthz")
    def healthz():
        return {"ok": True}

    return app


# Provide a module-level app for convenience (e.g., flask run)
app = create_app()
