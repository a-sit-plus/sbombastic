package at.asitplus.gradle.sbombastic.internal

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.Project

internal data class JsResolvedNpmGraph(
    val rootName: String?,
    val rootVersion: String?,
    val packages: List<NpmNode>,
)

internal data class NpmNode(
    val name: String,
    val version: String,
    val dependencies: List<NpmNode>,
)

internal object JsYarnV1NpmGraphCollector {

    fun collect(
        project: Project,
        workspacePackageDir: File,
        yarnLockFile: File,
    ): JsResolvedNpmGraph? =
        collect(
            project = project,
            workspacePackageDirs = listOf(workspacePackageDir),
            yarnLockFile = yarnLockFile,
        )

    fun collect(
        project: Project,
        workspacePackageDirs: List<File>,
        yarnLockFile: File,
    ): JsResolvedNpmGraph? {
        if (!yarnLockFile.isFile) {
            project.logger.info("Skipping JS/WASM npm graph collection: no yarn.lock at {}", yarnLockFile)
            return null
        }

        val candidatePackageJsonFiles = workspacePackageDirs
            .distinct()
            .map { it.resolve("package.json") }
            .filter { it.isFile }

        if (candidatePackageJsonFiles.isEmpty()) {
            project.logger.info(
                "Skipping JS/WASM npm graph collection: no package.json in any candidate dirs {}",
                workspacePackageDirs,
            )
            return null
        }

        val manifests = candidatePackageJsonFiles.map { packageJsonFile ->
            packageJsonFile to parsePackageManifest(packageJsonFile)
        }

        val selected = manifests.firstOrNull { (_, manifest) -> manifest.dependencies.isNotEmpty() }
            ?: manifests.first()

        val selectedPackageJsonFile = selected.first
        val manifest = selected.second
        val directDependencies = manifest.dependencies

        project.logger.info(
            "Using JS/WASM package.json {} with dependencies {}",
            selectedPackageJsonFile,
            directDependencies.keys.sorted(),
        )

        if (directDependencies.isEmpty()) {
            project.logger.info(
                "Skipping JS/WASM npm graph collection: no direct dependencies in {}",
                selectedPackageJsonFile,
            )
            return JsResolvedNpmGraph(
                rootName = manifest.name,
                rootVersion = manifest.version,
                packages = emptyList(),
            )
        }

        val lock = parseYarnLockV1(yarnLockFile)

        val rootNodes = directDependencies.entries.mapNotNull { (depName, depRange) ->
            buildTreeForSelector(
                selector = "$depName@$depRange",
                lock = lock,
                visitedSelectors = linkedSetOf(),
                logger = project.logger,
            )
        }

        return JsResolvedNpmGraph(
            rootName = manifest.name,
            rootVersion = manifest.version,
            packages = rootNodes,
        )
    }

    private data class PackageManifest(
        val name: String?,
        val version: String?,
        val dependencies: Map<String, String>,
    )

    private data class YarnLockEntry(
        val selectors: Set<String>,
        val version: String,
        val dependencies: Map<String, String>,
    )

    private data class YarnLock(
        val entries: List<YarnLockEntry>,
        val bySelector: Map<String, YarnLockEntry>,
    )

    private fun parsePackageManifest(packageJsonFile: File): PackageManifest {
        val parsed = JsonSlurper().parse(packageJsonFile) as? Map<*, *> ?: emptyMap<Any?, Any?>()

        val dependencies = (parsed["dependencies"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = v as? String ?: return@mapNotNull null
                key to value
            }
            ?.toMap()
            .orEmpty()

        return PackageManifest(
            name = parsed["name"] as? String,
            version = parsed["version"] as? String,
            dependencies = dependencies,
        )
    }

    private fun parseYarnLockV1(yarnLockFile: File): YarnLock {
        val lines = yarnLockFile.readLines()
        val entries = mutableListOf<YarnLockEntry>()
        var index = 0

        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()

            if (line.isBlank() || line.startsWith("#")) {
                index++
                continue
            }

            if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
                index++
                continue
            }

            if (!line.endsWith(":")) {
                index++
                continue
            }

            val selectors = parseSelectors(line.removeSuffix(":"))
            index++

            var version: String? = null
            val dependencies = linkedMapOf<String, String>()
            var inDependenciesBlock = false

            while (index < lines.size) {
                val bodyRaw = lines[index]
                val bodyLine = bodyRaw.trimEnd()

                if (bodyLine.isBlank()) {
                    index++
                    continue
                }

                if (!bodyRaw.startsWith(" ") && !bodyRaw.startsWith("\t")) {
                    break
                }

                val indent = bodyRaw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                val trimmed = bodyRaw.trim()

                if (indent == 2 && trimmed.startsWith("version ")) {
                    version = parseQuotedOrBareValue(trimmed.removePrefix("version ").trim())
                    inDependenciesBlock = false
                    index++
                    continue
                }

                if (indent == 2 && trimmed == "dependencies:") {
                    inDependenciesBlock = true
                    index++
                    continue
                }

                if (inDependenciesBlock && indent >= 4) {
                    parseDependencyLine(trimmed)?.let { (depName, depRange) ->
                        dependencies[depName] = depRange
                    }
                    index++
                    continue
                }

                if (indent == 2) {
                    inDependenciesBlock = false
                }

                index++
            }

            if (selectors.isNotEmpty() && version != null) {
                entries += YarnLockEntry(
                    selectors = selectors,
                    version = version,
                    dependencies = dependencies,
                )
            }
        }

        val bySelector = linkedMapOf<String, YarnLockEntry>()
        entries.forEach { entry ->
            entry.selectors.forEach { selector ->
                bySelector[selector] = entry
            }
        }

        return YarnLock(entries, bySelector)
    }

    private fun parseSelectors(raw: String): Set<String> {
        val quoted = Regex("\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
        if (quoted.isNotEmpty()) return quoted.toSet()

        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun parseQuotedOrBareValue(raw: String): String =
        raw.removeSurrounding("\"").trim()

    private fun parseDependencyLine(trimmed: String): Pair<String, String>? {
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return null
        val depName = parts[0].removeSurrounding("\"")
        val depRange = parts[1].removeSurrounding("\"")
        return depName to depRange
    }

    private fun buildTreeForSelector(
        selector: String,
        lock: YarnLock,
        visitedSelectors: MutableSet<String>,
        logger: org.gradle.api.logging.Logger,
    ): NpmNode? {
        val normalizedSelector = selector.trim()
        if (!visitedSelectors.add(normalizedSelector)) {
            return null
        }

        val entry = resolveSelector(normalizedSelector, lock)
        if (entry == null) {
            logger.info("No Yarn lock entry found for selector {}", normalizedSelector)
            return null
        }

        val packageName = selectorPackageName(normalizedSelector)
        val children = entry.dependencies.entries.mapNotNull { (childName, childRange) ->
            buildTreeForSelector(
                selector = "$childName@$childRange",
                lock = lock,
                visitedSelectors = visitedSelectors.toMutableSet(),
                logger = logger,
            )
        }

        return NpmNode(
            name = packageName,
            version = entry.version,
            dependencies = children,
        )
    }

    private fun resolveSelector(selector: String, lock: YarnLock): YarnLockEntry? {
        lock.bySelector[selector]?.let { return it }

        val unquoted = selector.removeSurrounding("\"")
        lock.bySelector[unquoted]?.let { return it }
        lock.bySelector["\"$unquoted\""]?.let { return it }

        return null
    }

    private fun selectorPackageName(selector: String): String {
        val normalized = selector.removeSurrounding("\"")
        val atIndex = normalized.lastIndexOf('@')
        if (atIndex <= 0) return normalized
        return normalized.substring(0, atIndex)
    }
}