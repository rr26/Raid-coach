package com.raidcoach.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class AnthropicError(message: String) : Exception(message) {
    class InvalidApiKey : AnthropicError("Invalid API key — check it in Settings.")
    class RateLimited : AnthropicError("Rate limited by Anthropic. Try again in a moment.")
    class ApiError(code: Int, detail: String) : AnthropicError("Anthropic API error ($code): $detail")
    class NetworkError(cause: Throwable) :
        AnthropicError(cause.message?.let { "Network error: $it" } ?: "Network error — check your connection.")
}

object AnthropicClient {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-4-6"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 1024

    suspend fun sendMessage(
        apiKey: String,
        systemPrompt: String,
        history: List<ApiMessage>,
        webSearchEnabled: Boolean
    ): Result<AnthropicReply> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("x-api-key", apiKey)
                    connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                    connection.setRequestProperty("content-type", "application/json")

                    val body = buildRequestBody(systemPrompt, history, webSearchEnabled)
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                    val responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val responseText = stream.bufferedReader().use { it.readText() }

                    if (responseCode !in 200..299) {
                        throw errorFor(responseCode, responseText)
                    }

                    parseReply(responseText)
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                throw AnthropicError.NetworkError(e)
            }
        }
    }

    private fun errorFor(code: Int, body: String): AnthropicError {
        val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: body.take(200)

        return when (code) {
            401 -> AnthropicError.InvalidApiKey()
            429 -> AnthropicError.RateLimited()
            else -> AnthropicError.ApiError(code, detail)
        }
    }

    private fun buildRequestBody(
        systemPrompt: String,
        history: List<ApiMessage>,
        webSearchEnabled: Boolean
    ): JSONObject {
        val messagesArray = JSONArray()
        for (message in history) {
            val contentArray = JSONArray()
            for (block in message.blocks) {
                val blockJson = JSONObject()
                when (block.type) {
                    "text" -> {
                        blockJson.put("type", "text")
                        blockJson.put("text", block.text)
                    }

                    "image" -> {
                        blockJson.put("type", "image")
                        blockJson.put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", block.mediaType)
                                .put("data", block.imageBase64)
                        )
                    }
                }
                contentArray.put(blockJson)
            }

            messagesArray.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", contentArray)
            )
        }

        val requestBody = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", systemPrompt)
            .put("messages", messagesArray)

        if (webSearchEnabled) {
            requestBody.put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                )
            )
        }

        return requestBody
    }

    // The web_search tool is server-executed: a single response can mix text blocks with
    // server_tool_use/web_search_tool_result blocks. Only "text" blocks are shown to the user;
    // any other block type just flags that a search happened.
    private fun parseReply(responseText: String): AnthropicReply {
        val content = JSONObject(responseText).getJSONArray("content")
        val textBuilder = StringBuilder()
        var usedWebSearch = false

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                textBuilder.append(block.optString("text"))
            } else {
                usedWebSearch = true
            }
        }

        return AnthropicReply(textBuilder.toString().trim(), usedWebSearch)
    }
}
