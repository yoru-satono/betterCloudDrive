"""Real API fixtures. Credentials are never serialized or printed."""
import base64
import hashlib
import hmac
import io
import json
import re
import secrets
import time
import zipfile
from pathlib import Path

import httpx

from engine import Engine, PocError


def wait_for(predicate, timeout=60, interval=0.1):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise PocError("CONDITION_TIMEOUT")


def env_values(repo):
    values = {}
    for name in (".env", "config/env-files/security.env", "config/env-files/backend.env"):
        file = repo / name
        if file.exists():
            for line in file.read_text(encoding="utf-8-sig").splitlines():
                if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip().strip("\"'")
    return values


def jwt(payload, key, algorithm="HS256"):
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
    body = encode(json.dumps({"alg": algorithm, "typ": "JWT"}).encode()) + "." + encode(json.dumps(payload).encode())
    return body + "." + encode(hmac.new(key, body.encode(), {"HS256": hashlib.sha256, "HS384": hashlib.sha384, "HS512": hashlib.sha512}[algorithm]).digest())


def expired_token(token, values):
    header, payload, _ = token.split(".")
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    algorithm = json.loads(base64.urlsafe_b64decode(header + "=" * (-len(header) % 4)))["alg"]
    claims.update(iat=int(time.time()) - 3600, exp=int(time.time()) - 60)
    return jwt(claims, base64.b64decode(values["JWT_ACCESS_SECRET"]), algorithm)


class Api:
    def __init__(self, run_id, base="http://127.0.0.1:8080/api/v1"):
        self.base = base
        self.run_id = run_id
        self.users, self.teams, self.files, self.sessions = [], [], [], []
        self.client = httpx.Client(timeout=60, trust_env=False)

    def call(self, method, path, user=None, **kwargs):
        headers = {"Authorization": "Bearer " + user["accessToken"]} if user else {}
        return Engine.envelope(self.client.request(method, self.base + path, headers=headers, **kwargs))

    def user(self, suffix):
        name = f"e2e_qt_{self.run_id}_{suffix}"
        email = name + "@test.local"
        password = secrets.token_urlsafe(24) + "aA1!"
        self.call("POST", "/auth/register-code/send", json={"email": email, "preferredLocale": "en-US"})
        def find_code():
            messages = self.client.get("http://127.0.0.1:8025/api/v1/messages").json().get("messages", [])
            for message in messages:
                if any(to.get("Address") == email for to in message.get("To", [])):
                    detail = self.client.get("http://127.0.0.1:8025/api/v1/message/" + message["ID"]).json()
                    code = re.search(r"\b\d{6}\b", detail.get("Text", ""))
                    if code:
                        return code.group(0)
            return None
        code = wait_for(find_code, 20)
        self.call("POST", "/auth/register", json={"username": name, "password": password, "email": email,
                  "verificationCode": code, "preferredLocale": "en-US"})
        user = {**self.call("POST", "/auth/login", json={"username": name, "password": password}),
                "username": name, "password": password}
        me = self.call("GET", "/auth/me", user)
        user["id"] = me["id"]
        self.users.append(user)
        return user

    def upload(self, user, name, content, team=None):
        base = f"/teams/{team}" if team else ""
        result = self.call("POST", base + "/upload/init", user, json={
            "parentId": None, "fileName": name, "fileSize": len(content), "md5Hash": hashlib.md5(content).hexdigest()})
        if result["mode"] == "MULTIPART":
            session = result["sessionId"]
            self.sessions.append((user, base, session))
            for index in range(result["totalChunks"]):
                chunk = content[index * result["chunkSize"]:(index + 1) * result["chunkSize"]]
                self.call("POST", f"{base}/upload/{session}/chunk", user,
                          params={"chunkNumber": index, "chunkMd5": hashlib.md5(chunk).hexdigest()},
                          files={"file": ("part", chunk)})
            result = self.call("POST", f"{base}/upload/{session}/complete", user)
        file_id = result["fileId"]
        self.files.append((user, team, file_id))
        return self.call("GET", f"{base}/files/{file_id}", user)

    def cleanup(self):
        failures = []
        # UI logout deliberately revokes its token. Cleanup needs a separate,
        # short-lived login, which is also logged out at the end.
        for user in self.users:
            try:
                user.update(self.call("POST", "/auth/login", json={"username": user["username"], "password": user["password"]}))
            except Exception:
                failures.append("CLEANUP_LOGIN_FAILED")
        # Only IDs allocated in this run are touched. No broad test-data SQL.
        for user, base, session in self.sessions:
            try:
                self.call("POST", f"{base}/upload/{session}/cancel", user)
            except PocError:
                pass  # Completed sessions do not need cancellation.
        for user, team, identity in reversed(self.files):
            if team:
                continue  # Team deletion owns its file lifecycle.
            try:
                self.call("DELETE", "/files", user, json={"fileIds": [identity]})
                self.call("DELETE", f"/recycle-bin/{identity}", user)
            except Exception:
                failures.append("PERSONAL_FILE_CLEANUP_FAILED")
        for user, identity in reversed(self.teams):
            try:
                self.call("DELETE", f"/teams/{identity}", user)
            except Exception:
                failures.append("TEAM_CLEANUP_FAILED")
        for user in self.users:
            try:
                self.call("POST", "/auth/logout", user)
            except Exception:
                failures.append("LOGOUT_CLEANUP_FAILED")
        self.client.close()
        return failures


def document():
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        archive.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        archive.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Qt WebEngine validation</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:body></w:document>')
    return data.getvalue()


def pdf_document():
    content = b"BT /F1 24 Tf 72 720 Td (Qt PDF validation) Tj ET"
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
               b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream"]
    body, offsets = b"%PDF-1.4\n", [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(body))
        body += str(index).encode() + b" 0 obj\n" + obj + b"\nendobj\n"
    start = len(body)
    body += b"xref\n0 6\n0000000000 65535 f \n" + b"".join(f"{offset:010} 00000 n \n".encode() for offset in offsets[1:])
    return body + b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + str(start).encode() + b"\n%%EOF\n"
