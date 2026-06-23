from fastapi import FastAPI
from sqlalchemy import text

from .api.commitgotchi import router as commitgotchi_router
from .api.quiz_grading import router as quiz_grading_router
from .db import engine

app = FastAPI(title="Commit-Gotchi AI Service", version="0.1.0")
app.include_router(quiz_grading_router)
app.include_router(commitgotchi_router)


@app.get("/api/health")
def health():
    """Health endpoint that also verifies the PostgreSQL connection."""
    db_up = False
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        db_up = True
    except Exception:
        db_up = False
    return {"service": "fastapi", "status": "ok", "db": "up" if db_up else "down"}
