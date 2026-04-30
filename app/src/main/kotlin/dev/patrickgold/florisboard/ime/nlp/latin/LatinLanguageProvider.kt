/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"
    }

    private val appContext by context.appContext()
    private val prefs by FlorisPreferenceStore

    private val dataLock = Any()
    @Volatile private var wordData: Map<String, Int> = emptyMap()
    @Volatile private var wordIndex: Map<String, List<String>> = emptyMap()
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
    @Volatile private var activeDictionaryKey: String? = null

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        val dictKey = subtype.primaryLocale.language.ifBlank { "en" }
        if (activeDictionaryKey == dictKey && wordData.isNotEmpty()) {
            return@withContext
        }
        val rawData = loadDictionaryText(dictKey)
        synchronized(dataLock) {
            activeDictionaryKey = dictKey
            if (rawData.isNullOrBlank()) {
                wordData = emptyMap()
                wordIndex = emptyMap()
                return@withContext
            }
            val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
            wordData = jsonData
            wordIndex = buildWordIndex(jsonData)
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val locale = subtype.primaryLocale.base
        val normalized = word.lowercase(locale)
        val wordDataSnapshot = wordData
        if (wordDataSnapshot.isEmpty()) {
            return SpellingResult.unspecified()
        }
        if (wordDataSnapshot.containsKey(normalized)) {
            return SpellingResult.validWord()
        }
        val suggestions = buildSuggestions(
            input = word,
            normalized = normalized,
            locale = locale,
            maxCandidateCount = maxSuggestionCount,
            allowAutoCommit = false,
        )
        return SpellingResult.typo(suggestions.map { it.text.toString() }.toTypedArray())
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val locale = subtype.primaryLocale.base
        val input = content.composingText.ifBlank { content.currentWordText }
        if (input.isBlank()) {
            return emptyList()
        }
        val normalized = input.lowercase(locale)
        if (wordData.isEmpty()) {
            return emptyList()
        }
        return buildSuggestions(
            input = input,
            normalized = normalized,
            locale = locale,
            maxCandidateCount = maxCandidateCount,
            allowAutoCommit = prefs.correction.autoCorrectEnabled.get(),
        )
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.keys.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.getOrDefault(word, 0) / 255.0
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }

    private fun loadDictionaryText(languageKey: String): String? {
        val exactPath = "ime/dict/$languageKey.json"
        val fallbackPath = "ime/dict/data.json"
        return runCatching { appContext.assets.readText(exactPath) }
            .recoverCatching {
                if (languageKey == "en") {
                    appContext.assets.readText(fallbackPath)
                } else {
                    null
                }
            }
            .getOrNull()
    }

    private fun buildWordIndex(data: Map<String, Int>): Map<String, List<String>> {
        val index = mutableMapOf<String, MutableList<String>>()
        for (word in data.keys) {
            val key = word.take(min(2, word.length))
            index.getOrPut(key) { mutableListOf() }.add(word)
        }
        for (list in index.values) {
            list.sortByDescending { data[it] ?: 0 }
        }
        return index
    }

    private fun buildSuggestions(
        input: String,
        normalized: String,
        locale: Locale,
        maxCandidateCount: Int,
        allowAutoCommit: Boolean,
    ): List<WordSuggestionCandidate> {
        val prefixKey = normalized.take(min(2, normalized.length))
        val index = wordIndex
        val wordDataSnapshot = wordData
        val prefixCandidates = index[prefixKey].orEmpty().ifEmpty {
            if (prefixKey.length > 1) index[normalized.take(1)].orEmpty() else emptyList()
        }
        val exactMatchExists = wordDataSnapshot.containsKey(normalized)
        val prefixMatches = prefixCandidates.filter { it.startsWith(normalized) }
        val sortedPrefixMatches = prefixMatches.sortedByDescending { wordDataSnapshot[it] ?: 0 }
        val results = sortedPrefixMatches.take(maxCandidateCount).toMutableList()

        var autoCommitWord: String? = null
        if (!exactMatchExists && normalized.length >= 3) {
            val maxDistance = if (normalized.length >= 6) 2 else 1
            autoCommitWord = findBestFuzzyMatch(normalized, prefixCandidates, maxDistance, wordDataSnapshot)
            if (autoCommitWord != null && autoCommitWord !in results) {
                results.add(0, autoCommitWord)
            }
        }

        return results.distinct().take(maxCandidateCount).mapIndexed { indexInList, candidate ->
            WordSuggestionCandidate(
                text = applyInputCase(input, candidate, locale),
                confidence = (wordDataSnapshot[candidate] ?: 0) / 255.0,
                isEligibleForAutoCommit = allowAutoCommit && candidate == autoCommitWord && indexInList == 0,
                sourceProvider = this,
            )
        }
    }

    private fun findBestFuzzyMatch(
        input: String,
        candidates: List<String>,
        maxDistance: Int,
        wordDataSnapshot: Map<String, Int>,
    ): String? {
        var bestWord: String? = null
        var bestDistance = maxDistance + 1
        var bestFrequency = -1
        for (candidate in candidates) {
            if (abs(candidate.length - input.length) > maxDistance) {
                continue
            }
            val distance = editDistanceWithin(input, candidate, maxDistance) ?: continue
            val frequency = wordDataSnapshot[candidate] ?: 0
            if (distance < bestDistance || (distance == bestDistance && frequency > bestFrequency)) {
                bestDistance = distance
                bestFrequency = frequency
                bestWord = candidate
            }
        }
        return bestWord
    }

    private fun editDistanceWithin(input: String, candidate: String, maxDistance: Int): Int? {
        if (abs(input.length - candidate.length) > maxDistance) {
            return null
        }
        var prev = IntArray(candidate.length + 1) { it }
        var curr = IntArray(candidate.length + 1)
        for (i in 1..input.length) {
            curr[0] = i
            var minInRow = curr[0]
            for (j in 1..candidate.length) {
                val cost = if (input[i - 1] == candidate[j - 1]) 0 else 1
                val value = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
                curr[j] = value
                if (value < minInRow) {
                    minInRow = value
                }
            }
            if (minInRow > maxDistance) {
                return null
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        val result = prev[candidate.length]
        return if (result <= maxDistance) result else null
    }

    private fun applyInputCase(input: String, suggestion: String, locale: Locale): String {
        return when {
            input.isEmpty() -> suggestion
            input.all { it.isUpperCase() } -> suggestion.uppercase(locale)
            input.first().isUpperCase() -> suggestion.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(locale)
                } else {
                    char.toString()
                }
            }
            else -> suggestion
        }
    }
}
