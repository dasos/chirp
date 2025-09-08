from flask import Blueprint, request, jsonify
import os
import requests
from functools import lru_cache

bp = Blueprint("api", __name__)


@lru_cache(maxsize=1)
def get_api_key():
    key = os.getenv("OPENAI_API_KEY")
    if not key:
        raise ValueError("OPENAI_API_KEY environment variable is not set")
    return key


@bp.route("/session", methods=["GET"])
def get_session():
    try:
        api_key = get_api_key()
    except Exception as e:
        return jsonify({"error": "Unable to load API key", "details": str(e)}), 500

    model = request.args.get("model", "gpt-4o-realtime-preview")
    voice = request.args.get("voice", "verse")
    modalities = request.args.getlist("modalities") or ["text", "audio"]
    instructions = request.args.get("instructions")

    payload = {
        "model": model,
        "voice": voice,
        "modalities": modalities,
    }
    if instructions:
        payload["instructions"] = instructions

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "OpenAI-Beta": "realtime=v1",
    }

    try:
        r = requests.post(
            "https://api.openai.com/v1/realtime/sessions",
            headers=headers,
            json=payload,
            timeout=15,
        )
        return jsonify(r.json()), r.status_code
    except requests.RequestException as e:
        return jsonify({"error": "Upstream request failed", "details": str(e)}), 502
