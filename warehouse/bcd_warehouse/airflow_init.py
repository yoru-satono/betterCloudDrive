"""Create the local-only Airflow login file without logging its password."""

import json
import os
from pathlib import Path


def main():
    path = Path(os.environ["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE"])
    path.parent.mkdir(parents=True, exist_ok=True)
    password = os.environ["WAREHOUSE_AIRFLOW_ADMIN_PASSWORD"]
    if len(password) < 24:
        raise ValueError("Airflow local password must have at least 24 characters")
    with path.open("w", encoding="utf-8") as handle:
        json.dump({"warehouse-admin": password}, handle)
    path.chmod(0o600)


if __name__ == "__main__":
    main()
