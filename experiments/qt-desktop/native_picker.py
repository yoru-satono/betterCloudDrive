"""Interactive native-picker check; use the actual Windows file dialog.

The agent/user chooses the printed synthetic file. No test-only picker bypass.
"""
import argparse
import json

from engine import digest
from live import wait_for
from validate import Run, need

args = argparse.Namespace(packaged=None, office_only=False, previews_only=False)
run = Run(args)
try:
    need(run.step("backend-and-accounts", run.setup), "SETUP_FAILED")
    run.api.upload(run.owner, "seed_" + run.id + ".txt", b"Native picker seed")
    need(run.step("qt-real-vue-and-bridge", run.launch), "LAUNCH_FAILED")
    need(run.step("ui-login", run.login), "LOGIN_FAILED")
    source = run.fixtures / ("native-picker-" + run.id + ".txt")
    source.write_text("Synthetic Windows QFileDialog validation " + run.id, encoding="utf-8")
    request = {"apiBaseUrl": run.api.base, "parentId": None, "token": run.owner["accessToken"], "resumableUploads": [], "resumableDirectories": []}
    print("Choose synthetic fixture: " + str(source), flush=True)
    run.page.evaluate("window.__BCD_QT_POC__.invoke('upload_desktop_files', {request:" + json.dumps(request) + "}).then(result=>window.nativePickerResult=result);true")
    def verify():
        wait_for(lambda: run.page.evaluate("!!window.nativePickerResult"), 180, interval=1)
        need(run.page.evaluate("window.nativePickerResult.selected===true"), "PICKER_CANCELLED")
        def completed():
            tasks = run.page.invoke("get_transfer_queue")["tasks"]
            return next((task for task in tasks if task["name"] == source.name and task["status"] in ("done", "instant")), None)
        task = wait_for(completed, 30, interval=1)
        identity = task["nodes"][0]["remoteFileId"]
        run.api.files.append((run.owner, None, identity))
        file = run.api.call("GET", "/files/" + str(identity), run.owner)
        need(file["md5Hash"] == digest(source), "PICKER_UPLOAD_CHECKSUM")
        run.page.screenshot(run.out / "native-picker-completed.png")
        return {"actualWindowsDialog": True, "selectedSyntheticFile": True, "uploadedAndMd5Matched": True}
    run.step("native-file-picker-upload", verify)
    run.step("logout", run.logout)
finally:
    run.close_app()
    if run.engine:
        run.engine.close()
    failures = run.api.cleanup()
    run.results.append({"name": "run-scoped-cleanup", "status": "failed" if failures else "passed", "errors": failures})
    run.save()
    print("Results: " + str(run.out / "results.json"), flush=True)
