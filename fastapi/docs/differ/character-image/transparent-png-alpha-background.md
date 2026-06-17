# Character Image Deferred Note - Transparent PNG Alpha Background

## Context

Story 1 implements the core character image generation service and intentionally validates generated image bytes before local storage. The validator accepts only PNG bytes with an actual alpha channel.

During a manual Gemini smoke test with the design keyword `별의커비에 나오는 커비`, Gemini returned a visually usable 2x3 sprite sheet. However, the background was a checkerboard pattern baked into the image pixels instead of true transparency. The service correctly classified the result as `MISSING_ALPHA_CHANNEL` and did not save it to `fastapi/runtime/data/character-images/`.

## Problem

The current canonical prompt asks for a transparent PNG and alpha transparency, but the image model may still produce an image that only looks transparent because it includes a checkerboard background.

This creates a product-quality gap:

- the generation may look close to the intended sprite sheet
- the PNG can still fail the service guardrail because it has no alpha channel
- repeated fallback results may occur even when the visible sprite art is acceptable

## Deferred Decision

Do not weaken Story 1 validation and do not accept non-alpha PNGs as generated character assets.

The alpha-channel requirement is important for the game asset contract. Story 1 should keep rejecting checkerboard-background PNGs so downstream UI and frame extraction do not need to guess whether the background is removable.

## Follow-Up Direction

When character image generation is refined, prefer one or more of these options:

- strengthen the image prompt to explicitly forbid checkerboard backgrounds and require real transparent pixels outside each sprite
- run a second image-edit/background-removal pass when Gemini returns a non-alpha PNG
- add deterministic post-processing only if the background pattern is simple and reliable enough to remove safely
- add a production smoke test script that records safe metadata only, not API keys, full prompts, or image bytes
- keep generated smoke files outside git, or under the ignored runtime directory only

The follow-up should preserve the current safety rule: only true transparent PNG assets are stored as `READY`; non-alpha outputs remain `FALLBACK` until a prompt or post-processing step converts them into valid transparent PNGs.
