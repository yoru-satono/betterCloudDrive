from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Any

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QAction, QDesktopServices, QPixmap
from PySide6.QtWidgets import (
    QDialog,
    QFileDialog,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QMenu,
    QPlainTextEdit,
    QProgressBar,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from betterclouddrive_desktop.api import ApiClient, ApiError
from betterclouddrive_desktop.models import FileEntity
from betterclouddrive_desktop.services.formatters import format_date, format_size
from betterclouddrive_desktop.services.passwords import generate_share_password, validate_share_password
from betterclouddrive_desktop.services.upload import UploadService
from betterclouddrive_desktop.ui.widgets import (
    FolderPickerDialog,
    ShareDialog,
    TextInputDialog,
    button,
    confirm,
    save_file_path,
    show_error,
    show_info,
    title,
)
from betterclouddrive_desktop.workers import run_in_thread


class FilePage(QWidget):
    def __init__(self, api: ApiClient) -> None:
        super().__init__()
        self.api = api
        self.upload_service = UploadService(api)
        self.current_parent_id: int | None = None
        self.breadcrumb: list[tuple[int | None, str]] = [(None, "全部文件")]
        self.files: list[FileEntity] = []

        layout = QVBoxLayout(self)
        head = QHBoxLayout()
        head.addWidget(title("全部文件"))
        head.addStretch(1)
        self.search_input = QLabel("")
        self.path_label = QLabel("全部文件")
        head.addWidget(self.path_label)
        layout.addLayout(head)

        toolbar = QHBoxLayout()
        self.back_btn = button("返回")
        self.refresh_btn = button("刷新")
        self.search_btn = button("搜索")
        self.new_folder_btn = button("新建文件夹", "primary")
        self.upload_btn = button("上传文件", "primary")
        toolbar.addWidget(self.back_btn)
        toolbar.addWidget(self.refresh_btn)
        toolbar.addWidget(self.search_btn)
        toolbar.addStretch(1)
        toolbar.addWidget(self.new_folder_btn)
        toolbar.addWidget(self.upload_btn)
        layout.addLayout(toolbar)

        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["名称", "类型", "大小", "更新时间", "版本"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(3, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(4, QHeaderView.ResizeToContents)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setContextMenuPolicy(Qt.CustomContextMenu)
        layout.addWidget(self.table, 1)

        self.progress = QProgressBar()
        self.progress.setVisible(False)
        layout.addWidget(self.progress)

        self.back_btn.clicked.connect(self.go_back)
        self.refresh_btn.clicked.connect(self.refresh)
        self.new_folder_btn.clicked.connect(self.create_folder)
        self.upload_btn.clicked.connect(self.upload_files)
        self.search_btn.clicked.connect(self.search)
        self.table.itemDoubleClicked.connect(self.double_click)
        self.table.customContextMenuRequested.connect(self.context_menu)

    def load(self, parent_id: int | None = None) -> None:
        try:
            page = self.api.list_files(parent_id=parent_id, size=100)
            self.files = page.records
            self.current_parent_id = parent_id
            self.render()
        except ApiError as exc:
            show_error(self, exc.message)

    def refresh(self) -> None:
        self.load(self.current_parent_id)

    def render(self) -> None:
        self.path_label.setText(" / ".join(name for _, name in self.breadcrumb))
        self.table.setRowCount(len(self.files))
        for row, file in enumerate(self.files):
            self.table.setItem(row, 0, QTableWidgetItem(("📁 " if file.is_folder else "📄 ") + file.fileName))
            self.table.setItem(row, 1, QTableWidgetItem("文件夹" if file.is_folder else file.mimeType or "文件"))
            self.table.setItem(row, 2, QTableWidgetItem("-" if file.is_folder else format_size(file.fileSize)))
            self.table.setItem(row, 3, QTableWidgetItem(format_date(file.updatedAt)))
            self.table.setItem(row, 4, QTableWidgetItem(str(file.versionCount)))

    def selected_file(self) -> FileEntity | None:
        row = self.table.currentRow()
        if row < 0 or row >= len(self.files):
            return None
        return self.files[row]

    def double_click(self, *_args: object) -> None:
        file = self.selected_file()
        if not file:
            return
        if file.is_folder:
            self.breadcrumb.append((file.id, file.fileName))
            self.load(file.id)
        else:
            self.preview(file)

    def go_back(self) -> None:
        if len(self.breadcrumb) <= 1:
            return
        self.breadcrumb.pop()
        self.load(self.breadcrumb[-1][0])

    def search(self) -> None:
        dialog = TextInputDialog(self, "搜索", "关键词")
        if dialog.exec() != dialog.Accepted or not dialog.text:
            return
        try:
            self.files = self.api.search_files(dialog.text, size=100).records
            self.path_label.setText(f"搜索：{dialog.text}")
            self.render()
        except ApiError as exc:
            show_error(self, exc.message)

    def context_menu(self, pos) -> None:
        file = self.selected_file()
        if not file:
            return
        menu = QMenu(self)
        actions: list[tuple[str, Any]] = [
            ("下载", lambda: self.download(file)),
            ("预览", lambda: self.preview(file)),
            ("详情", lambda: self.details(file)),
            ("版本", lambda: self.versions(file)),
            ("管理标签", lambda: self.manage_tags(file)),
            ("移动到", lambda: self.move_or_copy(file, "move")),
            ("复制到", lambda: self.move_or_copy(file, "copy")),
            ("重命名", lambda: self.rename(file)),
            ("收藏", lambda: self.favorite(file)),
            ("分享", lambda: self.share(file)),
            ("删除", lambda: self.delete(file)),
        ]
        for label, callback in actions:
            if file.is_folder and label in {"预览", "版本", "复制到"}:
                continue
            action = QAction(label, self)
            action.triggered.connect(callback)
            menu.addAction(action)
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def create_folder(self) -> None:
        dialog = TextInputDialog(self, "新建文件夹", "名称")
        if dialog.exec() != dialog.Accepted or not dialog.text:
            return
        try:
            self.api.create_folder(self.current_parent_id, dialog.text)
            self.refresh()
        except ApiError as exc:
            show_error(self, exc.message)

    def upload_files(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(self, "选择上传文件")
        for path in paths:
            self.progress.setVisible(True)
            self.progress.setValue(0)
            run_in_thread(
                self.upload_service.upload_file,
                self._upload_finished,
                lambda msg, name=Path(path).name: show_error(self, f"{name}: {msg}"),
                self._upload_progress,
                Path(path),
                self.current_parent_id,
            )

    def _upload_progress(self, pct: float, message: str) -> None:
        self.progress.setVisible(True)
        self.progress.setValue(int(pct))
        self.progress.setFormat(f"{message} {pct:.0f}%")

    def _upload_finished(self, _result: object) -> None:
        self.progress.setVisible(False)
        self.refresh()

    def download(self, file: FileEntity) -> None:
        suggested = f"{file.fileName}.zip" if file.is_folder else file.fileName
        path = save_file_path(self, suggested)
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

    def preview(self, file: FileEntity) -> None:
        if file.is_folder:
            return
        try:
            data, content_type = self.api.preview_file(file.id)
        except ApiError as exc:
            show_error(self, exc.message)
            return

        if content_type.startswith("image/"):
            dialog = PreviewDialog(self, file.fileName)
            pixmap = QPixmap()
            pixmap.loadFromData(data)
            dialog.set_image(pixmap)
            dialog.exec()
        elif content_type.startswith("text/") or file.fileName.lower().endswith(
            (".txt", ".md", ".json", ".csv", ".log")
        ):
            dialog = PreviewDialog(self, file.fileName)
            dialog.set_text(data.decode("utf-8", errors="replace"))
            dialog.exec()
        else:
            tmp = Path(tempfile.gettempdir()) / file.fileName
            tmp.write_bytes(data)
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(tmp)))

    def details(self, file: FileEntity) -> None:
        try:
            data = self.api.get_file(file.id)
        except ApiError as exc:
            show_error(self, exc.message)
            return
        show_info(
            self,
            f"名称：{data.fileName}\n类型：{data.fileType}\n大小：{format_size(data.fileSize)}\n"
            f"创建：{format_date(data.createdAt)}\n更新：{format_date(data.updatedAt)}\nMD5：{data.md5Hash or '-'}",
        )

    def rename(self, file: FileEntity) -> None:
        dialog = TextInputDialog(self, "重命名", "新名称", file.fileName)
        if dialog.exec() != dialog.Accepted or not dialog.text:
            return
        try:
            self.api.rename_file(file.id, dialog.text)
            self.refresh()
        except ApiError as exc:
            show_error(self, exc.message)

    def delete(self, file: FileEntity) -> None:
        if not confirm(self, f"将「{file.fileName}」移入回收站？", "删除"):
            return
        try:
            self.api.delete_files([file.id])
            self.refresh()
        except ApiError as exc:
            show_error(self, exc.message)

    def move_or_copy(self, file: FileEntity, mode: str) -> None:
        dialog = FolderPickerDialog(self, self.api)
        if dialog.exec() != dialog.Accepted:
            return
        try:
            if mode == "move":
                self.api.move_file(file.id, dialog.selected_parent_id)
            else:
                self.api.copy_file(file.id, dialog.selected_parent_id)
            self.refresh()
        except ApiError as exc:
            show_error(self, exc.message)

    def favorite(self, file: FileEntity) -> None:
        try:
            self.api.add_favorite(file.id)
            show_info(self, "已添加到收藏")
        except ApiError as exc:
            show_error(self, exc.message)

    def share(self, file: FileEntity) -> None:
        dialog = ShareDialog(self, file.fileName, generate_share_password)
        if dialog.exec() != dialog.Accepted:
            return
        payload = dialog.payload()
        password = payload.get("password")
        if password:
            try:
                validate_share_password(password)
            except ValueError as exc:
                show_error(self, str(exc))
                return
        payload["fileId"] = file.id
        try:
            share = self.api.create_share(payload)
            url = f"{self.api.settings.web_base_url}/s/{share.shareCode}"
            if password:
                url = f"{url}\n访问密码：{password}"
            show_info(self, f"分享已创建：\n{url}")
        except ApiError as exc:
            show_error(self, exc.message)

    def manage_tags(self, file: FileEntity) -> None:
        try:
            tags = self.api.list_tags()
        except ApiError as exc:
            show_error(self, exc.message)
            return
        if not tags:
            show_info(self, "暂无标签，请先到标签页创建")
            return
        names = {tag.tagName: tag for tag in tags}
        dialog = TextInputDialog(self, "添加标签", f"标签名：{', '.join(names)}")
        if dialog.exec() != dialog.Accepted or dialog.text not in names:
            return
        try:
            self.api.add_file_tag(file.id, names[dialog.text].id)
            show_info(self, "标签已添加")
        except ApiError as exc:
            show_error(self, exc.message)

    def versions(self, file: FileEntity) -> None:
        try:
            versions = self.api.list_versions(file.id)
        except ApiError as exc:
            show_error(self, exc.message)
            return
        if not versions:
            show_info(self, "暂无版本")
            return
        lines = [f"v{v.versionNumber}  {format_size(v.fileSize)}  {format_date(v.createdAt)}" for v in versions]
        show_info(self, "\n".join(lines))


class PreviewDialog(QDialog):
    def __init__(self, parent: QWidget, file_name: str) -> None:
        super().__init__(parent)
        self.setWindowTitle(file_name)
        self.resize(720, 520)
        self.layout = QVBoxLayout(self)

    def set_image(self, pixmap: QPixmap) -> None:
        label = QLabel()
        label.setAlignment(Qt.AlignCenter)
        label.setPixmap(pixmap.scaled(680, 460, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        self.layout.addWidget(label)

    def set_text(self, text: str) -> None:
        editor = QPlainTextEdit()
        editor.setReadOnly(True)
        editor.setPlainText(text)
        self.layout.addWidget(editor)
