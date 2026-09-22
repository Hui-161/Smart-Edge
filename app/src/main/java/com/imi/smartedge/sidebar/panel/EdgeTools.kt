package com.imi.smartedge.sidebar.panel

/**
 * Catalog of the tools that can be placed on the "Tools" page of the edge panel.
 * The ids are used as AppInfo package names (type TOOL) and handled in FloatingPanelService.
 */
object EdgeTools {

    data class Tool(val id: String, val labelRes: Int, val iconRes: Int)

    const val FLASHLIGHT = "smartedge.tool.flashlight"
    const val ROTATION = "smartedge.tool.rotation"
    const val CAMERA = "smartedge.tool.camera"
    const val LOCK_SCREEN = "smartedge.tool.lock_screen"
    const val POWER_MENU = "smartedge.tool.power_menu"
    const val NOTIFICATIONS = "smartedge.tool.notifications"
    const val QUICK_SETTINGS = "smartedge.tool.quick_settings"
    const val SCREENSHOT = "smartedge.tool.screenshot"
    const val VOLUME_UP = "smartedge.tool.volume_up"
    const val VOLUME_DOWN = "smartedge.tool.volume_down"
    const val BRIGHTNESS_UP = "smartedge.tool.brightness_up"
    const val BRIGHTNESS_DOWN = "smartedge.tool.brightness_down"

    /** Prefix for contacts shown on the contacts page (suffix = index in the favorites list). */
    const val CONTACT_PREFIX = "smartedge.contact."
    /** Placeholder on an empty contacts page that opens the contact settings. */
    const val CONTACTS_SETUP = "smartedge.tool.contacts_setup"

    val ALL: List<Tool> = listOf(
        Tool(FloatingPanelService.TOOL_EXTRA_DIM, R.string.edge_tool_extra_dim, R.drawable.ic_edge_extra_dim),
        Tool(FloatingPanelService.TOOL_CLIPBOARD, R.string.edge_tool_clipboard, R.drawable.ic_copy),
        Tool(FLASHLIGHT, R.string.edge_tool_flashlight, R.drawable.ic_tool_flashlight),
        Tool(ROTATION, R.string.edge_tool_rotation, R.drawable.ic_tool_rotation),
        Tool(SCREENSHOT, R.string.action_screenshot, android.R.drawable.ic_menu_camera),
        Tool(CAMERA, R.string.edge_tool_camera, R.drawable.ic_tool_camera),
        Tool(VOLUME_UP, R.string.msg_tool_volume_up, R.drawable.ic_tool_volume_up),
        Tool(VOLUME_DOWN, R.string.msg_tool_volume_down, R.drawable.ic_tool_volume_down),
        Tool(BRIGHTNESS_UP, R.string.msg_tool_brightness_up, R.drawable.ic_brightness_up),
        Tool(BRIGHTNESS_DOWN, R.string.msg_tool_brightness_down, R.drawable.ic_brightness_down),
        Tool(NOTIFICATIONS, R.string.edge_tool_notifications, R.drawable.ic_tool_notifications),
        Tool(QUICK_SETTINGS, R.string.edge_tool_quick_settings, R.drawable.ic_tool_quick_settings),
        Tool(LOCK_SCREEN, R.string.edge_tool_lock, R.drawable.ic_tool_lock),
        Tool(POWER_MENU, R.string.action_power_menu, android.R.drawable.ic_lock_power_off),
        Tool(FloatingPanelService.TOOL_CONTACTS, R.string.edge_tool_contacts, R.drawable.ic_edge_contacts)
    )

    val DEFAULT_TOOLS_PAGE: List<String> = listOf(
        FloatingPanelService.TOOL_EXTRA_DIM,
        FloatingPanelService.TOOL_CLIPBOARD,
        FLASHLIGHT,
        ROTATION,
        SCREENSHOT,
        VOLUME_UP,
        VOLUME_DOWN,
        BRIGHTNESS_UP,
        BRIGHTNESS_DOWN
    )

    fun find(id: String): Tool? = ALL.firstOrNull { it.id == id }
}
