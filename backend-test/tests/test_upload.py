"""Tests for /api/v1/upload endpoints (chunked upload, instant upload)."""

import hashlib
import uuid
from tests.helpers.assert_helper import assert_api_ok, assert_api_error


class TestInitUpload:
    def test_init_upload(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": "test_upload.bin",
            "fileSize": 5242880 * 2,
            "totalChunks": 2,
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert "sessionId" in data
        assert data["chunkSize"] > 0
        assert data["totalChunks"] == 2

    def test_init_upload_missing_parent(self, client, auth_headers):
        # Backend accepts any parentId at init time (validation happens at complete)
        r = client.post("/api/v1/upload/init", json={
            "parentId": 99999,
            "fileName": "test.bin",
            "fileSize": 100,
            "totalChunks": 1,
        }, headers=auth_headers)
        assert_api_ok(r)  # Init succeeds even with nonexistent parent


class TestUploadChunk:
    def test_upload_chunk(self, client, auth_headers, created_folder, sample_file):
        file_size = sample_file.stat().st_size
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": f"chunk_{uuid.uuid4().hex[:6]}.txt",
            "fileSize": file_size,
            "totalChunks": 1,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]

        r2 = client.post(
            f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
            files={"file": sample_file.open("rb")},
            headers=auth_headers,
        )
        assert_api_ok(r2)


class TestUploadStatus:
    def test_upload_status(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": "status_test.bin",
            "fileSize": 5242880 * 3,
            "totalChunks": 3,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]

        r2 = client.get(f"/api/v1/upload/{session_id}/status", headers=auth_headers)
        data = assert_api_ok(r2)
        assert data["totalChunks"] == 3
        assert "missingChunks" in data


class TestCompleteUpload:
    def test_complete_upload(self, client, auth_headers, created_folder, sample_file):
        file_size = sample_file.stat().st_size
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": f"complete_{uuid.uuid4().hex[:6]}.txt",
            "fileSize": file_size,
            "totalChunks": 1,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]

        client.post(
            f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
            files={"file": sample_file.open("rb")},
            headers=auth_headers,
        )
        r2 = client.post(f"/api/v1/upload/{session_id}/complete", headers=auth_headers)
        data = assert_api_ok(r2)
        assert "fileId" in data

    def test_complete_missing_chunks(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": "incomplete.bin",
            "fileSize": 5242880 * 2,
            "totalChunks": 2,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]
        # Don't upload any chunks
        r2 = client.post(f"/api/v1/upload/{session_id}/complete", headers=auth_headers)
        assert_api_error(r2, 419006)


class TestCancelUpload:
    def test_cancel_upload(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": "cancel_test.bin",
            "fileSize": 5242880,
            "totalChunks": 1,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]
        r2 = client.post(f"/api/v1/upload/{session_id}/cancel", headers=auth_headers)
        assert_api_ok(r2)


class TestInstantUpload:
    def test_instant_upload_not_found(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/upload/instant", json={
            "parentId": created_folder["id"],
            "fileName": "instant_test.txt",
            "fileSize": 100,
            "md5Hash": "a" * 32,
        }, headers=auth_headers)
        # Should return 419010 (no matching file) — this is normal
        assert_api_error(r, 419010)

    def test_instant_upload_success(self, client, auth_headers, created_folder, sample_file):
        """Second upload of same content should succeed via deduplication (秒传)."""
        file_size = sample_file.stat().st_size
        file_bytes = sample_file.read_bytes()
        md5 = hashlib.md5(file_bytes).hexdigest()

        # First: ensure the file is in storage via chunked upload (pass md5Hash so FileEntity stores it)
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": f"orig_{uuid.uuid4().hex[:6]}.txt",
            "fileSize": file_size,
            "md5Hash": md5,
            "totalChunks": 1,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]
        client.post(
            f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
            files={"file": sample_file.open("rb")},
            headers=auth_headers,
        )
        client.post(f"/api/v1/upload/{session_id}/complete", headers=auth_headers)

        # Now instant upload the same content — should find existing file
        r2 = client.post("/api/v1/upload/instant", json={
            "parentId": created_folder["id"],
            "fileName": f"instant_{uuid.uuid4().hex[:6]}.txt",
            "fileSize": file_size,
            "md5Hash": md5,
        }, headers=auth_headers)
        data = assert_api_ok(r2)
        assert "fileId" in data


class TestUploadChunkIdempotent:
    def test_upload_chunk_idempotent(self, client, auth_headers, created_folder, sample_file):
        """Uploading the same chunk twice should not cause an error."""
        file_size = sample_file.stat().st_size
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": f"idem_{uuid.uuid4().hex[:6]}.txt",
            "fileSize": file_size,
            "totalChunks": 1,
        }, headers=auth_headers)
        session_id = r.json()["data"]["sessionId"]

        # Upload chunk 0 twice — second call should be idempotent (200)
        client.post(
            f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
            files={"file": sample_file.open("rb")},
            headers=auth_headers,
        )
        r2 = client.post(
            f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
            files={"file": sample_file.open("rb")},
            headers=auth_headers,
        )
        assert_api_ok(r2)


class TestQuotaExceeded:
    def test_init_upload_quota_exceeded(self, client, auth_headers, test_user, created_folder):
        """Upload a file larger than the user's quota should fail with 419001."""
        # Set a tiny quota via admin... or just test with a very large file
        r = client.post("/api/v1/upload/init", json={
            "parentId": created_folder["id"],
            "fileName": "huge.bin",
            "fileSize": 9999999999999,  # ~9TB, exceeds default 10GB quota
            "totalChunks": 1,
        }, headers=auth_headers)
        assert_api_error(r, 419001)
