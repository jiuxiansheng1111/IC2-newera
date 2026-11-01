"""Add the verified local fixed-voice agent to the matching r60 server release."""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "dist/IC2-New-Era-Server-1.20.1-r60-Complete.zip"
BASE_SHA = "C45BCC089515F7CF4D19E2422D9E0E01DBC9025F9453511D57D6ACB6A935516F"
VOICE = ROOT / "dist/ic2newera-fixed-voice.jar"
VOICE_SHA = "D1AC67C04E64A5DE16971CDB22EC7CEB928156C5387C701B6D0FD2EF9EB6CB92"
OWN_NAME = "ic2newera-0.2.0-migration1201-all.jar"
OWN_SHA = "6D56CF2F2E25790B647FDBBD431ACDE7151CCB9F6AFAE87281A9A22BAA31828D"
STEM = "IC2-New-Era-Server-1.20.1-r60-Voice"
WORK = ROOT / "build-isolated/server-packaging/20261030-r60-voice"
FLAGS = ("--add-modules=jdk.httpserver", "-javaagent:ic2newera-fixed-voice.jar")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def write_archive(path: Path, files: dict[str, bytes]) -> dict:
    partial = path.with_name(path.name + ".partial")
    if path.exists() or partial.exists():
        raise FileExistsError("Refusing to overwrite a release: " + str(path))
    with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1) as archive:
        for name, data in sorted(files.items()):
            rel = PurePosixPath(name)
            if rel.is_absolute() or ".." in rel.parts or "\\" in name:
                raise ValueError("Unsafe archive path: " + name)
            entry = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
            entry.compress_type = ZIP_DEFLATED
            archive.writestr(entry, data, compresslevel=1)
    with ZipFile(partial) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(files):
            raise ValueError("Archive integrity or file list differs")
        for name, data in files.items():
            if sha(archive.read(name)) != sha(data):
                raise ValueError("Archive readback differs: " + name)
    partial.replace(path)
    digest = sha(path.read_bytes())
    path.with_name(path.name + ".sha256").write_text(digest + "  " + path.name + "\n", encoding="ascii")
    return {"file": path.name, "sha256": digest, "sizeBytes": path.stat().st_size}


def main() -> None:
    if sha(BASE.read_bytes()) != BASE_SHA or sha(VOICE.read_bytes()) != VOICE_SHA:
        raise ValueError("Server baseline or voice JAR changed")
    voice = VOICE.read_bytes()
    with ZipFile(io.BytesIO(voice)) as agent:
        manifest = agent.read("META-INF/MANIFEST.MF").decode("utf-8")
        if "Premain-Class: com.ic2newera.fixedvoice.FixedVoiceAgent" not in manifest:
            raise ValueError("Unexpected voice agent entry point")
        if sum(n.endswith(".wav") for n in agent.namelist()) != 128:
            raise ValueError("Expected exactly 128 prerecorded voice clips")
    with ZipFile(BASE) as source:
        prefixes = {n.split("/", 1)[0] for n in source.namelist()}
        if len(prefixes) != 1:
            raise ValueError("Unexpected base archive layout")
        prefix = prefixes.pop() + "/"
        files = {n[len(prefix):]: source.read(n) for n in source.namelist() if not n.endswith("/")}
    if sha(files["mods/" + OWN_NAME]) != OWN_SHA:
        raise ValueError("Unexpected New Era JAR")
    previous_manifest = json.loads(files.pop("server-manifest.json"))
    params = files["user_jvm_args.txt"].decode("utf-8-sig").rstrip() + "\n"
    if any(flag in params for flag in FLAGS):
        raise ValueError("Baseline already includes voice flags")
    files["user_jvm_args.txt"] = (params + "\n".join(FLAGS) + "\n").encode("utf-8")
    files["ic2newera-fixed-voice.jar"] = voice
    notes = ("新纪元 r60 完整服务器：固定问候语音已内置\n\n"
             "根目录已包含 ic2newera-fixed-voice.jar，user_jvm_args.txt 已配置启动参数。\n"
             "使用本包 Start.sh / Start.bat 或 server.jar 启动，无需再次添加语音参数。\n"
             "服务器需要 Java 17；首次开服仍须服主自行阅读并接受 EULA。\n"
             "客户端继续使用 r60 Windows 整合包，客户端无需安装语音附件。\n"
             "内容是八位角色、中日文、四个关系阶段的送行/欢迎语音，共128片段。\n"
             "动态回答、英文语音和翻唱不在本附件内；实际游戏听音待玩家验收。\n"
             "更新已有服务器时不要覆盖 world、config、server.properties 或原内存配置。\n")
    files["FIXED-VOICE-README.zh-CN.txt"] = notes.encode("utf-8")
    files["README.zh-CN.md"] = ("# r60 完整服务器：已整合固定问候语音\n\n"
                                      + notes.replace("\n", "  \n") + "\n"
                                      + files["README.zh-CN.md"].decode("utf-8-sig")).encode("utf-8")
    previous_manifest["package"] = STEM
    previous_manifest["fixedVoice"] = {"file": "ic2newera-fixed-voice.jar", "sha256": VOICE_SHA,
                                        "sizeBytes": len(voice), "clipCount": 128,
                                        "startupArgsFile": "user_jvm_args.txt", "runtimeVerified": False}
    previous_manifest["files"] = [{"path": name, "sizeBytes": len(data), "sha256": sha(data)}
                                  for name, data in sorted(files.items())]
    files["server-manifest.json"] = (json.dumps(previous_manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    complete = write_archive(ROOT / "dist" / (STEM + ".zip"),
                             {STEM + "/" + name: data for name, data in files.items()})
    update_notes = ("仅供已配置固定语音启动参数的现有服务器更新：先正常停服并备份。\n"
                    "解压到服务器根目录，只替换两个JAR；不覆盖存档、配置或内存设置。\n"
                    "新部署请使用 r60-Voice 完整服务器包。\n")
    update = write_archive(ROOT / "dist/IC2-New-Era-Server-1.20.1-r60-Update.zip",
                           {"mods/" + OWN_NAME: files["mods/" + OWN_NAME],
                            "ic2newera-fixed-voice.jar": voice,
                            "UPDATE-README.zh-CN.txt": update_notes.encode("utf-8")})
    WORK.mkdir(parents=True, exist_ok=True)
    audit = {"baseSha256": BASE_SHA, "newEraSha256": OWN_SHA, "voiceSha256": VOICE_SHA,
             "voiceClips": 128, "archives": [complete, update], "readbackVerified": True,
             "gameStarted": False, "testsRun": False}
    (WORK / "package-audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=True))


if __name__ == "__main__":
    main()
