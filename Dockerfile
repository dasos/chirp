# syntax=docker/dockerfile:1

FROM python:3.11-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# System deps (optional: add build tools if needed)
RUN apt-get update -y && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install Python deps first for better caching
COPY requirements.txt ./
RUN pip install -U pip && pip install -r requirements.txt

# Copy application code
COPY . .

# Expose app port
EXPOSE 8000

# Default to gunicorn WSGI server
CMD ["gunicorn", "server:app", "--bind", "0.0.0.0:8000", "--workers", "2", "--threads", "4", "--timeout", "120"]

