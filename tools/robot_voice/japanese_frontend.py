"""Load one explicitly selected, already-installed Japanese frontend package.

This adapter never adds another environment's site-packages to sys.path, copies
third-party source, installs dependencies, downloads dictionaries or imports Torch.
"""
from __future__ import annotations

import importlib
import importlib.util
import os
import struct
import sys
from pathlib import Path

DICTIONARY_FILES = ("sys.dic", "unk.dic", "matrix.bin", "char.bin")


def package_problem(package: Path) -> str | None:
    if package.name != "pyopenjtalk" or not (package / "__init__.py").is_file():
        return "需要已有pyopenjtalk包目录（含__init__.py），不能指定整个site-packages。"
    if os.name == "nt":
        if struct.calcsize("P") != 8:
            return "当前已有日语前端扩展仅适用于64位Windows Python。"
        tag = f"cp{sys.version_info.major}{sys.version_info.minor}-win_amd64"
        if any(not (package / f"{name}.{tag}.pyd").is_file() for name in ("openjtalk", "htsengine")):
            return "已有日语前端C扩展ABI不匹配当前Python版本/架构。"
    dictionary = package / "open_jtalk_dic_utf_8-1.11"
    if any(not (dictionary / name).is_file() or not (dictionary / name).stat().st_size for name in DICTIONARY_FILES):
        return "已有日语前端缺少完整本地OpenJTalk字典；不会自动下载。"
    return None


def load_japanese_frontend(package_path: str | Path | None = None):
    """Use an explicit single package, or a normally installed primary-env package."""
    if package_path:
        package = Path(package_path).resolve(strict=True)
        problem = package_problem(package)
        if problem:
            raise RuntimeError(problem)
        existing = sys.modules.get("pyopenjtalk")
        if existing is not None:
            if Path(existing.__file__).resolve().parent != package:
                raise RuntimeError("已加载日语前端与显式配置路径不同，拒绝混用环境。")
            module = existing
        else:
            spec = importlib.util.spec_from_file_location("pyopenjtalk", package / "__init__.py",
                                                        submodule_search_locations=[str(package)])
            if spec is None or spec.loader is None:
                raise RuntimeError("无法定位已有日语前端包加载器。")
            module = importlib.util.module_from_spec(spec)
            sys.modules["pyopenjtalk"] = module
            previous_bytecode = sys.dont_write_bytecode
            sys.dont_write_bytecode = True
            try:
                spec.loader.exec_module(module)
            except BaseException:
                sys.modules.pop("pyopenjtalk", None)
                raise
            finally:
                sys.dont_write_bytecode = previous_bytecode
        # Public package option: use only the verified local dictionary. This
        # older Windows C extension needs an ASCII path; a relative path avoids
        # copying its dictionary or changing either shared Python environment.
        dictionary = package / "open_jtalk_dic_utf_8-1.11"
        dictionary_path = str(dictionary)
        if os.name == "nt":
            try:
                relative = os.path.relpath(dictionary)
                if relative.isascii():
                    dictionary_path = relative
            except ValueError:
                pass
            if not dictionary_path.isascii():
                raise RuntimeError("已有OpenJTalk字典需要ASCII可表示的路径；请使用可从模型工作目录相对访问的独立资产目录。")
        module.OPEN_JTALK_DICT_DIR = dictionary_path.encode("utf-8")
    else:
        module = importlib.import_module("pyopenjtalk")
        dictionary = Path(os.fsdecode(module.OPEN_JTALK_DICT_DIR))
        if any(not (dictionary / name).is_file() for name in DICTIONARY_FILES):
            raise RuntimeError("主环境日语前端缺少本地字典；不会自动下载。")
    if any(not callable(getattr(module, name, None)) for name in ("g2p", "run_frontend", "make_label")):
        raise RuntimeError("已有日语前端缺少GPT-SoVITS所需的公开接口。")
    return module
