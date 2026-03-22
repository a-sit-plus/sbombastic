plugins {
    `kotlin-dsl`
    `signing`
    id("com.gradle.plugin-publish") version "1.2.1"
}

group = "at.asitplus.gradle"
version = "0.0.1"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("org.cyclonedx:cyclonedx-gradle-plugin:3.2.2") {
        //upgrade transitive dependency to a non.vulnerable version
        implementation("org.cyclonedx:cyclonedx-core-java:11.0.1")
    }
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")
}

gradlePlugin {
    website = "https://github.com/a-sit-plus/sbombastic"
    vcsUrl = "https://github.com/a-sit-plus/sbombastic"
    plugins {
        create("sbombastic") {
            id = "at.asitplus.gradle.sbombastic"
            implementationClass = "at.asitplus.gradle.sbombastic.MrSbombastic"
            displayName = "SBOMbastic"
            description = "Accurate, publication-aware CycloneDX SBOM generation and normalization for Kotlin Mulitplatform"
            tags = listOf("sbom", "kotlin", "kmp", "multiplatform")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

publishing {
    repositories {
        mavenLocal {
            signing.isRequired = false
        }
    }
}
signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    sign(publishing.publications)
}