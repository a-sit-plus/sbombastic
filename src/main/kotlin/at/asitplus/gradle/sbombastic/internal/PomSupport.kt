package at.asitplus.gradle.sbombastic.internal

import org.gradle.api.Project
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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

internal fun Project.projectPublicationCoordinates(publicationName: String): PublishedCoordinates? {
    val pomFile =
        layout.buildDirectory.file("publications/$publicationName/pom-default.xml").get().asFile.takeIf(File::exists)
            ?: return null
    return readPomCoordinates(pomFile)
}

internal fun readPomCoordinates(pomFile: File): PublishedCoordinates {
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
    )
}
