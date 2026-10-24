#!/usr/bin/env python3
"""Fail-closed unit tests for the native media source bundle contract."""

from __future__ import annotations

import copy
import json
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
