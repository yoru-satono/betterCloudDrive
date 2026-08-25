"""Tests for /api/v1/files endpoints (CRUD, search)."""

import uuid
from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated


class TestListFiles:
    def test_list_root_empty(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/files", headers=auth_headers))
        # Root may or may not be empty depending on test state

    def test_list_with_items(self, client, auth_headers, created_folder, uploaded_file):
        data = assert_paginated(client.get("/api/v1/files", headers=auth_headers))
        assert data["total"] >= 1

    def test_list_pagination(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/files?page=1&size=5", headers=auth_headers))
        assert data["size"] == 5

    def test_list_sort_by_name(self, client, auth_headers):
        r = client.get("/api/v1/files?sortBy=fileName&order=asc", headers=auth_headers)
        assert_api_ok(r)

    def test_folder_first_sort_is_stable_across_more_than_two_pages(
        self, client, auth_headers, created_folder, uploaded_file,
    ):
        prefix = uuid.uuid4().hex[:6]
        folder_names = [f"{prefix}_{suffix}" for suffix in ("z", "a", "m", "b", "c")]
        for name in folder_names:
            assert_api_ok(client.post(
                "/api/v1/files/folder",
                json={"parentId": created_folder["id"], "folderName": name},
                headers=auth_headers,
            ))

        records = []
        for page in range(1, 5):
            data = assert_paginated(client.get(
                "/api/v1/files",
                params={
                    "parentId": created_folder["id"], "page": page, "size": 2,
                    "sortBy": "fileName", "order": "desc",
                },
                headers=auth_headers,
            ), expected_page=page)
            records.extend(data["records"])
            if page >= data["pages"]:
                break

        assert len(records) == 6
        assert len({item["id"] for item in records}) == len(records)
        assert [item["fileType"] for item in records] == ["folder"] * 5 + ["file"]
        assert [item["fileName"] for item in records[:5]] == sorted(folder_names, reverse=True)

        fallback = assert_paginated(client.get(
            "/api/v1/files",
            params={
                "parentId": created_folder["id"], "page": 1, "size": 100,
                "sortBy": "storagePath", "order": "sideways",
            },
            headers=auth_headers,
        ))["records"]
        assert [item["fileName"] for item in fallback[:5]] == sorted(folder_names)

    def test_list_with_parent_id(self, client, auth_headers, created_folder):
        r = client.get(f"/api/v1/files?parentId={created_folder['id']}", headers=auth_headers)
        assert_paginated(r)


class TestGetFile:
    def test_get_file(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/files/{uploaded_file['fileId']}", headers=auth_headers)
        data = assert_api_ok(r)
        assert data["id"] == uploaded_file["fileId"]

    def test_get_file_nonexistent(self, client, auth_headers):
        assert_api_error(client.get("/api/v1/files/99999", headers=auth_headers), 404001)

    def test_get_folder(self, client, auth_headers, created_folder):
        r = client.get(f"/api/v1/files/{created_folder['id']}", headers=auth_headers)
        data = assert_api_ok(r)
        assert data["fileType"] == "folder"


class TestCreateFolder:
    def test_create_folder_root(self, client, auth_headers):
        name = f"root_folder_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/files/folder", json={"folderName": name}, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["fileType"] == "folder"
        assert data["fileName"] == name

    def test_create_folder_nested(self, client, auth_headers, created_folder):
        name = f"sub_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/files/folder", json={
            "parentId": created_folder["id"], "folderName": name
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["parentId"] == created_folder["id"]

    def test_create_folder_name_conflict(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/files/folder", json={
            "parentId": None, "folderName": created_folder["fileName"]
        }, headers=auth_headers)
        assert_api_error(r, 409001)


class TestRename:
    def test_rename_file(self, client, auth_headers, uploaded_file):
        new_name = f"renamed_{uuid.uuid4().hex[:4]}.txt"
        r = client.put(f"/api/v1/files/{uploaded_file['fileId']}", json={
            "newName": new_name
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["fileName"] == new_name


class TestMove:
    def test_move_file(self, client, auth_headers, uploaded_file, created_folder):
        # Create a subfolder to move into
        sub = client.post("/api/v1/files/folder", json={
            "parentId": created_folder["id"], "folderName": f"dest_{uuid.uuid4().hex[:4]}"
        }, headers=auth_headers)
        dest_id = sub.json()["data"]["id"]

        r = client.post(f"/api/v1/files/{uploaded_file['fileId']}/move", json={
            "targetParentId": dest_id
        }, headers=auth_headers)
        assert_api_ok(r)


class TestCopy:
    def test_copy_file(self, client, auth_headers, uploaded_file, created_folder):
        destination = assert_api_ok(client.post("/api/v1/files/folder", json={
            "parentId": created_folder["id"], "folderName": f"copy_dest_{uuid.uuid4().hex[:6]}"
        }, headers=auth_headers))
        r = client.post(f"/api/v1/files/{uploaded_file['fileId']}/copy", json={
            "targetParentId": destination["id"]
        }, headers=auth_headers)
        assert_api_ok(r)
        records = assert_paginated(client.get(
            f"/api/v1/files?parentId={destination['id']}", headers=auth_headers,
        ))["records"]
        assert len(records) == 1
        assert records[0]["fileName"] == f"{uploaded_file['fileName']} (copy)"
        assert records[0]["id"] != uploaded_file["fileId"]

    def test_copy_preserves_content(self, client, auth_headers, uploaded_file, created_folder, sample_file):
        destination = assert_api_ok(client.post("/api/v1/files/folder", json={
            "parentId": created_folder["id"], "folderName": f"copy_content_{uuid.uuid4().hex[:6]}"
        }, headers=auth_headers))
        assert_api_ok(client.post(f"/api/v1/files/{uploaded_file['fileId']}/copy", json={
            "targetParentId": destination["id"]
        }, headers=auth_headers))

        files = assert_paginated(client.get(
            f"/api/v1/files?parentId={destination['id']}",
            headers=auth_headers,
        ))
        assert len(files["records"]) == 1
        copy_id = files["records"][0]["id"]
        assert files["records"][0]["fileName"] == f"{uploaded_file['fileName']} (copy)"
        assert copy_id != uploaded_file["fileId"]

        original_bytes = sample_file.read_bytes()
        dl = client.get(f"/api/v1/download/{copy_id}", headers=auth_headers)
        assert dl.status_code == 200
        assert dl.content == original_bytes


class TestDelete:
    def test_batch_delete(self, client, auth_headers, created_folder):
        # Create two files and delete them
        names = []
        for _ in range(2):
            names.append(f"del_{uuid.uuid4().hex[:4]}")
        file_ids = []
        for name in names:
            r = client.post("/api/v1/files/folder", json={"folderName": name, "parentId": created_folder["id"]}, headers=auth_headers)
            file_ids.append(r.json()["data"]["id"])

        r = client.request("DELETE", "/api/v1/files", json={"fileIds": file_ids}, headers=auth_headers)
        assert_api_ok(r)


class TestStage6FileManagementJourney:
    def test_recursive_copy_move_conflict_and_batch_recycle(
        self, client, auth_headers, created_folder, uploaded_file,
    ):
        def create(parent_id, name):
            return assert_api_ok(client.post(
                "/api/v1/files/folder",
                json={"parentId": parent_id, "folderName": name},
                headers=auth_headers,
            ))

        suffix = uuid.uuid4().hex[:6]
        level1 = create(created_folder["id"], f"  stage6-l1-{suffix}  ")
        assert level1["fileName"] == f"stage6-l1-{suffix}"
        level2 = create(level1["id"], f"stage6-l2-{suffix}")
        level3 = create(level2["id"], f"stage6-l3-{suffix}")
        destination = create(created_folder["id"], f"stage6-dest-{suffix}")

        renamed = assert_api_ok(client.put(
            f"/api/v1/files/{level1['id']}",
            json={"newName": f"  stage6-renamed-{suffix}  "},
            headers=auth_headers,
        ))
        assert renamed["fileName"] == f"stage6-renamed-{suffix}"

        assert_api_ok(client.post(
            f"/api/v1/files/{uploaded_file['fileId']}/move",
            json={"targetParentId": level3["id"]},
            headers=auth_headers,
        ))
        assert_api_ok(client.post(
            f"/api/v1/files/{level2['id']}/move",
            json={"targetParentId": destination["id"]},
            headers=auth_headers,
        ))

        assert_api_ok(client.post(
            f"/api/v1/files/{level2['id']}/copy",
            json={"targetParentId": created_folder["id"]},
            headers=auth_headers,
        ))
        root_items = assert_paginated(client.get(
            "/api/v1/files",
            params={"parentId": created_folder["id"], "size": 100},
            headers=auth_headers,
        ))["records"]
        copied_level2 = next(
            item for item in root_items
            if item["fileName"] == f"{level2['fileName']} (copy)"
        )
        copied_children = assert_paginated(client.get(
            "/api/v1/files",
            params={"parentId": copied_level2["id"], "size": 100},
            headers=auth_headers,
        ))["records"]
        copied_level3 = next(
            item for item in copied_children if item["fileName"] == level3["fileName"]
        )
        copied_leaf_items = assert_paginated(client.get(
            "/api/v1/files",
            params={"parentId": copied_level3["id"], "size": 100},
            headers=auth_headers,
        ))["records"]
        assert [item["fileName"] for item in copied_leaf_items] == [uploaded_file["fileName"]]

        conflict = client.post(
            f"/api/v1/files/{level2['id']}/copy",
            json={"targetParentId": created_folder["id"]},
            headers=auth_headers,
        )
        assert_api_error(conflict, 409001)

        trash = create(created_folder["id"], f"stage6-trash-{suffix}")
        assert_api_ok(client.request(
            "DELETE", "/api/v1/files",
            json={"fileIds": [copied_level2["id"], trash["id"], copied_level2["id"]]},
            headers=auth_headers,
        ))
        recycled = assert_paginated(client.get(
            "/api/v1/recycle-bin", params={"size": 100}, headers=auth_headers,
        ))["records"]
        recycled_ids = {item["id"] for item in recycled}
        assert copied_level2["id"] in recycled_ids
        assert trash["id"] in recycled_ids


class TestSearch:
    def test_search_by_filename(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/files/search?q={uploaded_file['fileName'][:5]}", headers=auth_headers)
        assert_paginated(r)

    def test_search_no_results(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/files/search?q=zzzzznotexist999", headers=auth_headers))
        assert data["total"] == 0

    def test_search_empty_query(self, client, auth_headers):
        r = client.get("/api/v1/files/search?q=", headers=auth_headers)
        # Backend treats empty query as match-all (returns all user files)
        assert_paginated(r)
