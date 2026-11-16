"""First-party stdin/stdout bridge to QQ Chat Robot's public subscription API."""

from __future__ import annotations

import asyncio
import io
import json
import sys
from pathlib import Path


def _safe_message(exc: Exception) -> str:
    # QQ's SubscriptionError intentionally contains only safe status codes and
    # request IDs. Avoid forwarding arbitrary exception text that could echo input.
    message = str(exc)
    if exc.__class__.__name__ == "SubscriptionError" and message:
        return message[:500]
    return f"本机订阅助手执行失败（{exc.__class__.__name__}）。"


def _encode_bounded(value: dict, limit: int) -> bytes:
    encoder = json.JSONEncoder(ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    output = io.BytesIO()
    size = 0
    for part in encoder.iterencode(value):
        data = part.encode("utf-8")
        size += len(data)
        if size > limit:
            raise ValueError("Responses 返回超过 8 MiB 限制。")
        output.write(data)
    return output.getvalue()


async def _run(project_root: Path, payload: dict) -> dict:
    sys.path.insert(0, str(project_root))
    from app.chatgpt_subscription import ChatGPTSubscription

    subscription = ChatGPTSubscription(timeout=900)
    try:
        if payload == {"__ic2newera_operation": "models"}:
            models = await subscription.list_models()
            # Return only identifiers provided by the subscription service. This
            # operation never calls respond() and never starts model inference.
            return {
                "ok": True,
                "response": {
                    "data": [
                        {"id": item["slug"]}
                        for item in models
                        if isinstance(item.get("slug"), str) and item["slug"]
                    ]
                },
            }
        response = await subscription.respond(payload)
        if not isinstance(response, dict):
            raise RuntimeError("subscription response must be a JSON object")
        return {"ok": True, "response": response}
    finally:
        await subscription.aclose()


def main() -> int:
    try:
        root = Path(sys.argv[1]).resolve(strict=True)
        if not root.is_dir() or not (root / "app" / "chatgpt_subscription.py").is_file():
            raise ValueError("配置的 QQ 工程目录无效。")
        raw = sys.stdin.buffer.read(12 * 1024 * 1024 + 1)
        if len(raw) > 12 * 1024 * 1024:
            raise ValueError("请求超过 12 MiB 限制。")
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError("Responses 请求必须为 JSON 对象。")
        # Keep standard Responses semantics; subscription adapter requires these
        # two transport/privacy fields. The private catalog operation never calls
        # respond() and therefore must not be reshaped as an inference payload.
        if payload != {"__ic2newera_operation": "models"}:
            payload["stream"] = True
            payload["store"] = False
        result = asyncio.run(_run(root, payload))
        output = _encode_bounded(result, 8 * 1024 * 1024)
        sys.stdout.buffer.write(output)
        sys.stdout.buffer.flush()
        return 0
    except Exception as exc:
        result = {"ok": False, "error": _safe_message(exc)}
        sys.stdout.buffer.write(_encode_bounded(result, 16 * 1024))
        sys.stdout.buffer.flush()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
