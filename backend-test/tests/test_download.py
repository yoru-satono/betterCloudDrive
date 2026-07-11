"""Tests for download and preview endpoints."""

from tests.helpers.assert_helper import assert_api_error


class TestDownload:
    def test_download_file(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/download/{uploaded_file['fileId']}", headers=auth_headers)
        assert r.status_code == 200
        assert "attachment" in r.headers.get("Content-Disposition", "")
        assert r.content

    def test_download_nonexistent(self, client, auth_headers):
        r = client.get("/api/v1/download/99999", headers=auth_headers)
        assert_api_error(r, 404001)

    def test_download_folder(self, client, auth_headers, created_folder):
        r = client.get(f"/api/v1/download/{created_folder['id']}", headers=auth_headers)
        assert_api_error(r, 404001)

    def test_download_range_request(self, client, auth_headers, uploaded_file):
        complete = client.get(f"/api/v1/download/{uploaded_file['fileId']}", headers=auth_headers)
        split_at = max(1, len(complete.content) // 2)
        first = client.get(
            f"/api/v1/download/{uploaded_file['fileId']}",
            headers={**auth_headers, "Range": f"bytes=0-{split_at - 1}"},
        )
        second = client.get(
            f"/api/v1/download/{uploaded_file['fileId']}",
            headers={**auth_headers, "Range": f"bytes={split_at}-"},
        )
        assert first.status_code == second.status_code == 206
        total = len(complete.content)
        assert first.headers["Content-Range"] == f"bytes 0-{split_at - 1}/{total}"
        assert second.headers["Content-Range"] == f"bytes {split_at}-{total - 1}/{total}"
        assert first.content + second.content == complete.content


class TestPreview:
    def test_preview_file(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/preview/{uploaded_file['fileId']}", headers=auth_headers)
        assert r.status_code == 200
        assert r.headers["Content-Type"].startswith("text/plain")
        assert r.content

    def test_preview_folder(self, client, auth_headers, created_folder):
        r = client.get(f"/api/v1/preview/{created_folder['id']}", headers=auth_headers)
        assert_api_error(r, 404001)
