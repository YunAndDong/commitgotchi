from __future__ import annotations

import unittest
from typing import Any

from fastapi.testclient import TestClient

from app.config import Settings
from app.image.schemas import fallback_image_result, ready_image_result
from app.main import app
from app.api.commitgotchi import get_settings, get_sprite_generator


class RecordingGenerator:
    """Fake sprite generator that records kwargs and returns a fixed result."""

    def __init__(self, result: Any) -> None:
        self.result = result
        self.calls: list[dict[str, Any]] = []

    def __call__(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return self.result


class CommitgotchiEndpointTest(unittest.TestCase):
    def setUp(self) -> None:
        app.dependency_overrides.clear()
        self.client = TestClient(app)

    def tearDown(self) -> None:
        app.dependency_overrides.clear()

    def _no_auth_settings(self) -> None:
        app.dependency_overrides[get_settings] = lambda: Settings(
            _env_file=None, spring_internal_api_secret=None
        )

    def test_valid_request_returns_section_4_4_success_shape(self) -> None:
        self._no_auth_settings()
        generator = RecordingGenerator(
            ready_image_result(local_path="/runtime/character-images/users/1/sprite.png")
        )
        app.dependency_overrides[get_sprite_generator] = lambda: generator

        response = self.client.post("/api/ai/commitgotchi", json=_request_payload())

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["userId"], 1)
        self.assertEqual(body["status"], "OK")
        # request s3ObjectUrl is echoed back, not used as a storage destination.
        self.assertEqual(body["s3ObjectUrl"], _request_payload()["s3ObjectUrl"])
        self.assertEqual(body["spriteSheetUrl"], "/runtime/character-images/users/1/sprite.png")
        # Spring client validation: frameMap joy/sad/angry must be int[2] coords.
        frame_map = body["spriteMeta"]["frameMap"]
        for emotion in ("joy", "sad", "angry"):
            coord = frame_map[emotion]
            self.assertEqual(len(coord), 2)
            self.assertTrue(all(isinstance(axis, int) for axis in coord))
        self.assertTrue(body["spriteMeta"]["transparent"])

    def test_generator_receives_prompt_user_and_s3_object_url(self) -> None:
        self._no_auth_settings()
        generator = RecordingGenerator(ready_image_result(local_path="/runtime/x.png"))
        app.dependency_overrides[get_sprite_generator] = lambda: generator

        self.client.post("/api/ai/commitgotchi", json=_request_payload())

        self.assertEqual(len(generator.calls), 1)
        call = generator.calls[0]
        self.assertEqual(call["design_keyword"], _request_payload()["prompt"])
        self.assertEqual(call["user_id"], 1)
        # prompt is the design keyword; s3_object_url is the Spring-chosen upload
        # location (INFRA-5). The raw camelCase request field is not forwarded.
        self.assertEqual(call["s3_object_url"], _request_payload()["s3ObjectUrl"])
        self.assertNotIn("s3ObjectUrl", call)

    def test_fallback_result_returns_section_4_4_fail_shape(self) -> None:
        self._no_auth_settings()
        generator = RecordingGenerator(fallback_image_result("IMAGE_GENERATION_FAILED"))
        app.dependency_overrides[get_sprite_generator] = lambda: generator

        response = self.client.post("/api/ai/commitgotchi", json=_request_payload())

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "FAIL")
        self.assertIsNone(body["spriteSheetUrl"])
        self.assertIsNone(body["spriteMeta"])
        self.assertEqual(body["errorMessage"], "IMAGE_GENERATION_FAILED")

    def test_invalid_request_returns_validation_error_without_calling_generator(self) -> None:
        self._no_auth_settings()
        generator = RecordingGenerator(ready_image_result(local_path="/runtime/x.png"))
        app.dependency_overrides[get_sprite_generator] = lambda: generator

        payload = _request_payload()
        payload.pop("prompt")
        response = self.client.post("/api/ai/commitgotchi", json=payload)

        self.assertEqual(response.status_code, 422)
        self.assertEqual(generator.calls, [])

    def test_internal_auth_rejects_missing_and_wrong_token_without_leakage(self) -> None:
        expected_secret = "expected-internal-secret"
        received_secret = "wrong-internal-secret"
        generator = RecordingGenerator(ready_image_result(local_path="/runtime/x.png"))
        app.dependency_overrides[get_sprite_generator] = lambda: generator
        app.dependency_overrides[get_settings] = lambda: Settings(
            _env_file=None, spring_internal_api_secret=expected_secret
        )

        missing = self.client.post("/api/ai/commitgotchi", json=_request_payload())
        wrong = self.client.post(
            "/api/ai/commitgotchi",
            json=_request_payload(),
            headers={"Authorization": f"Internal {received_secret}"},
        )

        self.assertEqual(missing.status_code, 401)
        self.assertEqual(wrong.status_code, 401)
        self.assertEqual(generator.calls, [])
        leak = missing.text + wrong.text
        self.assertNotIn(expected_secret, leak)
        self.assertNotIn(received_secret, leak)

    def test_valid_internal_token_is_accepted(self) -> None:
        secret = "expected-internal-secret"
        generator = RecordingGenerator(ready_image_result(local_path="/runtime/x.png"))
        app.dependency_overrides[get_sprite_generator] = lambda: generator
        app.dependency_overrides[get_settings] = lambda: Settings(
            _env_file=None, spring_internal_api_secret=secret
        )

        response = self.client.post(
            "/api/ai/commitgotchi",
            json=_request_payload(),
            headers={"Authorization": f"Internal {secret}"},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "OK")

    def test_blank_secret_allows_local_dev_without_auth(self) -> None:
        self._no_auth_settings()
        generator = RecordingGenerator(ready_image_result(local_path="/runtime/x.png"))
        app.dependency_overrides[get_sprite_generator] = lambda: generator

        response = self.client.post("/api/ai/commitgotchi", json=_request_payload())

        self.assertEqual(response.status_code, 200)

    def test_existing_routes_are_preserved(self) -> None:
        health = self.client.get("/api/health")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()["service"], "fastapi")


def _request_payload() -> dict[str, Any]:
    return {
        "userId": 1,
        "s3ObjectUrl": "s3://commitgotchi-sprites/characters/42/sprite-sheet.png",
        "prompt": "작고 둥근 초록 슬라임",
    }


if __name__ == "__main__":
    unittest.main()
