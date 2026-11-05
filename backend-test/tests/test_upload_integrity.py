"""Upload integrity contracts against the real API and object storage."""

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import uuid

import pytest
from tests.helpers.assert_helper import assert_api_error, assert_api_ok
from tests.test_teams import create_team

pytestmark = pytest.mark.security


@pytest.fixture(params=[False, True], ids=["personal", "team"])
def upload_base(request, client, auth_headers):
    if request.param:
        team = create_team(client, auth_headers)
        return f"/api/v1/teams/{team['id']}/upload"
    return "/api/v1/upload"


@dataclass(frozen=True)
class Attempt:
    session_id: str
    content: bytes

    def __str__(self):
        return self.session_id


def initialize(client, headers, base, content=None, digest=None):
    content = uuid.uuid4().hex.encode() if content is None else content
    result = assert_api_ok(client.post(f"{base}/init", headers=headers, json={
        "parentId": None, "fileName": f"integrity_{uuid.uuid4().hex}.bin", "fileSize": len(content),
        "md5Hash": digest if digest is not None else hashlib.md5(content).hexdigest(),
    }))
    assert result["mode"] == "MULTIPART"
    assert set(result) == {"mode", "sessionId", "chunkSize", "totalChunks"}
    assert result["totalChunks"] == (len(content) + result["chunkSize"] - 1) // result["chunkSize"]
    return Attempt(result["sessionId"], content)


def send(client, headers, base, session, content=None, digest=None):
    content = session.content if content is None else content
    return client.post(f"{base}/{session}/chunk", headers=headers,
                       params={"chunkNumber": 0, "chunkMd5": digest or hashlib.md5(content).hexdigest()},
                       files={"file": ("chunk.bin", content)})


def test_requires_well_formed_digests_and_computes_shape(client, auth_headers, upload_base):
    for digest in [None, "", "short", "z" * 32]:
        assert_api_error(client.post(f"{upload_base}/init", headers=auth_headers, json={
            "fileName": "invalid.bin", "fileSize": 5,  "md5Hash": digest,
        }), 400)
    assert_api_error(client.post(f"{upload_base}/init", headers=auth_headers, json={
        "fileName": "invalid.bin", "fileSize": -1, "md5Hash": "a" * 32,
    }), 400)
    session = initialize(client, auth_headers, upload_base)
    assert_api_error(client.post(f"{upload_base}/{session}/chunk", headers=auth_headers,
                                params={"chunkNumber": 0}, files={"file": ("part", b"hello")}), 400)
    assert_api_error(send(client, auth_headers, upload_base, session, digest="invalid"), 400)


def test_bad_chunk_preserves_missing_status_then_accepts_correct_retry(client, auth_headers, upload_base):
    session = initialize(client, auth_headers, upload_base)
    assert_api_error(send(client, auth_headers, upload_base, session, digest="a" * 32), 419007)
    assert_api_error(send(client, auth_headers, upload_base, session, content=b"four"), 419017)
    status = assert_api_ok(client.get(f"{upload_base}/{session}/status", headers=auth_headers))
    assert status["missingChunks"] == [0]
    assert status["uploadedChunks"] == 0
    assert_api_ok(send(client, auth_headers, upload_base, session, digest=hashlib.md5(session.content).hexdigest().upper()))
    assert_api_error(send(client, auth_headers, upload_base, session, content=b"x" * len(session.content)), 419007)
    completed = assert_api_ok(client.post(f"{upload_base}/{session}/complete", headers=auth_headers))
    retried = assert_api_ok(client.post(f"{upload_base}/{session}/complete", headers=auth_headers))
    assert retried["fileId"] == completed["fileId"]
    finished = assert_api_ok(client.get(f"{upload_base}/{session}/status", headers=auth_headers))
    assert finished["missingChunks"] == []
    assert finished["uploadedChunks"] == 1


def test_wrong_whole_digest_does_not_complete_or_destroy_resume_data(client, auth_headers, upload_base):
    session = initialize(client, auth_headers, upload_base, digest="a" * 32)
    assert_api_ok(send(client, auth_headers, upload_base, session))
    for _ in range(2):
        assert_api_error(client.post(f"{upload_base}/{session}/complete", headers=auth_headers), 419016)
    status = assert_api_ok(client.get(f"{upload_base}/{session}/status", headers=auth_headers))
    assert status["missingChunks"] == []
    assert_api_ok(send(client, auth_headers, upload_base, session))  # stored part still exists
    assert_api_ok(client.post(f"{upload_base}/{session}/cancel", headers=auth_headers))


def test_concurrent_duplicate_chunk_is_counted_once(client, auth_headers, upload_base):
    session = initialize(client, auth_headers, upload_base)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: send(client, auth_headers, upload_base, session), range(2)))
    for result in results:
        assert_api_ok(result)
    status = assert_api_ok(client.get(f"{upload_base}/{session}/status", headers=auth_headers))
    assert status["uploadedChunks"] == 1
    assert status["missingChunks"] == []
    assert_api_ok(client.post(f"{upload_base}/{session}/complete", headers=auth_headers))


def test_zero_bytes_still_verify_the_whole_digest(client, auth_headers, upload_base):
    invalid = initialize(client, auth_headers, upload_base, content=b"", digest="a" * 32)
    assert_api_error(client.post(f"{upload_base}/{invalid}/complete", headers=auth_headers), 419016)
    result = assert_api_ok(client.post(f"{upload_base}/init", headers=auth_headers, json={
        "fileName": f"empty-{uuid.uuid4().hex}", "fileSize": 0, "md5Hash": hashlib.md5(b"").hexdigest(),
    }))
    if result["mode"] == "INSTANT":
        assert set(result) == {"mode", "fileId"}
    else:
        assert result["mode"] == "MULTIPART" and result["totalChunks"] == 0
        assert_api_ok(client.post(f"{upload_base}/{result['sessionId']}/complete", headers=auth_headers))


def test_multiple_parts_produce_the_exact_downloaded_content(client, auth_headers, upload_base):
    content = bytes(range(251)) * ((5242880 + 17) // 251 + 1)
    content = content[:5242880] + uuid.uuid4().bytes + b"!"
    session = initialize(client, auth_headers, upload_base, content)
    for number, offset in enumerate(range(0, len(content), 5242880)):
        part = content[offset:offset + 5242880]
        assert_api_ok(client.post(f"{upload_base}/{session}/chunk", headers=auth_headers,
                                  params={"chunkNumber": number, "chunkMd5": hashlib.md5(part).hexdigest()},
                                  files={"file": ("part.bin", part)}))
    completed = assert_api_ok(client.post(f"{upload_base}/{session}/complete", headers=auth_headers))
    download = client.get(f"{upload_base.removesuffix('/upload')}/download/{completed['fileId']}", headers=auth_headers)
    assert download.status_code == 200
    assert len(download.content) == len(content)
    assert hashlib.md5(download.content).digest() == hashlib.md5(content).digest()


def test_init_dedup_result_and_removed_instant_route(client, auth_headers, upload_base):
    session = initialize(client, auth_headers, upload_base)
    assert_api_ok(send(client, auth_headers, upload_base, session))
    assert_api_ok(client.post(f"{upload_base}/{session}/complete", headers=auth_headers))
    result = assert_api_ok(client.post(f"{upload_base}/init", headers=auth_headers, json={
        "fileName": f"dedup-{uuid.uuid4().hex}", "fileSize": len(session.content),
        "md5Hash": hashlib.md5(session.content).hexdigest().upper(),
    }))
    assert result["mode"] == "INSTANT" and set(result) == {"mode", "fileId"}
    removed = client.post(f"{upload_base}/instant", headers=auth_headers, json={})
    assert removed.status_code == 404
    assert_api_error(removed, 404)
