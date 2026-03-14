package net.spartanb312.grunt.web

import net.spartanb312.grunt.config.Configs
import net.spartanb312.grunt.event.events.FinalizeEvent
import net.spartanb312.grunt.event.events.ProcessEvent
import net.spartanb312.grunt.event.events.TransformerEvent
import net.spartanb312.grunt.process.Transformers
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.process.transformers.PostProcessTransformer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarFile
import kotlin.system.measureTimeMillis

/**
 * Manages a single obfuscation session for web mode.
 */
class ObfuscationSession {

    enum class ProjectScope {
        INPUT, OUTPUT
    }

    enum class Status {
        IDLE, UPLOADING, READY, RUNNING, COMPLETED, ERROR
    }

    @Volatile
    var status: Status = Status.IDLE

    @Volatile
    var currentStep: String = ""

    @Volatile
    var progress: Int = 0

    @Volatile
    var totalSteps: Int = 0

    @Volatile
    var errorMessage: String? = null

    var inputJarPath: String? = null
    var outputJarPath: String? = null

    @Volatile
    var inputClassList: List<String>? = null

    /** Final obfuscated class name list (populated after completion) */
    @Volatile
    var finalClassList: List<String>? = null

    // Keep decompiled sources in-memory to avoid repeated CFR work.
    private val decompiledCache = ConcurrentHashMap<String, String>()

    // Console log messages
    val consoleLogs = CopyOnWriteArrayList<String>()

    // Listeners for real-time updates
    var onLogMessage: ((String) -> Unit)? = null
    var onProgressUpdate: ((String) -> Unit)? = null

    fun log(msg: String) {
        consoleLogs.add(msg)
        onLogMessage?.invoke(msg)
    }

    /**
     * Run the obfuscation process.
     */
    fun runObfuscation() {
        status = Status.RUNNING
        finalClassList = null
        clearCachedSources(ProjectScope.OUTPUT)
        consoleLogs.clear()
        errorMessage = null
        progress = 0

        try {
            ProcessEvent.Before.post()
            val time = measureTimeMillis {
                val inputPath = inputJarPath ?: throw IllegalStateException("No input JAR uploaded")
                ResourceCache(inputPath, Configs.Settings.libraries).apply {
                    log("Reading JAR: $inputPath")
                    currentStep = "Reading JAR..."
                    readJar()

                    val enabledTransformers = Transformers.sortedBy { it.order }
                        .filter { it.enabled && it != PostProcessTransformer }
                    totalSteps = enabledTransformers.size + 1 // +1 for PostProcess

                    val timeUsage = mutableMapOf<String, Long>()
                    val obfTime = measureTimeMillis {
                        log("Processing...")

                        enabledTransformers.forEachIndexed { index, transformer ->
                            val preEvent = TransformerEvent.Before(transformer, this)
                            preEvent.post()
                            if (!preEvent.cancelled) {
                                val actualTransformer = preEvent.transformer
                                currentStep = actualTransformer.name
                                progress = ((index + 1).toFloat() / totalSteps * 100).toInt()
                                log("Running transformer: ${actualTransformer.name} (${index + 1}/$totalSteps)")
                                onProgressUpdate?.invoke(
                                    """{"step":"${actualTransformer.name}","current":${index + 1},"total":$totalSteps,"progress":$progress}"""
                                )

                                val startTime = System.currentTimeMillis()
                                with(actualTransformer) { transform() }
                                timeUsage[actualTransformer.name] = System.currentTimeMillis() - startTime

                                val postEvent = TransformerEvent.After(actualTransformer, this)
                                postEvent.post()
                            }
                        }

                        // PostProcess
                        currentStep = "PostProcess"
                        progress = 95
                        log("Running PostProcess...")
                        onProgressUpdate?.invoke(
                            """{"step":"PostProcess","current":$totalSteps,"total":$totalSteps,"progress":95}"""
                        )
                        with(PostProcessTransformer) {
                            transform()
                            FinalizeEvent.Before(this@apply).post()
                            finalize()
                            FinalizeEvent.After(this@apply).post()
                        }
                    }

                    log("Took $obfTime ms to process!")
                    if (Configs.Settings.timeUsage) timeUsage.forEach { (name, duration) ->
                        log("   $name $duration ms")
                    }

                    // Record final class structure (names only, no bytes)
                    finalClassList = classes.keys.sorted()

                    // Dump output
                    val output = Configs.Settings.output
                    log("Dumping to $output")
                    currentStep = "Dumping..."
                    progress = 98
                    dumpJar(output)
                    outputJarPath = output
                }
            }
            ProcessEvent.After.post()
            log("Finished in $time ms!")
            currentStep = "Completed"
            progress = 100
            status = Status.COMPLETED
            onProgressUpdate?.invoke(
                """{"step":"Completed","current":$totalSteps,"total":$totalSteps,"progress":100}"""
            )
        } catch (e: Exception) {
            status = Status.ERROR
            errorMessage = e.message ?: "Unknown error"
            log("ERROR: ${e.message}")
            e.printStackTrace()
            onProgressUpdate?.invoke(
                """{"step":"Error","error":"${e.message?.replace("\"", "\\\"")}"}"""
            )
        }
    }

    /**
     * Get final obfuscated class/package structure (names only).
     */
    fun getFinalStructure(): List<String>? = finalClassList

    fun setInputClasses(classes: List<String>) {
        inputClassList = classes.sorted()
        clearCachedSources(ProjectScope.INPUT)
    }

    fun getProjectClasses(scope: ProjectScope): List<String>? {
        return when (scope) {
            ProjectScope.INPUT -> inputClassList
            ProjectScope.OUTPUT -> finalClassList
        }
    }

    fun decompileClass(scope: ProjectScope, className: String): String {
        val normalizedClass = normalizeClassName(className)
        val cacheKey = "${scope.name}:$normalizedClass"
        decompiledCache[cacheKey]?.let { return it }

        val jarPath = getJarPath(scope)
            ?: throw IllegalStateException("No ${scope.name.lowercase()} JAR available")
        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            throw IllegalStateException("JAR file not found: $jarPath")
        }

        val allClasses = readAllClassBytes(jarFile)
        val classBytes = allClasses[normalizedClass] ?: throw NoSuchElementException("Class not found")
        return Decompiler.decompile(normalizedClass, classBytes, allClasses).also {
            decompiledCache[cacheKey] = it
        }
    }

    private fun getJarPath(scope: ProjectScope): String? {
        return when (scope) {
            ProjectScope.INPUT -> inputJarPath
            ProjectScope.OUTPUT -> outputJarPath
        }
    }

    private fun normalizeClassName(className: String): String {
        val trimmed = className.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Class name is empty")
        if (trimmed.contains("..") || trimmed.contains(":") || trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw IllegalArgumentException("Illegal class name")
        }

        val withoutSuffix = trimmed.removeSuffix(".class")
        val normalized = if (withoutSuffix.contains('/')) withoutSuffix else withoutSuffix.replace('.', '/')
        if (normalized.isBlank()) throw IllegalArgumentException("Illegal class name")
        return normalized
    }

    private fun readAllClassBytes(jarFile: File): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        JarFile(jarFile).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry ->
                    val className = entry.name.removeSuffix(".class")
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    result[className] = bytes
                }
        }
        return result
    }

    private fun clearCachedSources(scope: ProjectScope) {
        val prefix = "${scope.name}:"
        decompiledCache.keys.toList().forEach { key ->
            if (key.startsWith(prefix)) {
                decompiledCache.remove(key)
            }
        }
    }
}
