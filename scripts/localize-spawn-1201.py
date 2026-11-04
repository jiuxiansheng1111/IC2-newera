"""Add only our zh_cn language resource to the pinned upstream Spawn JAR.

The original binary must be obtained separately from its author. No classes,
recipes, registries, metadata or other existing ZIP members are changed.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_SHA256 = '8CD18F3C029FDDFEEC28314AB9BB4845FF9061131E9F1DCE149778E5EE731D23'
RESOURCE = 'assets/spawn/lang/zh_cn.json'
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z%]')


def sha(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def read_json(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate language key: ' + key)
            result[key] = value
        return result
    return json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique)


def localize(source: Path, output: Path, language: Path) -> dict:
    if sha(source) != ORIGINAL_SHA256:
        raise ValueError('Input is not the pinned official Spawn 4.0.8 Forge 1.20.1 JAR')
    translated = read_json(language.read_bytes())
    with ZipFile(source) as upstream:
        english = read_json(upstream.read('assets/spawn/lang/en_us.json'))
        members = upstream.namelist()
        if len(members) != len(set(members)) or RESOURCE in members:
            raise ValueError('Unexpected duplicate or pre-existing translation')
        if any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I) for name in members):
            raise ValueError('Refusing to invalidate JAR signatures')
        if len(english) != 947 or translated.keys() != english.keys():
            raise ValueError('Translation must cover the exact 947 upstream keys')
        for key, value in translated.items():
            if not isinstance(value, str) or not value.strip() or '\ufffd' in value or '??' in value:
                raise ValueError('Invalid or corrupt translation: ' + key)
            if Counter(FORMAT.findall(value)) != Counter(FORMAT.findall(english[key])):
                raise ValueError('Changed format placeholders: ' + key)
            if Counter(re.findall(r'§.', value)) != Counter(re.findall(r'§.', english[key])):
                raise ValueError('Changed formatting codes: ' + key)
    data = (json.dumps(translated, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + '.partial')
    if output.exists() or partial.exists():
        raise FileExistsError('Preserve existing output: ' + str(output))
    with source.open('rb') as src, partial.open('xb') as dst:
        shutil.copyfileobj(src, dst)
    with ZipFile(partial, 'a', ZIP_DEFLATED, compresslevel=9) as target:
        entry = ZipInfo(RESOURCE, date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = ZIP_DEFLATED
        entry.create_system = 3
        entry.external_attr = 0o100644 << 16
        target.writestr(entry, data, compresslevel=9)
    with ZipFile(source) as upstream, ZipFile(partial) as patched:
        if set(patched.namelist()) != set(members) | {RESOURCE}:
            raise ValueError('Unexpected output members')
        if len(patched.namelist()) != len(members) + 1 or patched.testzip() is not None:
            raise ValueError('Output archive integrity failed')
        for name in members:
            if upstream.read(name) != patched.read(name):
                raise ValueError('Original payload changed: ' + name)
        if patched.read(RESOURCE) != data:
            raise ValueError('Translation readback differs')
    partial.replace(output)
    return {'revision': 'r63', 'file': output.name, 'sizeBytes': output.stat().st_size,
            'sha256': sha(output), 'upstreamSha256': ORIGINAL_SHA256,
            'languageKeys': len(translated), 'addedMembers': [RESOURCE],
            'unchangedOriginalMembers': len(members), 'originalPayloadsUnchanged': True,
            'translationSha256': hashlib.sha256(data).hexdigest().upper(),
            'gameplayCodeChanged': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--language', type=Path, default=ROOT / 'translations/spawn/zh_cn.json')
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    result = localize(args.source, args.output, args.language)
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(serialized, encoding='utf-8')
    print(serialized)
