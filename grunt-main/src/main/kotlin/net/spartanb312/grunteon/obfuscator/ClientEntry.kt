package net.spartanb312.grunteon.obfuscator

import net.spartanb312.everett.bootstrap.Main

// for dev mode only
fun main() = Main.main(arrayOf())

object ClientEntry {

    init {
        println("Client entry")
    }

}

object ServerEntry {

    init {
        println("Server entry")
    }

}