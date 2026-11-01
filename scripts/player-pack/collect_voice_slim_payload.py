"""One CPython 3.11 / Torch CUDA runtime shared by TTS, RVC and Demucs.

This collects distribution files only. It never imports an inference library,
changes the source voice environments, reads credentials or starts a model.
"""
from __future__ import annotations

import importlib.util
import json
import re
from collections import Counter
from pathlib import Path, PurePosixPath


COMMON = "tools/robot_voice/portable/python-tts/runtime/"
SITE = COMMON + "Lib/site-packages/"
OLD_RVC = "tools/robot_voice/portable/python-rvc/"
OLD_SINGING = "tools/robot_voice/portable/python-singing/"
SINGING_SITE = OLD_SINGING + "Lib/site-packages/"
# These are the upstream workbench's UI/ASR/download helpers, never called by
# the Minecraft TTS/RMVPE/Demucs workers. Whole packages and metadata are omitted.
UNUSED_DISTRIBUTIONS = {
    "gradio", "gradio-client", "modelscope", "funasr", "faster-whisper",
    "ctranslate2", "pip", "wheel", "build", "twine",
}
UNUSED_TOP_LEVEL = {
    "gradio", "gradio_client", "modelscope", "funasr", "faster_whisper",
    "ctranslate2", "ctranslate2.libs", "pip", "wheel", "build", "twine",
}


def _distribution_name(top: str) -> str | None:
    if top.endswith((".dist-info", ".egg-info")):
        stem = top.rsplit(".", 1)[0]
        stem = re.sub(r"-[0-9].*$", "", stem)
        return re.sub(r"[-_.]+", "-", stem).casefold()
    return None


def _remove_reason(target: str) -> str | None:
    if target.startswith(OLD_RVC):
        return "second_python_torch_cuda_environment"
    if target.startswith(OLD_SINGING):
        return "third_python_environment"
    if target.startswith(SITE):
        relative = target[len(SITE):]
        top = relative.split("/", 1)[0]
        if top in UNUSED_TOP_LEVEL or _distribution_name(top) in UNUSED_DISTRIBUTIONS:
            return "unused_workbench_distribution:" + top
        if any(part.casefold() in {"tests", "test", "benchmarks"} for part in PurePosixPath(relative).parts):
            return "unused_dependency_test_data"
    if target.startswith(COMMON + "Lib/"):
        relative = target[len(COMMON + "Lib/"):]
        if relative.split("/", 1)[0] in {"test", "ensurepip", "idlelib", "turtledemo"}:
            return "unused_stdlib_development_tools"
    return None


def collect_voice_payload(project_root: Path) -> dict:
    root = Path(project_root).resolve(strict=True)
    original_file = root / "scripts/player-pack/collect_voice_payload.py"
    spec = importlib.util.spec_from_file_location("ic2newera_full_voice_inventory", original_file)
    if spec is None or spec.loader is None:
        raise ValueError("Full voice inventory collector is missing")
    original = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(original)
    inventory = original.collect_voice_payload(root)
    if inventory["metadata"].get("unresolved_or_blocking_items"):
        raise ValueError("Original asset inventory is incomplete")
    files: dict[str, Path] = {}
    generated: dict[str, bytes] = {}
    removed = Counter()
    removed_bytes = Counter()
    for target, source in inventory["files"].items():
        reason = _remove_reason(target)
        if reason:
            removed[reason] += 1
            removed_bytes[reason] += source.stat().st_size
        else:
            files[target] = source
    for target, data in inventory["generated"].items():
        reason = _remove_reason(target)
        if reason:
            removed[reason] += 1
            removed_bytes[reason] += len(data)
        else:
            generated[target] = data

    common_tops = {path[len(SITE):].split("/", 1)[0] for path in (*files, *generated)
                   if path.startswith(SITE)}
    common_distributions = {name for top in common_tops if (name := _distribution_name(top))}
    additions = Counter()
    # Demucs already used Torch from the TTS environment through a .pth file.
    # Carry its unique packages into that same runtime; never overwrite the
    # established numpy/scipy/transformers/torch versions with a second copy.
    for target, source in inventory["files"].items():
        if not target.startswith(SINGING_SITE):
            continue
        relative = target[len(SINGING_SITE):]
        top = relative.split("/", 1)[0]
        name = _distribution_name(top)
        if (top in common_tops or (name and name in common_distributions)
                or top in UNUSED_TOP_LEVEL or name in UNUSED_DISTRIBUTIONS):
            continue
        if source.suffix.casefold() == ".pth" and "/" not in relative:
            continue
        if any(part.casefold() in {"tests", "test", "benchmarks"} for part in PurePosixPath(relative).parts):
            continue
        destination = SITE + relative
        if destination in files or destination in generated:
            raise ValueError("Demucs package would overwrite the shared runtime")
        files[destination] = source
        additions["demucs_unique_package_files"] += 1

    # The RVC implementation eagerly imports these helpers; they are source,
    # not the CPython 3.12 binaries from its old environment.
    config_path = root / "tools/robot_voice/ic2newera-voice-runtime.json"
    config = json.loads(config_path.read_text(encoding="utf-8-sig"))
    rvc_root = Path(config["rvc"]["root"])
    if not rvc_root.is_absolute():
        rvc_root = config_path.parent / rvc_root
    tools = rvc_root.resolve(strict=True) / "tools"
    required_helpers = {"progress.py", "cuda_graph.py", "faiss_io.py"}
    if not all((tools / name).is_file() for name in required_helpers):
        raise ValueError("A required RVC support module is missing")
    for source in tools.glob("*.py"):
        if source.is_file() and source.name != "pymss_webui.py":
            files["tools/robot_voice/portable/rvc/tools/" + source.name] = source.resolve(strict=True)
            additions["rvc_support_modules"] += 1
    rvc_init = "tools/robot_voice/portable/rvc/tools/__init__.py"
    if rvc_init not in files:
        generated[rvc_init] = b""

    # Install only the two missing binary wheels, built for the shared CPython
    # ABI. Their PyPI download hashes are locked; source cp312 binaries are never used.
    wheel_root = root / "build-isolated/player-packaging/20261024-voice-slim/wheels"
    lock = json.loads((wheel_root / "wheel-lock.json").read_text(encoding="utf-8"))
    for wheel in lock["wheels"]:
        if wheel["pythonTag"] != "cp311-cp311-win_amd64":
            raise ValueError("Voice extension wheel has the wrong Python ABI")
        staging = Path(wheel["stagingDirectory"])
        if not staging.is_absolute():
            staging = root / staging
        for source in staging.rglob("*"):
            if not source.is_file() or source.is_symlink():
                continue
            relative = source.relative_to(staging).as_posix()
            destination = SITE + relative
            if destination in files or destination in generated:
                raise ValueError("A binary wheel would overwrite an existing runtime package")
            files[destination] = source.resolve(strict=True)
            additions["cp311_extension_files"] += 1

    config_target = "tools/robot_voice/ic2newera-voice-runtime.json"
    portable = json.loads(generated[config_target].decode("utf-8"))
    shared_python = "portable/python-tts/runtime/python.exe"
    portable["base_tts"]["python"] = shared_python
    portable["rvc"]["python"] = shared_python
    if portable.get("singing", {}).get("enabled"):
        portable["singing"]["python"] = shared_python
    generated[config_target] = (json.dumps(portable, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    expected = {"tomori", "anon", "rana", "soyo", "taki", "sakiko", "uika", "umiri"}
    if set(portable["characters"]) != expected or any(
            not row.get("enabled") for row in portable["characters"].values()):
        raise ValueError("All eight character profiles must remain enabled")
    if set(portable["base_tts"].get("supported_languages", [])) != {"zh", "ja", "en"}:
        raise ValueError("Chinese, Japanese and English must remain supported")
    for target in (*files, *generated):
        if target.startswith((OLD_RVC, OLD_SINGING)):
            raise ValueError("A duplicate Python environment survived deduplication")
        if re.search(r"(?:python312|\.cp312[-.]|cpython-312)", target, flags=re.IGNORECASE):
            raise ValueError("A CPython 3.12 extension survived the shared cp311 inventory")

    metadata = dict(inventory["metadata"])
    metadata.update({
        "format": "ic2newera-shared-cp311-voice-payload-v2",
        "file_count": len(files) + len(generated),
        "source_file_count": len(files),
        "generated_file_count": len(generated),
        "total_bytes": sum(path.stat().st_size for path in files.values()) + sum(map(len, generated.values())),
        "python_runtime_count": 1,
        "torch_runtime_count": 1,
        "shared_runtime": {"python": "3.11.9", "torch": "2.7.0+cu128", "torchaudio": "2.7.0+cu128",
                           "workers": ["tts", "rvc", "demucs"]},
        "removed_file_counts": dict(removed),
        "removed_raw_bytes": dict(removed_bytes),
        "added_file_counts": dict(additions),
        "extension_wheels": [{key: row[key] for key in ("distribution", "version", "wheel", "url", "sha256", "pythonTag")}
                             for row in lock["wheels"]],
        "preserved_characters": sorted(expected),
        "preserved_languages": ["zh", "ja", "en"],
        "singing_enabled_in_source_config": bool(portable.get("singing", {}).get("enabled")),
        "notes": [
            "TTS, RVC and Demucs use one unchanged Torch/torchaudio cu128 pair and one CPython 3.11 base.",
            "RVC-specific compiled extensions were replaced with matching author-published cp311 wheels.",
            "Demucs unique packages were merged without overwriting shared package versions.",
            "Torch DLLs, bin helpers, headers and dynamic compiler modules remain intact.",
            "ONNX runtime is conservatively retained for eager optional imports; no individual GPU DLL is removed.",
            "Workbench UI/ASR packages and dependency test data are not used by the selected inference workers.",
            "Models, all eight voice/index profiles, Japanese dictionary and English NLTK data remain unchanged.",
            "Static dependency and ABI review is not execution of inference or cross-machine acceptance.",
        ],
        "inference_or_tts_performed": False,
    })
    metadata["groups"] = dict(Counter(path.split("/")[3] if "/portable/" in path else "service"
                                      for path in (*files, *generated)))
    metadata["torch_builds_static_version_py"] = {
        "shared": inventory["metadata"].get("torch_builds_static_version_py", {}).get("tts")}
    metadata["source_python_environments_before_deduplication"] = metadata.pop("python_environments", {})
    metadata["python_environments"] = {
        "shared": {"version": "3.11.9", "bundled_base_runtime": COMMON.removeprefix("tools/robot_voice/").rstrip("/"),
                   "bundled_site_packages": SITE.removeprefix("tools/robot_voice/").rstrip("/"),
                   "workers": ["tts", "rvc", "demucs"], "uses_host_registry_or_pythonpath": False}}
    metadata["singing_python_layout"] = "shared portable CPython root with Lib/site-packages"
    metadata["singing_shared_site_packages"] = "unique Demucs packages in the common TTS/RVC runtime; no second interpreter"
    return {"files": files, "generated": generated, "metadata": metadata}
