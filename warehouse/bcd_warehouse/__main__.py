import argparse
from datetime import date
import json

from .pipeline import run, WarehouseJobError


def main():
    parser = argparse.ArgumentParser(description="Run a BetterCloudDrive warehouse batch")
    parser.add_argument("job", choices=["hourly", "snapshot", "backfill"])
    parser.add_argument("--start", type=date.fromisoformat)
    parser.add_argument("--end", type=date.fromisoformat)
    arguments = parser.parse_args()
    if arguments.job == "backfill" and (arguments.start is None or arguments.end is None):
        parser.error("backfill requires --start and --end")
    try:
        print(json.dumps(run(arguments.job, start=arguments.start, end=arguments.end)))
    except WarehouseJobError as error:
        print(json.dumps({"status": "FAILED", "errorKey": str(error)}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
