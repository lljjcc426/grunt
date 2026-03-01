package net.spartanb312.grunteon.obfuscator.process.transformers

import net.spartanb312.grunteon.obfuscator.Grunteon
import net.spartanb312.grunteon.obfuscator.config.setting
import net.spartanb312.grunteon.obfuscator.process.Transformer
import net.spartanb312.grunteon.obfuscator.process.TransformerConfig
import net.spartanb312.grunteon.obfuscator.process.resource.JarResources
import net.spartanb312.grunteon.obfuscator.process.resource.WorkResources
import org.objectweb.asm.tree.ClassNode

class TestTransformer : Transformer<TestTransformer.Config>() {

    context(instance: Grunteon, res: WorkResources, jar: JarResources)
    override fun transformClass(
        classNode: ClassNode,
        config: Config,
    ) {
        config.boolean
        config.integer
        config.float
        config.list
        config.string
    }

    class Config : TransformerConfig() {
        val boolean by setting("Boolean", true)
        val integer by setting("Integer", 1)
        val float by setting("Float", 1f)
        val list by setting("List", listOf("a", "b", "c"))
        val string by setting("String", "string")
    }

}