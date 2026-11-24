from PySide6.QtCore import QByteArray, QBuffer, QIODevice, QRect
from PySide6.QtGui import QColor, QImage, QPainter

from rendering import pdf_page_has_ink


def raster(page, ink):
    image = QImage(400, 400, QImage.Format.Format_RGBA8888)
    image.fill(QColor("#333333"))
    painter = QPainter(image)
    if page:
        painter.fillRect(QRect(120, 80, 260, 310), QColor("white"))
    if ink:
        for x in range(160, 300, 15):
            painter.fillRect(QRect(x, 120, 7, 15), QColor("black"))
    painter.end()
    data = QByteArray()
    buffer = QBuffer(data)
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    image.save(buffer, "PNG")
    return bytes(data)


def test_pdf_loading_placeholder_is_not_a_rendered_page():
    assert not pdf_page_has_ink(raster(False, False))


def test_blank_pdf_page_is_not_the_text_fixture():
    assert not pdf_page_has_ink(raster(True, False))


def test_visible_pdf_page_contains_fixture_ink():
    assert pdf_page_has_ink(raster(True, True))


def test_white_viewport_outline_does_not_turn_background_into_ink():
    image = QImage(400, 400, QImage.Format.Format_RGBA8888)
    image.fill(QColor("#333333"))
    painter = QPainter(image)
    painter.setPen(QColor("white"))
    painter.drawRect(0, 0, 399, 399)
    painter.fillRect(QRect(8, 0, 260, 310), QColor("white"))
    painter.end()
    data = QByteArray()
    buffer = QBuffer(data)
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    image.save(buffer, "PNG")
    assert not pdf_page_has_ink(bytes(data))
