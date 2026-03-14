package at.asitplus.gradle.sbombastic.internal

internal data class SbomComponentCoordinates(
    val group: String,
    val name: String,
    val version: String,
)

internal data class PublishedCoordinates(
    val coordinates: SbomComponentCoordinates,
    val packaging: String,
    val directDependencies: List<SbomComponentCoordinates>,
)

internal data class SbomNormalizationPlan(
    val exactArtifactTypes: Map<SbomComponentCoordinates, String>,
    val coordinateAliases: Map<SbomComponentCoordinates, SbomComponentCoordinates>,
)

internal data class SemanticComponentRef(
    val group: String,
    val name: String,
    val version: String,
    val type: String,
) {
    override fun toString(): String = "$group:$name:$version ($type)"
}
