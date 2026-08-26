package com.posdemo.printer.data

import android.content.Context
import java.security.SecureRandom

class HubAuth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun token(): String = synchronized(lock) {
        val existing = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (existing.length >= 8) return existing
        val next = newToken()
        prefs.edit().putString(KEY_TOKEN, next).apply()
        next
    }

    fun matches(provided: String?): Boolean {
        val got = provided?.trim().orEmpty()
        if (got.isEmpty()) return false
        // Case-insensitive: browsers / manual entry may differ in case.
        return got.equals(token(), ignoreCase = true)
    }

    fun posPath(): String = "/pos?token=${token()}"

    fun nextTicketNo(): Int = synchronized(lock) {
        val n = prefs.getInt(KEY_TICKET, 0) + 1
        val wrapped = if (n > 999) 1 else n
        prefs.edit().putInt(KEY_TICKET, wrapped).apply()
        wrapped
    }

    companion object {
        private const val PREFS = "pos_printer_demo"
        private const val KEY_TOKEN = "hub_token_v1"
        private const val KEY_TICKET = "ticket_seq_v1"
        private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

        private fun newToken(): String {
            val rng = SecureRandom()
            return CharArray(8) { alphabet[rng.nextInt(alphabet.size)] }.concatToString()
        }
    }
}
