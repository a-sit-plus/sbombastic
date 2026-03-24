package at.asitplus.gradle.sbombastic.internal

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.findByType
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

private fun MavenPublication.isSbombasticCompatiblePublication(): Boolean =
    name != "relocation" &&
        name != "version" &&
        name != "versions" &&
        !artifactId.endsWith("-versionCatalog")

internal fun packagingFromCachedPom(group: String, module: String, version: String): String? {
    val cacheRoot = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
    val moduleDir = cacheRoot.resolve(group).resolve(module).resolve(version)
    val pomFile =
        moduleDir.takeIf(File::exists)?.walkTopDown()?.firstOrNull { it.isFile && it.extension == "pom" } ?: return null
    val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val document = documentBuilderFactory.newDocumentBuilder().parse(pomFile)
    val packagingElements = document.getElementsByTagName("packaging")
    return if (packagingElements.length == 0) "jar" else packagingElements.item(0).textContent.trim().ifBlank { "jar" }
}

internal fun Project.packagingForCoordinates(coordinates: SbomComponentCoordinates): String? =
    packagingFromCachedPom(coordinates.group, coordinates.name, coordinates.version)
        ?: rootProject.allprojects.asSequence()
            .mapNotNull { candidateProject ->
                candidateProject.layout.buildDirectory.dir("publications").get().asFile.takeIf(File::exists)
                    ?.listFiles()
                    ?.asSequence()
                    ?.mapNotNull { publicationDir ->
                        publicationDir.resolve("pom-default.xml").takeIf(File::exists)?.let(::readPomCoordinates)
                    }
                    ?.firstOrNull { it.coordinates == coordinates }
            }
            .firstOrNull()
            ?.packaging
        ?: rootProject.allprojects.asSequence()
            .mapNotNull { candidateProject ->
                candidateProject.configuredPublishedCoordinates()
                    .firstOrNull { it.coordinates == coordinates }
            }
            .firstOrNull()
            ?.packaging

internal fun Project.projectPublicationCoordinates(publicationName: String): PublishedCoordinates? {
    if (publicationName == "version" || publicationName == "versions") return null
    val pomFile =
        layout.buildDirectory.file("publications/$publicationName/pom-default.xml").get().asFile.takeIf(File::exists)
            ?: return null
    return readPomCoordinates(pomFile)?.takeUnless { it.coordinates.name.endsWith("-versionCatalog") }
}

internal fun Project.findPublicationCoordinates(
    requestedPublicationName: String,
    requestedPlatform: KotlinPlatformType? = null,
): PublishedCoordinates? {
    projectPublicationCoordinates(requestedPublicationName)?.let { return it }

    val publishedCoordinates = allProjectPublicationCoordinates()
    val configuredCoordinates = configuredPublishedCoordinatesByName()
    if (publishedCoordinates.isEmpty() && configuredCoordinates.isEmpty()) return null

    val preferredPublicationNames = buildList {
        requestedPlatform?.let { platform ->
            addAll(defaultPublicationNamesForPlatform(platform))
            if (platform == KotlinPlatformType.jvm && pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
                addAll(listOf("pluginMaven", "mavenJava"))
            }
        }

        if (pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
            addAll(listOf("pluginMaven", "mavenJava"))
        }

        add(name)
    }.distinct()

    preferredPublicationNames.firstNotNullOfOrNull(::projectPublicationCoordinates)?.let { return it }
    preferredPublicationNames.firstNotNullOfOrNull(configuredCoordinates::get)?.let { return it }

    val projectCoordinates = SbomComponentCoordinates(
        group = group.toString(),
        name = name,
        version = version.toString(),
    )

    return publishedCoordinates.entries.firstOrNull { (_, publication) ->
        publication.coordinates == projectCoordinates
    }?.value
        ?: configuredCoordinates.values.firstOrNull { publication ->
            publication.coordinates == projectCoordinates
        }
        ?: publishedCoordinates.entries.firstOrNull { (_, publication) ->
            publication.coordinates.group == projectCoordinates.group &&
                publication.coordinates.version == projectCoordinates.version &&
                publication.coordinates.name.startsWith(projectCoordinates.name)
        }?.value
        ?: configuredCoordinates.values.firstOrNull { publication ->
            publication.coordinates.group == projectCoordinates.group &&
                publication.coordinates.version == projectCoordinates.version &&
                publication.coordinates.name.startsWith(projectCoordinates.name)
        }
        ?: publishedCoordinates.values.firstOrNull()
        ?: configuredCoordinates.values.firstOrNull()
}

private fun Project.allProjectPublicationCoordinates(): Map<String, PublishedCoordinates> {
    val publicationsDir = layout.buildDirectory.dir("publications").get().asFile.takeIf(File::exists) ?: return emptyMap()
    return publicationsDir.listFiles()
        ?.asSequence()
        ?.mapNotNull { publicationDir ->
            val pomFile = publicationDir.resolve("pom-default.xml").takeIf(File::exists) ?: return@mapNotNull null
            readPomCoordinates(pomFile)
                ?.takeUnless { it.coordinates.name.endsWith("-versionCatalog") }
                ?.let { publicationDir.name to it }
        }
        ?.toMap(linkedMapOf())
        .orEmpty()
}

private fun Project.configuredPublishedCoordinates(): List<PublishedCoordinates> {
    val publishing = extensions.findByType<PublishingExtension>() ?: return emptyList()
    return publishing.publications.withType(MavenPublication::class.java)
        .filter(MavenPublication::isSbombasticCompatiblePublication)
        .map { publication ->
        PublishedCoordinates(
            coordinates = SbomComponentCoordinates(
                group = publication.groupId,
                name = publication.artifactId,
                version = publication.version,
            ),
            packaging = inferPackaging(publication),
            directDependencies = emptyList(),
        )
    }
}

private fun Project.configuredPublishedCoordinatesByName(): Map<String, PublishedCoordinates> {
    val publishing = extensions.findByType<PublishingExtension>() ?: return emptyMap()
    return publishing.publications.withType(MavenPublication::class.java)
        .filter(MavenPublication::isSbombasticCompatiblePublication)
        .associate { publication ->
        publication.name to PublishedCoordinates(
            coordinates = SbomComponentCoordinates(
                group = publication.groupId,
                name = publication.artifactId,
                version = publication.version,
            ),
            packaging = inferPackaging(publication),
            directDependencies = emptyList(),
        )
    }
}

private fun Project.inferPackaging(publication: MavenPublication): String = when {
    publication.name == "kotlinMultiplatform" -> "pom"
    publication.artifacts.any { it.extension == "pom" && it.classifier.isNullOrBlank() } -> "pom"
    publication.artifacts.any { it.extension == "aar" && it.classifier.isNullOrBlank() } -> "aar"
    else -> "jar"
}

private fun defaultPublicationNamesForPlatform(platform: KotlinPlatformType): List<String> = when (platform) {
    KotlinPlatformType.common -> listOf("kotlinMultiplatform")
    KotlinPlatformType.jvm -> listOf("jvm", "kotlinJvm", "pluginMaven", "mavenJava")
    KotlinPlatformType.androidJvm -> listOf("android", "release", "debug")
    KotlinPlatformType.js -> listOf("js")
    KotlinPlatformType.wasm -> listOf("wasm")
    KotlinPlatformType.native -> listOf("native")
}

internal fun readPomCoordinates(pomFile: File): PublishedCoordinates? {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val document = documentBuilderFactory.newDocumentBuilder().parse(pomFile)
    fun first(tagName: String): String = document.getElementsByTagName(tagName).item(0).textContent.trim()
    fun directDependencies(): List<SbomComponentCoordinates> {
        val dependencyNodes = document.getElementsByTagName("dependency")
        return buildList {
            for (index in 0 until dependencyNodes.length) {
                val dependencyNode = dependencyNodes.item(index)
                val children = dependencyNode.childNodes
                var group: String? = null
                var name: String? = null
                var version: String? = null
                for (childIndex in 0 until children.length) {
                    val child = children.item(childIndex)
                    when (child.nodeName) {
                        "groupId" -> group = child.textContent.trim()
                        "artifactId" -> name = child.textContent.trim()
                        "version" -> version = child.textContent.trim()
                    }
                }
                if (group != null && name != null && version != null) {
                    add(SbomComponentCoordinates(group, name, version))
                }
            }
        }
    }

    val packagingNodes = document.getElementsByTagName("packaging")
    val packaging =
        if (packagingNodes.length == 0) "jar" else packagingNodes.item(0).textContent.trim().ifBlank { "jar" }
    return PublishedCoordinates(
        coordinates = SbomComponentCoordinates(first("groupId"), first("artifactId"), first("version")),
        packaging = packaging,
        directDependencies = directDependencies(),
    ).takeUnless { it.coordinates.name.endsWith("-versionCatalog") }
}
