"""Common assertion helpers for API tests."""


def assert_api_ok(response):
    """Assert response has code=200 and return data."""
    body = response.json()
    assert body["code"] == 200, f"Expected 200, got {body}"
    assert body["message"] == "success"
    assert "requestId" in body
    return body.get("data")


def assert_api_error(response, expected_code: int):
    """Assert response has a specific error code. Handles non-JSON responses."""
    content_type = response.headers.get("Content-Type", "")
    if "application/json" in content_type:
        body = response.json()
        assert body["code"] == expected_code, f"Expected {expected_code}, got {body}"
        return body
    else:
        # Spring Security may return empty/HTML error responses
        assert response.status_code != 200, f"Expected error {expected_code}, got 200"
        return {"code": response.status_code}


def assert_paginated(response, expected_page: int = 1):
    """Assert valid pagination structure and return data."""
    data = assert_api_ok(response)
    assert "records" in data
    assert "total" in data
    assert "page" in data
    assert "size" in data
    assert "pages" in data
    assert data["page"] == expected_page
    return data
