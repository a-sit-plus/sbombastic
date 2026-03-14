package at.asitplus.gradle.sbombastic.tasks

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

abstract class GenerateJsYarnCyclonedxBomTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Input
    abstract val mainComponentType: Property<String>

    @get:OutputFile
    abstract val outputJson: RegularFileProperty

    @TaskAction
    fun generate() {
        val workspacePackageDirName = buildString {
            append(project.rootProject.name)
            if (project != project.rootProject) {
                append('-')
                append(project.name)
            }
        }

        val workspacePackageDir = project.rootProject.layout.buildDirectory
            .dir("js/packages/$workspacePackageDirName")
            .get()
            .asFile

        val workspacePackageJson = workspacePackageDir.resolve("package.json")
        if (!workspacePackageJson.isFile) {
            throw IllegalStateException("Missing Kotlin/JS workspace package.json: $workspacePackageJson")
        }

        val nodeExec = project.rootProject.extensions
            .findByType(NodeJsEnvSpec::class.java)
            ?.executable
            ?.takeIf { it.get().isNotBlank() }
            ?: throw IllegalStateException("Could not resolve Kotlin-managed Node executable from NodeJsEnvSpec")

        val corepackExec = siblingCorepackExecutable(File(nodeExec.get()))
            ?: throw IllegalStateException("Could not locate corepack next to managed Node executable: $nodeExec")

        val outFile = outputJson.get().asFile
        outFile.parentFile.mkdirs()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val result = execOperations.exec {
            workingDir = workspacePackageDir
            commandLine(
                corepackExec.absolutePath,
                "yarn",
                "dlx",
                "-q",
                "@cyclonedx/yarn-plugin-cyclonedx",
                "--output-format",
                "JSON",
                "--output-file",
                outFile.absolutePath,
                "--mc-type",
                mainComponentType.orNull ?: "library",
            )
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw IllegalStateException(
                buildString {
                    append("cyclonedx-node-yarn generation failed for ")
                    append(workspacePackageDir)
                    append(" using ")
                    append(corepackExec.absolutePath)
                    appendLine()
                    append("stderr: ")
                    append(stderr.toString(Charsets.UTF_8.name()).trim())
                    val stdOutText = stdout.toString(Charsets.UTF_8.name()).trim()
                    if (stdOutText.isNotEmpty()) {
                        appendLine()
                        append("stdout: ")
                        append(stdOutText)
                    }
                },
            )
        }

        if (!outFile.isFile || outFile.length() == 0L) {
            throw IllegalStateException("cyclonedx-node-yarn reported success but did not create output JSON: $outFile")
        }
    }

    private fun siblingCorepackExecutable(nodeExecutable: File): File? {
        val parent = nodeExecutable.parentFile ?: return null
        val candidates = listOf(
            File(parent, "corepack"),
            File(parent, "corepack.cmd"),
            File(parent, "corepack.exe"),
            File(parent, "corepack.bat"),
        )
        return candidates.firstOrNull { it.isFile }
    }
}