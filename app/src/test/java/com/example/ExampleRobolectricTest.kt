package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.JarvisHttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    assertEquals("Andra Control", appName)
  }

  @Test
  fun `jarvis http server starts and stops cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val server = JarvisHttpServer(
      context = context,
      port = 18765,
      token = "test-token",
      onLog = {}
    )
    val started = server.start()
    assertTrue("Server should start successfully", started)
    assertTrue("Server isRunning should be true", server.isRunning.value)
    server.stop()
    assertTrue("Server isRunning should be false after stop", !server.isRunning.value)
  }
}
