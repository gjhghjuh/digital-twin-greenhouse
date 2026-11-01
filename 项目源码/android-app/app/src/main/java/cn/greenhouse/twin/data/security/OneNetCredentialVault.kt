package cn.greenhouse.twin.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// 密钥仅存于应用私有目录，并由不可导出的 AndroidKeyStore 密钥加密。
class OneNetCredentialVault(context: Context) {
    private val credentialFile = AtomicFile(File(context.applicationContext.filesDir, "onenet-credentials.enc"))

    fun save(accessKey: String) = synchronized(lock) {
        require(accessKey.isNotBlank()) { "OneNET API 密钥不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(accessKey.toByteArray(Charsets.UTF_8))
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FILE_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            buffer.toByteArray()
        }
        val stream = credentialFile.startWrite()
        try {
            stream.write(bytes)
            credentialFile.finishWrite(stream)
        } catch (error: Exception) {
            credentialFile.failWrite(stream)
            throw error
        }
    }

    fun read(): String? = synchronized(lock) {
        if (!credentialFile.baseFile.exists()) return@synchronized null
        val stored = credentialFile.readFully()
        val (iv, encrypted) = DataInputStream(ByteArrayInputStream(stored)).use { input ->
            check(input.readInt() == FILE_VERSION) { "OneNET 凭据文件版本不正确" }
            val ivLength = input.readInt()
            check(ivLength == 12) { "OneNET 凭据初始化向量不正确" }
            val nonce = ByteArray(ivLength).also(input::readFully)
            val encryptedLength = input.readInt()
            check(encryptedLength in 16..MAX_CREDENTIAL_BYTES && encryptedLength == input.available()) {
                "OneNET 凭据文件长度不正确"
            }
            nonce to ByteArray(encryptedLength).also(input::readFully)
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("OneNET 加密密钥不存在，须重新导入连接配置")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        val lock = Any()
        const val KEY_ALIAS = "greenhouse-onenet-api-key-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FILE_VERSION = 1
        const val MAX_CREDENTIAL_BYTES = 65_536
    }
}
