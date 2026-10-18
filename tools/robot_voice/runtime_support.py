"""Own helper migrated from ChatRobot app/minecraft_voice_runtime.py; no QQ imports."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

from resources import _read_memory_status, resource_problem

_LOG = logging.getLogger("ic2newera.voice")
_CACHE_TOTAL = 8 * 1024 * 1024
_CACHE_ITEM = 2 * 1024 * 1024
_CACHE_COUNT = 32
_HASH = re.compile(r"[0-9a-f]{64}\.wav")


class WorkerFailure(RuntimeError):
    pass


def file_signature(value: str | Path | None) -> tuple[str, int, int] | None:
    if not value:
        return None
    path = Path(value).resolve(strict=True)
    stat = path.stat()
    return str(path), stat.st_size, stat.st_mtime_ns


def cache_key(data: Any) -> str:
    return hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


class AudioCache:
    """Only small generated WAVs; no model cache and no recursive cleanup."""
    def __init__(self, project_root: Path):
        self.root = (project_root / "audio_cache").resolve()
        self.enabled = self.root.is_relative_to(project_root.resolve())

    def read(self, key: str) -> bytes | None:
        if not self.enabled:
            return None
        path = self.root / f"{key}.wav"
        if not _HASH.fullmatch(path.name) or path.resolve().parent != self.root:
            return None
        try:
            if not path.is_file() or path.stat().st_size > _CACHE_ITEM:
                return None
            raw = path.read_bytes()
            path.touch()
            return raw
        except OSError:
            return None

    def write(self, key: str, raw: bytes) -> None:
        if not self.enabled or len(raw) > _CACHE_ITEM or not re.fullmatch(r"[0-9a-f]{64}", key):
            return
        try:
            self.root.mkdir(parents=True, exist_ok=True)
            path = self.root / f"{key}.wav"
            if path.resolve().parent != self.root:
                return
            temporary = self.root / f"{key}.tmp"
            temporary.write_bytes(raw)
            temporary.replace(path)
            entries = sorted((entry for entry in self.root.glob("*.wav")
                              if _HASH.fullmatch(entry.name) and entry.resolve().parent == self.root),
                             key=lambda entry: entry.stat().st_mtime, reverse=True)
            used = 0
            for count, entry in enumerate(entries):
                used += entry.stat().st_size
                if count >= _CACHE_COUNT or used > _CACHE_TOTAL:
                    entry.unlink(missing_ok=True)
        except OSError:
            # Cache failures must not discard a successfully generated reply.
            pass

    def remove(self, key: str) -> None:
        path = self.root / f"{key}.wav"
        if self.enabled and _HASH.fullmatch(path.name) and path.resolve().parent == self.root:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass

    def clear_generated(self) -> None:
        """Remove only this runtime's hash-named WAV cache entries and temp files."""
        if not self.enabled or not self.root.is_dir():
            return
        try:
            for path in self.root.iterdir():
                if path.is_file() and (_HASH.fullmatch(path.name) or re.fullmatch(r"[0-9a-f]{64}\.tmp", path.name)):
                    path.unlink(missing_ok=True)
        except OSError as exc:
            _LOG.warning("Minecraft voice cache cleanup failed (%s)", type(exc).__name__)


def resource_check(project_root: Path, stage: str, *, starting: bool) -> None:
    issue = resource_problem(project_root, starting=starting, stage=stage)
    if issue:
        raise WorkerFailure(issue.replace("翻唱", "语音转换") + "文字回复已保留。")


async def wait_for_resources(project_root: Path, stage: str, *, timeout_seconds: float = 600) -> None:
    """Wait asynchronously before model loading; caller request deadline bounds total wait."""
    deadline = time.monotonic() + timeout_seconds
    while True:
        issue = resource_problem(project_root, starting=True, stage=stage)
        if issue is None:
            return
        if not issue.startswith("可用内存不足"):
            raise WorkerFailure(issue.replace("翻唱", "语音转换") + "文字回复已保留。")
        if time.monotonic() >= deadline:
            raise WorkerFailure("等待可用内存超过10分钟，本次语音已取消；文字回复已保留。")
        await asyncio.sleep(2)


async def _stop_owned_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        try:
            await asyncio.to_thread(subprocess.run, ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW,
                                    timeout=5, check=False)
        except (OSError, subprocess.TimeoutExpired):
            try:
                process.kill()
            except ProcessLookupError:
                pass
    else:
        import signal
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        await asyncio.to_thread(process.wait, timeout=5)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
        except ProcessLookupError:
            pass
        await asyncio.to_thread(process.wait, timeout=5)


def _worker_environment() -> dict[str, str]:
    names = {"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "APPDATA", "LOCALAPPDATA", "USERPROFILE",
             "HOMEDRIVE", "HOMEPATH", "PUBLIC", "TEMP", "TMP", "PROCESSOR_ARCHITECTURE",
             "NUMBER_OF_PROCESSORS", "CUDA_PATH", "CUDA_VISIBLE_DEVICES"}
    environment = {key: value for key, value in os.environ.items() if key.upper() in names}
    environment.update({"PYTHONUTF8": "1", "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1",
                        "is_g2pw": "false"})
    environment.update({name: "2" for name in (
        "OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "NUMEXPR_NUM_THREADS",
    )})
    if os.name == "nt":
        temporary = Path(os.environ.get("PUBLIC", r"C:\Users\Public")) / "GPTSoVITS_temp"
        temporary.mkdir(parents=True, exist_ok=True)
        environment.update({"TEMP": str(temporary), "TMP": str(temporary)})
    return environment


async def run_worker(
    args: list[str | Path], *, cwd: Path, project_root: Path, stage: str,
    timeout_seconds: float, audit: dict, model_loading: bool = True,
) -> str:
    """Return after worker exit, using file logs instead of Windows Proactor pipe transports."""
    if model_loading:
        await wait_for_resources(project_root, stage)
    else:
        resource_check(project_root, stage, starting=False)
    started = time.monotonic()
    minimum_phys, report, primary_error = None, None, None
    phases, seen_phases = [], set()

    def tail(path: Path, limit: int = 8192) -> bytes:
        with path.open("rb") as stream:
            stream.seek(max(0, path.stat().st_size - limit))
            return stream.read(limit)

    def read_phases(path: Path) -> None:
        for line in tail(path, 65536).decode("utf-8", errors="replace").splitlines():
            try:
                event = json.loads(line)
                index = event.get("phase_index") if isinstance(event, dict) else None
                if (event.get("minecraft_voice_phase") and type(index) is int
                        and index not in seen_phases and len(phases) < 40):
                    phases.append({key: event[key] for key in (
                        "minecraft_voice_phase", "phase_index", "elapsed_seconds", "monotonic_seconds",
                        "working_set_bytes", "private_bytes") if key in event})
                    seen_phases.add(index)
            except (ValueError, AttributeError):
                pass

    def failure_diagnostic(stdout_path: Path, stderr_path: Path) -> dict | None:
        # Only our two controlled voice workers qualify. Their environment is
        # allowlisted and their job contains no QQ/LLM settings or credentials.
        expected = {"tts": "tts_worker.py", "rvc": "rvc_worker.py"}.get(stage)
        if not expected or len(args) < 2 or Path(args[1]).resolve() != Path(__file__).with_name(expected):
            return None
        try:
            stderr = tail(stderr_path, 24000).decode("utf-8", errors="replace")[-3000:]
            stdout = tail(stdout_path, 8000).decode("utf-8", errors="replace")[-1000:]
            return {"limit_characters": 4000, "stderr_tail": stderr, "stdout_tail": stdout}
        except OSError as exc:
            return {"unavailable": type(exc).__name__}

    with tempfile.TemporaryDirectory(prefix="ic2newera-worker-log-", ignore_cleanup_errors=True) as log_directory:
        stdout_path, stderr_path = (Path(log_directory) / name for name in ("stdout.log", "stderr.log"))
        with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
            spawn = asyncio.create_task(asyncio.to_thread(
                subprocess.Popen, [str(argument) for argument in args], cwd=str(cwd),
                env=_worker_environment(), stdin=subprocess.DEVNULL, stdout=stdout_file, stderr=stderr_file,
                creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW) if os.name == "nt" else 0,
                start_new_session=os.name != "nt",
            ))
            try:
                process = await asyncio.shield(spawn)
            except asyncio.CancelledError:
                process = await spawn
                await _stop_owned_process(process)
                raise
            try:
                while process.poll() is None:
                    await asyncio.to_thread(read_phases, stdout_path)
                    if os.name == "nt":
                        memory = _read_memory_status()
                        if memory is not None:
                            minimum_phys = min(minimum_phys or memory.available_phys, memory.available_phys)
                    resource_check(project_root, stage, starting=False)
                    if time.monotonic() - started > timeout_seconds:
                        raise TimeoutError(f"{stage} timeout")
                    if stdout_path.stat().st_size + stderr_path.stat().st_size > 2 * 1024 * 1024:
                        raise WorkerFailure("语音工作进程日志超过上限，已停止本次语音。")
                    await asyncio.sleep(0.25)
                stdout = await asyncio.to_thread(tail, stdout_path)
                await asyncio.to_thread(read_phases, stdout_path)
                if process.returncode:
                    _LOG.warning("Minecraft voice worker failed stage=%s exit=%s", stage, process.returncode)
                    raise WorkerFailure(f"{stage} 未完成，请检查本机语音运行环境；文字回复已保留。")
                try:
                    parsed = json.loads(stdout.decode("utf-8", errors="replace").strip().splitlines()[-1])
                    if isinstance(parsed, dict):
                        report = {key: parsed[key] for key in ("backend", "device", "half_precision", "frames",
                                                               "sample_rate", "section_count", "index_used", "g2pw_enabled",
                                                               "hf_load_dtype", "hf_direct_cuda") if key in parsed}
                except (ValueError, IndexError):
                    pass
                return stdout.decode("utf-8", errors="replace")
            except BaseException as exc:
                primary_error = exc
                raise
            finally:
                cleanup = asyncio.create_task(_stop_owned_process(process))
                cleanup_error = None
                try:
                    await asyncio.shield(cleanup)
                except asyncio.CancelledError:
                    await cleanup
                except Exception as exc:  # noqa: BLE001 -- preserve the primary resource/cancel error.
                    cleanup_error = type(exc).__name__
                    _LOG.warning("Minecraft voice worker cleanup failed (%s)", cleanup_error)
                try:
                    await asyncio.to_thread(read_phases, stdout_path)
                except OSError:
                    pass
                diagnostic = None
                if process.returncode not in (None, 0) and not isinstance(primary_error, asyncio.CancelledError):
                    diagnostic = await asyncio.to_thread(failure_diagnostic, stdout_path, stderr_path)
                audit.setdefault("stages", []).append({
                    "stage": stage, "elapsed_seconds": round(time.monotonic() - started, 3),
                    "worker_exited": process.poll() is not None, "exit_code": process.returncode,
                    "minimum_available_phys_bytes": minimum_phys, "worker_report": report,
                    "phases": phases, "cleanup_error": cleanup_error,
                    "failure_type": type(primary_error).__name__ if primary_error else None,
                    "failure_diagnostic": diagnostic,
                })
                # A cleanup problem must never replace the original resource/cancel failure.
                if cleanup_error and primary_error is None:
                    raise WorkerFailure("本次语音进程收尾未完成，请检查本机状态；文字已保留。")


def save_audit(project_root: Path, audit: dict) -> None:
    try:
        root = project_root
        root.mkdir(parents=True, exist_ok=True)
        (root / "last-request.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        pass
