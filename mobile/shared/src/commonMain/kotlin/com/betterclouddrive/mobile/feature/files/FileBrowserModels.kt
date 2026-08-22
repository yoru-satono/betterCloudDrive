package com.betterclouddrive.mobile.feature.files

enum class PersonalFileSort(val apiValue: String, val label: String) {
    NAME("fileName", "名称"),
    UPDATED_AT("updatedAt", "修改时间"),
    SIZE("fileSize", "大小"),
}

enum class SortDirection(val apiValue: String, val label: String) {
    ASCENDING("asc", "升序"),
    DESCENDING("desc", "降序"),
}

enum class FileViewMode { LIST, GRID }

data class BreadcrumbEntry(val id: Long?, val name: String)
