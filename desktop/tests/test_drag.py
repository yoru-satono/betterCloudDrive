import pytest

from PySide6.QtCore import QEvent, QPoint, QPointF, QRectF, Qt
from PySide6.QtGui import QMouseEvent
from PySide6.QtWidgets import QApplication, QWidget

from app import DesktopView
from test_host import qt


class Window(QWidget):
    def __init__(self):
        super().__init__()
        self.moves = []
        self.setGeometry(200, 100, 800, 600)

    def move(self, position):
        self.moves.append(QPoint(position))
        super().move(position)


@pytest.fixture
def view(qt):
    window = Window()
    view = DesktopView(window)
    view.setGeometry(0, 0, 800, 600)
    view.drag_regions = [QRectF(0, 0, 600, 36)]
    yield view, window
    view.deleteLater()
    window.deleteLater()
    qt.processEvents()


def mouse(widget, kind, point, *, buttons=Qt.MouseButton.NoButton, global_point=None):
    point = QPoint(*point)
    global_point = global_point or widget.mapToGlobal(point)
    button = Qt.MouseButton.NoButton if kind == QEvent.Type.MouseMove else Qt.MouseButton.LeftButton
    event = QMouseEvent(kind, QPointF(point), QPointF(global_point), button, buttons, Qt.KeyboardModifier.NoModifier)
    QApplication.sendEvent(widget, event)


@pytest.mark.parametrize("jitter", [0, 1])
def test_titlebar_click_does_not_move_window(view, jitter):
    widget, window = view
    before = window.pos()
    for _ in range(3):
        mouse(widget, QEvent.Type.MouseButtonPress, (30, 15), buttons=Qt.MouseButton.LeftButton)
        mouse(widget, QEvent.Type.MouseMove, (30 + jitter, 15), buttons=Qt.MouseButton.LeftButton)
        mouse(widget, QEvent.Type.MouseButtonRelease, (30 + jitter, 15))
    assert window.moves == []
    assert window.pos() == before


def test_page_click_and_controls_outside_view_do_not_move_window(view):
    widget, window = view
    mouse(widget, QEvent.Type.MouseButtonPress, (100, 200), buttons=Qt.MouseButton.LeftButton)
    mouse(widget, QEvent.Type.MouseButtonRelease, (100, 200))
    other = QWidget(window)
    mouse(other, QEvent.Type.MouseButtonPress, (20, 15), buttons=Qt.MouseButton.LeftButton)
    mouse(other, QEvent.Type.MouseButtonRelease, (20, 15))
    assert window.moves == []


def test_real_drag_moves_once_and_release_does_not_repeat_geometry_change(view):
    widget, window = view
    start = widget.mapToGlobal(QPoint(30, 15))
    end = start + QPoint(100, 50)
    before = window.pos()
    mouse(widget, QEvent.Type.MouseButtonPress, (30, 15), buttons=Qt.MouseButton.LeftButton)
    mouse(widget, QEvent.Type.MouseMove, (130, 65), buttons=Qt.MouseButton.LeftButton, global_point=end)
    release = widget.mapFromGlobal(end)
    mouse(widget, QEvent.Type.MouseButtonRelease, (release.x(), release.y()), global_point=end)
    assert window.pos() == before + QPoint(100, 50)
    assert window.moves == [before + QPoint(100, 50)]


def test_deactivation_clears_interrupted_drag(view):
    widget, window = view
    mouse(widget, QEvent.Type.MouseButtonPress, (30, 15), buttons=Qt.MouseButton.LeftButton)
    QApplication.sendEvent(window, QEvent(QEvent.Type.WindowDeactivate))
    mouse(widget, QEvent.Type.MouseButtonRelease, (300, 200))
    assert window.moves == []
