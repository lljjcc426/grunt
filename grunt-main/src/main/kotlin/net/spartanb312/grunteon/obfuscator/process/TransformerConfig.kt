package net.spartanb312.grunteon.obfuscator.process

import net.spartanb312.grunteon.obfuscator.config.AbstractValue
import net.spartanb312.grunteon.obfuscator.config.Configurable

abstract class TransformerConfig : Configurable {

    override val vals = mutableListOf<AbstractValue<*>>()

}