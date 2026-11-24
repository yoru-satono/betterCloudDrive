"""Interactive Windows checks; requires selecting only printed synthetic fixtures."""
import json
import argparse
import sys
from pathlib import Path

from validate import Validation, digest, need
from live_support import wait_for


def choose(run, command, expected):
    run.page.evaluate("""window.nativeSelection=null;(() => {
        const id=crypto.randomUUID();
        const listener=e=>{const r=JSON.parse(e.detail);if(r.id!==id)return;
          window.removeEventListener('bcd:response',listener);window.nativeSelection=r;};
        window.addEventListener('bcd:response',listener);
        window.dispatchEvent(new CustomEvent('bcd:request',{detail:JSON.stringify({
          id,command:""" + json.dumps(command) + """,args:{request:""" + json.dumps(run.request()) + """}})}));
    })();true""")
    wait_for(lambda: run.page.evaluate("!!window.nativeSelection"), 240, interval=0.3)
    result = run.page.evaluate("window.nativeSelection")
    need(not result["error"], "NATIVE_PICKER_" + str(result["error"]))
    need(result["result"]["selected"], "NATIVE_SELECTION_CANCELED")
    task_id = result["result"]["batchId"]
    def completed():
        tasks = run.page.invoke("get_transfer_queue")["tasks"]
        return next((t for t in tasks if t["id"] == task_id and t["status"] in ("done", "instant")), None)
    task = wait_for(completed, 40)
    matched = set()
    for node in task["nodes"]:
        if node["kind"] == "file" and node["name"] in expected:
            file = run.api.call("GET", "/files/" + str(node["remoteFileId"]), run.owner)
            need(file["md5Hash"] == digest(expected[node["name"]]), "PICKER_UPLOAD_CHECKSUM")
            matched.add(node["name"])
    need(matched == set(expected), "PICKER_SELECTED_FILES_MISMATCH")
    roots = task["nodes"][1:] if len(expected) > 1 else task["nodes"][:1]
    for node in roots:
        run.api.files.append((run.owner, None, node["remoteFileId"]))
    return {"actualWindowsDialog": True, "uploadedAndMd5Matched": True, "files": len(expected)}


def cancel(run, command):
    run.page.evaluate("""window.nativeSelection=null;(() => {
        const id=crypto.randomUUID();
        const listener=e=>{const r=JSON.parse(e.detail);if(r.id!==id)return;
          window.removeEventListener('bcd:response',listener);window.nativeSelection=r;};
        window.addEventListener('bcd:response',listener);
        window.dispatchEvent(new CustomEvent('bcd:request',{detail:JSON.stringify({
          id,command:""" + json.dumps(command) + """,args:{request:""" + json.dumps(run.request()) + """}})}));
    })();true""")
    wait_for(lambda: run.page.evaluate("!!window.nativeSelection"), 120, interval=0.3)
    result = run.page.evaluate("window.nativeSelection")
    need(not result["error"] and result["result"] == {"selected": False}, "PICKER_CANCEL_FAILED")
    return {"actualWindowsDialog": True, "noSelection": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--drag-only", action="store_true")
    parser.add_argument("--m1", action="store_true", help="Also check cancellation/close and wait for the explicit local visual-acceptance marker")
    parser.add_argument("--window-only", action="store_true", help="Verify window gestures and visual acceptance without repeating picker checks")
    parser.add_argument("--pickers-only", action="store_true", help="Verify native file/folder selection without repeating window gestures")
    args = parser.parse_args()
    run = Validation("http://127.0.0.1:8080/api/v1", False)
    failed = False
    try:
        run.step("isolated-backend-fixtures", run.setup)
        run.big_path = run.out / "seed.bin"
        run.big_path.write_bytes(b"native dialog validation")
        run.big_file = run.api.upload(run.owner, "seed.bin", run.big_path.read_bytes())["id"]
        run.step("launch", run.launch)
        run.step("login", run.login)
        # A native Windows modal dialog can suspend page CDP processing.
        # This is an explicit interactive check, not a background retry loop.
        run.page.ws.settimeout(300)
        a, b = run.out / "native-a.txt", run.out / "native-b.txt"
        a.write_text("synthetic A")
        b.write_text("synthetic B")
        if not args.drag_only and not args.window_only:
            print("SELECT_FILES", json.dumps([str(a), str(b)]), flush=True)
            run.step("native-multifile-picker", lambda: choose(run, "upload_desktop_files", {a.name: a, b.name: b}))
        if not args.pickers_only:
            before = run.page.invoke("desktop:diagnostics")["window"]
            print("DRAG_WINDOW_NOW", flush=True)
            def moved():
                after = run.page.invoke("desktop:diagnostics")["window"]
                return abs(after["x"] - before["x"]) + abs(after["y"] - before["y"]) > 10
            run.step("native-titlebar-drag", lambda: {"windowMoved": bool(wait_for(moved, 240, interval=0.3))})
        if not args.drag_only and not args.window_only:
            folder = run.out / "native-folder"
            (folder / "empty").mkdir(parents=True)
            child = folder / "file.txt"
            child.write_text("synthetic folder")
            print("SELECT_FOLDER", folder, flush=True)
            run.step("native-folder-picker", lambda: choose(run, "upload_desktop_folder", {child.name: child}))
        if args.m1 or args.window_only:
            for name, command in (() if args.window_only else (("native-file-cancel", "upload_desktop_files"),
                                  ("native-file-window-close", "upload_desktop_files"),
                                  ("native-folder-cancel", "upload_desktop_folder"),
                                  ("native-folder-window-close", "upload_desktop_folder"))):
                print(name.upper(), flush=True)
                run.step(name, lambda: cancel(run, command))
            before_state = run.page.invoke("desktop:diagnostics")
            before_clicks = before_state["window"]
            print("CLICK_AND_FOCUS_NOW", run.out / "visual-accepted", flush=True)
            wait_for(lambda: (run.out / "visual-accepted").is_file(), 120)
            after_state = run.page.invoke("desktop:diagnostics")
            after_clicks = after_state["window"]
            need(before_clicks == after_clicks, "CLICK_MOVED_WINDOW")
            presses = after_state["dragPresses"] - before_state["dragPresses"]
            need(presses >= 3, "TITLEBAR_CLICKS_NOT_OBSERVED")
            run.step("native-click-and-focus-visual", lambda: {"titlebarAndBodyClicks": True, "jitter": True,
                "firstClickAfterFocus": True, "windowPositionUnchanged": True, "nativeTitlebarPresses": presses, "visualAcceptance": "manual"})
        run.step("logout", run.logout_and_switch)
        run.step("exit", run.close_app)
    except Exception as error:
        failed = True
        print("NATIVE_CHECK_FAILED", type(error).__name__, flush=True)
    finally:
        if run.process:
            try:
                run.close_app()
            except Exception:
                failed = True
        try:
            run.step("run-scoped-cleanup", run.cleanup)
        except Exception:
            failed = True
        (run.out / "results.json").write_text(json.dumps({"runId": run.id, "checks": run.checks}, indent=2))
        print("report", run.out / "results.json", flush=True)
    sys.exit(int(failed))
