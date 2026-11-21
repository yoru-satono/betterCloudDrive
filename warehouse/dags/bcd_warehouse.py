"""Batch-only DAGs; no business data or credentials travel through XCom."""

from datetime import datetime, timedelta
from pathlib import Path
import subprocess
from zoneinfo import ZoneInfo

from airflow.sdk import DAG, task, Param


def execute_job(job, start=None, end=None):
    command = ["/opt/warehouse/.venv/bin/python", "-m", "bcd_warehouse", job]
    if start is not None:
        command.extend(["--start", start, "--end", end])
    result = subprocess.run(command, cwd="/opt/warehouse", capture_output=True, text=True, timeout=1200)
    if result.returncode:
        raise RuntimeError("warehouse_pipeline_failed; inspect pipeline run metadata")


for job, schedule in (("hourly", "10 * * * *"), ("snapshot", "5 0 * * *")):
    with DAG(
        dag_id=f"bcd_warehouse_{job}", schedule=schedule,
        start_date=datetime(2026, 1, 1, tzinfo=ZoneInfo("Asia/Shanghai")),
        catchup=False, max_active_runs=1, dagrun_timeout=timedelta(minutes=45),
        default_args={"retries": 1, "execution_timeout": timedelta(minutes=20)},
        tags=["betterclouddrive", "warehouse"],
    ) as dag:
        task(task_id="extract_build_validate_publish", do_xcom_push=False)(execute_job)(job)
        globals()[dag.dag_id] = dag

with DAG(
    dag_id="bcd_warehouse_backfill", schedule=None,
    start_date=datetime(2026, 1, 1, tzinfo=ZoneInfo("Asia/Shanghai")),
    catchup=False, max_active_runs=1, dagrun_timeout=timedelta(minutes=45),
    default_args={"execution_timeout": timedelta(minutes=20)},
    params={"start": Param("2026-11-20", type="string", format="date"),
            "end": Param("2026-11-20", type="string", format="date")},
    tags=["betterclouddrive", "warehouse"],
) as backfill:
    task(task_id="rebuild_retained_data", do_xcom_push=False)(execute_job)(
        "backfill", "{{ params.start }}", "{{ params.end }}")
