package com.defaulty.clckr

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.Locale
import java.math.BigInteger
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SUPABASE_URL = "https://jdlnufgabzpugbbpfhwv.supabase.co"
private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_NBMZG-973iGpUtKHF9BB4Q_lrqbxS4_"

private data class CloudResponse(val code: Int, val body: String)

private data class RankedLeaderboardEntry(
    val username: String,
    val elo: Int,
    val matches: Int,
    val badgeText: String?,
    val badgeColor: String?
)

private suspend fun supabaseRequest(
    path: String,
    method: String,
    body: String? = null,
    accessToken: String? = null,
    extraHeaders: Map<String, String> = emptyMap()
): CloudResponse = withContext(Dispatchers.IO) {
    try {
        val connection = (URL(SUPABASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doInput = true
            connection.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            extraHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
            CloudResponse(code, responseBody)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        CloudResponse(0, JSONObject().put("message", e.message ?: e::class.java.simpleName).toString())
    }
}

private fun cloudError(response: CloudResponse, fallback: String): String {
    return try {
        val json = JSONObject(response.body)
        json.optString("msg").ifBlank {
            json.optString("message").ifBlank {
                json.optString("error_description").ifBlank { fallback }
            }
        }
    } catch (_: Exception) {
        fallback
    }
}

private suspend fun cloudSignUp(
    email: String,
    username: String,
    password: String,
    prefs: SharedPreferences
): String? {
    try {
        val response = supabaseRequest(
        "/auth/v1/signup",
        "POST",
        JSONObject().apply {
            put("email", email.trim())
            put("password", password)
            put("data", JSONObject().put("username", username.trim()))
        }.toString()
    )
    if (response.code !in 200..299) return cloudError(response, "Registration failed")

    if (response.body.isBlank()) return "Registration failed: empty server response"

    val json = JSONObject(response.body)
    val user = json.optJSONObject("user")
    val userId = user?.optString("id").orEmpty()
    val accessToken = json.optString("access_token")
    val refreshToken = json.optString("refresh_token")

    if (userId.isBlank()) {
        return "Registration failed: server did not return a user"
    }

    // With email confirmation disabled, Supabase returns a session here.
    // If confirmation is enabled, there is no access/refresh token yet.
    if (accessToken.isBlank() || refreshToken.isBlank()) {
        return "Account created. Check your email and confirm it before logging in."
    }

    val profileResponse = supabaseRequest(
        "/rest/v1/profiles",
        "POST",
        JSONObject().apply {
            put("id", userId)
            put("username", username.trim())
        }.toString(),
        accessToken,
        mapOf("Prefer" to "resolution=merge-duplicates,return=representation")
    )
    if (profileResponse.code !in 200..299) {
        return cloudError(profileResponse, "Account created, but profile setup failed")
    }

        prefs.edit()
            .putString("cloud_access_token", accessToken)
            .putString("cloud_refresh_token", refreshToken)
            .putString("cloud_user_id", userId)
            .putString("account_logged_username", username.trim())
            .putString("account_email", email.trim())
            .apply()
        return null
    } catch (e: Exception) {
        return "Registration error: ${e.message ?: e::class.java.simpleName}"
    }
}

private suspend fun cloudLogin(
    email: String,
    password: String,
    prefs: SharedPreferences
): Pair<String?, String?> {
    val response = supabaseRequest(
        "/auth/v1/token?grant_type=password",
        "POST",
        JSONObject().apply {
            put("email", email.trim())
            put("password", password)
        }.toString()
    )
    if (response.code !in 200..299) return null to cloudError(response, "Incorrect email or password")

    val json = JSONObject(response.body)
    val accessToken = json.optString("access_token")
    val refreshToken = json.optString("refresh_token")
    val user = json.optJSONObject("user")
    val userId = user?.optString("id").orEmpty()
    if (accessToken.isBlank() || refreshToken.isBlank() || userId.isBlank()) {
        return null to "Login response was incomplete"
    }

    prefs.edit().putString("cloud_access_token", accessToken).putString("cloud_refresh_token", refreshToken).putString("cloud_user_id", userId).apply()
    val accountStatus = cloudGetMyAccountStatus(prefs)
    if (accountStatus?.first == "banned") return null to "This account is banned"
    if (accountStatus?.first == "suspended") return null to "This account is suspended"

    val profileResponse = supabaseRequest(
        "/rest/v1/profiles?select=username,avatar_path,badge_text,badge_color,elo,ranked_matches&id=eq.${URLEncoder.encode(userId, "UTF-8")}",
        "GET",
        accessToken = accessToken
    )
    if (profileResponse.code !in 200..299) {
        return null to cloudError(profileResponse, "Could not load account profile")
    }
    val profiles = org.json.JSONArray(profileResponse.body)
    var username: String? = null
    var badgeText: String? = null
    var badgeColor: String? = null
    var rankedElo = 0
    var rankedMatches = 0

    if (profiles.length() > 0) {
        val profile = profiles.getJSONObject(0)
        username = profile.optString("username").takeIf { it.isNotBlank() }
        badgeText = profile.optString("badge_text").takeIf { it.isNotBlank() }
        badgeColor = profile.optString("badge_color").takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
        rankedElo = profile.optInt("elo", 0)
        rankedMatches = profile.optInt("ranked_matches", 0)
    }

    val finalUsername = username ?: return null to "Account profile not found"

    prefs.edit()
        .putString("cloud_access_token", accessToken)
        .putString("cloud_refresh_token", refreshToken)
        .putString("cloud_user_id", userId)
        .putString("account_logged_username", finalUsername)
        .putString("account_email", email.trim())
        .putString("account_badge_text", badgeText)
        .putString("account_badge_color", badgeColor)
        .putInt("ranked_elo", rankedElo)
        .putInt("ranked_matches", rankedMatches)
        .apply()
    return finalUsername to null
}

private suspend fun cloudRecordRankedResult(
    prefs: SharedPreferences,
    won: Boolean,
    mode: String
): Triple<Int, Int, Int>? {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return null
    val response = supabaseRequest(
        "/rest/v1/rpc/record_ranked_pve_result",
        "POST",
        JSONObject().apply {
            put("p_won", won)
            put("p_mode", mode)
        }.toString(),
        accessToken
    )
    if (response.code !in 200..299) return null
    return try {
        // The RPC now returns a JSON object directly. Keep array parsing as a
        // compatibility fallback for older deployed versions.
        val row = if (response.body.trimStart().startsWith("[")) {
            val rows = org.json.JSONArray(response.body)
            if (rows.length() == 0) null else rows.getJSONObject(0)
        } else {
            JSONObject(response.body)
        }
        if (row == null) null else {
            val elo = row.optInt("new_elo", 0)
            val matches = row.optInt("ranked_matches", 0)
            val delta = row.optInt("delta", 0)
            prefs.edit().putInt("ranked_elo", elo).putInt("ranked_matches", matches).apply()
            Triple(elo, matches, delta)
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun cloudLoadRankedLeaderboard(
    prefs: SharedPreferences,
    top100: Boolean
): List<RankedLeaderboardEntry> {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return emptyList()
    return try {
        val result = mutableListOf<RankedLeaderboardEntry>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val limit = if (top100) 100 else pageSize
            val path = "/rest/v1/profiles?select=username,elo,ranked_matches,badge_text,badge_color&ranked_matches=gt.0&order=elo.desc,username.asc&limit=$limit&offset=$offset"
            val response = supabaseRequest(path, "GET", accessToken = accessToken)
            if (response.code !in 200..299) break
            val rows = org.json.JSONArray(response.body)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                result += RankedLeaderboardEntry(
                    username = row.optString("username"),
                    elo = row.optInt("elo", 0),
                    matches = row.optInt("ranked_matches", 0),
                    badgeText = row.optString("badge_text").takeIf { it.isNotBlank() },
                    badgeColor = row.optString("badge_color").takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
                )
            }
            if (top100 || rows.length() < pageSize) break
            offset += pageSize
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun cloudCheckAdmin(prefs: SharedPreferences): Boolean {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return false
    val response = supabaseRequest("/rest/v1/rpc/is_current_user_admin", "POST", "{}", accessToken)
    return response.code in 200..299 && response.body.trim().equals("true", ignoreCase = true)
}

private suspend fun cloudSetBadge(
    username: String,
    badgeText: String,
    badgeColor: String,
    prefs: SharedPreferences
): String? {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return "Not logged in"
    val response = supabaseRequest(
        "/rest/v1/rpc/admin_set_badge",
        "POST",
        JSONObject().apply {
            put("p_username", username.trim())
            put("p_badge_text", badgeText.trim())
            put("p_badge_color", badgeColor.trim())
        }.toString(),
        accessToken
    )
    return if (response.code in 200..299) null else cloudError(response, "Could not set badge")
}

private suspend fun cloudSetAccountStatus(
    username: String,
    status: String,
    suspendMinutes: Int?,
    prefs: SharedPreferences
): String? {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return "Not logged in"
    val until = if (status == "suspended") {
        val minutes = suspendMinutes?.coerceAtLeast(1) ?: return "Enter suspend duration"
        java.time.Instant.now().plusSeconds(minutes.toLong() * 60L).toString()
    } else null
    val body = JSONObject().apply {
        put("p_username", username.trim())
        put("p_status", status)
        if (until == null) put("p_suspend_until", JSONObject.NULL) else put("p_suspend_until", until)
    }.toString()
    val response = supabaseRequest("/rest/v1/rpc/admin_set_account_status", "POST", body, accessToken)
    return if (response.code in 200..299) null else cloudError(response, "Could not change account status")
}

private suspend fun cloudGetMyAccountStatus(prefs: SharedPreferences): Pair<String, String?>? {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return null
    val response = supabaseRequest("/rest/v1/rpc/get_my_account_status", "POST", "{}", accessToken)
    if (response.code !in 200..299) return null
    return try {
        val json = JSONObject(response.body)
        json.optString("status", "active") to json.optString("suspend_until").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

private suspend fun cloudRefreshSession(prefs: SharedPreferences): Boolean {
    val refreshToken = prefs.getString("cloud_refresh_token", null) ?: return false
    val response = supabaseRequest(
        "/auth/v1/token?grant_type=refresh_token",
        "POST",
        JSONObject().put("refresh_token", refreshToken).toString()
    )
    if (response.code !in 200..299) return false
    val json = JSONObject(response.body)
    val accessToken = json.optString("access_token")
    val newRefreshToken = json.optString("refresh_token").ifBlank { refreshToken }
    val user = json.optJSONObject("user")
    val userId = user?.optString("id").orEmpty()
    if (accessToken.isBlank() || userId.isBlank()) return false
    val profileResponse = supabaseRequest(
        "/rest/v1/profiles?select=username,badge_text,badge_color,elo,ranked_matches&id=eq.${URLEncoder.encode(userId, "UTF-8")}",
        "GET",
        accessToken = accessToken
    )
    var profileUsername: String? = null
    var profileBadgeText: String? = null
    var profileBadgeColor: String? = null
    var profileRankedElo = 0
    var profileRankedMatches = 0
    if (profileResponse.code in 200..299) {
        val rows = org.json.JSONArray(profileResponse.body)
        if (rows.length() > 0) {
            val profile = rows.getJSONObject(0)
            profileUsername = profile.optString("username").takeIf { it.isNotBlank() }
            profileBadgeText = profile.optString("badge_text").takeIf { it.isNotBlank() }
            profileBadgeColor = profile.optString("badge_color").takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
            profileRankedElo = profile.optInt("elo", 0)
            profileRankedMatches = profile.optInt("ranked_matches", 0)
        }
    }
    prefs.edit()
        .putString("cloud_access_token", accessToken)
        .putString("cloud_refresh_token", newRefreshToken)
        .putString("cloud_user_id", userId)
        .putString("account_logged_username", profileUsername ?: prefs.getString("account_logged_username", null))
        .putString("account_badge_text", profileBadgeText)
        .putString("account_badge_color", profileBadgeColor)
        .putInt("ranked_elo", profileRankedElo)
        .putInt("ranked_matches", profileRankedMatches)
        .apply()
    return true
}

private suspend fun cloudLoadGame(prefs: SharedPreferences): JSONObject? {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return null
    val userId = prefs.getString("cloud_user_id", null) ?: return null
    val response = supabaseRequest(
        "/rest/v1/game_data?select=save_data&user_id=eq.${URLEncoder.encode(userId, "UTF-8")}",
        "GET",
        accessToken = accessToken
    )
    if (response.code !in 200..299) return null
    val rows = org.json.JSONArray(response.body)
    if (rows.length() == 0) return null
    return rows.getJSONObject(0).optJSONObject("save_data")
}

private suspend fun cloudSaveGame(
    prefs: SharedPreferences,
    clicks: BigInteger,
    autoclickers: BigInteger,
    autoSpeedLevel: Int,
    clickMultiplier: Int,
    selectedSkin: String,
    selectedShopSkin: String,
    selectedAccessory: String
) {
    val accessToken = prefs.getString("cloud_access_token", null) ?: return
    val userId = prefs.getString("cloud_user_id", null) ?: return
    val saveData = JSONObject().apply {
        put("clicks", clicks.toString())
        put("autoclickers", autoclickers.toString())
        put("auto_speed_level", autoSpeedLevel)
        put("click_multiplier", clickMultiplier)
        put("selected_skin", selectedSkin)
        put("selected_shop_skin", selectedShopSkin)
        put("selected_accessory", selectedAccessory)
    }
    supabaseRequest(
        "/rest/v1/game_data",
        "POST",
        JSONObject().apply {
            put("user_id", userId)
            put("save_data", saveData)
        }.toString(),
        accessToken,
        mapOf("Prefer" to "resolution=merge-duplicates")
    )
}

private fun hashAccountPassword(password: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun accountPrefsKey(username: String): String {
    return username.trim().lowercase(Locale.ROOT)
}

private fun generateDailyQuest(prefs: SharedPreferences) {
    val duration = Random.nextInt(5, 51) + 5
    val error = Random.nextInt(-50, 51)
    val target = (duration * 10 + error).coerceIn(50, 500)
    prefs.edit()
        .putInt("daily_duration", duration)
        .putInt("daily_target", target)
        .putInt("daily_error", error)
        .putLong("daily_next_refresh", 0L)
        .remove("daily_result")
        .remove("daily_reward_percent")
        .apply()
}

private fun ensureDailyQuest(prefs: SharedPreferences): Triple<Int, Int, Int> {
    val now = System.currentTimeMillis()
    val nextRefresh = prefs.getLong("daily_next_refresh", 0L)
    var duration = prefs.getInt("daily_duration", 0)
    var target = prefs.getInt("daily_target", 0)
    var error = prefs.getInt("daily_error", 0)

    if (duration !in 10..55 || target !in 50..500 || (nextRefresh > 0L && now >= nextRefresh)) {
        generateDailyQuest(prefs)
        duration = prefs.getInt("daily_duration", 1)
        target = prefs.getInt("daily_target", 50)
        error = prefs.getInt("daily_error", 0)
    }

    return Triple(duration, target, error)
}

private fun loadClicks(prefs: SharedPreferences): BigInteger {
    val storedString = try {
        prefs.getString("clicks", null)
    } catch (_: ClassCastException) {
        null
    }

    if (!storedString.isNullOrBlank()) {
        storedString.toBigIntegerOrNull()?.let { return it }
    }

    // Migrate old versions that stored clicks as Int.
    return try {
        BigInteger.valueOf(prefs.getInt("clicks", 0).toLong())
    } catch (_: ClassCastException) {
        BigInteger.ZERO
    }
}

private fun accentTextColor(color: Color): Color {
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (luminance > 0.6f) Color.Black else Color.White
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ClckrApp()
        }
    }
}

@Composable
fun ClckrApp() {

    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(
            "clckr",
            Context.MODE_PRIVATE
        )
    }

    var loading by remember {
        mutableStateOf(true)
    }

    var theme by remember {
        mutableStateOf(
            prefs.getString(
                "theme",
                "default"
            ) ?: "default"
        )
    }

    // =========================================================
    // ACCENT COLOR
    // =========================================================

    var accentColorInt by remember {
        mutableIntStateOf(
            prefs.getInt(
                "accent_color",
                -1
            )
        )
    }

    val systemDark = isSystemInDarkTheme()

    val darkTheme = when (theme) {
        "dark" -> true
        "system", "default" -> systemDark
        else -> false
    }

    val accentColor = if (accentColorInt == -1) {
        if (darkTheme) Color.White else Color.Black
    } else {
        Color(accentColorInt)
    }

    LaunchedEffect(Unit) {
        delay(900)
        loading = false
    }

    MaterialTheme(
        colorScheme = if (darkTheme) {
            darkColorScheme(
                primary = accentColor,
                onPrimary = accentTextColor(accentColor),
                secondary = accentColor,
                onSecondary = accentTextColor(accentColor)
            )
        } else {
            lightColorScheme(
                primary = accentColor,
                onPrimary = accentTextColor(accentColor),
                secondary = accentColor,
                onSecondary = accentTextColor(accentColor)
            )
        }
    ) {
        AnimatedVisibility(
            visible = loading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                     .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "clckr.",
                    color = accentTextColor(accentColor),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(
            visible = !loading,
            enter = fadeIn()
        ) {
            GameScreen(
                prefs = prefs,
                theme = theme,
                onThemeChange = {
                    theme = it
                    if (it == "default") {
                        accentColorInt = -1
                        prefs.edit()
                            .putString("theme", it)
                            .putInt("accent_color", -1)
                            .apply()
                    } else {
                        prefs.edit()
                            .putString("theme", it)
                            .apply()
                    }
                },
                accentColor = accentColor,
                onAccentColorChange = {
                    accentColorInt = it.toArgb()
                    prefs.edit()
                        .putInt("accent_color", accentColorInt)
                        .apply()
                }
            )
        }
    }
}

// =============================================================
// GAME SCREEN
// =============================================================

@Composable
fun GameScreen(
    prefs: SharedPreferences,
    theme: String,
    onThemeChange: (String) -> Unit,
    accentColor: Color,
    onAccentColorChange: (Color) -> Unit
) {

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val systemDark = isSystemInDarkTheme()

    val darkTheme = theme == "dark" || ((theme == "system" || theme == "default") && systemDark)

    val textColor = if (darkTheme) Color.White else Color.Black

    // =========================================================
    // CURRENCY
    // =========================================================

    fun getPrefsIntCompat(key: String, default: Int): Int {
        return when (val value = prefs.all[key]) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    var clicks by remember {
        mutableStateOf(loadClicks(prefs))
    }

    // =========================================================
    // UPGRADES
    // =========================================================

    var autoclickers by remember {
        mutableStateOf(
            when (val value = prefs.all["autoclickers"]) {
                is String -> value.toBigIntegerOrNull() ?: BigInteger.ZERO
                is Int -> BigInteger.valueOf(value.toLong())
                is Long -> BigInteger.valueOf(value)
                else -> BigInteger.ZERO
            }
        )
    }

    var autoSpeedLevel by remember {
        mutableIntStateOf(getPrefsIntCompat("auto_speed_level", 0))
    }

    var clickMultiplier by remember {
        mutableIntStateOf(getPrefsIntCompat("click_multiplier", 1))
    }

    // Manual clicks used as the watch clock: starts at 12:00:00.
    var watchSeconds by remember {
        mutableLongStateOf(0L)
    }

    var manualClickId by remember { mutableIntStateOf(0) }
    // Rolling window for manual CPS detection. One continuous over-limit period = one violation.
    var bulbFlash by remember { mutableStateOf(false) }

    // =========================================================
    // BOUGHT SKINS
    // =========================================================

    var blueBought by remember {
        mutableStateOf(prefs.getBoolean("blue", false))
    }

    var redBought by remember {
        mutableStateOf(prefs.getBoolean("red", false))
    }

    var yellowBought by remember {
        mutableStateOf(prefs.getBoolean("yellow", false))
    }

    var greenBought by remember {
        mutableStateOf(prefs.getBoolean("green", false))
    }

    var customBought by remember {
        mutableStateOf(prefs.getBoolean("custom", false))
    }

    var gradientBought by remember {
        mutableStateOf(prefs.getBoolean("gradient", false))
    }

    var customGradientBought by remember {
        mutableStateOf(prefs.getBoolean("custom_gradient", false))
    }

    // =========================================================
    // SHOP SKINS / ACCESSORIES
    // =========================================================

    val shopSkinIds = listOf("watch", "radar", "speedometer", "bulb", "record")
    val shopAccessoryIds = listOf("chain", "crown", "ribbon", "halo", "sparkles")

    var ownedShopSkins by remember {
        mutableStateOf(shopSkinIds.filter { prefs.getBoolean("shop_skin_$it", false) }.toSet())
    }
    var ownedAccessories by remember {
        mutableStateOf(shopAccessoryIds.filter { prefs.getBoolean("accessory_$it", false) }.toSet())
    }
    var selectedShopSkin by remember {
        mutableStateOf(
            (prefs.getString("selected_shop_skin", "none") ?: "none")
                .takeIf { it == "none" || it in shopSkinIds }
                ?: "none"
        )
    }
    var selectedAccessory by remember { mutableStateOf(prefs.getString("selected_accessory", "none") ?: "none") }

    // =========================================================
    // SELECTED SKIN
    // =========================================================

    var selectedSkin by remember {
        mutableStateOf(prefs.getString("selected_skin", "default") ?: "default")
    }

    // =========================================================
    // CUSTOM COLORS
    // =========================================================

    var customColorInt by remember {
        mutableIntStateOf(
            prefs.getInt("custom_color", AndroidColor.rgb(180, 80, 255))
        )
    }

    var gradientColor1Int by remember {
        mutableIntStateOf(
            prefs.getInt("gradient_color_1", AndroidColor.RED)
        )
    }

    var gradientColor2Int by remember {
        mutableIntStateOf(
            prefs.getInt("gradient_color_2", AndroidColor.BLUE)
        )
    }

    var customGradientColor1Int by remember {
        mutableIntStateOf(
            prefs.getInt("custom_gradient_color_1", AndroidColor.rgb(255, 80, 80))
        )
    }

    var customGradientColor2Int by remember {
        mutableIntStateOf(
            prefs.getInt("custom_gradient_color_2", AndroidColor.rgb(80, 120, 255))
        )
    }

    // =========================================================
    // CUSTOM IMAGE / SOUND
    // =========================================================

    var customImageBought by remember {
        mutableStateOf(prefs.getBoolean("custom_image_bought", false))
    }

    var customSoundBought by remember {
        mutableStateOf(prefs.getBoolean("custom_sound_bought", false))
    }

    var customButtonBought by remember {
        mutableStateOf(prefs.getBoolean("custom_button_bought", false))
    }

    var customButtonEnabled by remember {
        mutableStateOf(prefs.getBoolean("custom_button_enabled", true))
    }

    var customButtonUri by remember {
        mutableStateOf(prefs.getString("custom_button_uri", null))
    }

    var customImageUri by remember {
        mutableStateOf(prefs.getString("custom_image_uri", null))
    }

    var customSoundUri by remember {
        mutableStateOf(prefs.getString("custom_sound_uri", null))
    }

    // =========================================================
    // COSMETIC SETTINGS
    // =========================================================

    var customBackgroundEnabled by remember {
        mutableStateOf(prefs.getBoolean("customBackgroundEnabled", true))
    }

    var customSoundEnabled by remember {
        mutableStateOf(prefs.getBoolean("customSoundEnabled", true))
    }

    var clickAnimationEnabled by remember {
        mutableStateOf(prefs.getBoolean("clickAnimationEnabled", true))
    }

    var numberFormat by remember {
        mutableStateOf(prefs.getString("numberFormat", "plain") ?: "plain")
    }

    var numberDecimals by remember {
        mutableIntStateOf(prefs.getInt("numberDecimals", 2).coerceIn(0, 9))
    }

    // =========================================================
    // DAILY QUESTS
    // =========================================================

    val dailyInitial = remember { ensureDailyQuest(prefs) }
    var dailyDuration by remember { mutableIntStateOf(dailyInitial.first) }
    var dailyTarget by remember { mutableIntStateOf(dailyInitial.second) }
    var dailyError by remember { mutableIntStateOf(dailyInitial.third) }
    var dailyQuestRunning by remember { mutableStateOf(false) }
    var dailyReturnTransition by remember { mutableStateOf(false) }
    var dailyReturnTransitionAlpha by remember { mutableFloatStateOf(0f) }
    var dailyQuestResult by remember { mutableStateOf(prefs.getString("daily_result", null)) }
    var dailyRewardPercent by remember { mutableIntStateOf(prefs.getInt("daily_reward_percent", 0)) }
    var dailyRewardOverride by remember { mutableIntStateOf(prefs.getInt("daily_reward_override", -1)) }
    var dailyDurationOverride by remember { mutableIntStateOf(prefs.getInt("daily_duration_override", -1)) }
    var dailyNextRefresh by remember { mutableLongStateOf(prefs.getLong("daily_next_refresh", 0L)) }

    // =========================================================
    // RANKED MODE
    // =========================================================

    var rankedElo by remember { mutableIntStateOf(prefs.getInt("ranked_elo", 0)) }
    var rankedMatches by remember { mutableIntStateOf(prefs.getInt("ranked_matches", 0)) }
    var rankedBattleRunning by remember { mutableStateOf(false) }
    var rankedBattleMode by remember { mutableStateOf("easy") }
    var rankedResultGain by remember { mutableIntStateOf(0) }

    // =========================================================
    // SERVER-SIDE ADMIN
    // =========================================================

    var isAdmin by remember { mutableStateOf(false) }

    // =========================================================
    // LOCAL ACCOUNT
    // =========================================================

    var accountUsername by remember {
        mutableStateOf(prefs.getString("account_logged_username", null))
    }

    var accountEmail by remember {
        mutableStateOf(prefs.getString("account_email", null))
    }

    var accountBadgeText by remember {
        mutableStateOf(prefs.getString("account_badge_text", null))
    }

    var accountBadgeColor by remember {
        mutableStateOf(prefs.getString("account_badge_color", null))
    }

    var accountAvatarUri by remember(accountUsername) {
        mutableStateOf(
            accountUsername?.let { username ->
                prefs.getString("account_avatar_${accountPrefsKey(username)}", null)
            }
        )
    }

    // =========================================================
    // PANELS
    // =========================================================

    var panel by remember { mutableStateOf<String?>(null) }
    var displayedPanel by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(panel) {
        if (panel != null) {
            displayedPanel = panel
        } else {
            delay(250)
            displayedPanel = null
        }
    }

    LaunchedEffect(panel) {
        if (panel == "daily") {
            val quest = ensureDailyQuest(prefs)
            dailyDuration = prefs.getInt("daily_duration_override", -1).takeIf { it in 10..55 } ?: quest.first
            dailyError = quest.third
            dailyTarget = (dailyDuration * 10 + dailyError).coerceIn(50, 500)
            dailyNextRefresh = prefs.getLong("daily_next_refresh", 0L)
            dailyQuestResult = prefs.getString("daily_result", null)
            dailyRewardPercent = prefs.getInt("daily_reward_percent", 0)
            dailyRewardOverride = prefs.getInt("daily_reward_override", -1)
            dailyDurationOverride = prefs.getInt("daily_duration_override", -1)
        }
    }

    LaunchedEffect(dailyReturnTransition) {
        if (dailyReturnTransition) {
            var elapsed = 0L
            while (elapsed < 1000L) {
                delay(16L)
                elapsed += 16L
                dailyReturnTransitionAlpha = (1f - elapsed / 1000f).coerceIn(0f, 1f)
            }
            dailyReturnTransitionAlpha = 0f
            dailyReturnTransition = false
        }
    }

    // =========================================================
    // CLOUD SESSION / SAVE
    // =========================================================

    LaunchedEffect(Unit) {
        if (cloudRefreshSession(prefs)) {
            isAdmin = cloudCheckAdmin(prefs)
            val accountStatus = cloudGetMyAccountStatus(prefs)
            if (accountStatus?.first == "banned" || accountStatus?.first == "suspended") {
                isAdmin = false
                accountUsername = null
                accountEmail = null
                prefs.edit().remove("cloud_access_token").remove("cloud_refresh_token").remove("cloud_user_id").remove("account_logged_username").remove("account_email").apply()
            } else {
            accountUsername = prefs.getString("account_logged_username", null)
            accountEmail = prefs.getString("account_email", null)
            accountBadgeText = prefs.getString("account_badge_text", null)
            accountBadgeColor = prefs.getString("account_badge_color", null)
            rankedElo = prefs.getInt("ranked_elo", 0)
            rankedMatches = prefs.getInt("ranked_matches", 0)
            val cloudSave = cloudLoadGame(prefs)
            if (cloudSave != null) {
                cloudSave.optString("clicks").toBigIntegerOrNull()?.let { clicks = it }
                cloudSave.optString("autoclickers").toBigIntegerOrNull()?.let { autoclickers = it }
                if (cloudSave.has("auto_speed_level")) autoSpeedLevel = cloudSave.optInt("auto_speed_level", autoSpeedLevel)
                if (cloudSave.has("click_multiplier")) clickMultiplier = cloudSave.optInt("click_multiplier", clickMultiplier)
                if (cloudSave.has("selected_skin")) selectedSkin = cloudSave.optString("selected_skin", selectedSkin)
                if (cloudSave.has("selected_shop_skin")) selectedShopSkin = cloudSave.optString("selected_shop_skin", selectedShopSkin)
                if (cloudSave.has("selected_accessory")) selectedAccessory = cloudSave.optString("selected_accessory", selectedAccessory)
            }
            }
        } else if (prefs.getString("cloud_refresh_token", null) != null) {
            accountUsername = null
            accountEmail = null
            prefs.edit()
                .remove("cloud_access_token")
                .remove("cloud_refresh_token")
                .remove("cloud_user_id")
                .remove("account_logged_username")
                .remove("account_email")
                .apply()
        }
    }

    LaunchedEffect(accountUsername) {
        while (accountUsername != null) {
            delay(5000)
            cloudSaveGame(
                prefs,
                clicks,
                autoclickers,
                autoSpeedLevel,
                clickMultiplier,
                selectedSkin,
                selectedShopSkin,
                selectedAccessory
            )
        }
    }

    // =========================================================
    // AUTOCLICKER LOOP
    // =========================================================

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        var accumulatedNanos = 0L

        while (true) {
            delay(16)
            val now = System.nanoTime()
            val elapsedNanos = (now - lastTime).coerceAtLeast(0L)
            lastTime = now

            if (autoclickers > BigInteger.ZERO) {
                accumulatedNanos += elapsedNanos
                val clicksPerSecond = autoclickers.multiply(
                    BigInteger.valueOf((1L + autoSpeedLevel).toLong())
                )
                val earned = clicksPerSecond
                    .multiply(BigInteger.valueOf(accumulatedNanos))
                    .divide(BigInteger.valueOf(1_000_000_000L))

                if (earned > BigInteger.ZERO) {
                    clicks += earned
                    val usedNanos = earned
                        .multiply(BigInteger.valueOf(1_000_000_000L))
                        .divide(clicksPerSecond)
                    accumulatedNanos = (accumulatedNanos -
                        usedNanos.toLong().coerceAtMost(accumulatedNanos))
                        .coerceAtLeast(0L)
                    prefs.edit()
                        .putString("clicks", clicks.toString())
                        .putString("autoclickers", autoclickers.toString())
                        .apply()
                }
            } else {
                accumulatedNanos = 0L
            }
        }
    }

    // =========================================================
    // IMAGE PICKER
    // =========================================================

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            customImageUri = uri.toString()
            prefs.edit().putString("custom_image_uri", uri.toString()).apply()
        }
    }

    // =========================================================
    // SOUND PICKER
    // =========================================================

    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            customSoundUri = uri.toString()
            prefs.edit().putString("custom_sound_uri", uri.toString()).apply()
        }
    }

    // =========================================================
    // CUSTOM BUTTON PICKER
    // =========================================================

    val buttonPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            customButtonUri = uri.toString()
            prefs.edit().putString("custom_button_uri", uri.toString()).apply()
        }
    }

    // =========================================================
    // ACCOUNT AVATAR PICKER
    // =========================================================

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && accountUsername != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            accountAvatarUri = uri.toString()
            prefs.edit()
                .putString("account_avatar_${accountPrefsKey(accountUsername!!)}", uri.toString())
                .apply()
        }
    }

    // =========================================================
    // ROOT
    // =========================================================

    Box(modifier = Modifier.fillMaxSize()) {

        // System/Phone Hardware & Gesture Back Button Handler
        BackHandler(enabled = panel != null || settingsOpen) {
            panel = null
            settingsOpen = false
        }

        // =====================================================
        // CUSTOM BACKGROUND
        // =====================================================

        if (customBackgroundEnabled && customImageBought && customImageUri != null) {
            val backgroundBitmap = remember(customImageUri) {
                try {
                    BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(
                            Uri.parse(customImageUri)
                        )
                    )
                } catch (_: Exception) {
                    null
                }
            }

            if (backgroundBitmap != null) {
                Image(
                    bitmap = backgroundBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // =====================================================
        // GAME
        // =====================================================

        AnimatedVisibility(
            visible = !settingsOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (customBackgroundEnabled && customImageBought && customImageUri != null)
                            Color.Transparent
                        else
                            MaterialTheme.colorScheme.background
                    )
            ) {

                // =================================================
                // CLICKS
                // =================================================

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 75.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ClickCounter(
                            clicks = clicks,
                            format = numberFormat,
                            enabled = clickAnimationEnabled,
                            decimals = numberDecimals,
                            textColor = textColor
                        )

                        Text(
                            text = "clicks",
                            color = textColor,
                            fontSize = 16.sp
                        )
                    }
                }

                // =================================================
                // MAIN BUTTON
                // =================================================

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .then(
                                if (selectedShopSkin == "bulb") Modifier
                                else Modifier
                                    .clip(CircleShape)
                                    .border(
                                        width = 3.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                            )
                            .clickable {
                                clicks += BigInteger.valueOf(clickMultiplier.toLong())
                                watchSeconds += 1L
                                manualClickId += 1
                                prefs.edit().putString("clicks", clicks.toString()).apply()

                                if (customSoundEnabled) {
                                    playCustomSound(context, customSoundUri)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (customButtonBought && customButtonEnabled && customButtonUri != null) {
                            val buttonBitmap = remember(customButtonUri, context) {
                                try {
                                    context.contentResolver.openInputStream(Uri.parse(customButtonUri))?.use { stream -> BitmapFactory.decodeStream(stream) }
                                } catch (_: Exception) { null }
                            }
                            if (buttonBitmap != null) {
                                Image(
                                    bitmap = buttonBitmap.asImageBitmap(),
                                    contentDescription = "Custom click button",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else if (selectedShopSkin != "bulb") {
                            when (selectedSkin) {
                            "default" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface)
                                )
                            }
                            "blue" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Blue)
                                )
                            }
                            "red" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Red)
                                )
                            }
                            "yellow" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Yellow)
                                )
                            }
                            "green" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Green)
                                )
                            }
                            "custom" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(customColorInt))
                                )
                            }
                            "gradient" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    Color(gradientColor1Int),
                                                    Color(gradientColor2Int)
                                                )
                                            )
                                        )
                                )
                            }
                            "custom_gradient" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    Color(customGradientColor1Int),
                                                    Color(customGradientColor2Int)
                                                )
                                            )
                                        )
                                )
                            }
                            "custom_image" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface)
                                )
                            }
                            }
                        }

                        // Purchased shop skin model. The model uses the currently selected color.
                        val modelColor = when (selectedSkin) {
                            "blue" -> Color.Blue
                            "red" -> Color.Red
                            "yellow" -> Color.Yellow
                            "green" -> Color.Green
                            "custom" -> Color(customColorInt)
                            "gradient" -> Color(gradientColor1Int)
                            "custom_gradient" -> Color(customGradientColor1Int)
                            else -> accentColor
                        }

                        if (!(customButtonBought && customButtonEnabled && customButtonUri != null)) when (selectedShopSkin) {
                            "watch" -> WatchModel(
                                modifier = Modifier.size(170.dp),
                                dark = darkTheme,
                                elapsedSeconds = watchSeconds
                            )
                            "radar" -> RadarModel(Modifier.fillMaxSize(), modelColor, manualClickId)
                            "speedometer" -> SpeedometerModel(Modifier.fillMaxSize(), modelColor, manualClickId)
                            "bulb" -> LightBulbModel(Modifier.size(250.dp), modelColor, manualClickId, onSpark = { bulbFlash = true })
                            "record" -> RecordModel(Modifier.fillMaxSize(), modelColor, manualClickId)
                        }

                        if (selectedAccessory != "none") {
                            val accessorySymbol = when (selectedAccessory) {
                                "chain" -> "⛓"
                                "crown" -> "♛"
                                "ribbon" -> "◆"
                                "halo" -> "○"
                                "sparkles" -> "✦"
                                else -> ""
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Text(
                                    accessorySymbol,
                                    color = if (selectedAccessory == "crown") Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 18.dp)
                                )
                            }
                        }
                    }
                }

                // =================================================
                // PANEL
                // =================================================

                AnimatedVisibility(
                    visible = panel != null,
                    enter = fadeIn(animationSpec = tween(250)) +
                            expandVertically(
                                animationSpec = tween(250),
                                expandFrom = Alignment.Bottom
                            ),
                    exit = fadeOut(animationSpec = tween(250)) +
                            shrinkVertically(
                                animationSpec = tween(250),
                                shrinkTowards = Alignment.Bottom
                            )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 500.dp)
                    ) {
                        when (displayedPanel) {
                            "shop" -> {
                                ShopPanel(
                                    clicks = clicks,
                                    textColor = textColor,
                                    blueBought = blueBought,
                                    redBought = redBought,
                                    yellowBought = yellowBought,
                                    greenBought = greenBought,
                                    customBought = customBought,
                                    gradientBought = gradientBought,
                                    customGradientBought = customGradientBought,
                                    ownedShopSkins = ownedShopSkins,
                                    ownedAccessories = ownedAccessories,
                                    numberFormat = numberFormat,
                                    numberDecimals = numberDecimals,
                                    onBuyBlue = {
                                        if (clicks >= BigInteger.valueOf(10000L)) {
                                            clicks -= BigInteger.valueOf(10000L)
                                            blueBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("blue", true).apply()
                                        }
                                    },
                                    onBuyRed = {
                                        if (clicks >= BigInteger.valueOf(20000L)) {
                                            clicks -= BigInteger.valueOf(20000L)
                                            redBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("red", true).apply()
                                        }
                                    },
                                    onBuyYellow = {
                                        if (clicks >= BigInteger.valueOf(30000L)) {
                                            clicks -= BigInteger.valueOf(30000L)
                                            yellowBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("yellow", true).apply()
                                        }
                                    },
                                    onBuyGreen = {
                                        if (clicks >= BigInteger.valueOf(40000L)) {
                                            clicks -= BigInteger.valueOf(40000L)
                                            greenBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("green", true).apply()
                                        }
                                    },
                                    onBuyCustom = {
                                        if (clicks >= BigInteger.valueOf(50000L)) {
                                            clicks -= BigInteger.valueOf(50000L)
                                            customBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("custom", true).apply()
                                        }
                                    },
                                    onBuyGradient = {
                                        if (clicks >= BigInteger.valueOf(75000L)) {
                                            clicks -= BigInteger.valueOf(75000L)
                                            gradientBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("gradient", true).apply()
                                        }
                                    },
                                    onBuyCustomGradient = {
                                        if (clicks >= BigInteger.valueOf(100000L)) {
                                            clicks -= BigInteger.valueOf(100000L)
                                            customGradientBought = true
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("custom_gradient", true).apply()
                                        }
                                    },
                                    onBuyShopSkin = { id, price ->
                                        if (!ownedShopSkins.contains(id) && clicks >= price) {
                                            clicks -= price
                                            ownedShopSkins = ownedShopSkins + id
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("shop_skin_$id", true).apply()
                                        }
                                    },
                                    onBuyAccessory = { id, price ->
                                        if (!ownedAccessories.contains(id) && clicks >= price) {
                                            clicks -= price
                                            ownedAccessories = ownedAccessories + id
                                            prefs.edit().putString("clicks", clicks.toString()).putBoolean("accessory_$id", true).apply()
                                        }
                                    },

                                )
                            }
                            "factory" -> {
                                WipPanel(
                                    title = "FACTORY",
                                    text = "Factory is under construction.\n\nThe full worker, salary, equipment, cleanliness and production system will be added later.",
                                    textColor = textColor
                                )
                            }
                            "daily" -> {
                                DailyQuestPanel(
                                    textColor = textColor,
                                    accentColor = accentColor,
                                    targetClicks = dailyTarget,
                                    durationSeconds = dailyDuration,
                                    result = dailyQuestResult,
                                    rewardPercent = dailyRewardPercent,
                                    nextRefreshMillis = dailyNextRefresh,
                                    onStart = { dailyQuestRunning = true }
                                )
                            }

                            "skins" -> {
                                SkinsPanel(
                                    textColor = textColor,
                                    blueBought = blueBought,
                                    redBought = redBought,
                                    yellowBought = yellowBought,
                                    greenBought = greenBought,
                                    customBought = customBought,
                                    gradientBought = gradientBought,
                                    customGradientBought = customGradientBought,
                                    selectedSkin = selectedSkin,
                                    customColorInt = customColorInt,
                                    gradientColor1Int = gradientColor1Int,
                                    gradientColor2Int = gradientColor2Int,
                                    customGradientColor1Int = customGradientColor1Int,
                                    customGradientColor2Int = customGradientColor2Int,
                                    ownedShopSkins = ownedShopSkins,
                                    ownedAccessories = ownedAccessories,
                                    selectedShopSkin = selectedShopSkin,
                                    selectedAccessory = selectedAccessory,
                                    onSelectShopSkin = { id ->
                                        selectedShopSkin = id
                                        prefs.edit().putString("selected_shop_skin", id).apply()
                                    },
                                    onSelectAccessory = { id ->
                                        selectedAccessory = id
                                        prefs.edit().putString("selected_accessory", id).apply()
                                    },
                                    onSelectSkin = {
                                        selectedSkin = it
                                        prefs.edit().putString("selected_skin", it).apply()
                                    },
                                    onCustomColorChange = {
                                        customColorInt = it.toArgb()
                                        prefs.edit().putInt("custom_color", customColorInt).apply()
                                    },
                                    onGradientColor1Change = {
                                        gradientColor1Int = it.toArgb()
                                        prefs.edit().putInt("gradient_color_1", gradientColor1Int).apply()
                                    },
                                    onGradientColor2Change = {
                                        gradientColor2Int = it.toArgb()
                                        prefs.edit().putInt("gradient_color_2", gradientColor2Int).apply()
                                    },
                                    onCustomGradientColor1Change = {
                                        customGradientColor1Int = it.toArgb()
                                        prefs.edit().putInt("custom_gradient_color_1", customGradientColor1Int).apply()
                                    },
                                    onCustomGradientColor2Change = {
                                        customGradientColor2Int = it.toArgb()
                                        prefs.edit().putInt("custom_gradient_color_2", customGradientColor2Int).apply()
                                    }
                                )
                            }
                            "upgrades" -> {
                                UpgradePanel(
                                    clicks = clicks,
                                    textColor = textColor,
                                    autoclickers = autoclickers,
                                    autoSpeedLevel = autoSpeedLevel,
                                    clickMultiplier = clickMultiplier,
                                    numberFormat = numberFormat,
                                    numberDecimals = numberDecimals,
                                    onBuyAutoclickers = { amount ->
                                        var total = BigInteger.ZERO
                                        repeat(amount.coerceIn(1, 1000)) { index ->
                                            val current = autoclickers + BigInteger.valueOf(index.toLong())
                                            total += current.divide(BigInteger.TEN)
                                                .add(BigInteger.ONE)
                                                .multiply(BigInteger.valueOf(100L))
                                        }

                                        if (clicks >= total) {
                                            clicks -= total
                                            autoclickers += BigInteger.valueOf(amount.toLong())
                                            prefs.edit()
                                                .putString("clicks", clicks.toString())
                                                .putString("autoclickers", autoclickers.toString())
                                                .apply()
                                        }
                                    },
                                    onBuyAutoSpeed = { amount ->
                                        val pricePerLevel = autoclickers.divide(BigInteger.TEN)
                                            .multiply(BigInteger.valueOf(500L))
                                        val total = pricePerLevel.multiply(BigInteger.valueOf(amount.toLong()))

                                        if (autoclickers >= BigInteger.TEN && pricePerLevel > BigInteger.ZERO && clicks >= total) {
                                            clicks -= total
                                            autoSpeedLevel += amount
                                            prefs.edit()
                                                .putString("clicks", clicks.toString())
                                                .putInt("auto_speed_level", autoSpeedLevel)
                                                .apply()
                                        }
                                    },
                                    onBuyMultiplier = { amount ->
                                        var total = BigInteger.ZERO
                                        repeat(amount.coerceIn(1, 1000)) { index ->
                                            total += BigInteger.valueOf((clickMultiplier + index).toLong() * 100L)
                                        }

                                        if (clicks >= total) {
                                            clicks -= total
                                            clickMultiplier += amount
                                            prefs.edit()
                                                .putString("clicks", clicks.toString())
                                                .putInt("click_multiplier", clickMultiplier)
                                                .apply()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // =================================================
                // NEW BOTTOM NAVIGATION
                // =================================================

                BottomNavigationBar(
                    panel = panel,
                    isSettingsOpen = settingsOpen,
                    textColor = textColor,
                    accentColor = accentColor,
                    onPanelChange = {
                        panel = if (panel == it) null else it
                    },
                    onSettings = {
                        panel = null
                        settingsOpen = true
                    },
                    onHome = {
                        panel = null
                        settingsOpen = false
                    }
                )
            }
        }

        val bulbFlashAlpha by animateFloatAsState(
            targetValue = if (bulbFlash) 1f else 0f,
            animationSpec = tween(if (bulbFlash) 120 else 700),
            label = "bulbFlash"
        )
        if (bulbFlashAlpha > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = bulbFlashAlpha))
            )
        }
        LaunchedEffect(bulbFlash) {
            if (bulbFlash) {
                delay(1000)
                bulbFlash = false
            }
        }

        // =====================================================
        // SETTINGS
        // =====================================================

        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            SettingsScreen(
                textColor = textColor,
                accentColor = accentColor,
                theme = theme,
                onThemeChange = onThemeChange,
                onAccentColorChange = onAccentColorChange,
                customImageBought = customImageBought,
                customSoundBought = customSoundBought,
                customImageUri = customImageUri,
                customSoundUri = customSoundUri,
                customButtonBought = customButtonBought,
                customButtonUri = customButtonUri,
                customButtonEnabled = customButtonEnabled,
                clicks = clicks,
                onBuyImage = {
                    if (!customImageBought && clicks >= BigInteger("1000000000000000000")) {
                        clicks -= BigInteger("1000000000000000000")
                        customImageBought = true
                        prefs.edit().putString("clicks", clicks.toString()).putBoolean("custom_image_bought", true).apply()
                    }
                },
                onBuySound = {
                    if (!customSoundBought && clicks >= BigInteger("1000000000000000")) {
                        clicks -= BigInteger("1000000000000000")
                        customSoundBought = true
                        prefs.edit().putString("clicks", clicks.toString()).putBoolean("custom_sound_bought", true).apply()
                    }
                },
                onChooseImage = {
                    imagePicker.launch(arrayOf("image/*"))
                },
                onChooseSound = {
                    soundPicker.launch(arrayOf("audio/*"))
                },
                onBuyButton = {
                    if (!customButtonBought && clicks >= BigInteger("1000000000000000000")) {
                        clicks -= BigInteger("1000000000000000000")
                        customButtonBought = true
                        prefs.edit().putString("clicks", clicks.toString()).putBoolean("custom_button_bought", true).apply()
                    }
                },
                onChooseButton = { buttonPicker.launch(arrayOf("image/*")) },
                onCustomButtonEnabledChange = { enabled ->
                    customButtonEnabled = enabled
                    prefs.edit().putBoolean("custom_button_enabled", enabled).apply()
                },
                customBackgroundEnabled = customBackgroundEnabled,
                customSoundEnabled = customSoundEnabled,
                clickAnimationEnabled = clickAnimationEnabled,
                numberFormat = numberFormat,
                numberDecimals = numberDecimals,
                onCustomBackgroundEnabledChange = {
                    customBackgroundEnabled = it
                    prefs.edit().putBoolean("customBackgroundEnabled", it).apply()
                },
                onCustomSoundEnabledChange = {
                    customSoundEnabled = it
                    prefs.edit().putBoolean("customSoundEnabled", it).apply()
                },
                onClickAnimationEnabledChange = {
                    clickAnimationEnabled = it
                    prefs.edit().putBoolean("clickAnimationEnabled", it).apply()
                },
                onNumberFormatChange = {
                    numberFormat = it
                    prefs.edit().putString("numberFormat", it).apply()
                },
                onNumberDecimalsChange = { value ->
                    numberDecimals = value.coerceIn(0, 9)
                    prefs.edit().putInt("numberDecimals", numberDecimals).apply()
                },
                accountUsername = accountUsername,
                accountEmail = accountEmail,
                accountBadgeText = accountBadgeText,
                accountBadgeColor = accountBadgeColor,
                accountAvatarUri = accountAvatarUri,
                onChooseAvatar = {
                    if (accountUsername != null) {
                        avatarPicker.launch(arrayOf("image/*"))
                    }
                },
                onRegisterAccount = { email, username, password ->
                    if (!email.contains("@") || email.length > 160) {
                        "Enter a valid email address"
                    } else {
                        val normalized = accountPrefsKey(username)
                        when {
                            !normalized.matches(Regex("[a-z0-9_]{3,20}")) ->
                                "Username: 3-20 characters, a-z, 0-9 or _"
                            password.length < 6 ->
                                "Password must contain at least 6 characters"
                            else -> {
                                val result = cloudSignUp(email, username, password, prefs)
                                if (result == null) {
                                    accountUsername = username.trim()
                                    accountEmail = email.trim()
                                    isAdmin = cloudCheckAdmin(prefs)
                                    accountAvatarUri = null
                                }
                                result
                            }
                        }
                    }
                },
                onLoginAccount = { email, password ->
                    val (username, error) = cloudLogin(email, password, prefs)
                    if (error == null && username != null) {
                        accountUsername = username
                        accountEmail = email.trim()
                        isAdmin = cloudCheckAdmin(prefs)
                        accountBadgeText = prefs.getString("account_badge_text", null)
                        accountBadgeColor = prefs.getString("account_badge_color", null)
                        rankedElo = prefs.getInt("ranked_elo", 0)
                        rankedMatches = prefs.getInt("ranked_matches", 0)
                        accountAvatarUri = null
                        val cloudSave = cloudLoadGame(prefs)
                        if (cloudSave != null) {
                            cloudSave.optString("clicks").toBigIntegerOrNull()?.let { clicks = it }
                            cloudSave.optString("autoclickers").toBigIntegerOrNull()?.let { autoclickers = it }
                            if (cloudSave.has("auto_speed_level")) autoSpeedLevel = cloudSave.optInt("auto_speed_level", autoSpeedLevel)
                            if (cloudSave.has("click_multiplier")) clickMultiplier = cloudSave.optInt("click_multiplier", clickMultiplier)
                            if (cloudSave.has("selected_skin")) selectedSkin = cloudSave.optString("selected_skin", selectedSkin)
                            if (cloudSave.has("selected_shop_skin")) selectedShopSkin = cloudSave.optString("selected_shop_skin", selectedShopSkin)
                            if (cloudSave.has("selected_accessory")) selectedAccessory = cloudSave.optString("selected_accessory", selectedAccessory)
                        }
                        null
                    } else error
                },
                onLogoutAccount = {
                    accountUsername = null
                    accountEmail = null
                    isAdmin = false
                    accountBadgeText = null
                    accountBadgeColor = null
                    accountAvatarUri = null
                    rankedElo = 0
                    rankedMatches = 0
                    prefs.edit()
                        .remove("account_logged_username")
                        .remove("account_email")
                        .remove("cloud_access_token")
                        .remove("cloud_refresh_token")
                        .remove("cloud_user_id")
                        .remove("ranked_elo")
                        .remove("ranked_matches")
                        .apply()
                },
                isAdmin = isAdmin,
                onAdminAddClicks = { amount ->
                    if (isAdmin && amount.signum() > 0) {
                        clicks += amount
                        prefs.edit().putString("clicks", clicks.toString()).apply()
                    }
                },
                onAdminRevertClicks = { amount ->
                    if (isAdmin && amount.signum() > 0) {
                        clicks = (clicks - amount).max(BigInteger.ZERO)
                        prefs.edit().putString("clicks", clicks.toString()).apply()
                    }
                },
                onAdminSetReward = { reward ->
                    if (isAdmin) {
                        dailyRewardOverride = reward.coerceIn(0, 100)
                        prefs.edit().putInt("daily_reward_override", dailyRewardOverride).apply()
                    }
                },
                onAdminSetDuration = { duration ->
                    if (isAdmin) {
                        dailyDurationOverride = duration.coerceIn(10, 55)
                        val error = dailyError
                        dailyDuration = dailyDurationOverride
                        dailyTarget = (dailyDuration * 10 + error).coerceIn(50, 500)
                        prefs.edit().putInt("daily_duration_override", dailyDurationOverride).apply()
                    }
                },
                onAdminResetQuestSettings = {
                    if (isAdmin) {
                        prefs.edit().remove("daily_duration_override").remove("daily_reward_override").apply()
                        dailyDurationOverride = -1
                        dailyRewardOverride = -1
                        val quest = ensureDailyQuest(prefs)
                        dailyDuration = quest.first
                        dailyTarget = quest.second
                        dailyError = quest.third
                    }
                },
                onAdminGrantSkin = { skin ->
                    if (isAdmin) {
                        when (skin) {
                            "blue" -> blueBought = true
                            "red" -> redBought = true
                            "yellow" -> yellowBought = true
                            "green" -> greenBought = true
                            "custom" -> customBought = true
                            "gradient" -> gradientBought = true
                            "custom_gradient" -> customGradientBought = true
                            in shopSkinIds -> {
                                ownedShopSkins = ownedShopSkins + skin
                            }
                            in shopAccessoryIds -> {
                                ownedAccessories = ownedAccessories + skin
                            }
                        }
                        prefs.edit()
                            .putBoolean(skin, true)
                            .putBoolean("shop_skin_$skin", skin in shopSkinIds)
                            .putBoolean("accessory_$skin", skin in shopAccessoryIds)
                            .apply()
                    }
                },
                onAdminSetBadge = { username, badgeText, badgeColor ->
                    if (!isAdmin) "Admin panel is locked"
                    else {
                        val result = cloudSetBadge(username, badgeText, badgeColor, prefs)
                        if (result == null && username.trim().equals(accountUsername?.trim(), ignoreCase = true)) {
                            accountBadgeText = badgeText.trim()
                            accountBadgeColor = badgeColor.trim()
                            prefs.edit().putString("account_badge_text", accountBadgeText).putString("account_badge_color", accountBadgeColor).apply()
                        }
                        result
                    }
                },
                onAdminSetAccountStatus = { username, status, minutes ->
                    if (!isAdmin) "Admin panel is locked" else cloudSetAccountStatus(username, status, minutes, prefs)
                },
                onAdminResetDaily = {
                    if (isAdmin) {
                        generateDailyQuest(prefs)
                        val quest = ensureDailyQuest(prefs)
                        dailyDuration = quest.first
                        dailyTarget = quest.second
                        dailyError = quest.third
                        dailyNextRefresh = 0L
                        dailyQuestResult = null
                        dailyRewardPercent = 0
                    }
                },
                onBack = {
                    settingsOpen = false
                }
            )
        }

        // =====================================================
        // RANKED MODE
        // =====================================================

        AnimatedVisibility(
            visible = panel == "ranked",
            enter = fadeIn(animationSpec = tween(450)) +
                slideInVertically(initialOffsetY = { it / 8 }, animationSpec = tween(450)),
            exit = fadeOut(animationSpec = tween(250))
        ) {
            RankedModeScreen(
                textColor = textColor,
                accentColor = accentColor,
                loggedIn = accountUsername != null,
                username = accountUsername,
                elo = rankedElo,
                rankedMatches = rankedMatches,
                resultGain = rankedResultGain,
                onStartBattle = { mode ->
                    rankedBattleMode = mode
                    rankedResultGain = 0
                    rankedBattleRunning = true
                },
                onClose = { panel = null }
            )
        }

        if (rankedBattleRunning && accountUsername != null) {
            RankedBattleRunner(
                mode = rankedBattleMode,
                textColor = textColor,
                accentColor = accentColor,
                resultGain = rankedResultGain,
                onClickSound = { if (customSoundEnabled) playCustomSound(context, customSoundUri) },
                onMatchComplete = { won ->
                    val result = cloudRecordRankedResult(prefs, won, rankedBattleMode)
                    if (result != null) {
                        rankedElo = result.first
                        rankedMatches = result.second
                        rankedResultGain = result.third
                    } else {
                        rankedResultGain = 0
                    }
                },
                onExit = {
                    rankedBattleRunning = false
                    rankedResultGain = 0
                    panel = "ranked"
                }
            )
        }

        if (dailyQuestRunning) {
            DailyQuestRunner(
                targetClicks = dailyTarget,
                durationSeconds = dailyDuration,
                textColor = textColor,
                accentColor = accentColor,
                onClickSound = { if (customSoundEnabled) playCustomSound(context, customSoundUri) },
                onFinish = { passed ->
                    if (passed) {
                        val rewardPercent = if (dailyRewardOverride >= 0) dailyRewardOverride else (50 + dailyError).coerceIn(0, 100)
                        val reward = clicks.multiply(BigInteger.valueOf(rewardPercent.toLong())).divide(BigInteger.valueOf(100L))
                        clicks += reward
                        dailyRewardPercent = rewardPercent
                        dailyQuestResult = "passed"
                        prefs.edit()
                            .putString("clicks", clicks.toString())
                            .putString("daily_result", "passed")
                            .putInt("daily_reward_percent", rewardPercent)
                            .putLong("daily_next_refresh", System.currentTimeMillis() + 24L * 60L * 60L * 1000L)
                            .apply()
                    } else {
                        dailyRewardPercent = 0
                        dailyQuestResult = "failed"
                        prefs.edit()
                            .putString("daily_result", "failed")
                            .putInt("daily_reward_percent", 0)
                            .putLong("daily_next_refresh", System.currentTimeMillis() + 24L * 60L * 60L * 1000L)
                            .apply()
                    }
                    dailyNextRefresh = prefs.getLong("daily_next_refresh", 0L)
                    dailyReturnTransitionAlpha = 1f
                    dailyQuestRunning = false
                    panel = "daily"
                    dailyReturnTransition = true
                }
            )
        }

        if (dailyReturnTransition) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dailyReturnTransitionAlpha))
            )
        }

    }
}

// =============================================================
// RANKED MODE
// =============================================================

@Composable
fun RankedModeScreen(
    textColor: Color,
    accentColor: Color,
    loggedIn: Boolean,
    username: String?,
    elo: Int,
    rankedMatches: Int,
    resultGain: Int,
    onStartBattle: (String) -> Unit,
    onClose: () -> Unit
) {
    var section by remember { mutableStateOf("battle") }
    var leaderboardTab by remember { mutableStateOf("top100") }
    var leaderboard by remember { mutableStateOf<List<RankedLeaderboardEntry>>(emptyList()) }
    var loadingLeaderboard by remember { mutableStateOf(false) }
    var battleResult by remember { mutableStateOf<Boolean?>(null) }

    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("clckr", Context.MODE_PRIVATE)
    }

    LaunchedEffect(section, leaderboardTab, loggedIn) {
        if (loggedIn && section == "leaderboard") {
            loadingLeaderboard = true
            leaderboard = cloudLoadRankedLeaderboard(prefs, leaderboardTab == "top100")
            loadingLeaderboard = false
        }
    }

    BackHandler {
        onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 55.dp, bottom = 30.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "RANKED MODE",
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (loggedIn) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("ELO $elo", color = textColor, fontWeight = FontWeight.Bold)
                    Text("$rankedMatches MATCHES", color = textColor.copy(alpha = .6f), fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(25.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loggedIn) {
                SettingsTab(
                    text = "BATTLE",
                    selected = section == "battle",
                    textColor = textColor,
                    accentColor = accentColor,
                    onClick = { section = "battle" }
                )
                SettingsTab(
                    text = "LEADERBOARD",
                    selected = section == "leaderboard",
                    textColor = textColor,
                    accentColor = accentColor,
                    onClick = { section = "leaderboard" }
                )
            } else {
                RankedLockedTab("BATTLE", textColor)
                RankedLockedTab("LEADERBOARD", textColor)
            }
        }

        Spacer(Modifier.height(25.dp))

        if (!loggedIn) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔒", fontSize = 54.sp, color = textColor.copy(alpha = .45f))
                    Spacer(Modifier.height(12.dp))
                    Text("ACCOUNT REQUIRED", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Log in or create a clckr. account to use Ranked Mode.",
                        color = textColor.copy(alpha = .6f),
                        fontSize = 14.sp
                    )
                }
            }
        } else if (section == "battle") {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("PVE BATTLE", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Battle against a bot. Your ELO is saved to your account.", color = textColor.copy(alpha = .65f), fontSize = 13.sp)
                Spacer(Modifier.height(25.dp))

                RankedModeChoice(
                    title = "EASY",
                    details = "100 clicks · 20 seconds",
                    accentColor = accentColor,
                    textColor = textColor,
                    onClick = { onStartBattle("easy") }
                )
                RankedModeChoice(
                    title = "MEDIUM",
                    details = "200 clicks · 35 seconds",
                    accentColor = accentColor,
                    textColor = textColor,
                    onClick = { onStartBattle("medium") }
                )
                RankedModeChoice(
                    title = "HARD",
                    details = "300 clicks · 30 seconds",
                    accentColor = accentColor,
                    textColor = textColor,
                    onClick = { onStartBattle("hard") }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsTab(
                    text = "TOP 100",
                    selected = leaderboardTab == "top100",
                    textColor = textColor,
                    accentColor = accentColor,
                    onClick = { leaderboardTab = "top100" }
                )
                SettingsTab(
                    text = "GLOBAL",
                    selected = leaderboardTab == "global",
                    textColor = textColor,
                    accentColor = accentColor,
                    onClick = { leaderboardTab = "global" }
                )
            }
            Spacer(Modifier.height(15.dp))

            if (loadingLeaderboard) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accentColor)
                }
            } else if (leaderboard.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No ranked players yet.", color = textColor.copy(alpha = .6f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    leaderboard.forEachIndexed { index, player ->
                        val isMe = player.username.equals(username, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isMe) accentColor.copy(alpha = .12f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (isMe) accentColor.copy(alpha = .45f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = .25f),
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#${index + 1}",
                                color = textColor.copy(alpha = .55f),
                                modifier = Modifier.width(45.dp),
                                fontSize = 12.sp
                            )
                            Text(
                                player.username,
                                color = textColor,
                                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${player.elo} ELO", color = textColor, fontWeight = FontWeight.Bold)
                                Text("${player.matches} matches", color = textColor.copy(alpha = .55f), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.RankedLockedTab(
    text: String,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔒", fontSize = 12.sp)
            Spacer(Modifier.width(5.dp))
            Text(text, color = textColor.copy(alpha = .4f), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RankedModeChoice(
    title: String,
    details: String,
    accentColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(76.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = textColor
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = .45f))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(details, fontSize = 12.sp, color = textColor.copy(alpha = .65f))
        }
    }
}

@Composable
fun RankedBattleRunner(
    mode: String,
    textColor: Color,
    accentColor: Color,
    resultGain: Int,
    onClickSound: () -> Unit,
    onMatchComplete: suspend (Boolean) -> Unit,
    onExit: () -> Unit
) {
    val (target, duration) = when (mode) {
        "medium" -> 200 to 35
        "hard" -> 300 to 30
        else -> 100 to 20
    }

    var phase by remember { mutableStateOf("countdown") }
    var playerClicks by remember { mutableIntStateOf(0) }
    var botClicks by remember { mutableIntStateOf(0) }
    var playerClickWindowStart by remember { mutableLongStateOf(0L) }
    var playerClickWindowCount by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(duration) }
    var finished by remember { mutableStateOf(false) }
    var won by remember { mutableStateOf(false) }
    var transitionAlpha by remember { mutableFloatStateOf(1f) }
    var resultShown by remember { mutableStateOf(false) }
    var matchReported by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    val animatedGain = remember { Animatable(0f) }

    LaunchedEffect(resultGain, resultShown) {
        if (resultShown) {
            animatedGain.snapTo(0f)
            animatedGain.animateTo(
                resultGain.toFloat(),
                animationSpec = tween(
                    durationMillis = maxOf(350, resultGain * 80),
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        transitionAlpha = 1f
        var elapsed = 0L
        while (elapsed < 1000L) {
            delay(16L)
            elapsed += 16L
            transitionAlpha = (1f - elapsed / 1000f).coerceIn(0f, 1f)
        }
        transitionAlpha = 0f
        phase = "countdown2"
        delay(1000L)
        phase = "countdown1"
        delay(1000L)
        phase = "active"

        val end = System.currentTimeMillis() + duration * 1000L
        var lastBotTick = System.currentTimeMillis()
        val (botMinCps, botMaxCps) = when (mode) {
            "easy" -> 18 to 20
            "medium" -> 14 to 18
            else -> 10 to 14
        }
        var nextBotDelay = Random.nextLong(1000L / botMaxCps, 1000L / botMinCps + 1L)

        while (!finished) {
            val now = System.currentTimeMillis()
            remaining = ((end - now + 999L) / 1000L).toInt().coerceAtLeast(0)

            if (playerClicks >= target) {
                won = true
                finished = true
            } else if (botClicks >= target) {
                won = false
                finished = true
            } else if (remaining <= 0) {
                won = playerClicks >= botClicks
                finished = true
            } else {
                if (now - lastBotTick >= nextBotDelay) {
                    lastBotTick = now
                    if (botClicks < target) {
                        botClicks += 1
                    }
                    nextBotDelay = Random.nextLong(1000L / botMaxCps, 1000L / botMinCps + 1L)
                }
            }

            if (finished) {
                delay(700L)
                transitionAlpha = 0f
                if (!matchReported) {
                    matchReported = true
                    onMatchComplete(won)
                }
                resultShown = true
                break
            }
            delay(50L)
        }
    }

    if (resultShown) {
        BackHandler { }
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (won) "You Won!" else "You Lost!",
                    color = textColor,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                val displayedDelta = animatedGain.value.toInt()
                val eloSign = if (displayedDelta > 0) "+" else ""
                Text(
                    "ELO: $eloSign$displayedDelta",
                    color = textColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(30.dp))
                Button(
                    onClick = {
                        if (!exiting) {
                            exiting = true
                            onExit()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = accentTextColor(accentColor))
                ) {
                    Text("BACK TO BATTLE")
                }
            }
            if (transitionAlpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = transitionAlpha))
                )
            }
        }
    } else {
        BackHandler { }
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF181818)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 45.dp, bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${remaining}s", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(16.dp))

                RankedBattleButton(
                    title = "BOT",
                    value = botClicks,
                    target = target,
                    textColor = Color.White,
                    accentColor = accentColor.copy(alpha = .55f),
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.size(220.dp)
                )

                Spacer(Modifier.weight(1f))

                if (phase.startsWith("countdown")) {
                    val number = when (phase) {
                        "countdown" -> "3"
                        "countdown2" -> "2"
                        else -> "1"
                    }
                    Box(
                        modifier = Modifier.size(220.dp).clip(CircleShape).background(accentColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(number, color = accentTextColor(accentColor), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    RankedBattleButton(
                        title = "YOU",
                        value = playerClicks,
                        target = target,
                        textColor = Color.White,
                        accentColor = accentColor,
                        enabled = !finished,
                        onClick = {
                            if (!finished) {
                                val now = System.currentTimeMillis()
                                if (now - playerClickWindowStart >= 1000L) {
                                    playerClickWindowStart = now
                                    playerClickWindowCount = 0
                                }
                                if (playerClickWindowCount < 50) {
                                    playerClickWindowCount += 1
                                    playerClicks++
                                    onClickSound()
                                }
                            }
                        },
                        modifier = Modifier.size(220.dp)
                    )
                }
            }

            if (transitionAlpha > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = transitionAlpha)))
            }
        }
    }
}

@Composable
private fun RankedBattleButton(
    title: String,
    value: Int,
    target: Int,
    textColor: Color,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(3.dp, accentColor, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(value.toString(), color = textColor, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("/ $target", color = textColor.copy(alpha = .55f), fontSize = 12.sp)
        }
    }
}

// =============================================================
// WIP PANEL
// =============================================================

@Composable
fun WipPanel(title: String, text: String, textColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(25.dp))
        Text(text, color = textColor, fontSize = 16.sp)
    }
}

// =============================================================
// CLICK COUNTER
// =============================================================

@Composable
fun ClickCounter(
    clicks: BigInteger,
    format: String,
    enabled: Boolean,
    decimals: Int,
    textColor: Color
) {
    val formatted = formatClicks(clicks, format, decimals)
    if (!enabled) {
        Text(formatted, color = textColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    } else {
        AnimatedContent(
            targetState = formatted,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(180)) { it } + fadeIn(tween(180))) togetherWith
                        (slideOutVertically(animationSpec = tween(180)) { -it } + fadeOut(tween(180)))
            },
            label = "click_counter"
        ) { value ->
            Text(value, color = textColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
    }
}

fun formatClicks(value: BigInteger, mode: String, decimals: Int = 2): String {
    if (mode == "plain") return value.toString()
    if (value < BigInteger.valueOf(1000L)) return value.toString()

    val suffixes = arrayOf(
        "", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No",
        "Dc", "Ud", "Dd", "Td", "Qad", "Qid", "Sxd", "Spd", "Ocd", "Nod"
    )
    val precision = decimals.coerceIn(0, 9)
    var exp = (value.toString().length - 1) / 3
    var scale = BigInteger.TEN.pow(exp * 3)
    var scaled = value.toDouble() / scale.toDouble()

    // Round to the requested decimal places, then promote 9.99... to 10.0 of the next suffix.
    val factor = Math.pow(10.0, precision.toDouble())
    scaled = kotlin.math.round(scaled * factor) / factor
    if (scaled >= 1000.0 && exp + 1 < 1_000_000) {
        exp += 1
        scale = BigInteger.TEN.pow(exp * 3)
        scaled = value.toDouble() / scale.toDouble()
        scaled = kotlin.math.round(scaled * factor) / factor
    }

    val number = if (precision == 0) {
        scaled.toLong().toString()
    } else {
        String.format(Locale.US, "%.${precision}f", scaled)
    }
    val suffix = if (exp < suffixes.size) suffixes[exp] else "e${exp * 3}"
    if (mode == "letters") return number + suffix
    return number + "×10" + superscript(exp * 3)
}

fun superscript(number: Int): String = number.toString().map {
    when (it) {
        '0' -> '⁰'; '1' -> '¹'; '2' -> '²'; '3' -> '³'; '4' -> '⁴'
        '5' -> '⁵'; '6' -> '⁶'; '7' -> '⁷'; '8' -> '⁸'; else -> '⁹'
    }
}.joinToString("")

// =============================================================
// BOTTOM NAVIGATION BAR
// =============================================================

@Composable
fun BottomNavigationBar(
    panel: String?,
    isSettingsOpen: Boolean = false,
    textColor: Color,
    accentColor: Color,
    onPanelChange: (String) -> Unit,
    onSettings: () -> Unit,
    onHome: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    // 0 = extra menu, 1 = home, 2 = settings.
    val selectedIndex = when {
        isSettingsOpen -> 2
        menuOpen || panel != null -> 0
        else -> 1
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 6.dp)
    ) {
        val itemWidth = maxWidth / 3

        // Extra menu popup floating above the navigation bar
        AnimatedVisibility(
            visible = menuOpen,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 66.dp),
            enter = fadeIn(tween(220)) +
                    expandVertically(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Bottom
                    ),
            exit = fadeOut(tween(170)) +
                    shrinkVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Bottom
                    )
        ) {
            Column(
                modifier = Modifier
                    .width(210.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = .35f),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf(
                    "Ranked Mode" to "ranked",
                    "Daily Quests" to "daily",
                    "Factory" to "factory",
                    "Upgrades" to "upgrades",
                    "Skins" to "skins",
                    "Shop" to "shop"
                ).forEach { (title, id) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                menuOpen = false
                                onPanelChange(id)
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title,
                            color = textColor,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )

                    }
                }
            }
        }

        // Fixed bottom navigation bar background.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = .35f),
                    RoundedCornerShape(18.dp)
                )
        ) {
            val selectedX by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                ),
                label = "bottom_nav_selection"
            )

            Box(
                modifier = Modifier
                    .offset(x = selectedX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { menuOpen = !menuOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⋮",
                        color = if (selectedIndex == 0) accentTextColor(accentColor) else textColor,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            menuOpen = false
                            onHome()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "●",
                        color = if (selectedIndex == 1) accentTextColor(accentColor) else textColor,
                        fontSize = 24.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            menuOpen = false
                            onSettings()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⚙",
                        color = if (selectedIndex == 2) accentTextColor(accentColor) else textColor,
                        fontSize = 25.sp
                    )
                }
            }
        }
    }
}

// =============================================================
// SETTINGS SCREEN
// =============================================================

@Composable
fun SettingsScreen(
    textColor: Color,
    accentColor: Color,
    theme: String,
    onThemeChange: (String) -> Unit,
    onAccentColorChange: (Color) -> Unit,
    customImageBought: Boolean,
    customSoundBought: Boolean,
    customImageUri: String?,
    customSoundUri: String?,
    customButtonBought: Boolean,
    customButtonUri: String?,
    customButtonEnabled: Boolean,
    clicks: BigInteger,
    onBuyImage: () -> Unit,
    onBuySound: () -> Unit,
    onChooseImage: () -> Unit,
    onChooseSound: () -> Unit,
    onBuyButton: () -> Unit,
    onChooseButton: () -> Unit,
    onCustomButtonEnabledChange: (Boolean) -> Unit,
    customBackgroundEnabled: Boolean,
    customSoundEnabled: Boolean,
    clickAnimationEnabled: Boolean,
    numberFormat: String,
    numberDecimals: Int,
    onCustomBackgroundEnabledChange: (Boolean) -> Unit,
    onCustomSoundEnabledChange: (Boolean) -> Unit,
    onClickAnimationEnabledChange: (Boolean) -> Unit,
    onNumberFormatChange: (String) -> Unit,
    onNumberDecimalsChange: (Int) -> Unit,
    accountUsername: String?,
    accountEmail: String?,
    accountBadgeText: String?,
    accountBadgeColor: String?,
    accountAvatarUri: String?,
    onChooseAvatar: () -> Unit,
    onRegisterAccount: suspend (String, String, String) -> String?,
    onLoginAccount: suspend (String, String) -> String?,
    onLogoutAccount: () -> Unit,
    isAdmin: Boolean,
    onAdminAddClicks: (BigInteger) -> Unit,
    onAdminRevertClicks: (BigInteger) -> Unit,
    onAdminSetReward: (Int) -> Unit,
    onAdminSetDuration: (Int) -> Unit,
    onAdminResetQuestSettings: () -> Unit,
    onAdminGrantSkin: (String) -> Unit,
    onAdminSetBadge: suspend (String, String, String) -> String?,
    onAdminSetAccountStatus: suspend (String, String, Int?) -> String?,
    onAdminResetDaily: () -> Unit,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var section by remember { mutableStateOf("general") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 55.dp,
                bottom = 30.dp
            )
    ) {

        // =========================================================
        // HEADER
        // =========================================================

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SETTINGS",
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(30.dp))

        // =========================================================
        // SETTINGS TABS
        // =========================================================

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsTab(
                text = "GENERAL",
                selected = section == "general",
                textColor = textColor,
                accentColor = accentColor,
                onClick = { section = "general" }
            )

            SettingsTab(
                text = "MISCHIEVOUS",
                selected = section == "mischievous",
                textColor = textColor,
                accentColor = accentColor,
                onClick = { section = "mischievous" }
            )

        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            SettingsTab(
                text = "ACCOUNT",
                selected = section == "account",
                textColor = textColor,
                accentColor = accentColor,
                onClick = { section = "account" }
            )
        }

        Spacer(Modifier.height(30.dp))

        // =========================================================
        // GENERAL
        // =========================================================

        if (section == "general") {

            Text(
                "CLCKR.",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(7.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.tiktok.com/@clckrdev")
                                )
                            )
                        } catch (_: Exception) { }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("clckr. on tiktok", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://t.me/clckrdev")
                                )
                            )
                        } catch (_: Exception) { }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("clckr. on telegram", fontSize = 11.sp)
                }
            }


            Text(
                "GENERAL",
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(20.dp))

            // =====================================================
            // THEME
            // =====================================================

            Text("THEME", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            ThemeOption("Light", theme == "light", textColor, accentColor) { onThemeChange("light") }
            ThemeOption("Dark", theme == "dark", textColor, accentColor) { onThemeChange("dark") }
            ThemeOption("System", theme == "system", textColor, accentColor) { onThemeChange("system") }

            Spacer(Modifier.height(30.dp))

            // =====================================================
            // ACCENT COLOR
            // =====================================================

            Text("ACCENT COLOR", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Choose the color used by buttons and navigation.", color = textColor)
            Spacer(Modifier.height(15.dp))

            ThemeOption("Default", theme == "default", textColor, accentColor) {
                onThemeChange("default")
            }
            Spacer(Modifier.height(8.dp))

            ColorWheel(
                selectedColor = accentColor,
                onColorChange = onAccentColorChange
            )

            Spacer(Modifier.height(30.dp))

            Text("NUMBER FORMAT", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ThemeOption("1000000", numberFormat == "plain", textColor, accentColor) { onNumberFormatChange("plain") }
            ThemeOption("1M / 1.23M", numberFormat == "letters", textColor, accentColor) { onNumberFormatChange("letters") }
            ThemeOption("1×10⁶ / 1.23×10⁶", numberFormat == "math", textColor, accentColor) { onNumberFormatChange("math") }

            if (numberFormat != "plain") {
                Spacer(Modifier.height(14.dp))
                Text(
                    "DECIMAL PLACES: $numberDecimals",
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = numberDecimals.toFloat(),
                    onValueChange = { onNumberDecimalsChange(it.toInt()) },
                    valueRange = 0f..9f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "0 to 9 digits after the decimal point",
                    color = textColor.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            SettingSwitchRow(
                title = "CLICK COUNT ANIMATION",
                checked = clickAnimationEnabled,
                textColor = textColor,
                onCheckedChange = onClickAnimationEnabledChange
            )

            Spacer(Modifier.height(24.dp))

        }

        // =========================================================
        // ACCOUNT
        // =========================================================

        if (section == "account") {
            Text(
                "ACCOUNT",
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(14.dp))

            // Passport-style identity card.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, accentColor.copy(alpha = .45f))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "CLCKR. IDENTITY",
                                color = textColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "ACCOUNT PASSPORT",
                                color = textColor.copy(alpha = .55f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "CLCKR.",
                            color = accentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = textColor.copy(alpha = .12f))
                    Spacer(Modifier.height(16.dp))

                    if (accountUsername == null) {
                        Text(
                            "NO ACCOUNT",
                            color = textColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Create or sign in to your clckr. identity.",
                            color = textColor.copy(alpha = .65f),
                            fontSize = 13.sp
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(accentColor.copy(alpha = .12f))
                                    .border(1.dp, accentColor.copy(alpha = .35f), RoundedCornerShape(10.dp))
                                    .clickable(onClick = onChooseAvatar),
                                contentAlignment = Alignment.Center
                            ) {
                                if (accountAvatarUri != null) {
                                    val avatarBitmap = remember(accountAvatarUri, context) {
                                        try {
                                            context.contentResolver.openInputStream(Uri.parse(accountAvatarUri))?.use { stream ->
                                                BitmapFactory.decodeStream(stream)
                                            }
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                    if (avatarBitmap != null) {
                                        Image(
                                            bitmap = avatarBitmap.asImageBitmap(),
                                            contentDescription = "Account avatar",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        DefaultAccountAvatar(color = textColor)
                                    }
                                } else {
                                    DefaultAccountAvatar(color = textColor)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("NAME", color = textColor.copy(alpha = .5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(accountUsername, color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (!accountBadgeText.isNullOrBlank()) {
                                    val badgeColor = accountBadgeColor?.let { runCatching { Color(AndroidColor.parseColor(it)) }.getOrNull() } ?: accentColor
                                    Spacer(Modifier.height(5.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = badgeColor.copy(alpha = .16f),
                                        border = BorderStroke(1.dp, badgeColor.copy(alpha = .45f))
                                    ) {
                                        Text(
                                            accountBadgeText!!,
                                            color = badgeColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(7.dp))
                                Text("ID", color = textColor.copy(alpha = .5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    accountPrefsKey(accountUsername).uppercase(),
                                    color = textColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = textColor.copy(alpha = .12f))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STATUS     ${if (accountUsername == null) "NOT REGISTERED" else "ACTIVE"}",
                        color = if (accountUsername == null) textColor.copy(alpha = .55f) else accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            if (accountUsername == null) {
                var mode by remember { mutableStateOf("login") }
                var email by remember { mutableStateOf("") }
                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var confirmPassword by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTab(text = "LOG IN", selected = mode == "login", textColor = textColor, accentColor = accentColor, onClick = { mode = "login"; error = null })
                    SettingsTab(text = "REGISTER", selected = mode == "register", textColor = textColor, accentColor = accentColor, onClick = { mode = "register"; error = null })
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(value = email, onValueChange = { email = it.take(160) }, label = { Text("EMAIL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                if (mode == "register") {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.take(20) },
                        label = { Text("USERNAME") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(value = password, onValueChange = { password = it.take(64) }, label = { Text("PASSWORD") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (mode == "register") {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it.take(64) }, label = { Text("CONFIRM PASSWORD") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                if (error != null) { Spacer(Modifier.height(8.dp)); Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Spacer(Modifier.height(14.dp))
                AccentButton(text = if (mode == "login") "LOG IN" else "CREATE ACCOUNT", enabled = email.isNotBlank() && password.isNotBlank() && (mode == "login" || (username.isNotBlank() && confirmPassword.isNotBlank())), accentColor = accentColor, onClick = {
                    coroutineScope.launch {
                        if (mode == "register" && password != confirmPassword) {
                            error = "Passwords do not match"
                        } else {
                            error = try {
                                if (mode == "login") {
                                    onLoginAccount(email, password)
                                } else {
                                    onRegisterAccount(email, username, password)
                                }
                            } catch (e: Exception) {
                                "Account request error: ${e.message ?: e::class.java.simpleName}"
                            }
                        }
                    }
                })
            } else {
                AccentButton(text = "LOG OUT", enabled = true, accentColor = accentColor, onClick = onLogoutAccount)
                Spacer(Modifier.height(8.dp))
                Text("Google linking will be added later.", color = textColor.copy(alpha = .55f), fontSize = 12.sp)
            }
        }

        // =========================================================
        // MISCHIEVOUS
        // =========================================================

        if (section == "mischievous") {

            Text(
                "MISCHIEVOUS",
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))
            Text("Experimental and custom stuff.", color = textColor)
            Spacer(Modifier.height(25.dp))

            // =====================================================
            // CUSTOM IMAGE
            // =====================================================

            Text("CUSTOM BACKGROUND", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Use your own image as the full-screen game background.", color = textColor)
            Spacer(Modifier.height(12.dp))

            if (!customImageBought) {
                AccentButton(
                    text = "UNLOCK — 1 QUINTILLION CLICKS",
                    enabled = clicks >= BigInteger("1000000000000000000"),
                    accentColor = accentColor,
                    onClick = onBuyImage
                )
            } else {
                AccentButton(
                    text = if (customImageUri == null) "CHOOSE IMAGE" else "CHANGE IMAGE",
                    enabled = true,
                    accentColor = accentColor,
                    onClick = onChooseImage
                )
            }

            SettingSwitchRow(
                title = "ENABLE CUSTOM BACKGROUND",
                checked = customBackgroundEnabled,
                textColor = textColor,
                onCheckedChange = onCustomBackgroundEnabledChange
            )

            Spacer(Modifier.height(30.dp))

            // =====================================================
            // CUSTOM SOUND
            // =====================================================

            Text("CUSTOM CLICK SOUND", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Replace the default click sound.", color = textColor)
            Spacer(Modifier.height(12.dp))

            if (!customSoundBought) {
                AccentButton(
                    text = "UNLOCK — 1 QUADRILLION CLICKS",
                    enabled = clicks >= BigInteger("1000000000000000"),
                    accentColor = accentColor,
                    onClick = onBuySound
                )
            } else {
                AccentButton(
                    text = if (customSoundUri == null) "CHOOSE SOUND" else "CHANGE SOUND",
                    enabled = true,
                    accentColor = accentColor,
                    onClick = onChooseSound
                )
            }

            SettingSwitchRow(
                title = "ENABLE CUSTOM SOUND",
                checked = customSoundEnabled,
                textColor = textColor,
                onCheckedChange = onCustomSoundEnabledChange
            )

            Spacer(Modifier.height(30.dp))

            // =====================================================
            // CUSTOM BUTTON
            // =====================================================

            Text("CUSTOM BUTTON", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Replace the main click button with your own image.", color = textColor)
            Spacer(Modifier.height(12.dp))
            if (!customButtonBought) {
                AccentButton(
                    text = "UNLOCK — 1 QUINTILLION CLICKS",
                    enabled = clicks >= BigInteger("1000000000000000000"),
                    accentColor = accentColor,
                    onClick = onBuyButton
                )
            } else {
                AccentButton(
                    text = if (customButtonUri == null) "CHOOSE BUTTON IMAGE" else "CHANGE BUTTON IMAGE",
                    enabled = true,
                    accentColor = accentColor,
                    onClick = onChooseButton
                )
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "ENABLE CUSTOM BUTTON",
                    checked = customButtonEnabled && customButtonUri != null,
                    textColor = textColor,
                    onCheckedChange = { enabled ->
                        onCustomButtonEnabledChange(enabled)
                    }
                )
            }

            Spacer(Modifier.height(35.dp))

            // =====================================================
            // ADMIN PANEL
            // =====================================================

            Text("ADMIN PANEL", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Contest/admin tools. Ranked mode is never modified by these tools.", color = textColor)
            Spacer(Modifier.height(12.dp))

            if (!isAdmin) {
                Text("Admin access is controlled by the server.", color = textColor.copy(alpha = .6f), fontSize = 12.sp)
            } else {
                AdminPanel(
                    textColor = textColor,
                    accentColor = accentColor,
                    onAddClicks = onAdminAddClicks,
                    onRevertClicks = onAdminRevertClicks,
                    onSetReward = onAdminSetReward,
                    onSetDuration = onAdminSetDuration,
                    onResetQuestSettings = onAdminResetQuestSettings,
                    onGrantSkin = onAdminGrantSkin,
                    onSetBadge = onAdminSetBadge,
                    onSetAccountStatus = onAdminSetAccountStatus,
                    onResetDaily = onAdminResetDaily
                )
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    textColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = textColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// =============================================================
// ACCENT BUTTON
// =============================================================

@Composable
fun AccentButton(
    text: String,
    enabled: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = accentColor,
            contentColor = accentTextColor(accentColor),
            disabledContainerColor = accentColor.copy(alpha = 0.35f),
            disabledContentColor = accentTextColor(accentColor).copy(alpha = 0.7f)
        )
    ) {
        Text(text = text, color = accentTextColor(accentColor))
    }
}

// =============================================================
// DEFAULT ACCOUNT AVATAR
// =============================================================

@Composable
fun DefaultAccountAvatar(color: Color) {
    Canvas(Modifier.fillMaxSize().padding(14.dp)) {
        val cx = size.width / 2f
        val headRadius = size.minDimension * .16f
        val headY = size.height * .25f
        val bodyTop = size.height * .43f
        val bodyBottom = size.height * .78f
        val stroke = size.minDimension * .16f

        drawCircle(color, headRadius, Offset(cx, headY))
        drawLine(
            color,
            Offset(cx, bodyTop),
            Offset(cx, bodyBottom),
            strokeWidth = stroke
        )
        drawLine(
            color,
            Offset(cx, size.height * .55f),
            Offset(size.width * .25f, size.height * .68f),
            strokeWidth = stroke
        )
        drawLine(
            color,
            Offset(cx, size.height * .55f),
            Offset(size.width * .75f, size.height * .68f),
            strokeWidth = stroke
        )
        drawLine(
            color,
            Offset(cx, bodyBottom),
            Offset(size.width * .30f, size.height * .94f),
            strokeWidth = stroke
        )
        drawLine(
            color,
            Offset(cx, bodyBottom),
            Offset(size.width * .70f, size.height * .94f),
            strokeWidth = stroke
        )
    }
}

// =============================================================
// SETTINGS TAB
// =============================================================

@Composable
fun RowScope.SettingsTab(
    text: String,
    selected: Boolean,
    textColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) accentColor
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 1.dp,
                color = if (selected) accentColor
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) accentTextColor(accentColor) else textColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// =============================================================
// THEME OPTION
// =============================================================

@Composable
fun ThemeOption(
    text: String,
    selected: Boolean,
    textColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = accentColor)
        )

        Spacer(Modifier.width(8.dp))

        Text(text, color = textColor)
    }
}

// =============================================================
// SHOP SKIN MODELS
// =============================================================

@Composable
fun RadarModel(modifier: Modifier, color: Color, clickTrigger: Int) {
    val angle = remember { Animatable(0f) }
    var queuedClicks by remember { mutableIntStateOf(0) }

    LaunchedEffect(clickTrigger) {
        if (clickTrigger > 0) queuedClicks++
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (queuedClicks > 0) {
                queuedClicks--
                angle.animateTo(
                    angle.value + 360f,
                    animationSpec = tween(900, easing = LinearEasing)
                )
            } else {
                delay(16)
            }
        }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.46f
        val caseColor = Color(0xFF30343A)
        val caseEdge = Color(0xFF111317)
        val screen = Color(0xFF151A1F)
        val grid = Color(0xFF4C545C)
        val stroke = size.minDimension * 0.018f

        drawCircle(caseColor, radius * 1.08f, Offset(cx, cy))
        drawCircle(caseEdge, radius * 1.01f, Offset(cx, cy), style = Stroke(stroke * 1.5f))
        drawCircle(screen, radius, Offset(cx, cy))
        drawCircle(grid, radius * .67f, Offset(cx, cy), style = Stroke(stroke))
        drawCircle(grid, radius * .34f, Offset(cx, cy), style = Stroke(stroke))
        drawLine(grid, Offset(cx-radius,cy), Offset(cx+radius,cy), strokeWidth=stroke)
        drawLine(grid, Offset(cx,cy-radius), Offset(cx,cy+radius), strokeWidth=stroke)

        val a = Math.toRadians((angle.value - 90f).toDouble())
        val ex = cx + cos(a).toFloat() * radius * .94f
        val ey = cy + sin(a).toFloat() * radius * .94f
        drawLine(color.copy(alpha=.20f), Offset(cx,cy), Offset(ex,ey), strokeWidth=size.minDimension*.09f)
        drawLine(color, Offset(cx,cy), Offset(ex,ey), strokeWidth=size.minDimension*.035f)
        drawCircle(color, size.minDimension*.045f, Offset(cx,cy))
        listOf(.56f to -135f, .72f to 18f, .43f to 120f).forEach { (r,a0) ->
            val rad=Math.toRadians(a0.toDouble())
            drawCircle(color,size.minDimension*.028f,Offset(cx+cos(rad).toFloat()*radius*r,cy+sin(rad).toFloat()*radius*r))
        }
    }
}

@Composable
fun SpeedometerModel(modifier: Modifier, color: Color, clickTrigger: Int) {
    var speed by remember { mutableIntStateOf(0) }
    val limitHit = remember { Animatable(0f) }

    LaunchedEffect(clickTrigger) {
        if (clickTrigger > 0) {
            if (speed < 220) speed = (speed + 1).coerceAtMost(220)
            else {
                limitHit.snapTo(0f)
                limitHit.animateTo(.12f, tween(70))
                limitHit.animateTo(0f, tween(110))
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (speed > 0) speed = (speed - 5).coerceAtLeast(0)
        }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * .49f
        val caseColor = Color(0xFF30343A)
        val caseEdge = Color(0xFF111317)
        val dial = Color(0xFF252A30)
        val tick = Color(0xFFB0B6BD)
        val stroke = size.minDimension * .018f
        val startAngle = 135f
        val sweepAngle = 216f
        val arcInset = radius * .07f
        val arcTopLeft = Offset(cx - radius + arcInset, cy - radius + arcInset)
        val arcSize = androidx.compose.ui.geometry.Size((radius - arcInset) * 2f, (radius - arcInset) * 2f)

        drawCircle(caseColor, radius * 1.08f, Offset(cx, cy))
        drawCircle(caseEdge, radius * 1.01f, Offset(cx, cy), style = Stroke(stroke * 1.5f))
        drawCircle(dial, radius, Offset(cx, cy))
        drawArc(tick.copy(alpha = .55f), startAngle, sweepAngle, false, arcTopLeft, arcSize, style = Stroke(stroke * 1.3f))
        drawArc(color, startAngle, sweepAngle, false, arcTopLeft, arcSize, style = Stroke(stroke * 1.7f))

        val labels = listOf(0, 20, 40, 60, 80, 100, 120, 140, 160, 180, 200, 220)
        labels.forEachIndexed { index, value ->
            val angle = Math.toRadians(startAngle + index * (sweepAngle / 11.0))
            val outer = radius * .87f
            val inner = radius * .70f
            drawLine(tick, Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer), strokeWidth = stroke * 1.25f)
            val tx = cx + cos(angle).toFloat() * radius * .57f
            val ty = cy + sin(angle).toFloat() * radius * .57f
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                setColor(android.graphics.Color.WHITE)
                alpha = 255
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = size.minDimension * .050f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            drawContext.canvas.nativeCanvas.drawText(value.toString(), tx, ty + paint.textSize * .34f, paint)
        }

        val needleAngle = startAngle + (speed / 220.0) * sweepAngle - limitHit.value * 18.0
        val a = Math.toRadians(needleAngle)
        val nx = cx + cos(a).toFloat() * radius * .68f
        val ny = cy + sin(a).toFloat() * radius * .68f
        drawLine(Color.Black.copy(alpha = .4f), Offset(cx + 2f, cy + 2f), Offset(nx + 2f, ny + 2f), strokeWidth = size.minDimension * .05f)
        drawLine(color, Offset(cx, cy), Offset(nx, ny), strokeWidth = size.minDimension * .042f)
        drawCircle(Color(0xFF111317), size.minDimension * .07f, Offset(cx, cy))
        drawCircle(color, size.minDimension * .038f, Offset(cx, cy))

        drawRoundRect(Color(0xFF171A1D), Offset(cx - radius * .25f, cy + radius * .36f),
            androidx.compose.ui.geometry.Size(radius * .50f, radius * .16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * .04f))
        val readoutPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            setColor(android.graphics.Color.WHITE)
            alpha = 255
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.minDimension * .070f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.drawText("$speed", cx, cy + radius * .465f, readoutPaint)
    }
}

@Composable
fun LightBulbModel(modifier: Modifier, color: Color, clickTrigger: Int, onSpark: () -> Unit) {
    var level by remember { mutableIntStateOf(0) }
    var extraClicks by remember { mutableIntStateOf(0) }
    var spark by remember { mutableStateOf(false) }
    var decayJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(clickTrigger) {
        if (clickTrigger <= 0) return@LaunchedEffect
        decayJob?.cancel()
        if (level < 50) {
            level = (level + 1).coerceAtMost(50)
        } else {
            extraClicks++
            if (extraClicks >= 10) {
                extraClicks = 0
                spark = true
                delay(180)
                spark = false
                level = 0
                extraClicks = 0
                onSpark()
            }
        }
        decayJob = launch {
            delay(700)
            while (level > 0) {
                level = (level - 1).coerceAtLeast(0)
                delay(250)
            }
        }
    }

    val stage = when {
        level <= 0 -> 0
        level <= 10 -> 1
        level <= 20 -> 2
        level <= 30 -> 3
        level <= 40 -> 4
        else -> 5
    }
    val glowAlpha = floatArrayOf(.06f, .12f, .20f, .30f, .42f, .56f)[stage]

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val bulbRadius = size.minDimension * .52f
        val bulbCenter = Offset(cx, size.height * .43f)
        val glass = Color(0xFFB8C0C8)
        val baseTop = size.height * .78f

        // Multiple translucent layers create the light bloom.
        drawCircle(color.copy(alpha = glowAlpha * .16f), bulbRadius * 2.05f, bulbCenter)
        drawCircle(color.copy(alpha = glowAlpha * .25f), bulbRadius * 1.72f, bulbCenter)
        drawCircle(color.copy(alpha = glowAlpha * .36f), bulbRadius * 1.45f, bulbCenter)
        drawCircle(color.copy(alpha = glowAlpha * .52f), bulbRadius * 1.22f, bulbCenter)
        drawCircle(glass, bulbRadius * .91f, bulbCenter)
        drawCircle(color.copy(alpha = .10f + stage * .08f), bulbRadius * .82f, bulbCenter)

        val filamentAlpha = .25f + stage * .14f
        drawLine(color.copy(alpha = filamentAlpha), Offset(cx - bulbRadius * .28f, bulbCenter.y), Offset(cx - bulbRadius * .10f, bulbCenter.y + bulbRadius * .18f), strokeWidth = size.minDimension * .025f)
        drawLine(color.copy(alpha = filamentAlpha), Offset(cx - bulbRadius * .10f, bulbCenter.y + bulbRadius * .18f), Offset(cx + bulbRadius * .12f, bulbCenter.y - bulbRadius * .08f), strokeWidth = size.minDimension * .025f)
        drawLine(color.copy(alpha = filamentAlpha), Offset(cx + bulbRadius * .12f, bulbCenter.y - bulbRadius * .08f), Offset(cx + bulbRadius * .28f, bulbCenter.y + bulbRadius * .12f), strokeWidth = size.minDimension * .025f)

        drawRoundRect(Color(0xFF686D73), Offset(cx - bulbRadius * .40f, baseTop), androidx.compose.ui.geometry.Size(bulbRadius * .80f, size.height * .16f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bulbRadius * .12f))
        for (i in 1..3) drawLine(Color(0xFF34383D), Offset(cx - bulbRadius * .34f, baseTop + size.height * .04f * i), Offset(cx + bulbRadius * .34f, baseTop + size.height * .04f * i), strokeWidth = size.minDimension * .014f)

        if (spark) {
            for (a0 in listOf(-160f, -120f, -90f, -60f, -20f, 20f, 55f, 120f)) {
                val rad = Math.toRadians(a0.toDouble())
                drawLine(color, Offset(bulbCenter.x + cos(rad).toFloat() * bulbRadius * 1.12f, bulbCenter.y + sin(rad).toFloat() * bulbRadius * 1.12f), Offset(bulbCenter.x + cos(rad).toFloat() * bulbRadius * 1.60f, bulbCenter.y + sin(rad).toFloat() * bulbRadius * 1.60f), strokeWidth = size.minDimension * .025f)
            }
        }
    }
}

@Composable
fun RecordModel(modifier: Modifier, color: Color, clickTrigger: Int) {
    val kick = remember { Animatable(0f) }
    val rotation = rememberInfiniteTransition(label = "recordRotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "recordSpin"
    )

    LaunchedEffect(clickTrigger) {
        if (clickTrigger > 0) {
            kick.snapTo(0f)
            kick.animateTo(-12f, tween(90, easing = LinearEasing))
            kick.animateTo(0f, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * .49f
        val caseColor = Color(0xFF30343A)
        val caseEdge = Color(0xFF111317)

        drawRoundRect(caseColor, Offset(size.width * .005f, size.height * .005f), androidx.compose.ui.geometry.Size(size.width * .99f, size.height * .99f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .08f))
        drawRoundRect(caseEdge, Offset(size.width * .025f, size.height * .025f), androidx.compose.ui.geometry.Size(size.width * .95f, size.height * .95f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .06f), style = Stroke(size.minDimension * .015f))

        rotate(rotation.value + kick.value, pivot = Offset(cx, cy)) {
            drawCircle(Color(0xFF17191C), radius, Offset(cx, cy))
            for (i in 1..10) drawCircle(Color(0xFF3A3D42), radius * (i / 11f), Offset(cx, cy), style = Stroke(size.minDimension * .009f))
            drawCircle(color, radius * .23f, Offset(cx, cy))
            drawCircle(Color(0xFF101215), radius * .065f, Offset(cx, cy))
            // Small offset label replaces the previous floating ball.
            drawCircle(color.copy(alpha = .85f), radius * .045f, Offset(cx + radius * .38f, cy - radius * .12f))
        }

        // Fixed tonearm: it no longer rotates with the record.
        drawLine(Color(0xFF8A9097), Offset(size.width * .78f, size.height * .24f), Offset(size.width * .78f, size.height * .52f), strokeWidth = size.minDimension * .025f)
        drawLine(Color(0xFF8A9097), Offset(size.width * .78f, size.height * .24f), Offset(size.width * .64f, size.height * .30f), strokeWidth = size.minDimension * .025f)
        drawCircle(caseEdge, size.minDimension * .035f, Offset(size.width * .78f, size.height * .24f))
    }
}

@Composable
fun WatchModel(
    modifier: Modifier = Modifier,
    dark: Boolean,
    elapsedSeconds: Long
) {
    val caseColor = if (dark) Color(0xFFD6D9DE) else Color(0xFF5F6368)
    val caseDark = if (dark) Color(0xFF9EA3AA) else Color(0xFF34373B)
    val dialColor = if (dark) Color(0xFF17191C) else Color(0xFFF5F5F5)
    val handColor = if (dark) Color.White else Color(0xFF202124)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * .34f
        val strapWidth = radius * .62f
        val strapHeight = size.height * .30f
        drawRoundRect(caseDark, Offset(cx - strapWidth / 2f, cy - radius - strapHeight),
            androidx.compose.ui.geometry.Size(strapWidth, strapHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(strapWidth * .16f))
        drawRoundRect(caseDark, Offset(cx - strapWidth / 2f, cy + radius),
            androidx.compose.ui.geometry.Size(strapWidth, strapHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(strapWidth * .16f))
        drawCircle(caseColor, radius * 1.16f, Offset(cx, cy))
        drawCircle(caseDark, radius * 1.02f, Offset(cx, cy))
        drawCircle(dialColor, radius * .91f, Offset(cx, cy))
        drawRoundRect(caseDark, Offset(cx + radius * 1.02f, cy - radius * .18f),
            androidx.compose.ui.geometry.Size(radius * .28f, radius * .36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * .08f))
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30 - 90).toDouble())
            val outer = radius * .78f
            val inner = if (i % 3 == 0) radius * .62f else radius * .68f
            drawLine(handColor,
                Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                strokeWidth = if (i % 3 == 0) radius * .07f else radius * .035f)
        }
        val seconds = elapsedSeconds % 60L
        val minutes = (elapsedSeconds / 60L) % 60L
        val hours = (elapsedSeconds / 3600L) % 12L
        val secondAngle = Math.toRadians(seconds * 6.0 - 90.0)
        val minuteAngle = Math.toRadians((minutes + seconds / 60.0) * 6.0 - 90.0)
        val hourAngle = Math.toRadians((hours + minutes / 60.0 + seconds / 3600.0) * 30.0 - 90.0)
        drawLine(handColor, Offset(cx, cy), Offset(cx + cos(hourAngle).toFloat() * radius * .48f, cy + sin(hourAngle).toFloat() * radius * .48f), strokeWidth = radius * .065f)
        drawLine(handColor, Offset(cx, cy), Offset(cx + cos(minuteAngle).toFloat() * radius * .62f, cy + sin(minuteAngle).toFloat() * radius * .62f), strokeWidth = radius * .045f)
        drawLine(Color(0xFFE53935), Offset(cx, cy), Offset(cx + cos(secondAngle).toFloat() * radius * .70f, cy + sin(secondAngle).toFloat() * radius * .70f), strokeWidth = radius * .025f)
        drawCircle(handColor, radius * .07f, Offset(cx, cy))
    }
}

// =============================================================
// SHOP
// =============================================================

@Composable
fun ShopPanel(
    clicks: BigInteger,
    textColor: Color,
    blueBought: Boolean,
    redBought: Boolean,
    yellowBought: Boolean,
    greenBought: Boolean,
    customBought: Boolean,
    gradientBought: Boolean,
    customGradientBought: Boolean,
    ownedShopSkins: Set<String>,
    ownedAccessories: Set<String>,
    numberFormat: String,
    numberDecimals: Int,
    onBuyBlue: () -> Unit,
    onBuyRed: () -> Unit,
    onBuyYellow: () -> Unit,
    onBuyGreen: () -> Unit,
    onBuyCustom: () -> Unit,
    onBuyGradient: () -> Unit,
    onBuyCustomGradient: () -> Unit,
    onBuyShopSkin: (String, BigInteger) -> Unit,
    onBuyAccessory: (String, BigInteger) -> Unit
) {
    var tab by remember { mutableStateOf("colors") }

    val shopSkins = listOf(
        "watch" to BigInteger("1000000000"),
        "radar" to BigInteger("5000000000"),
        "speedometer" to BigInteger("25000000000"),
        "bulb" to BigInteger("100000000000"),
        "record" to BigInteger("500000000000")
    )
    /* val accessories = listOf(
        "chain" to BigInteger("1000000000000"),
        "crown" to BigInteger("1000000000000000000000"),
        "ribbon" to BigInteger("5000000000000"),
        "halo" to BigInteger("25000000000000"),
        "sparkles" to BigInteger("100000000000000")
    ) */

    val skinNames = mapOf(
        "watch" to "Watch", "radar" to "Radar", "speedometer" to "Speedometer",
        "bulb" to "Light Bulb", "record" to "Vinyl Record"
    )
    /* val accessoryNames = mapOf(
        "chain" to "Chain", "crown" to "Crown", "ribbon" to "Ribbon",
        "halo" to "Halo", "sparkles" to "Sparkles"
    ) */

    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("SHOP", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("PURCHASE", color = textColor.copy(alpha = 0.65f), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("colors" to "COLORS", "skins" to "SKINS" /*, "accessories" to "ACCESSORIES"*/).forEach { (id, title) ->
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (tab == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { tab = id }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(title, color = if (tab == id) accentTextColor(MaterialTheme.colorScheme.primary) else textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(15.dp))

        when (tab) {
            "colors" -> {
                ShopItem("Blue", BigInteger("10000"), blueBought, clicks, textColor, numberFormat, numberDecimals, onBuyBlue)
                ShopItem("Red", BigInteger("20000"), redBought, clicks, textColor, numberFormat, numberDecimals, onBuyRed)
                ShopItem("Yellow", BigInteger("30000"), yellowBought, clicks, textColor, numberFormat, numberDecimals, onBuyYellow)
                ShopItem("Green", BigInteger("40000"), greenBought, clicks, textColor, numberFormat, numberDecimals, onBuyGreen)
                ShopItem("Custom Color", BigInteger("50000"), customBought, clicks, textColor, numberFormat, numberDecimals, onBuyCustom)
                ShopItem("Gradient", BigInteger("75000"), gradientBought, clicks, textColor, numberFormat, numberDecimals, onBuyGradient)
                ShopItem("Custom Gradient", BigInteger("100000"), customGradientBought, clicks, textColor, numberFormat, numberDecimals, onBuyCustomGradient)
            }
            "skins" -> {
                shopSkins.forEach { (id, price) ->
                    CosmeticShopItem(
                        name = skinNames[id] ?: id, price = price, bought = ownedShopSkins.contains(id),
                        clicks = clicks, textColor = textColor, numberFormat = numberFormat, numberDecimals = numberDecimals,
                        onBuy = { onBuyShopSkin(id, price) }
                    )
                }
            }
            /*
            "accessories" -> {
                accessories.forEach { (id, price) ->
                    CosmeticShopItem(
                        name = accessoryNames[id] ?: id, price = price, bought = ownedAccessories.contains(id),
                        clicks = clicks, textColor = textColor,
                        onBuy = { onBuyAccessory(id, price) }
                    )
                }
            }
            */
        }
    }
}

// =============================================================
// SHOP ITEM
// =============================================================

@Composable
fun ShopItem(
    name: String,
    price: BigInteger,
    bought: Boolean,
    clicks: BigInteger,
    textColor: Color,
    numberFormat: String,
    numberDecimals: Int,
    onBuy: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = textColor, fontWeight = FontWeight.Bold)
            Text(if (bought) "Owned" else "${formatClicks(price, numberFormat, numberDecimals)} clicks", color = textColor)
        }

        if (!bought) {
            AccentButtonSmall(
                text = "BUY",
                enabled = clicks >= price,
                onClick = onBuy
            )
        } else {
            Text("OWNED", color = textColor, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CosmeticShopItem(
    name: String,
    price: BigInteger,
    bought: Boolean,
    clicks: BigInteger,
    textColor: Color,
    numberFormat: String,
    numberDecimals: Int,
    onBuy: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = textColor, fontWeight = FontWeight.Bold)
            Text(if (bought) "Owned" else "${formatClicks(price, numberFormat, numberDecimals)} clicks", color = textColor)
        }
        if (!bought) {
            AccentButtonSmall("BUY", clicks >= price, onBuy)
        } else {
            Text("OWNED", color = textColor, fontWeight = FontWeight.Bold)
        }
    }
}

// =============================================================
// SMALL ACCENT BUTTON
// =============================================================

@Composable
fun AccentButtonSmall(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {

    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = accentTextColor(MaterialTheme.colorScheme.primary),
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            disabledContentColor = accentTextColor(MaterialTheme.colorScheme.primary).copy(alpha = 0.7f)
        )
    ) {
        Text(text = text, color = accentTextColor(MaterialTheme.colorScheme.primary))
    }
}

// =============================================================
// SKINS
// =============================================================

@Composable
fun SkinsPanel(
    textColor: Color,
    blueBought: Boolean,
    redBought: Boolean,
    yellowBought: Boolean,
    greenBought: Boolean,
    customBought: Boolean,
    gradientBought: Boolean,
    customGradientBought: Boolean,
    selectedSkin: String,
    customColorInt: Int,
    gradientColor1Int: Int,
    gradientColor2Int: Int,
    customGradientColor1Int: Int,
    customGradientColor2Int: Int,
    ownedShopSkins: Set<String>,
    ownedAccessories: Set<String>,
    selectedShopSkin: String,
    selectedAccessory: String,
    onSelectShopSkin: (String) -> Unit,
    onSelectAccessory: (String) -> Unit,
    onSelectSkin: (String) -> Unit,
    onCustomColorChange: (Color) -> Unit,
    onGradientColor1Change: (Color) -> Unit,
    onGradientColor2Change: (Color) -> Unit,
    onCustomGradientColor1Change: (Color) -> Unit,
    onCustomGradientColor2Change: (Color) -> Unit
) {

    var customHex by remember(customColorInt) { mutableStateOf(String.format(Locale.US, "#%06X", customColorInt and 0xFFFFFF)) }
    var customGradientHex1 by remember(customGradientColor1Int) { mutableStateOf(String.format(Locale.US, "#%06X", customGradientColor1Int and 0xFFFFFF)) }
    var customGradientHex2 by remember(customGradientColor2Int) { mutableStateOf(String.format(Locale.US, "#%06X", customGradientColor2Int and 0xFFFFFF)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {

        Text("SKINS", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))

        SkinSelectItem(
            name = "Default",
            selected = selectedSkin == "default",
            textColor = textColor,
            onSelect = { onSelectSkin("default") }
        )

        if (blueBought) {
            SkinSelectItem(
                name = "Blue",
                selected = selectedSkin == "blue",
                textColor = textColor,
                onSelect = { onSelectSkin("blue") }
            )
        }

        if (redBought) {
            SkinSelectItem(
                name = "Red",
                selected = selectedSkin == "red",
                textColor = textColor,
                onSelect = { onSelectSkin("red") }
            )
        }

        if (yellowBought) {
            SkinSelectItem(
                name = "Yellow",
                selected = selectedSkin == "yellow",
                textColor = textColor,
                onSelect = { onSelectSkin("yellow") }
            )
        }

        if (greenBought) {
            SkinSelectItem(
                name = "Green",
                selected = selectedSkin == "green",
                textColor = textColor,
                onSelect = { onSelectSkin("green") }
            )
        }

        if (customBought) {
            SkinSelectItem(
                name = "Custom Color",
                selected = selectedSkin == "custom",
                textColor = textColor,
                onSelect = { onSelectSkin("custom") }
            )

            Spacer(Modifier.height(10.dp))

            ColorWheel(
                selectedColor = Color(customColorInt),
                onColorChange = {
                    onCustomColorChange(it)
                    customHex = String.format(Locale.US, "#%06X", it.toArgb() and 0xFFFFFF)
                }
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customHex,
                onValueChange = {
                    val normalized = if (it.startsWith("#")) it.uppercase() else "#${it.uppercase()}"
                    customHex = normalized.take(7)
                    if (normalized.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                        onCustomColorChange(Color(AndroidColor.parseColor(normalized)))
                    }
                },
                label = { Text("HEX COLOR") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
        }

        if (gradientBought) {
            SkinSelectItem(
                name = "Gradient",
                selected = selectedSkin == "gradient",
                textColor = textColor,
                onSelect = { onSelectSkin("gradient") }
            )

            Spacer(Modifier.height(15.dp))

            Text("FIRST COLOR — PURCHASED COLORS", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OwnedColorPicker(
                textColor = textColor,
                selectedColor = gradientColor1Int,
                blueBought = blueBought,
                redBought = redBought,
                yellowBought = yellowBought,
                greenBought = greenBought,
                onColorChange = onGradientColor1Change
            )

            Spacer(Modifier.height(14.dp))

            Text("SECOND COLOR — PURCHASED COLORS", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OwnedColorPicker(
                textColor = textColor,
                selectedColor = gradientColor2Int,
                blueBought = blueBought,
                redBought = redBought,
                yellowBought = yellowBought,
                greenBought = greenBought,
                onColorChange = onGradientColor2Change
            )

            Spacer(Modifier.height(20.dp))
        }

        if (customGradientBought) {
            SkinSelectItem(
                name = "Custom Gradient",
                selected = selectedSkin == "custom_gradient",
                textColor = textColor,
                onSelect = { onSelectSkin("custom_gradient") }
            )

            Spacer(Modifier.height(15.dp))

            Text("FIRST COLOR", color = textColor, fontWeight = FontWeight.Bold)

            ColorWheel(
                selectedColor = Color(customGradientColor1Int),
                onColorChange = {
                    onCustomGradientColor1Change(it)
                    customGradientHex1 = String.format(Locale.US, "#%06X", it.toArgb() and 0xFFFFFF)
                }
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customGradientHex1,
                onValueChange = {
                    val normalized = if (it.startsWith("#")) it.uppercase() else "#${it.uppercase()}"
                    customGradientHex1 = normalized.take(7)
                    if (normalized.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                        onCustomGradientColor1Change(Color(AndroidColor.parseColor(normalized)))
                    }
                },
                label = { Text("HEX COLOR") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            Text("SECOND COLOR", color = textColor, fontWeight = FontWeight.Bold)

            ColorWheel(
                selectedColor = Color(customGradientColor2Int),
                onColorChange = {
                    onCustomGradientColor2Change(it)
                    customGradientHex2 = String.format(Locale.US, "#%06X", it.toArgb() and 0xFFFFFF)
                }
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customGradientHex2,
                onValueChange = {
                    val normalized = if (it.startsWith("#")) it.uppercase() else "#${it.uppercase()}"
                    customGradientHex2 = normalized.take(7)
                    if (normalized.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                        onCustomGradientColor2Change(Color(AndroidColor.parseColor(normalized)))
                    }
                },
                label = { Text("HEX COLOR") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("SKINS", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val names = mapOf(
                "watch" to "Watch", "radar" to "Radar", "speedometer" to "Speedometer",
                "bulb" to "Light Bulb", "record" to "Vinyl Record"
            )
            SkinSelectItem(
                name = "None",
                selected = selectedShopSkin == "none",
                textColor = textColor,
                onSelect = { onSelectShopSkin("none") }
            )
        ownedShopSkins.forEach { id ->
            SkinSelectItem(
                name = names[id] ?: id,
                selected = selectedShopSkin == id,
                textColor = textColor,
                onSelect = { onSelectShopSkin(id) }
            )
        }

        /*
        if (ownedAccessories.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("ACCESSORIES", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val names = mapOf(
                "chain" to "Chain", "crown" to "Crown", "ribbon" to "Ribbon",
                "halo" to "Halo", "sparkles" to "Sparkles"
            )
            SkinSelectItem(
                name = "None",
                selected = selectedAccessory == "none",
                textColor = textColor,
                onSelect = { onSelectAccessory("none") }
            )
            ownedAccessories.forEach { id ->
                SkinSelectItem(
                    name = names[id] ?: id,
                    selected = selectedAccessory == id,
                    textColor = textColor,
                    onSelect = { onSelectAccessory(id) }
                )
            }
        }
        */
    }
}

// =============================================================
// OWNED COLOR PICKER
// =============================================================

@Composable
fun OwnedColorPicker(
    textColor: Color,
    selectedColor: Int,
    blueBought: Boolean,
    redBought: Boolean,
    yellowBought: Boolean,
    greenBought: Boolean,
    onColorChange: (Color) -> Unit
) {
    val colors = buildList<Pair<String, Color>> {
        if (blueBought) add("Blue" to Color.Blue)
        if (redBought) add("Red" to Color.Red)
        if (yellowBought) add("Yellow" to Color.Yellow)
        if (greenBought) add("Green" to Color.Green)
    }

    if (colors.isEmpty()) {
        Text("Buy at least one color first.", color = textColor.copy(alpha = 0.7f))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { (name, color) ->
                val selected = selectedColor == color.toArgb()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(color)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onColorChange(color) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(name, color = if (name == "Yellow") Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =============================================================
// ADMIN PANEL
// =============================================================

@Composable
fun AdminPanel(
    textColor: Color,
    accentColor: Color,
    onAddClicks: (BigInteger) -> Unit,
    onRevertClicks: (BigInteger) -> Unit,
    onSetReward: (Int) -> Unit,
    onSetDuration: (Int) -> Unit,
    onResetQuestSettings: () -> Unit,
    onGrantSkin: (String) -> Unit,
    onSetBadge: suspend (String, String, String) -> String?,
    onSetAccountStatus: suspend (String, String, Int?) -> String?,
    onResetDaily: () -> Unit
) {
    var clickAmount by remember { mutableStateOf("") }
    var rewardAmount by remember { mutableStateOf("") }
    var durationAmount by remember { mutableStateOf("") }
    var badgeUsername by remember { mutableStateOf("") }
    var badgeText by remember { mutableStateOf("") }
    var badgeColor by remember { mutableStateOf("#8B5CF6") }
    var badgeStatus by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Text("GRANT CLICKS", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = clickAmount,
        onValueChange = { clickAmount = it.filter(Char::isDigit) },
        label = { Text("AMOUNT") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    AccentButton(
        text = "ADD CLICKS",
        enabled = clickAmount.isNotEmpty() && clickAmount.toBigIntegerOrNull()?.signum() == 1,
        accentColor = accentColor,
        onClick = {
            clickAmount.toBigIntegerOrNull()?.let(onAddClicks)
            clickAmount = ""
        }
    )

    Spacer(Modifier.height(6.dp))
    AccentButton(
        text = "REVERT CLICKS",
        enabled = clickAmount.isNotEmpty() && clickAmount.toBigIntegerOrNull()?.signum() == 1,
        accentColor = accentColor,
        onClick = {
            clickAmount.toBigIntegerOrNull()?.let(onRevertClicks)
            clickAmount = ""
        }
    )

    Spacer(Modifier.height(20.dp))
    Text("DAILY QUEST TOOLS", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = rewardAmount,
        onValueChange = { rewardAmount = it.filter(Char::isDigit).take(3) },
        label = { Text("REWARD PERCENT (0-100)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    AccentButton(
        text = "SET REWARD",
        enabled = rewardAmount.toIntOrNull()?.let { it in 0..100 } == true,
        accentColor = accentColor,
        onClick = { rewardAmount.toIntOrNull()?.let(onSetReward) }
    )
    Spacer(Modifier.height(8.dp))
    Text("Custom reward overrides the generated quest reward until changed.", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)

    Spacer(Modifier.height(15.dp))
    Text("QUEST TIME", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = durationAmount,
        onValueChange = { durationAmount = it.filter(Char::isDigit).take(2) },
        label = { Text("TIME IN SECONDS (5-50)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    val previewDuration = durationAmount.toIntOrNull()?.coerceIn(1, 45) ?: 1
    val previewTarget = (previewDuration * 10).coerceIn(50, 500)
    val previewReward = rewardAmount.toIntOrNull()?.coerceIn(0, 100) ?: 50
    Text("Preview: $previewTarget clicks in $previewDuration seconds", color = textColor)
    Text("Reward: +$previewReward%", color = textColor)
    Spacer(Modifier.height(8.dp))
    AccentButton(
        text = "SET TIME",
        enabled = durationAmount.toIntOrNull()?.let { it in 1..45 } == true,
        accentColor = accentColor,
        onClick = { durationAmount.toIntOrNull()?.let(onSetDuration) }
    )
    Spacer(Modifier.height(8.dp))
    AccentButtonSmall("RESET QUEST SETTINGS", true, onClick = onResetQuestSettings)
    Spacer(Modifier.height(8.dp))
    AccentButtonSmall("RESET DAILY TIMER", true, onClick = onResetDaily)

    Spacer(Modifier.height(20.dp))
    Text("GRANT SKINS", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    listOf(
        "blue" to "BLUE",
        "red" to "RED",
        "yellow" to "YELLOW",
        "green" to "GREEN",
        "custom" to "CUSTOM COLOR",
        "gradient" to "GRADIENT",
        "custom_gradient" to "CUSTOM GRADIENT",
        "watch" to "WATCH",
        "radar" to "RADAR",
        "speedometer" to "SPEEDOMETER",
        "bulb" to "LIGHT BULB",
        "record" to "VINYL RECORD"
    ).forEach { (id, name) ->
        AccentButtonSmall(name, true, onClick = { onGrantSkin(id) })
        Spacer(Modifier.height(5.dp))
    }

    Spacer(Modifier.height(20.dp))
    Text("ACCOUNT BADGES", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = badgeUsername, onValueChange = { badgeUsername = it.take(20) }, label = { Text("USERNAME") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = badgeText, onValueChange = { badgeText = it.take(32) }, label = { Text("BADGE TEXT") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = badgeColor, onValueChange = {
        val normalized = if (it.startsWith("#")) it.uppercase() else "#${it.uppercase()}"
        badgeColor = normalized.take(7)
    }, label = { Text("BADGE COLOR (#RRGGBB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    AccentButtonSmall("GIVE / UPDATE BADGE", badgeUsername.isNotBlank() && badgeText.isNotBlank() && badgeColor.matches(Regex("#[0-9A-Fa-f]{6}")), onClick = {
        coroutineScope.launch {
            badgeStatus = onSetBadge(badgeUsername, badgeText, badgeColor) ?: "Badge updated"
        }
    })
    if (badgeStatus != null) {
        Spacer(Modifier.height(5.dp))
        Text(badgeStatus!!, color = textColor.copy(alpha = .7f), fontSize = 12.sp)
    }

    Spacer(Modifier.height(20.dp))
    Text("ACCOUNT MODERATION", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    var moderationUsername by remember { mutableStateOf("") }
    var suspendMinutes by remember { mutableStateOf("60") }
    var moderationStatus by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(value = moderationUsername, onValueChange = { moderationUsername = it.take(20) }, label = { Text("USERNAME") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = suspendMinutes, onValueChange = { suspendMinutes = it.filter(Char::isDigit).take(6) }, label = { Text("SUSPEND MINUTES") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AccentButtonSmall("BAN", moderationUsername.isNotBlank()) { coroutineScope.launch { moderationStatus = onSetAccountStatus(moderationUsername, "banned", null) ?: "Account banned" } }
        AccentButtonSmall("SUSPEND", moderationUsername.isNotBlank() && suspendMinutes.toIntOrNull()?.let { it > 0 } == true) { coroutineScope.launch { moderationStatus = onSetAccountStatus(moderationUsername, "suspended", suspendMinutes.toIntOrNull()) ?: "Account suspended" } }
        AccentButtonSmall("UNBAN", moderationUsername.isNotBlank()) { coroutineScope.launch { moderationStatus = onSetAccountStatus(moderationUsername, "active", null) ?: "Account restored" } }
    }
    moderationStatus?.let { Text(it, color = textColor.copy(alpha = .7f), fontSize = 12.sp) }

    Spacer(Modifier.height(15.dp))
    Text("FUTURE TEST TOOLS", color = textColor, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Factory instant build — coming later", color = textColor.copy(alpha = 0.6f))
    Spacer(Modifier.height(8.dp))
    Text("Ranked mode is not affected by admin tools.", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
}

// =============================================================
// DAILY QUEST PANEL
// =============================================================

@Composable
fun DailyQuestPanel(
    textColor: Color,
    accentColor: Color,
    targetClicks: Int,
    durationSeconds: Int,
    result: String?,
    rewardPercent: Int,
    nextRefreshMillis: Long,
    onStart: () -> Unit
) {
    val remainingHours = if (nextRefreshMillis > 0L) {
        ((nextRefreshMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 3599999L) / 3600000L
    } else 0L

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("DAILY QUESTS", color = textColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Today's Goal: $targetClicks clicks in $durationSeconds seconds.", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))

        AccentButton(
            text = if (result == null) "START QUEST" else "QUEST COMPLETE",
            enabled = result == null,
            accentColor = accentColor,
            onClick = onStart
        )

        Spacer(Modifier.height(35.dp))
        if (result == "passed") {
            Text("You Passed!", color = textColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Your Reward: +$rewardPercent%", color = textColor, fontSize = 18.sp)
        } else if (result == "failed") {
            Text("You Failed!", color = textColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Better Luck Next Time!", color = textColor, fontSize = 18.sp)
        }

        if (result != null && remainingHours > 0L) {
            Spacer(Modifier.height(8.dp))
            Text("See You in $remainingHours hours!", color = textColor.copy(alpha = 0.7f))
        }
    }
}

// =============================================================
// DAILY QUEST RUNNER
// =============================================================

@Composable
fun DailyQuestRunner(
    targetClicks: Int,
    durationSeconds: Int,
    textColor: Color,
    accentColor: Color,
    onClickSound: () -> Unit,
    onFinish: (Boolean) -> Unit
) {
    var phase by remember { mutableStateOf("countdown") }
    var questClicks by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(durationSeconds) }
    var finished by remember { mutableStateOf(false) }
    var transitionAlpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        // The previous scene is immediately covered by a fully black screen.
        // This prevents the quest scene from being visible during the fade-out.
        transitionAlpha = 1f
        delay(1000L)

        // Fade from the black screen into the quest scene.
        var elapsed = 0L
        while (elapsed < 1000L) {
            delay(16L)
            elapsed += 16L
            transitionAlpha = (1f - elapsed / 1000f).coerceIn(0f, 1f)
        }
        transitionAlpha = 0f

        delay(1000)
        phase = "countdown2"
        delay(1000)
        phase = "countdown1"
        delay(1000)
        phase = "active"

        val end = System.currentTimeMillis() + durationSeconds * 1000L
        while (!finished) {
            val seconds = ((end - System.currentTimeMillis() + 999L) / 1000L).toInt()
            remaining = seconds.coerceAtLeast(0)

            if (questClicks >= targetClicks) {
                finished = true
                delay(700)
                elapsed = 0L
                while (elapsed < 1000L) {
                    delay(16L)
                    elapsed += 16L
                    transitionAlpha = (elapsed / 1000f).coerceIn(0f, 1f)
                }
                transitionAlpha = 1f
                onFinish(true)
                break
            }

            if (seconds <= 0) {
                finished = true
                delay(700)
                elapsed = 0L
                while (elapsed < 1000L) {
                    delay(16L)
                    elapsed += 16L
                    transitionAlpha = (elapsed / 1000f).coerceIn(0f, 1f)
                }
                transitionAlpha = 1f
                onFinish(false)
                break
            }
            delay(100L)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF181818)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (phase.startsWith("countdown")) {
                val number = when (phase) {
                    "countdown" -> "3"
                    "countdown2" -> "2"
                    else -> "1"
                }
                Box(
                    modifier = Modifier.size(220.dp).clip(CircleShape).background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(number, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(enabled = !finished && phase == "active") {
                            questClicks++
                            onClickSound()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(questClicks.toString(), color = textColor, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                Text("$remaining seconds", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Goal: $targetClicks clicks", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
        }

        if (transitionAlpha > 0f) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = transitionAlpha))
            )
        }
    }
}

// SKIN SELECT ITEM
// =============================================================

@Composable
fun SkinSelectItem(
    name: String,
    selected: Boolean,
    textColor: Color,
    onSelect: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            name,
            color = textColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        Button(
            onClick = onSelect,
            enabled = !selected,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = accentTextColor(MaterialTheme.colorScheme.primary),
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = accentTextColor(MaterialTheme.colorScheme.primary)
            )
        ) {
            Text(if (selected) "SELECTED" else "SELECT")
        }
    }
}

// =============================================================
// UPGRADES
// =============================================================

@Composable
fun UpgradePanel(
    clicks: BigInteger,
    textColor: Color,
    autoclickers: BigInteger,
    autoSpeedLevel: Int,
    clickMultiplier: Int,
    numberFormat: String,
    numberDecimals: Int,
    onBuyAutoclickers: (Int) -> Unit,
    onBuyAutoSpeed: (Int) -> Unit,
    onBuyMultiplier: (Int) -> Unit
) {
    var bulkAmountText by remember { mutableStateOf("1") }
    val bulkAmount = bulkAmountText.toIntOrNull()?.coerceIn(1, 1000) ?: 1

    fun autoclickerTotalPrice(amount: Int): BigInteger {
        var total = BigInteger.ZERO
        repeat(amount.coerceIn(1, 1000)) { index ->
            val current = autoclickers + BigInteger.valueOf(index.toLong())
            total += current.divide(BigInteger.TEN)
                .add(BigInteger.ONE)
                .multiply(BigInteger.valueOf(100L))
        }
        return total
    }

    fun speedTotalPrice(amount: Int): BigInteger {
        val price = autoclickers.divide(BigInteger.TEN)
            .multiply(BigInteger.valueOf(500L))
        return price.multiply(BigInteger.valueOf(amount.toLong()))
    }

    fun multiplierTotalPrice(amount: Int): BigInteger {
        var total = BigInteger.ZERO
        repeat(amount.coerceIn(1, 1000)) { index ->
            total += BigInteger.valueOf((clickMultiplier + index).toLong() * 100L)
        }
        return total
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("BULK BUY", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Choose how many upgrades to buy at once.", color = textColor)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = bulkAmountText,
            onValueChange = { value ->
                if (value.all { it.isDigit() } && value.length <= 4) {
                    bulkAmountText = value
                }
            },
            label = { Text("Amount") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Slider(
            value = bulkAmount.toFloat(),
            onValueChange = { value ->
                bulkAmountText = value.toInt().coerceIn(1, 1000).toString()
            },
            valueRange = 1f..1000f,
            steps = 998,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Buying $bulkAmount at a time", color = textColor, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        Text("AUTOCLICKERS", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Each autoclicker makes 1 click at its current speed.", color = textColor)
        Spacer(Modifier.height(10.dp))

        val autoclickerTotal = autoclickerTotalPrice(bulkAmount)
        AccentButtonSmall(
            text = "BUY $bulkAmount AUTOCLICKER${if (bulkAmount == 1) "" else "S"} — ${formatClicks(autoclickerTotal, numberFormat, numberDecimals)}",
            enabled = clicks >= autoclickerTotal,
            onClick = { onBuyAutoclickers(bulkAmount) }
        )

        Text("Owned: ${formatClicks(autoclickers, numberFormat, numberDecimals)}", color = textColor)
        Text(
            "Current rate: ${formatClicks(autoclickers.multiply(BigInteger.valueOf((1 + autoSpeedLevel).toLong())), numberFormat, numberDecimals)} clicks/sec",
            color = textColor
        )

        if (autoclickers >= BigInteger.TEN) {
            Spacer(Modifier.height(25.dp))
            Text("AUTOCLICKER SPEED", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Each autoclicker clicks ${1 + autoSpeedLevel} time${if (autoSpeedLevel == 0) "" else "s"} per second.", color = textColor)

            val speedTotal = speedTotalPrice(bulkAmount)
            AccentButtonSmall(
                text = "BUY $bulkAmount SPEED${if (bulkAmount == 1) "" else "S"} — ${formatClicks(speedTotal, numberFormat, numberDecimals)}",
                enabled = speedTotal > BigInteger.ZERO && clicks >= speedTotal,
                onClick = { onBuyAutoSpeed(bulkAmount) }
            )

            Text("Speed level: $autoSpeedLevel", color = textColor)
            Text("Each autoclicker: ${if (autoSpeedLevel == 0) "1 click every 1 second" else "1 click every ${String.format(java.util.Locale.US, "%.3f", 1.0 / (1 + autoSpeedLevel))} seconds"}", color = textColor)
        }

        Spacer(Modifier.height(25.dp))
        Text("CLICK MULTIPLIER", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Works only on manual clicks.", color = textColor)

        val multiplierTotal = multiplierTotalPrice(bulkAmount)
        AccentButtonSmall(
            text = "BUY $bulkAmount MULTIPLIER${if (bulkAmount == 1) "" else "S"} — ${formatClicks(multiplierTotal, numberFormat, numberDecimals)}",
            enabled = clicks >= multiplierTotal,
            onClick = { onBuyMultiplier(bulkAmount) }
        )

        Text("Current: +$clickMultiplier per click", color = textColor)
    }
}

// =============================================================
// COLOR WHEEL
// =============================================================

@Composable
fun ColorWheel(
    selectedColor: Color,
    onColorChange: (Color) -> Unit
) {
    val hsv = remember(selectedColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(selectedColor.toArgb(), it) }
    }

    fun colorFromWheel(offset: Offset, width: Float, height: Float): Color? {
        val centerX = width / 2f
        val centerY = height / 2f
        val dx = offset.x - centerX
        val dy = offset.y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        val radius = minOf(width, height) / 2f
        if (distance > radius) return null
        var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (hue < 0f) hue += 360f
        val saturation = (distance / radius).coerceIn(0f, 1f)
        return Color.hsv(hue, saturation, hsv[2])
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(
                modifier = Modifier
                    .size(240.dp)
                    .pointerInput(hsv[2]) {
                        detectDragGestures(
                            onDragStart = { position ->
                                colorFromWheel(position, size.width.toFloat(), size.height.toFloat())?.let(onColorChange)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                colorFromWheel(change.position, size.width.toFloat(), size.height.toFloat())?.let(onColorChange)
                            }
                        )
                    }
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) / 2f
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                        center = center
                    ),
                    radius = radius,
                    center = center
                )

                val angle = Math.toRadians(hsv[0].toDouble())
                val selectedRadius = radius * hsv[1]
                val point = Offset(
                    center.x + cos(angle).toFloat() * selectedRadius,
                    center.y + sin(angle).toFloat() * selectedRadius
                )
                drawCircle(Color.White, 11f, point, style = Stroke(width = 4f))
                drawCircle(selectedColor, 6f, point)
            }

            Spacer(Modifier.width(14.dp))

            Canvas(
                modifier = Modifier
                    .width(28.dp)
                    .height(240.dp)
                    .pointerInput(selectedColor) {
                        fun update(y: Float) {
                            val value = (y / size.height).coerceIn(0f, 1f)
                            onColorChange(Color.hsv(hsv[0], hsv[1], value))
                        }
                        detectDragGestures(
                            onDragStart = { pos -> update(pos.y) },
                            onDrag = { change, _ ->
                                change.consume()
                                update(change.position.y)
                            }
                        )
                    }
            ) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color.Black, Color.White)),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f)
                )
                val y = hsv[2] * size.height
                drawCircle(Color.White, 8f, Offset(size.width / 2f, y), style = Stroke(width = 3f))
            }
        }

        Spacer(Modifier.height(5.dp))
        Text(
            text = "#${selectedColor.toArgb().and(0xFFFFFF).toString(16).uppercase().padStart(6, '0')}",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
    }
}

// =============================================================
// CUSTOM SOUND
// =============================================================

fun playCustomSound(
    context: Context,
    uriString: String?
) {

    if (uriString == null) return

    try {
        val player = MediaPlayer()
        player.setDataSource(context, Uri.parse(uriString))
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener { it.release() }
        player.prepareAsync()
    } catch (_: Exception) {
    }
}
