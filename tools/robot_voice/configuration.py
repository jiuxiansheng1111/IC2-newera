"""Explicit, non-secret game voice configuration; never reads .env or QQ modules."""
from __future__ import annotations

import json
import math
from pathlib import Path

CHARACTERS = ("tomori", "anon", "rana", "soyo", "taki", "sakiko", "uika", "umiri")


class VoiceError(RuntimeError):
    def __init__(self, message: str, *, code: str = "voice_unavailable", status: int = 503):
        super().__init__(message)
        self.code, self.status = code, status


def _object(value, required: set[str], optional: set[str] = frozenset()) -> dict:
    if not isinstance(value, dict) or not required.issubset(value) or set(value) - required - optional:
        raise VoiceError("独立语音配置字段无效；仅接受已列出的语音环境、模型与采样参数。")
    return value


def _number(value, minimum, maximum, *, integer=False):
    if (type(value) not in ((int,) if integer else (int, float)) or not math.isfinite(value)
            or not minimum <= value <= maximum):
        raise VoiceError("独立语音数值配置超出允许范围。")
    return value


class VoiceConfiguration:
    def __init__(self, path: Path):
        self.path = path.resolve(strict=True)
        if self.path.stat().st_size > 65536 or self.path.suffix != ".json":
            raise VoiceError("独立语音配置需为不超过64KiB的JSON文件。")
        data = _object(json.loads(self.path.read_text(encoding="utf-8-sig")),
                       {"protocol", "port", "data_directory", "ffmpeg", "base_tts", "rvc", "characters"},
                       {"gpu_lock_file"})
        if type(data["protocol"]) is not int or data["protocol"] != 1:
            raise VoiceError("独立语音配置协议必须为1。")
        self.port = _number(data["port"], 1024, 65535, integer=True)
        if self.port in (8012, 9880):
            raise VoiceError("独立游戏语音应使用单独端口9881，不能占用旧QQ桥或TTS端口。")
        self.directory = self.path.parent

        def local_path(value):
            if not isinstance(value, str) or not value.strip() or any(ord(c) < 32 for c in value):
                raise VoiceError("语音环境/模型路径为空或无效。")
            result = Path(value).expanduser()
            return (result if result.is_absolute() else self.directory / result).resolve()

        self.storage = local_path(data["data_directory"])
        if self.storage == self.directory or not self.storage.is_relative_to(self.directory):
            raise VoiceError("独立语音生成目录必须是tools/robot_voice内的专用子目录。")
        self.lock = local_path(data.get("gpu_lock_file", str(self.storage / "gpu.lock")))
        if self.lock.suffix != ".lock":
            raise VoiceError("GPU协调文件必须使用专用.lock文件。")
        self.ffmpeg = local_path(data["ffmpeg"])
        base = dict(_object(data["base_tts"], {
            "root", "python", "version", "gpt_weights", "sovits_weights", "bert_base_path", "cnhuhbert_base_path",
            "reference_audio", "prompt_text", "prompt_language", "supported_languages", "seed", "top_k",
            "temperature", "repetition_penalty", "text_split_method",
        }, {"japanese_frontend_package"}))
        for name in ("root", "python", "gpt_weights", "sovits_weights", "bert_base_path", "cnhuhbert_base_path", "reference_audio"):
            base[name] = local_path(base[name])
        base["japanese_frontend_package"] = local_path(base["japanese_frontend_package"]) if base.get("japanese_frontend_package") else None
        if (base["gpt_weights"].suffix != ".ckpt" or base["sovits_weights"].suffix != ".pth"
                or base["reference_audio"].suffix.casefold() not in (".wav", ".mp3", ".ogg", ".flac")):
            raise VoiceError("基础TTS需明确的.ckpt/.pth检查点及音频参考文件。")
        if base["version"] not in ("v1", "v2") or base["prompt_language"] not in ("zh", "ja", "en"):
            raise VoiceError("当前独立基础TTS仅支持既有v1/v2模型和中/日/英参考转录。")
        if not isinstance(base["prompt_text"], str) or len(base["prompt_text"]) > 2000:
            raise VoiceError("基础TTS参考转录格式无效。")
        languages = base["supported_languages"]
        if not isinstance(languages, list) or not languages or any(x not in ("zh", "ja", "en") for x in languages):
            raise VoiceError("基础TTS支持语言需明确列出zh/ja/en。")
        if base["text_split_method"] not in ("cut0", "cut1", "cut2", "cut3", "cut4", "cut5"):
            raise VoiceError("基础TTS切句设置无效。")
        for field, low, high, integer in (("seed", 0, 2**31 - 1, True), ("top_k", 1, 100, True),
                                         ("temperature", 0.1, 2, False), ("repetition_penalty", 1, 3, False)):
            _number(base[field], low, high, integer=integer)
        self.base = base
        self.rvc = dict(_object(data["rvc"], {"root", "python", "seed"}))
        for key in ("root", "python"):
            self.rvc[key] = local_path(self.rvc[key])
        _number(self.rvc["seed"], 0, 2**31 - 1, integer=True)
        characters = _object(data["characters"], set(CHARACTERS))
        self.characters = {}
        for character, value in characters.items():
            profile = dict(_object(value, {"enabled", "model", "index", "index_rate", "speaker_id", "semitone_shift"}))
            if type(profile["enabled"]) is not bool:
                raise VoiceError("角色语音enabled必须为布尔值。")
            profile["model"] = local_path(profile["model"])
            profile["index"] = local_path(profile["index"]) if profile["index"] else None
            if profile["model"].suffix != ".pth" or profile["index"] and profile["index"].suffix != ".index":
                raise VoiceError("角色RVC需.pth模型和可选.index索引。")
            _number(profile["index_rate"], 0, 1)
            _number(profile["speaker_id"], 0, 109, integer=True)
            _number(profile["semitone_shift"], -12, 12, integer=True)
            self.characters[character] = profile

    def missing(self, character: str | None = None) -> list[str]:
        paths = {"ffmpeg": self.ffmpeg, "base_tts.python": self.base["python"],
                 "base_tts.gpt_weights": self.base["gpt_weights"], "base_tts.sovits_weights": self.base["sovits_weights"],
                 "base_tts.reference_audio": self.base["reference_audio"], "rvc.python": self.rvc["python"],
                 "base_tts.public_entry": self.base["root"] / "GPT_SoVITS/TTS_infer_pack/TTS.py",
                 "rvc.public_entry": self.rvc["root"] / "infer/vc/modules.py",
                 "rvc.rmvpe": self.rvc["root"] / "assets/rmvpe/rmvpe.pt"}
        missing = [field for field, path in paths.items() if not path.is_file()]
        transformers = {"base_tts." + field: self.base[field] for field in ("bert_base_path", "cnhuhbert_base_path")}
        transformers["rvc.hubert"] = self.rvc["root"] / "assets/hubert_base"
        for field, folder in transformers.items():
            if (not folder.is_dir() or not (folder / "config.json").is_file()
                    or not any((folder / name).is_file() for name in ("model.safetensors", "pytorch_model.bin",
                                                                 "model.safetensors.index.json", "pytorch_model.bin.index.json"))):
                missing.append(field)
        if not self.base["prompt_text"].strip():
            missing.append("base_tts.prompt_text (必须匹配参考录音)")
        if self.base["prompt_language"] == "ja" or "ja" in self.base["supported_languages"]:
            from japanese_frontend import package_problem

            frontend = self.base["japanese_frontend_package"]
            if frontend is None:
                frontend = self.base["python"].parents[1] / "Lib/site-packages/pyopenjtalk"
            problem = package_problem(frontend)
            if problem:
                missing.append("base_tts.japanese_frontend_package: " + problem)
        if character:
            profile = self.characters[character]
            if not profile["enabled"]:
                missing.append("characters." + character + ".enabled")
            for field in ("model", "index"):
                path = profile[field]
                if path is not None and not path.is_file():
                    missing.append(f"characters.{character}.{field}")
        return missing
