"""Tests for /api/v1/recycle-bin endpoints."""

import uuid
from tests.helpers.assert_helper import assert_api_error, assert_api_ok, assert_paginated


class TestRecycleBin:
    def test_list_empty(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/recycle-bin", headers=auth_headers))
        # May or may not be empty

    def test_list_with_items(self, client, auth_headers, created_folder):
        # Create an item then delete it
        name = f"trash_{uuid.uuid4().hex[:4]}"
        r = client.post("/api/v1/files/folder", json={"folderName": name, "parentId": created_folder["id"]}, headers=auth_headers)
        fid = r.json()["data"]["id"]
        client.request("DELETE", "/api/v1/files", json={"fileIds": [fid]}, headers=auth_headers)

        data = assert_paginated(client.get("/api/v1/recycle-bin", headers=auth_headers))
        assert data["total"] >= 1

    def test_restore_file(self, client, auth_headers, created_folder):
        name = f"restore_{uuid.uuid4().hex[:4]}"
        r = client.post("/api/v1/files/folder", json={"folderName": name, "parentId": created_folder["id"]}, headers=auth_headers)
        fid = r.json()["data"]["id"]
        client.request("DELETE", "/api/v1/files", json={"fileIds": [fid]}, headers=auth_headers)

        r2 = client.post(f"/api/v1/recycle-bin/{fid}/restore", headers=auth_headers)
        assert_api_ok(r2)

    def test_permanent_delete(self, client, auth_headers, created_folder):
        name = f"permdel_{uuid.uuid4().hex[:4]}"
        r = client.post("/api/v1/files/folder", json={"folderName": name, "parentId": created_folder["id"]}, headers=auth_headers)
        fid = r.json()["data"]["id"]
        client.request("DELETE", "/api/v1/files", json={"fileIds": [fid]}, headers=auth_headers)

        r2 = client.delete(f"/api/v1/recycle-bin/{fid}", headers=auth_headers)
        assert_api_ok(r2)

    def test_empty_recycle_bin(self, client, auth_headers, created_folder):
        # Create a folder, soft-delete it, then empty the recycle bin
        name = f"empty_{uuid.uuid4().hex[:4]}"
        r = client.post("/api/v1/files/folder", json={
            "folderName": name, "parentId": created_folder["id"]
        }, headers=auth_headers)
        fid = r.json()["data"]["id"]
        client.request("DELETE", "/api/v1/files", json={"fileIds": [fid]}, headers=auth_headers)

        # Verify it is in the recycle bin
        before = assert_paginated(client.get("/api/v1/recycle-bin", headers=auth_headers))
        assert before["total"] >= 1

        # Empty recycle bin
        r2 = client.delete("/api/v1/recycle-bin", headers=auth_headers)
        assert_api_ok(r2)

        # Recycle bin should now be empty
        after = assert_paginated(client.get("/api/v1/recycle-bin", headers=auth_headers))
        assert after["total"] == 0

    def test_deleted_folder_tree_is_hidden_and_restored_as_one_root(
        self, client, auth_headers, created_folder,
    ):
        marker = f"trash_tree_{uuid.uuid4().hex[:8]}"
        root = assert_api_ok(client.post(
            "/api/v1/files/folder",
            json={"folderName": marker, "parentId": created_folder["id"]},
            headers=auth_headers,
        ))
        child = assert_api_ok(client.post(
            "/api/v1/files/folder",
            json={"folderName": f"{marker}_child", "parentId": root["id"]},
            headers=auth_headers,
        ))
        assert_api_ok(client.post(f"/api/v1/favorites/{child['id']}", headers=auth_headers))
        share = assert_api_ok(client.post(
            "/api/v1/shares", json={"fileId": child["id"]}, headers=auth_headers,
        ))

        assert_api_ok(client.request(
            "DELETE", "/api/v1/files", json={"fileIds": [root["id"]]}, headers=auth_headers,
        ))

        recycle = assert_paginated(client.get(
            "/api/v1/recycle-bin",
            params={"q": f"  {marker}  ", "page": 1, "size": 50},
            headers=auth_headers,
        ))
        assert [item["id"] for item in recycle["records"]] == [root["id"]]
        assert "storagePath" not in recycle["records"][0]
        assert "userId" not in recycle["records"][0]

        assert_api_error(client.get(f"/api/v1/files/{child['id']}", headers=auth_headers), 404001)
        search = assert_paginated(client.get(
            "/api/v1/files/search", params={"q": marker}, headers=auth_headers,
        ))
        assert child["id"] not in {item["id"] for item in search["records"]}
        favorites = assert_paginated(client.get(
            "/api/v1/favorites", params={"q": marker}, headers=auth_headers,
        ))
        assert child["id"] not in {item["id"] for item in favorites["records"]}
        assert_api_error(client.post(f"/api/v1/shares/access/{share['shareCode']}"), 404001)

        assert_api_ok(client.post(f"/api/v1/recycle-bin/{root['id']}/restore", headers=auth_headers))
        restored_child = assert_api_ok(
            client.get(f"/api/v1/files/{child['id']}", headers=auth_headers)
        )
        assert restored_child["parentId"] == root["id"]
