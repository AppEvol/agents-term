package com.appevol.agentsterm.data

/** The app themes the user can pick in Settings. [storageKey] is what gets persisted, so keep it stable. */
enum class AppThemeId(val storageKey: String) {
    /** Windows XP "Luna" look: blue gradient title bars, beige windows, squared-off controls. */
    XP_LUNA("xp_luna"),

    /** The original dark palette. */
    MIDNIGHT("midnight"),

    /** The original light palette. */
    DAYLIGHT("daylight"),

    /** [DAYLIGHT] or [MIDNIGHT], following the phone's dark mode setting. */
    SYSTEM("system"),
    ;

    companion object {
        val Default = XP_LUNA

        fun fromStorageKey(key: String?): AppThemeId = entries.firstOrNull { it.storageKey == key } ?: Default
    }
}
