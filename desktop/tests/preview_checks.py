"""Real Vue/OnlyOffice checks for the desktop acceptance harness."""
import base64
import hashlib
import json
from pathlib import Path
from live_support import document, pdf_document, env_values, jwt, wait_for
from network import DesktopError
REPO = Path(__file__).resolve().parents[2]
def need(condition, code):
    if not condition:
        raise DesktopError(code)
class PreviewChecks:
    def preview(self):
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nS8AAAAASUVORK5CYII=")
        image = self.api.upload(self.owner, "image_" + self.id + ".png", png)
        text = self.api.upload(self.owner, "preview_" + self.id + ".txt", b"Qt preview fixture")
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        for file in (image, text):
            self.page.evaluate(store + ".openPreview(" + json.dumps(file) + ")")
            selector = "[role=dialog] img" if file is image else "[role=dialog] iframe"
            self.page.wait("!!document.querySelector(" + json.dumps(selector) + ")", 20)
            if file is image:
                self.page.wait("document.querySelector('[role=dialog] img').naturalWidth>0")
            else:
                self.page.wait("document.querySelector('[role=dialog] iframe').contentDocument?.body.innerText.includes('Qt preview fixture')")
            self.page.evaluate(store + ".closePreview()")
        return {"image": True, "text": True}

    def office(self):
        file = self.api.upload(self.owner, "office_" + self.id + ".docx", document(), self.team)
        self.office_file = file
        before = file["versionCount"]
        config = self.api.call("GET", f"/teams/{self.team}/office/files/{file['id']}/config", self.owner)
        need(config["editable"], "OFFICE_NOT_EDITABLE")
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        script_url = config["documentServerUrl"].rstrip("/") + "/web-apps/apps/api/documents/api.js"
        self.page.evaluate("""new Promise((resolve,reject)=>{
          const script=document.createElement('script'); script.src=""" + json.dumps(script_url) + """;
          script.dataset.onlyofficeApi=script.src;
          script.onload=()=>{script.dataset.loaded='true';
            const Original=window.DocsAPI.DocEditor;
            window.DocsAPI.DocEditor=function(id,config){
              const ready=config.events?.onDocumentReady;
              config.events={...config.events,onDocumentReady:(event)=>{window.qtOfficeReady=true;ready?.(event)},
                onDocumentStateChange:event=>{window.qtOfficeModified=!!event.data;window.qtOfficeWasModified=window.qtOfficeWasModified||!!event.data},
                onError:event=>{window.qtOfficeError=event.data?.errorCode}};
              return new Original(id,config);
            }; resolve(true);
          };script.onerror=()=>reject(new Error('OFFICE_SCRIPT_FAILED'));document.head.append(script);
        })""")
        self.page.evaluate(store + ".openPreview(" + json.dumps(file) + "," + json.dumps({"teamId": self.team}) + ")")
        self.page.wait("!!document.querySelector('[data-testid=onlyoffice-shell] iframe')", 40)
        self.page.wait("window.qtOfficeReady===true", 90)
        # Enter the actual document iframe through CDP's execution context.
        self.page.command("Page.enable")
        tree = self.page.command("Page.getFrameTree")["frameTree"]
        frames = []
        def walk(item):
            frames.append(item["frame"])
            for child in item.get("childFrames", []):
                walk(child)
        walk(tree)
        frame = next((f for f in frames if "/web-apps/apps/documenteditor/" in f["url"]), None)
        need(frame is not None, "OFFICE_FRAME_NOT_LOADED")
        # Wait for the editor to advertise its loaded state, rather than assuming
        # an iframe alone proves document rendering or a save callback.
        context = self.page.command("Page.createIsolatedWorld", {"frameId": frame["id"], "worldName": "qt-validation"})["executionContextId"]
        def editor_ready():
            value = self.page.command("Runtime.evaluate", {"contextId": context,
                "expression": "!!document.querySelector('#editor_sdk canvas')", "returnByValue": True})
            return value.get("result", {}).get("value")
        wait_for(editor_ready, 60)
        self.page.command("Runtime.evaluate", {"contextId": context, "expression": "Array.from(document.querySelectorAll('button,[role=button]')).filter(e=>/^(Got it|知道了|OK)$/i.test(e.innerText.trim())).forEach(e=>e.click())"})
        self.page.invoke("desktop:focus")
        self.page.screenshot(self.out / "office.png")
        value = self.page.evaluate("(() => {const r=document.querySelector('[data-testid=onlyoffice-shell] iframe').getBoundingClientRect();return {x:r.x+r.width*.5,y:r.y+r.height*.5}})()")
        for kind in ("mousePressed", "mouseReleased"):
            self.page.command("Input.dispatchMouseEvent", {"type": kind, "button": "left", "clickCount": 1, **value})
        marker = "QT_SAVE_" + self.id
        self.page.command("Input.dispatchKeyEvent", {"type": "keyDown", "key": "End", "code": "End", "modifiers": 2, "windowsVirtualKeyCode": 35})
        self.page.command("Input.dispatchKeyEvent", {"type": "keyUp", "key": "End", "code": "End", "modifiers": 2, "windowsVirtualKeyCode": 35})
        for char in marker:
            self.page.command("Input.dispatchKeyEvent", {"type": "keyDown", "key": char, "text": char, "unmodifiedText": char})
            self.page.command("Input.dispatchKeyEvent", {"type": "keyUp", "key": char})
        self.page.wait("window.qtOfficeWasModified===true", 15)
        self.page.screenshot(self.out / "office-edited.png")
        self.page.command("Input.dispatchKeyEvent", {"type": "keyDown", "key": "s", "code": "KeyS", "modifiers": 2, "windowsVirtualKeyCode": 83})
        self.page.command("Input.dispatchKeyEvent", {"type": "keyUp", "key": "s", "code": "KeyS", "modifiers": 2, "windowsVirtualKeyCode": 83})
        self.page.wait("window.qtOfficeWasModified===true && window.qtOfficeModified===false", 20)
        key = config["config"]["document"]["key"]
        payload = {"c": "forcesave", "key": key}
        secret = env_values(REPO)["ONLYOFFICE_JWT_SECRET"].encode()
        payload["token"] = jwt(payload, secret)
        response = self.api.client.post("http://127.0.0.1:8082/coauthoring/CommandService.ashx", json=payload).json()
        self.metrics["officeForceSaveCode"] = response.get("error")
        need(response.get("error") in (0, 4), "OFFICE_FORCE_SAVE_" + str(response.get("error")))
        def saved():
            current = self.api.call("GET", f"/teams/{self.team}/files/{file['id']}", self.owner)
            if current.get("versionCount", 0) <= before:
                return False
            download = self.api.client.get(self.api.base + f"/teams/{self.team}/download/{file['id']}", headers={"Authorization": "Bearer " + self.owner["accessToken"]})
            import io, zipfile, xml.etree.ElementTree as ET
            with zipfile.ZipFile(io.BytesIO(download.content)) as archive:
                text = "".join(ET.fromstring(archive.read("word/document.xml")).itertext())
                return marker in text
        wait_for(saved, 90, interval=3)
        self.page.evaluate(store + ".closePreview()")
        return {"editorRendered": True, "realCallback": True, "versionCreated": True, "markerSaved": True,
                "forceSaveResponse": response.get("error")}

    def readonly_office(self):
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        personal = self.api.upload(self.owner, "personal_" + self.id + ".docx", document())
        config = self.api.call("GET", f"/office/files/{personal['id']}/config", self.owner)
        need(not config["editable"] and config["config"]["editorConfig"]["mode"] == "view", "PERSONAL_OFFICE_NOT_READONLY")
        self.page.evaluate("window.qtOfficeReady=false;" + store + ".openPreview(" + json.dumps(personal) + ")")
        self.page.wait("window.qtOfficeReady===true", 60)
        self.page.screenshot(self.out / "personal-office-readonly.png")
        self.page.evaluate(store + ".closePreview()")
        file = self.office_file
        config = self.api.call("GET", f"/teams/{self.team}/office/files/{file['id']}/config", self.viewer)
        need(not config["editable"] and config["config"]["editorConfig"]["mode"] == "view", "TEAM_VIEWER_NOT_READONLY")
        auth = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
        self.page.evaluate(auth + ".login(" + json.dumps(self.viewer["username"]) + "," + json.dumps(self.viewer["password"]) + ")")
        self.page.evaluate("window.qtOfficeReady=false;" + store + ".openPreview(" + json.dumps(file) + "," + json.dumps({"teamId": self.team}) + ")")
        self.page.wait("window.qtOfficeReady===true", 60)
        self.page.screenshot(self.out / "team-office-readonly.png")
        self.page.evaluate(store + ".closePreview()")
        self.page.evaluate(auth + ".login(" + json.dumps(self.owner["username"]) + "," + json.dumps(self.owner["password"]) + ")")
        for key in ("accessToken", "refreshToken"):
            self.owner[key] = self.page.evaluate("localStorage.getItem(" + json.dumps(key) + ")")
            self.secrets.append(self.owner[key])
        return {"personalReadOnlyRendered": True, "teamViewerReadOnlyRendered": True}

    def pdf(self):
        from rendering import pdf_page_has_ink, crop_css
        file = self.api.upload(self.owner, "preview_" + self.id + ".pdf", pdf_document())
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        self.page.evaluate(store + ".openPreview(" + json.dumps(file) + ")")
        self.page.wait("!!document.querySelector('[role=dialog] iframe')", 20)
        previous_capture = None
        def rendered():
            nonlocal previous_capture
            tree = self.page.command("Page.getFrameTree")["frameTree"]
            frames = []
            def walk(item):
                frames.append(item["frame"])
                for child in item.get("childFrames", []):
                    walk(child)
            walk(tree)
            frame = next((f for f in frames if f.get("mimeType") == "application/pdf"), None)
            if not frame:
                return False
            rect = self.page.evaluate("(() => {const r=document.querySelector('[role=dialog] iframe').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,scale:1}})()")
            capture = self.page.command("Page.captureScreenshot", {"format": "png"})
            png = crop_css(base64.b64decode(capture["data"]), rect, self.page.evaluate("innerWidth"))
            checksum = hashlib.sha256(png).hexdigest()
            stable = checksum == previous_capture
            previous_capture = checksum
            if stable and pdf_page_has_ink(png):
                (self.out / "pdf-content.png").write_bytes(png)
                return True
            return False
        wait_for(rendered, 20, interval=1)
        self.page.screenshot(self.out / "pdf.png")
        self.page.evaluate(store + ".closePreview()")
        # Chromium's PDF plugin does not expose page text through this page's
        # CDP AX tree. Keep the visual assertion explicit instead of claiming it.
        return {"realBackendPdf": True, "embeddedPdfFrameLoaded": True, "visiblePageAndInk": True,
                "screenshotForVisualReview": "pdf-content.png", "stableConsecutiveFrames": True}
