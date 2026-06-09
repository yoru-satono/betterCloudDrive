"""Test-scoped fixtures for the backend-test project."""

import hashlib
import pytest
import time
import uuid
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"


@pytest.fixture(scope="session")
def sample_file():
    return DATA_DIR / "sample.txt"


@pytest.fixture(scope="session")
def sample_image():
    return DATA_DIR / "sample_1x1.png"


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
    try:
        client.request("DELETE", "/api/v1/files", json={"fileIds": [folder_id]}, headers=auth_headers)
    except Exception:
        pass


@pytest.fixture
def uploaded_file(client, auth_headers, created_folder, sample_file):
    """Upload a small file via instant upload (or chunked fallback). Retries on failure."""
    file_size = sample_file.stat().st_size
    file_name = f"tf_{uuid.uuid4().hex[:6]}.txt"
    md5 = hashlib.md5(sample_file.read_bytes()).hexdigest()

    last_error = None
    for attempt in range(3):
        try:
            r = client.post("/api/v1/upload/instant", json={
                "parentId": created_folder["id"],
                "fileName": file_name,
                "fileSize": file_size,
                "md5Hash": md5,
            }, headers=auth_headers)
            if r.json()["code"] == 419010:
                # Fallback: chunked upload
                r = client.post("/api/v1/upload/init", json={
                    "parentId": created_folder["id"],
                    "fileName": file_name,
                    "fileSize": file_size,
                    "totalChunks": 1,
                }, headers=auth_headers)
                session_id = r.json()["data"]["sessionId"]
                client.post(
                    f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
                    files={"file": sample_file.open("rb")},
                    headers=auth_headers,
                )
                r = client.post(f"/api/v1/upload/{session_id}/complete", headers=auth_headers)

            if r.json()["code"] == 200:
                data = r.json()["data"]
                file_id = data.get("fileId", data.get("id"))
                yield {"fileId": file_id, "fileName": data.get("fileName", file_name), "fileSize": file_size}
                break
            last_error = r.json()
        except Exception as e:
            last_error = str(e)
        if attempt < 2:
            time.sleep(0.2 * (attempt + 1))
            file_name = f"tf_{uuid.uuid4().hex[:6]}.txt"
    else:
        raise RuntimeError(f"Failed to upload file after 3 attempts: {last_error}")

    # Cleanup
    try:
        client.request("DELETE", "/api/v1/files", json={"fileIds": [file_id]}, headers=auth_headers)
    except Exception:
        pass
