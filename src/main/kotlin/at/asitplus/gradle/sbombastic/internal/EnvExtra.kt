package at.asitplus.gradle.sbombastic.internal

import org.gradle.api.Project
import kotlin.reflect.KProperty

internal class EnvExtraDelegate(private val project: Project) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String? = get(property.name)

    operator fun get(name: String): String? =
        System.getenv(name)
            ?: runCatching { project.extensions.extraProperties[name] as String }.getOrNull()
}

internal val Project.envExtra: EnvExtraDelegate
    get() = EnvExtraDelegate(this)
