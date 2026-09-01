"""Tests for /api/v1/files/{fileId}/versions endpoints."""

from tests.helpers.assert_helper import assert_api_ok, assert_api_error


class TestFileVersions:
    def test_list_versions(self, client, auth_headers, versioned_file):
        r = client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        data = assert_api_ok(r)
        assert len(data) >= 2
        assert all("storagePath" not in item and "userId" not in item for item in data)

    def test_version_download_supports_full_and_range_responses(
        self, client, auth_headers, versioned_file,
    ):
        versions = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        )
        version_number = versions[0]["versionNumber"]
        path = f"/api/v1/files/{versioned_file['fileId']}/versions/{version_number}/download"

        complete = client.get(path, headers=auth_headers)
        assert complete.status_code == 200
        assert complete.content == versioned_file["content"]
        assert complete.headers["Accept-Ranges"] == "bytes"
        assert int(complete.headers["Content-Length"]) == len(versioned_file["content"])

        ranged = client.get(path, headers={**auth_headers, "Range": "bytes=1-4"})
        assert ranged.status_code == 206
        assert ranged.content == versioned_file["content"][1:5]
        assert ranged.headers["Content-Range"] == f"bytes 1-4/{len(versioned_file['content'])}"
        assert ranged.headers["Content-Length"] == "4"
        assert ranged.headers["Accept-Ranges"] == "bytes"

        invalid = client.get(path, headers={**auth_headers, "Range": "bytes=999999-"})
        assert invalid.status_code == 416
        assert invalid.headers["Content-Range"] == f"bytes */{len(versioned_file['content'])}"

    def test_list_versions_nonexistent_file(self, client, auth_headers):
        r = client.get("/api/v1/files/99999/versions", headers=auth_headers)
        assert_api_error(r, 404001)

    def test_delete_last_version_fails_with_stable_semantic_code(
        self, client, auth_headers, versioned_file,
    ):
        versions = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        )
        assert len(versions) == 2
        assert_api_ok(client.delete(
            f"/api/v1/files/{versioned_file['fileId']}/versions/{versions[-1]['versionNumber']}",
            headers=auth_headers,
        ))
        response = client.delete(
            f"/api/v1/files/{versioned_file['fileId']}/versions/{versions[0]['versionNumber']}",
            headers=auth_headers,
        )
        body = assert_api_error(response, 409003)
        assert body["messageKey"] == "error.cannotDeleteOnlyVersion"

    def test_restore_archives_current_content_and_updates_count(
        self, client, auth_headers, versioned_file,
    ):
        versions = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        )
        assert_api_ok(client.post(
            f"/api/v1/files/{versioned_file['fileId']}/versions/{versions[-1]['versionNumber']}/restore",
            headers=auth_headers,
        ))
        restored_versions = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        )
        assert len(restored_versions) == 3
        metadata = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}", headers=auth_headers)
        )
        assert metadata["versionCount"] == 4

    def test_delete_nonexistent_version(self, client, auth_headers, uploaded_file):
        r = client.delete(
            f"/api/v1/files/{uploaded_file['fileId']}/versions/999", headers=auth_headers
        )
        assert r.json()["code"] != 200

    def test_delete_version_success(self, client, auth_headers, versioned_file):
        versions = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        )
        assert len(versions) >= 2
        oldest_vnum = versions[-1]["versionNumber"]
        r = client.delete(
            f"/api/v1/files/{versioned_file['fileId']}/versions/{oldest_vnum}",
            headers=auth_headers,
        )
        assert_api_ok(r)

        # Verify version count decreased
        updated = assert_api_ok(
            client.get(f"/api/v1/files/{versioned_file['fileId']}/versions", headers=auth_headers)
        )
        assert len(updated) == len(versions) - 1
