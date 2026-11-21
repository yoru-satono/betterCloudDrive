from datetime import date, timedelta
import pytest

from bcd_warehouse.pipeline import validate_backfill, WarehouseJobError

TODAY = date(2026, 11, 20)


def test_backfill_accepts_only_real_retained_snapshots():
    days = [TODAY - timedelta(days=index) for index in range(7)]
    validate_backfill(TODAY - timedelta(days=6), TODAY, days, TODAY)
    with pytest.raises(WarehouseJobError, match="historical_snapshot_unavailable"):
        validate_backfill(TODAY - timedelta(days=7), TODAY, days, TODAY)


@pytest.mark.parametrize("start,end", [
    (TODAY, TODAY - timedelta(days=1)),
    (TODAY - timedelta(days=31), TODAY),
    (TODAY - timedelta(days=90), TODAY - timedelta(days=90)),
    (TODAY, TODAY + timedelta(days=1)),
])
def test_invalid_ranges_are_rejected_before_reprocessing(start, end):
    with pytest.raises(WarehouseJobError, match="invalid_backfill_range"):
        validate_backfill(start, end, [], TODAY)
