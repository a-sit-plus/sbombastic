package at.asitplus.gradle.sbombastic.tasks

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

    @get:InputFile
    abstract val inputJson: org.gradle.api.file.RegularFileProperty

    @get:OutputFile
    abstract val outputJson: org.gradle.api.file.RegularFileProperty

    @get:OutputFile
    abstract val outputXml: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun normalize() {
        val normalizedPublicationName = publicationName.get()
        val resolvedPublicationPlatform = publicationPlatform.get()
            .let {  KotlinPlatformType.valueOf(it)  }
        logger.warn("normalizing for publication '$normalizedPublicationName' with platform '$resolvedPublicationPlatform'")

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
        logger.warn("patching ${inputJson.get()} for $publicationPlatform: $shouldResolveNpm")
        val jsNpmGraph = if (
            shouldResolveNpm
        ) {
            val workspacePackageDirName = buildString {
                append(project.rootProject.name)
                if (project != project.rootProject) {
                    append('-')
                    append(project.name)
                }
            }

            val jsPubDir =if( resolvedPublicationPlatform == KotlinPlatformType.js) "js" else "wasm"
            val jsWorkspacePackageDir = project.rootProject.layout.buildDirectory
                .dir("$jsPubDir/packages/$workspacePackageDirName")
                .get()
                .asFile

            val yarnLockFile = project.rootProject.projectDir.resolve("kotlin-js-store/yarn.lock")

            JsYarnV1NpmGraphCollector.collect(
                project = project,
                workspacePackageDir = jsWorkspacePackageDir,
                yarnLockFile = yarnLockFile,
            )
        } else {
            null
        }
        logger.warn(
            "npm graph for {}: roots={}",
            publicationName.get(),
            jsNpmGraph?.packages?.joinToString { "${it.name}@${it.version}" } ?: "<none>",
        )

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
            )

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