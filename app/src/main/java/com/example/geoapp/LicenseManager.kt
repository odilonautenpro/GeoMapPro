import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object LicenseManager {
    private const val COMPANY_PUBKEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmn3jVtzj6lfEJS9i82QSz5iF+VT8ksGW8tWMDIIZJhQ24E4ahNbxTi8ESExS7NUE1W4YFGu+5u0bZLQ3zZu8PQ=="

    fun buildActivationRequest(ctx: Context, versionCode: Int): String {
        DeviceKey.ensureKeyPair()

        val payload = JSONObject()
            .put("pkg", ctx.packageName)
            .put("v", versionCode)
            .put("pubkey_hash", DeviceKey.pubKeyHashB64Url())
            .put("issued_at", System.currentTimeMillis())
            .toString()

        val b64 = Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "REQ:$b64"
    }

    fun isUnlocked(ctx: Context): Boolean {
        val blob = LicenseStore.loadBlob(ctx) ?: return false
        return validateLicenseBlob(ctx, blob)
    }

    fun tryActivate(ctx: Context, licenseText: String): Boolean {
        val lic = licenseText.trim()
        if (!validateLicenseBlob(ctx, lic)) return false
        LicenseStore.saveBlob(ctx, lic)
        return true
    }

    private fun validateLicenseBlob(ctx: Context, licenseText: String): Boolean {
        if (!licenseText.startsWith("LIC:")) return false

        val body = licenseText.removePrefix("LIC:")
        val parts = body.split(".")
        if (parts.size != 2) return false

        val payloadBytes = b64UrlDecode(parts[0])
        val sigBytes = b64UrlDecode(parts[1])

        if (!verifyCompanySignature(payloadBytes, sigBytes)) return false

        val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
        val pkg = json.getString("pkg")
        val pubHash = json.getString("pubkey_hash")
        val expiresAt = json.optLong("expires_at", 0L)

        if (pkg != ctx.packageName) return false
        if (pubHash != DeviceKey.pubKeyHashB64Url()) return false
        if (expiresAt != 0L && System.currentTimeMillis() > expiresAt) return false

        return true
    }

    private fun verifyCompanySignature(payload: ByteArray, signature: ByteArray): Boolean {
        val pubKeyDer = Base64.decode(COMPANY_PUBKEY_B64, Base64.DEFAULT)
        val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubKeyDer))

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pubKey)
        sig.update(payload)
        return sig.verify(signature)
    }

    private fun b64UrlDecode(s: String): ByteArray {
        return Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)
    }
}