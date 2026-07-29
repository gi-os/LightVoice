package com.gios.lightvoice.act

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.gios.lightvoice.Prefs
import org.json.JSONObject

/**
 * One person the assistant can reach. [phone] is what the dialer gets; [imessage]
 * is the handle BlueBubbles sends to (a phone number or an Apple ID email).
 */
data class Contact(val name: String, val phone: String?, val imessage: String?)

/**
 * The merged address book, from both places the LPIII keeps names.
 *
 * `ContactsContract` holds whatever is on the SIM/device, which on a Light Phone is
 * often thin. The BlueBubbles server exposes the Mac's address book — the same
 * source iMessage itself uses — and that is usually the complete one, so it is
 * cached here (see [refreshFromServer]) and merged in. Device entries win on the
 * phone number, server entries fill in iMessage handles.
 */
object ContactBook {

    fun all(c: Context): List<Contact> {
        val byName = LinkedHashMap<String, Contact>()
        for (entry in device(c) + server(c)) {
            val key = entry.name.lowercase()
            val existing = byName[key]
            byName[key] = if (existing == null) entry else Contact(
                name = existing.name,
                phone = existing.phone ?: entry.phone,
                imessage = existing.imessage ?: entry.imessage,
            )
        }
        return byName.values.toList()
    }

    /**
     * Best match for a spoken name. Exact, then first-name, then prefix, then a
     * loose containment pass — Whisper hears "Alex Kim" as "Alexis" often enough
     * that anything stricter fails on real speech. Ambiguity resolves to the first
     * match rather than asking, because a question costs another whole round trip.
     */
    fun resolve(c: Context, spoken: String): Contact? {
        val q = spoken.trim().lowercase().removeSuffix(".")
        if (q.isEmpty()) return null
        val people = all(c)
        return people.firstOrNull { it.name.lowercase() == q }
            ?: people.firstOrNull { it.name.substringBefore(" ").lowercase() == q }
            ?: people.firstOrNull { it.name.lowercase().startsWith(q) }
            ?: people.firstOrNull { q.length >= 3 && it.name.lowercase().contains(q) }
            ?: people.firstOrNull { q.length >= 3 && q.contains(it.name.substringBefore(" ").lowercase()) }
    }

    /** Names only, for the intent model's system prompt. Capped so a 2,700-contact
     *  address book cannot dominate the token budget. */
    fun names(c: Context, limit: Int = 250): List<String> = all(c).map { it.name }.take(limit)

    private fun device(c: Context): List<Contact> {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val out = ArrayList<Contact>()
        runCatching {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val number = cursor.getString(1)?.trim().orEmpty()
                    if (name.isBlank() || number.isBlank()) continue
                    out.add(Contact(name, number, number))
                }
            }
        }
        return out
    }

    private fun server(c: Context): List<Contact> = runCatching {
        val obj = JSONObject(Prefs.bbContactsJson(c))
        obj.keys().asSequence().mapNotNull { name ->
            val addresses = obj.optJSONArray(name) ?: return@mapNotNull null
            var phone: String? = null
            var handle: String? = null
            for (i in 0 until addresses.length()) {
                val a = addresses.optString(i).orEmpty()
                if (a.isBlank()) continue
                if (handle == null) handle = a
                if (phone == null && a.none { it == '@' }) phone = a
            }
            Contact(name, phone, handle)
        }.toList()
    }.getOrDefault(emptyList())

    /** Pulls the Mac's address book from BlueBubbles and caches it as name -> handles. */
    fun refreshFromServer(c: Context): Int {
        val url = Prefs.bbUrl(c)
        val password = Prefs.bbPassword(c)
        if (url.isBlank() || password.isBlank()) return 0
        val pairs = Bb.contacts(url, password)
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for ((address, name) in pairs) {
            grouped.getOrPut(name) { ArrayList() }.let { if (address !in it) it.add(address) }
        }
        val obj = JSONObject()
        for ((name, addresses) in grouped) {
            obj.put(name, org.json.JSONArray().also { arr -> addresses.forEach { arr.put(it) } })
        }
        Prefs.setBbContactsJson(c, obj.toString())
        return grouped.size
    }
}
