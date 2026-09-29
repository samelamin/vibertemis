#!/usr/bin/env python3
"""Identity of native source and build inputs, independent of checkout path."""
import hashlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
def fingerprint():
    paths = [ROOT / 'quest/native' / name for name in ('alvr-pyrowave.patch', 'sources.json', 'build-android.sh')]
    paths += [path for path in (ROOT / 'quest/native/pyroclient').iterdir() if path.is_file()]
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda p: p.relative_to(ROOT).as_posix()):
        digest.update(str(path.relative_to(ROOT)).replace('\\', '/').encode() + b'\0')
        digest.update(path.read_bytes() + b'\0')
    return digest.hexdigest()
if __name__ == '__main__': print(fingerprint())
