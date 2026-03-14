package at.asitplus.gradle.sbombastic.tasks

import at.asitplus.gradle.sbombastic.internal.SemanticComponentRef
import at.asitplus.gradle.sbombastic.internal.packagingForCoordinates
import at.asitplus.gradle.sbombastic.internal.projectPublicationCoordinates
import at.asitplus.gradle.sbombastic.internal.typeFromPurl
import org.cyclonedx.model.Component
import org.cyclonedx.parsers.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyCyclonedxBomConsistencyTask : DefaultTask() {
    @get:InputFile
    abstract val inputJson: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun verify() {
        val bom = JsonParser().parse(inputJson.get().asFile)
        val knownRefs = linkedSetOf<String>()
        bom.metadata?.component?.bomRef?.let(knownRefs::add)
        bom.components?.mapNotNullTo(knownRefs) { it.bomRef }

        val danglingRefs = mutableListOf<String>()
        bom.dependencies?.forEach { dependency ->
            if (dependency.ref !in knownRefs) {
                danglingRefs += "Missing component for dependency ref: ${dependency.ref}"
            }
            dependency.dependencies?.forEach { dependsOn ->
                if (dependsOn.ref !in knownRefs) {
                    danglingRefs += "Missing component for dependsOn ref: ${dependsOn.ref} (from ${dependency.ref})"
                }
            }
        }

        check(danglingRefs.isEmpty()) {
            buildString {
                appendLine("CycloneDX BOM graph is inconsistent for ${inputJson.get().asFile}:")
                danglingRefs.sorted().forEach(::appendLine)
            }
        }
    }
}

abstract class VerifyCyclonedxBomDirectDependenciesTask : DefaultTask() {
    @get:Input
    abstract val publicationName: Property<String>

    @get:InputFile
    abstract val inputJson: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun verify() {
        val publicationCoordinates = project.projectPublicationCoordinates(publicationName.get())
            ?: error("Missing publication metadata for ${project.path}:${publicationName.get()}")
        val rootSemantic = SemanticComponentRef(
            publicationCoordinates.coordinates.group,
            publicationCoordinates.coordinates.name,
            publicationCoordinates.coordinates.version,
            publicationCoordinates.packaging,
        )
        val expectedDirectDependencies = publicationCoordinates.directDependencies.mapTo(linkedSetOf()) { dependency ->
            val type = project.packagingForCoordinates(dependency)
                ?: error("Missing cached POM packaging for ${dependency.group}:${dependency.name}:${dependency.version}")
            SemanticComponentRef(dependency.group, dependency.name, dependency.version, type)
        }

        val bom = JsonParser().parse(inputJson.get().asFile)
        val bomRefToComponent = linkedMapOf<String, SemanticComponentRef>()
        fun register(ref: String?, component: Component?) {
            if (ref == null || component == null) return
            val group = component.group ?: return
            val name = component.name ?: return
            val version = component.version ?: return
            val type = component.bomRef?.let(::typeFromPurl) ?: component.purl?.let(::typeFromPurl) ?: "unknown"
            bomRefToComponent[ref] = SemanticComponentRef(group, name, version, type)
        }
        register(bom.metadata?.component?.bomRef, bom.metadata?.component)
        bom.components?.forEach { register(it.bomRef, it) }

        val rootDependencyEntry = bom.dependencies?.firstOrNull { it.ref == bom.metadata?.component?.bomRef }
            ?: error("Missing dependency entry for root BOM component in ${inputJson.get().asFile}")
        val actualDirectDependencies = rootDependencyEntry.dependencies.orEmpty().mapTo(linkedSetOf()) { dependsOn ->
            bomRefToComponent[dependsOn.ref]
                ?: error("Dependency ref ${dependsOn.ref} missing component mapping in ${inputJson.get().asFile}")
        }

        val missingDirectDependencies = expectedDirectDependencies - actualDirectDependencies
        val unexpectedDirectDependencies = actualDirectDependencies - expectedDirectDependencies

        check(missingDirectDependencies.isEmpty() && unexpectedDirectDependencies.isEmpty()) {
            buildString {
                appendLine("CycloneDX BOM direct dependencies do not match the publication POM for ${project.path}:${publicationName.get()}")
                appendLine("Root component: $rootSemantic")
                if (missingDirectDependencies.isNotEmpty()) {
                    appendLine("Missing direct dependencies:")
                    missingDirectDependencies.sortedBy { "${it.group}:${it.name}:${it.version}:${it.type}" }
                        .forEach { appendLine("  - $it") }
                }
                if (unexpectedDirectDependencies.isNotEmpty()) {
                    appendLine("Unexpected direct dependencies:")
                    unexpectedDirectDependencies.sortedBy { "${it.group}:${it.name}:${it.version}:${it.type}" }
                        .forEach { appendLine("  - $it") }
                }
            }
        }
    }
}
