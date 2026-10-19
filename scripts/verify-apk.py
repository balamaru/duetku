"""Verify the actual signed APK, not just the source manifest (stdlib only)."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("--build-tools", required=True, type=pathlib.Path)
parser.add_argument("--out", required=True, type=pathlib.Path)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)


def run(tool, *arguments):
    result = subprocess.run(
        [str(args.build_tools / tool), *map(str, arguments)],
        capture_output=True, text=True, check=True,
    )
    return result.stdout


signature = run("apksigner.bat", "verify", "--verbose", "--print-certs", args.apk)
manifest = run("aapt.exe", "dump", "xmltree", args.apk, "AndroidManifest.xml")
badging = run("aapt.exe", "dump", "badging", args.apk)
resources = run("aapt2.exe", "dump", "resources", args.apk)
rules_id = re.search(r"android:dataExtractionRules[^\n]*=@(0x[0-9a-f]+)", manifest)
assert rules_id, "Missing data extraction rules reference"
# Release resource shrinking renames files: resolve the actual manifest reference.
rules_file = re.search(
    rf"resource {rules_id.group(1)} [^\n]+\n\s+\(\) \(file\) (\S+)", resources
)
assert rules_file, "Cannot resolve backup rules from APK resource table"
rules = run("aapt.exe", "dump", "xmltree", args.apk, rules_file.group(1))
permissions = re.findall(r'android.permission.[A-Z_]+', manifest)
assert set(permissions) == {"android.permission.USE_BIOMETRIC", "android.permission.INTERNET"}, "Unexpected APK permission"
assert re.search(r"android:usesCleartextTraffic.*=\(type 0x12\)0x0\b", manifest), "Cleartext must be disabled"
for attribute in ("allowBackup", "fullBackupContent", "hasFragileUserData"):
    assert re.search(rf"android:{attribute}.*=\(type 0x12\)0x0\b", manifest), attribute
assert not re.search(r"android:debuggable.*=\(type 0x12\)0xffffffff", manifest), "Debuggable APK"
assert "android:dataExtractionRules" in manifest
assert "E: cloud-backup" in rules and "E: device-transfer" in rules
for section in ("cloud-backup", "device-transfer"):
    body = re.search(rf"^  E: {section}[^\n]*\n(.*?)(?=^  E: |\Z)", rules, re.S | re.M)
    assert body, f"Missing {section} rules"
    for domain in ("root", "file", "database", "sharedpref", "external"):
        assert f'"{domain}"' in body.group(1), f"Missing {section} exclusion: {domain}"
    assert body.group(1).count('path="."') == 5, f"Incomplete {section} exclusion paths"
with zipfile.ZipFile(args.apk) as apk:
    abis = sorted({n.split("/")[1] for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")})
assert abis == ["arm64-v8a"], abis
run("zipalign.exe", "-c", "-P", "16", "4", args.apk)
report = {
    "apk": args.apk.name,
    "bytes": args.apk.stat().st_size,
    "MiB": round(args.apk.stat().st_size / 1024**2, 3),
    "sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest(),
    "abis": abis,
    "checks": "signature, zip alignment, biometric + explicitly approved INTERNET only, cleartext disabled, non-debuggable, backup disabled, no retained uninstall data",
    "device_tests": "NOT RUN by this script",
}
for name, content in (("manifest.txt", manifest), ("signature.txt", signature), ("badging.txt", badging), ("data-extraction-rules.txt", rules)):
    (args.out / name).write_text(content, encoding="utf-8")
(args.out / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
