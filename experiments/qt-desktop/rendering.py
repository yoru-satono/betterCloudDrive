"""Raster assertion for the one-page, text-only synthetic PDF fixture.

The PDF plugin has no page text in its CDP AX tree. Detect a rendered white
page containing dark ink, rather than accepting an empty grey iframe.
"""
from PySide6.QtCore import QByteArray
from PySide6.QtGui import QImage


def pdf_page_has_ink(png):
    image = QImage.fromData(QByteArray(png)).convertToFormat(QImage.Format.Format_RGBA8888)
    if image.isNull():
        return False
    width, height, stride = image.width(), image.height(), image.bytesPerLine()
    pixels = image.constBits()
    step = 4
    columns, rows = width // step, height // step
    white = set()
    # Ignore the viewport's thin white outline: it can touch a clipped page and
    # otherwise connect the whole dark viewport to the page's bounding box.
    for y in range(1, rows - 1):
        for x in range(1, columns - 1):
            pos = y * step * stride + x * step * 4
            if min(pixels[pos], pixels[pos + 1], pixels[pos + 2]) > 235:
                white.add(y * columns + x)
    if len(white) < columns * rows * 0.12:
        return False
    largest = []
    while white:
        first = white.pop()
        stack, component = [first], [first]
        while stack:
            index = stack.pop()
            x = index % columns
            neighbors = [index - columns, index + columns]
            if x:
                neighbors.append(index - 1)
            if x < columns - 1:
                neighbors.append(index + 1)
            for neighbor in neighbors:
                if neighbor in white:
                    white.remove(neighbor)
                    stack.append(neighbor)
                    component.append(neighbor)
        if len(component) > len(largest):
            largest = component
    if len(largest) < columns * rows * 0.1:
        return False
    left = min(index % columns for index in largest) * step + 12
    right = max(index % columns for index in largest) * step - 12
    top = min(index // columns for index in largest) * step + 12
    bottom = min(max(index // columns for index in largest) * step - 12, top + height // 3)
    dark = 0
    for y in range(top, bottom):
        for x in range(left, right):
            pos = y * stride + x * 4
            if max(pixels[pos], pixels[pos + 1], pixels[pos + 2]) < 100:
                dark += 1
                if dark >= 40:
                    return True
    return False
