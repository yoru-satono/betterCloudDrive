"""Per-request proxy snapshots and bounded replay of idempotent operations."""
from contextlib import contextmanager
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

import httpx

from network import DesktopError

TRANSIENT = (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError, httpx.ProxyError)
RETRY_STATUS = {408, 429, 502, 503, 504}


class LiveClient:
    def __init__(self, factory):
        self.factory = factory

    def request(self, method, url, **kwargs):
        with self.factory() as client:
            return client.request(method, url, **kwargs)

    @contextmanager
    def stream(self, method, url, **kwargs):
        with self.factory() as client:
            with client.stream(method, url, **kwargs) as response:
                yield response


def retry_delay(response, attempt):
    if response is not None and response.status_code == 429:
        value = response.headers.get('Retry-After', '')
        try:
            seconds = float(value)
        except ValueError:
            try:
                seconds = (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds()
            except (ValueError, TypeError, OverflowError):
                seconds = 2 ** attempt
        return min(30, max(0, seconds))
    return 2 ** attempt


def retry_request(client, method, url, check, wait, before_attempt=None, **kwargs):
    for attempt in range(3):
        check()
        if before_attempt:
            before_attempt()
            check()
        response = None
        try:
            response = client.request(method, url, **kwargs)
            if response.status_code not in RETRY_STATUS:
                return response
        except TRANSIENT:
            if attempt == 2:
                raise DesktopError('NETWORK_RETRY_EXHAUSTED') from None
        if attempt == 2:
            raise DesktopError('NETWORK_RETRY_EXHAUSTED')
        wait(retry_delay(response, attempt), check)
