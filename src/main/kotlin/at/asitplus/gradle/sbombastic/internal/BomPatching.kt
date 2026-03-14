package at.asitplus.gradle.sbombastic.internal

import at.asitplus.gradle.sbombastic.licenseId
import at.asitplus.gradle.sbombastic.licenseName
import at.asitplus.gradle.sbombastic.licenseUrl
import org.cyclonedx.model.*
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.net.URLEncoder

internal fun SupplierInfo.toOrganizationalEntity(): OrganizationalEntity =
    OrganizationalEntity().apply {
        name = this@toOrganizationalEntity.name
        urls = this@toOrganizationalEntity.urls
        if (this@toOrganizationalEntity.contactName != null || this@toOrganizationalEntity.email != null) {
            addContact(
                OrganizationalContact().apply {
                    name = this@toOrganizationalEntity.contactName
                    email = this@toOrganizationalEntity.email
                },
            )
        }
    }

internal fun Bom.normalizeBom(
    project: Project,
    normalizationPlan: SbomNormalizationPlan,
    publicationCoordinates: PublishedCoordinates,
    refRewrites: MutableMap<String, String>,
    supplierInfo: SupplierInfo?,
    thirdPartySupplierMappings: List<SupplierMapping>,
    jsNpmGraph: JsResolvedNpmGraph?,
): Bom {
    metadata?.component?.rewriteComponent(normalizationPlan, refRewrites)
    components?.forEach { component -> component.rewriteComponent(normalizationPlan, refRewrites) }
    dependencies = dependencies?.map { it.rewrittenDependency(refRewrites) }?.toMutableList()

    alignRootDependenciesToPublicationPom(publicationCoordinates)

    if (jsNpmGraph != null) {
        patchResolvedNpmGraph(jsNpmGraph)
    }

    patchSupplierMetadata(supplierInfo, thirdPartySupplierMappings)

    project.licenseId?.let {
        patchLicenseMetadata(it, project.licenseName, project.licenseUrl)
        patchFirstPartyComponentLicenses(project.licenseId, project.licenseName, project.licenseUrl)
    }
    return this
}

private fun Bom.patchLicenseMetadata(
    licenseId: String,
    licenseName: String?,
    licenseUrl: String?,
) {
    val license = License().apply {
        id = licenseId
        name = licenseName
        url = licenseUrl
    }
    val choice = LicenseChoice().apply {
        addLicense(license)
    }

    if (metadata == null) {
        metadata = Metadata()
    }

    metadata!!.component?.licenses = choice
}

private fun Bom.patchFirstPartyComponentLicenses(
    licenseId: String?,
    licenseName: String?,
    licenseUrl: String?,
) {
    val rootGroup = metadata?.component?.group ?: return
    components?.forEach { component ->
        if (component.group == rootGroup) {
            component.licenses = LicenseChoice().apply {
                addLicense(
                    License().apply {
                        id = licenseId
                        name = licenseName
                        url = licenseUrl
                    },
                )
            }
        }
    }
}

private fun Bom.patchSupplierMetadata(
    supplierInfo: SupplierInfo?,
    thirdPartySupplierMappings: List<SupplierMapping>,
) {
    if (supplierInfo == null && thirdPartySupplierMappings.isEmpty()) return

    if (metadata == null) {
        metadata = Metadata()
    }

    val ownGroup = metadata!!.component?.group
    val firstPartySupplier = supplierInfo?.toOrganizationalEntity()

    if (firstPartySupplier != null) {
        metadata!!.supplier = firstPartySupplier
        metadata!!.component?.supplier = firstPartySupplier
    }

    components?.forEach { component ->
        val group = component.group ?: return@forEach

        when {
            ownGroup != null && group == ownGroup -> {
                if (firstPartySupplier != null) {
                    component.supplier = firstPartySupplier
                }
            }

            else -> {
                val thirdPartySupplier =
                    thirdPartySupplierMappings.findSupplierForGroup(group)?.toOrganizationalEntity()
                if (thirdPartySupplier != null) {
                    component.supplier = thirdPartySupplier
                }
            }
        }
    }
}

private fun Bom.alignRootDependenciesToPublicationPom(publicationCoordinates: PublishedCoordinates) {
    val rootRef = metadata?.component?.bomRef ?: return
    val rootDependency = dependencies?.firstOrNull { it.ref == rootRef } ?: return
    val byCoordinates = linkedMapOf<SbomComponentCoordinates, String>()
    metadata?.component?.let { component ->
        val group = component.group
        val name = component.name
        val version = component.version
        val bomRef = component.bomRef
        if (group != null && name != null && version != null && bomRef != null) {
            byCoordinates[SbomComponentCoordinates(group, name, version)] = bomRef
        }
    }
    components?.forEach { component ->
        val group = component.group
        val name = component.name
        val version = component.version
        val bomRef = component.bomRef
        if (group != null && name != null && version != null && bomRef != null) {
            byCoordinates[SbomComponentCoordinates(group, name, version)] = bomRef
        }
    }
    rootDependency.dependencies = publicationCoordinates.directDependencies.mapNotNull { coordinates ->
        byCoordinates[coordinates]?.let(::Dependency)
    }
}

private fun Component.rewriteComponent(
    normalizationPlan: SbomNormalizationPlan,
    refRewrites: MutableMap<String, String>,
) {
    val group = group ?: return
    val name = name ?: return
    val version = version ?: return
    val originalCoordinates = SbomComponentCoordinates(group, name, version)
    val targetCoordinates = normalizationPlan.coordinateAliases[originalCoordinates] ?: originalCoordinates
    val artifactType = normalizationPlan.exactArtifactTypes[targetCoordinates]
        ?: normalizationPlan.exactArtifactTypes[originalCoordinates]
        ?: return
    val oldBomRef = bomRef
    val oldPurl = purl
    this.group = targetCoordinates.group
    this.name = targetCoordinates.name
    this.version = targetCoordinates.version
    val newBomRef = oldBomRef?.let { rewritePurl(it, targetCoordinates, artifactType) }
    val newPurl = oldPurl?.let { rewritePurl(it, targetCoordinates, artifactType) }
    if (oldBomRef != null && newBomRef != null && oldBomRef != newBomRef) {
        bomRef = newBomRef
        refRewrites[oldBomRef] = newBomRef
    }
    if (oldPurl != null && newPurl != null && oldPurl != newPurl) {
        purl = newPurl
    }
}

private fun Dependency.rewrittenDependency(refRewrites: Map<String, String>): Dependency =
    Dependency(refRewrites[ref] ?: ref).also { rewritten ->
        rewritten.dependencies = dependencies?.map { it.rewrittenDependency(refRewrites) }
    }

private fun rewritePurl(
    purl: String,
    coordinates: SbomComponentCoordinates,
    artifactType: String,
): String {
    val rewrittenCoordinates = when {
        !purl.startsWith("pkg:maven/") -> purl
        else -> {
            val queryIndex = purl.indexOf('?').let { if (it < 0) purl.length else it }
            buildString {
                append("pkg:maven/")
                append(coordinates.group)
                append('/')
                append(coordinates.name)
                append('@')
                append(coordinates.version)
                append(purl.substring(queryIndex))
            }
        }
    }
    return withPurlType(rewrittenCoordinates, artifactType)
}

private fun withPurlType(purl: String, artifactType: String): String {
    val queryIndex = purl.indexOf('?')
    if (queryIndex < 0) {
        return "$purl?type=$artifactType"
    }
    val base = purl.substring(0, queryIndex)
    val qualifiers = purl.substring(queryIndex + 1)
        .split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { qualifier ->
            val separatorIndex = qualifier.indexOf('=')
            if (separatorIndex < 0) null else qualifier.substring(0, separatorIndex) to qualifier.substring(
                separatorIndex + 1
            )
        }
        .toMutableList()
    val existingTypeIndex = qualifiers.indexOfFirst { it.first == "type" }
    if (existingTypeIndex >= 0) qualifiers[existingTypeIndex] = "type" to artifactType
    else qualifiers += "type" to artifactType
    return buildString {
        append(base)
        append('?')
        append(qualifiers.joinToString("&") { "${it.first}=${it.second}" })
    }
}

internal fun typeFromPurl(purl: String): String? =
    purl.substringAfter('?', "").split('&').firstOrNull { it.startsWith("type=") }?.substringAfter('=')

private fun Bom.patchResolvedNpmGraph(graph: JsResolvedNpmGraph) {
    println("patchResolvedNpmGraph roots = " + graph.packages.joinToString { "${it.name}@${it.version}" })

    val componentByRef = linkedMapOf<String, Component>()
    val dependencyByRef = linkedMapOf<String, Dependency>()

    metadata?.component?.bomRef?.let { rootRef ->
        dependencyByRef[rootRef] = dependencies
            ?.firstOrNull { it.ref == rootRef }
            ?.deepCopy()
            ?: Dependency(rootRef)
    }

    metadata?.component?.let { root ->
        root.bomRef?.let { componentByRef[it] = root }
    }

    components
        ?.filter { !it.bomRef.isNullOrBlank() }
        ?.forEach { componentByRef[it.bomRef!!] = it }

    dependencies
        ?.forEach { dependencyByRef[it.ref] = it.deepCopy() }

    val visited = linkedSetOf<String>()
    fun visit(node: NpmNode) {
        val ref = node.toPurl()
        if (visited.add(ref)) {
            componentByRef.putIfAbsent(ref, node.toComponent())
        }

        val currentDependency = dependencyByRef.getOrPut(ref) { Dependency(ref) }
        val currentChildren = currentDependency.dependencies
            ?.map { it.ref }
            ?.toMutableSet()
            ?: linkedSetOf()

        node.dependencies.forEach { child ->
            val childRef = child.toPurl()
            if (currentChildren.add(childRef)) {
                val newChildren = (currentDependency.dependencies?.toMutableList() ?: mutableListOf())
                newChildren += Dependency(childRef)
                currentDependency.dependencies = newChildren
            }
            visit(child)
        }
    }

    graph.packages.forEach(::visit)

    val rootRef = metadata?.component?.bomRef
    if (rootRef != null) {
        val rootDependency = dependencyByRef.getOrPut(rootRef) { Dependency(rootRef) }
        val existingRootChildren = rootDependency.dependencies
            ?.map { it.ref }
            ?.toMutableSet()
            ?: linkedSetOf()

        graph.packages.forEach { node ->
            val childRef = node.toPurl()
            if (existingRootChildren.add(childRef)) {
                val newChildren = (rootDependency.dependencies?.toMutableList() ?: mutableListOf())
                newChildren += Dependency(childRef)
                rootDependency.dependencies = newChildren
            }
        }
    }

    components = componentByRef.values.toMutableList()
    dependencies = dependencyByRef.values.toMutableList()
}

private fun Dependency.deepCopy(): Dependency =
    Dependency(ref).also { copy ->
        copy.dependencies = dependencies?.map { it.deepCopy() }
    }

private fun NpmNode.toComponent(): Component =
    Component().apply {
        type = Component.Type.LIBRARY
        name = this@toComponent.name
        version = this@toComponent.version
        purl = this@toComponent.toPurl()
        bomRef = purl
    }

private fun NpmNode.toPurl(): String =
    buildString {
        append("pkg:npm/")
        append(encodeNpmNameForPurl(name))
        append('@')
        append(version)
    }

private fun encodeNpmNameForPurl(name: String): String {
    val separatorIndex = name.indexOf('/')
    return if (name.startsWith("@") && separatorIndex > 1) {
        val scope = name.substring(0, separatorIndex)
        val pkg = name.substring(separatorIndex + 1)
        "${urlEncode(scope)}/${urlEncode(pkg)}"
    } else {
        urlEncode(name)
    }
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")