package com.example.gptest

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gptest.business.CommunityPost
import com.example.gptest.ui.MainActivity
import com.example.gptest.ui.QuoteMonitorHolder
import com.example.gptest.ui.community.CommunityMonitorService
import com.example.gptest.ui.community.CommunityMonitorStore
import com.example.gptest.ui.community.CommunityNotifications
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityMonitorSmokeTest {
    @Test fun entryOpensFromMainMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 10_000
            while (!QuoteMonitorHolder.get(context).uiState.value.watchlistLoaded && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withContentDescription(context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)))
                .perform(click())
            // Overflow popup creation is posted to a subsequent frame by AppCompat.
            var showing = false
            val menuDeadline = System.currentTimeMillis() + 3_000
            while (!showing && System.currentTimeMillis() < menuDeadline) {
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    assertNotNull(toolbar.menu.findItem(R.id.action_community))
                    showing = toolbar.isOverflowMenuShowing
                }
                if (!showing) Thread.sleep(100)
            }
            assertTrue("Overflow menu did not open", showing)
            onView(withText("动态提醒")).perform(click())
            onView(withText("重生之散户一哥")).check(matches(isDisplayed()))
            onView(withText("开启提醒")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
        }
    }

    // Opt-in diagnostic: only run against a disposable emulator with notification permission granted.
    @Test fun livePollingAndNotificationDelivery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("communityLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CommunityMonitorStore.get(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val firstRun = !store.checkpoint.initialized
        val checkedBefore = store.state.value.checkedAt
        val previousInterval = store.state.value.interval
        store.setInterval(15)
        ActivityScenario.launch(com.example.gptest.ui.community.CommunityMonitorActivity::class.java).use { scenario ->
            try {
                onView(withText("开启提醒")).perform(click())
                val deadline = System.currentTimeMillis() + 30_000
                while (store.state.value.checkedAt <= checkedBefore && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                assertTrue(store.state.value.message, store.state.value.running)
                assertTrue(store.state.value.message, store.state.value.checkedAt > checkedBefore)
                assertTrue(store.state.value.posts.isNotEmpty())
                if (firstRun) assertFalse(manager.activeNotifications.any { it.tag?.startsWith("community-post:") == true })
                val firstCheck = store.state.value.checkedAt
                scenario.moveToState(Lifecycle.State.CREATED)
                val secondDeadline = System.currentTimeMillis() + 30_000
                while (store.state.value.checkedAt <= firstCheck && System.currentTimeMillis() < secondDeadline) {
                    Thread.sleep(200)
                }
                assertTrue("Background polling did not continue: ${store.state.value.message}",
                    store.state.value.checkedAt > firstCheck)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val post = CommunityPost(9999999999L, 1795570303, "模拟器通知验证", "https://c.10jqka.com.cn/m/post/1")
                val notifier = CommunityNotifications(context)
                notifier.newPost(post)
                notifier.newPost(post.copy(text = "模拟器通知验证（更新）"))
                val notification = manager.activeNotifications.single { it.tag == "community-post:9999999999" }
                assertEquals("模拟器通知验证（更新）", notification.notification.extras.getString("android.text"))
                assertNotNull(notification.notification.contentIntent)
                onView(withText("停止提醒")).perform(click())
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertFalse(store.state.value.running)
                assertFalse(store.resumeRequested)
            } finally {
                context.startService(Intent(context, CommunityMonitorService::class.java).setAction(CommunityMonitorService.STOP))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                store.setInterval(previousInterval)
                manager.cancel("community-post:9999999999", 1)
            }
        }
    }
}
