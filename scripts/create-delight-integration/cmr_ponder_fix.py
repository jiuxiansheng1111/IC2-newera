"""Build this project's small Forge client patch; inspect upstream bytecode without loading it."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory
import tomllib
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


MOD_ID = "newera_ponder_fix"
VERSION = "1.0.0"
NAME = f"newera-ponder-fix-1.20.1-{VERSION}.jar"
SRC = Path(__file__).with_name("ponder-fix-src")
ROOT = SRC.parents[2]
TARGET = "fr.iglee42.cmr.ponder.CustomPonderScenes"
TARGET_CLASS_SHA = "4dd132516cc08de37ca21dfd3d564803913a3a931754ef15fe3e8af88f4bddaa"
SCENE_DESCRIPTOR = "(Lnet/createmod/ponder/api/scene/SceneBuilder;Lnet/createmod/ponder/api/scene/SceneBuildingUtil;)V"


def _sha(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def _jdk(tool):
    home = os.environ.get("JAVA_HOME")
    p = Path(home) / "bin" / (tool + ".exe" if os.name == "nt" else tool) if home else None
    found = str(p) if p and p.is_file() else shutil.which(tool)
    if not found:
        raise RuntimeError(f"Java 17 JDK tool not found: {tool}")
    return found


def _classpath():
    base = Path(os.environ.get("NEWERA_FORGE_LIBRARIES") or ROOT.parent / ".minecraft/libraries")
    coordinates = [
        ("net/minecraftforge", a, "1.20.1-47.4.28", ext) for a, ext in
        (("fmlcore", ""), ("fmlloader", ""), ("javafmllanguage", ""), ("forge", "-universal"))
    ] + [
        ("net/minecraftforge", "forgespi", "7.0.1", ""),
        ("net/minecraftforge", "eventbus", "6.2.33", ""),
        ("net/minecraftforge", "mergetool", "1.1.5", "-api"),
        ("org/spongepowered", "mixin", "0.8.5", ""),
        ("org/ow2/asm", "asm", "9.9.1", ""),
        ("org/ow2/asm", "asm-tree", "9.9.1", ""),
        ("cpw/mods", "modlauncher", "10.0.9", ""),
        ("cpw/mods", "securejarhandler", "2.1.10", ""),
        ("org/apache/maven", "maven-artifact", "3.8.5", ""),
        ("org/apache/commons", "commons-lang3", "3.12.0", ""),
        ("org/slf4j", "slf4j-api", "2.0.1", ""),
    ]
    jars = [base / group / artifact / ver / f"{artifact}-{ver}{ext}.jar"
            for group, artifact, ver, ext in coordinates]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(f"Forge 47.4.28 compilation library not found: {jar}")
    return os.pathsep.join(map(str, jars))


def _tree(leaf):
    return next(p for p in (SRC / leaf, SRC / "src/main" / leaf) if p.is_dir())


def build_jar(out: Path) -> Path:
    out = Path(out)
    if out.suffix.lower() != ".jar":
        out = out / NAME
    out.parent.mkdir(parents=True, exist_ok=True)
    sources = sorted(_tree("java").rglob("*.java"))
    resources = _tree("resources")
    if not sources:
        raise ValueError("No first-party Java sources")
    with TemporaryDirectory(prefix=".newera-ponder-", dir=out.parent) as temp:
        scratch = Path(temp)
        classes = scratch / "classes"
        classes.mkdir()
        subprocess.run([_jdk("javac"), "--release", "17", "-encoding", "UTF-8", "-proc:none",
                        "-g:none", "-classpath", _classpath(), "-d", str(classes), *map(str, sources)], check=True)
        archive = scratch / NAME
        payload = {p.relative_to(resources).as_posix(): p.read_bytes() for p in resources.rglob("*") if p.is_file()}
        for p in classes.rglob("*.class"):
            rel = p.relative_to(classes).as_posix()
            if rel in payload:
                raise ValueError(f"Duplicate resource {rel}")
            payload[rel] = p.read_bytes()
        if not {"META-INF/MANIFEST.MF", "META-INF/mods.toml"} <= payload.keys():
            raise ValueError("Forge resources are missing")
        # Stable output on Windows and Linux regardless of the checkout's newline settings.
        for name in payload:
            if name.lower().endswith((".mf", ".toml", ".json", ".js", ".mcmeta", ".txt")):
                content = payload[name].replace(b"\r\n", b"\n")
                if name == "META-INF/MANIFEST.MF":
                    content = (content.rstrip(b"\n") + b"\n\n").replace(b"\n", b"\r\n")
                payload[name] = content
        with ZipFile(archive, "w", ZIP_DEFLATED) as z:
            for name in sorted(payload, key=lambda n: (n != "META-INF/MANIFEST.MF", n)):
                info = ZipInfo(name, (2026, 11, 14, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                z.writestr(info, payload[name], compress_type=ZIP_DEFLATED, compresslevel=9)
        if not out.exists() or _sha(archive) != _sha(out):
            os.replace(archive, out)
    return out


def _upstream(cmr):
    with ZipFile(cmr) as z:
        meta = tomllib.loads(z.read("META-INF/mods.toml").decode("utf-8-sig"))
        if not any(m["modId"] == "cmr" and m["version"] == "1.2.1-fix1" for m in meta["mods"]):
            raise ValueError("Unexpected version of Create More Recipes")
        original = z.read(TARGET.replace(".", "/") + ".class")
        if hashlib.sha256(original).hexdigest() != TARGET_CLASS_SHA:
            raise ValueError("CMR target changed; review its Ponder scene before applying a patch")
    # Inspect the real production method, including local slot and operation order.
    dump = subprocess.run([_jdk("javap"), "-p", "-s", "-c", "-classpath", str(cmr), TARGET],
                          capture_output=True, check=True).stdout.decode("utf-8", "replace").replace("\r", "")
    section = dump.split("void blockSpout(", 1)[1].split("\n  private ", 1)[0].split("\n  public ", 1)[0]
    ops = [l.strip() for l in section.splitlines() if re.match(r"\s*\d+:\s+\w+", l)]
    calls = [i for i, op in enumerate(ops) if "PonderScene.getWorld:" in op]
    if len(calls) != 1 or SCENE_DESCRIPTOR not in section:
        raise ValueError("Ponder scene signature/call count changed")
    i = calls[0]
    expected = ("aload_0", "SceneBuilder.getScene:", "PonderScene.getWorld:", "PonderLevel.f_46441_:",
                "float 1.5f", "VecHelper.offsetRandomly:")
    if not all(sig in op for sig, op in zip(expected, ops[i-2:i+4], strict=True)):
        raise ValueError("Refusing an unfamiliar particle sequence")
    gen = [n for n, op in enumerate(ops) if "RandomSource.m_216327_" in op]
    if len(gen) != 1 or "astore_3" not in ops[gen[0]+1] or gen[0] >= i:
        raise ValueError("Expected the scene's existing random source in local slot 3")
    if sum("VecHelper.offsetRandomly:" in op for op in ops) != 2:
        raise ValueError("Unexpected set of particle offsets")
    return TARGET_CLASS_SHA


def audit_pair(fix_jar: Path, cmr_jar: Path) -> dict:
    target_sha = _upstream(cmr_jar)
    with ZipFile(fix_jar) as z:
        names = z.namelist()
        if len(names) != len(set(names)) or z.testzip() or any(n.endswith(".jar") for n in names):
            raise ValueError("Bad or nested repair archive")
        desc = tomllib.loads(z.read("META-INF/mods.toml").decode("utf-8-sig"))
        if [(m["modId"], m["version"]) for m in desc["mods"]] != [(MOD_ID, VERSION)]:
            raise ValueError("Wrong repair mod/version")
        if desc["mods"][0].get("displayTest") != "IGNORE_ALL_VERSION":
            raise ValueError("Client repair must tolerate a server without this mod")
        if any(n.startswith(("fr/iglee42/", "net/createmod/", "net/minecraft/", "org/spongepowered/"))
               for n in names):
            raise ValueError("Unexpected third-party content in repair JAR")
        for n in names:
            if n.endswith(".class") and z.read(n)[:8] != bytes.fromhex("cafebabe0000003d"):
                raise ValueError("Repair must use Java 17 bytecode")
        if desc["modLoader"] == "javafml" and not any(
            b"Lnet/minecraftforge/fml/common/Mod;" in z.read(n) and MOD_ID.encode() in z.read(n)
            for n in names if n.endswith(".class")):
            raise ValueError("A javafml compatibility JAR must contain its @Mod entrypoint")
        mixin_names = [n for n in names if n.endswith("mixins.json")]
        if len(mixin_names) != 1:
            raise ValueError("Expected one client Mixin config")
        cfg = json.loads(z.read(mixin_names[0]))
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8")
        if ("MixinConfigs: " + mixin_names[0]) not in manifest or cfg.get("mixins") or cfg.get("server") or not cfg.get("client"):
            raise ValueError("Client mixin was not exclusively registered on the client")
        for cls in [cfg["plugin"], *(cfg["package"] + "." + c for c in cfg["client"])]:
            if cls.replace(".", "/") + ".class" not in names:
                raise ValueError("Missing repair class " + cls)
        impl = z.read(cfg["plugin"].replace(".", "/") + ".class")
        for literal in (TARGET.encode(), b"1.2.1-fix1", b"2.1.0", b"blockSpout", SCENE_DESCRIPTOR.encode(),
                        b"m_216327_", b"getScene", b"getWorld", b"f_46441_", b"offsetRandomly",
                        b"insertBefore", b"remove"):
            if literal not in impl:
                raise ValueError("Repair is missing an audited conditional/particle operation: " + literal.decode())
        if any(b'net/minecraftforge/network/simple/SimpleChannel' in z.read(n) for n in names if n.endswith(".class")):
            raise ValueError("This cosmetic fix must not register a required network channel")
    return {"modId": MOD_ID, "version": VERSION, "sha256": _sha(fix_jar),
            "upstreamTargetClassSha256": target_sha, "clientOnly": True}
