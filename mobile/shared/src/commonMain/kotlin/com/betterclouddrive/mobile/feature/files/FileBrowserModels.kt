package com.betterclouddrive.mobile.feature.files

enum class PersonalFileSort(val apiValue: String) {
    NAME("fileName"),
    UPDATED_AT("updatedAt"),
    SIZE("fileSize"),
}

enum class SortDirection(val apiValue: String) {
    ASCENDING("asc"),
    DESCENDING("desc"),
}

enum class FileViewMode { LIST, GRID }

data class BreadcrumbEntry(val id: Long?, val name: String)
