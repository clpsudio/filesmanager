package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.vault.SoftwareKeyWrapper
import com.clpstudio.filesmanager.vault.VaultKeyFile
import com.clpstudio.filesmanager.vault.VaultKeyLostException
import com.clpstudio.filesmanager.vault.WrongSecretException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The vault key file: master key under PBKDF2(PIN) under the hardware key; both layers needed. */
class VaultKeysTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hw = SoftwareKeyWrapper()
    private fun kf(file: File = File(tmp.root, "keys.properties"), wrapper: SoftwareKeyWrapper = hw) = VaultKeyFile(file, wrapper, iterations = 2000)

    @Test fun createThenUnlockGivesSameMasterKey() {
        val k = kf()
        val mk = k.create(CredentialType.PIN, "123456")
        assertEquals(32, mk.size)
        assertArrayEquals(mk, k.unlock("123456"))
        assertEquals(CredentialType.PIN, k.type())
        assertTrue(k.checkMasterKey(mk))
        assertFalse(k.checkMasterKey(ByteArray(32)))
    }

    @Test fun wrongPinIsWrongSecret() {
        val k = kf()
        k.create(CredentialType.PIN, "123456")
        assertThrows(WrongSecretException::class.java) { k.unlock("123457") }
        assertThrows(WrongSecretException::class.java) { k.unlock("") }
    }

    @Test fun fileHoldsNoSecretInTheClear() {
        val f = File(tmp.root, "keys.properties")
        val k = kf(f)
        val mk = k.create(CredentialType.PASSWORD, "correct horse battery")
        val text = f.readText()
        assertFalse(text.contains("correct horse"))
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(mk)))
    }

    @Test fun changeKeepsMasterKeyAndOldSecretStopsWorking() {
        val k = kf()
        val mk = k.create(CredentialType.PIN, "1111")
        k.change("1111", CredentialType.PASSWORD, "new password!")
        assertArrayEquals(mk, k.unlock("new password!"))
        assertEquals(CredentialType.PASSWORD, k.type())
        assertThrows(WrongSecretException::class.java) { k.unlock("1111") }
    }

    @Test fun changeWithWrongOldSecretFails() {
        val k = kf()
        k.create(CredentialType.PIN, "1111")
        assertThrows(WrongSecretException::class.java) { k.change("2222", CredentialType.PIN, "3333") }
        k.unlock("1111")
    }

    @Test fun anotherPhonesHardwareKeyCantOpenIt() {
        val f = File(tmp.root, "keys.properties")
        kf(f).create(CredentialType.PIN, "123456")
        // Same file, different hardware key (copied to another phone / data restored): lost, even with the right PIN.
        assertThrows(VaultKeyLostException::class.java) { kf(f, SoftwareKeyWrapper()).unlock("123456") }
    }

    @Test fun deletedKeyFileIsLost() {
        val k = kf()
        k.create(CredentialType.PIN, "123456")
        k.delete()
        assertFalse(k.exists)
        assertThrows(VaultKeyLostException::class.java) { k.unlock("123456") }
    }

    @Test fun eachSetupHasItsOwnSaltAndKey() {
        val a = kf(File(tmp.root, "a.properties")).create(CredentialType.PIN, "1234")
        val b = kf(File(tmp.root, "b.properties")).create(CredentialType.PIN, "1234")
        assertFalse(a.contentEquals(b))
    }
}
