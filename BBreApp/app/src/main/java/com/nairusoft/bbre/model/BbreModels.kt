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

/**
 * Plaintext backup structure serialized inside the AES-256-GCM ciphertext payload.
 * Never persisted unencrypted; contains the backed-up user credentials hash record and keys.
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val exportedAt: String,
    val user: User,
    val keys: List<UserKey>
)

/**
 * Encrypted backup envelope written to user-selected `.bbre` files.
 */
@Serializable
data class EncryptedBackupEnvelope(
    val format: String,
    val cipher: String,
    val kdf: String,
    val kdfIterations: Int,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String
)

/**
 * Summary of key import deduplication.
 */
data class KeyImportSummary(
    val importedCount: Int,
    val skippedDuplicates: Int
)

/**
 * Outcome of restoring a backed-up account together with its keys.
 */
sealed class RestoreAccountResult {
    data class Restored(val summary: KeyImportSummary) : RestoreAccountResult()
    object UserAlreadyExists : RestoreAccountResult()
    data class Error(val cause: Throwable? = null, val message: String) : RestoreAccountResult()
}
