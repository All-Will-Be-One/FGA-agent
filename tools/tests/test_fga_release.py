import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("fga_release", Path(__file__).parents[1] / "fga_release.py")
release_tool = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_tool
SPEC.loader.exec_module(release_tool)
Release, Version = release_tool.Release, release_tool.Version
OLD = Release(1, "v3065", "a" * 40)
NEW = Release(2, "v3068", "b" * 40)


class VersionTests(unittest.TestCase):
    def test_patch_rolls_from_decimal_digits_to_hex_letters(self):
        version = Version(NEW, 10)
        self.assertEqual(version.name, "3068-0x000a")
        self.assertEqual(version.code, 201064458)

    def test_new_upstream_resets_patch_and_increases_code(self):
        previous = Version(OLD, 0xffff)
        current = previous.advance(NEW)
        self.assertEqual((current.name, current.code), ("3068-0x0000", 201064448))
        self.assertGreater(current.code, previous.code)

    def test_same_release_retry_preserves_patch(self):
        current = Version(NEW, 0x12)
        self.assertEqual(current.advance(NEW), current)

    def test_downgrade_and_replaced_release_are_not_silent(self):
        current = Version(NEW, 0)
        for release in (OLD, Release(3, NEW.tag, NEW.sha), Release(NEW.id, NEW.tag, "c" * 40)):
            with self.subTest(release=release), self.assertRaises(ValueError):
                current.advance(release)

    def test_exhausted_patch_and_android_code_fail_without_wrapping(self):
        for version in (Version(NEW, 0x10000), Version(NEW, -1),
                        Version(Release(4, "v32043", "c" * 40), 0xffff)):
            with self.subTest(version=version), self.assertRaises(ValueError):
                _ = version.code

    def test_unpublished_or_other_branch_is_not_a_release_candidate(self):
        for field, value in (("draft", True), ("prerelease", True),
                             ("target_commitish", "desktop")):
            data = NEW.metadata() | {field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                Release.parse(data)

    def test_record_roundtrip_and_inconsistent_code(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            version = Version(NEW, 0xfe)
            release_tool.write_version(repo, version)
            self.assertEqual(release_tool.read_version(repo), version)
            path = repo / "fga-version.properties"
            path.write_text(path.read_text().replace(str(version.code), "1"))
            with self.assertRaises(ValueError):
                release_tool.read_version(repo)

    def test_patch_overflow_does_not_modify_record(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            release_tool.write_version(repo, Version(NEW, 0xffff))
            before = (repo / "fga-version.properties").read_bytes()
            with self.assertRaises(ValueError):
                release_tool.write_version(repo, Version(NEW, 0x10000))
            self.assertEqual((repo / "fga-version.properties").read_bytes(), before)


class StateTests(unittest.TestCase):
    def test_failed_validation_is_retried_even_when_version_was_prepared(self):
        state = {"releases": {NEW.key: {"status": "failed", "candidate_commit": "head"}}}
        self.assertEqual(release_tool.classify(Version(OLD, 1), NEW, state), "retry_required")
        self.assertEqual(release_tool.classify(Version(NEW, 0), NEW, state), "retry_required")

    def test_other_release_identity_cannot_count_as_synced(self):
        state = {"releases": {OLD.key: {"status": "synced", "candidate_commit": "head"}}}
        self.assertEqual(release_tool.classify(Version(OLD, 1), NEW, state), "update_available")

    def test_validation_and_sync_are_tied_to_the_clean_candidate_commit(self):
        state = {"releases": {NEW.key: {"status": "validated", "candidate_commit": "head"}}}
        current = Version(NEW, 0)
        self.assertEqual(release_tool.classify(current, NEW, state, "head"), "awaiting_sync")
        state["releases"][NEW.key]["status"] = "synced"
        self.assertEqual(release_tool.classify(current, NEW, state, "head"), "synced")
        self.assertEqual(release_tool.classify(current, NEW, state, "other"), "candidate")
        self.assertEqual(release_tool.classify(current, NEW, state, "head", False), "candidate")

    def test_local_validation_does_not_mark_remote_sync(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "-qm", "initial", "--allow-empty"], cwd=repo, check=True)
            sha = release_tool.run(repo, "git", "rev-parse", "HEAD")
            release = Release(2, "v3068", sha)
            release_tool.write_version(repo, Version(release, 0))
            subprocess.run(["git", "add", "fga-version.properties"], cwd=repo, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "-qm", "version"], cwd=repo, check=True)
            release_tool.record_state(repo, release, "validated")
            state = release_tool.read_state(repo)
            self.assertEqual(state["releases"][release.key]["status"], "validated")
            with self.assertRaises(subprocess.CalledProcessError):
                release_tool.record_state(repo, release, "synced")  # No configured remote.
            self.assertEqual(release_tool.read_state(repo), state)

    def test_prepare_retry_preserves_validated_state_and_patch(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "-qm", "initial", "--allow-empty"], cwd=repo, check=True)
            release = Release(2, "v3068", release_tool.run(repo, "git", "rev-parse", "HEAD"))
            release_tool.write_version(repo, Version(release, 0x12))
            subprocess.run(["git", "add", "fga-version.properties"], cwd=repo, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "-qm", "version"], cwd=repo, check=True)
            release_tool.record_state(repo, release, "validated")
            before = release_tool.state_path(repo).read_bytes()
            with tempfile.NamedTemporaryFile(mode="w", suffix=".json") as frozen:
                json.dump(release.metadata(), frozen)
                frozen.flush()
                output = subprocess.check_output([sys.executable, str(SPEC.origin), "--repo", str(repo),
                                                  "prepare", "--release-json", frozen.name], text=True)
            self.assertEqual(json.loads(output)["status"], "awaiting_sync")
            self.assertEqual(release_tool.read_version(repo).patch, 0x12)
            self.assertEqual(release_tool.state_path(repo).read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
