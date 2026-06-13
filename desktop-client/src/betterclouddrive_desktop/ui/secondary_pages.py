from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction
from PySide6.QtWidgets import (
    QHBoxLayout,
    QHeaderView,
    QLineEdit,
    QMenu,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from betterclouddrive_desktop.api import ApiClient, ApiError
from betterclouddrive_desktop.models import FileEntity, ShareLinkEntity, TagEntity
from betterclouddrive_desktop.services.formatters import format_date, format_size
from betterclouddrive_desktop.services.passwords import validate_share_password
from betterclouddrive_desktop.ui.widgets import (
    FolderPickerDialog,
    ShareEditDialog,
    TextInputDialog,
    button,
    confirm,
    save_file_path,
    show_error,
    show_info,
    title,
)


class SimpleTablePage(QWidget):
    def __init__(self, api: ApiClient, title_text: str, headers: list[str]) -> None:
        super().__init__()
        self.api = api
        layout = QVBoxLayout(self)
        top = QHBoxLayout()
        top.addWidget(title(title_text))
        top.addStretch(1)
        self.refresh_btn = button("刷新")
        top.addWidget(self.refresh_btn)
        layout.addLayout(top)
        self.table = QTableWidget(0, len(headers))
        self.table.setHorizontalHeaderLabels(headers)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setContextMenuPolicy(Qt.CustomContextMenu)
        layout.addWidget(self.table, 1)
        self.refresh_btn.clicked.connect(self.load)
        self.table.customContextMenuRequested.connect(self.context_menu)

    def load(self) -> None:
        raise NotImplementedError

    def context_menu(self, pos) -> None:
        return None


class FavoritesPage(SimpleTablePage):
    def __init__(self, api: ApiClient) -> None:
        super().__init__(api, "我的收藏", ["名称", "类型", "大小", "更新时间"])
        self.files: list[FileEntity] = []

    def load(self) -> None:
        try:
            self.files = self.api.list_favorites(size=100).records
            self.table.setRowCount(len(self.files))
            for row, file in enumerate(self.files):
                self.table.setItem(row, 0, QTableWidgetItem(file.fileName))
                self.table.setItem(row, 1, QTableWidgetItem("文件夹" if file.is_folder else file.mimeType or "文件"))
                self.table.setItem(row, 2, QTableWidgetItem("-" if file.is_folder else format_size(file.fileSize)))
                self.table.setItem(row, 3, QTableWidgetItem(format_date(file.updatedAt)))
        except ApiError as exc:
            show_error(self, exc.message)

    def selected_file(self) -> FileEntity | None:
        row = self.table.currentRow()
        return self.files[row] if 0 <= row < len(self.files) else None

    def context_menu(self, pos) -> None:
        file = self.selected_file()
        if not file:
            return
        menu = QMenu(self)
        download = QAction("下载", self)
        remove = QAction("取消收藏", self)
        download.triggered.connect(lambda: self.download(file))
        remove.triggered.connect(lambda: self.remove(file))
        menu.addAction(download)
        menu.addAction(remove)
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def download(self, file: FileEntity) -> None:
        path = save_file_path(self, f"{file.fileName}.zip" if file.is_folder else file.fileName)
        if not path:
            return
        try:
            if file.is_folder:
                self.api.download_folder_zip(file.id, Path(path))
            else:
                self.api.download_file(file.id, Path(path))
            show_info(self, "下载完成")
        except ApiError as exc:
            show_error(self, exc.message)

    def remove(self, file: FileEntity) -> None:
        try:
            self.api.remove_favorite(file.id)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)


class RecyclePage(SimpleTablePage):
    def __init__(self, api: ApiClient) -> None:
        super().__init__(api, "回收站", ["名称", "类型", "大小", "删除时间"])
        self.files: list[FileEntity] = []
        self.empty_btn = button("清空回收站", "danger")
        self.layout().itemAt(0).layout().addWidget(self.empty_btn)
        self.empty_btn.clicked.connect(self.empty)

    def load(self) -> None:
        try:
            self.files = self.api.list_recycle_bin(size=100).records
            self.table.setRowCount(len(self.files))
            for row, file in enumerate(self.files):
                self.table.setItem(row, 0, QTableWidgetItem(file.fileName))
                self.table.setItem(row, 1, QTableWidgetItem("文件夹" if file.is_folder else file.mimeType or "文件"))
                self.table.setItem(row, 2, QTableWidgetItem("-" if file.is_folder else format_size(file.fileSize)))
                self.table.setItem(row, 3, QTableWidgetItem(format_date(file.updatedAt)))
        except ApiError as exc:
            show_error(self, exc.message)

    def selected_file(self) -> FileEntity | None:
        row = self.table.currentRow()
        return self.files[row] if 0 <= row < len(self.files) else None

    def context_menu(self, pos) -> None:
        file = self.selected_file()
        if not file:
            return
        menu = QMenu(self)
        restore = QAction("恢复", self)
        delete = QAction("永久删除", self)
        restore.triggered.connect(lambda: self.restore(file))
        delete.triggered.connect(lambda: self.delete(file))
        menu.addAction(restore)
        menu.addAction(delete)
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def restore(self, file: FileEntity) -> None:
        try:
            self.api.restore_file(file.id)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)

    def delete(self, file: FileEntity) -> None:
        if not confirm(self, f"永久删除「{file.fileName}」？", "永久删除"):
            return
        try:
            self.api.permanent_delete(file.id)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)

    def empty(self) -> None:
        if not confirm(self, "将永久删除回收站所有文件。", "清空回收站"):
            return
        try:
            self.api.empty_recycle_bin()
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)


class SharesPage(SimpleTablePage):
    def __init__(self, api: ApiClient) -> None:
        super().__init__(api, "我的分享", ["分享码", "状态", "访问", "下载", "过期"])
        self.shares: list[ShareLinkEntity] = []

    def load(self) -> None:
        try:
            self.shares = self.api.list_shares(size=100).records
            self.table.setRowCount(len(self.shares))
            for row, share in enumerate(self.shares):
                self.table.setItem(row, 0, QTableWidgetItem(share.shareCode))
                self.table.setItem(row, 1, QTableWidgetItem("已取消" if share.isCanceled else "有效"))
                self.table.setItem(row, 2, QTableWidgetItem(f"{share.visitCount}/{share.maxVisits or '不限'}"))
                self.table.setItem(row, 3, QTableWidgetItem(str(share.downloadCount)))
                self.table.setItem(row, 4, QTableWidgetItem(format_date(share.expireAt)))
        except ApiError as exc:
            show_error(self, exc.message)

    def selected_share(self) -> ShareLinkEntity | None:
        row = self.table.currentRow()
        return self.shares[row] if 0 <= row < len(self.shares) else None

    def context_menu(self, pos) -> None:
        share = self.selected_share()
        if not share:
            return
        menu = QMenu(self)
        copy = QAction("复制链接", self)
        detail = QAction("详情", self)
        edit = QAction("编辑", self)
        cancel = QAction("取消分享", self)
        copy.triggered.connect(lambda: show_info(self, f"{self.api.settings.web_base_url}/s/{share.shareCode}"))
        detail.triggered.connect(lambda: self.detail(share))
        edit.triggered.connect(lambda: self.edit(share))
        cancel.triggered.connect(lambda: self.cancel(share))
        for action in (detail, edit, copy, cancel):
            menu.addAction(action)
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def detail(self, share: ShareLinkEntity) -> None:
        try:
            data = self.api.get_share(share.id)
            show_info(
                self,
                f"链接：{self.api.settings.web_base_url}/s/{data.shareCode}\n"
                f"文件 ID：{data.fileId}\n访问：{data.visitCount}/{data.maxVisits or '不限'}\n"
                f"下载：{data.downloadCount}\n密码：{'有' if data.passwordHash else '无'}",
            )
        except ApiError as exc:
            show_error(self, exc.message)

    def edit(self, share: ShareLinkEntity) -> None:
        dialog = ShareEditDialog(self, share.maxVisits)
        if dialog.exec() != dialog.Accepted:
            return
        payload = dialog.payload()
        if payload.get("password"):
            try:
                validate_share_password(str(payload["password"]))
            except ValueError as exc:
                show_error(self, str(exc))
                return
        try:
            self.api.update_share(share.id, payload)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)

    def cancel(self, share: ShareLinkEntity) -> None:
        if not confirm(self, "取消后分享链接将失效。", "取消分享"):
            return
        try:
            self.api.delete_share(share.id)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)


class TagsPage(QWidget):
    COLORS = ["#00d4aa", "#60a5fa", "#a78bfa", "#f87171", "#fb923c", "#4ade80", "#f472b6"]

    def __init__(self, api: ApiClient) -> None:
        super().__init__()
        self.api = api
        self.tags: list[TagEntity] = []
        self.active_tag: TagEntity | None = None
        self.files: list[FileEntity] = []
        layout = QVBoxLayout(self)
        top = QHBoxLayout()
        top.addWidget(title("标签"))
        top.addStretch(1)
        self.new_btn = button("新建标签", "primary")
        self.refresh_btn = button("刷新")
        top.addWidget(self.refresh_btn)
        top.addWidget(self.new_btn)
        layout.addLayout(top)
        body = QHBoxLayout()
        self.tags_table = QTableWidget(0, 3)
        self.tags_table.setHorizontalHeaderLabels(["名称", "文件", "颜色"])
        self.tags_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.files_table = QTableWidget(0, 3)
        self.files_table.setHorizontalHeaderLabels(["文件", "类型", "大小"])
        self.files_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        body.addWidget(self.tags_table, 1)
        body.addWidget(self.files_table, 2)
        layout.addLayout(body, 1)
        self.refresh_btn.clicked.connect(self.load)
        self.new_btn.clicked.connect(self.create)
        self.tags_table.itemSelectionChanged.connect(self.select_tag)
        self.tags_table.setContextMenuPolicy(Qt.CustomContextMenu)
        self.tags_table.customContextMenuRequested.connect(self.tags_menu)
        self.files_table.setContextMenuPolicy(Qt.CustomContextMenu)
        self.files_table.customContextMenuRequested.connect(self.files_menu)

    def load(self) -> None:
        try:
            self.tags = self.api.list_tags()
            self.tags_table.setRowCount(len(self.tags))
            for row, tag in enumerate(self.tags):
                self.tags_table.setItem(row, 0, QTableWidgetItem(tag.tagName))
                self.tags_table.setItem(row, 1, QTableWidgetItem(str(tag.fileCount)))
                self.tags_table.setItem(row, 2, QTableWidgetItem(tag.color or ""))
        except ApiError as exc:
            show_error(self, exc.message)

    def select_tag(self) -> None:
        row = self.tags_table.currentRow()
        if row < 0 or row >= len(self.tags):
            return
        self.active_tag = self.tags[row]
        try:
            self.files = self.api.list_files_by_tag(self.active_tag.id, size=100).records
            self.files_table.setRowCount(len(self.files))
            for f_row, file in enumerate(self.files):
                self.files_table.setItem(f_row, 0, QTableWidgetItem(file.fileName))
                self.files_table.setItem(
                    f_row,
                    1,
                    QTableWidgetItem("文件夹" if file.is_folder else file.mimeType or "文件"),
                )
                self.files_table.setItem(
                    f_row,
                    2,
                    QTableWidgetItem("-" if file.is_folder else format_size(file.fileSize)),
                )
        except ApiError as exc:
            show_error(self, exc.message)

    def create(self) -> None:
        dialog = TextInputDialog(self, "新建标签", "名称")
        if dialog.exec() != dialog.Accepted or not dialog.text:
            return
        try:
            self.api.create_tag(dialog.text, self.COLORS[len(self.tags) % len(self.COLORS)])
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)

    def tags_menu(self, pos) -> None:
        row = self.tags_table.currentRow()
        if row < 0 or row >= len(self.tags):
            return
        tag = self.tags[row]
        menu = QMenu(self)
        edit = QAction("编辑", self)
        delete = QAction("删除", self)
        edit.triggered.connect(lambda: self.edit_tag(tag))
        delete.triggered.connect(lambda: self.delete_tag(tag))
        menu.addAction(edit)
        menu.addAction(delete)
        menu.exec(self.tags_table.viewport().mapToGlobal(pos))

    def files_menu(self, pos) -> None:
        row = self.files_table.currentRow()
        if row < 0 or row >= len(self.files) or not self.active_tag:
            return
        file = self.files[row]
        menu = QMenu(self)
        remove = QAction("移除此标签", self)
        remove.triggered.connect(lambda: self.remove_file_tag(file))
        menu.addAction(remove)
        menu.exec(self.files_table.viewport().mapToGlobal(pos))

    def edit_tag(self, tag: TagEntity) -> None:
        dialog = TextInputDialog(self, "编辑标签", "名称", tag.tagName)
        if dialog.exec() != dialog.Accepted or not dialog.text:
            return
        try:
            self.api.update_tag(tag.id, dialog.text, tag.color)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)

    def delete_tag(self, tag: TagEntity) -> None:
        if not confirm(self, f"删除标签「{tag.tagName}」？", "删除标签"):
            return
        try:
            self.api.delete_tag(tag.id)
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)

    def remove_file_tag(self, file: FileEntity) -> None:
        if not self.active_tag:
            return
        try:
            self.api.remove_file_tag(file.id, self.active_tag.id)
            self.select_tag()
            self.load()
        except ApiError as exc:
            show_error(self, exc.message)


class PublicSharePage(QWidget):
    def __init__(self, api: ApiClient) -> None:
        super().__init__()
        self.api = api
        self.share_code = ""
        self.password = ""
        self.files: list[FileEntity] = []
        self.root_file_id: int | None = None
        self.parent_stack: list[tuple[int | None, str]] = [(None, "分享")]
        layout = QVBoxLayout(self)
        top = QHBoxLayout()
        top.addWidget(title("访问分享"))

        self.share_input = QLineEdit()
        self.share_input.setPlaceholderText("分享码")
        self.password_input = QLineEdit()
        self.password_input.setPlaceholderText("访问密码（可选）")
        self.password_input.setEchoMode(QLineEdit.Password)
        self.access_btn = button("访问", "primary")
        self.back_btn = button("返回")
        top.addWidget(self.share_input)
        top.addWidget(self.password_input)
        top.addWidget(self.access_btn)
        top.addWidget(self.back_btn)
        layout.addLayout(top)
        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels(["名称", "类型", "大小", "更新时间"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setContextMenuPolicy(Qt.CustomContextMenu)
        layout.addWidget(self.table, 1)
        self.access_btn.clicked.connect(self.access)
        self.back_btn.clicked.connect(self.go_back)
        self.table.itemDoubleClicked.connect(self.enter_folder)
        self.table.customContextMenuRequested.connect(self.menu)

    def access(self) -> None:
        self.share_code = self.share_input.text().strip()
        self.password = self.password_input.text()
        if not self.share_code:
            return
        try:
            root = self.api.access_share(self.share_code, self.password or None)
            self.root_file_id = root.fileId
            self.parent_stack = [(None, root.fileName)]
            self.files = self.api.list_shared_files(self.share_code, None, size=100).records
            if not self.files:
                self.files = [
                    FileEntity(
                        id=root.fileId,
                        userId=0,
                        fileName=root.fileName,
                        fileType=root.fileType,
                        fileSize=root.fileSize,
                    )
                ]
            self.render()
        except ApiError as exc:
            show_error(self, exc.message)

    def load_parent(self, parent_id: int | None) -> None:
        try:
            self.files = self.api.list_shared_files(self.share_code, parent_id, size=100).records
            self.render()
        except ApiError as exc:
            show_error(self, exc.message)

    def enter_folder(self, *_args: object) -> None:
        file = self.selected_file()
        if not file or not file.is_folder:
            return
        self.parent_stack.append((file.id, file.fileName))
        self.load_parent(file.id)

    def go_back(self) -> None:
        if len(self.parent_stack) <= 1:
            return
        self.parent_stack.pop()
        self.load_parent(self.parent_stack[-1][0])

    def render(self) -> None:
        self.table.setRowCount(len(self.files))
        for row, file in enumerate(self.files):
            self.table.setItem(row, 0, QTableWidgetItem(file.fileName))
            self.table.setItem(row, 1, QTableWidgetItem("文件夹" if file.is_folder else file.mimeType or "文件"))
            self.table.setItem(row, 2, QTableWidgetItem("-" if file.is_folder else format_size(file.fileSize)))
            self.table.setItem(row, 3, QTableWidgetItem(format_date(file.updatedAt)))

    def selected_file(self) -> FileEntity | None:
        row = self.table.currentRow()
        return self.files[row] if 0 <= row < len(self.files) else None

    def menu(self, pos) -> None:
        file = self.selected_file()
        if not file:
            return
        menu = QMenu(self)
        download = QAction("下载", self)
        save = QAction("保存到我的网盘", self)
        download.triggered.connect(lambda: self.download(file))
        save.triggered.connect(lambda: self.save(file))
        menu.addAction(download)
        menu.addAction(save)
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def download(self, file: FileEntity) -> None:
        path = save_file_path(self, f"{file.fileName}.zip" if file.is_folder else file.fileName)
        if not path:
            return
        try:
            if file.is_folder:
                self.api.download_shared_folder_zip(self.share_code, file.id, self.password or None, Path(path))
            else:
                self.api.download_shared_file(self.share_code, file.id, self.password or None, Path(path))
            show_info(self, "下载完成")
        except ApiError as exc:
            show_error(self, exc.message)

    def save(self, file: FileEntity) -> None:
        dialog = FolderPickerDialog(self, self.api)
        if dialog.exec() != dialog.Accepted:
            return
        try:
            self.api.save_shared_item(self.share_code, file.id, dialog.selected_parent_id, self.password or None)
            show_info(self, "已保存到我的网盘")
        except ApiError as exc:
            show_error(self, exc.message)
