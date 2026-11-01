"""Bounded-memory mix of an RVC vocal stem and the separated accompaniment."""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
import soundfile as sf


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vocals", type=Path, required=True)
    parser.add_argument("--accompaniment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--accompaniment-gain", type=float, required=True)
    parser.add_argument("--vocal-target-rms", type=float, required=True)
    args = parser.parse_args()
    if not 0.5 <= args.accompaniment_gain <= 1.0 or not 0.05 <= args.vocal_target_rms <= 0.35:
        raise ValueError("Invalid mix settings")
    return args


def main():
    args = parse_args()
    with sf.SoundFile(args.vocals) as vocal, sf.SoundFile(args.accompaniment) as backing:
        if vocal.samplerate != 44100 or backing.samplerate != 44100 or backing.channels not in (1, 2):
            raise ValueError("Demucs stems must use 44.1 kHz mono/stereo audio")
        if abs(len(vocal) - len(backing)) > 44100:
            raise ValueError("Vocal and accompaniment durations differ by more than one second")
        frames = min(len(vocal), len(backing))
        if frames <= 0:
            raise ValueError("Empty cover stems")
        vocal_sum = backing_sum = 0.0
        vocal_count = backing_count = 0
        while vocal.tell() < frames and backing.tell() < frames:
            left = vocal.read(min(65536, frames - vocal.tell()), dtype="float32", always_2d=True)
            right = backing.read(min(65536, frames - backing.tell()), dtype="float32", always_2d=True)
            if not left.size or not right.size or not np.all(np.isfinite(left)) or not np.all(np.isfinite(right)):
                raise ValueError("Invalid cover stem audio")
            vocal_sum += float(np.square(left, dtype=np.float64).sum())
            backing_sum += float(np.square(right, dtype=np.float64).sum())
            vocal_count += left.size
            backing_count += right.size
        vocal_rms = math.sqrt(vocal_sum / max(vocal_count, 1))
        backing_rms = math.sqrt(backing_sum / max(backing_count, 1))
        if vocal_rms < 0.0001:
            raise ValueError("Converted vocal stem is silent")
        target = max(args.vocal_target_rms, backing_rms * args.accompaniment_gain * (10 ** (3 / 20)))
        vocal_gain = min(4.0, max(1.0, target / vocal_rms))
        vocal.seek(0)
        backing.seek(0)
        mix_peak = 0.0
        remaining = frames
        while remaining:
            size = min(65536, remaining)
            left = vocal.read(size, dtype="float32", always_2d=True)
            right = backing.read(size, dtype="float32", always_2d=True)
            if left.shape[0] != right.shape[0]:
                raise ValueError("Cover stems ended at different frames")
            if left.shape[1] == 1:
                left = np.repeat(left, 2, axis=1)
            if right.shape[1] == 1:
                right = np.repeat(right, 2, axis=1)
            mixed = left[:size, :2] * vocal_gain + right[:size, :2] * args.accompaniment_gain
            mix_peak = max(mix_peak, float(np.max(np.abs(mixed))) if mixed.size else 0.0)
            remaining -= size
        output_gain = min(1.0, 0.98 / mix_peak) if mix_peak > 0 else 1.0
        vocal.seek(0)
        backing.seek(0)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with sf.SoundFile(args.output, "w", samplerate=44100, channels=2, subtype="PCM_16", format="WAV") as out:
            remaining = frames
            while remaining:
                size = min(65536, remaining)
                left = vocal.read(size, dtype="float32", always_2d=True)
                right = backing.read(size, dtype="float32", always_2d=True)
                if left.shape[0] != right.shape[0]:
                    raise ValueError("Cover stems ended at different frames")
                if left.shape[1] == 1:
                    left = np.repeat(left, 2, axis=1)
                if right.shape[1] == 1:
                    right = np.repeat(right, 2, axis=1)
                mixed = left[:size, :2] * vocal_gain + right[:size, :2] * args.accompaniment_gain
                out.write(np.clip(mixed * output_gain, -1.0, 1.0))
                remaining -= size
    print('{"backend":"rvc_vocals_plus_demucs_accompaniment","sample_rate":44100}', flush=True)


if __name__ == "__main__":
    main()
