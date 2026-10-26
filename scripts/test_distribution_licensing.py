"""Behavioral tests for source/binary separation and fail-closed release materials."""

import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import distribution_legal as legal
import prepare_application_sources as sources
from prepare_media_sources import BundleError, ensure_sources
from prepare_release_assets import stage_assets, verify_uploaded


class DistributionLicensingTest(unittest.TestCase):
    def test_eula_does_not_import_polyform_obligations(self):
        eula, metadata = legal.agreement("eula")
        source, source_metadata = legal.agreement("polyform")
        self.assertIn("## Noncompete", source)
        self.assertNotIn("## Noncompete", eula)
        self.assertIn("Third-party components are not relicensed under this EULA", eula)
        self.assertIn("commercial and non-commercial purposes", eula)
        self.assertIn("not required to use the free functionality", eula)
        self.assertNotEqual(metadata["identity"], source_metadata["identity"])
        self.assertNotEqual(metadata["identity"], "2026-07-19")
        with self.assertRaises(ValueError):
            legal.agreement("unknown")

    def test_content_change_changes_acceptance_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copyfile(legal.ROOT / "EULA.md", root / "EULA.md")
            old = legal.agreement("eula", root)[1]
            with (root / "EULA.md").open("a") as stream:
                stream.write("\nAn amended term.\n")
            self.assertNotEqual(old["identity"], legal.agreement("eula", root)[1]["identity"])

    def test_website_is_generated_from_current_canonical_eula(self):
        legal.site(legal.ROOT, check=True)

    def fake_package(self, root, kind="polyform"):
        image = root / "image"
        (image / "legal").mkdir(parents=True)
        text, metadata = legal.agreement(kind)
        with zipfile.ZipFile(image / "app.jar", "w") as archive:
            archive.writestr("licenses/indagium-license-agreement.md", text)
            archive.writestr("licenses/distribution-license.json", json.dumps(metadata))
            archive.writestr("licenses/source-acquisition.json", json.dumps(legal.source_acquisition()))
            archive.writestr("licenses/" + metadata["document"], (legal.ROOT / metadata["document"]).read_bytes())
        (image / "legal/indagium-license-agreement.txt").write_text(text)
        (image / "legal/distribution-license.json").write_text(json.dumps(metadata))
        (image / "legal/source-acquisition.json").write_text(json.dumps(legal.source_acquisition()))
        return image

    def test_package_rejects_wrong_model_missing_readable_copy_and_corruption(self):
        with tempfile.TemporaryDirectory() as temporary:
            image = self.fake_package(Path(temporary))
            legal.validate_package(image, "polyform")
            with self.assertRaises(ValueError):
                legal.validate_package(image, "eula")
            readable = image / "legal/indagium-license-agreement.txt"
            readable.write_text("Incorrect agreement")
            with self.assertRaisesRegex(ValueError, "readable installed"):
                legal.validate_package(image, "polyform")
            readable.unlink()
            with self.assertRaises(ValueError):
                legal.validate_package(image, "polyform")

    def test_version_specific_source_links_are_required_in_the_installed_image(self):
        with tempfile.TemporaryDirectory() as temporary:
            image = self.fake_package(Path(temporary))
            legal.validate_package(image, "polyform")
            (image / "legal/source-acquisition.json").write_text("{}")
            with self.assertRaisesRegex(ValueError, "version-specific source"):
                legal.validate_package(image, "polyform")

    def test_missing_java_source_lock_entry_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copytree(legal.ROOT / "licenses/application", root / "licenses/application")
            shutil.copyfile(legal.ROOT / "gradle.lockfile", root / "gradle.lockfile")
            p = root / sources.LOCK
            lock = json.loads(p.read_text())
            lock["sourceArchives"] = [x for x in lock["sourceArchives"] if x["id"] != "english-pos-dict-source"]
            p.write_text(json.dumps(lock))
            with self.assertRaisesRegex(BundleError, "closure is incomplete"):
                sources.validate_lock(root)

    def test_corrupt_source_cache_cannot_be_published(self):
        item = sources.validate_lock()["sourceArchives"][0]
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            (cache / item["archive"]).write_bytes(b"corrupt")
            with self.assertRaises(BundleError):
                ensure_sources([item], cache, False)

    def test_runtime_notices_and_vendor_mapping_are_checked(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = self.fake_package(root, "eula")
            runtime = image / "runtime"
            (runtime / "lib").mkdir(parents=True)
            (runtime / "legal/java.base").mkdir(parents=True)
            (runtime / "release").write_text('JAVA_VERSION="21.0.9"\nMODULES="java.base"\n')
            (runtime / "lib/modules").write_bytes(b"synthetic module image")
            notice = runtime / "legal/java.base/LICENSE"
            notice.write_text("synthetic upstream notice")
            jdk = root / "jdk"; jdk.mkdir()
            (jdk / "release").write_text('IMPLEMENTOR="Eclipse Adoptium"\nJAVA_RUNTIME_VERSION="21.0.9+10-LTS"\n')
            sources.record_runtime(image, jdk, "macos-arm64", image / "legal/runtime-license-manifest.json")
            legal.validate_package(image, "eula")
            notice.write_text("changed")
            with self.assertRaisesRegex(BundleError, "notices differ"):
                legal.validate_package(image, "eula")
            (jdk / "release").write_text('IMPLEMENTOR="Other"\nJAVA_RUNTIME_VERSION="21.0.9+10-LTS"\n')
            with self.assertRaisesRegex(BundleError, "exact Temurin"):
                sources.record_runtime(image, jdk, "macos-arm64", image / "legal/runtime-license-manifest.json")

    def test_incomplete_release_is_not_staged(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(ValueError, "Incomplete release"):
                stage_assets(root, root / "staged", "1.8.99")

    def test_published_historical_release_is_never_modified(self):
        with patch("prepare_release_assets.api", return_value={"draft": False}):
            with self.assertRaisesRegex(ValueError, "historical release"):
                verify_uploaded("example/project", "123", Path("not-needed"))

    def test_complete_release_is_staged_and_source_corruption_is_rejected(self):
        import hashlib
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            version = "1.8.99"
            for suffix in (".dmg", ".msi", "-x86_64.AppImage", "-aarch64.AppImage", "-x86_64.flatpak", "-aarch64.flatpak"):
                (root / f"Indagium-{version}{suffix}").write_bytes(b"binary fixture")
            for arch in ("amd64", "arm64"):
                (root / f"indagium_{version}_{arch}.deb").write_bytes(b"binary fixture")
            for kind in ("media-sources", "application-sources"):
                name = f"Indagium-{version}-{kind}.tar.gz"
                (root / name).write_bytes(b"source fixture")
                digest = hashlib.sha256(b"source fixture").hexdigest()
                (root / (name + ".sha256")).write_text(f"{digest}  {name}\n")
                (root / (name + ".manifest.json")).write_text(json.dumps({"applicationVersion": version, "archive": {"sha256": digest}}))
            for name in ("EULA.md", "NOTICE", "THIRD_PARTY_NOTICES.md"):
                shutil.copyfile(legal.ROOT / name, root / name)
            (root / "dependency-report.txt").write_text("dependency fixture")
            result = stage_assets(root, root / "staged", version)
            self.assertIn("EULA.md", result)
            (root / f"Indagium-{version}-application-sources.tar.gz").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                stage_assets(root, root / "second-stage", version)


if __name__ == "__main__":
    unittest.main()
