/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import dev.patrickgold.florisboard.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object OpenAiSuggestionClient {
    private const val ResponsesUrl = "https://api.openai.com/v1/responses"
    private const val ConnectTimeoutMs = 15_000
    private const val ReadTimeoutMs = 45_000
    private const val MaxOutputTokens = 1_000

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    suspend fun generateDefaultReply(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = BuildConfig.OPENAI_API_KEY.trim()
            require(apiKey.isNotEmpty()) {
                "OPENAI_API_KEY не задан. Добавь openAiApiKey в Gradle properties или переменную окружения."
            }

            val connection = (URL(ResponsesUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = ConnectTimeoutMs
                readTimeout = ReadTimeoutMs
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }

            val requestBody = buildJsonObject {
                put("model", BuildConfig.OPENAI_MODEL)
                put("input", prompt)
                put("store", false)
                put("max_output_tokens", MaxOutputTokens)
            }.toString()

            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseBody = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(parseOpenAiError(errorBody) ?: "OpenAI error ${connection.responseCode}")
            }

            parseOutputText(responseBody)
        }
    }

    private fun parseOutputText(responseBody: String): String {
        val root = json.parseToJsonElement(responseBody).jsonObject
        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { outputText ->
            if (outputText.isNotBlank()) return outputText.trim()
        }

        return root["output"]
            ?.jsonArray
            ?.flatMap { outputItem ->
                outputItem.jsonObject["content"]?.jsonArray.orEmpty()
            }
            ?.mapNotNull { contentItem ->
                val contentObject = contentItem.jsonObject
                contentObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: contentObject["output_text"]?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString(separator = "\n")
            ?.trim()
            .orEmpty()
            .ifBlank {
                throw IllegalStateException(parseEmptyResponseReason(root))
            }
    }

    private fun parseEmptyResponseReason(root: JsonObject): String {
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        val incompleteReason = (root["incomplete_details"] as? JsonObject)
            ?.get("reason")
            ?.jsonPrimitive
            ?.contentOrNull
        val responseError = (root["error"] as? JsonObject)
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull

        return when {
            responseError != null -> responseError
            status == "incomplete" && incompleteReason == "max_output_tokens" -> {
                "OpenAI не успел сгенерировать текст: достигнут лимит max_output_tokens=$MaxOutputTokens"
            }
            status == "incomplete" && incompleteReason != null -> {
                "OpenAI вернул неполный ответ: $incompleteReason"
            }
            status != null -> {
                "OpenAI вернул пустой ответ со статусом: $status"
            }
            else -> "OpenAI вернул пустой ответ"
        }
    }

    private fun parseOpenAiError(errorBody: String): String? {
        return runCatching {
            json.parseToJsonElement(errorBody)
                .jsonObject["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
