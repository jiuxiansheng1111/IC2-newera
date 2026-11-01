"""Demucs two-stem separation in an isolated process with local checkpoints only."""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

CHECKPOINT = "955717e8-8726e21a.th"
CHECKPOINT_BYTES = 84141911
CHECKPOINT_SHA256 = "8726e21a993978c7ba086d3872e7608d7d5bfcA646CA4ACA459FFDA844FAA8B4".lower()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=("htdemucs",), required=True)
    parser.add_argument("--model-cache", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cache = args.model_cache.resolve(strict=True)
    checkpoint = (cache / "checkpoints" / CHECKPOINT).resolve(strict=True)
    if (not checkpoint.is_relative_to(cache) or not checkpoint.is_file()
            or checkpoint.stat().st_size != CHECKPOINT_BYTES):
        raise FileNotFoundError("The configured local Demucs checkpoint is unavailable")
    digest = hashlib.sha256()
    with checkpoint.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    checksum = digest.hexdigest()
    if checksum != CHECKPOINT_SHA256 or CHECKPOINT.split("-")[1].split(".")[0] != checksum[:8]:
        raise ValueError("The configured local Demucs checkpoint failed its filename hash check")

    import torch
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.hub.set_dir(str(cache))
    def local_only(url, *positional, **keywords):
        filename = Path(str(url).split("?", 1)[0]).name
        candidate = (cache / "checkpoints" / filename).resolve()
        if filename != CHECKPOINT or candidate != checkpoint or not candidate.is_file():
            raise RuntimeError("Automatic model downloads are disabled")
        try:
            return torch.load(candidate, map_location=keywords.get("map_location", "cpu"), weights_only=False)
        except TypeError:  # Older installed Torch versions do not expose weights_only.
            return torch.load(candidate, map_location=keywords.get("map_location", "cpu"))

    torch.hub.load_state_dict_from_url = local_only
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sys.argv = ["demucs", "--two-stems=vocals", "--name", args.model, "--device", "cuda",
                "--shifts", "1", "--segment", "7", "--out", str(output), str(source)]
    from demucs.separate import main as separate
    separate()


if __name__ == "__main__":
    main()
