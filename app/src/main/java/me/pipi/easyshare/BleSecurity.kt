package me.pipi.easyshare

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object BleSecurity {
    /**
     * EC key pair for one BLE session. The receiver creates a fresh pair per service instance
     * and after every accepted request, the sender one per outgoing task, so sessions never
     * share key material and the advertised public key cannot be used to track a device.
     */
    class SessionKeyPair private constructor(
        private val privateKey: ECPrivateKey,
        publicKey: ECPublicKey,
    ) {
        val encodedPublicKey: String = Base64.getEncoder().encodeToString(publicKey.encoded)

        fun deriveSessionKey(peerPublicKey: String, cryptoVersion: Int? = null): SessionCipher {
            val kf = KeyFactory.getInstance("EC")
            val otherPublicKey =
                kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(peerPublicKey)))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(otherPublicKey, true)
            return if (cryptoVersion != null && cryptoVersion >= MODERN_CRYPTO_VERSION) {
                val secret = agreement.generateSecret()
                SessionCipher(
                    key = SecretKeySpec(hkdfSha256(secret), "AES"),
                    authenticated = true,
                )
            } else {
                val secret = agreement.generateSecret("TlsPremasterSecret")
                SessionCipher(SecretKeySpec(secret.encoded, "AES"), authenticated = false)
            }
        }

        companion object {
            fun generate(): SessionKeyPair {
                val generator = KeyPairGenerator.getInstance("EC")
                generator.initialize(256)
                val keyPair = generator.generateKeyPair()
                return SessionKeyPair(
                    keyPair.private as ECPrivateKey,
                    keyPair.public as ECPublicKey,
                )
            }
        }
    }

    private fun hkdfSha256(secret: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(secret)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
        expand.update(HKDF_INFO)
        expand.update(1.toByte())
        return expand.doFinal().copyOf(32)
    }

    class SessionCipher(
        private val key: SecretKeySpec,
        private val authenticated: Boolean,
    ) {
        fun decrypt(encodedData: String): String = decrypt("value", encodedData)

        fun decrypt(field: String, encodedData: String): String {
            val data = Base64.getDecoder().decode(encodedData)
            if (authenticated) {
                require(data.size >= GCM_NONCE_BYTES + GCM_TAG_BYTES)
                val nonce = data.copyOfRange(0, GCM_NONCE_BYTES)
                val cipherText = data.copyOfRange(GCM_NONCE_BYTES, data.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
                return cipher.doFinal(cipherText).decodeToString(throwOnInvalidSequence = true)
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(LEGACY_IV))
            return cipher.doFinal(data).decodeToString(throwOnInvalidSequence = true)
        }

        fun encrypt(data: String): String = encrypt("value", data)

        fun encrypt(field: String, data: String): String {
            if (authenticated) {
                val nonce = ByteArray(GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
                return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(data.toByteArray()))
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(LEGACY_IV))
            return Base64.getEncoder()
                .encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
        }
    }

    /** ECDH + HKDF + AES-GCM for the Wi-Fi Direct credentials. */
    const val MODERN_CRYPTO_VERSION = 2

    /** Like [MODERN_CRYPTO_VERSION], but the session token and certificate fingerprint are encrypted too. */
    const val PROTECTED_SESSION_CRYPTO_VERSION = 3
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private val LEGACY_IV = "0102030405060708".toByteArray(Charsets.US_ASCII)
    private val HKDF_SALT = "Easy Share BLE v2".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "P2P metadata AES-GCM".toByteArray(Charsets.UTF_8)
}
