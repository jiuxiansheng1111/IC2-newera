"""Cross-platform process locks for the mutable r71 release and PCL instance."""
from contextlib import contextmanager
from functools import wraps
import os
from pathlib import Path
import time
import errno


@contextmanager
def file_lock(path: Path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.touch(exist_ok=True)
    stream = path.open("r+b")
    try:
        if path.stat().st_size == 0:
            stream.write(b"\0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            while True:
                try:
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                    break
                except OSError as exc:
                    if getattr(exc, "winerror", None) not in (33, 36) and exc.errno not in (errno.EACCES, errno.EDEADLK):
                        raise
                    time.sleep(0.2)
            try:
                yield
            finally:
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
    finally:
        stream.close()


def serialize_r71_release(function):
    """Serialize all code paths that mutate r71 selection, overlays, or archives."""
    @wraps(function)
    def locked(*args, **kwargs):
        root = Path(__file__).resolve().parents[2]
        lock = root / "build-isolated/player-packaging/20261113-delight-joint/deployment-locks/r71-release.lock"
        with file_lock(lock):
            return function(*args, **kwargs)
    return locked
