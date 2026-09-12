"""Stage 20 real-API acceptance journeys for team lifecycle management."""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import threading
import time
import uuid

import pytest

from tests.helpers.assert_helper import assert_api_error, assert_api_ok, assert_paginated
from tests.test_teams import add_member, create_team, register_user


pytestmark = [pytest.mark.core, pytest.mark.admin]


def _records(client, headers, team_id):
    return assert_paginated(client.get(
        f"/api/v1/teams/{team_id}/members", params={"page": 1, "size": 100}, headers=headers,
    ))["records"]


def _wait_for_notification(client, headers, team_id, notification_type):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        records = assert_paginated(client.get(
            "/api/v1/notifications",
            params={"type": notification_type, "page": 1, "size": 100},
            headers=headers,
        ))["records"]
        match = next((item for item in records if item.get("teamId") == team_id), None)
        if match is not None:
            return match
        time.sleep(0.25)
    raise AssertionError(f"Notification {notification_type} for team {team_id} was not delivered")


def _admin_audit_actions(client, admin_headers, team_id):
    page = assert_paginated(client.get(
        f"/api/v1/admin/teams/{team_id}/audit-logs",
        params={"page": 1, "size": 100},
        headers=admin_headers,
    ))
    return {record["action"] for record in page["records"]}


def test_role_matrix_transfer_leave_delete_notifications_and_audit(
        client, auth_headers, admin_headers, registration_code_for):
    marker = f"stage20_{uuid.uuid4().hex[:8]}"
    admin = register_user(client, registration_code_for, "stage20_admin")
    editor = register_user(client, registration_code_for, "stage20_editor")
    viewer = register_user(client, registration_code_for, "stage20_viewer")
    team = create_team(client, auth_headers, marker)
    team_id = team["id"]
    deleted = False

    add_member(client, auth_headers, team_id, admin["username"], "ADMIN")
    add_member(client, auth_headers, team_id, editor["username"], "EDITOR")
    add_member(client, auth_headers, team_id, viewer["username"], "VIEWER")

    try:
        assert_api_error(client.put(
            f"/api/v1/teams/{team_id}", json={"name": "blocked"}, headers=editor["headers"],
        ), 403)
        assert_api_error(client.put(
            f"/api/v1/teams/{team_id}", json={"name": "blocked"}, headers=viewer["headers"],
        ), 403)
        renamed = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}", json={"name": f"  {marker}_renamed  "}, headers=admin["headers"],
        ))
        assert renamed["name"] == f"{marker}_renamed"
        assert renamed["role"] == "ADMIN"

        assert_api_error(client.post(f"/api/v1/teams/{team_id}/leave", headers=auth_headers), 403)

        first_transfer = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/owner",
            json={"userId": editor["userId"]},
            headers=auth_headers,
        ))
        assert first_transfer["ownerId"] == editor["userId"]
        assert first_transfer["role"] == "ADMIN"
        roles = {member["userId"]: member["role"] for member in _records(client, editor["headers"], team_id)}
        assert roles[editor["userId"]] == "OWNER"
        assert sum(role == "OWNER" for role in roles.values()) == 1

        assert_api_ok(client.post(f"/api/v1/teams/{team_id}/leave", headers=auth_headers))
        assert_api_error(client.get(f"/api/v1/teams/{team_id}", headers=auth_headers), 403)

        second_transfer = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/owner",
            json={"userId": viewer["userId"]},
            headers=editor["headers"],
        ))
        assert second_transfer["ownerId"] == viewer["userId"]
        assert second_transfer["role"] == "ADMIN"
        roles = {member["userId"]: member["role"] for member in _records(client, viewer["headers"], team_id)}
        assert roles[viewer["userId"]] == "OWNER"
        assert roles[editor["userId"]] == "ADMIN"
        assert sum(role == "OWNER" for role in roles.values()) == 1

        pre_delete_actions = _admin_audit_actions(client, admin_headers, team_id)
        assert {"TEAM_UPDATE", "OWNER_TRANSFER", "MEMBER_LEAVE"} <= pre_delete_actions

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=viewer["headers"]))
        deleted = True
        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=viewer["headers"]))

        for headers in (viewer["headers"], editor["headers"], admin["headers"]):
            assert_api_error(client.get(f"/api/v1/teams/{team_id}", headers=headers), 404)
            listed = assert_paginated(client.get("/api/v1/teams", headers=headers))["records"]
            assert all(item["id"] != team_id for item in listed)

        notification = _wait_for_notification(client, admin["headers"], team_id, "TEAM_DELETE")
        assert notification["type"] == "TEAM_DELETE"
        assert notification["teamId"] == team_id

        details = assert_api_ok(client.get(f"/api/v1/admin/teams/{team_id}", headers=admin_headers))
        deleted_at = datetime.fromisoformat(details["deletedAt"]).replace(tzinfo=timezone.utc)
        purge_after = datetime.fromisoformat(details["purgeAfter"]).replace(tzinfo=timezone.utc)
        assert (purge_after - deleted_at).days == 30
        assert "TEAM_DELETE" in _admin_audit_actions(client, admin_headers, team_id)
    finally:
        if not deleted:
            client.delete(f"/api/v1/teams/{team_id}", headers=viewer["headers"])
        client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers)


def test_concurrent_owner_transfers_are_serialized_to_one_winner(
        client, auth_headers, admin_headers, registration_code_for):
    first = register_user(client, registration_code_for, "stage20_race_a")
    second = register_user(client, registration_code_for, "stage20_race_b")
    team = create_team(client, auth_headers, f"stage20_race_{uuid.uuid4().hex[:8]}")
    team_id = team["id"]
    add_member(client, auth_headers, team_id, first["username"], "EDITOR")
    add_member(client, auth_headers, team_id, second["username"], "VIEWER")
    barrier = threading.Barrier(2)

    def transfer(target):
        barrier.wait(timeout=5)
        response = client.post(
            f"/api/v1/teams/{team_id}/owner",
            json={"userId": target["userId"]},
            headers=auth_headers,
        )
        return target, response.json()

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(transfer, (first, second)))

        successes = [(target, body) for target, body in results if body["code"] == 200]
        failures = [body for _, body in results if body["code"] != 200]
        assert len(successes) == 1, results
        assert len(failures) == 1 and failures[0]["code"] == 403, results

        winner, success = successes[0]
        assert success["data"]["ownerId"] == winner["userId"]
        members = _records(client, winner["headers"], team_id)
        owners = [member for member in members if member["role"] == "OWNER"]
        assert [member["userId"] for member in owners] == [winner["userId"]]
        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=winner["headers"]))
    finally:
        client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers)
