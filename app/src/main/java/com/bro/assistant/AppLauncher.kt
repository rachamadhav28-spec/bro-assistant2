package com.bro.assistant

import android.content.Context
import android.content.Intent
import java.util.Locale

data class AppMatch(val label: String, val packageName: String, val intent: Intent)

/** Finds an installed app by the name the user said, and prepares the intent that opens it. */
class AppLauncher(private val context: Context) {

    // Popular apps with well-known package names. Checked first, so "Maps" or "Chrome" never
    // opens some other app with a similar name.
    private val knownPackages: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "gmail" to listOf("com.google.android.gm"),
        "chrome" to listOf("com.android.chrome"),
        "googlemaps" to listOf("com.google.android.apps.maps"),
        "playstore" to listOf("com.android.vending"),
        "photos" to listOf("com.google.android.apps.photos"),
        "telegram" to listOf("org.telegram.messenger"),
        "facebook" to listOf("com.facebook.katana"),
        "snapchat" to listOf("com.snapchat.android"),
        "spotify" to listOf("com.spotify.music"),
        "x" to listOf("com.twitter.android")
    )

    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun isInstalled(packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    /** Returns the installed app that best matches the spoken name, or null if there is none. */
    fun find(name: String): AppMatch? {
        val q = norm(name)
        if (q.isEmpty()) return null

        val known = knownPackages[q]
        if (known != null) {
            for (pkg in known) {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) return AppMatch(name.trim(), pkg, launch)
            }
        }
        return scan(q)
    }

    private fun score(q: String, label: String): Int = when {
        label == q -> 100
        q.length >= 3 && label.startsWith(q) -> 80
        q.length >= 3 && label.contains(q) -> 60
        label.length >= 4 && q.contains(label) -> 50
        else -> 0
    }

    /** Looks through every app that has a launcher icon and picks the best name match. */
    private fun scan(q: String): AppMatch? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)

        var best: AppMatch? = null
        var bestScore = 0
        var bestLength = Int.MAX_VALUE
        for (info in apps) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            val label = info.loadLabel(pm).toString()
            val normalized = norm(label)
            val s = score(q, normalized)
            if (s == 0) continue
            if (s > bestScore || (s == bestScore && normalized.length < bestLength)) {
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, info.activityInfo.name)
                best = AppMatch(label, pkg, launch)
                bestScore = s
                bestLength = normalized.length
            }
        }
        return best
    }
}
