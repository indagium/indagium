#!/usr/bin/env python3
"""Smoke-test LGPL replacement in a temporary COPY; never modifies an installed app."""

import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def replace_jar(path: Path, overrides: dict[str, bytes]) -> None:
    temporary = path.with_suffix(".replacement")
    with zipfile.ZipFile(path) as original, zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as changed:
        for info in original.infolist():
            # A modified JAR must not carry signatures for the old contents.
            if info.filename.upper().startswith("META-INF/") and info.filename.upper().endswith((".SF", ".RSA", ".DSA", ".EC")):
                continue
            changed.writestr(info.filename, overrides.pop(info.filename, original.read(info)))
        for name, data in overrides.items():
            changed.writestr(name, data)
    temporary.replace(path)


def verify(image: Path, jdk: Path, source_cache: Path) -> None:
    from prepare_application_sources import validate_lock
    from prepare_media_sources import ensure_sources
    source = next(i for i in validate_lock()["sourceArchives"] if i["id"] == "edu.washington.cs.knowitall:openregex:1.1.1:source")
    ensure_sources([source], source_cache, True)
    with tempfile.TemporaryDirectory(prefix="indagium-lgpl-replacement-") as temporary:
        root = Path(temporary)
        copied = root / "app"
        shutil.copytree(image, copied, symlinks=True)
        jars = list(copied.rglob("*.jar"))
        openregex = next(p for p in jars if p.name.startswith("openregex-"))
        language = next(p for p in jars if p.name.startswith("languagetool-core-"))
        classifier = next(p for p in jars if p.name.startswith("ffmpeg-") and any(s in p.name for s in ("macosx-", "linux-", "windows-")))
        classpath = str(openregex.parent / "*")
        javac = jdk / "bin" / ("javac.exe" if sys.platform == "win32" else "javac")
        java = jdk / "bin" / ("java.exe" if sys.platform == "win32" else "java")
        # Recompile the actual OpenRegex source against the app's dependency interfaces.
        sources = root / "openregex-src"; sources.mkdir()
        with zipfile.ZipFile(source_cache / "openregex-1.1.1-sources.jar") as archive:
            for name in archive.namelist():
                if name.endswith(".java") and ".." not in Path(name).parts:
                    p = sources / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(archive.read(name))
        classes = root / "openregex-classes"; classes.mkdir()
        subprocess.run([str(javac), "--release", "8", "-proc:none", "-cp", classpath, "-d", str(classes), *map(str, sources.rglob("*.java"))], check=True)
        replace_jar(openregex, {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")})
        replace_jar(language, {"indagium-lgpl-test-marker.txt": b"compatible test-only modified LanguageTool JAR\n"})
        # Modify only the informational configure-prefix string in the shared library. This
        # changes native bytes without changing its ABI or executable instructions.
        with zipfile.ZipFile(classifier) as archive:
            native = next(n for n in archive.namelist() if "avcodec" in n and "jni" not in n and n.endswith((".dylib", ".so.62", ".dll")))
            data = archive.read(native)
        if b"--prefix=.." not in data:
            raise RuntimeError("Native configure marker was not found; no binary was changed")
        modified = data.replace(b"--prefix=..", b"--prefix=./")
        if sys.platform == "darwin":
            library = root / "modified-libavcodec.dylib"; library.write_bytes(modified)
            subprocess.run(["codesign", "--force", "--sign", "-", str(library)], check=True)
            modified = library.read_bytes()
        replace_jar(classifier, {native: modified})
        probe = root / "ReplacementProbe.java"
        probe.write_text('''
import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.bytedeco.ffmpeg.global.avcodec;
public final class ReplacementProbe {
    public static void main(String[] args) throws Exception {
        if (JLanguageTool.class.getResourceAsStream("/indagium-lgpl-test-marker.txt") == null)
            throw new AssertionError("Modified LanguageTool JAR was not loaded");
        JLanguageTool tool = new JLanguageTool(Languages.getLanguageForShortCode("en-US"));
        if (tool.check("This are an error.").isEmpty()) throw new AssertionError("Proofreading failed");
        if (!avcodec.avcodec_configuration().getString().contains("--prefix=./"))
            throw new AssertionError("Modified native FFmpeg library was not loaded");
        System.out.println("Replacement passed: rebuilt OpenRegex, modified LanguageTool and modified FFmpeg shared library.");
    }
}
''')
        subprocess.run([str(javac), "-cp", classpath, str(probe)], check=True)
        separator = ";" if sys.platform == "win32" else ":"
        subprocess.run([str(java), "-Djava.awt.headless=true", "-Duser.home=" + str(root / "home"),
                        "-Dorg.bytedeco.javacpp.cachedir=" + str(root / "native-cache"),
                        "-cp", str(root) + separator + classpath, "ReplacementProbe"], check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--jdk-home", type=Path, required=True)
    parser.add_argument("--source-cache", type=Path, default=Path("build/application-source-cache"))
    args = parser.parse_args()
    verify(args.image, args.jdk_home, args.source_cache)
