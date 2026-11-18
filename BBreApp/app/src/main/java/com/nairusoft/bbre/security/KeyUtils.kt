package com.nairusoft.bbre.security

import com.nairusoft.bbre.model.UserKey
import java.security.SecureRandom

/**
 * Pure-JVM helpers for BBre key normalization, semantic patch versioning,
 * bank list merging, and unique alphanumeric key generation.
 */
object KeyUtils {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val ALPHANUMERIC_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    val DEFAULT_BANKS: List<String> = listOf(
        "Banco de la República",
        "Bancolombia",
        "Davivienda",
        "Daviplata",
        "Nequi"
    )

    fun normalizeField(raw: String): String {
        return raw.trim().replace(WHITESPACE_REGEX, " ")
    }

    fun normalizeKeyValue(raw: String): String {
        return raw.trim()
    }

    fun incrementPatchVersion(version: String): String {
        val parts = version.trim().split(".")
        if (parts.size == 3) {
            val major = parts[0].toIntOrNull()
            val minor = parts[1].toIntOrNull()
            val patch = parts[2].toIntOrNull()
            if (major != null && minor != null && patch != null && major >= 0 && minor >= 0 && patch >= 0) {
                return "$major.$minor.${patch + 1}"
            }
        }
        return "0.0.2"
    }

    fun buildBankOptions(
        userKeys: List<UserKey>,
        customBankOption: String
    ): List<String> {
        val seenLower = LinkedHashMap<String, String>()
        for (bank in DEFAULT_BANKS) {
            val normalized = normalizeField(bank)
            seenLower.putIfAbsent(normalized.lowercase(), normalized)
        }
        for (key in userKeys) {
            val normalized = normalizeField(key.type)
            if (normalized.isNotEmpty() && !normalized.equals(customBankOption, ignoreCase = true)) {
                seenLower.putIfAbsent(normalized.lowercase(), normalized)
            }
        }
        val sortedBanks = seenLower.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
        return sortedBanks + customBankOption
    }

    fun generateUniqueAlphanumericCode(
        existingValues: Collection<String>,
        length: Int = 10
    ): String {
        val normalizedExisting = existingValues.mapTo(HashSet()) { it.trim().uppercase() }
        repeat(64) {
            val candidate = buildString(length) {
                repeat(length) {
                    append(ALPHANUMERIC_CHARS[secureRandom.nextInt(ALPHANUMERIC_CHARS.length)])
                }
            }
            if (!normalizedExisting.contains(candidate)) {
                return candidate
            }
        }
        // Guaranteed fallback with timestamp suffix if unlikely collision occurs
        return buildString(length) {
            repeat(length) {
                append(ALPHANUMERIC_CHARS[secureRandom.nextInt(ALPHANUMERIC_CHARS.length)])
            }
        }
    }
}
