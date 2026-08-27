"""Stage 10 real-API acceptance journey for personal favorites and tags."""

import uuid

from tests.helpers.assert_helper import assert_api_ok, assert_paginated


def test_personal_favorites_and_tags_three_page_journey(client, auth_headers):
    marker = f"stage10_{uuid.uuid4().hex[:8]}"
    personal_ids = []
    team_id = None
    tag_id = None

    try:
        for index in range(105):
            folder = assert_api_ok(client.post(
                "/api/v1/files/folder",
                json={"parentId": None, "folderName": f"{marker}_{index:03d}"},
                headers=auth_headers,
            ))
            personal_ids.append(folder["id"])
            assert_api_ok(client.post(f"/api/v1/favorites/{folder['id']}", headers=auth_headers))

        team = assert_api_ok(client.post(
            "/api/v1/teams",
            json={"name": f"{marker}_team"},
            headers=auth_headers,
        ))
        team_id = team["id"]
        team_folder = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/files/folder",
            json={"parentId": None, "folderName": f"{marker}_team_folder"},
            headers=auth_headers,
        ))
        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/favorites/{team_folder['id']}",
            headers=auth_headers,
        ))

        favorite_records = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                "/api/v1/favorites",
                params={"q": f"  {marker}  ", "page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            favorite_records.extend(result["records"])

        favorite_ids = [record["id"] for record in favorite_records]
        assert len(favorite_ids) == 105
        assert len(set(favorite_ids)) == 105
        assert set(favorite_ids) == set(personal_ids)
        assert team_folder["id"] not in favorite_ids

        tag = assert_api_ok(client.post(
            "/api/v1/tags",
            json={"tagName": f"  {marker}_tag  ", "color": "#336699"},
            headers=auth_headers,
        ))
        tag_id = tag["id"]
        assert tag["tagName"] == f"{marker}_tag"
        assert_api_ok(client.post(
            f"/api/v1/tags/{tag_id}/files",
            json={"fileIds": personal_ids},
            headers=auth_headers,
        ))

        tagged_records = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                f"/api/v1/tags/{tag_id}/files",
                params={"q": marker, "page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            tagged_records.extend(result["records"])

        tagged_ids = [record["id"] for record in tagged_records]
        assert len(tagged_ids) == 105
        assert len(set(tagged_ids)) == 105
        assert set(tagged_ids) == set(personal_ids)

        second_tag = assert_api_ok(client.post(
            "/api/v1/tags",
            json={"tagName": f"{marker}_second", "color": "#ff9900"},
            headers=auth_headers,
        ))
        replaced = assert_api_ok(client.put(
            f"/api/v1/files/{personal_ids[0]}/tags",
            json={"tagIds": [tag_id, second_tag["id"], tag_id]},
            headers=auth_headers,
        ))
        assert {item["id"] for item in replaced} == {tag_id, second_tag["id"]}
        assert_api_ok(client.put(
            f"/api/v1/files/{personal_ids[0]}/tags",
            json={"tagIds": []},
            headers=auth_headers,
        )) == []

        assert_api_ok(client.delete(f"/api/v1/tags/{tag_id}", headers=auth_headers))
        tag_id = None
        assert_api_ok(client.get(f"/api/v1/files/{personal_ids[1]}", headers=auth_headers))
    finally:
        if tag_id is not None:
            client.delete(f"/api/v1/tags/{tag_id}", headers=auth_headers)
        for start in range(0, len(personal_ids), 100):
            client.request(
                "DELETE",
                "/api/v1/files",
                json={"fileIds": personal_ids[start:start + 100]},
                headers=auth_headers,
            )
        if team_id is not None:
            client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
