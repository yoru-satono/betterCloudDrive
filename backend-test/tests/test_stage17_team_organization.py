"""阶段 17 团队收藏与标签真实 API 验收。"""

import uuid

import pytest

from tests.helpers.assert_helper import assert_api_ok, assert_paginated

pytestmark = pytest.mark.core


def test_team_favorites_tags_and_atomic_assignment_three_page_journey(client, auth_headers):
    marker = f"stage17_{uuid.uuid4().hex[:8]}"
    team_id = None
    tag_ids = []

    try:
        team = assert_api_ok(client.post(
            "/api/v1/teams",
            json={"name": f"{marker}_team"},
            headers=auth_headers,
        ))
        team_id = int(team["id"])
        file_ids = []
        for index in range(105):
            folder = assert_api_ok(client.post(
                f"/api/v1/teams/{team_id}/files/folder",
                json={"parentId": None, "folderName": f"{marker}_{index:03d}"},
                headers=auth_headers,
            ))
            file_ids.append(int(folder["id"]))
            assert_api_ok(client.post(
                f"/api/v1/teams/{team_id}/favorites/{folder['id']}",
                headers=auth_headers,
            ))

        favorites = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                f"/api/v1/teams/{team_id}/favorites",
                params={"q": f"  {marker}  ", "page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            favorites.extend(result["records"])
        assert len(favorites) == 105
        assert {int(item["id"]) for item in favorites} == set(file_ids)

        first = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/tags",
            json={"tagName": f"  {marker}_important  ", "color": " #AABBCC "},
            headers=auth_headers,
        ))
        tag_ids.append(int(first["id"]))
        assert first["tagName"] == f"{marker}_important"
        assert first["color"] == "#aabbcc"
        second = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/tags",
            json={"tagName": f"{marker}_second", "color": "#ff9900"},
            headers=auth_headers,
        ))
        tag_ids.append(int(second["id"]))

        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/tags/{first['id']}/files",
            json={"fileIds": file_ids},
            headers=auth_headers,
        ))
        tagged = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                f"/api/v1/teams/{team_id}/tags/{first['id']}/files",
                params={"q": marker, "page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            tagged.extend(result["records"])
        assert len(tagged) == 105
        assert {int(item["id"]) for item in tagged} == set(file_ids)

        target_id = file_ids[-1]
        current = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/files/{target_id}/tags",
            headers=auth_headers,
        ))
        assert {int(item["id"]) for item in current} == {int(first["id"])}

        replaced = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}/files/{target_id}/tags",
            json={"tagIds": [first["id"], second["id"], first["id"]]},
            headers=auth_headers,
        ))
        assert {int(item["id"]) for item in replaced} == set(tag_ids)
        assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}/files/{target_id}/tags",
            json={"tagIds": []},
            headers=auth_headers,
        )) == []
        assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/files/{target_id}",
            headers=auth_headers,
        ))
    finally:
        if team_id is not None:
            client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
