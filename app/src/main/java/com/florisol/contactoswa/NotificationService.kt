package com.florisol.contactoswa

import android.app.Notification
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationService"

        // Paquetes objetivo: WhatsApp Estándar y WhatsApp Business
        private const val PACKAGE_WHATSAPP = "com.whatsapp"
        private const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        /**
         * Expresión regular para identificar si el título corresponde a un número de teléfono.
         * WhatsApp muestra los contactos no guardados en formatos internacionales como:
         * "+52 1 55 1234 5678", "+34 612 34 56 78", "+1 (555) 000-0000", "+54 9 11 1234-5678", etc.
         */
        private val PHONE_NUMBER_REGEX = Pattern.compile("^\\+?[0-9\\s\\-\\(\\)]{7,25}$")
    }

    // Executor para ejecutar consultas y escrituras en SQLite/ContactsContract en segundo plano
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListenerService conectado exitosamente.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListenerService desconectado.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) return

        val packageName = sbn.packageName

        // 1. Filtrar únicamente notificaciones provenientes de WhatsApp o WhatsApp Business
        if (packageName != PACKAGE_WHATSAPP && packageName != PACKAGE_WHATSAPP_BUSINESS) {
            return
        }

        val extras: Bundle = sbn.notification.extras ?: return

        // 2. Extraer el título de la notificación (Remitente)
        val titleCharSequence = extras.getCharSequence(Notification.EXTRA_TITLE)
        val title = titleCharSequence?.toString()?.trim() ?: return

        if (title.isEmpty()) return

        Log.d(TAG, "Notificación recibida de [$packageName] con Título/Remitente: '$title'")

        // 3. Validar si el título es un número de teléfono (contacto no guardado)
        if (isPhoneNumber(title)) {
            val rawPhoneNumber = title
            Log.i(TAG, "Número detectado como contacto no guardado: $rawPhoneNumber")

            // Procesar en segundo plano para no bloquear el hilo de notificaciones
            backgroundExecutor.execute {
                processAndSaveContact(rawPhoneNumber)
            }
        } else {
            Log.d(TAG, "El título '$title' no es un número de teléfono (ya tiene nombre asignado o es un grupo).")
        }
    }

    /**
     * Valida mediante Regex si el texto recibido tiene la estructura de un número telefónico
     * y contiene al menos 7 dígitos numéricos reales.
     */
    private fun isPhoneNumber(text: String): Boolean {
        if (!PHONE_NUMBER_REGEX.matcher(text).matches()) {
            return false
        }
        // Verificar que contenga al menos 7 dígitos reales (evita falsos positivos como símbolos aislados)
        val digitCount = text.count { it.isDigit() }
        return digitCount in 7..20
    }

    /**
     * Comprueba permisos, verifica existencia previa y guarda el contacto si no existe.
     */
    private fun processAndSaveContact(phoneNumber: String) {
        // Verificar permisos en tiempo de ejecución
        if (!hasContactsPermissions(this)) {
            Log.e(TAG, "No se tienen permisos de READ_CONTACTS / WRITE_CONTACTS. No se puede guardar.")
            return
        }

        // 4.1 Verificar si el número ya existe en la agenda telefónica
        if (isContactAlreadySaved(this, phoneNumber)) {
            Log.i(TAG, "El número $phoneNumber ya existe en los contactos. Omitiendo guardado.")
            return
        }

        // 4.2 Si no existe, guardar automáticamente
        val currentDateTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val contactDisplayName = "Cliente Florisol - $currentDateTime"

        val success = saveContact(this, contactDisplayName, phoneNumber)
        if (success) {
            Log.i(TAG, "Contacto guardado exitosamente: '$contactDisplayName' -> $phoneNumber")
        } else {
            Log.e(TAG, "Error al intentar guardar el contacto: $phoneNumber")
        }
    }

    /**
     * Comprueba si el número ya existe en los contactos utilizando PhoneLookup de ContactsContract.
     * PhoneLookup maneja internamente la normalización de códigos de país y formatos de espaciado.
     */
    private fun isContactAlreadySaved(context: Context, phoneNumber: String): Boolean {
        var cursor: Cursor? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME
            )

            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val existingName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                Log.d(TAG, "Número encontrado en la agenda como: '$existingName'")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando PhoneLookup: ${e.message}", e)
        } finally {
            cursor?.close()
        }
        return false
    }

    /**
     * Inserta silenciosamente un nuevo contacto en la agenda del dispositivo
     * utilizando operaciones por lotes (Batch) con ContentProviderOperation.
     */
    private fun saveContact(context: Context, displayName: String, phoneNumber: String): Boolean {
        val ops = ArrayList<ContentProviderOperation>()

        // 1. Crear RawContact vacío
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )

        // 2. Asignar Nombre del Contacto
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    displayName
                )
                .build()
        )

        // 3. Asignar Número de Teléfono
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    phoneNumber
                )
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
                .build()
        )

        return try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            results.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al guardar contacto en ContentResolver: ${e.message}", e)
            false
        }
    }

    private fun hasContactsPermissions(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }
}
