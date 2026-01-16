import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object LicenseStore {
    private const val PREF = "license_secure"
    private const val KEY_BLOB = "license_blob"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            ctx,
            PREF,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun loadBlob(ctx: Context): String? = prefs(ctx).getString(KEY_BLOB, null)

    fun saveBlob(ctx: Context, blob: String) {
        prefs(ctx).edit().putString(KEY_BLOB, blob).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_BLOB).apply()
    }
}