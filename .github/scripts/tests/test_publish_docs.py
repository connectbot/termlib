import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("publisher", SCRIPTS / "publish-docs.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


def publication(path, text="public API"):
    path.mkdir(parents=True)
    (path / "index.html").write_text(text)
    (path / "index.md").write_text(text)


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "archive"
        self.output = self.root / "output"
        self.logs = self.root / "logs"
        for path in (self.archive, self.output, self.logs):
            path.mkdir()
        self.calls = []

    def builder(self, repo, scripts, commit, name, output, log):
        self.calls.append(name)
        publication(output, commit)

    def run_reconcile(self, previous=None, versions=None, builder=None, retry=False, config="config"):
        with patch.object(publisher, "git", return_value=b"plugins { alias(libs.plugins.dokka) }"):
            return publisher.reconcile(
                self.root, SCRIPTS, self.archive, self.output, self.logs,
                previous or publisher.DocumentationManifest(),
                versions or {"main": "new-main", "0.1.0": "release"},
                config, retry, builder or self.builder)

    def previous(self, name, commit="release", config="config", status="success"):
        publication(self.archive / name, "previous content")
        published = publisher.DocumentationConfiguration(commit, config)
        attempt = publisher.BuildAttempt(published, publisher.BuildStatus(status))
        return publisher.DocumentationManifest({name: publisher.VersionDocumentation(published, attempt)})

    def test_supported_tags_and_semver_order(self):
        tags = ["0.0.40", "v0.0.1", "0.1.0-rc.1", "main", "test/0.2.0", "0.2.0-01"]
        self.assertTrue(all(publisher.release_key(tag) is None for tag in tags))
        supported = ["v0.2.0", "0.10.0", "0.2.0-rc.2", "0.2.0-rc.10", "0.1.0"]
        self.assertEqual(sorted(supported, key=publisher.release_key, reverse=True),
                         ["0.10.0", "v0.2.0", "0.2.0-rc.10", "0.2.0-rc.2", "0.1.0"])

    def test_initial_backfill_and_no_unverified_content(self):
        publication(self.archive / "0.1.0", "unverified")
        records, _ = self.run_reconcile()
        self.assertEqual(self.calls, ["main", "0.1.0"])
        self.assertEqual((self.output / "0.1.0/index.html").read_text(), "release")
        self.assertEqual(records.versions["0.1.0"].published.policy, publisher.POLICY)

    def test_new_tag_preserves_existing_release(self):
        previous = self.previous("0.1.0")
        self.run_reconcile(previous, {"main": "main", "0.1.0": "release", "0.2.0": "new"})
        self.assertEqual(self.calls, ["main", "0.2.0"])
        self.assertEqual((self.output / "0.1.0/index.html").read_text(), "previous content")

    def test_failed_tag_not_retried_even_after_source_or_config_change(self):
        previous = publisher.DocumentationManifest({
            "0.1.0": publisher.VersionDocumentation(
                attempt=publisher.BuildAttempt(
                    publisher.DocumentationConfiguration("old", "config"),
                    publisher.BuildStatus.FAILED)),
        })
        records, messages = self.run_reconcile(previous, config="changed")
        self.assertEqual(self.calls, ["main"])
        self.assertIsNone(records.versions["0.1.0"].published)
        self.assertIn("not retried", messages[-1])
        self.assertFalse(messages[-1].split(": ", 1)[-1].startswith("failed"))

    def test_manual_retry(self):
        previous = publisher.DocumentationManifest({
            "0.1.0": publisher.VersionDocumentation(
                attempt=publisher.BuildAttempt(
                    publisher.DocumentationConfiguration("old", "config"),
                    publisher.BuildStatus.FAILED)),
        })
        records, _ = self.run_reconcile(previous, retry=True)
        self.assertIs(records.versions["0.1.0"].attempt.status, publisher.BuildStatus.SUCCESS)
        self.assertIn("0.1.0", self.calls)

    def test_failed_main_keeps_previous_and_retries_next_run(self):
        previous = self.previous("main", "old-main")

        def fail(*args):
            raise RuntimeError("build failure")

        records, _ = self.run_reconcile(previous, {"main": "new-main"}, fail)
        self.assertEqual((self.output / "main/index.html").read_text(), "previous content")
        main = records.versions["main"]
        self.assertIs(main.attempt.status, publisher.BuildStatus.FAILED)
        self.assertTrue(main.should_build(
            "main", publisher.DocumentationConfiguration("new-main", "config"), False))

    def test_configuration_change_rebuilds_successful_release(self):
        self.run_reconcile(self.previous("0.1.0"), config="new-config")
        self.assertIn("0.1.0", self.calls)

    def test_missing_dokka_is_skipped(self):
        with patch.object(publisher, "git", return_value=b"plugins { kotlin() }"):
            records, _ = publisher.reconcile(
                self.root, SCRIPTS, self.archive, self.output, self.logs,
                publisher.DocumentationManifest(), {"0.1.0": "release"},
                "config", builder=self.builder)
        self.assertIs(records.versions["0.1.0"].attempt.status, publisher.BuildStatus.SKIPPED)
        self.assertEqual(self.calls, [])

    def test_failed_initial_main_has_no_deployable_entrypoint(self):
        def fail(*args):
            raise RuntimeError("build failure")

        self.run_reconcile(builder=fail)
        self.assertFalse((self.output / "main/index.html").exists())

    def test_absent_library_is_skipped(self):
        with patch.object(publisher, "git", return_value=b""):
            records, _ = publisher.reconcile(
                self.root, SCRIPTS, self.archive, self.output, self.logs,
                publisher.DocumentationManifest(), {"0.1.0": "release"},
                "config", builder=self.builder)
        self.assertIs(records.versions["0.1.0"].attempt.status, publisher.BuildStatus.SKIPPED)

    def test_old_visibility_policy_is_not_served_after_failure(self):
        previous = self.previous("0.1.0")
        previous.versions["0.1.0"].published = publisher.DocumentationConfiguration(
            "release", "config", policy=0)

        def fail(*args):
            raise RuntimeError("build failure")

        records, _ = self.run_reconcile(previous, {"0.1.0": "release"}, fail)
        self.assertIsNone(records.versions["0.1.0"].published)
        self.assertFalse((self.output / "0.1.0").exists())

    def test_manifest_without_policy_is_unverified(self):
        path = self.archive / "manifest.json"
        path.write_text(json.dumps({
            "schema": 1,
            "versions": {"0.1.0": {"published": {
                "commit": "release", "config": "config",
            }}},
        }))
        manifest = publisher.DocumentationManifest.read(path)
        self.assertEqual(manifest.versions["0.1.0"].published.policy, 0)

    def test_manifest_round_trip_uses_configuration_classes(self):
        manifest = self.previous("0.1.0")
        path = self.archive / "manifest.json"
        manifest.write(path)
        self.assertEqual(publisher.DocumentationManifest.read(path), manifest)

    def test_site_without_main_is_not_deployable(self):
        publication(self.output / "0.1.0")
        self.assertFalse(publisher.generate_site(self.output, ["main", "0.1.0"], publisher.BASE_URL))
        self.assertFalse((self.output / "index.html").exists())

    def test_indexes_and_sitemaps(self):
        for name in ("main", "0.1.0", "v0.2.0", "0.10.0"):
            publication(self.output / name)
            (self.output / name / "a space.html").write_text("API")
        publisher.generate_site(self.output, ["main", "0.10.0", "v0.2.0", "0.1.0"], publisher.BASE_URL)
        index = (self.output / "index.html").read_text()
        links = [index.index(f'{publisher.BASE_URL}/{name}/index.html')
                 for name in ("main", "0.10.0", "v0.2.0", "0.1.0")]
        self.assertEqual(links, sorted(links))
        self.assertIn("a%20space.html", (self.output / "main/sitemap.xml").read_text())
        self.assertEqual(len(ET.parse(self.output / "sitemap_index.xml").getroot()), 4)

    def test_sitemap_encodes_and_escapes_urls(self):
        publication(self.output / "main")
        publication(self.output / "0.2.0+build")
        (self.output / "main/a&b.html").write_text("API")
        (self.output / "main/navigation.html").write_text("navigation")
        publisher.generate_site(self.output, ["main", "0.2.0+build", "0.3.0"], publisher.BASE_URL)
        self.assertIn("a%26b.html", (self.output / "main/sitemap.xml").read_text())
        self.assertNotIn("navigation.html", (self.output / "main/sitemap.xml").read_text())
        self.assertIn("0.2.0%2Bbuild", (self.output / "index.html").read_text())
        self.assertNotIn("0.3.0", (self.output / "index.html").read_text())

    def test_archive_round_trip_and_unchanged_tree(self):
        remote = self.root / "remote.git"
        repo = self.root / "repo"
        subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)
        subprocess.run(["git", "init", str(repo)], check=True, capture_output=True)
        publisher.git(repo, "remote", "add", "origin", str(remote))
        parent, manifest = publisher.load_archive(repo, self.archive)
        self.assertIsNone(parent)
        self.assertEqual(manifest, publisher.DocumentationManifest())
        publication(self.output / "main")
        (self.output / "manifest.json").write_text(json.dumps({"schema": 1, "versions": {"main": {}}}))
        publisher.save_archive(repo, self.output, None)
        parent, manifest = publisher.load_archive(repo, self.archive)
        self.assertIn("main", manifest.versions)
        self.assertEqual((self.archive / "main/index.md").read_text(), "public API")
        publisher.save_archive(repo, self.output, parent)
        self.assertEqual(publisher.git(remote, "rev-parse", "gh-pages").decode().strip(), parent)
        self.assertFalse((repo / ".git/index").exists())


if __name__ == "__main__":
    unittest.main()
