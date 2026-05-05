package com.projecttemp.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRequestValidationTest {
    @Test
    fun `rejects non-http icon url values`() {
        val error =
            assertThrows(UserInputException::class.java) {
                buildRequestFromForm(
                    appName = "Demo",
                    websiteUrl = "https://example.com",
                    iconUrl = "C:/private/icon.png",
                    applicationId = null,
                    versionCode = "1",
                    versionName = "1.0.0",
                    minSdk = "21",
                    allowCleartext = false,
                    iconBackgroundColor = "#FFFFFF",
                    userAgentSuffix = null
                )
            }

        assertTrue(error.message.orEmpty().contains("Icon URL"))
    }

    @Test
    fun `accepts valid remote icon url`() {
        val request =
            buildRequestFromForm(
                appName = "Demo",
                websiteUrl = "https://example.com",
                iconUrl = "https://cdn.example.com/icon.png",
                applicationId = null,
                versionCode = "3",
                versionName = "2.0.0",
                minSdk = "24",
                allowCleartext = false,
                iconBackgroundColor = "#ABCDEF",
                userAgentSuffix = "demo"
            )

        assertEquals("https://cdn.example.com/icon.png", request.iconUrl)
        assertEquals(24, request.minSdk)
        assertEquals("#ABCDEF", request.iconBackgroundColor)
    }
}
