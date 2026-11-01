#!/usr/bin/env python3
"""Publish a hash-pinned tested APK, never a fresh CI debug build or a signing key."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def command(*args, binary=False):
    result = subprocess.check_output(args, cwd=ROOT)
    return result if binary else result.decode().strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", args.tag):
        parser.error("Invalid release tag")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        parser.error("Run this publisher in the trusted tag workflow")
    head = command("git", "rev-parse", "HEAD")
    metadata_path = ROOT / "docs/releases" / f"{args.tag}.json"
    metadata = json.loads(metadata_path.read_text())
    name = metadata["apk_name"]
    if metadata["tag"] != args.tag or not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", name):
        parser.error("Invalid release metadata")
    tag_ref = f"refs/tags/{args.tag}"
    refs = json.loads(command("gh", "api", f"repos/{repo}/git/matching-refs/tags/{args.tag}"))
    if not any(item["ref"] == tag_ref for item in refs):
        if os.environ.get("GITHUB_REF") != f"refs/heads/release/{args.tag}":
            parser.error("Tag missing; only an explicit trusted release branch may create it")
        # This branch path also supports maintainers publishing through the GitHub connector.
        tag = json.loads(command("gh", "api", "--method", "POST", f"repos/{repo}/git/tags",
                                 "-f", f"tag={args.tag}", "-f", f"message=BrotherFastPrint {metadata['version_name']}",
                                 "-f", f"object={head}", "-f", "type=commit"))
        command("gh", "api", "--method", "POST", f"repos/{repo}/git/refs",
                "-f", f"ref={tag_ref}", "-f", f"sha={tag['sha']}")
    command("git", "fetch", "--no-tags", "origin", f"{tag_ref}:{tag_ref}")
    if head != command("git", "rev-parse", f"{tag_ref}^{{commit}}"):
        parser.error("Checked-out source does not match the release tag")
    endpoint = f"repos/{repo}/releases/tags/{args.tag}"
    # Distinguish a missing release from authentication/network failures.
    releases = json.loads(command("gh", "api", f"repos/{repo}/releases?per_page=100"))
    release = next((item for item in releases if item["tag_name"] == args.tag), None)
    published = release is not None and not release["draft"]
    stage_commit = None
    with tempfile.TemporaryDirectory(prefix="brotherfastprint-release-") as work:
        output = Path(work)
        apk = output / name
        if published:
            command("gh", "release", "download", args.tag, "--repo", repo,
                    "--pattern", name, "--dir", str(output))
        else:
            branch = f"refs/heads/release-assets/{args.tag}"
            command("git", "fetch", "--no-tags", "origin", branch)
            stage_commit = command("git", "rev-parse", "FETCH_HEAD")
            stage = json.loads(command("git", "show", f"{stage_commit}:release-manifest.json"))
            expected = {"tag": args.tag, "source_commit": head, "apk_name": name,
                        "apk_sha256": metadata["apk_sha256"]}
            if stage != expected:
                raise RuntimeError("Staged APK is not bound to this source commit and metadata")
            apk.write_bytes(command("git", "show", f"{stage_commit}:{name}", binary=True))
        if apk.stat().st_size != metadata["apk_size_bytes"] or sha256(apk) != metadata["apk_sha256"]:
            raise RuntimeError("Tested APK hash/size mismatch; refusing publication")
        source = output / f"BrotherFastPrint-{metadata['version_name']}-source.zip"
        command("git", "archive", "--format=zip", "--prefix=BrotherFastPrint/",
                f"--output={source}", head)
        metadata_asset = output / f"BrotherFastPrint-{metadata['version_name']}-metadata.json"
        public_metadata = dict(metadata, source_commit=head)
        metadata_asset.write_text(json.dumps(public_metadata, indent=2) + "\n")
        assets = [apk, source, metadata_asset]
        checksums = output / "SHA256SUMS"
        checksums.write_text("".join(f"{sha256(path)}  {path.name}\n" for path in assets))
        assets.append(checksums)
        if release is None:
            command("gh", "release", "create", args.tag, "--repo", repo, "--verify-tag",
                    "--target", head, "--draft", "--prerelease", "--title",
                    f"BrotherFastPrint {metadata['version_name']}", "--notes-file",
                    str(ROOT / "docs/releases" / f"{args.tag}.md"))
        release = json.loads(command("gh", "api", endpoint))
        existing = {asset["name"] for asset in release["assets"]}
        for asset in assets:
            if asset.name not in existing:
                if published:
                    raise RuntimeError(f"Published release is incomplete: {asset.name}")
                command("gh", "release", "upload", args.tag, str(asset), "--repo", repo)
            verify_dir = output / "verify"
            verify_dir.mkdir(exist_ok=True)
            command("gh", "release", "download", args.tag, "--repo", repo,
                    "--pattern", asset.name, "--dir", str(verify_dir))
            if sha256(verify_dir / asset.name) != sha256(asset):
                raise RuntimeError(f"Release asset differs: {asset.name}; refusing to overwrite")
        if not published:
            command("gh", "release", "edit", args.tag, "--repo", repo, "--draft=false", "--prerelease")
        print(f"Published and verified: https://github.com/{repo}/releases/tag/{args.tag}")
    if stage_commit is not None:
        # Delete only the staging ref we consumed, with an exact lease. Never main or a tag.
        command("gh", "auth", "setup-git")
        ref = f"refs/heads/release-assets/{args.tag}"
        result = subprocess.run(["git", "push", f"--force-with-lease={ref}:{stage_commit}",
                                 "origin", f":{ref}"], cwd=ROOT, check=False)
        if result.returncode:
            print("::warning::Release published; temporary asset branch needs manual cleanup")
        release_ref = f"refs/heads/release/{args.tag}"
        if os.environ.get("GITHUB_REF") == release_ref:
            result = subprocess.run(["git", "push", f"--force-with-lease={release_ref}:{head}",
                                     "origin", f":{release_ref}"], cwd=ROOT, check=False)
            if result.returncode:
                print("::warning::Release published; temporary release branch needs manual cleanup")


if __name__ == "__main__":
    main()
