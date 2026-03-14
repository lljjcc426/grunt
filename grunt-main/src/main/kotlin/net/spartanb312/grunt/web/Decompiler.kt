package net.spartanb312.grunt.web

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.ClassFileSource
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair

/**
 * CFR decompiler wrapper - converts class bytecode to Java source code
 */
object Decompiler {

    /**
     * Decompile a single class from its bytecode
     * @param className the internal name of the class (e.g. "com/example/MyClass")
     * @param classBytes the raw class file bytes
     * @param allClasses optional map of all available classes for resolving references
     * @return decompiled Java source code as a String
     */
    fun decompile(
        className: String,
        classBytes: ByteArray,
        allClasses: Map<String, ByteArray> = emptyMap()
    ): String {
        val result = StringBuilder()

        val sinkFactory = object : OutputSinkFactory {
            override fun getSupportedSinks(
                sinkType: OutputSinkFactory.SinkType,
                collection: Collection<OutputSinkFactory.SinkClass>
            ): List<OutputSinkFactory.SinkClass> {
                return listOf(OutputSinkFactory.SinkClass.STRING)
            }

            override fun <T> getSink(
                sinkType: OutputSinkFactory.SinkType,
                sinkClass: OutputSinkFactory.SinkClass
            ): OutputSinkFactory.Sink<T> {
                @Suppress("UNCHECKED_CAST")
                return when (sinkType) {
                    OutputSinkFactory.SinkType.JAVA -> OutputSinkFactory.Sink<T> { content ->
                        result.append(content.toString())
                    }
                    OutputSinkFactory.SinkType.EXCEPTION -> OutputSinkFactory.Sink<T> { content ->
                        result.append("// Decompilation error: $content\n")
                    }
                    else -> OutputSinkFactory.Sink<T> { }
                }
            }
        }

        val classFileSource = object : ClassFileSource {
            override fun informAnalysisRelativePathDetail(usePath: String?, classFilePath: String?) {}

            override fun getPossiblyRenamedPath(path: String): String = path

            override fun addJar(jarPath: String): Collection<String> = emptyList()

            override fun getClassFileContent(path: String): Pair<ByteArray, String> {
                val lookupName = path.removeSuffix(".class")
                val bytes = if (lookupName == className) {
                    classBytes
                } else {
                    allClasses[lookupName] ?: allClasses[lookupName.replace("/", ".")] ?: run {
                        // Try loading from runtime
                        try {
                            val stream = ClassLoader.getSystemResourceAsStream("$lookupName.class")
                            stream?.readBytes()
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                return Pair(bytes ?: ByteArray(0), lookupName)
            }
        }

        try {
            val options = mapOf(
                "showversion" to "false",
                "hideutf" to "false",
                "decodestringswitch" to "false",
                "sugarenums" to "true",
                "decodelambdas" to "true",
                "removeboilerplate" to "true",
                "removeinnerclasssynthetics" to "true",
                "aexagg" to "false",
                "recovertypeclash" to "true",
                "recovertypehints" to "true",
                "forcereturningifs" to "true",
                "silent" to "true"
            )

            val driver = CfrDriver.Builder()
                .withClassFileSource(classFileSource)
                .withOutputSink(sinkFactory)
                .withOptions(options)
                .build()

            driver.analyse(listOf("$className.class"))
        } catch (e: Exception) {
            result.clear()
            result.append("// Failed to decompile: ${e.message}\n")
            result.append("// Class: ${className.replace("/", ".")}\n")
        }

        return result.toString()
    }
}
