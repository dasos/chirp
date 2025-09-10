import os
from flask import Flask, render_template, send_from_directory, send_file

try:
    # Import API blueprint from package module
    from .api import bp as api_bp  # type: ignore
except Exception:
    api_bp = None


def create_app() -> Flask:
    app = Flask(__name__)
    project_root = os.path.abspath(os.path.join(app.root_path, os.pardir))

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

    # PWA: serve manifest, icons, and service worker from repo root
    @app.get("/manifest.json")
    def manifest():
        path = os.path.join(project_root, "manifest.json")
        resp = send_file(path, mimetype="application/manifest+json")
        resp.headers["Cache-Control"] = "no-cache"
        return resp

    @app.get("/icons/<path:filename>")
    def icons(filename: str):
        return send_from_directory(os.path.join(project_root, "icons"), filename)

    @app.get("/service-worker.js")
    def service_worker():
        path = os.path.join(project_root, "service-worker.js")
        resp = send_file(path, mimetype="application/javascript")
        resp.headers["Cache-Control"] = "no-cache"
        return resp

    return app


# Provide a module-level app for convenience (e.g., flask run)
app = create_app()
