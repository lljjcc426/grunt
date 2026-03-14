package net.spartanb312.grunt.web

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import net.spartanb312.grunt.config.Configs
import net.spartanb312.grunt.gui.util.LoggerPrinter
import net.spartanb312.grunt.process.Transformers
import net.spartanb312.grunt.process.transformers.PostProcessTransformer
import net.spartanb312.grunt.utils.logging.Logger
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.URI
import java.time.Duration
import java.util.Collections
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

object WebServer {

    private val gson = Gson()
    private var session = ObfuscationSession()
    private var defaultConfigPath: String = File("config.json").absolutePath
    private val wsConsoleSessions = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())
    private val wsProgressSessions = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())
    private val classNamePattern = Regex("^[A-Za-z0-9_/$.\\-]+$")

    fun start(port: Int = 8080, configPath: String = "config.json") {
        defaultConfigPath = File(configPath).absolutePath

        // Capture stdout so Logger.info() messages get relayed to WebSocket console
        val originalOut = System.out
        val capturePrinter = LoggerPrinter(originalOut) { line ->
            if (line.isNotBlank()) {
                // Always reference the current session (it may be replaced on new upload)
                session.consoleLogs.add(line)
                // Broadcast to all connected WebSocket console clients
                val escaped = line.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val jsonMsg = """{"type":"log","message":"$escaped"}"""
                synchronized(wsConsoleSessions) {
                    wsConsoleSessions.forEach { ws ->
                        try {
                            kotlinx.coroutines.runBlocking {
                                ws.send(Frame.Text(jsonMsg))
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        System.setOut(capturePrinter)

        Logger.info("Starting web server on port $port...")

        val server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
            }
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(30)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }

            routing {
                // Serve static web files
                staticResources()

                // API routes
                apiRoutes()

                // WebSocket routes
                wsRoutes()
            }
        }

        server.start(wait = false)
        Logger.info("Web server started at http://localhost:$port/login")

        // Try to open browser
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI("http://localhost:$port/login"))
                Logger.info("Browser opened automatically")
            }
        } catch (e: Exception) {
            Logger.info("Please open http://localhost:$port/login in your browser")
        }

        // Keep alive
        Thread.currentThread().join()
    }

    private fun Routing.staticResources() {
        suspend fun ApplicationCall.respondHtml(name: String) {
            val resource = this::class.java.classLoader.getResource("web/$name")
            if (resource != null) {
                respondText(resource.readText(), ContentType.Text.Html)
            } else {
                respondText("Web UI not found", status = HttpStatusCode.NotFound)
            }
        }

        get("/") { call.respondHtml("login.html") }
        get("/index.html") { call.respondHtml("index.html") }
        get("/login") { call.respondHtml("login.html") }
        get("/login.html") { call.respondHtml("login.html") }

        get("/css/{file}") {
            val fileName = call.parameters["file"] ?: return@get
            val resource = this::class.java.classLoader.getResource("web/css/$fileName")
            if (resource != null) {
                call.respondText(resource.readText(), ContentType.Text.CSS)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get("/js/{file}") {
            val fileName = call.parameters["file"] ?: return@get
            val resource = this::class.java.classLoader.getResource("web/js/$fileName")
            if (resource != null) {
                call.respondText(resource.readText(), ContentType.Application.JavaScript)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get("/fonts/{file...}") {
            val pathParts = call.parameters.getAll("file")
            if (pathParts.isNullOrEmpty()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            if (pathParts.any { it.contains("..") || it.contains("\\") || it.contains(":") }) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val filePath = pathParts.joinToString("/")
            val resource = this::class.java.classLoader.getResource("web/fonts/$filePath")
            if (resource != null) {
                val contentType = when {
                    filePath.endsWith(".woff2", ignoreCase = true) -> ContentType.parse("font/woff2")
                    filePath.endsWith(".woff", ignoreCase = true) -> ContentType.parse("font/woff")
                    else -> ContentType.Application.OctetStream
                }
                call.respondBytes(resource.readBytes(), contentType)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    private fun Routing.apiRoutes() {
        route("/api") {
            // GET /api/transformers - list all transformers
            get("/transformers") {
                val result = JsonArray()
                Transformers.filter { it != PostProcessTransformer }.forEach { transformer ->
                    val obj = JsonObject()
                    obj.addProperty("name", transformer.name)
                    obj.addProperty("category", transformer.category.name)
                    obj.addProperty("enabled", transformer.enabled)
                    obj.addProperty("order", transformer.order)

                    val settings = JsonArray()
                    transformer.getValues().forEach { value ->
                        val setting = JsonObject()
                        setting.addProperty("name", value.name)
                        when (value.value) {
                            is Boolean -> {
                                setting.addProperty("type", "boolean")
                                setting.addProperty("value", value.value as Boolean)
                            }
                            is Int -> {
                                setting.addProperty("type", "int")
                                setting.addProperty("value", value.value as Int)
                            }
                            is Float -> {
                                setting.addProperty("type", "float")
                                setting.addProperty("value", value.value as Float)
                            }
                            is String -> {
                                setting.addProperty("type", "string")
                                setting.addProperty("value", value.value as String)
                            }
                            is List<*> -> {
                                setting.addProperty("type", "list")
                                setting.add("value", JsonArray().apply {
                                    (value.value as List<*>).forEach { add(it?.toString()) }
                                })
                            }
                        }
                        settings.add(setting)
                    }
                    obj.add("settings", settings)
                    result.add(obj)
                }
                call.respondText(gson.toJson(result), ContentType.Application.Json)
            }

            // GET /api/config - get current config
            get("/config") {
                val result = JsonObject()
                val settings = JsonObject()
                settings.addProperty("input", Configs.Settings.input)
                settings.addProperty("output", Configs.Settings.output)
                settings.add("libraries", JsonArray().apply {
                    Configs.Settings.libraries.forEach { add(it) }
                })
                settings.add("exclusions", JsonArray().apply {
                    Configs.Settings.exclusions.forEach { add(it) }
                })
                settings.addProperty("parallel", Configs.Settings.parallel)
                settings.addProperty("generateRemap", Configs.Settings.generateRemap)
                settings.addProperty("corruptOutput", Configs.Settings.corruptOutput)
                settings.addProperty("timeUsage", Configs.Settings.timeUsage)
                result.add("settings", settings)
                call.respondText(gson.toJson(result), ContentType.Application.Json)
            }

            // POST /api/config - update config
            post("/config") {
                try {
                    val body = call.receiveText()
                    val json = JsonParser.parseString(body).asJsonObject

                    // Update settings
                    json["input"]?.asString?.let { Configs.Settings.input = it }
                    json["output"]?.asString?.let { Configs.Settings.output = it }
                    json["parallel"]?.asBoolean?.let { Configs.Settings.parallel = it }
                    json["generateRemap"]?.asBoolean?.let { Configs.Settings.generateRemap = it }
                    json["corruptOutput"]?.asBoolean?.let { Configs.Settings.corruptOutput = it }
                    json["libraries"]?.asJsonArray?.let { arr ->
                        Configs.Settings.libraries = arr.map { it.asString }
                    }
                    json["exclusions"]?.asJsonArray?.let { arr ->
                        Configs.Settings.exclusions = arr.map { it.asString }
                    }

                    // Update transformer settings
                    json["transformers"]?.asJsonObject?.let { transformersObj ->
                        transformersObj.entrySet().forEach { (name, value) ->
                            val transformer = Transformers.find { it.name == name } ?: return@forEach
                            val settings = value.asJsonObject
                            transformer.getValues().forEach { abstractValue ->
                                settings[abstractValue.name]?.let { newVal ->
                                    @Suppress("UNCHECKED_CAST")
                                    when (abstractValue.value) {
                                        is Boolean -> (abstractValue as? net.spartanb312.grunt.config.AbstractValue<Boolean>)?.value = newVal.asBoolean
                                        is Int -> (abstractValue as? net.spartanb312.grunt.config.AbstractValue<Int>)?.value = newVal.asInt
                                        is Float -> (abstractValue as? net.spartanb312.grunt.config.AbstractValue<Float>)?.value = newVal.asFloat
                                        is String -> (abstractValue as? net.spartanb312.grunt.config.AbstractValue<String>)?.value = newVal.asString
                                        is List<*> -> (abstractValue as? net.spartanb312.grunt.config.AbstractValue<List<String>>)?.value =
                                            newVal.asJsonArray.map { it.asString }
                                    }
                                }
                            }
                        }
                    }

                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                } catch (e: Exception) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", e.message ?: "Unknown error")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            }

            // POST /api/config/save - save config to file
            post("/config/save") {
                try {
                    val body = call.receiveText()
                    val json = JsonParser.parseString(body).asJsonObject
                    val path = json["path"]?.asString ?: "config.json"
                    Configs.saveConfig(path)
                    call.respondText("""{"status":"ok","path":"$path"}""", ContentType.Application.Json)
                } catch (e: Exception) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", e.message ?: "Unknown error")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }

            // POST /api/config/load - load config from file
            post("/config/load") {
                try {
                    val body = call.receiveText()
                    val json = JsonParser.parseString(body).asJsonObject
                    val path = json["path"]?.asString ?: "config.json"
                    Configs.resetConfig()
                    Configs.loadConfig(path)
                    call.respondText("""{"status":"ok","path":"$path"}""", ContentType.Application.Json)
                } catch (e: Exception) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", e.message ?: "Unknown error")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }

            // POST /api/config/pick-load - pick external config and overwrite default config file
            post("/config/pick-load") {
                try {
                    if (GraphicsEnvironment.isHeadless()) {
                        call.respondText(
                            """{"status":"error","message":"File picker is not available in headless mode"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@post
                    }

                    val defaultFile = File(defaultConfigPath).absoluteFile
                    val selectedFile = pickConfigJsonFile(defaultFile)
                    if (selectedFile == null) {
                        call.respondText("""{"status":"cancelled"}""", ContentType.Application.Json)
                        return@post
                    }

                    if (!selectedFile.exists() || !selectedFile.isFile || !selectedFile.name.endsWith(".json", ignoreCase = true)) {
                        call.respondText(
                            """{"status":"error","message":"Selected file must be a valid .json file"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@post
                    }

                    Configs.resetConfig()
                    Configs.loadConfig(selectedFile.absolutePath)
                    Configs.saveConfig(defaultFile.absolutePath)

                    val result = JsonObject().apply {
                        addProperty("status", "ok")
                        addProperty("sourcePath", selectedFile.absolutePath)
                        addProperty("targetPath", defaultFile.absolutePath)
                    }
                    call.respondText(gson.toJson(result), ContentType.Application.Json)
                } catch (e: Exception) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", e.message ?: "Unknown error")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }

            // POST /api/config/reset - reset config
            post("/config/reset") {
                Configs.resetConfig()
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            }

            // POST /api/upload - upload input JAR
            post("/upload") {
                try {
                    val multipart = call.receiveMultipart()
                    var fileName = ""
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            fileName = part.originalFileName ?: "input.jar"
                            val uploadDir = File("uploads")
                            uploadDir.mkdirs()
                            val file = File(uploadDir, fileName)
                            part.streamProvider().use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            session = ObfuscationSession()
                            session.inputJarPath = file.absolutePath
                            session.status = ObfuscationSession.Status.READY
                            Configs.Settings.input = file.absolutePath
                        }
                        part.dispose()
                    }

                    // Read class list from the uploaded JAR
                    val classList = mutableListOf<String>()
                    try {
                        val jarFile = java.util.jar.JarFile(File(session.inputJarPath!!))
                        jarFile.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .forEach { classList.add(it.name.removeSuffix(".class")) }
                        jarFile.close()
                        session.setInputClasses(classList)
                    } catch (e: Exception) {
                        Logger.error("Failed to read JAR: ${e.message}")
                    }

                    val result = JsonObject()
                    result.addProperty("status", "ok")
                    result.addProperty("fileName", fileName)
                    result.addProperty("classCount", classList.size)
                    result.add("classes", JsonArray().apply { classList.sorted().forEach { add(it) } })
                    call.respondText(gson.toJson(result), ContentType.Application.Json)
                } catch (e: Exception) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", e.message ?: "Unknown error")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }

            // POST /api/obfuscate - start obfuscation
            post("/obfuscate") {
                if (session.status == ObfuscationSession.Status.RUNNING) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", "Obfuscation already running")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.Conflict)
                    return@post
                }

                if (session.inputJarPath == null) {
                    val err = JsonObject()
                    err.addProperty("status", "error")
                    err.addProperty("message", "No JAR uploaded")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }

                // Setup WebSocket broadcasting
                session.onLogMessage = { msg ->
                    val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    val jsonMsg = """{"type":"log","message":"$escaped"}"""
                    synchronized(wsConsoleSessions) {
                        wsConsoleSessions.forEach { ws ->
                            try {
                                kotlinx.coroutines.runBlocking {
                                    ws.send(Frame.Text(jsonMsg))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                session.onProgressUpdate = { progressJson ->
                    synchronized(wsProgressSessions) {
                        wsProgressSessions.forEach { ws ->
                            try {
                                kotlinx.coroutines.runBlocking {
                                    ws.send(Frame.Text(progressJson))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Run in background thread
                thread(name = "obfuscation", priority = Thread.MAX_PRIORITY) {
                    session.runObfuscation()
                }

                call.respondText("""{"status":"started"}""", ContentType.Application.Json)
            }

            // GET /api/status - get current obfuscation status
            get("/status") {
                val result = JsonObject()
                result.addProperty("status", session.status.name)
                result.addProperty("currentStep", session.currentStep)
                result.addProperty("progress", session.progress)
                result.addProperty("totalSteps", session.totalSteps)
                session.errorMessage?.let { result.addProperty("error", it) }
                call.respondText(gson.toJson(result), ContentType.Application.Json)
            }

            // GET /api/structure - get final obfuscated class/package structure
            get("/structure") {
                val classList = session.getFinalStructure()
                if (classList == null) {
                    val err = JsonObject()
                    err.addProperty("error", "No obfuscation result available")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }
                val result = JsonObject()
                result.addProperty("status", "ok")
                result.addProperty("classCount", classList.size)
                result.add("classes", JsonArray().apply { classList.forEach { add(it) } })
                call.respondText(gson.toJson(result), ContentType.Application.Json)
            }

            // GET /api/project/meta?scope=input|output
            get("/project/meta") {
                val scope = parseProjectScope(call.parameters["scope"])
                if (scope == null) {
                    call.respondText(
                        """{"status":"error","message":"Invalid scope"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val classes = session.getProjectClasses(scope)
                val result = JsonObject().apply {
                    addProperty("status", "ok")
                    addProperty("scope", scope.name.lowercase())
                    addProperty("available", classes != null)
                    addProperty("classCount", classes?.size ?: 0)
                }
                call.respondText(gson.toJson(result), ContentType.Application.Json)
            }

            // GET /api/project/tree?scope=input|output
            get("/project/tree") {
                val scope = parseProjectScope(call.parameters["scope"])
                if (scope == null) {
                    call.respondText(
                        """{"status":"error","message":"Invalid scope"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val classList = session.getProjectClasses(scope)
                if (classList == null) {
                    val err = JsonObject().apply {
                        addProperty("status", "error")
                        addProperty("scope", scope.name.lowercase())
                        addProperty("message", "No ${scope.name.lowercase()} class structure available")
                    }
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }

                val result = JsonObject().apply {
                    addProperty("status", "ok")
                    addProperty("scope", scope.name.lowercase())
                    addProperty("classCount", classList.size)
                    add("classes", JsonArray().apply { classList.forEach { add(it) } })
                }
                call.respondText(gson.toJson(result), ContentType.Application.Json)
            }

            // GET /api/project/source?scope=input|output&class=<internalName>
            get("/project/source") {
                val scope = parseProjectScope(call.parameters["scope"])
                if (scope == null) {
                    call.respondText(
                        """{"status":"error","message":"Invalid scope"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val className = call.parameters["class"]?.trim().orEmpty()
                if (!isValidClassName(className)) {
                    call.respondText(
                        """{"status":"error","message":"Invalid class name"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@get
                }

                try {
                    val code = session.decompileClass(scope, className)
                    val result = JsonObject().apply {
                        addProperty("status", "ok")
                        addProperty("scope", scope.name.lowercase())
                        addProperty("class", className)
                        addProperty("language", "java")
                        addProperty("code", code)
                    }
                    call.respondText(gson.toJson(result), ContentType.Application.Json)
                } catch (_: NoSuchElementException) {
                    val err = JsonObject().apply {
                        addProperty("status", "error")
                        addProperty("message", "Class not found")
                        addProperty("class", className)
                    }
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.NotFound)
                } catch (_: IllegalStateException) {
                    val err = JsonObject().apply {
                        addProperty("status", "error")
                        addProperty("message", "No ${scope.name.lowercase()} JAR available")
                        addProperty("class", className)
                    }
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.NotFound)
                } catch (_: IllegalArgumentException) {
                    val err = JsonObject().apply {
                        addProperty("status", "error")
                        addProperty("message", "Invalid class name")
                        addProperty("class", className)
                    }
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.BadRequest)
                } catch (e: Exception) {
                    val err = JsonObject().apply {
                        addProperty("status", "error")
                        addProperty("message", e.message ?: "Failed to decompile class")
                        addProperty("class", className)
                    }
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }

            // GET /api/download - download output JAR
            get("/download") {
                val outputPath = session.outputJarPath
                if (outputPath == null || !File(outputPath).exists()) {
                    val err = JsonObject()
                    err.addProperty("error", "No output file available")
                    call.respondText(gson.toJson(err), ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }
                val file = File(outputPath)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        file.name
                    ).toString()
                )
                call.respondFile(file)
            }

            // GET /api/logs - get console logs
            get("/logs") {
                call.respondText(gson.toJson(session.consoleLogs), ContentType.Application.Json)
            }
        }
    }

    private fun Routing.wsRoutes() {
        // WebSocket /ws/console - real-time console log
        webSocket("/ws/console") {
            wsConsoleSessions.add(this)
            try {
                // Send existing logs
                session.consoleLogs.forEach { log ->
                    val escaped = log.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    send(Frame.Text("""{"type":"log","message":"$escaped"}"""))
                }
                // Keep connection alive
                for (frame in incoming) {
                    // Client can send ping/pong or close
                }
            } finally {
                wsConsoleSessions.remove(this)
            }
        }

        // WebSocket /ws/progress - real-time progress
        webSocket("/ws/progress") {
            wsProgressSessions.add(this)
            try {
                // Send current status
                val statusJson = JsonObject().apply {
                    addProperty("step", session.currentStep)
                    addProperty("progress", session.progress)
                    addProperty("status", session.status.name)
                }
                send(Frame.Text(gson.toJson(statusJson)))
                // Keep connection alive
                for (frame in incoming) {
                    // Client can send ping/pong or close
                }
            } finally {
                wsProgressSessions.remove(this)
            }
        }
    }

    private fun parseProjectScope(rawScope: String?): ObfuscationSession.ProjectScope? {
        return when (rawScope?.lowercase()) {
            "input" -> ObfuscationSession.ProjectScope.INPUT
            "output" -> ObfuscationSession.ProjectScope.OUTPUT
            else -> null
        }
    }

    private fun pickConfigJsonFile(defaultFile: File): File? {
        val startDir = defaultFile.parentFile?.takeIf { it.exists() } ?: File(".").absoluteFile
        var selected: File? = null

        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser(startDir).apply {
                dialogTitle = "Select Config JSON"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                fileFilter = FileNameExtensionFilter("Json File", "json")
                selectedFile = defaultFile
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.let {
                    selected = it.absoluteFile
                }
            }
        }

        return selected
    }

    private fun isValidClassName(className: String): Boolean {
        if (className.isBlank()) return false
        if (className.contains("..")) return false
        if (className.startsWith("/") || className.startsWith("\\")) return false
        if (className.contains(":")) return false
        return classNamePattern.matches(className)
    }
}
