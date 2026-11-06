#!/usr/bin/env python3
"""Measure shallow sizes of the actual test candidates using a temporary Instrumentation agent."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
JAVA_SOURCE = r'''
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import sun.misc.Unsafe;
public class LayoutProbe {
    private static Instrumentation instrumentation;
    public static void premain(String args, Instrumentation inst) { instrumentation = inst; }
    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        for (String name : args) {
            Object value = unsafe.allocateInstance(Class.forName(name));
            System.out.println(name + " " + instrumentation.getObjectSize(value));
        }
        System.out.println("java.lang.String " + instrumentation.getObjectSize("12:34:56.789"));
        System.out.println("byte[12] " + instrumentation.getObjectSize(new byte[12]));
        System.out.println("java.lang.Long " + instrumentation.getObjectSize(Long.valueOf(1000L)));
    }
}
'''


def main():
    java_home = Path(os.environ["JAVA_HOME"])
    java = str(java_home / "bin" / "java")
    classpath = json.loads((ROOT / "build/ram-poc/classpath.json").read_text())["test"]
    with tempfile.TemporaryDirectory(prefix="ram-layout-") as temp:
        tmp = Path(temp)
        (tmp / "LayoutProbe.java").write_text(JAVA_SOURCE)
        (tmp / "MANIFEST.MF").write_text("Premain-Class: LayoutProbe\n\n")
        subprocess.run([str(java_home / "bin/javac"), str(tmp / "LayoutProbe.java")], check=True)
        subprocess.run([str(java_home / "bin/jar"), "cfm", str(tmp / "probe.jar"),
                        str(tmp / "MANIFEST.MF"), "-C", temp, "LayoutProbe.class"], check=True)
        subprocess.run([java, "-version"], check=True)
        flags = subprocess.check_output([java, "-XX:+PrintFlagsFinal", "-version"], stderr=subprocess.STDOUT, text=True)
        for line in flags.splitlines():
            if any(flag in line for flag in ("UseCompressedOops", "UseCompressedClassPointers", "CompactStrings", "ObjectAlignmentInBytes")):
                print(line.strip(), flush=True)
        subprocess.run([java, "-javaagent:" + str(tmp / "probe.jar"), "-cp", os.pathsep.join([temp] + classpath),
                        "LayoutProbe", "com.indagium.model.LogEntry",
                        "com.indagium.ram.compact.MetadataCandidate",
                        "com.indagium.ram.compact.OptionalMetadata",
                        "com.indagium.ram.compact.TimestampCandidate"], check=True)


if __name__ == "__main__":
    main()
