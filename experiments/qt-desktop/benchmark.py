"""Five independent, isolated-profile starts per runtime; no login credentials."""
import argparse
import json
import os
import statistics
import subprocess
import sys
import time
import uuid
from pathlib import Path

import psutil

from cdp import Cdp

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def size(root):
    return sum(p.stat().st_size for p in root.rglob("*") if p.is_file())


def rss(pid):
    parent = psutil.Process(pid)
    total = 0
    for process in [parent, *parent.children(recursive=True)]:
        try:
            total += process.memory_info().rss
        except psutil.Error:
            pass
    return total


def stop(process):
    try:
        parent = psutil.Process(process.pid)
        children = parent.children(recursive=True)
        for child in reversed(children):
            try:
                child.terminate()
            except psutil.Error:
                pass
        process.terminate()
        process.wait(timeout=10)
    except (psutil.Error, subprocess.TimeoutExpired):
        if process.poll() is None:
            process.kill()


def run(binary, runtime, out, index):
    directory = out / f"{runtime}-{index}"
    directory.mkdir()
    env = {**os.environ, "PYTHONUTF8": "1"}
    command = [str(binary)]
    if runtime == "qt":
        command += ["--profile", str(directory / "profile"), "--port", "5185", "--debug-port", "9225"]
    else:
        env["WEBVIEW2_USER_DATA_FOLDER"] = str(directory / "webview")
        env["WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"] = "--remote-debugging-port=9225 --remote-debugging-address=127.0.0.1"
    with (directory / "process.log").open("w", encoding="utf-8") as log:
        start = time.monotonic()
        process = subprocess.Popen(command, cwd=directory, env=env, stdout=log, stderr=log)
        page = None
        try:
            page = Cdp(9225)
            page.wait("!!document.querySelector('.login-page__form input')")
            elapsed = time.monotonic() - start
            if runtime == "tauri":
                # The environment override must actually be honored; otherwise
                # abort before inspecting any user state.
                paths = [p.cmdline() for p in psutil.Process(process.pid).children(recursive=True)]
                if not any(str(directory / "webview").lower() in " ".join(parts).lower() for parts in paths):
                    raise RuntimeError("TAURI_PROFILE_ISOLATION_UNCONFIRMED")
            samples = []
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                samples.append(rss(process.pid))
                time.sleep(0.2)
            return {"startupSeconds": round(elapsed, 3), "idleRssMedianBytes": int(statistics.median(samples)),
                    "idleRssPeakBytes": max(samples), "runtime": runtime,
                    "userAgent": page.evaluate("navigator.userAgent")}
        finally:
            if page:
                try:
                    if runtime == "qt":
                        page.evaluate("window.__BCD_QT_POC__.invoke('window:close');true")
                    else:
                        page.evaluate("window.__TAURI_INTERNALS__.invoke('plugin:window|close',{label:'main'});true")
                    process.wait(timeout=5)
                except Exception:
                    stop(process)
                page.close()
            else:
                stop(process)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=5)
    args = parser.parse_args()
    out = REPO / "test-results" / "qt-desktop" / ("benchmark-" + uuid.uuid4().hex[:10])
    out.mkdir(parents=True)
    qt = HERE / "dist" / "BetterCloudDriveQtPoc" / "BetterCloudDriveQtPoc.exe"
    tauri = REPO / "frontend" / "src-tauri" / "target" / "release" / "betterclouddrive.exe"
    result = {"method": "same host; fresh profiles; OS cache not flushed; sequential independent starts; 10 second idle sampling",
              "qtDirectoryBytes": size(qt.parent), "tauriExecutableBytes": tauri.stat().st_size,
              "tauriSharedWebView2RuntimeExcluded": True, "samples": [], "errors": []}
    for index in range(args.samples):
        for runtime, binary in (("tauri", tauri), ("qt", qt)):
            try:
                value = run(binary, runtime, out, index)
                result["samples"].append(value)
                print(runtime + " sample " + str(index + 1) + " passed", flush=True)
            except Exception as error:
                result["errors"].append({"runtime": runtime, "sample": index + 1, "error": type(error).__name__})
                print(runtime + " sample " + str(index + 1) + " failed", flush=True)
            (out / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("Results: " + str(out / "results.json"), flush=True)
    sys.exit(bool(result["errors"]))
