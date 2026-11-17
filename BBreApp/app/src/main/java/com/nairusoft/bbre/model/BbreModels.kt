package com.nairusoft.bbre.model

import kotlinx.serialization.Serializable

/**
 * Persisted user credential record (salted PBKDF2 hash or legacy SHA-256 awaiting migration).
 */
@Serializable
data class User(
    val username: String,
    val passwordHash: String,
    val salt: String = "",
    val kdfAlgorithm: String = "",
    val kdfIterations: Int = 0
)

/**
 * Persisted bank/payment key belonging to a specific user.
 */
@Serializable
data class UserKey(
    val id: String,
    val type: String,
    val value: String,
    val username: String,
    val createdBy: String = "",
    val creationDate: String = "",
    val modificationDate: String = "",
    val version: String = "0.0.1",
    val description: String = ""
)

/**
 * Outcome of reading an encrypted collection from storage without masking failures as empty lists.
 */
sealed class StorageLoadResult<out T> {
    data class Success<T>(val data: T) : StorageLoadResult<T>()
    data class Error(val cause: Throwable, val message: String) : StorageLoadResult<Nothing>()
}

/**
 * Outcome of mutating an encrypted storage collection.
 */
sealed class StorageOpResult {
    object Success : StorageOpResult()
    object Duplicate : StorageOpResult()
    object NotFound : StorageOpResult()
    data class Error(val cause: Throwable? = null, val message: String) : StorageOpResult()
}
