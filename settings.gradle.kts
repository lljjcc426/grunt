rootProject.name = "Grunteon"

// Launch bootstrap
include(":grunt-bootstrap")

// Components
include(":grunt-main")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

includeBuild("../Genesis") {
    dependencySubstitution {
        substitute(module("net.spartanb312:genesis-kotlin")).using(project(":genesis-kotlin"))
    }
}
