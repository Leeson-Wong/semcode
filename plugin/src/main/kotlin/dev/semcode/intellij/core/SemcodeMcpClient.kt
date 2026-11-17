package dev.semcode.intellij.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 最小 MCP stdio JSON-RPC 客户端：常驻 semcode-mcp 子进程，initialize 握手后
 * tools/call。进程死亡后下次调用自动重启。结果以 MCP content text 返回，
 * 调用方自行决定按纯文本还是 JSON 文档解析。
 */
class SemcodeMcpClient(
    private val command: List<String>,
    private val workDir: File,
    private val env: Map<String, String> = emptyMap(),
) {
    private val log = Logger.getInstance(SemcodeMcpClient::class.java)
    private val gson = Gson()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    @Synchronized
    private fun ensureStarted(): Boolean {
        if (process?.isAlive == true) return true
        teardown()

        val proc = try {
            ProcessBuilder(command)
                .directory(workDir)
                .apply { environment().putAll(env) }
                .start()
        } catch (e: Exception) {
            log.warn("failed to start semcode-mcp: ${command.firstOrNull()}", e)
            return false
        }

        val w = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
        Thread({ drain(proc) }, "semcode-mcp-reader").apply { isDaemon = true }.start()

        process = proc
        writer = w

        val init = request(
            "initialize",
            gson.toJsonTree(
                mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "semcode-intellij", "version" to "0.1.0"),
                ),
            ),
        )
        if (init == null) return false
        notify("notifications/initialized")
        return true
    }

    private fun drain(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val obj = try {
                        JsonParser.parseString(line).asJsonObject
                    } catch (_: Exception) {
                        continue
                    }
                    val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
                    pending.remove(id)?.complete(obj)
                }
            }
        } finally {
            // 进程结束：让所有在途请求失败，下次调用触发重启
            pending.values.forEach { it.completeExceptionally(IllegalStateException("semcode-mcp exited")) }
            pending.clear()
        }
    }

    /** tools/call，返回 content[0].text；失败返回 null。 */
    fun callTool(name: String, args: Map<String, Any?> = emptyMap()): String? {
        if (!ensureStarted()) return null
        val result = request(
            "tools/call",
            gson.toJsonTree(mapOf("name" to name, "arguments" to args)),
        ) ?: return null
        if (result.has("error")) {
            log.warn("tools/call $name failed: ${result["error"]}")
            return null
        }
        val text = result
            .getAsJsonObject("result")["content"]
            ?.asJsonArray
            ?.firstOrNull()
            ?.asJsonObject
            ?.get("text")
        return text?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** tools/call 且把 content text 当 JSON 文档解析。 */
    fun callToolJson(name: String, args: Map<String, Any?> = emptyMap()): JsonObject? {
        val text = callTool(name, args) ?: return null
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun request(method: String, params: Any?): JsonObject? {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val payload = gson.toJson(
            mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params),
        )
        try {
            val w = writer ?: return null.also { pending.remove(id) }
            synchronized(w) {
                w.write(payload)
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            log.warn("failed to send $method", e)
            return null
        }
        return try {
            future.get()
        } catch (e: Exception) {
            log.warn("no response for $method: ${e.message}")
            null
        }
    }

    private fun notify(method: String) {
        val payload = gson.toJson(mapOf("jsonrpc" to "2.0", "method" to method))
        try {
            val w = writer ?: return
            synchronized(w) {
                w.write(payload)
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            log.warn("failed to send notification $method", e)
        }
    }

    @Synchronized
    fun teardown() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("client torn down")) }
        pending.clear()
        writer?.let { try { it.close() } catch (_: Exception) {} }
        writer = null
        process?.let { try { it.destroyForcibly() } catch (_: Exception) {} }
        process = null
    }
}
