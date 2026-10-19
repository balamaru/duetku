"""Measure isolated spike APKs on a connected Android phone; never touches app data."""
import argparse
import json
import pathlib
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--native-apk", required=True)
    parser.add_argument("--go-apk", required=True)
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--output", default="artifacts/spike-measurements.json")
    args = parser.parse_args()
    if args.runs < 3:
        parser.error("Use at least three runs")

    def adb(*arguments):
        return subprocess.run([args.adb, "-s", args.serial, *arguments], check=True,
                              capture_output=True, text=True, timeout=90).stdout.strip()

    abi = adb("shell", "getprop", "ro.product.cpu.abilist")
    sdk = int(adb("shell", "getprop", "ro.build.version.sdk"))
    if "arm64-v8a" not in abi or sdk < 33:
        raise SystemExit("Requires arm64-v8a and Android API >= 33")
    result = {"model": adb("shell", "getprop", "ro.product.model"), "sdk": sdk,
              "abi": abi, "airplane_mode": adb("shell", "settings", "get", "global", "airplane_mode_on"),
              "metrics": "am start TotalTime = initial display; excludes full data readiness. No cold-start target verdict.",
              "variants": {}}
    for flavor, filename in (("native", args.native_apk), ("withgo", args.go_apk)):
        path = pathlib.Path(filename).resolve(strict=True)
        package = "id.kelolauangku.spike." + flavor
        adb("install", "-r", str(path))
        # Warm first-install initialization, then force-stop before each measured launch.
        adb("shell", "am", "start", "-W", "-n", package + "/id.kelolauangku.spike.SpikeActivity")
        time.sleep(2)
        samples = []
        for _ in range(args.runs):
            adb("shell", "am", "force-stop", package)
            output = adb("shell", "am", "start", "-W", "-n", package + "/id.kelolauangku.spike.SpikeActivity")
            values = dict(line.split(":", 1) for line in output.splitlines() if ":" in line)
            if values.get("Status", "").strip() != "ok" or "TotalTime" not in values:
                raise RuntimeError("Launch measurement failed: " + output)
            samples.append(int(values["TotalTime"].strip()))
            time.sleep(1)
        result["variants"][flavor] = {"apk_bytes": path.stat().st_size,
            "initial_display_ms": samples, "median_initial_display_ms": statistics.median(samples)}
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("Measurements written to", target)


if __name__ == "__main__":
    main()
