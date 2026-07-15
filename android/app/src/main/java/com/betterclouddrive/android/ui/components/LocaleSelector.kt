package com.betterclouddrive.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.betterclouddrive.android.util.LocaleController

@Composable
fun LocaleSelector(onChange: (String) -> Unit = LocaleController::set) {
    val current = LocaleController.current()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = current == LocaleController.ZH_CN, onClick = { onChange(LocaleController.ZH_CN) }, label = { Text("简体中文") })
        FilterChip(selected = current == LocaleController.EN_US, onClick = { onChange(LocaleController.EN_US) }, label = { Text("English") })
    }
}
