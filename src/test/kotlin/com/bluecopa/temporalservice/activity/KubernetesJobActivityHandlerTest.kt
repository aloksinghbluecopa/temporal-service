package com.bluecopa.temporalservice.activity

import com.bluecopa.temporalservice.activity.KubernetesJobActivityHandler.Companion.buildJobName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KubernetesJobActivityHandlerTest {

    @Test
    fun `job name is deterministic for the same workflow id and template name`() {
        val first = buildJobName("wf-123", "build-image")
        val second = buildJobName("wf-123", "build-image")
        assertEquals(first, second)
    }

    @Test
    fun `job name differs across workflow ids and template names`() {
        assertNotEquals(buildJobName("wf-123", "build-image"), buildJobName("wf-456", "build-image"))
        assertNotEquals(buildJobName("wf-123", "build-image"), buildJobName("wf-123", "deploy"))
    }

    @Test
    fun `job name keeps the prefix, sanitizes, and respects the 63-char limit`() {
        val name = buildJobName("wf-1", "Build_Image With Spaces! ${"x".repeat(80)}")
        assertTrue(name.startsWith("ts-"))
        assertTrue(name.length <= 63)
        assertTrue(name.all { it.isLowerCase() || it.isDigit() || it == '-' })
    }
}
