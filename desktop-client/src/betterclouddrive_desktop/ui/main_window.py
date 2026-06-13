from __future__ import annotations

from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QPushButton,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from betterclouddrive_desktop.api import ApiClient, ApiError
from betterclouddrive_desktop.ui.file_page import FilePage
from betterclouddrive_desktop.ui.login import SettingsDialog
from betterclouddrive_desktop.ui.secondary_pages import (
    FavoritesPage,
    PublicSharePage,
    RecyclePage,
    SharesPage,
    TagsPage,
)
from betterclouddrive_desktop.ui.widgets import button, muted, show_error


class MainWindow(QMainWindow):
    def __init__(self, api: ApiClient) -> None:
        super().__init__()
        self.api = api
        self.setWindowTitle("BetterCloudDrive")
        self.resize(1180, 760)
        self.nav_buttons: list[QPushButton] = []

        root = QWidget()
        layout = QHBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        self.sidebar = self._build_sidebar()
        layout.addWidget(self.sidebar)

        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(18, 14, 18, 18)
        topbar = QHBoxLayout()
        self.user_label = muted("未登录")
        settings = button("设置")
        logout = button("退出登录")
        settings.clicked.connect(self.open_settings)
        logout.clicked.connect(self.logout)
        topbar.addStretch(1)
        topbar.addWidget(self.user_label)
        topbar.addWidget(settings)
        topbar.addWidget(logout)
        content_layout.addLayout(topbar)

        self.stack = QStackedWidget()
        self.file_page = FilePage(api)
        self.shares_page = SharesPage(api)
        self.favorites_page = FavoritesPage(api)
        self.tags_page = TagsPage(api)
        self.recycle_page = RecyclePage(api)
        self.public_share_page = PublicSharePage(api)
        for page in (
            self.file_page,
            self.shares_page,
            self.favorites_page,
            self.tags_page,
            self.recycle_page,
            self.public_share_page,
        ):
            self.stack.addWidget(page)
        content_layout.addWidget(self.stack, 1)
        layout.addWidget(content, 1)
        self.setCentralWidget(root)
        self.load_user()
        self.switch_page(0)

    def _build_sidebar(self) -> QFrame:
        frame = QFrame()
        frame.setObjectName("sidebar")
        frame.setFixedWidth(230)
        layout = QVBoxLayout(frame)
        layout.setContentsMargins(14, 16, 14, 16)
        brand = QLabel("BetterCloudDrive")
        brand.setObjectName("title")
        layout.addWidget(brand)
        layout.addWidget(muted("Desktop"))
        layout.addSpacing(18)
        items = [
            ("全部文件", 0),
            ("我的分享", 1),
            ("我的收藏", 2),
            ("标签", 3),
            ("回收站", 4),
            ("访问分享", 5),
        ]
        for text, index in items:
            nav = button(text)
            nav.setObjectName("nav")
            nav.clicked.connect(lambda _checked=False, idx=index: self.switch_page(idx))
            self.nav_buttons.append(nav)
            layout.addWidget(nav)
        layout.addStretch(1)
        return frame

    def switch_page(self, index: int) -> None:
        self.stack.setCurrentIndex(index)
        for idx, nav in enumerate(self.nav_buttons):
            nav.setProperty("active", idx == index)
            nav.style().unpolish(nav)
            nav.style().polish(nav)
        page = self.stack.widget(index)
        if hasattr(page, "load"):
            page.load()

    def load_user(self) -> None:
        try:
            user = self.api.me()
            used_mb = user.storageUsed // 1024 // 1024
            quota_mb = user.storageQuota // 1024 // 1024
            self.user_label.setText(f"{user.username}  {used_mb} MB / {quota_mb} MB")
        except ApiError as exc:
            self.user_label.setText("登录状态异常")
            show_error(self, exc.message)

    def open_settings(self) -> None:
        dialog = SettingsDialog(self, self.api)
        dialog.exec()

    def logout(self) -> None:
        self.api.logout()
        self.close()
