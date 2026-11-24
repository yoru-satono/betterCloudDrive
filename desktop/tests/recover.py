"""Recover cleanup for interrupted runs, restricted to exact run-owned accounts.

Local development only: uses Compose PostgreSQL to identify fixture accounts,
then their normal APIs for cleanup. No database deletes or global test cleanup.
"""
import argparse
import base64
import json
import re
import shutil
import subprocess
import time
import uuid
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from live_support import Api, env_values, jwt

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
ROOT = REPO / "test-results" / "desktop"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", action="append", required=True, help="Exact 10-character run ID; repeat explicitly")
    args = parser.parse_args()
    for identity in args.run:
        if not re.fullmatch(r"[0-9a-f]{10}", identity) or not (ROOT / identity / "results.json").exists():
            parser.error("Unknown or invalid run ID")
    values = env_values(REPO)
    db = {}
    for line in (REPO / "config/env-files/database.env").read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            db[key.strip()] = value.strip().strip("\"'")
    names = ["e2e_qtd_" + identity + "_" + suffix for identity in args.run for suffix in ("owner", "viewer")]
    sql = "SELECT id,username FROM users WHERE username IN (" + ",".join("'" + name + "'" for name in names) + ") ORDER BY id"
    command = ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", db["POSTGRES_USER"], "-d", db["POSTGRES_DB"], "-At", "-c", sql]
    process = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
    if process.returncode:
        raise RuntimeError("RUN_ACCOUNT_LOOKUP_FAILED")
    users = process.stdout.splitlines()
    session_sql = "SELECT user_id,id,COALESCE(team_id::text,'') FROM upload_sessions WHERE status=1 AND user_id IN (SELECT id FROM users WHERE username IN (" + ",".join("'" + name + "'" for name in names) + "))"
    sessions = subprocess.run([*command[:-1], session_sql], cwd=REPO, text=True, capture_output=True)
    if sessions.returncode:
        raise RuntimeError("RUN_SESSION_LOOKUP_FAILED")
    by_user = {}
    for line in sessions.stdout.splitlines():
        owner, identity, team = line.split("|", 2)
        by_user.setdefault(owner, []).append((identity, team))
    summary = {"runs": args.run, "cleanedAccounts": 0, "cancelledUploads": 0, "errors": []}
    for line in users:
        user_id, username = line.split("|", 1)
        if username not in names:
            raise RuntimeError("UNEXPECTED_CLEANUP_ACCOUNT")
        token = jwt({"sub": user_id, "username": username, "role": "ROLE_USER", "jti": uuid.uuid4().hex,
                     "iat": int(time.time()), "exp": int(time.time()) + 300}, base64.b64decode(values["JWT_ACCESS_SECRET"]))
        api = Api("recovery")
        user = {"accessToken": token}
        try:
            for session, team in by_user.get(user_id, []):
                base = f"/teams/{int(team)}" if team else ""
                api.call("POST", base + "/upload/" + session + "/cancel", user)
                summary["cancelledUploads"] += 1
            # Fixture accounts have a unique random password and no user data.
            files = api.call("GET", "/files", user, params={"size": 100})["records"]
            if files:
                api.call("DELETE", "/files", user, json={"fileIds": [f["id"] for f in files]})
            trash = api.call("GET", "/recycle-bin", user, params={"size": 100})["records"]
            for file in trash:
                api.call("DELETE", "/recycle-bin/" + str(file["id"]), user)
            teams = api.call("GET", "/teams", user, params={"size": 100})["records"]
            for team in teams:
                if str(team.get("ownerId")) == user_id and team.get("name", "") in ("qtd_" + run for run in args.run):
                    api.call("DELETE", "/teams/" + str(team["id"]), user)
            api.call("POST", "/auth/logout", user)
            summary["cleanedAccounts"] += 1
        except Exception as error:
            summary["errors"].append({"userId": int(user_id), "type": type(error).__name__})
        finally:
            api.client.close()
    # Clear browser storage left by interrupted runs. Never follow a symlink or
    # recurse outside a marked, explicitly selected run profile.
    for identity in args.run:
        profile = ROOT / identity / "profile"
        if (profile / ".bcd-qt-desktop").exists():
            for name in ("web-storage", "cache"):
                target = profile / name
                if target.is_symlink() or not target.resolve().is_relative_to((ROOT / identity).resolve()):
                    raise RuntimeError("UNSAFE_PROFILE_PATH")
                if target.exists():
                    shutil.rmtree(target)
    report = ROOT / ("cleanup-" + uuid.uuid4().hex[:10] + ".json")
    report.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Recovered accounts:", summary["cleanedAccounts"], "errors:", len(summary["errors"]))
    print("Results:", report)
    return bool(summary["errors"])


if __name__ == "__main__":
    raise SystemExit(main())
