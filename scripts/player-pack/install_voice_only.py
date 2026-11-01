"""Install the optional slim voice payload into an existing Forge playtest profile."""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import install_player as common


def main() -> int:
    parser = argparse.ArgumentParser(description="IC2：新纪元 可选八角色三语语音安装工具")
    parser.add_argument("--game-directory")
    parser.add_argument("--voice-archive")
    args = parser.parse_args()
    package = common.safe_absolute(Path(__file__).absolute().parent.parent, must_exist=True)
    manifest = common.load_json(package / "install-manifest.json", "语音安装清单")
    if (not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1
            or manifest.get("minecraft") != "1.20.1" or manifest.get("forge") != "47.4.28"):
        raise common.InstallError("语音安装清单的版本无效。")
    if not isinstance(manifest.get("packVersion"), str) or not manifest["packVersion"].strip():
        raise common.InstallError("语音安装清单缺少包版本。")
    voice = manifest.get("voice")
    if not isinstance(voice, dict) or voice.get("rootPrefix") != "tools/robot_voice/":
        raise common.InstallError("语音清单前缀无效。")
    if voice.get("manifestEntry") != "voice-files.json":
        raise common.InstallError("语音文件清单名称无效。")
    common.relative_posix(voice.get("archive"), "语音ZIP名称")
    if not re.fullmatch(r"[A-Fa-f0-9]{64}", str(voice.get("sha256", ""))):
        raise common.InstallError("语音ZIP校验码无效。")
    if not isinstance(voice.get("sizeBytes"), int) or not 0 < voice["sizeBytes"] < 10_000_000_000:
        raise common.InstallError("语音ZIP大小无效或超过10GB。")
    if not isinstance(voice.get("uncompressedBytes"), int) or not 0 < voice["uncompressedBytes"] < common.MAX_VOICE_TOTAL_BYTES:
        raise common.InstallError("语音解压大小无效。")
    game_dir = common.choose_game_directory(args.game_directory)
    profile = common.verify_forge_instance(game_dir)
    if common.running_java_processes():
        raise common.InstallError("请先正常关闭Minecraft/Java程序，再安装语音；本工具不会关闭程序。")
    install_dir = common.contained_path(game_dir, common.INSTALL_DIR_NAME, "安装回执目录")
    common.mkdir_safe(game_dir, install_dir)
    state_path = common.contained_path(install_dir, "voice-install-state.json", "语音安装回执")
    state = {"schemaVersion": 1, "packVersion": manifest["packVersion"],
             "profile": profile, "status": "incomplete", "voiceInstalled": False,
             "voiceInferenceVerified": False, "voiceStatus": "pending",
             "gameModsChanged": False, "javaChanged": False}
    common.write_state(state_path, state)
    try:
        archive = common.choose_voice_archive(args.voice_archive, package, voice)
        prepared = common.voice_expected(manifest, archive)
        _inventory, declared, expected_sizes = prepared
        verified = common.preflight_voice_target(game_dir, manifest, declared, expected_sizes)
        state["voiceStatus"] = "installing"
        common.write_state(state_path, state)
        common.install_voice(game_dir, archive, manifest, state, state_path, prepared, verified)
        config_path = common.contained_path(game_dir, "config/ic2newera-robot-voice.json", "语音开关")
        common.mkdir_safe(game_dir, config_path.parent)
        options = common.load_json(config_path, "语音开关") if config_path.exists() else {}
        if not isinstance(options, dict):
            raise common.InstallError("语音开关配置必须是JSON对象。")
        options["enabled"] = True
        common.json_write_atomic(config_path, options)
        state.update(status="complete", voiceStatus="files_installed_inference_not_verified",
                     voiceInferenceVerified=False, finishedAtLocal=time.strftime("%Y-%m-%d %H:%M:%S"))
        common.write_state(state_path, state)
    except BaseException as exc:
        state["status"] = "incomplete"
        state["error"] = "用户中断" if isinstance(exc, KeyboardInterrupt) else str(exc)
        common.write_state(state_path, state)
        raise
    print("八角色中/日/英语音文件已安装并开启。现在可回到PCL启动此实例。")
    print("这里只确认文件安装；实际音色合成与扬声器效果需进入游戏验证。")
    print("回执：" + str(state_path))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (common.InstallError, OSError, ValueError, KeyboardInterrupt) as error:
        print("语音安装未完成：" + ("用户中断" if isinstance(error, KeyboardInterrupt) else str(error)),
              file=sys.stderr)
        raise SystemExit(130 if isinstance(error, KeyboardInterrupt) else 1)
