"""Small page-target CDP driver: Qt does not implement Browser contexts.

Uses Chromium's supported page protocol rather than patching Playwright to hide
its unsupported Browser.setDownloadBehavior command. No protocol traffic logged.
"""
import base64
import json
import time
from pathlib import Path

import httpx
import websocket


class Cdp:
    def __init__(self, port=9224, timeout=30):
        end = time.monotonic() + timeout
        while True:
            try:
                targets = httpx.get(f"http://127.0.0.1:{port}/json", timeout=1, trust_env=False).json()
                target = next(t for t in targets if t["type"] == "page")
                self.ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=timeout, suppress_origin=True)
                break
            except (httpx.HTTPError, StopIteration, ConnectionError, websocket.WebSocketException):
                if time.monotonic() >= end:
                    raise RuntimeError("CDP_UNAVAILABLE") from None
                time.sleep(0.1)
        self.identity = 0

    def command(self, method, params=None, session=None):
        self.identity += 1
        identity = self.identity
        request = {"id": identity, "method": method, "params": params or {}}
        if session:
            request["sessionId"] = session
        self.ws.send(json.dumps(request))
        while True:
            value = json.loads(self.ws.recv())
            if value.get("id") == identity:
                if "error" in value:
                    raise RuntimeError("CDP_" + method.replace(".", "_"))
                return value.get("result", {})

    def evaluate(self, expression):
        value = self.command("Runtime.evaluate", {"expression": expression, "awaitPromise": True, "returnByValue": True, "userGesture": True})
        if "exceptionDetails" in value:
            raise RuntimeError("PAGE_EVALUATION_FAILED")
        return value.get("result", {}).get("value")

    def wait(self, expression, timeout=30):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.evaluate("Boolean(" + expression + ")"):
                return
            time.sleep(0.1)
        raise RuntimeError("PAGE_CONDITION_TIMEOUT")

    def invoke(self, command, args=None):
        return self.evaluate("window.__BCD_QT_POC__.invoke(" + json.dumps(command) + "," + json.dumps(args or {}) + ")")

    def click(self, selector):
        selector = json.dumps(selector)
        bounds = self.evaluate(f"(() => {{ const e=document.querySelector({selector}); if(!e)return null; const r=e.getBoundingClientRect(); return {{x:r.x+r.width/2,y:r.y+r.height/2}} }})()")
        if not bounds:
            raise RuntimeError("ELEMENT_NOT_FOUND")
        self.command("Input.dispatchMouseEvent", {"type": "mousePressed", "button": "left", "clickCount": 1, **bounds})
        self.command("Input.dispatchMouseEvent", {"type": "mouseReleased", "button": "left", "clickCount": 1, **bounds})

    def screenshot(self, path):
        value = self.command("Page.captureScreenshot", {"format": "png"})
        Path(path).write_bytes(base64.b64decode(value["data"]))

    def close(self):
        self.ws.close()
