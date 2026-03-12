package com.beeper.mcp

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.beeper.mcp.tools.handleGetChats
import com.beeper.mcp.tools.handleGetContacts
import com.beeper.mcp.tools.handleGetMessages
import com.beeper.mcp.tools.handleSendMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

const val BEEPER_AUTHORITY = "com.beeper.api"

class McpServer(private val context: Context) {
    companion object {
        private const val TAG = "McpServer"
        private const val PORT = 8081
        private const val SERVICE_NAME = "beeper-mcp-server"
        private const val VERSION = "2.0.0"
    }

    private var ktorServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()

    data class SessionInfo(
        val id: String,
        val clientInfo: String,
        val startTime: Long = System.currentTimeMillis()
    )

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
            	// bind toute les interfaces
                ktorServer = embeddedServer(Netty, port = PORT) {
                // bind localhost uniquement pour securite
                //ktorServer = embeddedServer(Netty, port = PORT, host = "127.0.0.1") {
                    install(SSE)
                    install(CORS) {
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Put)
                        allowMethod(HttpMethod.Delete)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader("X-Request-Id")
                        allowHeader("X-Client-Name")
                        allowHeader("X-Client-Version")
                        anyHost()
                        allowCredentials = true
                    }

                    routing {

                        // ══════════════════════════════════════════════════════
                        // ENDPOINTS REST COMPATIBLES CONFLUENCE
                        // ══════════════════════════════════════════════════════

                        // GET /v1/info — infos compte (compatible Desktop API)
                        get("/v1/info") {
                            Log.d(TAG, "REST /v1/info")
                            val ip = getLocalIpAddress()
                            val info = buildJsonObject {
                                put("userID", "@beeper-android:beeper.com")
                                put("deviceID", "android-$ip")
                                put("version", VERSION)
                                put("platform", "android")
                            }
                            call.respondText(info.toString(), ContentType.Application.Json)
                        }

                        // GET /v1/chats — liste des conversations
                        get("/v1/chats") {
                            Log.d(TAG, "REST /v1/chats")
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                            val offset = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0
                            val protocol = call.request.queryParameters["protocol"]

                            try {
                                val paramBuilder = StringBuilder("limit=$limit&offset=$offset")
                                protocol?.let { paramBuilder.append("&protocol=${Uri.encode(it)}") }

                                val queryUri = "content://$BEEPER_AUTHORITY/chats?$paramBuilder".toUri()
                                val chatsArray = buildJsonArray {
                                    context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
                                        val roomIdIdx = cursor.getColumnIndex("roomId")
                                        val titleIdx = cursor.getColumnIndex("title")
                                        val previewIdx = cursor.getColumnIndex("messagePreview")
                                        val senderEntityIdIdx = cursor.getColumnIndex("senderEntityId")
                                        val protocolIdx = cursor.getColumnIndex("protocol")
                                        val unreadIdx = cursor.getColumnIndex("unreadCount")
                                        val timestampIdx = cursor.getColumnIndex("timestamp")
                                        val oneToOneIdx = cursor.getColumnIndex("oneToOne")
                                        val isMutedIdx = cursor.getColumnIndex("isMuted")

                                        while (cursor.moveToNext()) {
                                            val roomId = cursor.getString(roomIdIdx) ?: continue
                                            val proto = cursor.getString(protocolIdx) ?: "matrix"
                                            add(buildJsonObject {
                                                put("id", roomId)
                                                put("roomId", roomId)
                                                put("name", cursor.getString(titleIdx) ?: "")
                                                put("protocol", proto)
                                                // Normaliser protocol → netKey compatible Confluence
                                                put("network", normalizeProtocol(proto))
                                                put("unreadCount", cursor.getInt(unreadIdx))
                                                put("timestamp", cursor.getLong(timestampIdx))
                                                put("lastActivityTS", cursor.getLong(timestampIdx))
                                                put("isOneToOne", cursor.getInt(oneToOneIdx) == 1)
                                                put("isMuted", cursor.getInt(isMutedIdx) == 1)
                                                put("preview", cursor.getString(previewIdx) ?: "")
                                                put("senderEntityId", cursor.getString(senderEntityIdIdx) ?: "")
                                            })
                                        }
                                    }
                                }

                                val response = buildJsonObject {
                                    putJsonArray("items") { chatsArray.forEach { add(it) } }
                                    put("hasMore", chatsArray.size == limit)
                                }
                                call.respondText(response.toString(), ContentType.Application.Json)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error /v1/chats: ${e.message}")
                                call.respondText(
                                    """{"error":"${e.message}","items":[]}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError
                                )
                            }
                        }

                        // GET /v1/chats/{chatId}/messages — messages d'une conversation
                        get("/v1/chats/{chatId}/messages") {
                            val chatId = call.parameters["chatId"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                            val offset = call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0
                            Log.d(TAG, "REST /v1/chats/$chatId/messages limit=$limit offset=$offset")

                            try {
                                val encodedRoomId = Uri.encode(chatId)
                                val queryUri = "content://$BEEPER_AUTHORITY/messages?roomIds=$encodedRoomId&limit=$limit&offset=$offset".toUri()

                                val msgsArray = buildJsonArray {
                                    context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
                                        val originalIdIdx = cursor.getColumnIndex("originalId")
                                        val roomIdIdx = cursor.getColumnIndex("roomId")
                                        val senderContactIdIdx = cursor.getColumnIndex("senderContactId")
                                        val timestampIdx = cursor.getColumnIndex("timestamp")
                                        val isSentByMeIdx = cursor.getColumnIndex("isSentByMe")
                                        val isDeletedIdx = cursor.getColumnIndex("isDeleted")
                                        val typeIdx = cursor.getColumnIndex("type")
                                        val textContentIdx = cursor.getColumnIndex("text_content")
                                        val displayNameIdx = cursor.getColumnIndex("displayName")
                                        val reactionsIdx = cursor.getColumnIndex("reactions")

                                        while (cursor.moveToNext()) {
                                            val msgId = cursor.getString(originalIdIdx) ?: continue
                                            val ts = cursor.getLong(timestampIdx)
                                            val isSentByMe = cursor.getInt(isSentByMeIdx) == 1
                                            val text = cursor.getString(textContentIdx) ?: ""
                                            val displayName = cursor.getString(displayNameIdx) ?: ""
                                            val senderId = cursor.getString(senderContactIdIdx) ?: ""

                                            add(buildJsonObject {
                                                put("id", msgId)
                                                put("roomId", cursor.getString(roomIdIdx) ?: chatId)
                                                put("text", text)
                                                put("timestamp", ts.toString())
                                                put("sortKey", ts.toString().padStart(20, '0'))
                                                // Champs compatibles Desktop API
                                                put("isSender", isSentByMe)
                                                put("isFromMe", isSentByMe)
                                                put("isSentByMe", isSentByMe)
                                                put("senderID", senderId)
                                                put("senderName", displayName)
                                                put("type", cursor.getString(typeIdx) ?: "TEXT")
                                                put("isDeleted", cursor.getInt(isDeletedIdx) == 1)
                                                put("reactions", cursor.getString(reactionsIdx) ?: "")
                                            })
                                        }
                                    }
                                }

                                val response = buildJsonObject {
                                    putJsonArray("items") { msgsArray.forEach { add(it) } }
                                    put("hasMore", msgsArray.size == limit)
                                }
                                call.respondText(response.toString(), ContentType.Application.Json)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error /v1/chats/$chatId/messages: ${e.message}")
                                call.respondText(
                                    """{"error":"${e.message}","items":[]}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError
                                )
                            }
                        }

                        // POST /v1/chats/{chatId}/messages — envoyer un message
                        post("/v1/chats/{chatId}/messages") {
                            val chatId = call.parameters["chatId"] ?: ""
                            Log.d(TAG, "REST POST /v1/chats/$chatId/messages")

                            try {
                                val body = call.receiveText()
                                val json = Json.parseToJsonElement(body).jsonObject
                                val text = json["text"]?.jsonPrimitive?.content ?: ""

                                if (text.isEmpty()) {
                                    call.respondText(
                                        """{"error":"text is required"}""",
                                        ContentType.Application.Json,
                                        HttpStatusCode.BadRequest
                                    )
                                    return@post
                                }

                                val uriBuilder = "content://$BEEPER_AUTHORITY/messages".toUri().buildUpon()
                                uriBuilder.appendQueryParameter("roomId", chatId)
                                uriBuilder.appendQueryParameter("text", text)

                                val result = context.contentResolver.insert(uriBuilder.build(), android.content.ContentValues())

                                if (result != null) {
                                    val response = buildJsonObject {
                                        put("success", true)
                                        put("roomId", chatId)
                                        put("text", text)
                                    }
                                    call.respondText(response.toString(), ContentType.Application.Json)
                                } else {
                                    call.respondText(
                                        """{"error":"Failed to send message"}""",
                                        ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error POST /v1/chats/$chatId/messages: ${e.message}")
                                call.respondText(
                                    """{"error":"${e.message}"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError
                                )
                            }
                        }

                        // ══════════════════════════════════════════════════════
                        // ENDPOINTS ORIGINAUX MCP
                        // ══════════════════════════════════════════════════════

                        get("/health") {
                            val status = buildJsonObject {
                                put("status", "healthy")
                                put("service", SERVICE_NAME)
                                put("version", VERSION)
                                put("uptime", System.currentTimeMillis())
                                put("sessions", activeSessions.size)
                                put("ip", getLocalIpAddress())
                            }
                            call.respondText(status.toString(), ContentType.Application.Json)
                        }

                        get("/sessions") {
                            val sessions = buildJsonArray {
                                activeSessions.forEach { (id, info) ->
                                    add(buildJsonObject {
                                        put("id", id)
                                        put("client", info.clientInfo)
                                        put("duration", System.currentTimeMillis() - info.startTime)
                                    })
                                }
                            }
                            call.respondText(sessions.toString(), ContentType.Application.Json)
                        }

                        sse("/sse") {
                            handleMcpSession(this)
                        }

                        mcp("/mcp") {
                            createMcpServer()
                        }
                    }
                }.start(wait = false)

                val ipAddress = getLocalIpAddress()
                Log.i(TAG, "=== CONFLUENCE-COMPATIBLE SERVER STARTED ===")
                Log.i(TAG, "REST API: http://$ipAddress:$PORT/v1/chats")
                Log.i(TAG, "REST API: http://$ipAddress:$PORT/v1/info")
                Log.i(TAG, "Health: http://$ipAddress:$PORT/health")

            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}", e)
            }
        }
    }

    // Normaliser les protocols Beeper Android → netKeys Confluence
    private fun normalizeProtocol(proto: String): String {
        return when (proto.lowercase()) {
            "whatsapp" -> "whatsapp"
            "telegram" -> "telegram"
            "signal" -> "signal"
            "instagram", "instagram_e2ee" -> "instagram"
            "facebook", "messenger" -> "facebook"
            "imessage", "apple" -> "imessage"
            "sms", "gmessages", "android_sms" -> "sms"
            "discord" -> "discord"
            "slack" -> "slack"
            "twitter", "twitter_dm" -> "twitter"
            "linkedin" -> "linkedin"
            "googlevoice" -> "googlevoice"
            "googlechat" -> "googlechat"
            "matrix", "beeper", "hungryserv" -> "matrix"
            "bluesky" -> "bluesky"
            else -> proto.lowercase()
        }
    }

    private suspend fun handleMcpSession(session: ServerSSESession) {
        val sessionId = generateSessionId()
        activeSessions[sessionId] = SessionInfo(id = sessionId, clientInfo = "unknown")
        try {
            session.send("event: initialize\n")
            session.send("data: ${createInitializeResponse()}\n\n")
            while (true) {
                kotlinx.coroutines.delay(30000)
                session.send("event: ping\n")
                session.send("data: {\"type\":\"ping\",\"timestamp\":${System.currentTimeMillis()}}\n\n")
            }
        } finally {
            activeSessions.remove(sessionId)
        }
    }

    private fun createMcpServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = SERVICE_NAME, version = VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = true)
                )
            )
        )
        server.addTool("get_chats", "Retrieves chats/conversations") { request ->
            context.contentResolver.handleGetChats(request)
        }
        server.addTool("get_contacts", "Retrieves contacts") { request ->
            context.contentResolver.handleGetContacts(request)
        }
        server.addTool("send_message", "Send a text message") { request ->
            context.contentResolver.handleSendMessage(request)
        }
        server.addTool("get_messages", "Get messages from chats") { request ->
            context.contentResolver.handleGetMessages(request)
        }
        server.addResource(
            uri = "beeper://chats",
            name = "Chat List",
            description = "List of all Beeper chats",
            mimeType = "application/json"
        ) { request ->
            ReadResourceResult(contents = listOf(TextResourceContents(
                uri = request.uri, text = getChatListResource(), mimeType = "application/json"
            )))
        }
        server.addPrompt(
            name = "summarize_chats",
            description = "Generate a summary of recent chat activity",
            arguments = listOf(PromptArgument(name = "time_range", description = "Time range e.g. 24h", required = false))
        ) { request ->
            GetPromptResult(
                description = "Summary",
                messages = listOf(
                    PromptMessage(
                        role = Role.user,
                        content = TextContent(
                            text = "Summarize chat activity for ${request.arguments?.get("time_range") ?: "24h"}"
                        )
                    )
                )
            )
        }
        return server
    }

    private fun createInitializeResponse(): String {
        return buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("serverInfo", buildJsonObject {
                put("name", SERVICE_NAME)
                put("version", VERSION)
            })
        }.toString()
    }

    private fun getChatListResource(): String {
        return try {
            val uri = "content://$BEEPER_AUTHORITY/chats".toUri()
            val chats = buildJsonArray {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val roomIdIdx = cursor.getColumnIndex("roomId")
                    val titleIdx = cursor.getColumnIndex("title")
                    val unreadIdx = cursor.getColumnIndex("unreadCount")
                    val timestampIdx = cursor.getColumnIndex("timestamp")
                    while (cursor.moveToNext()) {
                        add(buildJsonObject {
                            put("roomId", cursor.getString(roomIdIdx) ?: "")
                            put("title", cursor.getString(titleIdx) ?: "")
                            put("unreadCount", cursor.getInt(unreadIdx))
                            put("timestamp", cursor.getLong(timestampIdx))
                        })
                    }
                }
            }
            chats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat list resource", e)
            "[]"
        }
    }

    private fun generateSessionId() = "session_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                            return address.hostAddress ?: "unknown"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "127.0.0.1"
    }

    fun stop() {
        activeSessions.clear()
        ktorServer?.stop()
        Log.i(TAG, "Server stopped")
    }
}
