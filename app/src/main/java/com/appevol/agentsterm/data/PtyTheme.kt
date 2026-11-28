package com.appevol.agentsterm.data

enum class PtyTheme(val storageKey: String) {
    FOLLOW_APP("follow_app"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageKey(key: String?): PtyTheme = entries.firstOrNull { it.storageKey == key } ?: FOLLOW_APP
    }
}
