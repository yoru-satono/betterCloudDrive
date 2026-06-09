package com.betterclouddrive.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.util.FileTypeHelper

@Composable
fun FileIcon(
    file: FileItem,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    if (file.isFolder) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = "folder",
            modifier = modifier.size(size),
            tint = Color(0xFFF0A500), // Amber
        )
    } else {
        val color = FileTypeHelper.getColorForExtension(file.fileName)
        val icon = when {
            FileTypeHelper.isImage(file.fileName) -> Icons.Default.Image
            FileTypeHelper.isVideo(file.fileName) -> Icons.Default.PlayCircle
            FileTypeHelper.isAudio(file.fileName) -> Icons.Default.MusicNote
            file.fileName.endsWith(".pdf") -> Icons.Default.PictureAsPdf
            FileTypeHelper.isArchive(file.fileName) -> Icons.Default.FolderZip
            FileTypeHelper.isText(file.fileName) -> Icons.Default.Description
            else -> Icons.Default.InsertDriveFile
        }
        Icon(
            imageVector = icon,
            contentDescription = "file",
            modifier = modifier.size(size),
            tint = color,
        )
    }
}
