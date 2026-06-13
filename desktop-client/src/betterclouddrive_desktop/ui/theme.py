from __future__ import annotations

from PySide6.QtGui import QFont, QFontDatabase
from PySide6.QtWidgets import QApplication

BG_BASE = "#080809"
BG_SURFACE = "#0e0e10"
BG_ELEVATED = "#141416"
BG_OVERLAY = "#1a1a1e"
BORDER = "rgba(255, 255, 255, 0.08)"
BORDER_HOVER = "rgba(255, 255, 255, 0.14)"
TEXT_PRIMARY = "#f0f0f2"
TEXT_SECONDARY = "#8a8a9a"
TEXT_MUTED = "#4a4a5a"
ACCENT = "#00d4aa"
ACCENT_HOVER = "#00e8bb"
DANGER = "#ff4757"
SUCCESS = "#2ed573"
WARNING = "#ffa502"


def apply_theme(app: QApplication) -> None:
    app.setStyle("Fusion")
    font = QFont("Inter", 10)
    font.setStyleStrategy(QFont.PreferAntialias)
    app.setFont(font)
    app.setStyleSheet(STYLESHEET)


STYLESHEET = f"""
QWidget {{
    background: {BG_BASE};
    color: {TEXT_PRIMARY};
    font-size: 13px;
}}

QMainWindow, QDialog {{
    background: {BG_BASE};
}}

QFrame#sidebar, QFrame#topbar, QFrame#panel {{
    background: {BG_SURFACE};
    border: 1px solid {BORDER};
}}

QLabel#title {{
    font-size: 20px;
    font-weight: 700;
}}

QLabel#subtitle, QLabel#muted {{
    color: {TEXT_SECONDARY};
}}

QPushButton {{
    background: transparent;
    border: 1px solid {BORDER};
    border-radius: 7px;
    padding: 7px 12px;
    color: {TEXT_SECONDARY};
    font-weight: 600;
}}

QPushButton:hover {{
    background: {BG_ELEVATED};
    color: {TEXT_PRIMARY};
    border-color: {BORDER_HOVER};
}}

QPushButton#primary {{
    background: {ACCENT};
    border-color: {ACCENT};
    color: #080809;
}}

QPushButton#primary:hover {{
    background: {ACCENT_HOVER};
    border-color: {ACCENT_HOVER};
}}

QPushButton#danger {{
    color: {DANGER};
    border-color: rgba(255, 71, 87, 0.28);
}}

QPushButton#danger:hover {{
    background: rgba(255, 71, 87, 0.12);
    border-color: {DANGER};
}}

QPushButton#nav {{
    text-align: left;
    border: none;
    border-radius: 7px;
    padding: 9px 12px;
}}

QPushButton#nav[active="true"] {{
    background: rgba(0, 212, 170, 0.12);
    color: {ACCENT};
}}

QLineEdit, QSpinBox, QDateTimeEdit, QComboBox, QTextEdit, QPlainTextEdit {{
    background: {BG_OVERLAY};
    border: 1px solid {BORDER};
    border-radius: 7px;
    padding: 7px 9px;
    color: {TEXT_PRIMARY};
    selection-background-color: {ACCENT};
    selection-color: #080809;
}}

QLineEdit:focus, QSpinBox:focus, QDateTimeEdit:focus, QComboBox:focus, QTextEdit:focus, QPlainTextEdit:focus {{
    border-color: rgba(0, 212, 170, 0.55);
}}

QTableWidget {{
    background: {BG_BASE};
    alternate-background-color: {BG_SURFACE};
    border: 1px solid {BORDER};
    border-radius: 8px;
    gridline-color: {BORDER};
}}

QHeaderView::section {{
    background: {BG_ELEVATED};
    color: {TEXT_MUTED};
    border: none;
    border-bottom: 1px solid {BORDER};
    padding: 8px;
    font-size: 11px;
    font-weight: 700;
}}

QTableWidget::item {{
    padding: 8px;
    border-bottom: 1px solid {BORDER};
}}

QTableWidget::item:selected {{
    background: rgba(0, 212, 170, 0.18);
    color: {TEXT_PRIMARY};
}}

QListWidget {{
    background: {BG_BASE};
    border: 1px solid {BORDER};
    border-radius: 8px;
    outline: none;
}}

QListWidget::item {{
    padding: 10px;
    border-radius: 7px;
}}

QListWidget::item:selected {{
    background: rgba(0, 212, 170, 0.16);
    color: {TEXT_PRIMARY};
}}

QProgressBar {{
    background: {BG_OVERLAY};
    border: 1px solid {BORDER};
    border-radius: 5px;
    text-align: center;
    color: {TEXT_SECONDARY};
}}

QProgressBar::chunk {{
    background: {ACCENT};
    border-radius: 5px;
}}

QScrollBar:vertical, QScrollBar:horizontal {{
    background: transparent;
    width: 8px;
    height: 8px;
}}

QScrollBar::handle {{
    background: rgba(255,255,255,0.16);
    border-radius: 4px;
}}

QMenu {{
    background: {BG_OVERLAY};
    border: 1px solid {BORDER_HOVER};
    border-radius: 7px;
    padding: 4px;
}}

QMenu::item {{
    padding: 7px 18px;
    border-radius: 5px;
}}

QMenu::item:selected {{
    background: {BG_ELEVATED};
}}
"""


def load_font(path: str) -> None:
    QFontDatabase.addApplicationFont(path)

