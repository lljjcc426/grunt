package net.spartanb312.grunteon.obfuscator

import net.spartanb312.everett.bootstrap.Main

// for dev mode only
fun main() = Main.launch(listOf("-DevMode"), "net.spartanb312.grunteon.obfuscator.ApplicationEntry")

object ApplicationEntry {

    init {
        println("Application entry")
    }

}