#!/usr/bin/env python3
"""Reconcile release documentation, then save a complete GitHub Pages archive.

The archive is a generated Git tree: committing it does not use or change the
source checkout's index. Only this script's --publish path pushes gh-pages.
"""

import argparse
from dataclasses import asdict, dataclass, field
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

from docs_site import generate_site


BASE_URL = "https://termlib.connectbot.org"
POLICY = 1  # Bump if the public-only filtering contract changes.
TAG = re.compile(r"v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
                 r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
                 r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?\Z")


class BuildStatus(str, Enum):
    SUCCESS = "success"
    FAILED = "failed"
    SKIPPED = "skipped"


@dataclass(frozen=True)
class DocumentationConfiguration:
    commit: str
    config: str
    policy: int = POLICY

    @classmethod
    def from_dict(cls, value):
        # Missing policy means the artifact predates explicit visibility filtering.
        return cls(value["commit"], value["config"], value.get("policy", 0))


@dataclass(frozen=True)
class BuildAttempt:
    configuration: DocumentationConfiguration
    status: BuildStatus

    @classmethod
    def from_dict(cls, value):
        configuration = DocumentationConfiguration(
            value.get("commit", ""), value.get("config", ""), value.get("policy", 0))
        return cls(configuration, BuildStatus(value["status"]))

    def to_dict(self):
        return {**asdict(self.configuration),
                "status": self.status.value}


@dataclass
class VersionDocumentation:
    published: DocumentationConfiguration | None = None
    attempt: BuildAttempt | None = None

    @classmethod
    def from_dict(cls, value):
        return cls(
            DocumentationConfiguration.from_dict(value["published"]) if value.get("published") else None,
            BuildAttempt.from_dict(value["attempt"]) if value.get("attempt") else None,
        )

    def to_dict(self):
        result = {}
        if self.published:
            result["published"] = asdict(self.published)
        if self.attempt:
            result["attempt"] = self.attempt.to_dict()
        return result

    def copy_verified_archive(self, source, destination):
        if (self.published and self.published.policy == POLICY
                and (source / "index.html").is_file()
                and (source / "index.md").is_file()):
            shutil.copytree(source, destination)
            return self.published
        return None

    def should_build(self, name, desired, retry_failed):
        if (name != "main" and self.attempt
                and self.attempt.status is BuildStatus.FAILED and not retry_failed):
            return False
        return self.published != desired

    def message(self, name, attempted=False):
        if attempted:
            message = f"{name}: {self.attempt.status.value}"
        elif self.attempt and self.attempt.status is BuildStatus.FAILED:
            message = f"{name}: previous failure; not retried"
        else:
            message = f"{name}: reused"
        if self.attempt and self.attempt.status is BuildStatus.FAILED and self.published:
            message += " (serving previous successful build)"
        return message


@dataclass
class DocumentationManifest:
    versions: dict[str, VersionDocumentation] = field(default_factory=dict)

    @classmethod
    def read(cls, path):
        if not path.exists():
            return cls()
        document = json.loads(path.read_text())
        if document.get("schema") != 1:
            raise RuntimeError("Unknown archive manifest schema")
        return cls({name: VersionDocumentation.from_dict(value)
                    for name, value in document.get("versions", {}).items()})

    def write(self, path):
        document = {"schema": 1,
                    "versions": {name: version.to_dict()
                                 for name, version in self.versions.items()}}
        path.write_text(json.dumps(document, indent=2) + "\n")


def release_key(tag):
    match = TAG.fullmatch(tag)
    if not match:
        return None
    core = tuple(map(int, match.group(1, 2, 3)))
    pre = match.group(4)
    if core < (0, 1, 0) or (core == (0, 1, 0) and pre):
        return None
    if pre and any(part.isdigit() and len(part) > 1 and part[0] == '0'
                   for part in pre.split('.')):
        return None
    identifiers = tuple((0, int(part)) if part.isdigit() else (1, part)
                        for part in pre.split('.')) if pre else ()
    return core, pre is None, identifiers, tag


def git(repo, *args, data=None):
    return subprocess.check_output(["git", "-C", str(repo), *args], input=data)


def discover(repo):
    tags = git(repo, "tag", "--list").decode().splitlines()
    tags = sorted((tag for tag in tags if release_key(tag)),
                  key=release_key, reverse=True)
    return {name: git(repo, "rev-parse", f"{ref}^{{commit}}").decode().strip()
            for name, ref in [("main", "HEAD")] + [(tag, f"refs/tags/{tag}") for tag in tags]}


def load_archive(repo, archive):
    result = subprocess.run(["git", "-C", str(repo), "ls-remote", "--exit-code",
                             "origin", "refs/heads/gh-pages"], capture_output=True)
    if result.returncode == 2:
        return None, DocumentationManifest()
    if result.returncode:
        raise RuntimeError(result.stderr.decode())
    git(repo, "fetch", "--no-tags", "origin", "refs/heads/gh-pages")
    parent = git(repo, "rev-parse", "FETCH_HEAD").decode().strip()
    with tempfile.TemporaryFile() as packed:
        subprocess.run(["git", "-C", str(repo), "archive", parent], stdout=packed, check=True)
        packed.seek(0)
        with tarfile.open(fileobj=packed) as contents:
            contents.extractall(archive, filter="data")
    return parent, DocumentationManifest.read(archive / "manifest.json")


def build_version(repo, scripts, commit, name, destination, log):
    with tempfile.TemporaryDirectory(prefix="termlib-docs-build-") as temporary:
        checkout = Path(temporary) / "source"
        with log.open("w") as output:
            subprocess.run(["git", "clone", "--shared", "--no-checkout", str(repo), str(checkout)],
                           stdout=output, stderr=subprocess.STDOUT, check=True)
            subprocess.run(["git", "-C", str(checkout), "checkout", "--detach", commit],
                           stdout=output, stderr=subprocess.STDOUT, check=True)
            # A local SDK override is useful for developer verification, never archived.
            if (repo / "local.properties").exists():
                shutil.copy2(repo / "local.properties", checkout / "local.properties")
            subprocess.run([
                "./gradlew", ":lib:dokkaGeneratePublicationHtml", ":lib:dokkaGenerateMarkdown",
                "--no-configuration-cache", "--no-daemon", "--console=plain",
                "--init-script", str(scripts / "docs.init.gradle.kts"),
                f"-PdocsSourceCommit={commit}", f"-PdocsVersion={name}",
            ], cwd=checkout, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=1800)
        generated = checkout / "lib/build/dokka"
        if not (generated / "html/index.html").is_file() or not (generated / "markdown/index.md").is_file():
            raise RuntimeError("Dokka did not produce both HTML and Markdown entrypoints")
        shutil.copytree(generated / "markdown", destination)
        shutil.copytree(generated / "html", destination, dirs_exist_ok=True)


def reconcile(repo, scripts, archive, output, logs, previous, versions, config,
              retry_failed=False, builder=build_version):
    records = {}
    messages = []
    for name, commit in versions.items():
        old = previous.versions.get(name, VersionDocumentation())
        record = VersionDocumentation(attempt=old.attempt)
        record.published = old.copy_verified_archive(archive / name, output / name)
        desired = DocumentationConfiguration(commit, config)
        files = git(repo, "ls-tree", "--name-only", commit, "lib/build.gradle.kts").decode()
        build_file = git(repo, "show", f"{commit}:lib/build.gradle.kts").decode() if files.strip() else ""
        if not re.search(r"\bdokka\b", build_file):
            record.attempt = BuildAttempt(desired, BuildStatus.SKIPPED)
            messages.append(f"{name}: skipped (no Dokka configuration)")
        elif record.should_build(name, desired, retry_failed):
            print(f"Building documentation for {name} ({commit})", flush=True)
            status = BuildStatus.FAILED
            # Build separately so failures cannot damage the previous successful tree.
            with tempfile.TemporaryDirectory(prefix="termlib-docs-result-") as temporary:
                result = Path(temporary) / "result"
                log = logs / f"{name}.log"
                try:
                    builder(repo, scripts, commit, name, result, log)
                    if any(path.is_symlink() for path in result.rglob("*")):
                        raise RuntimeError("Dokka output contains symlinks")
                except (subprocess.SubprocessError, OSError, RuntimeError) as error:
                    with log.open("a") as failed_log:
                        failed_log.write(f"\nPublisher: {error}\n")
                else:
                    # Assembly errors are fatal: never publish a partially copied tree.
                    if (output / name).exists():
                        shutil.rmtree(output / name)
                    shutil.copytree(result, output / name)
                    record.published = desired
                    status = BuildStatus.SUCCESS
                record.attempt = BuildAttempt(desired, status)
            messages.append(record.message(name, attempted=True))
        else:
            messages.append(record.message(name))
        records[name] = record
    return DocumentationManifest(records), messages


def write_tree(repo, directory):
    """Store generated files without touching the developer's Git index."""
    entries = []
    for path in sorted(directory.iterdir()):
        if path.is_symlink():
            raise RuntimeError(f"Archive cannot contain symlinks: {path}")
        if path.is_dir():
            mode, kind, oid = "040000", "tree", write_tree(repo, path)
        else:
            mode, kind = "100644", "blob"
            oid = git(repo, "hash-object", "-w", str(path)).decode().strip()
        entries.append(f"{mode} {kind} {oid}\t{path.name}\0".encode())
    return git(repo, "mktree", "-z", data=b"".join(entries)).decode().strip()


def save_archive(repo, output, parent):
    tree = write_tree(repo, output)
    if parent and git(repo, "rev-parse", f"{parent}^{{tree}}").decode().strip() == tree:
        return
    args = ["-c", "user.name=github-actions[bot]", "-c",
            "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit-tree", tree]
    if parent:
        args.extend(["-p", parent])
    commit = git(repo, *args, data=b"Update versioned public API documentation\n").decode().strip()
    # An ordinary fast-forward push detects external writers instead of overwriting them.
    git(repo, "push", "origin", f"{commit}:refs/heads/gh-pages")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--logs", type=Path, required=True)
    parser.add_argument("--retry-failed", choices=["true", "false"], default="false")
    parser.add_argument("--publish", action="store_true", help="Push the generated archive to gh-pages")
    args = parser.parse_args()
    scripts = Path(__file__).resolve().parent
    repo = scripts.parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    args.logs.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    for path in [Path(__file__), scripts / "docs.init.gradle.kts"]:
        digest.update(path.read_bytes())
    versions = discover(repo)
    with tempfile.TemporaryDirectory(prefix="termlib-docs-archive-") as temporary:
        archive = Path(temporary)
        parent, previous = load_archive(repo, archive)
        manifest, messages = reconcile(repo, scripts, archive, output, args.logs, previous,
                                      versions, digest.hexdigest(), args.retry_failed == "true")
    manifest.write(output / "manifest.json")
    (output / ".nojekyll").touch()
    ready = generate_site(output, versions, BASE_URL)
    failures = sum(message.split(": ", 1)[-1].startswith("failed")
                   for message in messages)
    summary = "## Documentation versions\n\n" + "\n".join(f"- {message}" for message in messages) + "\n"
    if not ready:
        summary += "\nNo verified main documentation is available; the live site will be left unchanged.\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(summary)
    if args.publish:
        save_archive(repo, output, parent)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write(f"ready={str(ready).lower()}\nfailures={failures}\n")


if __name__ == "__main__":
    main()
