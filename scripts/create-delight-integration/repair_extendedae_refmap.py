"""Repair the single missing ExtendedAE Plus 1.5.5 Forge refmap entry.

This is a local compatibility patcher, not an installer. It accepts only the
known r71 1.5.5 source JAR and the author's verified Forge 1.6.4 reference JAR.
The target method selector key is taken from 1.5.5's actual @Inject annotation
(`broadcastChanges`, without a descriptor); the target SRG method mapping is
verified against 1.6.4's refmap (`broadcastChanges()V`).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import tempfile
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path


SOURCE_SHA256 = "1d008e2fcd36c381e765ab2a09f8008dfec370604b2f72a15ace93c41b70ee02"
UPSTREAM_FIX_SHA256 = "d917d5c7ed095ce974a1b8135f1ebfd0dff65bb1e67b2504567afad03ec8d2ae"
REFMAP_NAME = "extendedae_plus-refmap.json"
MIXIN_CONFIG_NAME = "extendedae_plus.mixins.json"
MIXIN_CLASS_NAME = "com/extendedae_plus/mixin/ae2/menu/CraftingCPUMenuManualStatusMixin"
MIXIN_CONFIG_ENTRY = "ae2.menu.CraftingCPUMenuManualStatusMixin"
MIXIN_CLASS_PATH = MIXIN_CLASS_NAME + ".class"
SELECTOR_KEY_155 = "broadcastChanges"
UPSTREAM_SELECTOR_KEY_164 = "broadcastChanges()V"
TARGET_SRG = "Lappeng/menu/me/crafting/CraftingCPUMenu;m_38946_()V"


@dataclass(frozen=True)
class RepairResult:
    output_jar: Path
    source_sha256: str
    upstream_fix_sha256: str
    output_sha256: str
    changed_entry: str
    selector_key: str
    target_srg: str
    signer_entries: tuple[str, ...]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json(raw: bytes, label: str) -> dict:
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{label} root must be an object")
    return value


def _matching_brace(raw: bytes, open_at: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    for index in range(open_at, len(raw)):
        byte = raw[index]
        if in_string:
            if escaped:
                escaped = False
            elif byte == 0x5C:
                escaped = True
            elif byte == 0x22:
                in_string = False
            continue
        if byte == 0x22:
            in_string = True
        elif byte == 0x7B:
            depth += 1
        elif byte == 0x7D:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("Unbalanced JSON object")


def _property_open(raw: bytes, key: str, start: int, end: int) -> tuple[int, int]:
    key_bytes = re.escape(json.dumps(key).encode("utf-8"))
    pattern = re.compile(rb"(?m)^([ ]*)" + key_bytes + rb"[ ]*:[ ]*\{")
    matches = [m for m in pattern.finditer(raw, start, end)]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one object property named {key!r}")
    match = matches[0]
    return match.end() - 1, len(match.group(1))


def _append_object_property(raw: bytes, obj_open: int, obj_close: int, indent: int, key: str, value: str) -> bytes:
    body = raw[obj_open + 1 : obj_close]
    if not body.strip():
        raise ValueError("Refmap object unexpectedly empty")
    trim = len(body)
    while trim and body[trim - 1] in b" \t\r\n":
        trim -= 1
    if trim == 0 or body[trim - 1] != 0x7D:
        raise ValueError("Expected last refmap property to be an object")
    prefix = raw[: obj_open + 1 + trim]
    suffix = raw[obj_open + 1 + trim :]
    line = (
        b",\n"
        + b" " * indent
        + json.dumps(key, ensure_ascii=False).encode("utf-8")
        + b": {\n"
        + b" " * (indent + 2)
        + json.dumps(SELECTOR_KEY_155).encode("utf-8")
        + b": "
        + json.dumps(value).encode("utf-8")
        + b"\n"
        + b" " * indent
        + b"}"
    )
    return prefix + line + suffix


def _patched_refmap(raw: bytes, upstream_raw: bytes) -> bytes:
    current = _json(raw, REFMAP_NAME)
    upstream = _json(upstream_raw, "author 1.6.4 refmap")
    root_mappings = current.get("mappings")
    if not isinstance(root_mappings, dict) or MIXIN_CLASS_NAME in root_mappings:
        raise ValueError("Unexpected 1.5.5 mappings layout or existing target entry")
    named_data = current.get("data", {}).get("named:intermediary")
    if not isinstance(named_data, dict) or MIXIN_CLASS_NAME in named_data:
        raise ValueError("Unexpected 1.5.5 named:intermediary layout or existing target entry")

    expected = (
        upstream.get("mappings", {})
        .get(MIXIN_CLASS_NAME, {})
        .get(UPSTREAM_SELECTOR_KEY_164)
    )
    expected_data = (
        upstream.get("data", {})
        .get("named:intermediary", {})
        .get(MIXIN_CLASS_NAME, {})
        .get(UPSTREAM_SELECTOR_KEY_164)
    )
    if expected != TARGET_SRG or expected_data != TARGET_SRG:
        raise ValueError("Author 1.6.4 refmap does not contain the expected Forge SRG mapping")

    mappings_open, mappings_indent = _property_open(raw, "mappings", 0, len(raw))
    mappings_close = _matching_brace(raw, mappings_open)
    data_open, _ = _property_open(raw, "data", 0, len(raw))
    data_close = _matching_brace(raw, data_open)
    named_open, named_indent = _property_open(raw, "named:intermediary", data_open, data_close)
    named_close = _matching_brace(raw, named_open)

    # Insert in reverse source order so the first insertion does not shift the
    # byte offsets of the later section. Only the two owning refmap objects
    # gain a single property; all pre-existing JSON bytes remain unchanged.
    patched = _append_object_property(
        raw,
        named_open,
        named_close,
        named_indent + 2,
        MIXIN_CLASS_NAME,
        TARGET_SRG,
    )
    mappings_open2, mappings_indent2 = _property_open(patched, "mappings", 0, len(patched))
    mappings_close2 = _matching_brace(patched, mappings_open2)
    patched = _append_object_property(
        patched,
        mappings_open2,
        mappings_close2,
        mappings_indent2 + 2,
        MIXIN_CLASS_NAME,
        TARGET_SRG,
    )

    final = _json(patched, "repaired " + REFMAP_NAME)
    if final["mappings"][MIXIN_CLASS_NAME] != {SELECTOR_KEY_155: TARGET_SRG}:
        raise ValueError("Generated root mapping did not validate")
    if final["data"]["named:intermediary"][MIXIN_CLASS_NAME] != {SELECTOR_KEY_155: TARGET_SRG}:
        raise ValueError("Generated named:intermediary mapping did not validate")
    return patched


def _rewrite_zip_single_entry(source_bytes: bytes, entry_name: str, replacement: bytes) -> bytes:
    """Replace one ZIP payload while copying all other local records verbatim."""
    eocd_sig = b"PK\x05\x06"
    eocd_at = source_bytes.rfind(eocd_sig)
    if eocd_at < 0 or len(source_bytes) < eocd_at + 22:
        raise ValueError("Input is not a standard ZIP/JAR archive")
    disk, cd_disk, entries_disk, entries_total, cd_size, cd_offset, comment_len = struct.unpack_from(
        "<4H2LH", source_bytes, eocd_at + 4
    )
    if disk or cd_disk or entries_disk != entries_total or entries_total == 0xFFFF or cd_offset == 0xFFFFFFFF:
        raise ValueError("Multi-disk or ZIP64 input is not supported by this patcher")
    eocd_end = eocd_at + 22 + comment_len
    if eocd_end != len(source_bytes):
        raise ValueError("Trailing data after the ZIP end record is not supported")

    entries: list[dict] = []
    cursor = cd_offset
    for _ in range(entries_total):
        if source_bytes[cursor : cursor + 4] != b"PK\x01\x02":
            raise ValueError("Malformed central directory")
        name_len, extra_len, note_len = struct.unpack_from("<3H", source_bytes, cursor + 28)
        end = cursor + 46 + name_len + extra_len + note_len
        raw = source_bytes[cursor:end]
        try:
            name = raw[46 : 46 + name_len].decode("utf-8")
        except UnicodeDecodeError:
            name = raw[46 : 46 + name_len].decode("cp437")
        entries.append({"name": name, "raw": raw, "old_offset": struct.unpack_from("<L", raw, 42)[0]})
        cursor = end
    if cursor != cd_offset + cd_size:
        raise ValueError("Unexpected bytes in the central directory")
    if sum(e["name"] == entry_name for e in entries) != 1:
        raise ValueError(f"Expected exactly one archive entry named {entry_name}")

    local_records: list[dict] = []
    for entry in sorted(entries, key=lambda e: e["old_offset"]):
        offset = entry["old_offset"]
        if source_bytes[offset : offset + 4] != b"PK\x03\x04":
            raise ValueError(f"Malformed local header for {entry['name']}")
        flags, method = struct.unpack_from("<2H", source_bytes, offset + 6)
        crc, compressed_size, uncompressed_size = struct.unpack_from("<3L", entry["raw"], 16)
        local_name_len, local_extra_len = struct.unpack_from("<2H", source_bytes, offset + 26)
        data_start = offset + 30 + local_name_len + local_extra_len
        data_end = data_start + compressed_size
        record_end = data_end
        if flags & 0x0008:
            descriptor_sig = struct.unpack_from("<L", source_bytes, data_end)[0]
            record_end += 16 if descriptor_sig == 0x08074B50 else 12
        if record_end > cd_offset:
            raise ValueError(f"Entry {entry['name']} overlaps the central directory")
        entry.update({
            "flags": flags,
            "method": method,
            "crc": crc,
            "compressed_size": compressed_size,
            "uncompressed_size": uncompressed_size,
            "local_name_len": local_name_len,
            "local_extra_len": local_extra_len,
            "record_end": record_end,
            "old_local_bytes": source_bytes[offset:record_end],
        })
        if entry["name"] == entry_name:
            if method == 8:
                compressor = zlib.compressobj(level=9, wbits=-15)
                compressed = compressor.compress(replacement) + compressor.flush()
            elif method == 0:
                compressed = replacement
            else:
                raise ValueError(f"Unsupported compression method {method} for target entry")
            entry["replacement"] = replacement
            entry["replacement_compressed"] = compressed
            entry["replacement_crc"] = zlib.crc32(replacement) & 0xFFFFFFFF
            entry["replacement_flags"] = flags & ~0x0008
        local_records.append(entry)

    expected_start = 0
    for entry in local_records:
        if entry["old_offset"] != expected_start:
            raise ValueError("Unindexed bytes between local ZIP entries are not supported")
        expected_start = entry["record_end"]
    if expected_start != cd_offset:
        raise ValueError("Unexpected unindexed bytes before the central directory")

    output_locals = bytearray()
    for entry in local_records:
        entry["new_offset"] = len(output_locals)
        if "replacement" not in entry:
            output_locals.extend(entry["old_local_bytes"])
            continue
        old = entry["old_local_bytes"]
        header = bytearray(old[:30])
        struct.pack_into("<H", header, 6, entry["replacement_flags"])
        struct.pack_into("<L", header, 14, entry["replacement_crc"])
        struct.pack_into("<L", header, 18, len(entry["replacement_compressed"]))
        struct.pack_into("<L", header, 22, len(entry["replacement"]))
        name_and_extra_end = 30 + entry["local_name_len"] + entry["local_extra_len"]
        output_locals.extend(header)
        output_locals.extend(old[30:name_and_extra_end])
        output_locals.extend(entry["replacement_compressed"])

    central = bytearray()
    for entry in entries:
        raw = bytearray(entry["raw"])
        struct.pack_into("<L", raw, 42, entry["new_offset"])
        if "replacement" in entry:
            struct.pack_into("<H", raw, 8, entry["replacement_flags"])
            struct.pack_into("<L", raw, 16, entry["replacement_crc"])
            struct.pack_into("<L", raw, 20, len(entry["replacement_compressed"]))
            struct.pack_into("<L", raw, 24, len(entry["replacement"]))
        central.extend(raw)
    eocd = bytearray(source_bytes[eocd_at:])
    struct.pack_into("<L", eocd, 12, len(central))
    struct.pack_into("<L", eocd, 16, len(output_locals))
    return bytes(output_locals + central + eocd)


def repair_extendedae_refmap(source_jar: Path | str, author_reference_jar: Path | str, output_jar: Path | str) -> RepairResult:
    """Create the r71 1.5.5 compatibility JAR from verified source inputs."""
    source = Path(source_jar)
    reference = Path(author_reference_jar)
    output = Path(output_jar)
    if _sha256(source) != SOURCE_SHA256:
        raise ValueError("Source JAR SHA-256 does not match the verified r71 ExtendedAE Plus 1.5.5 file")
    upstream_hash = _sha256(reference)
    if upstream_hash != UPSTREAM_FIX_SHA256:
        raise ValueError("Reference JAR SHA-256 does not match the verified author Forge 1.6.4 file")

    with zipfile.ZipFile(source, "r") as src, zipfile.ZipFile(reference, "r") as author:
        source_names = src.namelist()
        for required in (REFMAP_NAME, MIXIN_CONFIG_NAME, MIXIN_CLASS_PATH, "META-INF/mods.toml"):
            if required not in source_names:
                raise ValueError(f"Source JAR is missing required entry {required}")
        mods_toml = src.read("META-INF/mods.toml").decode("utf-8")
        if 'modId = "extendedae_plus"' not in mods_toml or 'version = "1.5.5"' not in mods_toml:
            raise ValueError("Source JAR is not ExtendedAE Plus 1.5.5")
        mixin_config = _json(src.read(MIXIN_CONFIG_NAME), MIXIN_CONFIG_NAME)
        if mixin_config.get("refmap") != REFMAP_NAME:
            raise ValueError("Unexpected mixin refmap resource name")
        if MIXIN_CONFIG_ENTRY not in mixin_config.get("mixins", []):
            raise ValueError("Manual status mixin is not enabled by the source mixin config")
        # The class file has been hash-locked by SOURCE_SHA256. Confirm the
        # selector used by its @Inject is the no-descriptor spelling in 1.5.5.
        mixin_class = src.read(MIXIN_CLASS_PATH)
        if b"broadcastChanges" not in mixin_class or b"org/spongepowered/asm/mixin/injection/Inject" not in mixin_class:
            raise ValueError("1.5.5 manual-status mixin no longer contains the expected @Inject selector")

        author_names = author.namelist()
        if REFMAP_NAME not in author_names:
            raise ValueError("Author reference JAR is missing its refmap")
        repaired_refmap = _patched_refmap(src.read(REFMAP_NAME), author.read(REFMAP_NAME))
        signers = tuple(
            name for name in source_names
            if name.upper().startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
        )
        if signers:
            raise ValueError("Signed source JAR cannot be patched without invalidating signatures: " + ", ".join(signers))

        output.parent.mkdir(parents=True, exist_ok=True)
        fd, temp_name = tempfile.mkstemp(prefix=".extendedae-refmap-", suffix=".jar", dir=output.parent)
        os.close(fd)
        try:
            Path(temp_name).write_bytes(_rewrite_zip_single_entry(source.read_bytes(), REFMAP_NAME, repaired_refmap))
            with zipfile.ZipFile(temp_name, "r") as check:
                if check.namelist() != source_names:
                    raise ValueError("Output JAR entry order differs from source")
                changed = [n for n in source_names if src.read(n) != check.read(n)]
                if changed != [REFMAP_NAME]:
                    raise ValueError(f"Expected only {REFMAP_NAME} to change, got {changed}")
                metadata_fields = (
                    "date_time", "compress_type", "comment", "extra", "create_system",
                    "create_version", "extract_version", "flag_bits", "volume",
                    "internal_attr", "external_attr",
                )
                original_info = {info.filename: info for info in src.infolist()}
                output_info = {info.filename: info for info in check.infolist()}
                for name in source_names:
                    for field in metadata_fields:
                        original_value = getattr(original_info[name], field)
                        output_value = getattr(output_info[name], field)
                        if name == REFMAP_NAME and field == "flag_bits":
                            original_value &= ~0x0008
                        if original_value != output_value:
                            raise ValueError(f"ZIP metadata changed for {name}: {field}")
            os.replace(temp_name, output)
        finally:
            if os.path.exists(temp_name):
                os.remove(temp_name)

    return RepairResult(
        output_jar=output,
        source_sha256=SOURCE_SHA256,
        upstream_fix_sha256=upstream_hash,
        output_sha256=_sha256(output),
        changed_entry=REFMAP_NAME,
        selector_key=SELECTOR_KEY_155,
        target_srg=TARGET_SRG,
        signer_entries=(),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_jar", type=Path, help="Verified ExtendedAE Plus 1.5.5 Forge source JAR")
    parser.add_argument("author_reference_jar", type=Path, help="Author's verified ExtendedAE Plus 1.6.4 Forge JAR")
    parser.add_argument(
        "--output",
        type=Path,
        default=(Path(__file__).resolve().parents[2] / "build-isolated/player-packaging/20261113-delight-joint"
                 / "r71-three-fixes/ae/extendedae_plus-1.5.5.jar"),
        help="Defaults to the r71 build staging directory",
    )
    args = parser.parse_args()
    result = repair_extendedae_refmap(args.source_jar, args.author_reference_jar, args.output)
    print(json.dumps({
        "output": str(result.output_jar),
        "source_sha256": result.source_sha256,
        "author_reference_sha256": result.upstream_fix_sha256,
        "output_sha256": result.output_sha256,
        "changed_entry": result.changed_entry,
        "selector_key": result.selector_key,
        "target_srg": result.target_srg,
        "signer_entries": list(result.signer_entries),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
