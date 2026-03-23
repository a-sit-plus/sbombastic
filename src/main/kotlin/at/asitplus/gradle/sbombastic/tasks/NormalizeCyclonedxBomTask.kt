package at.asitplus.gradle.sbombastic.tasks

import at.asitplus.gradle.sbombastic.debugManualDependencies
import at.asitplus.gradle.sbombastic.manualSbomDependencyFromJson
import at.asitplus.gradle.sbombastic.internal.JsYarnV1NpmGraphCollector
import at.asitplus.gradle.sbombastic.internal.SupplierInfo
import at.asitplus.gradle.sbombastic.internal.buildNormalizationPlan
import at.asitplus.gradle.sbombastic.internal.loadSupplierMappings
import at.asitplus.gradle.sbombastic.internal.normalizeBom
import at.asitplus.gradle.sbombastic.internal.projectPublicationCoordinates
import java.util.LinkedHashMap
import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.parsers.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

abstract class NormalizeCyclonedxBomTask : DefaultTask() {
    @get:Input
    abstract val publicationName: Property<String>

    @get:Input
    abstract val publicationPlatform: Property<String>

    @get:Input
    abstract val includeConfigs: ListProperty<String>

    @get:Input
    abstract val supplierName: Property<String>

    @get:Input
    abstract val supplierUrls: ListProperty<String>

    @get:Input
    abstract val supplierContactName: Property<String>

    @get:Input
    abstract val supplierEmail: Property<String>

    @get:Input
    abstract val supplierMappingsUrl: Property<String>

    @get:Input
    abstract val manualDependenciesJson: ListProperty<String>

    @get:InputFile
    abstract val inputJson: org.gradle.api.file.RegularFileProperty

    @get:OutputFile
    abstract val outputJson: org.gradle.api.file.RegularFileProperty

    @get:OutputFile
    abstract val outputXml: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun normalize() {
        val normalizedPublicationName = publicationName.get()
        val resolvedPublicationPlatform = KotlinPlatformType.valueOf(publicationPlatform.get())

        val normalizationPlan = project.buildNormalizationPlan(normalizedPublicationName, includeConfigs.get())
        val publicationCoordinates = project.projectPublicationCoordinates(normalizedPublicationName)
            ?: error("Missing publication metadata for ${project.path}:$normalizedPublicationName")

        val supplierInfo = supplierName.orNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                SupplierInfo(
                    name = it,
                    urls = supplierUrls.get().filter { url -> url.isNotBlank() },
                    contactName = supplierContactName.orNull?.trim()?.ifBlank { null },
                    email = supplierEmail.orNull?.trim()?.ifBlank { null },
                )
            }

        val thirdPartySupplierMappings = supplierMappingsUrl.orNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::loadSupplierMappings)
            .orEmpty()

        val shouldResolveNpm = resolvedPublicationPlatform == KotlinPlatformType.js ||
                resolvedPublicationPlatform == KotlinPlatformType.wasm

        val jsNpmGraph = if (shouldResolveNpm) {
            val workspacePackageDirName = buildString {
                append(project.rootProject.name)
                if (project != project.rootProject) {
                    append('-')
                    append(project.name)
                }
            }

            val packageDir = if (resolvedPublicationPlatform == KotlinPlatformType.wasm) {
                project.rootProject.layout.buildDirectory
                    .dir("wasm/packages/$workspacePackageDirName")
                    .get()
                    .asFile
            } else {
                project.rootProject.layout.buildDirectory
                    .dir("js/packages/$workspacePackageDirName")
                    .get()
                    .asFile
            }

            val yarnLockFile = project.rootProject.projectDir.resolve("kotlin-js-store/yarn.lock")

            JsYarnV1NpmGraphCollector.collect(
                project = project,
                workspacePackageDir = packageDir,
                yarnLockFile = yarnLockFile,
            )
        } else {
            null
        }

        val manualDependencies = manualDependenciesJson.getOrElse(emptyList())
            .map(::manualSbomDependencyFromJson)

        val refRewrites = LinkedHashMap<String, String>()
        val jsonBom = JsonParser().parse(inputJson.get().asFile)
            .normalizeBom(
                project = project,
                normalizationPlan = normalizationPlan,
                publicationCoordinates = publicationCoordinates,
                refRewrites = refRewrites,
                supplierInfo = supplierInfo,
                thirdPartySupplierMappings = thirdPartySupplierMappings,
                jsNpmGraph = jsNpmGraph,
                manualDependencies = manualDependencies,
            )

        if (project.debugManualDependencies) {
            val genericComponents = buildList {
                jsonBom.metadata?.component?.takeIf { component ->
                    component.bomRef?.startsWith("pkg:generic/") == true || component.purl?.startsWith("pkg:generic/") == true
                }?.let(::add)
                jsonBom.components.orEmpty().forEach { component ->
                    if (component.bomRef?.startsWith("pkg:generic/") == true || component.purl?.startsWith("pkg:generic/") == true) {
                        add(component)
                    }
                }
            }
            val rootRef = jsonBom.metadata?.component?.bomRef
            val rootDependsOn = jsonBom.dependencies.orEmpty()
                .firstOrNull { it.ref == rootRef }
                ?.dependencies
                .orEmpty()
                .map { it.ref }

            logger.lifecycle(
                buildString {
                    append("  > SBOM normalized manual output for ")
                    append(project.path)
                    append(':')
                    append(normalizedPublicationName)
                    append(" generic components ")
                    append(
                        genericComponents.map { component ->
                            component.name + ":" + component.version
                        }.joinToString(prefix = "[", postfix = "]"),
                    )
                    append(" root dependsOn ")
                    append(rootDependsOn.joinToString(prefix = "[", postfix = "]"))
                },
            )
        }

        outputJson.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BomGeneratorFactory.createJson(Version.VERSION_16, jsonBom).toJsonString())
        }
        outputXml.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BomGeneratorFactory.createXml(Version.VERSION_16, jsonBom).toXmlString())
        }
    }
}
