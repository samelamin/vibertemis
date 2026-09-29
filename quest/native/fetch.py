#!/usr/bin/env python3
"""Fetch exact native sources; refuse to overwrite local work. Python 3 stdlib only."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PINS = json.loads((ROOT / "quest/native/sources.json").read_text())
DEST = ROOT / "build/quest"

def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)

def checkout(name, destination):
    pin = PINS[name]
    if not destination.exists():
        destination.mkdir(parents=True)
        run("git", "init", str(destination))
        run("git", "-C", str(destination), "config", "core.autocrlf", "false")
        run("git", "-C", str(destination), "remote", "add", "origin", pin["url"])
        run("git", "-C", str(destination), "fetch", "--depth=1", "origin", pin["commit"])
        run("git", "-C", str(destination), "checkout", "--detach", pin["commit"])
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=destination, text=True).strip()
    if head != pin["commit"]:
        raise SystemExit(f"Unexpected {name} revision in {destination}; use a fresh build directory")
    return destination

if __name__ == "__main__":
    alvr = checkout("alvr", DEST / "alvr")
    run("git", "submodule", "update", "--init", "openvr", cwd=alvr)
    patch = ROOT / "quest/native/alvr-pyrowave.patch"
    reverse = subprocess.run(["git", "apply", "--reverse", "--check", str(patch)], cwd=alvr, capture_output=True)
    if reverse.returncode:
        status = subprocess.check_output(["git", "status", "--porcelain"], cwd=alvr)
        if status:
            raise SystemExit("ALVR has local changes that differ from the native patch; refusing to overwrite")
        run("git", "apply", "--check", str(patch), cwd=alvr)
        run("git", "apply", str(patch), cwd=alvr)
    pyro = checkout("pyrowave", DEST / "pyrowave")
    granite = checkout("granite", pyro / "Granite")
    run("git", "submodule", "update", "--init", "third_party/volk", "third_party/khronos/vulkan-headers", cwd=granite)
