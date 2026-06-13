from __future__ import annotations

import sys

from PySide6.QtWidgets import QApplication

from betterclouddrive_desktop.api import ApiClient
from betterclouddrive_desktop.ui.login import LoginWindow
from betterclouddrive_desktop.ui.main_window import MainWindow
from betterclouddrive_desktop.ui.theme import apply_theme


class DesktopApp:
    def __init__(self) -> None:
        self.qt = QApplication(sys.argv)
        apply_theme(self.qt)
        self.api = ApiClient()
        self.login_window: LoginWindow | None = None
        self.main_window: MainWindow | None = None

    def run(self) -> int:
        if self.api.is_authenticated:
            self.show_main()
        else:
            self.show_login()
        return self.qt.exec()

    def show_login(self) -> None:
        self.login_window = LoginWindow(self.api)
        self.login_window.logged_in.connect(self.show_main)
        self.login_window.show()

    def show_main(self) -> None:
        if self.login_window:
            self.login_window.close()
        self.main_window = MainWindow(self.api)
        self.main_window.show()


def main() -> int:
    app = DesktopApp()
    return app.run()

