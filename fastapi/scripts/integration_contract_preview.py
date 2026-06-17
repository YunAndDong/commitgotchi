from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any, Mapping


FASTAPI_ROOT = Path(__file__).resolve().parents[1]
if str(FASTAPI_ROOT) not in sys.path:
    sys.path.insert(0, str(FASTAPI_ROOT))

from app.api.quiz_grading import QuizGradingRequest, process_quiz_grading_request  # noqa: E402
from app.config import Settings  # noqa: E402
from app.integration.report_consumer import process_report_request_message  # noqa: E402
from app.integration.schemas import SpringCallbackResponse  # noqa: E402
from app.integration.spring_client import SpringCallbackClient  # noqa: E402


class RecordingSpringTransport:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def post_json(
        self,
        url: str,
        *,
        headers: Mapping[str, str],
        payload: Mapping[str, Any],
        timeout_seconds: float,
    ) -> SpringCallbackResponse:
        self.calls.append(
            {
                "url": url,
                "headers": dict(headers),
                "payload": dict(payload),
                "timeoutSeconds": timeout_seconds,
            }
        )
        return SpringCallbackResponse(
            status_code=200,
            body={"received": True, "preview": True},
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Preview FastAPI/Spring integration contracts without Spring Boot, "
            "AWS SQS, or Gemini."
        ),
    )
    parser.add_argument(
        "--compact",
        action="store_true",
        help="Print compact JSON instead of indented JSON.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output = build_preview()
    print(
        json.dumps(
            output,
            ensure_ascii=False,
            indent=None if args.compact else 2,
        )
    )
    return 0


def build_preview() -> dict[str, Any]:
    transport = RecordingSpringTransport()
    client = _preview_callback_client(transport)

    report_process_result = process_report_request_message(
        _sample_report_message(),
        report_service=_sample_report_service,
        callback_client=client,
    )
    report_callback = _redacted_call(transport.calls[-1])

    quiz_process_result = process_quiz_grading_request(
        QuizGradingRequest(**_sample_quiz_request()),
        grader=_sample_quiz_grader,
        callback_client=client,
    )
    quiz_callback = _redacted_call(transport.calls[-1])

    return {
        "mode": "fastapi-only-preview",
        "externalCalls": {
            "springBoot": False,
            "awsSqs": False,
            "gemini": False,
        },
        "reportSqsConsumer": {
            "processResult": asdict(report_process_result),
            "springCallback": report_callback,
        },
        "quizGradingWebhook": {
            "processResult": quiz_process_result.to_dict(),
            "springCallback": quiz_callback,
        },
    }


def _preview_callback_client(
    transport: RecordingSpringTransport,
) -> SpringCallbackClient:
    return SpringCallbackClient(
        settings=Settings(
            _env_file=None,
            spring_boot_internal_base_url="http://fake-spring.local:8080",
            spring_internal_api_secret="preview-secret",
            spring_report_callback_path="/api/report",
            spring_quiz_grade_result_path="/api/internal/quizzes/grade-result",
            spring_callback_timeout_seconds=2,
        ),
        transport=transport,
    )


def _sample_report_message() -> dict[str, Any]:
    return {
        "requestId": "report-preview-1",
        "userId": 1,
        "targetDate": "2026-06-16",
        "userMetadata": {
            "displayName": "fastapi-preview",
            "reportDirection": {
                "focus": "JPA N+1과 트랜잭션 복습",
            },
        },
        "characterMetadata": {
            "characterId": 10,
            "name": "커밋고치",
            "personality": "칭찬은 짧게, 부족한 부분은 명확히 짚어주는 성격",
            "currentStats": {
                "db": 120,
                "algorithm": 90,
                "cs": 80,
                "network": 60,
                "framework": 140,
            },
        },
        "dailyReport": {
            "title": "JPA fetch join 복습",
            "content": (
                "오늘은 JPA N+1 문제가 언제 생기는지 정리하고 "
                "fetch join과 batch size의 차이를 공부했다."
            ),
        },
    }


def _sample_report_service(**_: Any) -> Mapping[str, Any]:
    return {
        "status": "SUCCESS",
        "scoreDelta": {
            "db": 3,
            "algorithm": 0,
            "cs": 0,
            "network": 0,
            "framework": 2,
        },
        "emotion": "JOY",
        "statusMessage": "좋아요, 오늘 학습 내용이 캐릭터 성장에 반영됐어요.",
        "dailyReport": {
            "text": "N+1 문제의 원인과 해결 전략을 잘 정리했습니다.",
            "feedback": "fetch join과 batch size의 적용 기준을 다음에 비교해보세요.",
        },
        "nextRecommendation": {
            "topics": ["fetch join", "batch size"],
            "rationale": "N+1 해결책을 상황별로 구분하면 이해가 단단해집니다.",
        },
        "recommendedQuizzes": [
            {
                "problemId": 101,
                "question": "JPA N+1 문제란 무엇인가?",
                "modelAnswer": "연관 데이터를 반복 조회하면서 추가 쿼리가 발생하는 문제입니다.",
                "scoreAllocation": {"db": 3, "framework": 2},
                "submissionId": "preview-should-be-filtered",
            }
        ],
    }


def _sample_quiz_request() -> dict[str, Any]:
    return {
        "submissionId": "quiz-preview-1",
        "userId": 1,
        "characterId": 10,
        "quizId": 55,
        "problemId": 101,
        "question": "JPA N+1 문제란 무엇인가?",
        "modelAnswer": "연관 엔티티를 지연 로딩할 때 추가 쿼리가 반복해서 발생하는 문제다.",
        "userAnswer": "연관 데이터를 조회할 때 쿼리가 N번 더 나가는 문제입니다.",
        "scoreAllocation": {
            "db": 3,
            "algorithm": 0,
            "cs": 0,
            "network": 0,
            "framework": 2,
        },
        "characterMetadata": {
            "personality": "칭찬은 짧게, 부족한 부분은 명확히 알려주는 성격",
        },
        "callbackUrl": "https://ignored.example/api/internal/quizzes/grade-result",
    }


def _sample_quiz_grader(**kwargs: Any) -> Mapping[str, Any]:
    return {
        "submissionId": kwargs["submissionId"],
        "status": "GRADED",
        "scoreAllocation": kwargs["scoreAllocation"],
        "scoreDelta": {
            "db": 9,
            "algorithm": 0,
            "cs": 0,
            "network": 0,
            "framework": 2,
        },
        "feedback": "N+1의 핵심을 잘 잡았습니다. 해결 전략까지 쓰면 더 좋아요.",
        "confidence": 0.91,
    }


def _redacted_call(call: Mapping[str, Any]) -> dict[str, Any]:
    headers = dict(call.get("headers", {}))
    if "Authorization" in headers:
        headers["Authorization"] = "Internal <redacted>"
    return {
        "url": call.get("url"),
        "headers": headers,
        "timeoutSeconds": call.get("timeoutSeconds"),
        "payload": call.get("payload"),
    }


if __name__ == "__main__":
    raise SystemExit(main())
