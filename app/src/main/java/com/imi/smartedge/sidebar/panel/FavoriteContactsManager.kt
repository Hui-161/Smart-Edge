package com.imi.smartedge.sidebar.panel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the favorite contacts shown in the edge panel (name + phone number)
 * and starts calls / SMS for them. Data never leaves the device.
 */
object FavoriteContactsManager {

    private const val TAG = "FavoriteContacts"
    private const val PREFS_NAME = "favorite_contacts_prefs"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_DIRECT_CALL = "direct_call"
    const val MAX_CONTACTS = 20

    data class Contact(val name: String, val number: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getContacts(context: Context): List<Contact> {
        val raw = prefs(context).getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Contact(obj.getString("name"), obj.getString("number"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse favorite contacts", e)
            emptyList()
        }
    }

    private fun saveContacts(context: Context, contacts: List<Contact>) {
        val array = JSONArray()
        contacts.take(MAX_CONTACTS).forEach { contact ->
            array.put(JSONObject().apply {
                put("name", contact.name)
                put("number", contact.number)
            })
        }
        prefs(context).edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    /** Adds a contact; returns false if it already exists or the list is full. */
    fun addContact(context: Context, contact: Contact): Boolean {
        val current = getContacts(context)
        if (current.size >= MAX_CONTACTS) return false
        if (current.any { normalize(it.number) == normalize(contact.number) }) return false
        saveContacts(context, current + contact)
        return true
    }

    fun removeContact(context: Context, contact: Contact) {
        saveContacts(context, getContacts(context).filterNot { it == contact })
    }

    /**
     * Reads the phone row returned by the system contact picker
     * (ACTION_PICK on Phone.CONTENT_URI). The picker grants temporary read access
     * to this single row, so READ_CONTACTS is not required.
     */
    fun contactFromPickerResult(context: Context, uri: Uri): Contact? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val number = cursor.getString(1)?.trim().orEmpty()
                if (number.isEmpty()) return null
                val name = cursor.getString(0)?.trim().takeUnless { it.isNullOrEmpty() } ?: number
                Contact(name, number)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read picked contact", e)
            null
        }
    }

    /**
     * Imports the contacts marked as favorite ("starred") in the phone's contacts app.
     * Requires READ_CONTACTS. Returns the number of newly added contacts.
     */
    fun importStarredContacts(context: Context): Int {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1"
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC, " +
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"

        var added = 0
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { cursor ->
                val seenContactIds = mutableSetOf<Long>()
                while (cursor.moveToNext()) {
                    // One number per contact (primary number comes first)
                    if (!seenContactIds.add(cursor.getLong(0))) continue
                    val number = cursor.getString(2)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    val name = cursor.getString(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: number
                    if (addContact(context, Contact(name, number))) added++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import starred contacts", e)
        }
        return added
    }

    /** If true (and CALL_PHONE is granted), calls start immediately instead of opening the dialer. */
    fun isDirectCallEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIRECT_CALL, false)

    fun setDirectCallEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIRECT_CALL, enabled).apply()
    }

    fun call(context: Context, contact: Contact): Boolean {
        val canCallDirectly = isDirectCallEnabled(context) &&
                context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return startSafely(context, Intent(action, Uri.fromParts("tel", contact.number, null)))
    }

    fun sendSms(context: Context, contact: Contact): Boolean {
        return startSafely(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null)))
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No app available for ${intent.action}", e)
            false
        }
    }

    private fun normalize(number: String) = number.filter { it.isDigit() || it == '+' }
}
