"""Game-only TTS -> full worker exit -> RVC; no chat or LLM dependency."""
from __future__ import annotations

import array
import asyncio
import io
import json
import math
import os
import sys
import tempfile
import time
import wave
import wave
from collections import deque
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

from configuration import VoiceConfiguration, VoiceError
from resources import RUN_HARD_MIN_FREE_GIB, START_MIN_FREE_GIB, resource_problem
from runtime_support import (
    AudioCache,
    WorkerFailure,
    cache_key,
    file_signature,
    run_worker,
    save_audit,
)

MAX_AUDIO_BYTES = 8 * 1024 * 1024
MAX_AUDIO_SECONDS = 90
SOURCE = Path(__file__).resolve().parent
MAX_WAITING_JOBS = 16
MAX_REQUEST_SECONDS = 600
MAX_COVER_SECONDS = 360
MAX_COVER_BYTES = 80 * 1024 * 1024
MAX_DECODED_COVER_BYTES = 64 * 1024 * 1024


@dataclass
class QueueItem:
    future: asyncio.Future
    kind: str
    character: str
    payload: object
    deadline: float
    on_started: object = None


def validate_audio(raw: bytes) -> float:
    if not raw or len(raw) > MAX_AUDIO_BYTES:
        raise VoiceError("音频为空或超过8MiB，文字回复已保留。")
    try:
        with wave.open(io.BytesIO(raw), "rb") as audio:
            channels, width, rate, frames = audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()
            if (audio.getcomptype() != "NONE" or channels not in (1, 2) or width != 2
                    or not 8000 <= rate <= 48000 or not 0 < frames <= rate * MAX_AUDIO_SECONDS):
                raise VoiceError("语音需要90秒内的16位PCM WAV，文字回复已保留。")
            samples = audio.readframes(frames)
            if len(samples) != frames * channels * width:
                raise VoiceError("音频数据不完整，文字回复已保留。")
            values = array.array("h", samples)
            if sys.byteorder != "little":
                values.byteswap()
            rms = math.sqrt(sum(value * value for value in values) / len(values))
            if rms < 8 or max(abs(value) for value in values) < 64:
                raise VoiceError("本次合成接近静音，文字回复已保留。")
            return frames / rate
    except (wave.Error, EOFError) as exc:
        raise VoiceError("语音未返回有效PCM WAV，文字回复已保留。") from exc


@asynccontextmanager
async def gpu_lease(path: Path):
    """Owned advisory lock, using the same OS primitives as the old self-written helper."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as stream:
        stream.seek(0, 2)
        if not stream.tell():
            stream.write(b"\0")
            stream.flush()
        acquired = False
        try:
            while not acquired:
                stream.seek(0)
                try:
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    acquired = True
                except OSError as exc:
                    if exc.errno not in (11, 13, 35, 36) and getattr(exc, "winerror", None) not in (33, 36):
                        raise
                    await asyncio.sleep(0.25)
            yield
        finally:
            if acquired:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class VoiceEngine:
    def __init__(self, config: VoiceConfiguration):
        self.config = config
        self.gate = asyncio.Semaphore(1)
        self.waiting: deque[QueueItem] = deque()
        self.queue_changed = asyncio.Event()
        self.consumer: asyncio.Task | None = None
        self.active: tuple[asyncio.Future, asyncio.Task] | None = None
        self.cache = AudioCache(config.storage)
        self.last_audio_validated = False

    def health(self) -> dict:
        missing = self.config.missing()
        cover_missing = self.config.singing_missing()
        resource = resource_problem(self.config.directory, starting=True, stage="health")
        return {"protocol": 1, "backend": "ic2newera_standalone_voice", "optional": True,
                "runtime_mode": "sequential_one_shot_workers", "requires_astrbot": False,
                "requires_persistent_tts": False, "models_resident_in_http_process": False,
                "models_loaded": None if self.gate.locked() else False, "inference_verified": False,
                "last_audio_validated": self.last_audio_validated, "environment_and_base_files_present": not missing,
                "missing": missing, "model_start_allowed": resource is None, "resource_message": resource or "",
                "memory_start_threshold_gib": START_MIN_FREE_GIB, "memory_running_threshold_gib": RUN_HARD_MIN_FREE_GIB,
                "cover": {"available": not cover_missing, "missing": cover_missing,
                          "max_source_bytes": 16 * 1024 * 1024, "max_duration_seconds": MAX_COVER_SECONDS,
                          "max_output_bytes": MAX_COVER_BYTES, "route": "demucs_vocals_then_rvc_then_accompaniment"},
                "busy": self.gate.locked(), "queued": len(self.waiting), "max_waiting_jobs": MAX_WAITING_JOBS,
                "request_timeout_seconds": MAX_REQUEST_SECONDS,
                "max_audio_bytes": MAX_AUDIO_BYTES, "max_duration_seconds": MAX_AUDIO_SECONDS,
                "characters": [{"id": character, "configured": not self.config.missing(character),
                                "route": "tts_then_rvc", "missing": self.config.missing(character)}
                               for character in self.config.characters],
                "status": "文件存在与资源读数仅作诊断，独立运行时推理尚须实际请求验收。"}

    def cached(self, key: str) -> bytes | None:
        raw = self.cache.read(key)
        if raw:
            try:
                validate_audio(raw)
            except VoiceError:
                self.cache.remove(key)
                return None
        return raw

    def cleanup_generated(self) -> None:
        self.cache.clear_generated()
        root = self.config.storage.resolve()
        if not root.is_dir():
            return
        for path in root.glob("job-*"):
            try:
                resolved = path.resolve()
                if resolved.parent == root and resolved.is_dir():
                    import shutil
                    shutil.rmtree(resolved, ignore_errors=True)
            except OSError:
                pass

    async def shutdown(self) -> None:
        if self.consumer is not None and not self.consumer.done():
            self.consumer.cancel()
            await asyncio.gather(self.consumer, return_exceptions=True)
        while self.waiting:
            item = self.waiting.popleft()
            if not item.future.done():
                item.future.cancel()
        self.queue_changed.clear()
        self.cleanup_generated()

    async def synthesize(self, character: str, text: str) -> bytes:
        loop = asyncio.get_running_loop()
        future: asyncio.Future[bytes] = loop.create_future()
        deadline = time.monotonic() + MAX_REQUEST_SECONDS
        if len(self.waiting) >= MAX_WAITING_JOBS:
            raise VoiceError("语音等待队列已满，请稍后再试；文字已保留。", code="voice_busy")
        self.waiting.append(QueueItem(future, "speech", character, text, deadline))
        self.queue_changed.set()
        if self.consumer is None or self.consumer.done():
            self.consumer = asyncio.create_task(self._consume())
        try:
            return await future
        except asyncio.CancelledError:
            if not future.done():
                future.cancel()
            self._remove_waiting(future)
            if self.active is not None and self.active[0] is future:
                self.active[1].cancel()
            raise

    async def submit_cover(self, job_id: str, character: str, source: Path, work: Path, duration: float,
                           on_started=None) -> Path:
        if len(self.waiting) >= MAX_WAITING_JOBS:
            raise VoiceError("翻唱等待队列已满，请稍后再试。", code="voice_busy")
        loop = asyncio.get_running_loop()
        future = loop.create_future()
        item = QueueItem(future, "cover", character, (job_id, source, work, duration),
                         time.monotonic() + MAX_REQUEST_SECONDS, on_started)
        self.waiting.append(item)
        self.queue_changed.set()
        if self.consumer is None or self.consumer.done():
            self.consumer = asyncio.create_task(self._consume())
        try:
            return await future
        except asyncio.CancelledError:
            if not future.done():
                future.cancel()
            self._remove_waiting(future)
            if self.active is not None and self.active[0] is future:
                self.active[1].cancel()
            raise

    def _remove_waiting(self, future: asyncio.Future[bytes]) -> None:
        for item in self.waiting:
            if item.future is future:
                self.waiting.remove(item)
                break
        if not self.waiting:
            self.queue_changed.clear()

    async def _consume(self) -> None:
        while True:
            if not self.waiting:
                self.queue_changed.clear()
                await self.queue_changed.wait()
                continue
            item = self.waiting.popleft()
            future, character, deadline = item.future, item.character, item.deadline
            if not self.waiting:
                self.queue_changed.clear()
            try:
                if future.cancelled():
                    continue
                if item.on_started is not None:
                    item.on_started()
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    future.set_exception(TimeoutError("voice queue timeout"))
                    continue
                try:
                    async with asyncio.timeout(remaining):
                        work = asyncio.create_task(
                            self._synthesize(character, item.payload) if item.kind == "speech"
                            else self._cover(character, *item.payload))
                        self.active = (future, work)
                        result = await work
                    if not future.cancelled():
                        future.set_result(result)
                except asyncio.CancelledError:
                    if not future.done():
                        future.cancel()
                    current = asyncio.current_task()
                    if current is not None and current.cancelling():
                        raise
                except BaseException as exc:
                    if not future.cancelled():
                        future.set_exception(exc)
            finally:
                if self.active is not None and self.active[0] is future:
                    self.active = None

    async def _synthesize(self, character: str, text: str) -> bytes:
        async with self.gate:
            started = time.monotonic()
            audit = {"character": character, "status": "started", "runtime_mode": "standalone_sequential_workers",
                     "llm_called": False, "models_cached": False}
            config, base, profile = self.config, self.config.base, self.config.characters[character]
            try:
                missing = config.missing(character)
                if missing:
                    raise VoiceError("独立语音缺少配置/文件：" + "、".join(missing) + "。文字回复已保留。")
                language = "ja" if any("\u3040" <= c <= "\u30ff" for c in text) else (
                    "zh" if any("\u3400" <= c <= "\u9fff" for c in text) else "en")
                if language not in base["supported_languages"]:
                    raise VoiceError("基础音色未配置此语言，文字回复已保留。")
                payload = {"text": text, "text_lang": language, "ref_audio_path": str(base["reference_audio"]),
                           "prompt_text": base["prompt_text"], "prompt_lang": base["prompt_language"],
                           **{key: base[key] for key in ("seed", "top_k", "temperature", "repetition_penalty", "text_split_method", "speed_factor")}}
                tts_config = {"version": base["version"], "device": "cpu", "is_half": False,
                              "t2s_weights_path": str(base["gpt_weights"]), "vits_weights_path": str(base["sovits_weights"]),
                              "bert_base_path": str(base["bert_base_path"]), "cnhuhbert_base_path": str(base["cnhuhbert_base_path"])}
                job = {"gpt_root": str(base["root"]), "tts_config": tts_config, "payload": payload,
                       "gpt_weights": str(base["gpt_weights"]), "sovits_weights": str(base["sovits_weights"]),
                       "japanese_frontend_package": str(base["japanese_frontend_package"] or "")}
                base_key = cache_key({"job": job, "signatures": [file_signature(base[key]) for key in (
                    "python", "gpt_weights", "sovits_weights", "reference_audio")],
                    "japanese_frontend_adapter": file_signature(SOURCE / "japanese_frontend.py"),
                    "japanese_frontend_package": file_signature(base["japanese_frontend_package"] / "__init__.py")
                    if base["japanese_frontend_package"] else None,
                    "worker": file_signature(SOURCE / "tts_worker.py")})
                final_key = cache_key({"base": base_key, "profile": {key: str(value) for key, value in profile.items()},
                                       "model": file_signature(profile["model"]), "index": file_signature(profile["index"]),
                                       "worker": file_signature(SOURCE / "rvc_worker.py"),
                                       "rvc_seed": config.rvc["seed"],
                                       "rvc_python": file_signature(config.rvc["python"])})
                result = self.cached(final_key)
                audit["final_cache_hit"] = result is not None
                if result is None:
                    config.storage.mkdir(parents=True, exist_ok=True)
                    with tempfile.TemporaryDirectory(prefix="job-", dir=config.storage, ignore_cleanup_errors=True) as temporary:
                        work = Path(temporary)
                        source, request = work / "tts.wav", work / "request.json"
                        async with gpu_lease(config.lock):
                            result = self.cached(base_key)
                            audit["tts_cache_hit"] = result is not None
                            if result is None:
                                request.write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8")
                                await run_worker([base["python"], SOURCE / "tts_worker.py", "--request", request, "--output", source],
                                                 cwd=base["root"], project_root=config.storage, stage="tts", timeout_seconds=110, audit=audit)
                                if source.stat().st_size > MAX_AUDIO_BYTES:
                                    raise VoiceError("基础音频超过8MiB，文字已保留。")
                                result = source.read_bytes()
                                validate_audio(result)
                                self.cache.write(base_key, result)
                            source.write_bytes(result)
                            # run_worker returns only after the TTS process is fully reaped.
                            args = [config.rvc["python"], SOURCE / "rvc_worker.py", "--rvc-root", config.rvc["root"],
                                    "--model", profile["model"], "--source", source, "--output", work,
                                    "--index-rate", str(profile["index_rate"]), "--speaker-id", str(profile["speaker_id"]),
                                    "--semitone-shift", str(profile["semitone_shift"]), "--seed", str(config.rvc["seed"])]
                            if profile["index"]:
                                args.extend(["--index", profile["index"]])
                            await run_worker(args, cwd=config.rvc["root"], project_root=config.storage,
                                             stage="rvc", timeout_seconds=100, audit=audit)
                        final = work / "speech.wav"
                        await run_worker([config.ffmpeg, "-v", "error", "-nostdin", "-y", "-i", work / "rvc_vocals.wav",
                                          "-ar", "44100", "-ac", "1", "-c:a", "pcm_s16le", final], cwd=work,
                                         project_root=config.storage, stage="pcm", timeout_seconds=15, audit=audit, model_loading=False)
                        if final.stat().st_size > MAX_AUDIO_BYTES:
                            raise VoiceError("角色音频超过8MiB，文字已保留。")
                        result = final.read_bytes()
                    validate_audio(result)
                    self.cache.write(final_key, result)
                audit.update({"status": "generated", "bytes": len(result), "duration_seconds": validate_audio(result)})
                self.last_audio_validated = True
                return result
            except asyncio.CancelledError:
                audit["status"] = "cancelled"
                raise
            except WorkerFailure as exc:
                audit["status"] = "unavailable"
                raise VoiceError(str(exc)) from exc
            except VoiceError:
                audit["status"] = "unavailable"
                raise
            except TimeoutError:
                audit["status"] = "timeout"
                raise
            except Exception as exc:
                audit.update({"status": "unavailable", "failure_type": type(exc).__name__})
                raise VoiceError("独立语音未完成，请检查已有环境和模型；文字回复已保留。") from exc
            finally:
                audit["elapsed_seconds"] = round(time.monotonic() - started, 3)
                save_audit(config.storage, audit)

    async def _cover(self, character: str, job_id: str, source: Path, work: Path, duration: float) -> Path:
        async with self.gate:
            config = self.config
            singing = config.singing
            audit = {"character": character, "job_id": job_id, "status": "cover_started",
                     "runtime_mode": "standalone_sequential_workers", "llm_called": False}
            started = time.monotonic()
            try:
                missing = config.singing_missing()
                profile = config.characters[character]
                rvc_root = config.rvc["root"]
                rvc_assets = (rvc_root / "infer/vc/modules.py", rvc_root / "assets/rmvpe/rmvpe.pt",
                              rvc_root / "assets/hubert_base/config.json",
                              profile["model"])
                if (missing or not profile["enabled"] or any(not path.is_file() for path in rvc_assets)
                        or profile["index"] is not None and not profile["index"].is_file()):
                    raise VoiceError("独立翻唱依赖或角色音色文件不可用。", code="cover_unavailable")
                if not 0 < duration <= MAX_COVER_SECONDS:
                    raise VoiceError("歌曲时长超过翻唱上限。", code="duration_limit", status=413)
                output = work / "output.wav"
                demucs_output = work / "separated"
                async with gpu_lease(config.lock):
                    decoded = work / "decoded-source.wav"
                    await run_worker([config.ffmpeg, "-v", "error", "-nostdin", "-y", "-i", source,
                                      "-t", "360.05", "-vn", "-ar", "44100", "-ac", "2",
                                      "-c:a", "pcm_s16le", decoded],
                                     cwd=work, project_root=config.storage, stage="decode", timeout_seconds=90,
                                     audit=audit, model_loading=False)
                    if not decoded.is_file() or decoded.stat().st_size > MAX_DECODED_COVER_BYTES:
                        raise VoiceError("翻唱解码数据超过安全上限。", code="duration_limit", status=413)
                    try:
                        with wave.open(str(decoded), "rb") as decoded_audio:
                            valid_pcm = (decoded_audio.getnchannels() == 2 and decoded_audio.getsampwidth() == 2
                                         and decoded_audio.getframerate() == 44100
                                         and decoded_audio.getcomptype() == "NONE"
                                         and 0 < decoded_audio.getnframes() <= 44100 * MAX_COVER_SECONDS)
                    except (wave.Error, EOFError) as exc:
                        raise VoiceError("翻唱解码文件不是有效PCM WAV。", code="invalid_request", status=400) from exc
                    if not valid_pcm:
                        raise VoiceError("解码后的歌曲时长或PCM格式超出翻唱限制。",
                                         code="duration_limit", status=413)
                    await run_worker([singing["python"], SOURCE / "separation_worker.py", "--model", singing["model"],
                                      "--model-cache", singing["model_cache"], "--source", decoded,
                                      "--output", demucs_output], cwd=work, project_root=config.storage,
                                     stage="demucs", timeout_seconds=540, audit=audit)
                    stems = demucs_output / singing["model"] / decoded.stem
                    vocals, accompaniment = stems / "vocals.wav", stems / "no_vocals.wav"
                    args = [config.rvc["python"], SOURCE / "rvc_worker.py", "--rvc-root", config.rvc["root"],
                            "--model", profile["model"], "--source", vocals, "--output", work,
                            "--index-rate", str(profile["index_rate"]), "--speaker-id", str(profile["speaker_id"]),
                            "--semitone-shift", str(profile["semitone_shift"]), "--seed", str(config.rvc["seed"]),
                            "--max-duration-seconds", str(MAX_COVER_SECONDS)]
                    if profile["index"]:
                        args.extend(["--index", profile["index"]])
                    await run_worker(args, cwd=config.rvc["root"], project_root=config.storage,
                                     stage="rvc", timeout_seconds=300, audit=audit)
                    await run_worker([config.rvc["python"], SOURCE / "cover_mixer.py", "--vocals", work / "rvc_vocals.wav",
                                      "--accompaniment", accompaniment, "--output", output,
                                      "--accompaniment-gain", str(singing["accompaniment_gain"]),
                                      "--vocal-target-rms", str(singing["vocal_target_rms"])], cwd=work,
                                     project_root=config.storage, stage="mix", timeout_seconds=90,
                                     audit=audit, model_loading=False)
                if not output.is_file() or output.stat().st_size > MAX_COVER_BYTES:
                    raise VoiceError("翻唱成品超过80MiB或未生成。", code="cover_unavailable")
                audit.update({"status": "cover_generated", "bytes": output.stat().st_size,
                              "duration_seconds": duration})
                return output
            except asyncio.CancelledError:
                audit["status"] = "cover_cancelled"
                raise
            except WorkerFailure as exc:
                audit["status"] = "unavailable"
                raise VoiceError("翻唱工作进程未完成，请检查本机已安装环境。", code="cover_unavailable") from exc
            except VoiceError:
                audit["status"] = "unavailable"
                raise
            except TimeoutError as exc:
                audit["status"] = "timeout"
                raise VoiceError("翻唱生成超时。", code="voice_timeout", status=504) from exc
            except Exception as exc:
                audit.update({"status": "unavailable", "failure_type": type(exc).__name__})
                raise VoiceError("独立翻唱未完成，请检查本机依赖和音色文件。", code="cover_unavailable") from exc
            finally:
                audit["elapsed_seconds"] = round(time.monotonic() - started, 3)
                save_audit(config.storage, audit)
