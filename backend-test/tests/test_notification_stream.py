"""Notification stream ticket authentication contracts."""

import logging

import pytest

from tests.helpers.assert_helper import assert_api_error

pytestmark = pytest.mark.core


def test_notification_stream_ticket_is_one_time_and_hides_access_token(client, auth_headers):
    httpx_logger = logging.getLogger("httpx")
    previous_level = httpx_logger.level
    httpx_logger.setLevel(logging.WARNING)
    try:
        issued = client.post("/api/v1/notifications/stream-ticket", headers=auth_headers)
        assert issued.status_code == 200, issued.text
        ticket = issued.json()["data"]["ticket"]
        assert ticket and "accessToken" not in ticket

        with client.stream("GET", "/api/v1/notifications/stream", params={"ticket": ticket}) as stream:
            assert stream.status_code == 200
            assert "accessToken" not in str(stream.request.url)
            assert any(line.replace(" ", "").startswith("event:connected") for line in stream.iter_lines())

        replay = client.get("/api/v1/notifications/stream", params={"ticket": ticket})
        assert_api_error(replay, 401)
        forged = client.get("/api/v1/notifications/stream", params={"ticket": "forged"})
        assert_api_error(forged, 401)
    finally:
        httpx_logger.setLevel(previous_level)


def test_notification_stream_rejects_legacy_access_token_query(client, auth_headers):
    legacy = client.get("/api/v1/notifications/stream", params={"accessToken": "legacy-token"})
    assert_api_error(legacy, 400)
