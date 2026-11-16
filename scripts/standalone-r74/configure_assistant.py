"""Bind an existing local QQ Cat Robot sign-in to a Minecraft instance's assistant."""
import argparse
import json
import os
from pathlib import Path
import re
import tempfile
import tomllib
from urllib.parse import urlsplit

ENDPOINT = "https://api.openai.com/v1/responses"
SECTION = re.compile(rb"(?m)^\s*\[ai_model\][^\S\r\n]*(?:#[^\r\n]*)?\r?\n")
NEXT_SECTION = re.compile(rb"(?m)^[ \t]*\[")


def field(name):
    return re.compile(rb"(?m)^([ \t]*" + name.encode("ascii") + rb"[ \t]*=[ \t]*)([^\r\n]*)")


def public_value(contents, name):
    matches = list(field(name).finditer(contents))
    if len(matches) > 1:
        raise ValueError(f"Multiple public fields named {name}")
    if not matches:
        return ""
    return tomllib.loads(matches[0].group(0).decode("utf-8"))[name]


def former_origin(url):
    try:
        address = urlsplit(url)
        if address.scheme not in ("https", "http") or not address.hostname or address.username is not None:
            return "unassigned"
        host = address.hostname.lower()
        if ":" in host:
            host = "[" + host + "]"
        port = address.port
        default = 443 if address.scheme == "https" else 80
        return f"{address.scheme}://{host}" + (f":{port}" if port is not None and port != default else "")
    except (ValueError, TypeError):
        return "unassigned"


def settings(existing, model):
    newline = b"\r\n" if b"\r\n" in existing else b"\n"
    sections = list(SECTION.finditer(existing))
    if len(sections) > 1:
        raise ValueError("Multiple ai_model sections")
    if not sections:
        prefix = existing + (newline if existing and not existing.endswith(b"\n") else b"") + b"[ai_model]" + newline
        body, suffix = b"", b""
    else:
        start = sections[0].end()
        following = NEXT_SECTION.search(existing, start)
        end = following.start() if following else len(existing)
        prefix, body, suffix = existing[:start], existing[start:end], existing[end:]
    values = {"provider": "chatgpt_subscription", "model": model, "baseUrl": ENDPOINT,
              "enabled": True, "timeoutSeconds": 90}
    # Bind any previously saved key to its prior address without inspecting the key field.
    if not public_value(body, "apiKeyOrigin"):
        values["apiKeyOrigin"] = former_origin(public_value(body, "baseUrl"))
    for name, value in values.items():
        entry = field(name)
        public_value(body, name)
        encoded = json.dumps(value, ensure_ascii=False).encode("utf-8")
        if entry.search(body):
            body = entry.sub(lambda match: match[1] + encoded, body)
        else:
            if body and not body.endswith(b"\n"):
                body += newline
            body += b"\t" + name.encode("ascii") + b" = " + encoded + newline
        if public_value(body, name) != value:
            raise ValueError(f"Could not persist {name}")
    return prefix + body + suffix


def replace_if_unchanged(path, old, new):
    if new == old:
        return
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
            temp = Path(handle.name)
            handle.write(new)
            handle.flush()
            os.fsync(handle.fileno())
        if (path.read_bytes() if path.exists() else b"") != old:
            raise RuntimeError(f"{path.name} changed during setup")
        os.replace(temp, path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-dir", required=True, type=Path)
    parser.add_argument("--qq-project", required=True, type=Path)
    parser.add_argument("--python", required=True, type=Path)
    parser.add_argument("--model", required=True, help="An ID offered by the connected account")
    args = parser.parse_args()
    config = args.config_dir.resolve(strict=True)
    root = args.qq_project.resolve(strict=True)
    python = args.python.resolve(strict=True)
    if not (config.is_dir() and (root / "app/chatgpt_subscription.py").is_file() and python.is_file()):
        parser.error("Expected an instance config directory, QQ Chat Robot project and its Python executable.")
    if not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,128}", args.model):
        parser.error("Invalid model ID")
    prefs = config / "ic2newera-client.toml"
    old = prefs.read_bytes() if prefs.exists() else b""
    changed = settings(old, args.model)
    binding = config / "ic2newera-subscription-runtime.json"
    public = (json.dumps({"projectRoot": str(root), "python": str(python)}, ensure_ascii=False, indent=2) + "\n").encode()
    replace_if_unchanged(binding, binding.read_bytes() if binding.exists() else b"", public)
    replace_if_unchanged(prefs, old, changed)
    print(f"Saved {ENDPOINT} with model {args.model} and local ChatGPT sign-in.")


if __name__ == "__main__":
    main()
