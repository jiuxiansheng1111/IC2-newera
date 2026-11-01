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
import re
import shutil
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field
from http import HTTPStatus
from pathlib import Path
from urllib.parse import urlsplit

from configuration import CHARACTERS, VoiceConfiguration, VoiceError
from engine import VoiceEngine

HOST = "127.0.0.1"
MAX_BODY = 20 * 1024
MAX_HEADERS = 16 * 1024
MAX_JOB_SECONDS = 610
MAX_COVER_STATUS_TTL_SECONDS = 610
MAX_COVER_UPLOAD = 16 * 1024 * 1024
MAX_COVER_STAGED = 96 * 1024 * 1024
MAX_COVER_ACTIVE = 2
MAX_COVER_OUTPUT = 80 * 1024 * 1024
LOG = logging.getLogger("ic2newera.voice")
_SYNCHRONIZE = 0x00100000
_WAIT_OBJECT_0 = 0
_WAIT_TIMEOUT = 258


class ParentProcessUnavailable(ValueError):
    pass


@dataclass
class CoverJob:
    job_id: str
    character: str
    source: Path
    directory: Path
    state: str = "queued"
    position: int = 0
    error: str = ""
    output: Path | None = None
    task: asyncio.Task | None = None
    touched: float = field(default_factory=time.monotonic)
    reserved_bytes: int = 0
    download_active: bool = False


def _speech_text(value: str) -> str:
    """Remove common Markdown presentation syntax while preserving its words and numbers."""
    text = re.sub(r"!\[([^\]]*)\]\([^\n)]*\)", r"\1", value)
    text = re.sub(r"\[([^\]]+)\]\([^\n)]*\)", r"\1", text)
    text = re.sub(r"(?m)^\s{0,3}(?:```[^\n]*|~~~[^\n]*)$", " ", text)
    text = re.sub(r"(?m)^\s{0,3}#{1,6}\s+", "", text)
    text = re.sub(r"(?m)^\s{0,3}>\s?", "", text)
    text = re.sub(r"(?m)^\s{0,3}[-+*]\s+", "", text)
    text = re.sub(r"\*\*(.*?)\*\*|__(.*?)__|~~(.*?)~~|`([^`]+)`", lambda match: next(
        (group for group in match.groups() if group is not None), ""), text)
    return " ".join(text.split())


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


async def respond_file(writer, path: Path, character: str, job_id: str) -> None:
    size = path.stat().st_size
    headers = {"Content-Type": "audio/wav", "Content-Length": str(size), "Connection": "close",
               "Cache-Control": "no-store", "X-IC2NE-Protocol": "1", "X-IC2NE-Character": character,
               "X-IC2NE-Job-ID": job_id, "X-Content-Type-Options": "nosniff"}
    head = "HTTP/1.1 200 OK\r\n" + "".join(f"{key}: {value}\r\n" for key, value in headers.items()) + "\r\n"
    writer.write(head.encode("ascii"))
    with path.open("rb") as stream:
        while chunk := stream.read(65536):
            writer.write(chunk)
            await writer.drain()


class VoiceServer:
    def __init__(self, engine: VoiceEngine):
        self.engine = engine
        self.connections: set[asyncio.Task] = set()
        self.cover_jobs: OrderedDict[str, CoverJob] = OrderedDict()
        self.cover_reserved_bytes = 0
        self.cover_uploads = 0

    def _cover_error(self, status: int, code: str, message: str) -> VoiceError:
        return VoiceError(message, code=code, status=status)

    def _owned_job_directory(self, directory: Path) -> bool:
        root = self.engine.config.storage.resolve()
        try:
            resolved = directory.resolve()
            return resolved.parent == root and resolved.name.startswith("cover-")
        except OSError:
            return False

    def _cleanup_cover(self, job: CoverJob) -> None:
        if self._owned_job_directory(job.directory):
            shutil.rmtree(job.directory, ignore_errors=True)
        self.cover_reserved_bytes = max(0, self.cover_reserved_bytes - job.reserved_bytes)
        job.reserved_bytes = 0
        job.output = None
        job.state = "deleted"
        job.touched = time.monotonic()

    async def _probe_cover_duration(self, source: Path, content_type: str) -> float:
        config = self.engine.config.singing
        if not config or not config["enabled"] or self.engine.config.singing_missing():
            raise self._cover_error(503, "cover_unavailable", "本机翻唱依赖或本地模型尚未配置。")
        try:
            process = await asyncio.create_subprocess_exec(
                str(config["ffprobe"]), "-v", "error", "-show_entries", "format=format_name,duration",
                "-of", "json", str(source),
                stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            try:
                stdout, _ = await asyncio.wait_for(process.communicate(), timeout=20)
            except BaseException:
                if process.returncode is None:
                    with contextlib.suppress(ProcessLookupError):
                        process.kill()
                await process.wait()
                raise
            if process.returncode != 0:
                raise self._cover_error(400, "invalid_request", "无法读取上传音频的媒体信息。")
            metadata = json.loads(stdout.decode("ascii"))
            container = metadata.get("format", {})
            format_names = set(container.get("format_name", "").split(","))
            allowed = {"mp3"} if content_type == "audio/mpeg" else {"wav"}
            if not format_names.intersection(allowed):
                raise self._cover_error(415, "invalid_request", "上传音频内容格式与Content-Type不匹配。")
            duration = float(container.get("duration", "nan"))
            if not 0 < duration <= 360:
                raise self._cover_error(413, "duration_limit", "歌曲时长需在1至360秒之间。")
            return duration
        except VoiceError:
            raise
        except (OSError, ValueError, TypeError, AttributeError, TimeoutError, UnicodeError) as exc:
            raise self._cover_error(400, "invalid_request", "无法读取上传音频的时长。") from exc

    async def _run_cover(self, job: CoverJob, duration: float) -> None:
        job.state = "queued"
        try:
            job.output = await self.engine.submit_cover(job.job_id, job.character, job.source, job.directory, duration,
                                                        on_started=lambda: setattr(job, "state", "running"))
            job.state = "succeeded"
        except asyncio.CancelledError:
            job.state = "cancelled"
        except VoiceError as exc:
            job.state = "failed"
            job.error = exc.code
        except TimeoutError:
            job.state = "failed"
            job.error = "voice_timeout"
        except Exception:
            job.state = "failed"
            job.error = "cover_unavailable"
        finally:
            job.position = 0
            job.touched = time.monotonic()

    def _prune_cover_jobs(self) -> None:
        cutoff = time.monotonic() - MAX_COVER_STATUS_TTL_SECONDS
        for job_id, job in list(self.cover_jobs.items()):
            if (not job.download_active and job.state in ("succeeded", "failed", "cancelled", "deleted")
                    and job.touched < cutoff):
                self._cleanup_cover(job)
                self.cover_jobs.pop(job_id, None)
        while len(self.cover_jobs) >= 16:
            removable = next(((job_id, job) for job_id, job in self.cover_jobs.items()
                              if job.state in ("failed", "cancelled", "deleted")), None)
            if removable is None:
                return
            job_id, job = removable
            self._cleanup_cover(job)
            self.cover_jobs.pop(job_id, None)

    async def shutdown_covers(self) -> None:
        tasks = [job.task for job in self.cover_jobs.values() if job.task is not None and not job.task.done()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        for job in self.cover_jobs.values():
            self._cleanup_cover(job)

    def _cover_status(self, job: CoverJob) -> dict:
        position = job.position if job.state == "queued" else 0
        if job.state == "queued":
            queued = [item for item in self.engine.waiting if item.kind == "cover" and item.payload[0] == job.job_id]
            if queued:
                position = list(self.engine.waiting).index(queued[0]) + 1
        result = {"protocol": 1, "job_id": job.job_id, "state": job.state, "position": position}
        if job.error:
            result["error"] = job.error
            result["message"] = "翻唱任务未完成，请检查本机依赖或稍后重试。"
        return result

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
                cover_upload = method == "POST" and urlsplit(path).path == "/v1/cover"
                maximum_body = MAX_COVER_UPLOAD if cover_upload else MAX_BODY
                if not 0 <= length <= maximum_body:
                    raise VoiceError("翻唱音源超过16MiB。" if cover_upload else "语音请求超过20KiB。",
                                     code="request_too_large", status=413)
            except (ValueError, UnicodeError, asyncio.LimitOverrunError) as exc:
                raise VoiceError("HTTP语音请求格式无效。", code="invalid_request", status=400) from exc
            if method == "GET" and path == "/health" and not length:
                body = json.dumps(self.engine.health(), ensure_ascii=False).encode("utf-8")
                await respond(writer, 200, body, "application/json; charset=utf-8")
                return
            parsed_path = urlsplit(path)
            if parsed_path.path == "/v1/cover" and method == "POST":
                if headers.get("x-ic2ne-protocol") != "1":
                    raise self._cover_error(400, "invalid_request", "缺少翻唱协议标识。")
                if headers.get("x-ic2ne-character") not in CHARACTERS:
                    raise self._cover_error(400, "invalid_character", "翻唱角色标识无效。")
                request_id = headers.get("x-ic2ne-request-id", "")
                try:
                    uuid.UUID(request_id)
                except (ValueError, AttributeError) as exc:
                    raise self._cover_error(400, "invalid_request", "翻唱请求ID必须是UUID。") from exc
                content_type = headers.get("content-type", "").split(";", 1)[0].casefold()
                suffix = {"audio/mpeg": ".mp3", "audio/wav": ".wav", "audio/x-wav": ".wav"}.get(content_type)
                if suffix is None:
                    raise self._cover_error(415, "invalid_request", "翻唱音源仅接受audio/mpeg或audio/wav。")
                if length <= 0:
                    raise self._cover_error(400, "invalid_request", "翻唱音源不能为空。")
                self._prune_cover_jobs()
                active_jobs = [entry for entry in self.cover_jobs.values() if entry.state != "deleted"]
                if (len(active_jobs) + self.cover_uploads >= MAX_COVER_ACTIVE
                        or self.cover_reserved_bytes + length > MAX_COVER_STAGED):
                    raise self._cover_error(503, "voice_busy", "翻唱暂存容量已满，请稍后再试。")
                if len(self.cover_jobs) >= 16:
                    raise self._cover_error(503, "voice_busy", "翻唱任务状态队列已满，请稍后再试。")
                if not self.engine.config.singing or not self.engine.config.singing["enabled"]:
                    raise self._cover_error(503, "cover_unavailable", "本机翻唱功能尚未启用。")
                if self.engine.config.singing_missing():
                    raise self._cover_error(503, "cover_unavailable", "本机翻唱依赖或本地模型尚未配置。")
                job_id = uuid.uuid4().hex
                directory = self.engine.config.storage / f"cover-{job_id}"
                source = directory / f"source{suffix}"
                if not self._owned_job_directory(directory):
                    raise self._cover_error(503, "cover_unavailable", "翻唱作业目录不可用。")
                directory.mkdir(parents=True, exist_ok=False)
                job = CoverJob(job_id, headers["x-ic2ne-character"], source, directory,
                               position=len(self.engine.waiting) + 1, reserved_bytes=length)
                self.cover_reserved_bytes += length
                self.cover_uploads += 1
                remaining = length
                upload_deadline = asyncio.get_running_loop().time() + 120
                try:
                    with source.open("xb") as stream:
                        while remaining:
                            timeout = min(30, upload_deadline - asyncio.get_running_loop().time())
                            if timeout <= 0:
                                raise TimeoutError("cover upload timeout")
                            chunk = await asyncio.wait_for(reader.readexactly(min(65536, remaining)), timeout=timeout)
                            stream.write(chunk)
                            remaining -= len(chunk)
                    duration = await self._probe_cover_duration(source, content_type)
                except BaseException:
                    self._cleanup_cover(job)
                    self.cover_uploads = max(0, self.cover_uploads - 1)
                    raise
                self.cover_jobs[job_id] = job
                self.cover_uploads = max(0, self.cover_uploads - 1)
                job.task = asyncio.create_task(self._run_cover(job, duration))
                body = json.dumps(self._cover_status(job), ensure_ascii=False).encode("utf-8")
                try:
                    await respond(writer, 202, body, "application/json; charset=utf-8")
                except ConnectionError:
                    job.task.cancel()
                    await asyncio.gather(job.task, return_exceptions=True)
                    self._cleanup_cover(job)
                    self.cover_jobs.pop(job_id, None)
                    raise
                return
            match = re.fullmatch(r"/v1/cover/([0-9a-f]{32})", parsed_path.path)
            if match:
                if headers.get("x-ic2ne-protocol") != "1":
                    raise self._cover_error(400, "invalid_request", "缺少翻唱协议标识。")
                job = self.cover_jobs.get(match.group(1))
                if job is None:
                    raise self._cover_error(404, "not_found", "翻唱任务不存在。")
                if headers.get("x-ic2ne-character") and headers["x-ic2ne-character"] != job.character:
                    raise self._cover_error(400, "invalid_character", "角色标识与翻唱任务不匹配。")
                if method == "GET" and not length:
                    if parsed_path.query == "download=1":
                        if job.state != "succeeded" or job.output is None or not job.output.is_file():
                            raise self._cover_error(409, "cover_not_ready", "翻唱成品尚未就绪。")
                        if job.output.stat().st_size > MAX_COVER_OUTPUT:
                            raise self._cover_error(413, "request_too_large", "翻唱成品超过80MiB。")
                        job.download_active = True
                        try:
                            await respond_file(writer, job.output, job.character, job.job_id)
                        finally:
                            job.download_active = False
                    else:
                        body = json.dumps(self._cover_status(job), ensure_ascii=False).encode("utf-8")
                        await respond(writer, 200, body, "application/json; charset=utf-8")
                    return
                if method == "DELETE" and not length:
                    if job.download_active:
                        raise self._cover_error(409, "voice_busy", "翻唱文件正在传输，请稍后清理。")
                    if job.state in ("queued", "running"):
                        if job.task is not None and not job.task.done():
                            job.task.cancel()
                            await asyncio.gather(job.task, return_exceptions=True)
                        job.state = "cancelled"
                    self._cleanup_cover(job)
                    await respond(writer, 204, b"", "application/json; charset=utf-8")
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
            character, text = value["character"], _speech_text(value["text"])
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
    janitor = asyncio.create_task(_cover_janitor(service))
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
        for task in (serving, monitor, janitor):
            if task is not None and not task.done():
                task.cancel()
        await asyncio.gather(*(task for task in (serving, monitor) if task is not None), return_exceptions=True)
        connections = list(service.connections)
        for task in connections:
            task.cancel()
        await asyncio.gather(*connections, return_exceptions=True)
        await asyncio.gather(janitor, return_exceptions=True)
        await service.shutdown_covers()
        await service.engine.shutdown()
        if parent is not None:
            parent.close()


async def _watch_parent(parent: ParentProcess) -> None:
    while parent.is_alive():
        await asyncio.sleep(0.5)


async def _cover_janitor(service: VoiceServer) -> None:
    while True:
        await asyncio.sleep(30)
        service._prune_cover_jobs()


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
