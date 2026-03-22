package at.asitplus.gradle.sbombastic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

open class SbombasticExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val manualDependencies: NamedDomainObjectContainer<ManualSourceDependencySpec> =
        objects.domainObjectContainer(ManualSourceDependencySpec::class.java) { name ->
            objects.newInstance(ManualSourceDependencySpec::class.java, name)
        }

    fun manualDependency(
        name: String,
        action: Action<in ManualSourceDependencySpec>,
    ) {
        action.execute(manualDependencies.maybeCreate(name))
    }
}

abstract class ManualSourceDependencySpec @Inject constructor(
    private val dependencyName: String,
) : Named {
    abstract val version: Property<String>
    abstract val vcsUrls: ListProperty<String>

    abstract val supplierName: Property<String>
    abstract val supplierUrls: ListProperty<String>
    abstract val supplierContactName: Property<String>
    abstract val supplierEmail: Property<String>

    override fun getName(): String = dependencyName

    fun vcsUrl(url: String) {
        vcsUrls.add(url)
    }

    fun supplierUrl(url: String) {
        supplierUrls.add(url)
    }

    internal fun toModel(): ManualSbomDependency =
        ManualSbomDependency(
            name = name,
            version = version.orNull?.trim().orEmpty().ifBlank { "unspecified" },
            vcsUrls = vcsUrls.getOrElse(emptyList()).map(String::trim).filter(String::isNotBlank).distinct(),
            supplier = supplierName.orNull?.trim()?.takeIf(String::isNotBlank)?.let {
                ManualSbomSupplier(
                    name = it,
                    urls = supplierUrls.getOrElse(emptyList()).map(String::trim).filter(String::isNotBlank).distinct(),
                    contactName = supplierContactName.orNull?.trim()?.ifBlank { null },
                    email = supplierEmail.orNull?.trim()?.ifBlank { null },
                )
            },
        )
}

internal data class ManualSbomDependency(
    val name: String,
    val version: String,
    val vcsUrls: List<String>,
    val supplier: ManualSbomSupplier?,
)

internal data class ManualSbomSupplier(
    val name: String,
    val urls: List<String>,
    val contactName: String?,
    val email: String?,
)

internal fun ManualSbomDependency.toJson(): String =
    JsonOutput.toJson(
        linkedMapOf(
            "name" to name,
            "version" to version,
            "vcsUrls" to vcsUrls,
            "supplier" to supplier?.let {
                linkedMapOf(
                    "name" to it.name,
                    "urls" to it.urls,
                    "contactName" to it.contactName,
                    "email" to it.email,
                )
            },
        ),
    )

internal fun manualSbomDependencyFromJson(json: String): ManualSbomDependency {
    val parsed = JsonSlurper().parseText(json) as Map<*, *>
    val supplierMap = parsed["supplier"] as? Map<*, *>

    return ManualSbomDependency(
        name = parsed["name"] as String,
        version = parsed["version"] as String,
        vcsUrls = ((parsed["vcsUrls"] as? List<*>) ?: emptyList<Any>())
            .mapNotNull { it as? String },
        supplier = supplierMap?.let {
            ManualSbomSupplier(
                name = it["name"] as String,
                urls = ((it["urls"] as? List<*>) ?: emptyList<Any>())
                    .mapNotNull { url -> url as? String },
                contactName = it["contactName"] as? String,
                email = it["email"] as? String,
            )
        },
    )
}