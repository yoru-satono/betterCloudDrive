"""阶段 16 团队邀请与通知的大列表黑盒验收。"""

import subprocess
import uuid
from pathlib import Path

import pytest

from tests.helpers.assert_helper import assert_api_ok, assert_paginated

pytestmark = pytest.mark.core
REPO_ROOT = Path(__file__).resolve().parents[2]


def _sql(statement):
    result = subprocess.run(
        [
            "docker", "compose", "exec", "-T", "postgres", "psql",
            "-v", "ON_ERROR_STOP=1", "-U", "bcd_user", "-d", "better_cloud_drive",
            "-c", statement,
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_invites_and_notifications_have_stable_three_page_contract(client, auth_headers, test_user):
    marker = f"stage16_{uuid.uuid4().hex[:10]}"
    team = assert_api_ok(client.post(
        "/api/v1/teams",
        json={"name": f"{marker}_team"},
        headers=auth_headers,
    ))
    team_id = int(team["id"])
    user_id = int(test_user["userId"])

    try:
        _sql(f"""
        WITH generated AS (
            SELECT sequence,
                   md5('{marker}_' || sequence::text) AS token,
                   CURRENT_TIMESTAMP - sequence * INTERVAL '1 millisecond' AS created_at
              FROM generate_series(1, 105) AS sequence
        ), inserted AS (
            INSERT INTO team_invites (
                team_id, email, role, token, status, invited_by_user_id,
                expires_at, created_at, updated_at
            )
            SELECT {team_id}, '{marker}_' || sequence || '@test.local', 'VIEWER', token,
                   'PENDING', {user_id}, CURRENT_TIMESTAMP + INTERVAL '7 days', created_at, created_at
              FROM generated
            RETURNING token, created_at
        )
        INSERT INTO notifications (
            user_id, team_id, type, title_key, content_key, message_params, link, created_at
        )
        SELECT {user_id}, {team_id}, 'TEAM_INVITE', 'notification.teamInvite.title',
               'notification.teamInvite.content', '{{"teamName":"{marker}"}}'::jsonb,
               '/team-invites/' || token, created_at
          FROM inserted;
        """)

        invites = []
        notifications = []
        for page in range(1, 4):
            invite_page = assert_paginated(client.get(
                f"/api/v1/teams/{team_id}/invites",
                params={"page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            notification_page = assert_paginated(client.get(
                "/api/v1/notifications",
                params={"type": "TEAM_INVITE", "page": page, "size": 50},
                headers=auth_headers,
            ), expected_page=page)
            invites.extend(invite_page["records"])
            notifications.extend(notification_page["records"])

        assert len(invites) == 105
        assert len({item["id"] for item in invites}) == 105
        assert all(item["teamId"] == team_id and item["status"] == "PENDING" for item in invites)
        assert [(item["createdAt"], item["id"]) for item in invites] == sorted(
            [(item["createdAt"], item["id"]) for item in invites], reverse=True,
        )

        assert len(notifications) == 105
        assert len({item["id"] for item in notifications}) == 105
        assert all(item["teamId"] == team_id and item["isRead"] is False for item in notifications)
        assert [(item["createdAt"], item["id"]) for item in notifications] == sorted(
            [(item["createdAt"], item["id"]) for item in notifications], reverse=True,
        )

        unread_before = assert_api_ok(client.get(
            "/api/v1/notifications/unread-count", headers=auth_headers,
        ))
        assert unread_before >= 105
        assert_api_ok(client.post(
            f"/api/v1/notifications/{notifications[0]['id']}/read", headers=auth_headers,
        ))
        assert_api_ok(client.post("/api/v1/notifications/read-all", headers=auth_headers))
        unread_after = assert_api_ok(client.get(
            "/api/v1/notifications/unread-count", headers=auth_headers,
        ))
        assert unread_after == 0
    finally:
        _sql(f"DELETE FROM notifications WHERE team_id = {team_id}; DELETE FROM team_invites WHERE team_id = {team_id};")
        client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
