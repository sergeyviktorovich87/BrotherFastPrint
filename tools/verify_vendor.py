#!/usr/bin/env python3
"""Check the vendored files against the pinned upstream Git blob IDs."""
import hashlib
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[1] / "third_party" / "brlaser"
manifest = json.loads((root / "UPSTREAM.json").read_text())
for name, expected in manifest["git_blob_sha1"].items():
    data = (root / name).read_bytes()
    actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if actual != expected:
        raise SystemExit("Upstream file differs: " + name)
print("brlaser: all vendored files match commit " + manifest["commit"])
