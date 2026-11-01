#!/usr/bin/env python3
"""Stage a previously tested APK without adding binaries or signing keys to main."""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def git(*args, data=None):
    return subprocess.check_output(["git", *args], cwd=ROOT, input=data).decode().strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--push", action="store_true", help="Create the release-assets branch on origin")
    args = parser.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", args.tag):
        parser.error("Invalid release tag")
    metadata = json.loads((ROOT / "docs/releases" / f"{args.tag}.json").read_text())
    apk = args.apk.read_bytes()
    if (metadata["tag"] != args.tag or len(apk) != metadata["apk_size_bytes"]
            or hashlib.sha256(apk).hexdigest() != metadata["apk_sha256"]):
        parser.error("APK does not match the committed release metadata")
    name = metadata["apk_name"]
    if Path(name).name != name or not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", name):
        parser.error("Unsafe APK filename")
    if not args.push:
        print(f"Verified {name}: {len(apk)} bytes, SHA-256 {metadata['apk_sha256']}")
        return
    if git("status", "--porcelain"):
        parser.error("Commit source and release metadata before staging the APK")
    source_sha = git("rev-parse", "HEAD")
    manifest = json.dumps({"tag": args.tag, "source_commit": source_sha,
                           "apk_name": name, "apk_sha256": metadata["apk_sha256"]}, indent=2) + "\n"
    apk_blob = git("hash-object", "-w", "--stdin", data=apk)
    manifest_blob = git("hash-object", "-w", "--stdin", data=manifest.encode())
    entries = f"100644 blob {apk_blob}\t{name}\n100644 blob {manifest_blob}\trelease-manifest.json\n"
    tree = git("mktree", data=entries.encode())
    commit = git("commit-tree", tree, "-m", f"Stage verified {args.tag} APK for source {source_sha}")
    # An orphan commit cannot fast-forward an existing staging branch. Never force it.
    branch = f"refs/heads/release-assets/{args.tag}"
    subprocess.run(["git", "push", "origin", f"{commit}:{branch}"], cwd=ROOT, check=True)
    print(f"Staged {args.tag} for source {source_sha}; now push the tag at that source commit.")


if __name__ == "__main__":
    main()
