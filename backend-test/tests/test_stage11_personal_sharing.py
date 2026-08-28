"""Stage 11 real-API acceptance journey for personal share lifecycle."""

import time
import uuid

from tests.helpers.assert_helper import assert_api_error, assert_api_ok, assert_paginated


def test_personal_shares_three_page_lifecycle(client, auth_headers, uploaded_file):
    marker = f"stage11_{uuid.uuid4().hex[:8]}"
    file_id = uploaded_file["fileId"]
    future = int((time.time() + 7 * 86400) * 1000)
    past = int((time.time() - 86400) * 1000)
    created = []
    folder_id = None
    team_id = None

    try:
        for index in range(105):
            request = {"fileId": file_id}
            if index % 2 == 0:
                request["password"] = f"p{index:03d}"
            if 35 <= index < 70:
                request["expireAt"] = future
            elif index >= 70:
                request["expireAt"] = past

            share = assert_api_ok(client.post("/api/v1/shares", json=request, headers=auth_headers))
            assert share["shareUrl"] == f"http://localhost:3000/s/{share['shareCode']}"
            assert share["fileName"] == uploaded_file["fileName"]
            assert share["fileType"] == "file"
            assert share["fileSize"] == uploaded_file["fileSize"]
            created.append(share)

        records = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                "/api/v1/shares",
                params={"q": f"  {file_id}  ", "page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            records.extend(result["records"])

        expected_ids = {share["id"] for share in created}
        record_ids = [share["id"] for share in records]
        assert len(record_ids) == 105
        assert len(set(record_ids)) == 105
        assert set(record_ids) == expected_ids
        assert record_ids == sorted(record_ids, reverse=True)

        protected = assert_paginated(client.get(
            "/api/v1/shares",
            params={"q": file_id, "passwordProtected": True, "page": 1, "size": 100},
            headers=auth_headers,
        ))
        assert protected["total"] == 53
        assert all(item["hasPassword"] for item in protected["records"])

        never_expires = assert_paginated(client.get(
            "/api/v1/shares",
            params={"q": file_id, "expirationStatus": "NEVER", "page": 1, "size": 100},
            headers=auth_headers,
        ))
        expired = assert_paginated(client.get(
            "/api/v1/shares",
            params={"q": file_id, "expirationStatus": "EXPIRED", "page": 1, "size": 100},
            headers=auth_headers,
        ))
        active = assert_paginated(client.get(
            "/api/v1/shares",
            params={"q": file_id, "expirationStatus": "ACTIVE", "page": 1, "size": 100},
            headers=auth_headers,
        ))
        assert never_expires["total"] == 35
        assert expired["total"] == 35
        assert active["total"] == 70

        first = created[0]
        password = assert_api_ok(client.get(
            f"/api/v1/shares/{first['id']}/password", headers=auth_headers,
        ))
        assert password["password"] == "p000"
        updated = assert_api_ok(client.put(
            f"/api/v1/shares/{first['id']}",
            json={"clearPassword": True, "clearExpireAt": True, "clearMaxVisits": True},
            headers=auth_headers,
        ))
        assert updated["hasPassword"] is False
        assert updated["expireAtEpochMs"] is None
        assert updated["maxVisits"] is None

        public_share = created[1]
        assert client.get(public_share["shareUrl"]).status_code == 200
        assert_api_ok(client.post(f"/api/v1/shares/access/{public_share['shareCode']}"))
        download = client.post(
            f"/api/v1/shares/access/{public_share['shareCode']}/download/{file_id}"
        )
        assert download.status_code == 200
        detail = assert_api_ok(client.get(
            f"/api/v1/shares/{public_share['id']}", headers=auth_headers,
        ))
        assert detail["downloadCount"] >= 1

        limited = assert_api_ok(client.post(
            "/api/v1/shares",
            json={"fileId": file_id, "maxVisits": 1},
            headers=auth_headers,
        ))
        assert_api_ok(client.post(f"/api/v1/shares/access/{limited['shareCode']}"))
        assert_api_error(client.post(f"/api/v1/shares/access/{limited['shareCode']}"), 419005)

        folder = assert_api_ok(client.post(
            "/api/v1/files/folder",
            json={"parentId": None, "folderName": f"{marker}_folder"},
            headers=auth_headers,
        ))
        folder_id = folder["id"]
        folder_share = assert_api_ok(client.post(
            "/api/v1/shares",
            json={"fileId": folder_id, "password": "folder-pass"},
            headers=auth_headers,
        ))
        assert folder_share["fileName"] == folder["fileName"]
        assert folder_share["fileType"] == "folder"
        assert_api_ok(client.post(
            f"/api/v1/shares/access/{folder_share['shareCode']}",
            json={"password": "folder-pass"},
        ))

        team = assert_api_ok(client.post(
            "/api/v1/teams", json={"name": f"{marker}_team"}, headers=auth_headers,
        ))
        team_id = team["id"]
        team_folder = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/files/folder",
            json={"parentId": None, "folderName": f"{marker}_team_folder"},
            headers=auth_headers,
        ))
        team_share = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": team_folder["id"]},
            headers=auth_headers,
        ))
        personal_result = assert_paginated(client.get(
            "/api/v1/shares",
            params={"q": team_share["shareCode"], "page": 1, "size": 50},
            headers=auth_headers,
        ))
        assert personal_result["total"] == 0

        assert_api_ok(client.delete(f"/api/v1/shares/{public_share['id']}", headers=auth_headers))
        assert_api_ok(client.delete(f"/api/v1/shares/{public_share['id']}", headers=auth_headers))
        assert_api_error(client.post(f"/api/v1/shares/access/{public_share['shareCode']}"), 419002)
    finally:
        if folder_id is not None:
            client.request("DELETE", "/api/v1/files", json={"fileIds": [folder_id]}, headers=auth_headers)
        if team_id is not None:
            client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
