package at.asitplus.gradle.sbombastic.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

internal fun Project.buildNormalizationPlan(
    publicationName: String,
    includeConfigs: List<String>,
): SbomNormalizationPlan {
    val artifactTypes = linkedMapOf<SbomComponentCoordinates, MutableSet<String>>()
    val pomPackagings = linkedMapOf<SbomComponentCoordinates, MutableSet<String>>()
    val coordinateAliases = linkedMapOf<SbomComponentCoordinates, SbomComponentCoordinates>()

    projectPublicationCoordinates(publicationName)?.let { publicationCoordinates ->
        pomPackagings.getOrPut(publicationCoordinates.coordinates) { linkedSetOf() }
            .add(publicationCoordinates.packaging)
        publicationCoordinates.directDependencies.forEach { dependencyCoordinates ->
            packagingFromCachedPom(
                dependencyCoordinates.group,
                dependencyCoordinates.name,
                dependencyCoordinates.version,
            )?.let { packaging ->
                pomPackagings.getOrPut(dependencyCoordinates) { linkedSetOf() }.add(packaging)
            }
        }
    }

    includeConfigs.forEach { configurationName ->
        val configuration = configurations.getByName(configurationName)
        val resolvedArtifacts = configuration.incoming.artifactView { isLenient = true }
            .artifacts.artifacts.filterIsInstance<ResolvedArtifactResult>()

        resolvedArtifacts.forEach { artifact ->
            val extension = artifact.file.extension.ifBlank { return@forEach }
            val coordinates = when (val id = artifact.id.componentIdentifier) {
                is ModuleComponentIdentifier -> {
                    val c = SbomComponentCoordinates(id.group, id.module, id.version)
                    packagingFromCachedPom(id.group, id.module, id.version)?.let { packaging ->
                        pomPackagings.getOrPut(c) { linkedSetOf() }.add(packaging)
                    }
                    c
                }

                is ProjectComponentIdentifier -> {
                    val dependencyProject = rootProject.findProject(id.projectPath) ?: return@forEach
                    val fallbackCoordinates = SbomComponentCoordinates(
                        group = dependencyProject.group.toString(),
                        name = dependencyProject.name,
                        version = dependencyProject.version.toString(),
                    )
                    dependencyProject.projectPublicationCoordinates(publicationName)?.let { publicationCoordinates ->
                        coordinateAliases[fallbackCoordinates] = publicationCoordinates.coordinates
                        pomPackagings.getOrPut(publicationCoordinates.coordinates) { linkedSetOf() }
                            .add(publicationCoordinates.packaging)
                        publicationCoordinates.coordinates
                    } ?: fallbackCoordinates
                }

                else -> return@forEach
            }
            artifactTypes.getOrPut(coordinates) { linkedSetOf() }.add(extension)
        }
    }

    val exactExtensions = artifactTypes.filterValues { it.size == 1 }.mapValues { it.value.single() }.toMutableMap()
    pomPackagings.filterValues { it.size == 1 }.forEach { (coordinates, packagings) ->
        exactExtensions[coordinates] = packagings.single()
    }

    return SbomNormalizationPlan(exactArtifactTypes = exactExtensions, coordinateAliases = coordinateAliases)
}
