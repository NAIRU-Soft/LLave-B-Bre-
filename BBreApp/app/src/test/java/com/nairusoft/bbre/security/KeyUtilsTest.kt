package com.nairusoft.bbre.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyUtilsTest {

    @Test
    fun incrementPatchVersion_incrementsPatchAndHandlesMalformed() {
        assertEquals("0.0.2", KeyUtils.incrementPatchVersion("0.0.1"))
        assertEquals("0.0.10", KeyUtils.incrementPatchVersion("0.0.9"))
        assertEquals("1.2.4", KeyUtils.incrementPatchVersion("1.2.3"))
        assertEquals("0.0.2", KeyUtils.incrementPatchVersion(""))
        assertEquals("0.0.2", KeyUtils.incrementPatchVersion("invalid"))
    }

    @Test
    fun normalizeField_collapsesWhitespaceAndTrims() {
        assertEquals("Banco de Bogotá", KeyUtils.normalizeField("   Banco   de  Bogotá  "))
        assertEquals("3001234567", KeyUtils.normalizeKeyValue("  3001234567  "))
    }

    @Test
    fun buildBankOptions_mergesDeduplicatesAndSortsCustomBanks() {
        val customOption = "+ Agregar nuevo banco..."
        val keys = listOf(
            UserKey(id = "1", type = "bancolombia", value = "111", username = "ana"),
            UserKey(id = "2", type = "Nu Colombia", value = "222", username = "ana"),
            UserKey(id = "3", type = "nu colombia", value = "333", username = "ana"),
            UserKey(id = "4", type = "Banco Caja Social", value = "444", username = "ana")
        )

        val options = KeyUtils.buildBankOptions(keys, customOption)

        assertEquals(customOption, options.last())
        assertEquals(1, options.count { it.equals("Bancolombia", ignoreCase = true) })
        assertEquals(1, options.count { it.equals("Nu Colombia", ignoreCase = true) })
        assertTrue(options.contains("Banco Caja Social"))

        val withoutCustomOption = options.dropLast(1)
        assertEquals(withoutCustomOption.sortedWith(String.CASE_INSENSITIVE_ORDER), withoutCustomOption)
    }

    @Test
    fun generateUniqueAlphanumericCode_avoidsExistingValues() {
        val existing = setOf("ABC1234567", "XYZ9876543")
        val generated = KeyUtils.generateUniqueAlphanumericCode(existing, length = 10)

        assertEquals(10, generated.length)
        assertFalse(existing.contains(generated))
        assertTrue(generated.all { it.isLetterOrDigit() })
    }
}
