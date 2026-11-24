"""M1 live acceptance, isolated accounts/profile/ports; no Docker management."""
import argparse
import json
import socket
import sys

from validate import Validation, need

AUTH = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"


class M1Validation(Validation):
    def seed(self):
        self.big_path = self.out / "m1-seed.txt"
        self.big_path.write_bytes(b"M1 isolated session acceptance")
        self.big_file = self.api.upload(self.owner, self.big_path.name, self.big_path.read_bytes())["id"]
        return {"fixture": True}

    def sessions(self):
        self.login()
        need(self.page.invoke("desktop:diagnostics")["authenticated"], "NATIVE_NOT_AUTHENTICATED")
        self.session_refresh()
        self.page.wait("!!localStorage.getItem('accessToken')")
        # Await a real authenticated native operation, not only web token storage.
        self.page.invoke("get_transfer_queue")
        need(self.page.invoke("desktop:diagnostics")["authenticated"], "REFRESH_NOT_SYNCED")
        return {"login": True, "reloadRestore": True, "refresh": True, "nativeDownloadMd5": True}

    def switch(self):
        print("m1: logout", flush=True)
        self.page.evaluate(AUTH + ".logout()")
        self.page.wait("!!document.querySelector('.login-page__form')")
        need(not self.page.invoke("desktop:diagnostics")["authenticated"], "NATIVE_IDENTITY_RETAINED")
        # An alternative spelling addresses the same isolated test backend.
        target = self.api.base.replace("127.0.0.1", "localhost")
        if target == self.api.base:
            target = self.api.base.replace("localhost", "127.0.0.1")
        need(target != self.api.base, "M1_REQUIRES_LOOPBACK_BACKEND")
        self.page.click('button[aria-controls="desktop-connection-settings"]')
        print("m1: connection form", flush=True)
        def fill(value):
            self.page.evaluate("(() => { const e=document.querySelector('#desktop-connection-settings input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e," + json.dumps(value) + ");e.dispatchEvent(new Event('input',{bubbles:true}));return true})()")
        fill("file:///invalid")
        self.page.click('#desktop-connection-settings button[type="submit"]')
        need(self.page.evaluate("!!document.querySelector('#desktop-connection-settings')"), "FAILED_FORM_CLOSED")
        fill(target)
        self.page.click('#desktop-connection-settings button[type="submit"]')
        self.page.wait("!document.querySelector('#desktop-connection-settings')")
        print("m1: connection acknowledged", flush=True)
        need(self.page.evaluate("localStorage.getItem('bcd.apiBaseUrl')") == target, "CONNECTION_NOT_COMMITTED")
        need(not self.page.evaluate("localStorage.getItem('accessToken')||localStorage.getItem('refreshToken')"), "OLD_TOKEN_RESTORED")
        self.page.evaluate(AUTH + ".login(" + json.dumps(self.owner["username"]) + "," + json.dumps(self.owner["password"]) + ")")
        print("m1: new server login", flush=True)
        need(self.page.invoke("desktop:diagnostics")["authenticated"], "NEW_SERVER_LOGIN_FAILED")
        self.page.evaluate("window.__m1ReloadMarker=true")
        self.page.command("Page.reload")
        self.page.wait("!window.__m1ReloadMarker && !!document.querySelector('#app')?.__vue_app__")
        print("m1: saved connection bootstrapped", flush=True)
        self.page.evaluate(AUTH + ".initializeSession()")
        need(self.page.invoke("desktop:diagnostics")["authenticated"], "SAVED_SERVER_RESTORE_FAILED")
        self.page.evaluate(AUTH + ".logout()")
        need(not self.page.invoke("desktop:diagnostics")["authenticated"], "FINAL_LOGOUT_FAILED")
        return {"invalidInputRetained": True, "nativeAcknowledgement": True, "noOldCredentials": True, "newServerLogin": True, "savedConnectionReload": True, "logout": True}

    def exit_check(self):
        self.close_app()
        for port in (self.port, self.debug_port):
            with socket.socket() as probe:
                need(probe.connect_ex(("127.0.0.1", port)) != 0, "APPLICATION_PORT_RETAINED")
        return {"processExited": True, "portsReleased": True}

    def run(self):
        failed = False
        try:
            for name, operation in [("isolated-backend-fixtures", self.setup), ("m1-fixture", self.seed),
                    ("real-qt-vue-launch", self.launch), ("login-reload-refresh", self.sessions),
                    ("connection-and-logout", self.switch), ("bridge-security", self.security),
                    ("application-exit", self.exit_check)]:
                self.step(name, operation)
        except Exception:
            failed = True
        finally:
            if self.process:
                try:
                    self.close_app()
                except Exception:
                    failed = True
            try:
                self.step("run-scoped-cleanup", self.cleanup)
            except Exception:
                failed = True
            report = json.dumps({"runId": self.id, "offscreen": self.offscreen, "checks": self.checks}, indent=2)
            need(not any(value and len(str(value)) > 8 and str(value) in report for value in self.secrets), "REPORT_PRIVACY_CHECK_FAILED")
            (self.out / "results.json").write_text(report, encoding="utf-8")
            print("report", self.out / "results.json", flush=True)
        return int(failed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", default="http://127.0.0.1:8080/api/v1")
    parser.add_argument("--port", type=int, default=5194)
    parser.add_argument("--debug-port", type=int, default=9226)
    parser.add_argument("--offscreen", action="store_true")
    args = parser.parse_args()
    sys.exit(M1Validation(args.api_base, False, port=args.port, debug_port=args.debug_port, offscreen=args.offscreen).run())
