#!/usr/bin/env python3
"""Prevent a custom-codec UI from building against stale/stock native libraries."""
import hashlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
target = ROOT / "build/quest/upstream/app/src/main/jniLibs/arm64-v8a"
manifest = target / "vibertemis-native.sha256"
expected = {"libalvr_client_openxr.so", "libpyroclient.so", "libpyrowave-shared.so"}
if not manifest.exists():
    raise SystemExit("Custom PCVR libraries missing. Run quest/native/build-android.sh then install-android.py after fetch.sh")
seen = set()
for line in manifest.read_text().splitlines():
    digest, name = line.split()
    if name not in expected or name in seen or hashlib.sha256((target/name).read_bytes()).hexdigest() != digest:
        raise SystemExit("Native library set changed; rebuild and install the matching custom libraries")
    seen.add(name)
if seen != expected:
    raise SystemExit("Incomplete native library set")
print("Custom PCVR library checksums verified")
