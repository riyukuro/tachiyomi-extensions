package eu.kanade.tachiyomi.extension.en.mangaaggregator

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class mangaaggregator : ConfigurableSource, HttpSource() {

    override val name = "MangaAggregator"

    // override val baseUrl = "http://192.168.1.9:5000"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    override val baseUrl by lazy { getPrefBaseUrl() }
    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun parseMangaFromJson(response: Response): MangasPage {
        val jsonObject = json.decodeFromString<JsonObject>(response.body!!.string())

        val mangas = jsonObject["data"]!!.jsonArray.map { json ->
            SManga.create().apply {
                title = json.jsonObject["manga_title"]!!.jsonPrimitive.content
                thumbnail_url = json.jsonObject["manga_cover"]!!.jsonPrimitive.content
                url = json.jsonObject["manga_url"]!!.jsonPrimitive.content
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular?source=mangago", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaFromJson(response)

    // Latest
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest?source=mangago", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaFromJson(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val list = query.split(":")
        val source = list[0]
        val search = list[1]
        return GET("$baseUrl/search?source=$source&search=$search", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaFromJson(response)

    // Details

    override fun mangaDetailsRequest(manga: SManga) = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonObject = json.decodeFromString<JsonObject>(response.body!!.string())

        return SManga.create().apply {
            description = jsonObject["manga_desc"]!!.jsonPrimitive.contentOrNull
            status = jsonObject["manga_status"]!!.jsonPrimitive.contentOrNull.toStatus()
            thumbnail_url = jsonObject["manga_cover"]!!.jsonPrimitive.contentOrNull
            genre = try { jsonObject["manga_genres"]!!.jsonArray.joinToString { it.jsonPrimitive.content } } catch (_: Exception) { null }
            artist = try { jsonObject["manga_artist"]!!.jsonArray.joinToString { it.jsonPrimitive.content } } catch (_: Exception) { null }
            author = try { jsonObject["manga_author"]!!.jsonArray.joinToString { it.jsonPrimitive.content } } catch (_: Exception) { null }
        }
    }

    private fun String?.toStatus() = when {
        this == null -> SManga.UNKNOWN
        this.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
        this.contains("complete", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return json.decodeFromString<JsonObject>(response.body!!.string())["manga_chapters"]!!.jsonArray.map { json ->
            SChapter.create().apply {
                name = json.jsonObject["chapter_title"]!!.jsonPrimitive.content
                url = json.jsonObject["chapter_url"]!!.jsonPrimitive.content
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = mutableListOf<Page>()
        val jsonObject = json.decodeFromString<JsonObject>(response.body!!.string())

        if (jsonObject["isPaged"]!!.jsonPrimitive.booleanOrNull == false) {
            jsonObject["pages"]!!.jsonArray.mapIndexed { i, element ->
                pages.add(Page(i, "", element.jsonPrimitive!!.content))
            }
        } else {
            jsonObject["pages"]!!.jsonArray.mapIndexed { i, element ->
                pages.add(Page(i, "", baseUrl + element.jsonPrimitive!!.content))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(ADDRESS_TITLE, ADDRESS_DEFAULT, "The URL to access your Aggregator instance. Please include the port number if you didn't set up a reverse proxy"))
    }

    private fun androidx.preference.PreferenceScreen.editTextPreference(title: String, default: String, value: String, isPassword: Boolean = false): androidx.preference.EditTextPreference {
        return androidx.preference.EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }

    // We strip the last slash since we will append it above
    private fun getPrefBaseUrl(): String {
        var path = preferences.getString(ADDRESS_TITLE, ADDRESS_DEFAULT)!!
        if (path.isNotEmpty() && path.last() == '/') {
            path = path.substring(0, path.length - 1)
        }
        return path
    }

    companion object {
        private const val ADDRESS_TITLE = "Address"
        private const val ADDRESS_DEFAULT = ""
    }
}
