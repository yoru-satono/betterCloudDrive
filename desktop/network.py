"""Scoped HTTP clients. Authentication and proxy credentials are memory-only."""
from __future__ import annotations

import threading
import time
from urllib.parse import urlsplit
from urllib.request import getproxies, proxy_bypass

import httpx


class DesktopError(Exception):
    """Only stable, non-sensitive error codes cross the desktop bridge."""


class Interrupted(Exception):
    pass


def api_url(value):
    if not isinstance(value, str):
        raise DesktopError("INVALID_API_BASE")
    value = value.strip().rstrip("/")
    p = urlsplit(value)
    if (p.scheme not in ("http", "https") or not p.hostname or p.username or p.password
            or p.query or p.fragment or not p.path.endswith("/api/v1")):
        raise DesktopError("INVALID_API_BASE")
    try:
        p.port
    except ValueError:
        raise DesktopError("INVALID_API_BASE") from None
    return value


def envelope(response):
    if response.status_code == 401:
        raise DesktopError("AUTH_EXPIRED")
    if response.status_code == 403:
        raise DesktopError("PERMISSION_DENIED")
    if response.status_code >= 400:
        raise DesktopError(f"HTTP_{response.status_code}")
    try:
        value = response.json()
    except ValueError:
        raise DesktopError("INVALID_API_RESPONSE") from None
    if not isinstance(value, dict) or type(value.get("code")) is not int:
        raise DesktopError("INVALID_API_RESPONSE")
    if value.get("code") in (401, 401001, 401002):
        raise DesktopError("AUTH_EXPIRED")
    if value.get("code") in (403, 403001, 403002, 403003):
        raise DesktopError("PERMISSION_DENIED")
    if value.get("code") != 200:
        raise DesktopError(f"API_{value.get('code', 'INVALID')}")
    return value.get("data")


class ProxyAuth(httpx.Auth):
    requires_request_body = True

    def __init__(self, session, owner, generation, stop):
        self.session, self.owner, self.generation, self.stop = session, owner, generation, stop

    def sync_auth_flow(self, request):
        self.check()
        token = self.session.access(self.owner, self.generation)
        request.headers["Authorization"] = f"Bearer {token}"
        response = yield request
        # Failed authorisation precedes the operation; replay at most once.
        if response.status_code == 401:
            response.read()
            self.check()
            token = self.session.refresh(token, self.owner, self.generation, self.stop)
            request.headers["Authorization"] = f"Bearer {token}"
            self.check()
            yield request

    def check(self):
        if self.stop and self.stop.is_set():
            raise Interrupted()


class Session:
    def __init__(self, base, emit):
        self.base = api_url(base)
        self.owner, self.token = None, ""
        self.generation = 0
        self.condition = threading.Condition(threading.RLock())
        self.refresh_lock = threading.Lock()
        self.emit = emit

    def replace(self, base, owner=None, token="", invalidate=False):
        with self.condition:
            changed = base != self.base or owner != self.owner
            if changed or invalidate:
                self.generation += 1
            self.base, self.owner, self.token = base, owner, token
            self.condition.notify_all()
            return changed

    def access(self, owner, generation):
        with self.condition:
            if owner != self.owner or generation != self.generation or not self.token:
                raise DesktopError("SESSION_CHANGED")
            return self.token

    def refresh(self, previous, owner, generation, stop):
        with self.refresh_lock:
            current = self.access(owner, generation)
            if current != previous:
                return current
            self.emit("session:refresh-required", {"generation": generation})
            deadline = time.monotonic() + 20
            with self.condition:
                while self.token == previous:
                    self.access(owner, generation)
                    if stop and stop.is_set():
                        raise Interrupted()
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise DesktopError("AUTH_EXPIRED")
                    self.condition.wait(min(remaining, 0.2))
                return self.access(owner, generation)


def client_options(settings, base):
    proxy = None
    mode = settings["proxyMode"]
    if mode == "manual":
        auth = (settings["proxyUsername"], settings["proxyPassword"]) if settings["proxyUsername"] else None
        proxy = httpx.Proxy(settings["proxyUrl"], auth=auth)
    elif mode == "system":
        # urllib reads Windows Internet Settings as well as proxy environment
        # variables. Explicitly apply these to httpx, which otherwise reads only env.
        parsed = urlsplit(base)
        if not proxy_bypass(parsed.hostname):
            proxies = getproxies()
            proxy = proxies.get(parsed.scheme) or proxies.get("all")
    return dict(proxy=proxy, trust_env=False, follow_redirects=False,
                timeout=httpx.Timeout(30, connect=10))
