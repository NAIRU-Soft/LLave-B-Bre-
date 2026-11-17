package com.nairusoft.bbre

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.UserKey
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.StorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BbreStorageInstrumentedTest {

    @Test
    fun keystoreEncryptionAndStorageCrud_roundTripSuccessfully() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val securityManager = SecurityManager.getInstance(context)
        val storageManager = StorageManager.getInstance(context)

        val alias = "bbre_test_key_${UUID.randomUUID()}"
        securityManager.generateStandardKey(alias)

        val encrypted = securityManager.encryptToBase64("DatoSecretoBBre", alias)
        assertEquals("DatoSecretoBBre", securityManager.decryptFromBase64(encrypted!!, alias))
        assertNull(securityManager.decrypt(byteArrayOf(1, 2, 3), alias))

        val testUser = "inst_user_${UUID.randomUUID().toString().take(8)}"
        val creds = securityManager.createUserCredentials(testUser, "ClaveTest123")
        assertEquals(StorageOpResult.Success, storageManager.addUser(creds))

        val key = UserKey(
            id = UUID.randomUUID().toString(),
            type = "Bancolombia",
            value = "3001234567",
            username = testUser,
            createdBy = testUser,
            description = "Prueba"
        )
        assertEquals(StorageOpResult.Success, storageManager.addKey(key))

        val loaded = storageManager.getKeysForUserResult(testUser)
        assertTrue(loaded is StorageLoadResult.Success && loaded.data.size == 1)

        assertEquals(StorageOpResult.Success, storageManager.deleteUser(testUser))
    }
}
