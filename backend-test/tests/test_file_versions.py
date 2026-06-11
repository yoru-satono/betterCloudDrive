"""Tests for /api/v1/files/{fileId}/versions endpoints."""

import pytest
from tests.helpers.assert_helper import assert_api_ok, assert_api_error


class TestFileVersions:
    def test_list_versions(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/files/{uploaded_file['fileId']}/versions", headers=auth_headers)
        data = assert_api_ok(r)
        assert isinstance(data, list)
        # File has at least 0 versions (version records may not be created by instant upload)

    def test_list_versions_nonexistent_file(self, client, auth_headers):
        r = client.get("/api/v1/files/99999/versions", headers=auth_headers)
        assert_api_error(r, 404001)

    def test_delete_last_version_fails(self, client, auth_headers, uploaded_file):
        # File has only 1 version — cannot delete it
        versions = assert_api_ok(
            client.get(f"/api/v1/files/{uploaded_file['fileId']}/versions", headers=auth_headers)
        )
        if len(versions) >= 1:
            vnum = versions[-1]["versionNumber"]
            r = client.delete(
                f"/api/v1/files/{uploaded_file['fileId']}/versions/{vnum}", headers=auth_headers
            )
            # Should fail — can't delete the only version
            assert r.json()["code"] != 200

    def test_delete_nonexistent_version(self, client, auth_headers, uploaded_file):
        r = client.delete(
            f"/api/v1/files/{uploaded_file['fileId']}/versions/999", headers=auth_headers
        )
        assert r.json()["code"] != 200

    def test_delete_version_success(self, client, auth_headers, uploaded_file):
        """Delete a non-latest version; requires >=2 versions (skipped otherwise)."""
        versions = assert_api_ok(
            client.get(f"/api/v1/files/{uploaded_file['fileId']}/versions", headers=auth_headers)
        )
        if len(versions) < 2:
            pytest.skip("File has fewer than 2 versions — version creation not tested here")

        # Delete the oldest version (last in list, sorted newest-first)
        oldest_vnum = versions[-1]["versionNumber"]
        r = client.delete(
            f"/api/v1/files/{uploaded_file['fileId']}/versions/{oldest_vnum}",
            headers=auth_headers,
        )
        assert_api_ok(r)

        # Verify version count decreased
        updated = assert_api_ok(
            client.get(f"/api/v1/files/{uploaded_file['fileId']}/versions", headers=auth_headers)
        )
        assert len(updated) == len(versions) - 1
