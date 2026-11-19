package com.clipforge.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    private val permissions: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else RuleChain.emptyRuleChain()

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    @Test
    fun everyScreenOpens() {
        compose.onNodeWithTag("screen_dashboard").assertExists()
        for ((tab, screen) in listOf("tab_new" to "screen_new", "tab_queue" to "screen_queue",
            "tab_clips" to "screen_projects", "tab_exports" to "screen_exports", "tab_home" to "screen_dashboard")) {
            compose.onNodeWithTag(tab).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(screen).assertExists()
        }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screen_settings").assertExists()
        compose.onNodeWithTag("nav_ownership").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTagExists("public_key_pem")
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagExists(tag: String): Boolean =
        try {
            onNodeWithTag(tag).assertExists(); true
        } catch (e: AssertionError) {
            false
        }
}
