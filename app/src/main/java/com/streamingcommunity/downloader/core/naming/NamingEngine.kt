package com.streamingcommunity.downloader.core.naming

import com.streamingcommunity.downloader.core.model.AppSettings

object NamingEngine {

    private val COMMON_TOKENS = listOf("title", "year")
    private val EPISODE_TOKENS = COMMON_TOKENS + listOf("season", "season2", "episode", "episode2")

    val SLOT_TOKENS: Map<String, List<String>> = mapOf(
        "film_folder" to COMMON_TOKENS,
        "film_file" to COMMON_TOKENS,
        "series_folder" to COMMON_TOKENS,
        "season_folder" to COMMON_TOKENS + listOf("season", "season2"),
        "episode_file" to EPISODE_TOKENS,
        "anime_folder" to COMMON_TOKENS,
        "anime_season_folder" to COMMON_TOKENS + listOf("season", "season2"),
        "anime_episode_file" to COMMON_TOKENS + listOf("episode", "episode2"),
        "anime_movie_file" to COMMON_TOKENS
    )

    val DEFAULT_TEMPLATES: Map<String, String> = AppSettings.DEFAULT_NAMING_TEMPLATES

    private val TOKEN_RE = Regex("""\{([a-z0-9_]+)\}""")
    private val GROUP_RE = Regex("""\[([^\[\]]*)\]""")
    private val FORBIDDEN_RE = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private val NESTED_GROUP_RE = Regex("""\[[^\]]*\[""")

    private val PROBE_VALUES = mapOf(
        "title" to "Titolo",
        "year" to "2024",
        "season" to "1",
        "season2" to "01",
        "episode" to "7",
        "episode2" to "07"
    )

    fun sanitizeFilename(name: String, maxLength: Int = 180): String {
        val cleaned = FORBIDDEN_RE.replace(name, "").trim().trim('.')
        return if (cleaned.length > maxLength) cleaned.take(maxLength).trim() else cleaned
    }

    fun cleanTitle(name: String): String {
        return sanitizeFilename(name.replace("+", " ").replace(",", ""))
    }

    fun fmtEp(number: String): String {
        val parts = number.split(".", limit = 2)
        val padded = parts[0].padStart(2, '0')
        return if (parts.size == 1) padded else "$padded.${parts[1]}"
    }

    private fun scrub(name: String): String {
        return FORBIDDEN_RE.replace(name, "").trim().trim('.')
    }

    fun render(slot: String, template: String?, values: Map<String, String>): String {
        val text = template ?: DEFAULT_TEMPLATES[slot] ?: "{title}"
        var rendered = renderRaw(text, values)
        if (rendered.isEmpty() && text != DEFAULT_TEMPLATES[slot]) {
            rendered = renderRaw(DEFAULT_TEMPLATES[slot] ?: "{title}", values)
        }
        return rendered.ifEmpty {
            scrub(values["title"].orEmpty()).ifEmpty { "senza-nome" }
        }
    }

    fun renderRaw(template: String, values: Map<String, String>): String {
        val groupResolved = GROUP_RE.replace(template) { match ->
            val inner = match.groupValues[1]
            val tokens = TOKEN_RE.findAll(inner).map { it.groupValues[1] }.toList()
            if (tokens.isNotEmpty() && !tokens.all { !values[it].isNullOrEmpty() }) {
                ""
            } else {
                inner
            }
        }

        val tokenResolved = TOKEN_RE.replace(groupResolved) { match ->
            val tokenName = match.groupValues[1]
            values[tokenName].orEmpty()
        }

        return scrub(tokenResolved)
    }

    fun validate(slot: String, template: String): String {
        val allowed = SLOT_TOKENS[slot]
            ?: throw IllegalArgumentException("Sezione sconosciuta: $slot")

        val text = template.trim()
        if (text.isEmpty()) {
            throw IllegalArgumentException("Il modello non può essere vuoto")
        }
        if ("/" in text || "\\" in text) {
            throw IllegalArgumentException("Il modello non può contenere / o \\: è un solo nome, non un percorso")
        }
        if (".." in text) {
            throw IllegalArgumentException("Il modello non può contenere ..")
        }
        if (text.startsWith(".") || text.endsWith(".")) {
            throw IllegalArgumentException("Il modello non può iniziare o finire con un punto")
        }
        if (text.count { it == '[' } != text.count { it == ']' }) {
            throw IllegalArgumentException("Parentesi quadre non bilanciate")
        }
        if (NESTED_GROUP_RE.containsMatchIn(text)) {
            throw IllegalArgumentException("I gruppi opzionali non possono essere annidati")
        }

        val unknown = TOKEN_RE.findAll(text)
            .map { it.groupValues[1] }
            .filter { it !in allowed }
            .toList()
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException(
                "Segnaposto non validi qui: ${unknown.joinToString(", ") { "{$it}" }}. " +
                        "Disponibili: ${allowed.joinToString(", ") { "{$it}" }}"
            )
        }

        val rendered = renderRaw(text, PROBE_VALUES)
        if (rendered.isEmpty()) {
            throw IllegalArgumentException("Con valori di esempio il modello produce un nome vuoto")
        }
        if (rendered != scrub(rendered)) {
            throw IllegalArgumentException("Il modello contiene caratteri non ammessi in un nome di file")
        }

        return text
    }

    fun preview(slot: String, template: String, customValues: Map<String, String> = emptyMap()): String {
        return renderRaw(template, PROBE_VALUES + customValues)
    }

    private fun buildValues(
        title: String,
        year: String? = null,
        season: Int? = null,
        episode: String? = null
    ): Map<String, String> {
        val map = mutableMapOf(
            "title" to title,
            "year" to if (year.isNullOrBlank() || year == "0") "" else year
        )
        if (season != null) {
            map["season"] = season.toString()
            map["season2"] = "%02d".format(season)
        }
        if (episode != null) {
            map["episode"] = episode
            map["episode2"] = fmtEp(episode)
        }
        return map
    }

    private fun extensionForContainer(container: String): String {
        return if (container.lowercase() == "mkv") ".mkv" else ".mp4"
    }

    private fun ensureInsideRoot(rootDir: String, candidatePath: String): String {
        val rootCanonical = java.io.File(rootDir).canonicalPath
        val targetCanonical = java.io.File(candidatePath).canonicalPath
        if (!targetCanonical.startsWith(rootCanonical + java.io.File.separator)) {
            throw SecurityException("Destination path escapes media root directory")
        }
        return targetCanonical
    }

    fun filmPath(
        rootDir: String,
        title: String,
        year: String? = null,
        templates: Map<String, String> = DEFAULT_TEMPLATES,
        container: String = "mp4"
    ): String {
        val tpl = DEFAULT_TEMPLATES + templates
        val values = buildValues(cleanTitle(title), year)
        val folder = render("film_folder", tpl["film_folder"], values)
        val stem = render("film_file", tpl["film_file"], values)
        return ensureInsideRoot(rootDir, "$rootDir/Movies/$folder/$stem${extensionForContainer(container)}")
    }

    fun episodePath(
        rootDir: String,
        tvName: String,
        season: Int,
        episodeNumber: String,
        year: String? = null,
        templates: Map<String, String> = DEFAULT_TEMPLATES,
        container: String = "mp4"
    ): String {
        val tpl = DEFAULT_TEMPLATES + templates
        val values = buildValues(sanitizeFilename(tvName), year, season, episodeNumber)
        val folder = render("series_folder", tpl["series_folder"], values)
        val seasonFolder = render("season_folder", tpl["season_folder"], values)
        val stem = render("episode_file", tpl["episode_file"], values)
        return ensureInsideRoot(rootDir, "$rootDir/Series/$folder/$seasonFolder/$stem${extensionForContainer(container)}")
    }

    fun isAnimeSeries(animeType: String): Boolean {
        val kind = animeType.trim().lowercase().ifEmpty { "tv" }
        return kind != "movie" && kind != "film"
    }

    fun animePath(
        rootDir: String,
        animeName: String,
        episodeNumber: String,
        animeType: String = "tv",
        year: String? = null,
        templates: Map<String, String> = DEFAULT_TEMPLATES,
        container: String = "mp4"
    ): String {
        val tpl = DEFAULT_TEMPLATES + templates
        val values = buildValues(cleanTitle(animeName), year, 1, episodeNumber)
        val folder = render("anime_folder", tpl["anime_folder"], values)
        val ext = extensionForContainer(container)

        val candidate = if (isAnimeSeries(animeType)) {
            val seasonFolder = render("anime_season_folder", tpl["anime_season_folder"], values)
            val stem = render("anime_episode_file", tpl["anime_episode_file"], values)
            "$rootDir/Anime/$folder/$seasonFolder/$stem$ext"
        } else {
            val stem = render("anime_movie_file", tpl["anime_movie_file"], values)
            "$rootDir/Anime/$folder/$stem$ext"
        }
        return ensureInsideRoot(rootDir, candidate)
    }
}
