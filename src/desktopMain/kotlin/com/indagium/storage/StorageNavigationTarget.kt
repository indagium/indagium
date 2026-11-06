package com.indagium.storage

internal sealed interface StorageNavigationTarget {
    data class Visible(val position: Long) : StorageNavigationTarget

    data class Expand(val groupId: String) : StorageNavigationTarget

    data object Missing : StorageNavigationTarget
}
