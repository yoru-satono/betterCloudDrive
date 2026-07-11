"""External-client compatibility smoke test; rclone is mandatory when selected."""

import os
import shutil
import subprocess

import pytest

pytestmark = pytest.mark.webdav


def test_rclone_webdav_smoke(dav, test_user, backend_url, run_id, tmp_path):
    executable = shutil.which("rclone")
    assert executable, "rclone is required for the WebDAV acceptance module: https://rclone.org/install/"
    # Credentials are inherited from the authenticated fixture but rclone needs an obscured password.
    obscure = subprocess.run([executable, "obscure", "Dav-Test-456!"], capture_output=True, text=True, check=True).stdout.strip()
    env = {
        **os.environ,
        "RCLONE_CONFIG_BCD_TYPE": "webdav",
        "RCLONE_CONFIG_BCD_URL": f"{backend_url}/webdav/",
        "RCLONE_CONFIG_BCD_VENDOR": "other",
        "RCLONE_CONFIG_BCD_USER": test_user["username"],
        "RCLONE_CONFIG_BCD_PASS": obscure,
    }
    source = tmp_path / "source.txt"
    source.write_bytes(b"rclone-real-client-content")
    remote = f"bcd:rclone_{run_id}"
    commands = [
        ["mkdir", remote], ["copyto", str(source), f"{remote}/source.txt"],
        ["lsf", remote], ["moveto", f"{remote}/source.txt", f"{remote}/moved.txt"],
        ["copyto", str(source), f"{remote}/moved.txt"], ["deletefile", f"{remote}/moved.txt"],
        ["rmdir", remote],
    ]
    for args in commands:
        result = subprocess.run([executable, *args], env=env, capture_output=True, text=True)
        assert result.returncode == 0, f"rclone {' '.join(args)} failed:\n{result.stdout}\n{result.stderr}"
        if args[0] == "lsf":
            assert "source.txt" in result.stdout
