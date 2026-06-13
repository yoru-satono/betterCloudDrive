from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QLineEdit,
    QPushButton,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from betterclouddrive_desktop.api import ApiClient, ApiError
from betterclouddrive_desktop.services.settings import DEFAULT_API_BASE_URL, DEFAULT_WEB_BASE_URL
from betterclouddrive_desktop.ui.widgets import button, muted, show_error, show_info, title


class LoginWindow(QWidget):
    logged_in = Signal()

    def __init__(self, api: ApiClient) -> None:
        super().__init__()
        self.api = api
        self.setWindowTitle("BetterCloudDrive")
        self.resize(420, 560)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(36, 28, 36, 28)
        outer.addWidget(title("欢迎回来"))
        outer.addWidget(muted("登录到 BetterCloudDrive"))

        panel = QFrame()
        panel.setObjectName("panel")
        panel_layout = QVBoxLayout(panel)
        panel_layout.setContentsMargins(22, 22, 22, 22)
        self.stack = QStackedWidget()
        self.stack.addWidget(self._login_page())
        self.stack.addWidget(self._register_page())
        self.stack.addWidget(self._reset_page())
        panel_layout.addWidget(self.stack)
        outer.addWidget(panel, 1)

    def _login_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        form = QFormLayout()
        self.username = QLineEdit()
        self.password = QLineEdit()
        self.password.setEchoMode(QLineEdit.Password)
        self.api_base = QLineEdit(self.api.settings.api_base_url)
        self.web_base = QLineEdit(self.api.settings.web_base_url)
        form.addRow("用户名", self.username)
        form.addRow("密码", self.password)
        form.addRow("API 地址", self.api_base)
        form.addRow("Web 地址", self.web_base)
        layout.addLayout(form)
        login_btn = button("登录", "primary")
        login_btn.clicked.connect(self.login)
        layout.addWidget(login_btn)
        links = QHBoxLayout()
        reg = QPushButton("注册账号")
        reset = QPushButton("忘记密码")
        reg.clicked.connect(lambda: self.stack.setCurrentIndex(1))
        reset.clicked.connect(lambda: self.stack.setCurrentIndex(2))
        links.addWidget(reg)
        links.addWidget(reset)
        layout.addLayout(links)
        layout.addStretch(1)
        return page

    def _register_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        form = QFormLayout()
        self.reg_username = QLineEdit()
        self.reg_email = QLineEdit()
        self.reg_password = QLineEdit()
        self.reg_password.setEchoMode(QLineEdit.Password)
        self.reg_code = QLineEdit()
        form.addRow("用户名", self.reg_username)
        form.addRow("邮箱", self.reg_email)
        form.addRow("密码", self.reg_password)
        form.addRow("验证码", self.reg_code)
        layout.addLayout(form)
        send = button("发送验证码")
        submit = button("注册", "primary")
        back = button("返回登录")
        send.clicked.connect(self.send_registration_code)
        submit.clicked.connect(self.register)
        back.clicked.connect(lambda: self.stack.setCurrentIndex(0))
        layout.addWidget(send)
        layout.addWidget(submit)
        layout.addWidget(back)
        layout.addStretch(1)
        return page

    def _reset_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        form = QFormLayout()
        self.reset_email = QLineEdit()
        self.reset_code = QLineEdit()
        self.reset_password = QLineEdit()
        self.reset_password.setEchoMode(QLineEdit.Password)
        form.addRow("邮箱", self.reset_email)
        form.addRow("验证码", self.reset_code)
        form.addRow("新密码", self.reset_password)
        layout.addLayout(form)
        send = button("发送重置验证码")
        submit = button("重置密码", "primary")
        back = button("返回登录")
        send.clicked.connect(self.send_reset_code)
        submit.clicked.connect(self.reset_password_submit)
        back.clicked.connect(lambda: self.stack.setCurrentIndex(0))
        layout.addWidget(send)
        layout.addWidget(submit)
        layout.addWidget(back)
        layout.addStretch(1)
        return page

    def save_endpoints(self) -> None:
        self.api.set_base_urls(
            self.api_base.text() or DEFAULT_API_BASE_URL,
            self.web_base.text() or DEFAULT_WEB_BASE_URL,
        )

    def login(self) -> None:
        self.save_endpoints()
        try:
            self.api.login(self.username.text().strip(), self.password.text())
        except ApiError as exc:
            show_error(self, exc.message)
            return
        self.logged_in.emit()

    def send_registration_code(self) -> None:
        try:
            self.api.send_registration_code(self.reg_email.text().strip())
            show_info(self, "验证码已发送")
        except ApiError as exc:
            show_error(self, exc.message)

    def register(self) -> None:
        try:
            self.api.register(
                self.reg_username.text().strip(),
                self.reg_password.text(),
                self.reg_email.text().strip(),
                self.reg_code.text().strip(),
            )
            show_info(self, "注册成功，请登录")
            self.stack.setCurrentIndex(0)
        except ApiError as exc:
            show_error(self, exc.message)

    def send_reset_code(self) -> None:
        try:
            self.api.forgot_password(self.reset_email.text().strip())
            show_info(self, "验证码已发送")
        except ApiError as exc:
            show_error(self, exc.message)

    def reset_password_submit(self) -> None:
        try:
            self.api.reset_password(
                self.reset_email.text().strip(),
                self.reset_code.text().strip(),
                self.reset_password.text(),
            )
            show_info(self, "密码已重置，请登录")
            self.stack.setCurrentIndex(0)
        except ApiError as exc:
            show_error(self, exc.message)


class SettingsDialog(QDialog):
    def __init__(self, parent: QWidget, api: ApiClient) -> None:
        super().__init__(parent)
        self.api = api
        self.setWindowTitle("设置")
        layout = QVBoxLayout(self)
        form = QFormLayout()
        self.api_base = QLineEdit(api.settings.api_base_url)
        self.web_base = QLineEdit(api.settings.web_base_url)
        form.addRow("API 地址", self.api_base)
        form.addRow("Web 地址", self.web_base)
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def accept(self) -> None:
        self.api.set_base_urls(self.api_base.text(), self.web_base.text())
        super().accept()
