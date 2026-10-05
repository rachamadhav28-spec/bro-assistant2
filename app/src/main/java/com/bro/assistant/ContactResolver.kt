package com.bro.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

sealed class ContactResult {
    data class Found(val name: String, val number: String) : ContactResult()
    data class Several(val names: List<String>) : ContactResult()
    object NotFound : ContactResult()
    object NoPermission : ContactResult()
}

/** Turns "Rahul" (or a spoken phone number) into a phone number. Names need the Contacts permission. */
class ContactResolver(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun looksLikeNumber(query: String): Boolean {
        val digits = query.count { it.isDigit() }
        return digits >= 3 && query.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
    }

    fun resolve(query: String): ContactResult {
        val q = query.trim()
        if (q.isEmpty()) return ContactResult.NotFound

        if (looksLikeNumber(q)) {
            val cleaned = q.filter { it.isDigit() || it == '+' }
            return ContactResult.Found(q, cleaned)
        }
        if (!hasPermission()) return ContactResult.NoPermission

        val rows = mutableListOf<Pair<String, String>>()
        try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
            val projection = arrayOf(NAME_COLUMN, ContactsContract.CommonDataKinds.Phone.NUMBER)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use { c ->
                val nameCol = c.getColumnIndex(NAME_COLUMN)
                val numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && rows.size < MAX_ROWS) {
                    val name = c.getString(nameCol).orEmpty()
                    val number = c.getString(numCol).orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) rows.add(Pair(name, number))
                }
            }
        } catch (e: SecurityException) {
            return ContactResult.NoPermission
        }

        if (rows.isEmpty()) return ContactResult.NotFound

        // One entry per contact name, keeping that contact's first number.
        val byName = linkedMapOf<String, String>()
        for ((name, number) in rows) {
            val key = name.lowercase(Locale.ROOT)
            if (!byName.containsKey(key)) byName[key] = number
        }
        val names = rows.map { it.first }.distinctBy { it.lowercase(Locale.ROOT) }

        if (names.size == 1) {
            return ContactResult.Found(names[0], byName.getValue(names[0].lowercase(Locale.ROOT)))
        }
        val exact = names.filter { it.equals(q, ignoreCase = true) }
        if (exact.size == 1) {
            return ContactResult.Found(exact[0], byName.getValue(exact[0].lowercase(Locale.ROOT)))
        }
        return ContactResult.Several(names.take(4))
    }

    companion object {
        private const val MAX_ROWS = 50
        private const val NAME_COLUMN = "display_name"
    }
}
