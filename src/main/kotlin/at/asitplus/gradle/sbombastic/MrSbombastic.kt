package at.asitplus.gradle.sbombastic

import org.gradle.api.Plugin
import org.gradle.api.Project

class MrSbombastic : Plugin<Project> {
    override fun apply(target: Project) {
        if (target == target.rootProject) return

        val extension = target.extensions.create(
            "sbombastic",
            SbombasticExtension::class.java,
        )

        target.sbombastic(extension)
    }
}