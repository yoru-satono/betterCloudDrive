"""Tests for /api/v1/auth endpoints (register, login, refresh, logout, me, email, password)."""

import uuid
from tests.helpers.assert_helper import assert_api_ok, assert_api_error


class TestRegister:
    def test_register_success(self, client):
        username = f"reg_{uuid.uuid4().hex[:8]}"
        r = client.post("/api/v1/auth/register", json={
            "username": username, "password": "Test123!", "email": f"{username}@test.com"
        })
        data = assert_api_ok(r)
        assert data["userId"] is not None
        assert data["username"] == username

    def test_register_duplicate_username(self, client, test_user):
        r = client.post("/api/v1/auth/register", json={
            "username": test_user["username"], "password": "Test123!"
        })
        assert_api_error(r, 409)

    def test_register_short_username(self, client):
        r = client.post("/api/v1/auth/register", json={
            "username": "ab", "password": "Test123!"
        })
        assert_api_error(r, 400)

    def test_register_short_password(self, client):
        r = client.post("/api/v1/auth/register", json={
            "username": f"u_{uuid.uuid4().hex[:6]}", "password": "Ab1"
        })
        assert_api_error(r, 400)

    def test_register_weak_password_no_uppercase(self, client):
        r = client.post("/api/v1/auth/register", json={
            "username": f"u_{uuid.uuid4().hex[:6]}", "password": "abcdef1"
        })
        assert_api_error(r, 400)

    def test_register_weak_password_no_digit(self, client):
        r = client.post("/api/v1/auth/register", json={
            "username": f"u_{uuid.uuid4().hex[:6]}", "password": "Abcdefgh"
        })
        assert_api_error(r, 400)

    def test_register_invalid_email(self, client):
        r = client.post("/api/v1/auth/register", json={
            "username": f"u_{uuid.uuid4().hex[:6]}", "password": "Test123!", "email": "not-an-email"
        })
        assert_api_error(r, 400)

    def test_register_missing_username(self, client):
        r = client.post("/api/v1/auth/register", json={"password": "Test123!"})
        assert_api_error(r, 400)


class TestLogin:
    def test_login_success(self, client, test_user):
        r = client.post("/api/v1/auth/login", json={
            "username": test_user["username"], "password": test_user["password"]
        })
        data = assert_api_ok(r)
        assert "accessToken" in data
        assert "refreshToken" in data
        assert data["expiresIn"] > 0

    def test_login_wrong_password(self, client, test_user):
        r = client.post("/api/v1/auth/login", json={
            "username": test_user["username"], "password": "WrongPass1"
        })
        assert_api_error(r, 401)

    def test_login_nonexistent_user(self, client):
        r = client.post("/api/v1/auth/login", json={
            "username": "no_such_user_42", "password": "Test123!"
        })
        assert_api_error(r, 401)

    def test_login_missing_password(self, client):
        r = client.post("/api/v1/auth/login", json={"username": "someone"})
        assert_api_error(r, 400)


class TestRefresh:
    def test_refresh_token(self, client, test_user):
        r = client.post("/api/v1/auth/refresh", json={
            "refreshToken": test_user["refreshToken"]
        })
        data = assert_api_ok(r)
        assert "accessToken" in data
        assert "refreshToken" in data

    def test_refresh_with_invalid_token(self, client):
        r = client.post("/api/v1/auth/refresh", json={"refreshToken": "invalid.token.here"})
        # Should return error (signature validation fails)
        assert r.json()["code"] != 200


class TestLogout:
    def test_logout_then_access(self, client, test_user):
        # Logout with a fresh access token
        r_login = client.post("/api/v1/auth/login", json={
            "username": test_user["username"], "password": test_user["password"]
        })
        token = r_login.json()["data"]["accessToken"]
        headers = {"Authorization": f"Bearer {token}"}

        client.post("/api/v1/auth/logout", headers=headers)
        r = client.get("/api/v1/auth/me", headers=headers)
        # After logout, token is blacklisted — should NOT succeed
        assert r.status_code != 200


class TestMe:
    def test_me_authenticated(self, client, auth_headers, test_user):
        r = client.get("/api/v1/auth/me", headers=auth_headers)
        data = assert_api_ok(r)
        assert data["username"] == test_user["username"]

    def test_me_no_auth(self, client):
        r = client.get("/api/v1/auth/me")
        # Unauthorized — Spring Security returns 403 (or 401) without JSON body
        assert r.status_code in (401, 403)


class TestEmailVerification:
    def test_send_verification_code(self, client, auth_headers, test_user):
        r = client.post("/api/v1/auth/verify-email/send", headers=auth_headers)
        # Should succeed since test_user has an email
        assert_api_ok(r)

    def test_send_verification_already_verified(self, client, auth_headers):
        r = client.post("/api/v1/auth/verify-email/send", headers=auth_headers)
        # May succeed or fail — just ensure no 500
        assert r.status_code < 500

    def test_verify_email_wrong_code(self, client, auth_headers):
        r = client.post("/api/v1/auth/verify-email/confirm", json={"code": "000000"}, headers=auth_headers)
        assert_api_error(r, 419013)


class TestPasswordReset:
    def test_forgot_password(self, client, test_user):
        r = client.post("/api/v1/auth/forgot-password", json={"email": test_user["email"]})
        assert_api_ok(r)  # Always returns 200

    def test_forgot_password_nonexistent(self, client):
        r = client.post("/api/v1/auth/forgot-password", json={"email": "no@user.local"})
        assert_api_ok(r)  # Still 200 (security)

    def test_forgot_password_invalid_email(self, client):
        r = client.post("/api/v1/auth/forgot-password", json={"email": "bad-format"})
        assert_api_error(r, 400)

    def test_reset_password_wrong_code(self, client, test_user):
        r = client.post("/api/v1/auth/reset-password", json={
            "email": test_user["email"], "code": "000000", "newPassword": "NewPass456!"
        })
        assert_api_error(r, 419013)
