package at.asitplus.gradle.sbombastic.tasks

import at.asitplus.gradle.sbombastic.internal.SupplierInfo
import at.asitplus.gradle.sbombastic.internal.buildNormalizationPlan
import at.asitplus.gradle.sbombastic.internal.loadSupplierMappings
import at.asitplus.gradle.sbombastic.internal.normalizeBom
import at.asitplus.gradle.sbombastic.internal.projectPublicationCoordinates
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
import java.util.LinkedHashMap

abstract class NormalizeCyclonedxBomTask : DefaultTask() {
    @get:Input
    abstract val publicationName: Property<String>

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
        val normalizationPlan = project.buildNormalizationPlan(publicationName.get(), includeConfigs.get())
        val publicationCoordinates = project.projectPublicationCoordinates(publicationName.get())
            ?: error("Missing publication metadata for ${project.path}:${publicationName.get()}")

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

        val refRewrites = LinkedHashMap<String, String>()
        val jsonBom = JsonParser().parse(inputJson.get().asFile)
            .normalizeBom(
                project = project,
                normalizationPlan = normalizationPlan,
                publicationCoordinates = publicationCoordinates,
                refRewrites = refRewrites,
                supplierInfo = supplierInfo,
                thirdPartySupplierMappings = thirdPartySupplierMappings,
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
