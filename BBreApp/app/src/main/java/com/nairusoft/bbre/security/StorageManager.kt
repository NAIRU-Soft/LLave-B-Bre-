package com.nairusoft.bbre.security

import android.content.Context
import com.nairusoft.bbre.model.KeyImportSummary
import com.nairusoft.bbre.model.RestoreAccountResult
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.User
import com.nairusoft.bbre.model.UserKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class StorageManager(
    private val filesDir: File,
    private val cryptoProvider: CryptoProvider
) {

    interface CryptoProvider {
        fun ensureKey(alias: String): Boolean
        fun encrypt(plainText: String, alias: String): Result<String>
        fun decrypt(cipherText: String, alias: String): Result<String>
    }

    private class SecurityManagerCryptoProvider(
        private val securityManager: SecurityManager
    ) : CryptoProvider {
        override fun ensureKey(alias: String): Boolean {
            return securityManager.generateStandardKey(alias) != null
        }

        override fun encrypt(plainText: String, alias: String): Result<String> {
            return securityManager.encryptToBase64Result(plainText, alias)
        }

        override fun decrypt(cipherText: String, alias: String): Result<String> {
            return securityManager.decryptFromBase64Result(cipherText, alias)
        }
    }

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        SecurityManagerCryptoProvider(SecurityManager.getInstance(context.applicationContext))
    )

    companion object {
        private const val USERS_FILE = "usuarios.json"
        private const val KEYS_FILE = "llaves.json"
        private const val KEY_ALIAS = "bbre_db_key"

        @Volatile
        private var instance: StorageManager? = null

        fun getInstance(context: Context): StorageManager {
            return instance ?: synchronized(this) {
                instance ?: StorageManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val ioMutex = Mutex()

    init {
        cryptoProvider.ensureKey(KEY_ALIAS)
    }

    // --- Internal I/O Helpers (always called under ioMutex & Dispatchers.IO) ---

    private inline fun <reified T> readEncryptedList(fileName: String): StorageLoadResult<List<T>> {
        val file = File(filesDir, fileName)
        if (!file.exists()) {
            return StorageLoadResult.Success(emptyList())
        }
        return try {
            val encryptedText = file.readText(StandardCharsets.UTF_8)
            if (encryptedText.isBlank()) {
                return StorageLoadResult.Error(
                    IllegalStateException("Archivo vacío o corrupto: $fileName"),
                    "No se pudo leer $fileName porque está vacío o dañado."
                )
            }
            val decryptedResult = cryptoProvider.decrypt(encryptedText, KEY_ALIAS)
            val decryptedText = decryptedResult.getOrElse { error ->
                return StorageLoadResult.Error(
                    error,
                    "No se pudo descifrar $fileName. Los datos existentes se conservaron sin cambios."
                )
            }
            val decoded = json.decodeFromString<List<T>>(decryptedText)
            StorageLoadResult.Success(decoded)
        } catch (e: Exception) {
            StorageLoadResult.Error(
                e,
                "Error al procesar $fileName. Los datos existentes no fueron modificados."
            )
        }
    }

    private inline fun <reified T> writeEncryptedList(fileName: String, items: List<T>): Result<Unit> {
        return try {
            if (!cryptoProvider.ensureKey(KEY_ALIAS)) {
                return Result.failure(IllegalStateException("Clave de almacenamiento no disponible"))
            }
            val targetFile = File(filesDir, fileName)
            val jsonText = json.encodeToString(items)
            val encryptedText = cryptoProvider.encrypt(jsonText, KEY_ALIAS).getOrElse { error ->
                return Result.failure(error)
            }
            writeFileAtomically(targetFile, encryptedText)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun writeFileAtomically(targetFile: File, content: String) {
        val parent = targetFile.parentFile
            ?: throw IOException("Directorio destino inválido para ${targetFile.name}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("No se pudo crear el directorio ${parent.absolutePath}")
        }

        val tempFile = File(parent, "${targetFile.name}.tmp.${UUID.randomUUID()}")
        try {
            FileOutputStream(tempFile).use { fos ->
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    // --- User Operations ---

    suspend fun getUsersResult(): StorageLoadResult<List<User>> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            readEncryptedList<User>(USERS_FILE)
        }
    }

    suspend fun addUser(user: User): StorageOpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val users = when (val load = readEncryptedList<User>(USERS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(load.cause, load.message)
            }
            if (users.any { it.username.equals(user.username, ignoreCase = true) }) {
                return@withLock StorageOpResult.Duplicate
            }
            users.add(user)
            writeEncryptedList(USERS_FILE, users).fold(
                onSuccess = { StorageOpResult.Success },
                onFailure = { StorageOpResult.Error(it, "No se pudo guardar el usuario") }
            )
        }
    }

    suspend fun updateUser(oldUsername: String, updatedUser: User): StorageOpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val users = when (val load = readEncryptedList<User>(USERS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(load.cause, load.message)
            }
            val index = users.indexOfFirst { it.username.equals(oldUsername, ignoreCase = true) }
            if (index == -1) return@withLock StorageOpResult.NotFound

            // If username is changing, ensure new one is unique
            if (!oldUsername.equals(updatedUser.username, ignoreCase = true) &&
                users.any { it.username.equals(updatedUser.username, ignoreCase = true) }
            ) {
                return@withLock StorageOpResult.Duplicate
            }

            // Update corresponding keys owner if username changed (abort before saving users if keys fail)
            if (!oldUsername.equals(updatedUser.username, ignoreCase = true)) {
                val keys = when (val keysLoad = readEncryptedList<UserKey>(KEYS_FILE)) {
                    is StorageLoadResult.Success -> keysLoad.data.toMutableList()
                    is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(keysLoad.cause, keysLoad.message)
                }
                var keysChanged = false
                for (i in keys.indices) {
                    if (keys[i].username.equals(oldUsername, ignoreCase = true)) {
                        keys[i] = keys[i].copy(username = updatedUser.username)
                        keysChanged = true
                    }
                }
                if (keysChanged) {
                    writeEncryptedList(KEYS_FILE, keys).onFailure {
                        return@withLock StorageOpResult.Error(it, "No se pudieron actualizar las llaves del usuario")
                    }
                }
            }

            users[index] = updatedUser
            writeEncryptedList(USERS_FILE, users).fold(
                onSuccess = { StorageOpResult.Success },
                onFailure = { StorageOpResult.Error(it, "No se pudo actualizar el perfil") }
            )
        }
    }

    suspend fun deleteUser(username: String): StorageOpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val users = when (val load = readEncryptedList<User>(USERS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(load.cause, load.message)
            }
            val index = users.indexOfFirst { it.username.equals(username, ignoreCase = true) }
            if (index == -1) return@withLock StorageOpResult.NotFound

            val keys = when (val keysLoad = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> keysLoad.data
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(keysLoad.cause, keysLoad.message)
            }

            users.removeAt(index)
            writeEncryptedList(USERS_FILE, users).onFailure {
                return@withLock StorageOpResult.Error(it, "No se pudo eliminar el usuario")
            }

            val remainingKeys = keys.filter { !it.username.equals(username, ignoreCase = true) }
            if (remainingKeys.size != keys.size) {
                writeEncryptedList(KEYS_FILE, remainingKeys).onFailure {
                    return@withLock StorageOpResult.Error(it, "Usuario eliminado, pero falló la limpieza de llaves")
                }
            }
            StorageOpResult.Success
        }
    }

    // --- Key Operations ---

    suspend fun getKeysForUserResult(username: String): StorageLoadResult<List<UserKey>> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            when (val load = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> StorageLoadResult.Success(
                    load.data.filter { it.username.equals(username, ignoreCase = true) }
                )
                is StorageLoadResult.Error -> load
            }
        }
    }

    suspend fun addKey(key: UserKey): StorageOpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val keys = when (val load = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(load.cause, load.message)
            }
            val normalizedKey = key.copy(
                type = KeyUtils.normalizeField(key.type),
                value = KeyUtils.normalizeKeyValue(key.value),
                description = KeyUtils.normalizeField(key.description)
            )
            if (keys.any {
                    it.id == normalizedKey.id || (
                        KeyUtils.normalizeField(it.type).equals(normalizedKey.type, ignoreCase = true) &&
                            KeyUtils.normalizeKeyValue(it.value).equals(normalizedKey.value, ignoreCase = true) &&
                            it.username.equals(normalizedKey.username, ignoreCase = true)
                        )
                }
            ) {
                return@withLock StorageOpResult.Duplicate
            }
            keys.add(normalizedKey)
            writeEncryptedList(KEYS_FILE, keys).fold(
                onSuccess = { StorageOpResult.Success },
                onFailure = { StorageOpResult.Error(it, "No se pudo guardar la llave") }
            )
        }
    }

    suspend fun updateKey(updatedKey: UserKey): StorageOpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val keys = when (val load = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(load.cause, load.message)
            }
            val index = keys.indexOfFirst { it.id == updatedKey.id }
            if (index == -1) return@withLock StorageOpResult.NotFound

            val normalizedKey = updatedKey.copy(
                type = KeyUtils.normalizeField(updatedKey.type),
                value = KeyUtils.normalizeKeyValue(updatedKey.value),
                description = KeyUtils.normalizeField(updatedKey.description)
            )
            if (keys.any {
                    it.id != normalizedKey.id &&
                        KeyUtils.normalizeField(it.type).equals(normalizedKey.type, ignoreCase = true) &&
                        KeyUtils.normalizeKeyValue(it.value).equals(normalizedKey.value, ignoreCase = true) &&
                        it.username.equals(normalizedKey.username, ignoreCase = true)
                }
            ) {
                return@withLock StorageOpResult.Duplicate
            }

            keys[index] = normalizedKey
            writeEncryptedList(KEYS_FILE, keys).fold(
                onSuccess = { StorageOpResult.Success },
                onFailure = { StorageOpResult.Error(it, "No se pudo actualizar la llave") }
            )
        }
    }

    suspend fun deleteKey(id: String): StorageOpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val keys = when (val load = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock StorageOpResult.Error(load.cause, load.message)
            }
            val index = keys.indexOfFirst { it.id == id }
            if (index == -1) return@withLock StorageOpResult.NotFound
            keys.removeAt(index)
            writeEncryptedList(KEYS_FILE, keys).fold(
                onSuccess = { StorageOpResult.Success },
                onFailure = { StorageOpResult.Error(it, "No se pudo eliminar la llave") }
            )
        }
    }

    /**
     * Merges [incomingKeys] into [targetUsername] under [ioMutex], skipping per-user duplicates
     * and assigning fresh UUIDs if an imported id collides with another user's existing key.
     */
    suspend fun importKeysForUser(
        targetUsername: String,
        incomingKeys: List<UserKey>
    ): Result<KeyImportSummary> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val existingKeys = when (val load = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock Result.failure(load.cause)
            }
            val summary = mergeIncomingKeys(existingKeys, targetUsername, incomingKeys)
            if (summary.importedCount > 0) {
                writeEncryptedList(KEYS_FILE, existingKeys).onFailure { error ->
                    return@withLock Result.failure(error)
                }
            }
            Result.success(summary)
        }
    }

    /**
     * Restores an absent [user] record together with its [incomingKeys] atomically without modifying
     * or overwriting any already registered users or keys.
     */
    suspend fun restoreAccountWithKeys(
        user: User,
        incomingKeys: List<UserKey>
    ): RestoreAccountResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val users = when (val load = readEncryptedList<User>(USERS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock RestoreAccountResult.Error(load.cause, load.message)
            }
            if (users.any { it.username.equals(user.username, ignoreCase = true) }) {
                return@withLock RestoreAccountResult.UserAlreadyExists
            }

            val keys = when (val load = readEncryptedList<UserKey>(KEYS_FILE)) {
                is StorageLoadResult.Success -> load.data.toMutableList()
                is StorageLoadResult.Error -> return@withLock RestoreAccountResult.Error(load.cause, load.message)
            }

            val summary = mergeIncomingKeys(keys, user.username, incomingKeys)
            if (summary.importedCount > 0) {
                writeEncryptedList(KEYS_FILE, keys).onFailure { error ->
                    return@withLock RestoreAccountResult.Error(error, "No se pudieron restaurar las llaves de la cuenta")
                }
            }

            users.add(user)
            writeEncryptedList(USERS_FILE, users).fold(
                onSuccess = { RestoreAccountResult.Restored(summary) },
                onFailure = { RestoreAccountResult.Error(it, "No se pudo restaurar el usuario del respaldo") }
            )
        }
    }

    private fun mergeIncomingKeys(
        targetList: MutableList<UserKey>,
        targetUsername: String,
        incomingKeys: List<UserKey>
    ): KeyImportSummary {
        var importedCount = 0
        var skippedDuplicates = 0

        for (rawKey in incomingKeys) {
            val normalizedType = KeyUtils.normalizeField(rawKey.type)
            val normalizedValue = KeyUtils.normalizeKeyValue(rawKey.value)
            val normalizedDesc = KeyUtils.normalizeField(rawKey.description)
            if (normalizedType.isEmpty() || normalizedValue.isEmpty()) {
                skippedDuplicates++
                continue
            }

            val duplicateForTarget = targetList.any { existing ->
                existing.username.equals(targetUsername, ignoreCase = true) && (
                    existing.id == rawKey.id || (
                        KeyUtils.normalizeField(existing.type).equals(normalizedType, ignoreCase = true) &&
                            KeyUtils.normalizeKeyValue(existing.value).equals(normalizedValue, ignoreCase = true)
                        )
                    )
            }
            if (duplicateForTarget) {
                skippedDuplicates++
                continue
            }

            val assignedId = if (rawKey.id.isBlank() || targetList.any { it.id == rawKey.id }) {
                UUID.randomUUID().toString()
            } else {
                rawKey.id
            }

            val resolvedCreatedBy = KeyUtils.normalizeField(rawKey.createdBy).ifEmpty { targetUsername }
            targetList.add(
                UserKey(
                    id = assignedId,
                    type = normalizedType,
                    value = normalizedValue,
                    username = targetUsername,
                    createdBy = resolvedCreatedBy,
                    creationDate = rawKey.creationDate,
                    modificationDate = rawKey.modificationDate,
                    version = rawKey.version.ifBlank { "0.0.1" },
                    description = normalizedDesc
                )
            )
            importedCount++
        }

        return KeyImportSummary(importedCount = importedCount, skippedDuplicates = skippedDuplicates)
    }
}
