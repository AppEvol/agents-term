package com.example.data

import android.content.Context

interface ConnectionSettings {
    fun loadConnection(): ConnectionConfig
    fun saveConnection(config: ConnectionConfig)
    fun loadLastTabId(): String?
    fun saveLastTabId(tabId: String?)
}

class SettingsStore(context: Context) : ConnectionSettings {
    private val prefs = context.getSharedPreferences("herdr_mobile_prefs", Context.MODE_PRIVATE)

    override fun loadConnection(): ConnectionConfig {
        return ConnectionConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 7501),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        )
    }

    override fun saveConnection(config: ConnectionConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token.trim())
            .apply()
    }

    override fun loadLastTabId(): String? = prefs.getString(KEY_LAST_TAB, null)

    override fun saveLastTabId(tabId: String?) {
        prefs.edit().putString(KEY_LAST_TAB, tabId).apply()
    }

    /**
     * Loads non-secret SSH fields (host, port, username, auth mode, daemon port).
     * Passwords and private keys are intentionally never stored on disk.
     */
    fun loadSshProfile(): SshProfile {
        val host = prefs.getString(KEY_SSH_HOST, null)
            ?: prefs.getString(KEY_HOST, "").orEmpty()
        val normalizedHost = cleanHost(host)
        return SshProfile(
            host = host,
            sshPort = prefs.getInt(KEY_SSH_PORT, 22),
            username = prefs.getString(KEY_SSH_USERNAME, "").orEmpty(),
            authMethod = SshAuthMethod.fromStorageKey(prefs.getString(KEY_SSH_AUTH_METHOD, null)),
            daemonPort = prefs.getInt(KEY_SSH_DAEMON_PORT, 7501),
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

    fun getTrustedHostFingerprint(host: String, port: Int): String? {
        return prefs.getString(hostKeyPrefKey(host, port), null)
    }

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

    fun loadDebugSharingSettings(): DebugSharingSettings {
        return DebugSharingSettings(
            enabled = prefs.getBoolean(KEY_DEBUG_SHARING_ENABLED, false),
            includeScreenshot = prefs.getBoolean(KEY_DEBUG_INCLUDE_SCREENSHOT, true),
            includeLogcat = prefs.getBoolean(KEY_DEBUG_INCLUDE_LOGCAT, true),
            autoUploadOnError = prefs.getBoolean(KEY_DEBUG_AUTO_UPLOAD_ERRORS, false),
        )
    }

    fun saveDebugSharingSettings(settings: DebugSharingSettings) {
        prefs.edit()
            .putBoolean(KEY_DEBUG_SHARING_ENABLED, settings.enabled)
            .putBoolean(KEY_DEBUG_INCLUDE_SCREENSHOT, settings.includeScreenshot)
            .putBoolean(KEY_DEBUG_INCLUDE_LOGCAT, settings.includeLogcat)
            .putBoolean(KEY_DEBUG_AUTO_UPLOAD_ERRORS, settings.autoUploadOnError)
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

    private fun hostKeyPrefKey(host: String, port: Int): String {
        return "${KEY_HOST_KEY_PREFIX}${cleanHost(host)}:$port"
    }

    companion object {
        private const val KEY_HOST = "vm_host"
        private const val KEY_PORT = "vm_port"
        private const val KEY_TOKEN = "vm_token"
        private const val KEY_LAST_TAB = "last_tab_id"
        private const val KEY_SSH_HOST = "ssh_host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_SSH_USERNAME = "ssh_username"
        private const val KEY_SSH_AUTH_METHOD = "ssh_auth_method"
        private const val KEY_SSH_DAEMON_PORT = "ssh_daemon_port"
        private const val KEY_HOST_KEY_PREFIX = "ssh_host_fp_"
        private const val KEY_BOOTSTRAPPED_HOSTS = "bootstrapped_hosts"
        private const val KEY_DEBUG_SHARING_ENABLED = "debug_sharing_enabled"
        private const val KEY_DEBUG_INCLUDE_SCREENSHOT = "debug_include_screenshot"
        private const val KEY_DEBUG_INCLUDE_LOGCAT = "debug_include_logcat"
        private const val KEY_DEBUG_AUTO_UPLOAD_ERRORS = "debug_auto_upload_errors"
        private const val KEY_DEBUG_LAST_DUMP_ID = "debug_last_dump_id"
        private const val KEY_DEBUG_LAST_REMOTE_DIR = "debug_last_remote_dir"
        private const val KEY_DEBUG_LAST_SYMLINK = "debug_last_symlink"
        private const val KEY_DEBUG_LAST_UPLOADED_AT = "debug_last_uploaded_at"
    }
}
