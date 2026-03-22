package at.asitplus.gradle.sbombastic

import at.asitplus.gradle.sbombastic.internal.envExtra
import at.asitplus.gradle.sbombastic.internal.supplierInfoFromEnvExtra
import at.asitplus.gradle.sbombastic.internal.supplierMappingsUrlFromEnvExtra
import at.asitplus.gradle.sbombastic.internal.toOrganizationalEntity
import at.asitplus.gradle.sbombastic.tasks.NormalizeCyclonedxBomTask
import at.asitplus.gradle.sbombastic.tasks.VerifyCyclonedxBomConsistencyTask
import at.asitplus.gradle.sbombastic.tasks.VerifyCyclonedxBomDirectDependenciesTask
import java.util.Locale
import org.cyclonedx.Version
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.gradle.CyclonedxPlugin
import org.cyclonedx.model.Component
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

internal val Project.enableSbombasitc: Boolean
    get() = envExtra[SBOMBASTIC_ENABLED]?.toBoolean() ?: false
internal val Project.debugManualDependencies: Boolean
    get() = envExtra[SBOMBASTIC_DEBUG_MANUAL]?.toBoolean() ?: false

internal val Project.licenseId get() = envExtra[LICENSE_ID]?.trim()
internal val Project.licenseName get() = envExtra[LICENSE_NAME]?.trim()
internal val Project.licenseUrl get() = envExtra[LICENSE_URL]?.trim()

internal fun Project.sbombastic(extension: SbombasticExtension) {
    if (!enableSbombasitc) {
        logger.lifecycle("  > SBOM generation disabled for project $path")
        return
    }

    logger.lifecycle("  > Don't call me Mr. SBOMbastic! We did not get legal clearance for that pun from Shaggy.")

    val supplierInfo = supplierInfoFromEnvExtra()

    pluginManager.apply(CyclonedxPlugin::class.java)

    tasks.matching { it.name == "cyclonedxDirectBom" }.configureEach {
        enabled = false
        description = "Disabled in favor of publication-specific CycloneDX SBOM tasks."
    }
    tasks.matching { it.name == "cyclonedxBom" }.configureEach {
        enabled = false
        description = "Disabled in favor of publication-specific CycloneDX SBOM tasks."
    }

    pluginManager.withPlugin("maven-publish") {
        afterEvaluate {
            val publishing = extensions.findByType<PublishingExtension>() ?: return@afterEvaluate
            val publicationSbomTasks = mutableListOf<String>()

            publishing.publications.withType<MavenPublication>().configureEach {
                val publication = this
                val publicationConfigNames = cyclonedxConfigsForPublication(publication.name)
                if (
                    publication.name == "relocation" ||
                    publication.artifactId.endsWith("-versionCatalog") ||
                    publicationConfigNames.isEmpty()
                ) {
                    return@configureEach
                }

                val platform = resolvePublicationPlatform(publication.name)
                val cyclonedxTaskName = cyclonedxTaskNameForPublication(publication.name)
                val cyclonedxTask = tasks.register<CyclonedxDirectTask>(cyclonedxTaskName) {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Generates CycloneDX SBOM for Maven publication '${publication.name}'."
                    includeConfigs.set(publicationConfigNames)
                    projectType.set(Component.Type.LIBRARY)
                    schemaVersion.set(Version.VERSION_16)
                    includeMetadataResolution.set(true)
                    includeBuildSystem.set(true)
                    includeBomSerialNumber.set(true)
                    componentGroup.set(project.group.toString())
                    componentName.set(publication.artifactId)
                    componentVersion.set(project.version.toString())
                    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-publications/${publication.name}/bom.raw.json"))
                    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx-publications/${publication.name}/bom.raw.xml"))

                    supplierInfo?.let {
                        organizationalEntity.set(it.toOrganizationalEntity())
                    }
                }

                val normalizeTask = tasks.register<NormalizeCyclonedxBomTask>("${cyclonedxTaskName}Normalized") {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Normalizes CycloneDX package types for Maven publication '${publication.name}'."
                    dependsOn(cyclonedxTask)
                    dependsOn(tasks.matching {
                        it.name == "generatePomFileFor${
                            publication.name.replaceFirstChar { ch ->
                                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                            }
                        }Publication"
                    })
                    if (platform == KotlinPlatformType.js || platform == KotlinPlatformType.wasm) {
                        dependsOn(
                            project.rootProject.tasks.matching {
                                it.name.contains("packageJson", ignoreCase = true) ||
                                        it.name.contains("rootPackageJson", ignoreCase = true) ||
                                        it.name.contains("kotlinStoreYarnLock", ignoreCase = true) ||
                                        it.name.contains("yarnLock", ignoreCase = true)
                            },
                        )
                    }

                    publicationName.set(publication.name)
                    publicationPlatform.set(platform.name)
                    includeConfigs.set(publicationConfigNames)
                    inputJson.set(cyclonedxTask.flatMap { it.jsonOutput })
                    outputJson.set(layout.buildDirectory.file("reports/cyclonedx-publications/${publication.name}/bom.json"))
                    outputXml.set(layout.buildDirectory.file("reports/cyclonedx-publications/${publication.name}/bom.xml"))

                    supplierName.set(supplierInfo?.name.orEmpty())
                    supplierUrls.set(supplierInfo?.urls ?: emptyList())
                    supplierContactName.set(supplierInfo?.contactName.orEmpty())
                    supplierEmail.set(supplierInfo?.email.orEmpty())
                    supplierMappingsUrl.set(project.supplierMappingsUrlFromEnvExtra().orEmpty())
                    manualDependenciesJson.set(
                        providers.provider {
                            val collection = collectTransitiveManualDependencies(
                                ownExtension = extension,
                                includeConfigs = publicationConfigNames,
                            )
                            if (debugManualDependencies) {
                                logger.lifecycle(
                                    buildString {
                                        append("  > SBOM manual dependency collection for ")
                                        append(path)
                                        append(':')
                                        append(publication.name)
                                        append(" using configs ")
                                        append(collection.configurations.joinToString(prefix = "[", postfix = "]"))
                                        append(" visited projects ")
                                        append(collection.visitedProjects.joinToString(prefix = "[", postfix = "]"))
                                        append(" collected ")
                                        append(collection.dependencies.map { it.name }.joinToString(prefix = "[", postfix = "]"))
                                    },
                                )
                            }
                            collection.dependencies.map { it.toJson() }
                        },
                    )
                }

                if (publication.name == "kotlinMultiplatform") {
                    registerRootKmpSbomVariants(normalizeTask)
                }

                val verifyTask = tasks.register<VerifyCyclonedxBomConsistencyTask>("${cyclonedxTaskName}Consistency") {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Verifies CycloneDX SBOM graph consistency for Maven publication '${publication.name}'."
                    dependsOn(normalizeTask)
                    inputJson.set(normalizeTask.flatMap { it.outputJson })
                }

                val compareTask =
                    tasks.register<VerifyCyclonedxBomDirectDependenciesTask>("${cyclonedxTaskName}DirectDependencies") {
                        group = LifecycleBasePlugin.VERIFICATION_GROUP
                        description =
                            "Verifies CycloneDX SBOM direct dependencies for Maven publication '${publication.name}' against the publication POM."
                        dependsOn(normalizeTask)
                        publicationName.set(publication.name)
                        inputJson.set(normalizeTask.flatMap { it.outputJson })
                    }

                publicationSbomTasks += normalizeTask.name
                publicationSbomTasks += verifyTask.name
                publicationSbomTasks += compareTask.name

                if (publication.name != "kotlinMultiplatform") {
                    publication.artifact(normalizeTask.flatMap { it.outputJson }) {
                        this.classifier = "cyclonedx"
                        this.extension = "json"
                        builtBy(normalizeTask)
                    }
                    publication.artifact(normalizeTask.flatMap { it.outputXml }) {
                        classifier = "cyclonedx"
                        this.extension = "xml"
                        builtBy(normalizeTask)
                    }
                }
            }

            if (publicationSbomTasks.isEmpty()) {
                return@afterEvaluate
            }

            val aggregateTask = tasks.register("cyclonedxPublishedBom") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Generates CycloneDX SBOMs for all published Maven publications in $path."
                dependsOn(publicationSbomTasks)
            }

            tasks.withType<AbstractPublishToMaven>().configureEach {
                dependsOn(aggregateTask)
            }
        }
    }
}

private fun Project.collectTransitiveManualDependencies(
    ownExtension: SbombasticExtension,
    includeConfigs: List<String>,
): ManualDependencyCollection {
    val visited = linkedSetOf<Project>()
    val result = linkedMapOf<String, ManualSbomDependency>()
    val resolvedConfigurations = linkedSetOf<String>()

    fun visit(current: Project) {
        if (!visited.add(current)) return

        val currentExtension = current.extensions.findByType<SbombasticExtension>()
        val declared = (currentExtension ?: if (current == this) ownExtension else null)
            ?.manualDependencies
            ?.map { it.toModel() }
            .orEmpty()

        declared.forEach { dependency ->
            result.putIfAbsent(manualDependencyIdentity(dependency), dependency)
        }
    }

    visit(this)

    includeConfigs.asSequence()
        .mapNotNull(configurations::findByName)
        .forEach { configuration ->
            resolvedConfigurations += configuration.name
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val projectId = component.id as? ProjectComponentIdentifier ?: return@forEach
                rootProject.findProject(projectId.projectPath)?.let(::visit)
            }
        }

    return ManualDependencyCollection(
        dependencies = result.values.toList(),
        visitedProjects = visited.map(Project::getPath),
        configurations = resolvedConfigurations.toList(),
    )
}

private data class ManualDependencyCollection(
    val dependencies: List<ManualSbomDependency>,
    val visitedProjects: List<String>,
    val configurations: List<String>,
)

private fun manualDependencyIdentity(dependency: ManualSbomDependency): String =
    buildString {
        append(dependency.name)
        append('|')
        append(dependency.version)
        append('|')
        append(dependency.vcsUrls.joinToString(","))
    }

private fun Project.resolvePublicationPlatform(publicationName: String): KotlinPlatformType {
    if (publicationName == "kotlinMultiplatform") {
        return KotlinPlatformType.common
    }

    val kotlin = extensions.findByType<KotlinMultiplatformExtension>()
    if (kotlin == null) {
        return if (pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
            KotlinPlatformType.jvm
        } else {
            KotlinPlatformType.common
        }
    }

    val target = kotlin.targets.findByName(publicationName)
        ?: return if (pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
            KotlinPlatformType.jvm
        } else {
            KotlinPlatformType.common
        }

    return target.platformType
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
private fun Project.registerRootKmpSbomVariants(
    normalizeTask: org.gradle.api.tasks.TaskProvider<NormalizeCyclonedxBomTask>,
) {
    val kotlin = extensions.findByType<KotlinMultiplatformExtension>()
        ?: error("kotlinMultiplatform publication exists in $path, but KotlinMultiplatformExtension was not found")

    val jsonElements = configurations.maybeCreate("kotlinMultiplatformSbomJsonElements").apply {
        isCanBeConsumed = true
        isCanBeResolved = false
        isVisible = false
        description = "Documentation-only CycloneDX JSON SBOM variant for the kotlinMultiplatform publication"

        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
            attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("sbom-cyclonedx-json"))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        }

        outgoing.artifacts.clear()
        outgoing.artifact(normalizeTask.flatMap { it.outputJson }) {
            classifier = "cyclonedx"
            extension = "json"
            builtBy(normalizeTask)
        }
    }

    val xmlElements = configurations.maybeCreate("kotlinMultiplatformSbomXmlElements").apply {
        isCanBeConsumed = true
        isCanBeResolved = false
        isVisible = false
        description = "Documentation-only CycloneDX XML SBOM variant for the kotlinMultiplatform publication"

        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
            attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("sbom-cyclonedx-xml"))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        }

        outgoing.artifacts.clear()
        outgoing.artifact(normalizeTask.flatMap { it.outputXml }) {
            classifier = "cyclonedx"
            extension = "xml"
            builtBy(normalizeTask)
        }
    }

    kotlin.publishing.adhocSoftwareComponent.addVariantsFromConfiguration(jsonElements) {}
    kotlin.publishing.adhocSoftwareComponent.addVariantsFromConfiguration(xmlElements) {}
}

private fun Project.cyclonedxConfigsForPublication(publicationName: String): List<String> {
    if (publicationName == "kotlinMultiplatform") {
        return listOf("allSourceSetsCompileDependenciesMetadata")
    }

    val orderedCandidates = buildList {
        addAll(
            listOf(
                "${publicationName}RuntimeClasspath",
                "${publicationName}CompileClasspath",
                "${publicationName}CompileKlibraries",
                "${publicationName}CompilationDependenciesMetadata",
                "${publicationName}MainResolvableDependenciesMetadata",
                "${publicationName}MainImplementationDependenciesMetadata",
            ),
        )

        if (pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
            add("runtimeClasspath")
            add("compileClasspath")
        }
    }

    return orderedCandidates.mapNotNull { configurations.findByName(it)?.name }.distinct()
}

private fun cyclonedxTaskNameForPublication(publicationName: String): String =
    "cyclonedx${publicationName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }}PublicationBom"
