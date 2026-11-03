package io.github.infinifar.rustclipsync

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class ConnectionVaultTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val credentials = ConnectionCredentials("https://synthetic.invalid/base", "合成账号", "not-a-real-password-测试")

    @Test fun reopeningRestoresAllFieldsWithoutPlaintextOnDisk() {
        val key = key()
        val saved = ConnectionVault.seal(credentials, key)
        val reopened = ConnectionVault.open(saved.copyOf(), key)
        assertEquals(credentials.server, reopened.server)
        assertEquals(credentials.username, reopened.username)
        assertEquals(credentials.password, reopened.password)
        val disk = saved.toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("synthetic.invalid"))
        assertFalse(disk.contains("not-a-real-password"))
        assertFalse(credentials.toString().contains(credentials.password))
    }

    @Test fun savingSameCredentialsUsesFreshNonce() {
        val key = key()
        assertFalse(ConnectionVault.seal(credentials, key).contentEquals(ConnectionVault.seal(credentials, key)))
    }

    @Test fun tamperedTruncatedWrongKeyAndUnsupportedVersionFailClosed() {
        val key = key()
        val saved = ConnectionVault.seal(credentials, key)
        val tampered = saved.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val version = saved.copyOf().also { it[3] = 99 }
        for (bytes in listOf(tampered, version, saved.copyOf(7), ByteArray(ConnectionVault.MAX_ENVELOPE + 1))) {
            assertThrows(Exception::class.java) { ConnectionVault.open(bytes, key) }
        }
        assertThrows(Exception::class.java) { ConnectionVault.open(saved, key()) }
    }

    @Test fun oversizedConnectionIsRejectedAndBlankFieldsRoundtrip() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionVault.seal(ConnectionCredentials("s", "u", "x".repeat(ConnectionVault.MAX_ENVELOPE)), key())
        }
        val key = key()
        val blank = ConnectionVault.open(ConnectionVault.seal(ConnectionCredentials("s", "", ""), key), key)
        assertEquals("", blank.username); assertEquals("", blank.password)
    }
}
