#!/usr/bin/env python3
"""Inspect stable FGA releases and maintain the native fork's version record.

This tool never merges, pushes, publishes, schedules, builds, or installs anything.
"""

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys


UPSTREAM = "Fate-Grand-Automata/FGA"
UPSTREAM_BRANCH = "master"
FORK_BRANCH = "fgoagent/main"
MAX_CODE = 2_100_000_000


def run(repo, *args):
    return subprocess.check_output(args, cwd=repo, text=True, stderr=subprocess.PIPE).strip()


@dataclass(frozen=True)
class Release:
    id: int
    tag: str
    sha: str

    @property
    def number(self):
        return int(self.tag[1:])

    @property
    def key(self):
        return f"{self.id}:{self.tag}:{self.sha}"

    def metadata(self):
        return {"id": self.id, "tag_name": self.tag, "sha": self.sha,
                "draft": False, "prerelease": False, "target_commitish": UPSTREAM_BRANCH}

    @classmethod
    def parse(cls, data, sha=None):
        data = data.get("release", data)
        if data.get("draft") is not False or data.get("prerelease") is not False:
            raise ValueError("Only published stable upstream releases are supported")
        if data.get("target_commitish") != UPSTREAM_BRANCH:
            raise ValueError(f"Recheck upstream release branch: expected {UPSTREAM_BRANCH}")
        tag = data["tag_name"]
        sha = sha or data["sha"]
        release_id = data["id"]
        if not isinstance(release_id, int) or isinstance(release_id, bool) or release_id <= 0:
            raise ValueError("Invalid upstream release ID")
        if not re.fullmatch(r"v[1-9][0-9]*", tag) or not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("Expected a numeric upstream release tag and full commit SHA")
        return cls(release_id, tag, sha)


@dataclass(frozen=True)
class Version:
    release: Release
    patch: int

    @property
    def name(self):
        return f"{self.release.number}-0x{self.patch:04x}"

    @property
    def code(self):
        if not 0 <= self.patch <= 0xffff:
            raise ValueError("Native patch exhausted; never wrap the patch counter")
        code = self.release.number * 65536 + self.patch
        if not 1 <= code <= MAX_CODE:
            raise ValueError("Version code exceeds the supported Android release range")
        return code

    def advance(self, release):
        if release == self.release:
            return self  # Retry does not reset an already incremented native patch.
        if release.number <= self.release.number:
            raise ValueError("Upstream release must advance; changed identity needs manual review")
        return Version(release, 0)


def read_version(repo):
    props = {}
    for line in (repo / "fga-version.properties").read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    if (props["upstream.repository"], props["upstream.branch"]) != (UPSTREAM, UPSTREAM_BRANCH):
        raise ValueError("Unexpected native fork upstream")
    release = Release.parse({"id": int(props["upstream.releaseId"]),
                             "tag_name": props["upstream.tag"], "sha": props["upstream.sha"],
                             "draft": False, "prerelease": False,
                             "target_commitish": props["upstream.branch"]})
    if not re.fullmatch(r"0x[0-9a-f]{4}", props["patch"]):
        raise ValueError("Native patch must be four lowercase hexadecimal digits")
    version = Version(release, int(props["patch"], 16))
    if int(props["versionCode"]) != version.code:
        raise ValueError("Recorded versionCode disagrees with upstream/patch")
    return version


def write_version(repo, version):
    text = ("# Native fork release identity. Updated by tools/fga_release.py, never by CI run numbers.\n"
            f"upstream.repository={UPSTREAM}\nupstream.branch={UPSTREAM_BRANCH}\n"
            f"upstream.releaseId={version.release.id}\nupstream.tag={version.release.tag}\n"
            f"upstream.sha={version.release.sha}\npatch=0x{version.patch:04x}\n"
            f"versionCode={version.code}\n")
    (repo / "fga-version.properties").write_text(text)


def state_path(repo):
    path = Path(run(repo, "git", "rev-parse", "--git-path", "fga-upstream-state.json"))
    return path if path.is_absolute() else repo / path


def read_state(repo):
    path = state_path(repo)
    return json.loads(path.read_text()) if path.exists() else {"schema_version": 1, "releases": {}}


def record_state(repo, release, status, note=""):
    commit = run(repo, "git", "rev-parse", "HEAD")
    if status in ("validated", "synced"):
        if read_version(repo).release != release:
            raise ValueError("Version record does not match the release being validated")
        run(repo, "git", "merge-base", "--is-ancestor", release.sha, "HEAD")
        if run(repo, "git", "status", "--porcelain"):
            raise ValueError("Commit the candidate before recording validation or synchronization")
    if status == "synced":
        remote = run(repo, "git", "ls-remote", "origin", f"refs/heads/{FORK_BRANCH}")
        if not remote or remote.split()[0] != commit:
            raise ValueError("Synchronization requires the exact candidate on origin/fgoagent/main")
    state = read_state(repo)
    state["releases"][release.key] = {
        "release": release.metadata(), "status": status, "candidate_commit": commit,
        "updated_at": datetime.now(timezone.utc).isoformat(), "note": note,
    }
    path = state_path(repo)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n")
    temporary.replace(path)


def classify(version, release, state, commit=None, clean=True):
    attempt = state["releases"].get(release.key, {})
    if version.release != release:
        return "update_available" if attempt.get("status") != "failed" else "retry_required"
    if attempt.get("status") in ("synced", "validated") and (
        not clean or attempt.get("candidate_commit") != commit
    ):
        return "candidate"
    return {"synced": "synced", "failed": "retry_required", "validated": "awaiting_sync"}.get(
        attempt.get("status"), "candidate")


def get_release(repo, args):
    if args.release_json:
        return Release.parse(json.loads(args.release_json.read_text()), args.sha)
    data = json.loads(run(repo, "gh", "api", f"repos/{UPSTREAM}/releases/latest"))
    commit = json.loads(run(repo, "gh", "api", f"repos/{UPSTREAM}/commits/{data['tag_name']}"))
    return Release.parse(data, commit["sha"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("check", "prepare", "record"):
        cmd = sub.add_parser(command)
        cmd.add_argument("--release-json", type=Path, required=command != "check",
                         help="Frozen check output or GitHub Release JSON")
        cmd.add_argument("--sha", help="Resolved full commit SHA, if absent from the JSON")
        if command == "record":
            cmd.add_argument("--status", required=True, choices=("candidate", "failed", "validated", "synced"))
            cmd.add_argument("--note", default="")
    sub.add_parser("show")
    sub.add_parser("bump-patch")
    args = parser.parse_args()
    repo = args.repo.resolve()
    try:
        version = read_version(repo)
        if args.command in ("check", "prepare", "record"):
            release = get_release(repo, args)
            if args.command == "prepare":
                run(repo, "git", "merge-base", "--is-ancestor", release.sha, "HEAD")
                next_version = version.advance(release)
                if next_version != version or release.key not in read_state(repo)["releases"]:
                    write_version(repo, next_version)
                    record_state(repo, release, "candidate")
                version = next_version
            elif args.command == "record":
                record_state(repo, release, args.status, args.note)
            state = read_state(repo)
            result = {"release": release.metadata(), "status": classify(
                          version, release, state, run(repo, "git", "rev-parse", "HEAD"),
                          not run(repo, "git", "status", "--porcelain")),
                      "state_path": str(state_path(repo))}
        else:
            if args.command == "bump-patch":
                version = Version(version.release, version.patch + 1)
                write_version(repo, version)
                record_state(repo, version.release, "candidate", "Native patch incremented")
            result = {}
        result.update(versionName=version.name, versionCode=version.code)
        print(json.dumps(result, indent=2))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        detail = exc.stderr.strip() if isinstance(exc, subprocess.CalledProcessError) else str(exc)
        print(f"fga-release: {detail or str(exc)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
