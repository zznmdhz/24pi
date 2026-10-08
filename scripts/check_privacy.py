#!/usr/bin/env python3
"""Check staged source and reachable Git history without printing sensitive values."""
import re
import subprocess
import sys
from pathlib import Path


def git(*args):
    return subprocess.check_output(["git", *args])


blocked_suffixes = {".db", ".sqlite", ".sqlite3", ".csv", ".tsv", ".zip", ".apk", ".aab",
                    ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".log", ".hprof",
                    ".7z", ".tar", ".gz", ".bak", ".kdbx"}
patterns = {
    "personal machine path": re.compile(r"/(?:Users|home)/[^/\s\"'<>]+/"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "machine hostname": re.compile(r"\b[\w-]+(?:MacBook|Mac-mini)[\w-]*\.local\b", re.I),
    "mobile number": re.compile(r"(?<![\w\d])1[3-9]\d{9}(?![\w\d])"),
}
email = re.compile(r"(?<![\w.+-])[\w.+-]+@[\w.-]+\.[a-z]{2,24}(?![\w.])")
findings = set()
checked = set()


def inspect(path, data):
    key = (path, data)
    if key in checked:
        return
    checked.add(key)
    name = Path(path)
    if (name.suffix.lower() in blocked_suffixes or name.name in {"local.properties", "keystore.properties", ".env"}
            or (name.name.startswith(".env.") and name.name != ".env.example")):
        findings.add((path, 0, "private configuration or data file"))
    if b"\x00" in data[:8192]:
        if path != "gradle/wrapper/gradle-wrapper.jar":
            findings.add((path, 0, "binary asset requires manual privacy review"))
        return
    for line_number, line in enumerate(data.decode("utf-8", errors="replace").splitlines(), 1):
        for label, pattern in patterns.items():
            if pattern.search(line):
                findings.add((path, line_number, label))
        for match in email.findall(line):
            domain = match.rsplit("@", 1)[1].lower()
            if domain not in {"users.noreply.github.com", "noreply.github.com", "example.com", "example.org", "example.net"}:
                findings.add((path, line_number, "personal email"))


# Include staged changes, so this also works before the initial commit.
for path in git("ls-files", "-z").decode().split("\x00"):
    if path:
        inspect(path, git("show", ":" + path))

objects = git("rev-list", "--objects", "--all").decode().splitlines()
if objects:
    proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    for entry in objects:
        sha, _, path = entry.partition(" ")
        proc.stdin.write((sha + "\n").encode())
        proc.stdin.flush()
        header = proc.stdout.readline().decode().split()
        size = int(header[2])
        data = proc.stdout.read(size)
        proc.stdout.read(1)
        if header[1] == "blob":
            inspect(path, data)
        elif header[1] in {"commit", "tag"}:
            inspect("git-metadata/" + sha, data)
    proc.stdin.close()
    proc.wait()

for path, line_number, reason in sorted(findings):
    print(f"{path}:{line_number}: {reason}")
if findings:
    print(f"Privacy check failed: {len(findings)} findings. Matched values are withheld.")
    sys.exit(1)
print(f"Privacy check passed: {len(checked)} source/history records checked.")
