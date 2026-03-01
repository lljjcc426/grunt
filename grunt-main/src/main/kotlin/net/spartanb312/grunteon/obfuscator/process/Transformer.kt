package net.spartanb312.grunteon.obfuscator.process

import net.spartanb312.grunteon.obfuscator.Grunteon
import net.spartanb312.grunteon.obfuscator.process.resource.JarResources
import net.spartanb312.grunteon.obfuscator.process.resource.WorkResources
import org.objectweb.asm.tree.ClassNode

abstract class Transformer<T : TransformerConfig> {

    context(instance: Grunteon, res: WorkResources, jar: JarResources)
    open fun transformClass(
        classNode: ClassNode,
        config: T,
    ) {

    }

    //fun readConfig(json: JsonObject): T {
//
    //}
//
    //fun saveConfig(config: T): JsonObject {
//
    //}

}