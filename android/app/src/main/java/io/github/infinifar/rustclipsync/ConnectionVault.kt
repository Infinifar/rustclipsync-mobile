package io.github.infinifar.rustclipsync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Platform connection settings; independent of core's encrypted pairing/state format. */
internal class ConnectionCredentials(val server: String, val username: String, val password: String) {
    override fun toString() = "ConnectionCredentials([redacted])"
}

internal interface ConnectionStore {
    fun load(): ConnectionCredentials?
    fun save(value: ConnectionCredentials)
    fun clear()
}

/** Authenticated, bounded envelope. The Android implementation supplies a non-exportable key. */
internal object ConnectionVault {
    const val MAX_ENVELOPE = 64 * 1024
    private val header = byteArrayOf(82, 67, 67, 1)
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16

    fun seal(value: ConnectionCredentials, key: SecretKey): ByteArray {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                for (field in listOf(value.server, value.username, value.password)) {
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    try {
                        require(encoded.size <= MAX_ENVELOPE) { "connection field too large" }
                        output.writeInt(encoded.size)
                        output.write(encoded)
                    } finally { encoded.fill(0) }
                }
            }
            bytes.toByteArray()
        }
        try {
            require(plain.size + header.size + IV_SIZE + TAG_SIZE <= MAX_ENVELOPE) { "connection too large" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(header)
            check(cipher.iv.size == IV_SIZE)
            return header + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }

    fun open(envelope: ByteArray, key: SecretKey): ConnectionCredentials {
        require(envelope.size in (header.size + IV_SIZE + TAG_SIZE)..MAX_ENVELOPE) { "invalid connection envelope" }
        require(envelope.copyOfRange(0, header.size).contentEquals(header)) { "unsupported connection envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope, header.size, IV_SIZE))
        cipher.updateAAD(header)
        val plain = cipher.doFinal(envelope, header.size + IV_SIZE, envelope.size - header.size - IV_SIZE)
        try {
            return DataInputStream(ByteArrayInputStream(plain)).use { input ->
                fun field(): String {
                    val length = input.readInt()
                    require(length in 0..input.available()) { "invalid connection field" }
                    val encoded = ByteArray(length)
                    try { input.readFully(encoded); return encoded.toString(Charsets.UTF_8) }
                    finally { encoded.fill(0) }
                }
                val value = ConnectionCredentials(field(), field(), field())
                require(input.available() == 0) { "unexpected connection data" }
                value
            }
        } finally { plain.fill(0) }
    }
}
