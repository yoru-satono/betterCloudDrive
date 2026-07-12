"""Test-scoped fixtures for the backend-test project."""

import hashlib
import httpx
import pytest
import subprocess
import time
import uuid
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"


@pytest.fixture
def dav(client, test_user, auth_headers, backend_url):
    password = "Dav-Test-456!"
    enabled = client.put(
        "/api/v1/auth/webdav", json={"enabled": True, "password": password}, headers=auth_headers,
    ).json()
    assert enabled["code"] == 200 and enabled["data"]["webdavEnabled"] is True, enabled
    with httpx.Client(
        base_url=backend_url, auth=(test_user["username"], password), timeout=30,
    ) as authenticated:
        yield authenticated
    disabled = client.put(
        "/api/v1/auth/webdav", json={"enabled": False}, headers=auth_headers,
    ).json()
    assert disabled["code"] == 200 and disabled["data"]["webdavEnabled"] is False, disabled
    refused = client.request(
        "PROPFIND", "/webdav/", auth=(test_user["username"], password), headers={"Depth": "0"},
    )
    assert refused.status_code in (401, 403)


@pytest.fixture(scope="session")
def sample_file():
    return DATA_DIR / "sample.txt"


@pytest.fixture(scope="session")
def sample_image():
    return DATA_DIR / "sample_1x1.png"


def _chunked_upload(client, headers, parent_id, file_name, content):
    response = client.post("/api/v1/upload/init", json={
        "parentId": parent_id, "fileName": file_name,
        "fileSize": len(content), "totalChunks": 1,
        "md5Hash": hashlib.md5(content).hexdigest(),
    }, headers=headers)
    session_id = response.json()["data"]["sessionId"]
    chunk = client.post(
        f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
        files={"file": (file_name, content, "text/plain")}, headers=headers,
    )
    assert chunk.json()["code"] == 200, chunk.text
    complete = client.post(f"/api/v1/upload/{session_id}/complete", headers=headers)
    assert complete.json()["code"] == 200, complete.text
    return complete.json()["data"]


@pytest.fixture
def versioned_file(client, auth_headers, uploaded_file, test_user):
    """Attach two deterministic version rows to a real uploaded object."""
    file_id = uploaded_file["fileId"]
    sql = f"""
    INSERT INTO file_versions (file_id, user_id, version_number, file_size, md5_hash, storage_path, created_at)
    SELECT id, {test_user['userId']}, version_number, file_size, md5_hash, storage_path, CURRENT_TIMESTAMP
    FROM files CROSS JOIN (VALUES (1), (2)) AS versions(version_number)
    WHERE id = {file_id}
    ON CONFLICT (file_id, version_number) DO NOTHING;
    UPDATE files SET version_count = 2 WHERE id = {file_id};
    """
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
         "-U", "bcd_user", "-d", "better_cloud_drive", "-c", sql],
        cwd=Path(__file__).resolve().parents[2], capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    versions = client.get(f"/api/v1/files/{file_id}/versions", headers=auth_headers).json()
    assert versions["code"] == 200 and len(versions["data"]) >= 2, versions
    return {**uploaded_file, "contents": (b"version-one", b"version-two")}


def _retry_create_folder(client, auth_headers, parent_id, folder_name, max_retries=3):
    """Create a folder with retry on transient errors."""
    last_error = None
    for attempt in range(max_retries):
        try:
            r = client.post("/api/v1/files/folder", json={
                "parentId": parent_id, "folderName": folder_name
            }, headers=auth_headers)
            if r.json()["code"] == 200:
                return r.json()["data"]
            last_error = r.json()
        except Exception as e:
            last_error = str(e)
        if attempt < max_retries - 1:
            time.sleep(0.2 * (attempt + 1))
            folder_name = f"{folder_name}_{attempt}"
    raise RuntimeError(f"Failed to create folder after {max_retries} attempts: {last_error}")


@pytest.fixture
def created_folder(client, auth_headers):
    """Create a temporary folder; cleanup moves it to recycle bin."""
    folder_name = f"tf_{uuid.uuid4().hex[:6]}"
    data = _retry_create_folder(client, auth_headers, None, folder_name)
    folder_id = data["id"]
    yield data
    cleanup = client.request("DELETE", "/api/v1/files", json={"fileIds": [folder_id]}, headers=auth_headers)
    assert cleanup.status_code == 200, cleanup.text


@pytest.fixture
def uploaded_file(client, auth_headers, created_folder, sample_file):
    """Upload a fresh physical object; instant upload has dedicated coverage."""
    content = sample_file.read_bytes()
    file_name = f"tf_{uuid.uuid4().hex[:6]}.txt"
    data = _chunked_upload(client, auth_headers, created_folder["id"], file_name, content)
    file_id = data.get("fileId", data.get("id"))
    assert file_id is not None, data
    yield {"fileId": file_id, "fileName": data.get("fileName", file_name), "fileSize": len(content)}

    cleanup = client.request("DELETE", "/api/v1/files", json={"fileIds": [file_id]}, headers=auth_headers)
    assert cleanup.status_code == 200, cleanup.text
