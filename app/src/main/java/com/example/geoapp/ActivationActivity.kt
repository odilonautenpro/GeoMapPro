package com.example.geoapp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ActivationActivity : AppCompatActivity() {

    private lateinit var txtRequest: TextView
    private lateinit var btnCopyReq: Button
    private lateinit var edtLicense: EditText
    private lateinit var btnActivate: Button
    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_activation)

        txtRequest = findViewById(R.id.txtRequest)
        btnCopyReq = findViewById(R.id.btnCopyRequest)
        edtLicense = findViewById(R.id.edtLicense)
        btnActivate = findViewById(R.id.btnActivate)
        txtStatus = findViewById(R.id.txtStatus)

        if (LicenseManager.isUnlocked(this)) {
            goToMain()
            return
        }

        val req = LicenseManager.buildActivationRequest(this, getVersionCodeCompat())
        txtRequest.text = req

        btnCopyReq.setOnClickListener {
            copyToClipboard("Activation Request", req)
            toast("Código de solicitação copiado.")
        }

        btnActivate.setOnClickListener {
            val lic = edtLicense.text?.toString()?.trim().orEmpty()
            if (lic.isBlank()) {
                txtStatus.text = "Cole o código de ativação (LIC:...)"
                return@setOnClickListener
            }

            val ok = try {
                LicenseManager.tryActivate(this, lic)
            } catch (e: Exception) {
                txtStatus.text = "Falha ao validar licença: ${e.message}"
                false
            }

            if (ok) {
                txtStatus.text = "Ativado com sucesso."
                toast("Produto ativado.")
                goToMain()
            } else {
                txtStatus.text = "Licença inválida para este dispositivo (ou expirada)."
                toast("Licença inválida.")
            }
        }
    }

    private fun goToMain() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        finish()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun getVersionCodeCompat(): Int {
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }
}