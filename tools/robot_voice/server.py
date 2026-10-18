"""Optional loopback voice HTTP process. Standard library only; no resident models."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import ctypes
import ipaddress
import json
import logging
import os
from http import HTTPStatus
from pathlib import Path

from configuration import CHARACTERS, VoiceConfiguration, VoiceError
from engine import VoiceEngine

HOST = "127.0.0.1"
MAX_BODY = 20 * 1024
MAX_HEADERS = 16 * 1024
MAX_JOB_SECONDS = 610
LOG = logging.getLogger("ic2newera.voice")
_SYNCHRONIZE = 0x00100000
_WAIT_OBJECT_0 = 0
_WAIT_TIMEOUT = 258


class ParentProcessUnavailable(ValueError):
    pass


def _positive_pid(value: str) -> int:
    try:
        pid = int(value, 10)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("parent PID must be a positive integer") from exc
    if pid <= 0:
        raise argparse.ArgumentTypeError("parent PID must be a positive integer")
    if pid == os.getpid():
        raise argparse.ArgumentTypeError("parent PID cannot be this process")
    return pid


class ParentProcess:
    """Hold a Windows process handle, or poll a POSIX PID without owning it."""

    def __init__(self, pid: int):
        self.pid = pid
        self.handle = None
        if os.name == "nt":
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
            kernel32.OpenProcess.restype = ctypes.c_void_p
            kernel32.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
            kernel32.WaitForSingleObject.restype = ctypes.c_uint32
            kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
            kernel32.CloseHandle.restype = ctypes.c_int
            self.kernel32 = kernel32
            self.handle = kernel32.OpenProcess(_SYNCHRONIZE, False, pid)
            if not self.handle:
                raise ParentProcessUnavailable(f"parent process {pid} is unavailable")
            if self.kernel32.WaitForSingleObject(self.handle, 0) == _WAIT_OBJECT_0:
                self.close()
                raise ParentProcessUnavailable(f"parent process {pid} has already exited")
        else:
            try:
                os.kill(pid, 0)
            except ProcessLookupError as exc:
                raise ParentProcessUnavailable(f"parent process {pid} does not exist") from exc

    def is_alive(self) -> bool:
        if os.name == "nt":
            state = self.kernel32.WaitForSingleObject(self.handle, 0)
            if state == _WAIT_TIMEOUT:
                return True
            if state == _WAIT_OBJECT_0:
                return False
            error = ctypes.get_last_error()
            raise OSError(error, "cannot inspect parent process")
        try:
            os.kill(self.pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True

    def close(self) -> None:
        if self.handle:
            self.kernel32.CloseHandle(self.handle)
            self.handle = None


async def respond(writer, status: int, body: bytes, content_type: str, character: str = "") -> None:
    headers = {"Content-Type": content_type, "Content-Length": str(len(body)), "Connection": "close",
               "Cache-Control": "no-store", "X-IC2NE-Protocol": "1", "X-Content-Type-Options": "nosniff"}
    if character:
        headers["X-IC2NE-Character"] = character
        headers["X-IC2NE-Voice-Route"] = "tts_then_rvc"
    head = f"HTTP/1.1 {status} {HTTPStatus(status).phrase}\r\n" + "".join(f"{key}: {value}\r\n" for key, value in headers.items()) + "\r\n"
    writer.write(head.encode("ascii"))
    for offset in range(0, len(body), 65536):
        writer.write(body[offset:offset + 65536])
        await writer.drain()


class VoiceServer:
    def __init__(self, engine: VoiceEngine):
        self.engine = engine
        self.connections: set[asyncio.Task] = set()

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        task = asyncio.current_task()
        full = len(self.connections) >= 64
        self.connections.add(task)
        job = disconnect = None
        try:
            if full:
                raise VoiceError("本机语音连接过多，请稍后再试；文字回复已保留。", code="voice_busy")
            peer = writer.get_extra_info("peername")
            if not peer or not ipaddress.ip_address(peer[0]).is_loopback:
                raise VoiceError("语音接口仅接受本机连接。", code="loopback_only", status=403)
            try:
                raw_headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=10)
                if len(raw_headers) > MAX_HEADERS:
                    raise VoiceError("请求头超过上限。", code="invalid_request", status=431)
                lines = raw_headers.decode("ascii").split("\r\n")
                method, path, version = lines[0].split(" ")
                headers = {}
                for line in lines[1:-2]:
                    name, separator, value = line.partition(":")
                    name = name.strip().casefold()
                    if not separator or not name or name in headers or line.startswith((" ", "\t")):
                        raise ValueError("invalid/duplicate header")
                    headers[name] = value.strip()
                if version not in ("HTTP/1.0", "HTTP/1.1") or "transfer-encoding" in headers:
                    raise ValueError("unsupported HTTP framing")
                if "origin" in headers:
                    raise VoiceError("游戏语音接口不接受网页跨域请求。", code="browser_origin_refused", status=403)
                length = int(headers.get("content-length", "0"))
                if not 0 <= length <= MAX_BODY:
                    raise VoiceError("语音请求超过20KiB。", code="request_too_large", status=413)
            except (ValueError, UnicodeError, asyncio.LimitOverrunError) as exc:
                raise VoiceError("HTTP语音请求格式无效。", code="invalid_request", status=400) from exc
            if method == "GET" and path == "/health" and not length:
                body = json.dumps(self.engine.health(), ensure_ascii=False).encode("utf-8")
                await respond(writer, 200, body, "application/json; charset=utf-8")
                return
            if method != "POST" or path != "/voice":
                raise VoiceError("未知的本机语音接口。", code="not_found", status=404)
            if headers.get("content-type", "").split(";", 1)[0].casefold() != "application/json":
                raise VoiceError("语音请求需使用application/json。", code="invalid_content_type", status=415)
            raw = await asyncio.wait_for(reader.readexactly(length), timeout=10)
            try:
                value = json.loads(raw)
            except (ValueError, UnicodeError, RecursionError) as exc:
                raise VoiceError("语音请求JSON无效。", code="invalid_request", status=400) from exc
            if (not isinstance(value, dict) or set(value) != {"protocol", "character", "text"}
                    or type(value["protocol"]) is not int or value["protocol"] != 1
                    or not isinstance(value["character"], str) or value["character"] not in CHARACTERS
                    or not isinstance(value["text"], str) or any(ord(c) < 32 and c not in "\n\r\t" for c in value["text"])):
                raise VoiceError("语音协议、角色或文本无效。", code="invalid_request", status=400)
            character, text = value["character"], " ".join(value["text"].split())
            if not text or len(value["text"]) > 360:
                raise VoiceError("语音文本需为1至360个字符，完整文字仍可阅读。", code="text_limit", status=400)
            job = asyncio.create_task(self.engine.synthesize(character, text))
            disconnect = asyncio.create_task(reader.read(1))
            done, _ = await asyncio.wait({job, disconnect}, timeout=MAX_JOB_SECONDS, return_when=asyncio.FIRST_COMPLETED)
            if disconnect in done:
                # A disconnected/cancelled game request must not leave model work running.
                return
            if job not in done:
                raise TimeoutError("voice request timeout")
            result = await job
            await respond(writer, 200, result, "audio/wav", character)
        except VoiceError as exc:
            await respond(writer, exc.status, json.dumps({"protocol": 1, "error": exc.code, "message": str(exc)},
                                                         ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")
        except TimeoutError:
            await respond(writer, 504, json.dumps({"protocol": 1, "error": "voice_timeout", "message": "语音生成超时，文字回复已保留。"},
                                                  ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")
        except (ConnectionError, asyncio.IncompleteReadError):
            pass
        except Exception as exc:  # noqa: BLE001 -- no text, dependency errors, config values or credentials in HTTP/logs.
            LOG.warning("Independent voice request failed (%s)", type(exc).__name__)
            await respond(writer, 503, json.dumps({"protocol": 1, "error": "voice_unavailable", "message": "独立语音运行环境暂不可用，文字回复已保留。"},
                                                  ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")
        finally:
            for child in (job, disconnect):
                if child is not None and not child.done():
                    child.cancel()
            await asyncio.gather(*(child for child in (job, disconnect) if child is not None), return_exceptions=True)
            writer.close()
            try:
                await writer.wait_closed()
            except ConnectionError:
                pass
            self.connections.discard(task)


async def serve(config: VoiceConfiguration, parent_pid: int | None = None):
    parent = ParentProcess(parent_pid) if parent_pid is not None else None
    service = VoiceServer(VoiceEngine(config))
    server = await asyncio.start_server(service.handle, HOST, config.port, limit=MAX_HEADERS + 4)
    print(f"IC2 New Era optional voice listening on {HOST}:{config.port}; models load only on explicit /voice requests.", flush=True)
    serving = asyncio.create_task(server.serve_forever())
    monitor = None
    if parent is not None:
        print(f"Monitoring launcher parent process {parent_pid}.", flush=True)
        monitor = asyncio.create_task(_watch_parent(parent))
    try:
        if monitor is None:
            await serving
        else:
            done, _ = await asyncio.wait({serving, monitor}, return_when=asyncio.FIRST_COMPLETED)
            if monitor in done:
                await monitor
                print(f"Launcher parent process {parent_pid} exited; stopping voice runtime.", flush=True)
                serving.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await serving
            else:
                await serving
    finally:
        server.close()
        await server.wait_closed()
        for task in (serving, monitor):
            if task is not None and not task.done():
                task.cancel()
        await asyncio.gather(*(task for task in (serving, monitor) if task is not None), return_exceptions=True)
        connections = list(service.connections)
        for task in connections:
            task.cancel()
        await asyncio.gather(*connections, return_exceptions=True)
        await service.engine.shutdown()
        if parent is not None:
            parent.close()


async def _watch_parent(parent: ParentProcess) -> None:
    while parent.is_alive():
        await asyncio.sleep(0.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("ic2newera-voice-runtime.json"))
    parser.add_argument("--parent-pid", type=_positive_pid,
                        help="Exit with the specified launcher process; omitted for manual mode")
    parser.add_argument("--check-config", action="store_true", help="Only inspect configuration/files/resources; no listener or model import")
    args = parser.parse_args()
    try:
        config = VoiceConfiguration(args.config)
        if args.check_config:
            print(json.dumps(VoiceEngine(config).health(), ensure_ascii=False, indent=2))
        else:
            asyncio.run(serve(config, args.parent_pid))
    except ParentProcessUnavailable as exc:
        print(f"Independent voice unavailable: {exc}; refusing to start without its launcher.", flush=True)
        raise SystemExit(2) from None
    except (OSError, ValueError, VoiceError) as exc:
        # File contents and external library tracebacks are never printed at startup.
        print(f"Independent voice unavailable ({type(exc).__name__}); check the dedicated voice configuration.", flush=True)
        raise SystemExit(2) from None
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
