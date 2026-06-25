# syntax=docker/dockerfile:1

FROM python:3.11-slim AS runtime
WORKDIR /app

# curl for the container healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Install deps first for better layer caching
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
# RAG problem-bank catalog (and embeddings). Required at runtime by the quiz
# recommender: load_problem_bank_store() reads data/rag/catalog/problems.jsonl.
# Without this the bank is empty in the container and recommendedQuizzes is always [].
COPY data ./data

# Run as non-root
RUN useradd -r -u 1001 appuser
USER appuser

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS http://localhost:8000/api/health >/dev/null 2>&1 || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
