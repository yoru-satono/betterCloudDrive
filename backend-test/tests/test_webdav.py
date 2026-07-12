"""Result-oriented WebDAV protocol acceptance tests against the real stack."""

from urllib.parse import quote

import pytest

from tests.helpers.assert_helper import assert_api_ok

pytestmark = pytest.mark.webdav


def test_authentication_and_options(client, test_user, auth_headers, backend_url):
    password = "Dav-Auth-456!"
    assert_api_ok(client.put("/api/v1/auth/webdav", json={"enabled": True, "password": password}, headers=auth_headers))
    try:
        assert client.request("PROPFIND", "/webdav/", headers={"Depth": "0"}).status_code in (401, 403)
        assert client.request("PROPFIND", "/webdav/", auth=(test_user["username"], "wrong"), headers={"Depth": "0"}).status_code in (401, 403)
        response = client.request("OPTIONS", "/webdav/", auth=(test_user["username"], password))
        assert response.status_code == 200
        assert response.headers["DAV"] == "1, 2"
        assert {"PROPFIND", "MKCOL", "PUT", "COPY", "MOVE", "LOCK", "UNLOCK"} <= set(response.headers["Allow"].replace(" ", "").split(","))
    finally:
        assert_api_ok(client.put("/api/v1/auth/webdav", json={"enabled": False}, headers=auth_headers))


def test_full_file_lifecycle_and_rest_consistency(dav, client, auth_headers, run_id):
    root = f"dav_{run_id}"
    nested = f"{root}/中文 空格"
    file_path = f"{nested}/hello world.txt"
    encoded = "/webdav/" + "/".join(quote(part, safe="") for part in file_path.split("/"))
    content_v1 = b"webdav-content-v1"
    content_v2 = b"webdav-content-v2-overwrite"

    assert dav.request("MKCOL", f"/webdav/{root}").status_code == 201
    assert dav.request("MKCOL", "/webdav/" + quote(nested, safe="/")).status_code == 201
    assert dav.put(encoded, content=content_v1, headers={"Content-Type": "text/plain"}).status_code == 201

    propfind = dav.request("PROPFIND", "/webdav/" + quote(nested, safe="/"), headers={"Depth": "1"})
    assert propfind.status_code == 207
    assert propfind.headers["Content-Type"].startswith("application/xml")
    assert "hello%20world.txt" in propfind.text
    assert dav.get(encoded).content == content_v1
    head = dav.head(encoded)
    assert head.status_code == 200 and int(head.headers["Content-Length"]) == len(content_v1) and not head.content

    first = dav.get(encoded, headers={"Range": "bytes=0-5"})
    second = dav.get(encoded, headers={"Range": "bytes=6-"})
    assert first.status_code == second.status_code == 206
    assert first.headers["Content-Range"] == f"bytes 0-5/{len(content_v1)}"
    assert first.content + second.content == content_v1

    assert dav.put(encoded, content=content_v2, headers={"Content-Type": "text/plain"}).status_code == 204
    assert dav.get(encoded).content == content_v2
    listing = assert_api_ok(client.get("/api/v1/files", params={"parentId": ""}, headers=auth_headers))
    assert any(row["fileName"] == root for row in listing["records"])

    copied = encoded.replace("hello%20world.txt", "copy.txt")
    moved = encoded.replace("hello%20world.txt", "moved.txt")
    assert dav.request("COPY", encoded, headers={"Destination": copied}).status_code == 201
    assert dav.get(copied).content == content_v2
    assert dav.request("MOVE", encoded, headers={"Destination": moved}).status_code == 201
    assert dav.get(moved).content == content_v2
    assert dav.get(encoded).status_code == 404

    assert dav.delete(copied).status_code == 204
    assert dav.delete(moved).status_code == 204
    recycle = assert_api_ok(client.get("/api/v1/recycle-bin", headers=auth_headers))
    names = {row["fileName"] for row in recycle["records"]}
    assert {"copy.txt", "moved.txt"} <= names


def test_lock_conflict_and_unlock(dav, run_id):
    path = f"/webdav/lock_{run_id}.txt"
    assert dav.put(path, content=b"locked").status_code == 201
    lock = dav.request("LOCK", path, content='<D:lockinfo xmlns:D="DAV:"/>')
    assert lock.status_code == 200
    token = lock.headers["Lock-Token"]
    conflict = dav.put(path, content=b"must-not-write")
    assert conflict.status_code == 423
    assert dav.request("UNLOCK", path, headers={"Lock-Token": token}).status_code == 204
    assert dav.put(path, content=b"after-unlock").status_code == 204
    assert dav.get(path).content == b"after-unlock"
