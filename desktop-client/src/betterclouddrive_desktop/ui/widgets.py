from __future__ import annotations

from collections.abc import Callable
from typing import Any

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QComboBox,
    QDateTimeEdit,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)


def button(text: str, kind: str | None = None) -> QPushButton:
    btn = QPushButton(text)
    if kind:
        btn.setObjectName(kind)
    btn.setCursor(Qt.PointingHandCursor)
    return btn


def title(text: str) -> QLabel:
    label = QLabel(text)
    label.setObjectName("title")
    return label


def muted(text: str) -> QLabel:
    label = QLabel(text)
    label.setObjectName("muted")
    return label


def show_error(parent: QWidget, message: str) -> None:
    QMessageBox.critical(parent, "操作失败", message)


def show_info(parent: QWidget, message: str) -> None:
    QMessageBox.information(parent, "提示", message)


def confirm(parent: QWidget, message: str, title_text: str = "确认") -> bool:
    return QMessageBox.question(parent, title_text, message, QMessageBox.Yes | QMessageBox.No) == QMessageBox.Yes


class TextInputDialog(QDialog):
    def __init__(self, parent: QWidget, title_text: str, label: str, value: str = "", password: bool = False) -> None:
        super().__init__(parent)
        self.setWindowTitle(title_text)
        self.input = QLineEdit(value)
        if password:
            self.input.setEchoMode(QLineEdit.Password)
        layout = QVBoxLayout(self)
        form = QFormLayout()
        form.addRow(label, self.input)
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    @property
    def text(self) -> str:
        return self.input.text().strip()


class ShareDialog(QDialog):
    def __init__(self, parent: QWidget, file_name: str, password_generator: Callable[[int], str]) -> None:
        super().__init__(parent)
        self.password_generator = password_generator
        self.setWindowTitle(f"分享 {file_name}")
        self.max_visits = QSpinBox()
        self.max_visits.setRange(0, 1_000_000)
        self.max_visits.setSpecialValueText("不限")
        self.password_mode = QComboBox()
        self.password_mode.addItems(["不设密码", "手动输入", "自动生成 4 位", "自动生成 8 位"])
        self.password = QLineEdit()
        self.password.setPlaceholderText("4-16 位，留空表示无密码")
        self.notify_email = QLineEdit()
        self.notify_email.setPlaceholderText("可选")

        layout = QVBoxLayout(self)
        form = QFormLayout()
        form.addRow("访问次数", self.max_visits)
        form.addRow("密码方式", self.password_mode)
        form.addRow("访问密码", self.password)
        form.addRow("通知邮箱", self.notify_email)
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.password_mode.currentIndexChanged.connect(self._sync_password)
        self._sync_password()

    def _sync_password(self) -> None:
        index = self.password_mode.currentIndex()
        self.password.setReadOnly(index in (0, 2, 3))
        if index == 0:
            self.password.clear()
        elif index == 2:
            self.password.setText(self.password_generator(4))
        elif index == 3:
            self.password.setText(self.password_generator(8))

    def payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if self.max_visits.value() > 0:
            payload["maxVisits"] = self.max_visits.value()
        if self.password.text():
            payload["password"] = self.password.text()
        if self.notify_email.text().strip():
            payload["notifyEmail"] = self.notify_email.text().strip()
        return payload


class ShareEditDialog(QDialog):
    def __init__(self, parent: QWidget, max_visits: int | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("编辑分享")
        self.max_visits = QSpinBox()
        self.max_visits.setRange(0, 1_000_000)
        self.max_visits.setSpecialValueText("不限")
        self.max_visits.setValue(max_visits or 0)
        self.expire_mode = QComboBox()
        self.expire_mode.addItems(["不修改过期时间", "设为永不过期", "设置过期时间"])
        self.expire_at = QDateTimeEdit()
        self.expire_at.setCalendarPopup(True)
        self.password = QLineEdit()
        self.password.setPlaceholderText("留空表示不修改密码")

        layout = QVBoxLayout(self)
        form = QFormLayout()
        form.addRow("访问次数", self.max_visits)
        form.addRow("过期设置", self.expire_mode)
        form.addRow("过期时间", self.expire_at)
        form.addRow("新密码", self.password)
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.expire_mode.currentIndexChanged.connect(self._sync_expire)
        self._sync_expire()

    def _sync_expire(self) -> None:
        self.expire_at.setEnabled(self.expire_mode.currentIndex() == 2)

    def payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"maxVisits": self.max_visits.value() or None}
        if self.expire_mode.currentIndex() == 1:
            payload["expireAt"] = 0
        elif self.expire_mode.currentIndex() == 2:
            payload["expireAt"] = int(self.expire_at.dateTime().toSecsSinceEpoch() * 1000)
        if self.password.text().strip():
            payload["password"] = self.password.text().strip()
        return payload


class FolderPickerDialog(QDialog):
    def __init__(self, parent: QWidget, api, start_parent_id: int | None = None) -> None:
        super().__init__(parent)
        self.api = api
        self.current_parent_id = start_parent_id
        self.selected_parent_id: int | None = start_parent_id
        self.stack: list[tuple[int | None, str]] = [(None, "全部文件")]
        self.setWindowTitle("选择目标文件夹")
        self.resize(520, 420)
        layout = QVBoxLayout(self)

        head = QHBoxLayout()
        self.path_label = QLabel("全部文件")
        self.back_btn = button("返回上级")
        self.root_btn = button("根目录")
        head.addWidget(self.back_btn)
        head.addWidget(self.root_btn)
        head.addWidget(self.path_label, 1)
        layout.addLayout(head)

        from PySide6.QtWidgets import QListWidget

        self.list_widget = QListWidget()
        layout.addWidget(self.list_widget, 1)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self.back_btn.clicked.connect(self.go_back)
        self.root_btn.clicked.connect(self.go_root)
        self.list_widget.itemDoubleClicked.connect(self.enter_selected)
        self.load(None)

    def load(self, parent_id: int | None) -> None:
        self.current_parent_id = parent_id
        self.selected_parent_id = parent_id
        self.list_widget.clear()
        page = self.api.list_files(parent_id=parent_id, size=100)
        for item in page.records:
            if item.fileType == "folder":
                self.list_widget.addItem(f"{item.id}\t{item.fileName}")
        self.path_label.setText(" / ".join(name for _, name in self.stack))

    def enter_selected(self, *_args: object) -> None:
        item = self.list_widget.currentItem()
        if not item:
            return
        file_id_text, name = item.text().split("\t", 1)
        file_id = int(file_id_text)
        self.stack.append((file_id, name))
        self.load(file_id)

    def go_back(self) -> None:
        if len(self.stack) <= 1:
            return
        self.stack.pop()
        self.load(self.stack[-1][0])

    def go_root(self) -> None:
        self.stack = [(None, "全部文件")]
        self.load(None)


def save_file_path(parent: QWidget, suggested_name: str) -> str:
    path, _ = QFileDialog.getSaveFileName(parent, "保存文件", suggested_name)
    return path
