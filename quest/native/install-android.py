#!/usr/bin/env python3
"""Install only a complete hash-verified custom native set into the generated APK tree."""
import hashlib
from pathlib import Path
import shutil
ROOT = Path(__file__).resolve().parents[2]
source = ROOT / "build/quest/native-android"
target = ROOT / "build/quest/upstream/app/src/main/jniLibs/arm64-v8a"
expected = {"libalvr_client_openxr.so", "libpyroclient.so", "libpyrowave-shared.so"}
checksums = {}
for line in (source / "SHA256SUMS").read_text().splitlines():
    digest, name = line.split()
    if name not in expected or name in checksums:
        raise SystemExit("Invalid native library manifest")
    checksums[name] = digest
if set(checksums) != expected:
    raise SystemExit("Incomplete custom native set")
for name, digest in checksums.items():
    if hashlib.sha256((source/name).read_bytes()).hexdigest() != digest:
        raise SystemExit(f"Native library checksum mismatch: {name}")
target.mkdir(parents=True, exist_ok=True)
for name in sorted(expected):
    shutil.copy2(source/name, target/name)
shutil.copy2(source/"SHA256SUMS", target/"vibertemis-native.sha256")
print("Installed complete custom ALVR + PyroWave native set")
