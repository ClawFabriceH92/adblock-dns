package io.github.clawfabriceh92.adblockdns.ui

import io.github.clawfabriceh92.adblockdns.core.filter.ListCategory
import io.github.clawfabriceh92.adblockdns.vpn.AdBlockVpnService
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FRENCH = Locale.FRANCE

/** « 1 415 322 » : séparateur de milliers français. */
fun formatCount(value: Int): String = NumberFormat.getIntegerInstance(FRENCH).format(value.toLong())

fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm", FRENCH).format(Date(timestamp))

fun formatDate(timestamp: Long): String = SimpleDateFormat("dd/MM/yyyy", FRENCH).format(Date(timestamp))

fun formatDateTime(timestamp: Long): String = SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", FRENCH).format(Date(timestamp))

fun formatDayLabel(timestamp: Long): String = SimpleDateFormat("EEE d MMM", FRENCH).format(Date(timestamp))

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes o"
    bytes < 1024 * 1024 -> String.format(FRENCH, "%.1f Ko", bytes / 1024.0)
    else -> String.format(FRENCH, "%.1f Mo", bytes / (1024.0 * 1024.0))
}

fun plural(count: Int, singular: String, pluralForm: String = singular + "s"): String =
    "${formatCount(count)} ${if (count > 1) pluralForm else singular}"

/** Catégorie enregistrée dans le journal → libellé court. */
fun categoryLabel(category: String): String = when (category) {
    ListCategory.ADS_TRACKING.name -> "pub/traqueur"
    ListCategory.MALWARE.name -> "malveillant"
    ListCategory.CUSTOM.name -> "liste perso"
    AdBlockVpnService.CATEGORY_RULE -> "règle perso"
    else -> category.lowercase()
}

/** Catégorie → libellé long (statistiques). */
fun categoryTitle(category: String): String = when (category) {
    ListCategory.ADS_TRACKING.name -> "Publicité et traçage"
    ListCategory.MALWARE.name -> "Malveillants"
    ListCategory.CUSTOM.name -> "Liste personnelle"
    AdBlockVpnService.CATEGORY_RULE -> "Règles manuelles"
    else -> category
}

/** Type de requête DNS lisible (A, AAAA, HTTPS…). */
fun queryTypeLabel(type: Int): String = when (type) {
    1 -> "A"
    5 -> "CNAME"
    12 -> "PTR"
    15 -> "MX"
    16 -> "TXT"
    28 -> "AAAA"
    33 -> "SRV"
    64 -> "SVCB"
    65 -> "HTTPS"
    else -> "type $type"
}
