package com.imi.smartedge.sidebar.panel

/**
 * Represents an item that can be placed in the side panel.
 * Can be a standard App, a specific Activity, or a Shortcut.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    var isInPanel: Boolean = false,
    val type: Type = Type.APP,
    val intentUri: String? = null,
    val activityName: String? = null,
    val subItems: List<String>? = null, // identifiers for items inside a folder
    val appearanceKey: String? = null, // Forces redraw when shape/theme changes
    val isNotification: Boolean = false // Shown because of an active notification, not pinned
) {
    companion object {
        /** Divider between notification apps and pinned apps. */
        const val SEPARATOR_ID = "smartedge.separator"
    }

    enum class Type {
        APP, ACTIVITY, SHORTCUT, FOLDER, TOOL
    }

    /**
     * Unique identifier for this item in preferences.
     * For apps, it's the package name.
     * For activities/shortcuts, it's the intent URI.
     */
    val identifier: String
        get() = intentUri ?: packageName
}
