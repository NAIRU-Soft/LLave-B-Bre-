package com.nairusoft.bbre

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nairusoft.bbre.security.StorageManager
import com.nairusoft.bbre.ui.LoginActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoginAndDashboardUiTest {

    private var createdUsername: String? = null

    @After
    fun cleanup() = runBlocking {
        createdUsername?.let { username ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            StorageManager.getInstance(context).deleteUser(username)
        }
        Unit
    }

    @Test
    fun registerLoginCreateAndDeleteKey_endToEnd() {
        val username = "uitest_${UUID.randomUUID().toString().take(6)}"
        createdUsername = username

        ActivityScenario.launch(LoginActivity::class.java).use {
            // Switch to Register mode
            onView(withId(R.id.tvToggleMode)).perform(scrollTo(), click())
            onView(withId(R.id.etUsername)).perform(replaceText(username), closeSoftKeyboard())
            onView(withId(R.id.etPassword)).perform(replaceText("ClaveUiTest1"), closeSoftKeyboard())
            onView(withId(R.id.btnAction)).perform(scrollTo(), click())

            // Login with created user
            onView(withId(R.id.etPassword)).perform(replaceText("ClaveUiTest1"), closeSoftKeyboard())
            onView(withId(R.id.btnAction)).perform(scrollTo(), click())

            // Dashboard visible with empty state
            onView(withId(R.id.fabAddKey)).check(matches(isDisplayed()))
            onView(withId(R.id.fabAddKey)).perform(click())

            // Create key with generated random code
            onView(withId(R.id.etDescription)).perform(replaceText("Cuenta Principal"), closeSoftKeyboard())
            onView(withId(R.id.btnGenerateKey)).perform(click())
            onView(withId(R.id.btnSave)).perform(click())

            // Key card appears in RecyclerView
            onView(withText("Descripción: Cuenta Principal")).check(matches(isDisplayed()))
        }
    }
}
