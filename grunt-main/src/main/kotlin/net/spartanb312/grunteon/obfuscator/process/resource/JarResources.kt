package net.spartanb312.grunteon.obfuscator.process.resource

import org.objectweb.asm.tree.ClassNode

class JarResources(val jar: String) {

    val classes = mutableMapOf<String, ClassNode>()
    val resources = mutableMapOf<String, ByteArray>()
    val generatedClasses = mutableMapOf<String, ClassNode>()

}