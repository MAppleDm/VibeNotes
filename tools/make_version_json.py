#!/usr/bin/env python3
"""Writes version.json for the in-app updater: version, APK link, SHA-256 and what's new.

"What's new" is the body of the first "## " section of CHANGELOG.md.
"""
import argparse
import hashlib
import json
import os
import re


def whats_new(path):
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except FileNotFoundError:
        return ""
    sections = re.split(r"^## ", text, flags=re.M)
    if len(sections) < 2:
        return ""
    _title, _, body = sections[1].partition("\n")
    return body.strip()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True)
    p.add_argument("--code", type=int, required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--commit", default="")
    p.add_argument("--changelog", default="CHANGELOG.md")
    p.add_argument("--url", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    digest = hashlib.sha256()
    with open(args.apk, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)

    info = {
        "versionCode": args.code,
        "versionName": args.name,
        "apkUrl": args.url,
        "sha256": digest.hexdigest(),
        "size": os.path.getsize(args.apk),
        "commit": args.commit,
        "notes": whats_new(args.changelog),
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
