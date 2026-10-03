#!/usr/bin/env python3
"""Lists the attack surface of an Android app for a security review.

- exported manifest components (merged manifest if built, else src/main), with permission and
  intent actions; components reachable without any permission are marked "OPEN"
- manifest components whose class exists neither in the sources (incl. build/generated) nor,
  if given, in the APK (such an exported entry lets any app crash the process)
- receivers registered at runtime as exported (RECEIVER_EXPORTED, or no flag)
- shell command strings that interpolate variables (Shizuku `sh -c` / `su` sinks)
- hard-coded http(s) endpoints

Usage: attack-surface.py [repo-root] [--apk path/to/app.apk]
Read-only. Output is a starting point: every hit still has to be read in context.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
SYSTEM_PERMISSIONS = ("android.permission.BIND_", "android.permission.INTERACT_ACROSS_USERS")
SHELL_WORDS = r"(pm|am|cmd|settings|appops|dumpsys|svc|setprop|wm|input|service call|monkey|content|device_config|cat|rm|cp|mv|chmod|kill|logcat)"


def find_manifest(root):
    for pattern in ("app/build/intermediates/merged_manifests/release/**/AndroidManifest.xml",
                    "app/build/intermediates/merged_manifests/debug/**/AndroidManifest.xml",
                    "app/src/main/AndroidManifest.xml"):
        hits = glob.glob(os.path.join(root, pattern), recursive=True)
        if hits:
            return hits[0]
    sys.exit("No AndroidManifest.xml found")


def namespace(root):
    """AGP 7+: the source manifest has no package attribute, the namespace is in Gradle."""
    for name in ("app/build.gradle.kts", "app/build.gradle"):
        path = os.path.join(root, name)
        if os.path.exists(path):
            match = re.search(r"""namespace\s*=?\s*["']([\w.]+)["']""", open(path, encoding="utf-8").read())
            if match:
                return match.group(1)
    return ""


def source_files(root, exts=(".kt", ".java")):
    for base in glob.glob(os.path.join(root, "*/src/main")):
        for dirpath, _, files in os.walk(base, followlinks=True):
            for name in files:
                if name.endswith(exts):
                    yield os.path.join(dirpath, name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--apk")
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    manifest = find_manifest(root)
    tree = ET.parse(manifest).getroot()
    package = tree.get("package") or namespace(root)
    app = tree.find("application")
    sources = {path: open(path, encoding="utf-8", errors="replace").read() for path in source_files(root)}
    all_source = "\n".join(sources.values())
    # Classes generated at build time (KSP/kapt) count as existing for the class check
    for path in glob.glob(os.path.join(root, "*/build/generated/**/*.*"), recursive=True):
        if path.endswith((".kt", ".java")):
            all_source += "\n" + open(path, encoding="utf-8", errors="replace").read()
    dex_text = ""
    if args.apk:
        listing = subprocess.run(["unzip", "-l", args.apk], capture_output=True, text=True).stdout
        for dex in re.findall(r"(classes\d*\.dex)", listing):
            dex_text += subprocess.run(["unzip", "-p", args.apk, dex], capture_output=True).stdout.decode("latin-1")

    print(f"# Manifest: {os.path.relpath(manifest, root)}  (package {package or '?'})\n")
    print("## Exported components")
    for comp in app:
        if comp.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
            continue
        name = comp.get(A + "name") or ""
        filters = comp.findall("intent-filter")
        exported = comp.get(A + "exported")
        if exported == "false" or (exported is None and not filters):
            continue
        perm = comp.get(A + "permission") or comp.get(A + "readPermission") or ""
        actions = sorted({a.get(A + "name") for f in filters for a in f.findall("action")})
        status = "system-bound" if perm.startswith(SYSTEM_PERMISSIONS) else ("OPEN" if not perm else "perm")
        print(f"- [{status}] {comp.tag} {name}" + (f"  permission={perm}" if perm else "") +
              (f"  actions={', '.join(actions)}" if actions else ""))

    print("\n## Custom permissions (protectionLevel)")
    for perm in tree.findall("permission"):
        print(f"- {perm.get(A + 'name')}: {perm.get(A + 'protectionLevel') or 'normal'}")

    print("\n## Manifest components without a class")
    for comp in app:
        name = comp.get(A + "name") or ""
        if comp.tag not in ("activity", "service", "receiver", "provider") or not name:
            continue
        fqcn = (package + name) if name.startswith(".") else name
        simple = fqcn.split(".")[-1].split("$")[-1]
        in_sources = re.search(r"\b(class|object)\s+" + re.escape(simple) + r"\b", all_source)
        in_dex = dex_text and ("L" + fqcn.replace(".", "/") + ";") in dex_text
        if not in_sources and not in_dex and fqcn.startswith(package or "\0"):
            print(f"- {fqcn}")
        elif not in_sources and not in_dex and args.apk:
            print(f"- {fqcn} (not in APK)")

    print("\n## Runtime receivers registered as exported")
    for path, text in sources.items():
        for i, line in enumerate(text.splitlines(), 1):
            if "RECEIVER_EXPORTED" in line:
                print(f"- {os.path.relpath(path, root)}:{i}: {line.strip()[:140]}")

    print("\n## Shell strings with interpolated values")
    pattern = re.compile(r'"' + SHELL_WORDS + r' [^"]*\$')
    for path, text in sources.items():
        for i, line in enumerate(text.splitlines(), 1):
            if pattern.search(line) and "quote(" not in line:
                print(f"- {os.path.relpath(path, root)}:{i}: {line.strip()[:140]}")

    print("\n## Hard-coded endpoints")
    hosts = {}
    for path, text in sources.items():
        for host in re.findall(r"https?://([A-Za-z0-9.-]+)", text):
            hosts.setdefault(host, set()).add(os.path.relpath(path, root))
    for host in sorted(hosts):
        files = sorted(hosts[host])
        print(f"- {host}: {', '.join(files[:3])}{' …' if len(files) > 3 else ''}")


if __name__ == "__main__":
    main()
