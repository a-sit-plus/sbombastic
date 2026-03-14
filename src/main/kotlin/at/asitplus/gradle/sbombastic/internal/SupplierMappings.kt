package at.asitplus.gradle.sbombastic.internal

import at.asitplus.gradle.sbombastic.*
import groovy.json.JsonSlurper
import org.gradle.api.Project
import java.net.URL
import java.net.URLConnection
import java.util.*

internal data class SupplierInfo(
    val name: String,
    val urls: List<String>,
    val contactName: String?,
    val email: String?,
)

internal data class SupplierMapping(
    val groups: List<String>,
    val supplier: SupplierInfo,
)

internal fun List<SupplierMapping>.findSupplierForGroup(group: String?): SupplierInfo? {
    if (group.isNullOrBlank()) return null

    return asSequence()
        .flatMap { mapping -> mapping.groups.asSequence().map { prefix -> prefix to mapping.supplier } }
        .filter { (prefix, _) -> group == prefix || (if (prefix.endsWith(".*")) group.startsWith(prefix.dropLast(1)) else false) }
        .maxByOrNull { (prefix, _) -> prefix.length }
        ?.second
}

internal fun Project.supplierInfoFromEnvExtra(): SupplierInfo? {
    val name = envExtra[SUPPLIER_NAME]?.trim().orEmpty()
    if (name.isBlank()) return null

    val urls = envExtra[SUPPLIER_URL]
        .orEmpty()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val contactName = envExtra[SUPPLIER_CONTACTNAME]?.trim()?.ifBlank { null }
    val email = envExtra[SUPPLIER_EMAIL]?.trim()?.ifBlank { null }

    return SupplierInfo(name = name, urls = urls, contactName = contactName, email = email)
}

internal fun Project.supplierMappingsUrlFromEnvExtra(): String? =
    envExtra[SUPPLIER_MAPPINGS]?.trim()?.ifBlank { null }

private fun openSupplierMappingConnection(urlString: String): URLConnection {
    val url = URL(urlString)
    require(url.protocol.lowercase(Locale.ROOT) != "http") {
        "Plain http is not allowed for supplier mapping URL: $urlString"
    }
    return url.openConnection().apply {
        connectTimeout = 5_000
        readTimeout = 10_000
    }
}

internal fun loadSupplierMappings(urlString: String): List<SupplierMapping> {
    val connection = openSupplierMappingConnection(urlString)
    val parsed = connection.getInputStream().bufferedReader().use { reader ->
        JsonSlurper().parse(reader)
    }

    require(parsed is List<*>) {
        "Supplier mapping JSON must be a top-level array: $urlString"
    }

    return parsed.mapIndexed { index, rawEntry ->
        require(rawEntry is Map<*, *>) {
            "Supplier mapping entry #$index must be an object"
        }

        val prefixes = (rawEntry["groups"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        require(prefixes.isNotEmpty()) {
            "Supplier mapping entry #$index must contain a non-empty 'groups' list"
        }

        val rawSupplier = rawEntry["supplier"] as? Map<*, *>
            ?: error("Supplier mapping entry #$index must contain a 'supplier' object")

        val name = rawSupplier["name"]?.toString()?.trim().orEmpty()
        require(name.isNotBlank()) {
            "Supplier mapping entry #$index has supplier with missing/blank 'name'"
        }

        val urls = when (val rawUrls = rawSupplier["urls"]) {
            is List<*> -> rawUrls.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            null -> emptyList()
            else -> error("Supplier mapping entry #$index has supplier 'urls' that is not a list")
        }

        val contactName = rawSupplier["contactName"]?.toString()?.trim()?.ifBlank { null }
        val email = rawSupplier["email"]?.toString()?.trim()?.ifBlank { null }

        SupplierMapping(
            groups = prefixes,
            supplier = SupplierInfo(name = name, urls = urls, contactName = contactName, email = email),
        )
    }
}
