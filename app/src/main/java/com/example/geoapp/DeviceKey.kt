import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

object DeviceKey {
    private const val ALIAS = "device_activation_key"

    fun ensureKeyPair(): KeyPair {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) {
            val priv = ks.getKey(ALIAS, null) as java.security.PrivateKey
            val pub = ks.getCertificate(ALIAS).publicKey
            return KeyPair(pub, priv)
        }

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()

        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }

    fun pubKeyHashB64Url(): String {
        val pubBytes = ensureKeyPair().public.encoded
        val hash = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        return Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE)
    }
}