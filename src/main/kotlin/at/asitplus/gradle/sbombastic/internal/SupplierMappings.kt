package at.asitplus.gradle.sbombastic.internal

import at.asitplus.gradle.sbombastic.*
import groovy.json.JsonSlurper
import java.net.URL
import java.net.URLConnection
import java.util.Locale
import org.cyclonedx.model.Component
import org.gradle.api.Project

internal data class SupplierInfo(
    val name: String,
    val urls: List<String>,
    val contactName: String?,
    val email: String?,
)

internal enum class SupplierMappingType {
    mvn,
    npm,
}

internal data class SupplierMapping(
    val type: SupplierMappingType,
    val groups: List<String>,
    val packages: List<String>,
    val supplier: SupplierInfo,
)

internal fun List<SupplierMapping>.findSupplierForComponent(component: Component): SupplierInfo? {
    val purl = component.purl ?: component.bomRef
    val purlType = purl?.substringAfter("pkg:", "")?.substringBefore('/')
    return when (purlType) {
        "npm" -> findSupplierForNpmPackage(component.name)
        else -> findSupplierForGroup(component.group)
    }
}

internal fun List<SupplierMapping>.findSupplierForGroup(group: String?): SupplierInfo? {
    if (group.isNullOrBlank()) return null

    return asSequence()
        .filter { it.type == SupplierMappingType.mvn }
        .flatMap { mapping -> mapping.groups.asSequence().map { prefix -> prefix to mapping.supplier } }
        .filter { (prefix, _) ->
            group == prefix || (if (prefix.endsWith(".*")) group.startsWith(prefix.dropLast(1)) else false)
        }
        .maxByOrNull { (prefix, _) -> prefix.length }
        ?.second
}

internal fun List<SupplierMapping>.findSupplierForNpmPackage(packageName: String?): SupplierInfo? {
    if (packageName.isNullOrBlank()) return null

    return asSequence()
        .filter { it.type == SupplierMappingType.npm }
        .flatMap { mapping -> mapping.packages.asSequence().map { pkg -> pkg to mapping.supplier } }
        .firstOrNull { (pkg, _) -> pkg == packageName }
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

        val type = (rawEntry["type"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "mvn")
            .let {
                try {
                    SupplierMappingType.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    error("Supplier mapping entry #$index has invalid 'type': $it (expected 'mvn' or 'npm')")
                }
            }

        val groups = (rawEntry["groups"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val packages = (rawEntry["packages"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        when (type) {
            SupplierMappingType.mvn -> require(groups.isNotEmpty()) {
                "Supplier mapping entry #$index with type 'mvn' must contain a non-empty 'groups' list"
            }

            SupplierMappingType.npm -> require(packages.isNotEmpty()) {
                "Supplier mapping entry #$index with type 'npm' must contain a non-empty 'packages' list"
            }
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
            type = type,
            groups = groups,
            packages = packages,
            supplier = SupplierInfo(name = name, urls = urls, contactName = contactName, email = email),
        )
    }
}