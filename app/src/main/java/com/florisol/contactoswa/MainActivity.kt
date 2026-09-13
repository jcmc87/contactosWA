package com.florisol.contactoswa

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvContactPermissionStatus: TextView
    private lateinit var tvNotificationAccessStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnGrantContacts: Button
    private lateinit var btnNotificationAccess: Button

    // Manejador moderno para solicitud múltiple de permisos en tiempo de ejecución
    private val requestContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_CONTACTS] ?: false
        val writeGranted = permissions[Manifest.permission.WRITE_CONTACTS] ?: false

        if (readGranted && writeGranted) {
            Toast.makeText(this, "Permisos de contactos concedidos", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos de contactos para guardar los números automáticamente",
                Toast.LENGTH_LONG
            ).show()
        }
        updateUIState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Actualizamos los estados cada vez que la app vuelve al primer plano
        updateUIState()
    }

    private fun initViews() {
        tvContactPermissionStatus = findViewById(R.id.tvContactPermissionStatus)
        tvNotificationAccessStatus = findViewById(R.id.tvNotificationAccessStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnGrantContacts = findViewById(R.id.btnGrantContacts)
        btnNotificationAccess = findViewById(R.id.btnNotificationAccess)
    }

    private fun setupListeners() {
        btnGrantContacts.setOnClickListener {
            if (hasContactsPermissions()) {
                Toast.makeText(this, "Los permisos de contactos ya están otorgados", Toast.LENGTH_SHORT).show()
            } else {
                requestContactsPermissions()
            }
        }

        btnNotificationAccess.setOnClickListener {
            openNotificationListenerSettings()
        }
    }

    private fun updateUIState() {
        val contactsGranted = hasContactsPermissions()
        val notificationGranted = isNotificationServiceEnabled()

        // Estado de permisos de contactos
        if (contactsGranted) {
            tvContactPermissionStatus.text = "Permisos de Contactos: CONCEDIDOS \u2705"
            tvContactPermissionStatus.setTextColor(Color.parseColor("#2E7D32"))
            btnGrantContacts.isEnabled = false
            btnGrantContacts.text = "Contactos Habilitados"
        } else {
            tvContactPermissionStatus.text = "Permisos de Contactos: DENEGADOS \u274C"
            tvContactPermissionStatus.setTextColor(Color.parseColor("#C62828"))
            btnGrantContacts.isEnabled = true
            btnGrantContacts.text = "Solicitar Permiso de Contactos"
        }

        // Estado de acceso a notificaciones
        if (notificationGranted) {
            tvNotificationAccessStatus.text = "Acceso a Notificaciones: CONCEDIDO \u2705"
            tvNotificationAccessStatus.setTextColor(Color.parseColor("#2E7D32"))
            btnNotificationAccess.text = "Ajustes de Notificaciones (Activo)"
        } else {
            tvNotificationAccessStatus.text = "Acceso a Notificaciones: DESACTIVADO \u274C"
            tvNotificationAccessStatus.setTextColor(Color.parseColor("#C62828"))
            btnNotificationAccess.text = "Activar en Ajustes del Sistema"
        }

        // Estado general
        if (contactsGranted && notificationGranted) {
            tvServiceStatus.text = "\uD83D\uDE80 ¡El servicio está listo y escuchando notificaciones de WhatsApp / WA Business!"
            tvServiceStatus.setTextColor(Color.parseColor("#1B5E20"))
            tvServiceStatus.setBackgroundColor(Color.parseColor("#E8F5E9"))
        } else {
            tvServiceStatus.text = "\u26A0\uFE0F Se requieren todos los permisos para que la aplicación funcione en segundo plano."
            tvServiceStatus.setTextColor(Color.parseColor("#B71C1C"))
            tvServiceStatus.setBackgroundColor(Color.parseColor("#FFEBEE"))
        }
    }

    private fun hasContactsPermissions(): Boolean {
        val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    private fun requestContactsPermissions() {
        requestContactsLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            )
        )
    }

    /**
     * Verifica si nuestro NotificationService tiene concedido el acceso a notificaciones del sistema.
     */
    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Redirige al usuario a la pantalla de configuración de "Acceso a Notificaciones".
     */
    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la configuración de notificaciones", Toast.LENGTH_SHORT).show()
        }
    }
}
