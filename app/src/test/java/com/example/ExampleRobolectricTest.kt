package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AgentsTerm", appName)
        assertEquals("Settings", context.getString(R.string.settings_title))
        assertEquals("Settings & Debug", context.getString(R.string.action_app_settings))
    }

    @Test
    fun `settings store persists last cwd per host`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)

        assertNull(store.loadLastCwd("100.71.219.26"))
        store.saveLastCwd("100.71.219.26", "/home/ned/projects/agents-term")
        store.saveLastCwd("dev-vm.ts.net", "/home/alice/work")

        assertEquals("/home/ned/projects/agents-term", store.loadLastCwd("100.71.219.26"))
        assertEquals("/home/alice/work", store.loadLastCwd("dev-vm.ts.net"))

        store.saveLastCwd("100.71.219.26", null)
        assertNull(store.loadLastCwd("100.71.219.26"))
        assertEquals("/home/alice/work", store.loadLastCwd("dev-vm.ts.net"))
    }
}
