import errno
import hashlib
import threading
import time
from pathlib import Path

import httpx
import pytest

from engine import Engine
from network import DesktopError, Interrupted, ProxyAuth, client_options
from test_engine import system, request, wait, ok
from transport import LiveClient, retry_request, retry_delay


def sources(tmp_path, count):
    paths = [tmp_path / f'{i}.txt' for i in range(count)]
    for path in paths:
        path.write_bytes(b'abcdef')
    return paths


def test_file_concurrency_inside_one_batch_and_dynamic_limits(system, tmp_path):
    engine, _ = system
    engine.save_settings({'maxConcurrentUploads': 2})
    entered = [threading.Event() for _ in range(4)]
    release = [threading.Event() for _ in range(4)]
    def upload(task, node, client, child_stop, stop, generation):
        index = int(Path(node['localPath']).stem)
        entered[index].set()
        assert release[index].wait(4)
        engine._progress_check(task, node, child_stop, stop, generation)
        engine._update(task, node, 'done', bytesDone=node['bytesTotal'], progress=100)
    engine._upload = upload
    task = engine.upload_files(sources(tmp_path, 4), request(engine))
    try:
        assert entered[0].wait(2) and entered[1].wait(2)
        assert engine.active['upload'] == 2
        engine.save_settings({'maxConcurrentUploads': 1})
        release[0].set()
        assert not entered[2].wait(.15)
        release[1].set()
        assert entered[2].wait(2)
        engine.save_settings({'maxConcurrentUploads': 3})
        assert entered[3].wait(2)
    finally:
        for event in release:
            event.set()
    assert wait(engine, task)['status'] == 'done'


def test_upload_saturation_does_not_block_download(system, tmp_path):
    engine, _ = system
    engine.save_settings({'maxConcurrentUploads': 1, 'maxConcurrentDownloads': 1})
    entered, release = threading.Event(), threading.Event()
    def upload(task, node, client, child_stop, stop, generation):
        entered.set()
        assert release.wait(3)
        engine._update(task, node, 'done')
    engine._upload = upload
    task = engine.upload_files(sources(tmp_path, 1), request(engine))
    try:
        assert entered.wait(2)
        download = engine.download(tmp_path / 'download', request(engine, fileId=1, fileName='download'))
        assert wait(engine, download)['status'] == 'done'
        assert engine.active['upload'] == 1
    finally:
        release.set()
    wait(engine, task)


def test_failed_parent_blocks_descendants_but_sibling_can_finish_and_parent_can_resume(system, tmp_path):
    engine, api = system
    root = tmp_path / 'root'
    (root / 'bad').mkdir(parents=True)
    (root / 'good').mkdir()
    (root / 'bad' / 'blocked.txt').write_bytes(b'blocked')
    (root / 'good' / 'allowed.txt').write_bytes(b'allowed')
    deny = True
    def handler(req):
        if deny and req.url.path.endswith('/files/folder') and b'bad' in req.content:
            return httpx.Response(403, json={'code': 403})
        return api(req)
    engine.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(handler))
    task = wait(engine, engine.upload_files([root], request(engine), folder=True))
    assert task['status'] == 'error'
    assert [v['meta']['fileName'] for v in api.files.values()] == ['allowed.txt']
    bad = next(n for n in task['nodes'] if n['name'] == 'bad')
    assert bad['error'] == 'PERMISSION_DENIED'
    deny = False
    engine.resume(bad['id'])
    assert wait(engine, task)['status'] == 'done'
    assert sorted(v['meta']['fileName'] for v in api.files.values()) == ['allowed.txt', 'blocked.txt']


def test_pause_resume_inflight_never_runs_same_file_twice_concurrently(system, tmp_path):
    engine, _ = system
    entered, release = threading.Event(), threading.Event()
    calls, running, peak = 0, 0, 0
    def upload(task, node, client, child_stop, stop, generation):
        nonlocal calls, running, peak
        calls += 1
        running += 1
        peak = max(peak, running)
        try:
            entered.set()
            assert release.wait(3)
            engine._progress_check(task, node, child_stop, stop, generation)
            engine._update(task, node, 'done')
        finally:
            running -= 1
    engine._upload = upload
    task = engine.upload_files(sources(tmp_path, 1), request(engine))
    assert entered.wait(2)
    engine.pause(task['id'])
    for _ in range(4):
        engine.resume(task['id'])
    release.set()
    assert wait(engine, task)['status'] == 'done'
    assert peak == 1 and calls == 2


def test_safe_chunk_and_complete_response_loss_are_retried_without_new_init(system, tmp_path):
    engine, api = system
    lost = {'chunk': False, 'complete': False}
    counts = {'init': 0, 'chunk': 0, 'complete': 0}
    delays = []
    engine._wait = lambda seconds, check: (check(), delays.append(seconds))
    def handler(req):
        operation = req.url.path.rsplit('/', 1)[-1]
        if operation in counts:
            counts[operation] += 1
        result = api(req)
        if operation in lost and not lost[operation]:
            lost[operation] = True
            raise httpx.ReadTimeout('synthetic response loss')
        return result
    engine.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(handler))
    task = wait(engine, engine.upload_files(sources(tmp_path, 1), request(engine)))
    assert task['status'] == 'done'
    assert counts == {'init': 1, 'chunk': 3, 'complete': 2}
    assert len(api.files) == 1 and delays == [1, 1]


@pytest.mark.parametrize('operation', ['init', 'folder'])
def test_uncertain_creation_is_persisted_and_never_replayed_on_resume(system, tmp_path, operation):
    engine, api = system
    calls = []
    def handler(req):
        calls.append(req.url.path)
        result = api(req)
        if req.url.path.endswith('/upload/init' if operation == 'init' else '/files/folder'):
            raise httpx.ReadTimeout('synthetic post-commit response loss')
        return result
    engine.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(handler))
    if operation == 'folder':
        source = tmp_path / 'folder'
        source.mkdir()
        task = engine.upload_files([source], request(engine), folder=True)
    else:
        task = engine.upload_files(sources(tmp_path, 1), request(engine))
    result = wait(engine, task)
    assert result['nodes'][0]['error'] == 'RESULT_UNCERTAIN'
    assert engine._read('queue', [])[0]['nodes'][0]['_uncertain']
    with pytest.raises(DesktopError, match='RESULT_UNCERTAIN'):
        engine.resume(task['id'])
    restored = Engine(engine.root)
    try:
        restored.session.replace(restored.api_base, '1', 'synthetic-restored')
        with pytest.raises(DesktopError, match='RESULT_UNCERTAIN'):
            restored.resume(task['id'])
    finally:
        restored.close()
    assert len(calls) == 1


@pytest.mark.parametrize('status', [408, 429, 502, 503, 504])
def test_retry_status_budget_and_retry_after(status):
    class Client:
        calls = 0
        def request(self, *args, **kwargs):
            self.calls += 1
            return httpx.Response(status, headers={'Retry-After': '999'})
    client, delays = Client(), []
    with pytest.raises(DesktopError, match='NETWORK_RETRY_EXHAUSTED'):
        retry_request(client, 'GET', 'http://test', lambda: None, lambda seconds, check: delays.append(seconds))
    assert client.calls == 3
    assert delays == ([30, 30] if status == 429 else [1, 2])


def test_permission_and_validation_failures_are_not_retried():
    calls = []
    class Client:
        def request(self, *args, **kwargs):
            calls.append(1)
            return httpx.Response(403)
    assert retry_request(Client(), 'GET', 'http://test', lambda: None, lambda *_: pytest.fail('Must not wait')).status_code == 403
    assert len(calls) == 1


def test_retry_wait_is_interrupted_by_pause(system, tmp_path):
    engine, _ = system
    attempted = threading.Event()
    def handler(req):
        attempted.set()
        return httpx.Response(503)
    engine.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(handler))
    task = engine.download(tmp_path / 'd', request(engine, fileId=1, fileName='d'))
    assert attempted.wait(2)
    start = time.monotonic()
    engine.pause(task['id'])
    engine.futures[task['id']].result(timeout=1)
    assert time.monotonic() - start < 1
    assert engine.tasks[0]['status'] == 'paused'


def test_partial_download_reconnect_uses_actual_part_length(system, tmp_path):
    engine, _ = system
    content = b'x' * (1024 * 1024)
    ranges = []
    class Broken(httpx.SyncByteStream):
        def __iter__(self):
            yield content[:262144]
            raise httpx.ReadError('synthetic disconnect')
    def handler(req):
        if '/files/' in req.url.path:
            return ok({'fileSize': len(content), 'md5Hash': hashlib.md5(content).hexdigest()})
        value = req.headers.get('range')
        ranges.append(value)
        if value is None:
            return httpx.Response(200, stream=Broken())
        assert value == 'bytes=262144-'
        return httpx.Response(206, headers={'content-range': f'bytes 262144-{len(content)-1}/{len(content)}'}, content=content[262144:])
    engine.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(handler))
    engine._wait = lambda seconds, check: check()
    target = tmp_path / 'd'
    assert wait(engine, engine.download(target, request(engine, fileId=1, fileName='d')))['status'] == 'done'
    assert ranges == [None, 'bytes=262144-']
    assert target.read_bytes() == content


def test_aggregate_rate_limit_and_disable_wakes_waiters(system):
    engine, _ = system
    engine.save_settings({'uploadLimitBytesPerSec': 20000})
    start = time.monotonic()
    threads = [threading.Thread(target=engine._throttle, args=('upload', 3000, 0, threading.Event())) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=2)
    assert all(not thread.is_alive() for thread in threads)
    assert .27 <= time.monotonic() - start < 1.5
    engine.save_settings({'uploadLimitBytesPerSec': 1})
    waiter = threading.Thread(target=engine._throttle, args=('upload', 1000, 0, threading.Event()))
    waiter.start()
    engine.save_settings({})
    waiter.join(timeout=1)
    assert not waiter.is_alive()


def test_proxy_credentials_stay_in_memory_and_invalid_settings_rejected(system):
    engine, _ = system
    engine.save_settings({'proxyMode': 'manual', 'proxyUrl': 'http://127.0.0.1:12345', 'proxyUsername': 'synthetic-user', 'proxyPassword': 'synthetic-password'})
    stored = engine._read('settings', {})
    assert stored['proxyUsername'] == stored['proxyPassword'] == ''
    assert engine.settings['proxyUsername'] == 'synthetic-user'
    for scheme in ('http', 'https', 'socks5', 'socks5h'):
        options = client_options({**engine.settings, 'proxyUrl': f'{scheme}://127.0.0.1:12345'}, engine.api_base)
        assert options['proxy'] is not None and options['trust_env'] is False


def test_disk_full_and_file_occupied_are_actionable_and_never_publish(system, tmp_path, monkeypatch):
    engine, _ = system
    original = Path.open
    def fail(path, *args, **kwargs):
        if path.name.endswith('.part'):
            raise OSError(errno.ENOSPC, 'synthetic full disk')
        return original(path, *args, **kwargs)
    monkeypatch.setattr(Path, 'open', fail)
    target = tmp_path / 'd'
    task = wait(engine, engine.download(target, request(engine, fileId=1, fileName='d')))
    assert task['nodes'][0]['error'] == 'DISK_FULL' and not target.exists()
    assert engine._error_code(PermissionError(errno.EACCES, 'synthetic locked')) == 'FILE_UNAVAILABLE'


def test_context_capture_cannot_bind_new_work_to_later_identity(system, tmp_path):
    engine, _ = system
    context = engine.context()
    engine.clear_session()
    engine.session.replace(engine.api_base, '2', 'synthetic-new')
    with pytest.raises(DesktopError, match='SESSION_CHANGED'):
        engine.upload_files(sources(tmp_path, 1), request(engine), context=context)
    assert not engine.tasks



def test_canceling_unsent_rate_ticket_does_not_delay_other_uploads(system):
    engine, _ = system
    engine.save_settings({'uploadLimitBytesPerSec': 10000})
    stop = threading.Event()
    ended = threading.Event()
    def canceled():
        try:
            engine._throttle('upload', 1000000, 0, stop)
        except Interrupted:
            ended.set()
    thread = threading.Thread(target=canceled)
    thread.start()
    with engine.condition:
        stop.set()
        engine.condition.notify_all()
    assert ended.wait(1)
    start = time.monotonic()
    engine._throttle('upload', 1000, 0, threading.Event())
    assert time.monotonic() - start < .6
    thread.join()


def test_state_database_failure_stops_dispatch_without_publishing_files(system, tmp_path, monkeypatch):
    import sqlite3
    engine, _ = system
    original = engine._store
    def store(name, value):
        if name == 'queue':
            raise sqlite3.OperationalError('synthetic state volume full')
        return original(name, value)
    monkeypatch.setattr(engine, '_store', store)
    with pytest.raises(DesktopError, match='STATE_WRITE_FAILED'):
        engine.upload_files(sources(tmp_path, 1), request(engine))
    assert engine.state_failed and not engine.inflight


def test_download_rejects_junction_in_target_ancestors(system, tmp_path):
    import os
    import subprocess
    engine, _ = system
    external = tmp_path / 'external'
    external.mkdir()
    link = tmp_path / 'junction'
    if os.name == 'nt':
        result = subprocess.run(['cmd.exe', '/c', 'mklink', '/J', str(link), str(external)], capture_output=True)
        assert result.returncode == 0
    else:
        link.symlink_to(external, target_is_directory=True)
    try:
        with pytest.raises(DesktopError, match='INVALID_TARGET'):
            engine.download(link / 'escaped.bin', request(engine, fileId=1, fileName='escaped.bin'))
        assert not list(external.iterdir())
    finally:
        link.rmdir() if os.name == 'nt' else link.unlink()


def test_retry_bandwidth_hook_runs_only_once_per_network_attempt():
    count, charges, waits = 0, [], []
    class Client:
        def request(self, *args, **kwargs):
            nonlocal count
            count += 1
            if count < 3:
                raise httpx.ReadTimeout('synthetic')
            return httpx.Response(200)
    def wait(seconds, check):
        for _ in range(5):
            check()
        waits.append(seconds)
    retry_request(Client(), 'POST', 'http://test', lambda: None, wait, before_attempt=lambda: charges.append(1))
    assert len(charges) == count == 3 and waits == [1, 2]


@pytest.mark.windows_only
def test_windows_locked_part_is_retained_and_resume_succeeds_after_unlock(system, tmp_path):
    import msvcrt
    engine, _ = system
    original_spawn = engine._spawn
    engine._spawn = lambda _: None
    target = tmp_path / 'locked-download'
    task = engine.download(target, request(engine, fileId=1, fileName='locked-download'))
    internal = engine.tasks[0]
    part = engine._part(internal['nodes'][0])
    part.write_bytes(b'abc')
    with part.open('r+b') as locked:
        msvcrt.locking(locked.fileno(), msvcrt.LK_NBLCK, 3)
        try:
            engine._run(internal, threading.Event(), engine.session.generation)
            assert internal['nodes'][0]['error'] == 'FILE_UNAVAILABLE'
            assert part.exists() and not target.exists()
        finally:
            locked.seek(0)
            msvcrt.locking(locked.fileno(), msvcrt.LK_UNLCK, 3)
    engine._spawn = original_spawn
    engine.resume(task['id'])
    assert wait(engine, task)['status'] == 'done'
    assert target.read_bytes() == b'abcdef'


def test_failed_settings_save_preserves_previous_live_configuration(system, monkeypatch):
    import sqlite3
    engine, _ = system
    previous = dict(engine.settings)
    monkeypatch.setattr(engine, '_store', lambda *_: (_ for _ in ()).throw(sqlite3.OperationalError('synthetic storage fault')))
    with pytest.raises(DesktopError, match='SETTINGS_SAVE_FAILED'):
        engine.save_settings({'maxConcurrentUploads': 7})
    assert engine.settings == previous


def test_creation_intent_is_durable_before_request_and_returned_id_is_committed(system, tmp_path):
    engine, api = system
    checked = []
    def handler(req):
        if req.url.path.endswith('/upload/init'):
            stored = engine._read('queue', [])[0]['nodes'][0]
            assert stored['_uncertain'] is True and stored['uploadId'] is None
            checked.append('intent')
        elif req.url.path.endswith('/status'):
            stored = engine._read('queue', [])[0]['nodes'][0]
            assert stored['_uncertain'] is False and stored['uploadId'] is not None
            checked.append('identifier')
        return api(req)
    engine.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(handler))
    assert wait(engine, engine.upload_files(sources(tmp_path, 1), request(engine)))['status'] == 'done'
    assert checked == ['intent', 'identifier']


def test_directory_created_before_checkpoint_can_be_recovered_without_overwrite(system, tmp_path, monkeypatch):
    engine, api = system
    engine._spawn = lambda _: None
    target = tmp_path / 'recovered-directory'
    tree = {'id': 10, 'fileName': 'folder', 'fileType': 'folder', 'children': [{'id': 1, 'fileName': 'file', 'fileType': 'file'}]}
    task = engine.download(target, request(engine), tree=tree)
    internal = engine.tasks[0]
    original = engine._update
    def interrupted(task, node, status=None, **values):
        if status == 'done' and node['kind'] == 'folder':
            raise Interrupted()
        return original(task, node, status, **values)
    monkeypatch.setattr(engine, '_update', interrupted)
    class Client:
        check = staticmethod(lambda: None)
    with pytest.raises(Interrupted):
        engine._directory(internal, internal['nodes'][0], Client())
    assert target.is_dir() and engine._read('queue', [])[0]['nodes'][0]['_creatingDirectory']
    restored = Engine(engine.root)
    try:
        restored.session.replace(restored.api_base, '1', 'synthetic-restored')
        restored.client = lambda *args, **kwargs: httpx.Client(transport=httpx.MockTransport(api))
        restored.resume(task['id'])
        assert wait(restored, task)['status'] == 'done'
        assert (target / 'file').read_bytes() == api.content
    finally:
        restored.close()
