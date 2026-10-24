#!/usr/bin/env python3
"""Fail-closed unit tests for the native media source bundle contract."""

from __future__ import annotations

import copy
import json
import os
import re
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import prepare_media_sources as media


class MediaSourceValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.inventory = media.read_json(media.INVENTORY_PATH)
        cls.lock = media.read_json(media.LOCK_PATH)
        cls.runtime_versions = dict(media.SUPPORTED_RUNTIME_VERSIONS)

    def test_missing_full_license_text_fails_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            legal_root = Path(temporary) / "third-party"
            media.shutil.copytree(media.LEGAL_ROOT, legal_root)
            (legal_root / "texts" / "ffmpeg-lgpl-3.0.txt").unlink()
            with self.assertRaisesRegex(media.BundleError, "Required licence or notice is missing"):
                media.validate_inventory(self.inventory, legal_root)

    def test_changed_full_license_text_hash_fails_validation(self) -> None:
        changed = copy.deepcopy(self.inventory)
        changed["licenseTextHashes"]["texts/ffmpeg-lgpl-3.0.txt"] = "0" * 64
        with self.assertRaisesRegex(media.BundleError, "Licence/notice checksum mismatch"):
            media.validate_inventory(changed)

    def test_removing_a_required_native_source_fails_closed(self) -> None:
        modified = copy.deepcopy(self.lock)
        modified["sourceArchives"] = [item for item in modified["sourceArchives"] if item["id"] != "svt-av1"]
        with self.assertRaisesRegex(media.BundleError, "Source closure mismatch"):
            media.validate_lock(modified, "1.8.15", self.runtime_versions, self.inventory)

    def test_unknown_runtime_version_is_rejected(self) -> None:
        changed = dict(self.runtime_versions, ffmpeg="8.0.2-1.5.13")
        with self.assertRaisesRegex(media.BundleError, "Unsupported runtime version"):
            media.validate_lock(self.lock, "1.8.15", changed, self.inventory)

    def test_corrupt_cached_source_archive_is_rejected(self) -> None:
        source = self.lock["sourceArchives"][0]
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            (cache / source["archive"]).write_bytes(b"not the pinned archive")
            with self.assertRaisesRegex(media.BundleError, "Cached source checksum mismatch"):
                media.ensure_sources([source], cache, fetch_missing=False)

    def test_gitlab_macos_classifier_lookup_uses_custom_gradle_user_home(self) -> None:
        pipeline = (media.REPO_ROOT / ".gitlab-ci.yml").read_text(encoding="utf-8")
        lookup_lines = [
            line.strip()
            for line in pipeline.splitlines()
            if line.strip().startswith(("gradle_user_home=", "ffmpeg_jar=$(find ", "zstd_jar=$(find "))
        ]
        self.assertEqual(
            len(lookup_lines), 3,
            "expected the macOS classifier lookup to define its cache and both JAR paths",
        )
        self.assertIn("${GRADLE_USER_HOME:-$HOME/.gradle}", lookup_lines[0])
        self.assertTrue(all('find "$gradle_user_home/caches/' in line for line in lookup_lines[1:]))

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            custom_gradle_home = root / "custom gradle home"
            relative_jars = (
                (
                    "org.bytedeco/ffmpeg/8.0.1-1.5.13",
                    "ffmpeg-8.0.1-1.5.13-macosx-arm64.jar",
                ),
                (
                    "com.github.luben/zstd-jni/1.5.7-4",
                    "zstd-jni-1.5.7-4-darwin_aarch64.jar",
                ),
            )
            for relative_directory, filename in relative_jars:
                jar = (
                    custom_gradle_home / "caches" / "modules-2" / "files-2.1" /
                    relative_directory / "test" / filename
                )
                jar.parent.mkdir(parents=True)
                jar.write_bytes(b"fixture")

            shell = "\n".join(lookup_lines + ['printf "%s\\n" "$ffmpeg_jar" "$zstd_jar"'])
            environment = dict(os.environ)
            environment["GRADLE_USER_HOME"] = str(custom_gradle_home)
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", shell],
                check=True,
                capture_output=True,
                text=True,
                env=environment,
            )
            selected = result.stdout.splitlines()
            self.assertEqual(len(selected), 2)
            self.assertTrue(all(path.startswith(str(custom_gradle_home) + os.sep) for path in selected))

    @staticmethod
    def _write_synthetic_package(
        root: Path, *, omitted_resource: str | None = None, modified_resource: str | None = None,
    ) -> tuple[Path, Path, dict[str, object], dict[str, object]]:
        repo_root = root / "repo"
        package_root = root / "package"
        third_party = repo_root / "licenses" / "third-party"
        application = repo_root / "licenses" / "application"
        for directory in (third_party / "texts", application / "texts", package_root):
            directory.mkdir(parents=True, exist_ok=True)

        source_files = {
            "licenses/third-party/media-components.json": third_party / "media-components.json",
            "licenses/third-party/media-source-lock.json": third_party / "media-source-lock.json",
            "licenses/third-party/SOURCE-BUNDLE.md": third_party / "SOURCE-BUNDLE.md",
            "licenses/THIRD_PARTY_NOTICES.md": repo_root / "THIRD_PARTY_NOTICES.md",
            "licenses/NOTICE": repo_root / "NOTICE",
            "licenses/third-party/texts/shared.txt": third_party / "texts" / "shared.txt",
            "licenses/application/texts/app-license.txt": application / "texts" / "app-license.txt",
            "licenses/languagetool-LGPL-2.1.txt": (
                repo_root / "src" / "desktopMain" / "resources" / "licenses" / "languagetool-LGPL-2.1.txt"
            ),
        }
        for resource, path in source_files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"synthetic text for {resource}\n", encoding="utf-8")

        app_inventory = {
            "schemaVersion": 1,
            "components": [
                {"id": "shared-text-user", "licenseFiles": ["third-party/texts/shared.txt"]},
                {
                    "id": "application-texts",
                    "licenseFiles": [
                        "third-party/texts/shared.txt",
                        "application/texts/app-license.txt",
                        "languagetool-LGPL-2.1.txt",
                    ],
                },
            ],
        }
        app_inventory_path = application / "application-components.json"
        app_inventory_path.write_text(json.dumps(app_inventory, indent=2) + "\n", encoding="utf-8")

        resources = {resource: path.read_bytes() for resource, path in source_files.items()}
        resources["licenses/application/application-components.json"] = app_inventory_path.read_bytes()
        if omitted_resource is not None:
            resources.pop(omitted_resource)
        if modified_resource is not None:
            resources[modified_resource] = resources[modified_resource] + b" altered in package\n"

        inventory: dict[str, object] = {
            "licenseTextHashes": {"texts/shared.txt": media.sha256_file(third_party / "texts" / "shared.txt")},
        }
        binary_artifacts = [
            {"id": "ffmpeg-linux-x86_64", "archive": "ffmpeg-linux-x86_64.jar", "sha256": ""},
            {"id": "zstd-jni-linux-x64", "archive": "zstd-jni-linux-x64.jar", "sha256": ""},
        ]
        lock: dict[str, object] = {"binaryArtifacts": binary_artifacts}
        ffmpeg_jar = package_root / "ffmpeg-linux-x86_64.jar"
        zstd_jar = package_root / "zstd-jni-linux-x64.jar"
        with zipfile.ZipFile(ffmpeg_jar, "w") as archive:
            for resource, contents in resources.items():
                archive.writestr(resource, contents)
        with zipfile.ZipFile(zstd_jar, "w") as archive:
            archive.writestr("fixture/marker.txt", b"classifier fixture")
        binary_artifacts[0]["sha256"] = media.sha256_file(ffmpeg_jar)
        binary_artifacts[1]["sha256"] = media.sha256_file(zstd_jar)
        return repo_root, package_root, inventory, lock

    def _validate_synthetic_package(
        self, root: Path, *, omitted_resource: str | None = None, modified_resource: str | None = None,
    ) -> None:
        repo_root, package_root, inventory, lock = self._write_synthetic_package(
            root, omitted_resource=omitted_resource, modified_resource=modified_resource,
        )
        media.validate_packaged_resources(package_root, "linux-x64", inventory, lock, repo_root=repo_root)

    def test_complete_synthetic_package_includes_application_and_reused_media_licences(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            self._validate_synthetic_package(Path(temporary))

    def test_packaged_application_inventory_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(media.BundleError, "licenses/application/application-components.json"):
                self._validate_synthetic_package(
                    Path(temporary), omitted_resource="licenses/application/application-components.json",
                )

    def test_packaged_referenced_application_texts_are_required(self) -> None:
        missing_texts = (
            "licenses/application/texts/app-license.txt",
            "licenses/languagetool-LGPL-2.1.txt",
        )
        for missing_text in missing_texts:
            with self.subTest(missing_text=missing_text), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaisesRegex(media.BundleError, re.escape(missing_text)):
                    self._validate_synthetic_package(Path(temporary), omitted_resource=missing_text)

    def test_modified_packaged_application_text_fails_checksum_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(media.BundleError, "Packaged legal resource checksum mismatch"):
                self._validate_synthetic_package(
                    Path(temporary), modified_resource="licenses/application/texts/app-license.txt",
                )

    def test_wrong_downloaded_source_checksum_is_rejected(self) -> None:
        source = self.lock["sourceArchives"][0]
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)

            def write_corrupt_download(command: list[str], check: bool) -> None:
                Path(command[-1]).write_bytes(b"wrong downloaded bytes")

            with patch.object(media.subprocess, "run", side_effect=write_corrupt_download):
                with self.assertRaisesRegex(media.BundleError, "Downloaded source checksum mismatch"):
                    media.ensure_sources([source], cache, fetch_missing=True)
            self.assertFalse((cache / source["archive"]).exists())

    def test_license_provenance_cannot_drop_a_source_member(self) -> None:
        changed = copy.deepcopy(self.inventory)
        changed["legalTextProvenance"] = {"texts/ffmpeg-lgpl-3.0.txt": dict(changed["legalTextProvenance"]["texts/ffmpeg-lgpl-3.0.txt"], sourceId="ffmpeg", memberPath="FFmpeg-COPYING-NOT-THERE")}
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "source.tar.gz"
            import tarfile
            with tarfile.open(archive, "w:gz"):
                pass
            source = next(item for item in self.lock["sourceArchives"] if item["id"] == "ffmpeg")
            source = dict(source, archive=archive.name)
            with self.assertRaisesRegex(media.BundleError, "does not contain the legal text provenance member"):
                media.validate_legal_provenance_members(changed, [(archive, source)])

    @staticmethod
    def _macho64(linkedit_vmsize: int, flags: int = 0) -> bytes:
        header = bytearray(32)
        header[:4] = b"\xcf\xfa\xed\xfe"
        header[16:20] = (1).to_bytes(4, "little")
        header[20:24] = (72).to_bytes(4, "little")
        segment = bytearray(72)
        segment[0:4] = (0x19).to_bytes(4, "little")
        segment[4:8] = (72).to_bytes(4, "little")
        segment[8:24] = b"__LINKEDIT\0\0\0\0\0\0"
        segment[32:40] = linkedit_vmsize.to_bytes(8, "little")
        segment[68:72] = flags.to_bytes(4, "little")
        return bytes(header + segment)

    def test_macos_linkedit_normalization_changes_only_vmsize(self) -> None:
        upstream = self._macho64(0x1000)
        resigned = self._macho64(0x2000)
        self.assertEqual(media.normalize_macos_linkedit_vmsize(upstream, resigned), upstream)

    def test_macos_linkedit_normalization_rejects_code_mutation(self) -> None:
        upstream = self._macho64(0x1000)
        resigned_with_mutation = self._macho64(0x2000, flags=1)
        with self.assertRaisesRegex(media.BundleError, "differs outside __LINKEDIT vmsize"):
            media.normalize_macos_linkedit_vmsize(upstream, resigned_with_mutation)

    def test_macos_linkedit_normalization_rejects_out_of_bounds_command(self) -> None:
        malformed = bytearray(self._macho64(0x1000))
        malformed[36:40] = (0xFFFF).to_bytes(4, "little")
        with self.assertRaisesRegex(media.BundleError, "invalid bounds"):
            media.normalize_macos_linkedit_vmsize(self._macho64(0x1000), bytes(malformed))

    def test_macos_classifier_package_allows_only_the_linkedit_size_difference(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original_jar = root / "original.jar"
            packaged_jar = root / "packaged.jar"
            with zipfile.ZipFile(original_jar, "w") as archive:
                archive.writestr("lib/libcodec.dylib", self._macho64(0x1000))
                archive.writestr("META-INF/legal.txt", b"unchanged legal text")
            with zipfile.ZipFile(packaged_jar, "w") as archive:
                archive.writestr("lib/libcodec.dylib", self._macho64(0x2000))
                archive.writestr("META-INF/legal.txt", b"unchanged legal text")
            with patch.object(media, "_remove_macos_code_signature", side_effect=lambda data, _name: data):
                media._validate_macos_classifier_jars(packaged_jar, original_jar)

    def test_macos_classifier_package_rejects_other_entry_mutations(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original_jar = root / "original.jar"
            packaged_jar = root / "packaged.jar"
            with zipfile.ZipFile(original_jar, "w") as archive:
                archive.writestr("lib/libcodec.dylib", self._macho64(0x1000))
                archive.writestr("META-INF/legal.txt", b"original")
            with zipfile.ZipFile(packaged_jar, "w") as archive:
                archive.writestr("lib/libcodec.dylib", self._macho64(0x1000))
                archive.writestr("META-INF/legal.txt", b"mutated")
            with patch.object(media, "_remove_macos_code_signature", side_effect=lambda data, _name: data):
                with self.assertRaisesRegex(media.BundleError, "entry changed unexpectedly"):
                    media._validate_macos_classifier_jars(packaged_jar, original_jar)


if __name__ == "__main__":
    unittest.main(verbosity=2)
