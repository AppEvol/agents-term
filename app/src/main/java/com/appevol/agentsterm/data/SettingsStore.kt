package com.appevol.agentsterm.data

import android.content.Context
import androidx.core.content.edit

interface ConnectionSettings {
    fun loadConnection(): ConnectionConfig
    fun saveConnection(config: ConnectionConfig)
    fun loadLastTabId(): String?
    fun saveLastTabId(tabId: String?)
    fun loadLastCwd(host: String): String? = null
    fun saveLastCwd(host: String, cwd: String?) {}
}

class SettingsStore(context: Context) : ConnectionSettings {
    /** Non-secret preferences eligible for Android Auto Backup across reinstalls. */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Device-local pairing credentials excluded from Android Auto Backup. */
    private val localPrefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyConnectionIfNeeded()
    }

    private fun migrateLegacyConnectionIfNeeded() {
        val hasLegacy = prefs.contains(KEY_HOST) || prefs.contains(KEY_PORT) || prefs.contains(KEY_TOKEN)
        if (!hasLegacy) return

        val legacyHost = prefs.getString(KEY_HOST, "").orEmpty().trim()
        val legacyPort = prefs.getInt(KEY_PORT, DEFAULT_DAEMON_PORT)
        val legacyToken = prefs.getString(KEY_TOKEN, "").orEmpty().trim()

        if (legacyHost.isNotEmpty() && !localPrefs.contains(KEY_HOST)) {
            localPrefs.edit {
                putString(KEY_HOST, legacyHost)
                putInt(KEY_PORT, legacyPort)
                putString(KEY_TOKEN, legacyToken)
            }
        }

        prefs.edit {
            if (legacyHost.isNotEmpty() && prefs.getString(KEY_SSH_HOST, null).isNullOrBlank()) {
                putString(KEY_SSH_HOST, legacyHost)
            }
            remove(KEY_HOST)
            remove(KEY_PORT)
            remove(KEY_TOKEN)
        }
    }

    override fun loadConnection(): ConnectionConfig = ConnectionConfig(
        host = localPrefs.getString(KEY_HOST, "").orEmpty(),
        port = localPrefs.getInt(KEY_PORT, DEFAULT_DAEMON_PORT),
        token = localPrefs.getString(KEY_TOKEN, "").orEmpty(),
    )

    override fun saveConnection(config: ConnectionConfig) {
        localPrefs.edit {
            putString(KEY_HOST, config.host.trim())
            putInt(KEY_PORT, config.port)
            putString(KEY_TOKEN, config.token.trim())
        }
    }

    override fun loadLastTabId(): String? = prefs.getString(KEY_LAST_TAB, null)

    override fun saveLastTabId(tabId: String?) {
        prefs.edit().putString(KEY_LAST_TAB, tabId).apply()
    }

    override fun loadLastCwd(host: String): String? {
        val normalizedHost = cleanHost(host)
        if (normalizedHost.isBlank()) return null
        return prefs.getString(lastCwdPrefKey(normalizedHost), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun saveLastCwd(host: String, cwd: String?) {
        val normalizedHost = cleanHost(host)
        if (normalizedHost.isBlank()) return
        val key = lastCwdPrefKey(normalizedHost)
        val cleanedCwd = cwd?.trim()?.takeIf { it.isNotEmpty() }
        val editor = prefs.edit()
        if (cleanedCwd == null) {
            editor.remove(key)
        } else {
            editor.putString(key, cleanedCwd)
        }
        editor.apply()
    }

    /**
     * Loads non-secret SSH fields (host, port, username, auth mode, daemon port).
     * Passwords and private keys are intentionally never stored on disk.
     */
    fun loadSshProfile(): SshProfile {
        val host = prefs.getString(KEY_SSH_HOST, null)
            ?.takeIf { it.isNotBlank() }
            ?: localPrefs.getString(KEY_HOST, "").orEmpty()
        val normalizedHost = cleanHost(host)
        return SshProfile(
            host = host,
            sshPort = prefs.getInt(KEY_SSH_PORT, 22),
            username = prefs.getString(KEY_SSH_USERNAME, "").orEmpty(),
            authMethod = SshAuthMethod.fromStorageKey(prefs.getString(KEY_SSH_AUTH_METHOD, null)),
            daemonPort = prefs.getInt(KEY_SSH_DAEMON_PORT, DEFAULT_DAEMON_PORT),
            isBootstrapped = normalizedHost.isNotBlank() && isHostBootstrapped(normalizedHost),
        )
    }

    fun saveSshProfile(profile: SshProfile) {
        prefs.edit()
            .putString(KEY_SSH_HOST, profile.host.trim())
            .putInt(KEY_SSH_PORT, profile.sshPort)
            .putString(KEY_SSH_USERNAME, profile.username.trim())
            .putString(KEY_SSH_AUTH_METHOD, profile.authMethod.storageKey)
            .putInt(KEY_SSH_DAEMON_PORT, profile.daemonPort)
            .apply()
    }

    fun getTrustedHostFingerprint(host: String, port: Int): String? = prefs.getString(hostKeyPrefKey(host, port), null)

    fun saveTrustedHostFingerprint(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(hostKeyPrefKey(host, port), fingerprint).apply()
    }

    fun clearTrustedHostFingerprint(host: String, port: Int) {
        prefs.edit().remove(hostKeyPrefKey(host, port)).apply()
    }

    fun isHostBootstrapped(host: String): Boolean {
        val normalized = cleanHost(host)
        if (normalized.isBlank()) return false
        val set = prefs.getStringSet(KEY_BOOTSTRAPPED_HOSTS, emptySet()).orEmpty()
        return normalized in set
    }

    fun markHostBootstrapped(host: String) {
        val normalized = cleanHost(host)
        if (normalized.isBlank()) return
        val updated = prefs.getStringSet(KEY_BOOTSTRAPPED_HOSTS, emptySet()).orEmpty() + normalized
        prefs.edit().putStringSet(KEY_BOOTSTRAPPED_HOSTS, updated).apply()
    }

    fun loadAppTheme(): AppThemeId = AppThemeId.fromStorageKey(prefs.getString(KEY_APP_THEME, null))

    fun saveAppTheme(theme: AppThemeId) {
        prefs.edit { putString(KEY_APP_THEME, theme.storageKey) }
    }

    fun loadDebugSharingSettings(): DebugSharingSettings = DebugSharingSettings(
        enabled = prefs.getBoolean(KEY_DEBUG_SHARING_ENABLED, false),
        includeScreenshot = prefs.getBoolean(KEY_DEBUG_INCLUDE_SCREENSHOT, true),
        includeLogcat = prefs.getBoolean(KEY_DEBUG_INCLUDE_LOGCAT, true),
        autoUploadOnError = prefs.getBoolean(KEY_DEBUG_AUTO_UPLOAD_ERRORS, false),
        remoteInspectionEnabled = prefs.getBoolean(KEY_DEBUG_REMOTE_INSPECTION, false),
        remoteControlEnabled = prefs.getBoolean(KEY_DEBUG_REMOTE_CONTROL, false),
    )

    fun saveDebugSharingSettings(settings: DebugSharingSettings) {
        prefs.edit()
            .putBoolean(KEY_DEBUG_SHARING_ENABLED, settings.enabled)
            .putBoolean(KEY_DEBUG_INCLUDE_SCREENSHOT, settings.includeScreenshot)
            .putBoolean(KEY_DEBUG_INCLUDE_LOGCAT, settings.includeLogcat)
            .putBoolean(KEY_DEBUG_AUTO_UPLOAD_ERRORS, settings.autoUploadOnError)
            .putBoolean(KEY_DEBUG_REMOTE_INSPECTION, settings.remoteInspectionEnabled)
            .putBoolean(KEY_DEBUG_REMOTE_CONTROL, settings.remoteControlEnabled)
            .apply()
    }

    fun loadLastDebugUpload(): DebugDumpUploadState {
        val remoteDir = prefs.getString(KEY_DEBUG_LAST_REMOTE_DIR, null)
        val dumpId = prefs.getString(KEY_DEBUG_LAST_DUMP_ID, null)
        val latestSymlink = prefs.getString(KEY_DEBUG_LAST_SYMLINK, null)
        val uploadedAt = prefs.getLong(KEY_DEBUG_LAST_UPLOADED_AT, 0L).takeIf { it > 0L }
        return DebugDumpUploadState(
            lastDumpId = dumpId,
            lastRemoteDir = remoteDir,
            lastLatestSymlink = latestSymlink,
            lastUploadedAt = uploadedAt,
        )
    }

    fun saveLastDebugUpload(response: DebugDumpUploadResponse, uploadedAt: Long) {
        prefs.edit()
            .putString(KEY_DEBUG_LAST_DUMP_ID, response.dumpId)
            .putString(KEY_DEBUG_LAST_REMOTE_DIR, response.remoteDir)
            .putString(KEY_DEBUG_LAST_SYMLINK, response.latestSymlink)
            .putLong(KEY_DEBUG_LAST_UPLOADED_AT, uploadedAt)
            .apply()
    }

    private fun hostKeyPrefKey(host: String, port: Int): String = "${KEY_HOST_KEY_PREFIX}${cleanHost(host)}:$port"

    private fun lastCwdPrefKey(normalizedHost: String): String = "${KEY_LAST_CWD_PREFIX}$normalizedHost"

    companion object {
        const val PREFS_NAME = "herdr_mobile_prefs"
        const val LOCAL_PREFS_NAME = "herdr_mobile_local_prefs"
        private const val DEFAULT_DAEMON_PORT = 7501
        private const val KEY_HOST = "vm_host"
        private const val KEY_PORT = "vm_port"
        private const val KEY_TOKEN = "vm_token"
        private const val KEY_LAST_TAB = "last_tab_id"
        private const val KEY_LAST_CWD_PREFIX = "last_cwd_"
        private const val KEY_SSH_HOST = "ssh_host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_SSH_USERNAME = "ssh_username"
        private const val KEY_SSH_AUTH_METHOD = "ssh_auth_method"
        private const val KEY_SSH_DAEMON_PORT = "ssh_daemon_port"
        private const val KEY_HOST_KEY_PREFIX = "ssh_host_fp_"
        private const val KEY_BOOTSTRAPPED_HOSTS = "bootstrapped_hosts"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_DEBUG_SHARING_ENABLED = "debug_sharing_enabled"
        private const val KEY_DEBUG_INCLUDE_SCREENSHOT = "debug_include_screenshot"
        private const val KEY_DEBUG_INCLUDE_LOGCAT = "debug_include_logcat"
        private const val KEY_DEBUG_AUTO_UPLOAD_ERRORS = "debug_auto_upload_errors"
        private const val KEY_DEBUG_REMOTE_INSPECTION = "debug_remote_inspection"
        private const val KEY_DEBUG_REMOTE_CONTROL = "debug_remote_control"
        private const val KEY_DEBUG_LAST_DUMP_ID = "debug_last_dump_id"
        private const val KEY_DEBUG_LAST_REMOTE_DIR = "debug_last_remote_dir"
        private const val KEY_DEBUG_LAST_SYMLINK = "debug_last_symlink"
        private const val KEY_DEBUG_LAST_UPLOADED_AT = "debug_last_uploaded_at"
    }
}
