from __future__ import annotations

import inspect
from collections.abc import Callable
from typing import Any

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal, Slot


class TaskSignals(QObject):
    finished = Signal(object)
    failed = Signal(str)
    progress = Signal(float, str)


class TaskWorker(QRunnable):
    def __init__(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> None:
        super().__init__()
        self.fn = fn
        self.args = args
        self.kwargs = kwargs
        self.signals = TaskSignals()

    @Slot()
    def run(self) -> None:
        try:
            if "on_progress" in inspect.signature(self.fn).parameters and "on_progress" not in self.kwargs:
                self.kwargs["on_progress"] = self.signals.progress.emit
            result = self.fn(*self.args, **self.kwargs)
        except Exception as exc:
            self.signals.failed.emit(str(exc))
        else:
            self.signals.finished.emit(result)


def run_in_thread(
    fn: Callable[..., Any],
    on_finished: Callable[[Any], None] | None = None,
    on_failed: Callable[[str], None] | None = None,
    on_progress: Callable[[float, str], None] | None = None,
    *args: Any,
    **kwargs: Any,
) -> TaskWorker:
    worker = TaskWorker(fn, *args, **kwargs)
    if on_finished:
        worker.signals.finished.connect(on_finished)
    if on_failed:
        worker.signals.failed.connect(on_failed)
    if on_progress:
        worker.signals.progress.connect(on_progress)
    QThreadPool.globalInstance().start(worker)
    return worker
