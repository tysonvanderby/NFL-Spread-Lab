
package com.nflspreadlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

private const val ELO_K = 20.0
private const val HOME_ADVANTAGE_ELO = 55.0
private const val ELO_PER_POINT = 25.0
private const val VALIDATION_START = "2015-01-01"

private fun normalizeTeamName(name: String): String {
    val aliases = mapOf(
        "Arizona" to "ARI", "Arizona Cardinals" to "ARI",
        "Atlanta" to "ATL", "Atlanta Falcons" to "ATL",
        "Baltimore" to "BAL", "Baltimore Ravens" to "BAL",
        "Buffalo" to "BUF", "Buffalo Bills" to "BUF",
        "Carolina" to "CAR", "Carolina Panthers" to "CAR",
        "Chicago" to "CHI", "Chicago Bears" to "CHI",
        "Cincinnati" to "CIN", "Cincinnati Bengals" to "CIN",
        "Cleveland" to "CLE", "Cleveland Browns" to "CLE",
        "Dallas" to "DAL", "Dallas Cowboys" to "DAL",
        "Denver" to "DEN", "Denver Broncos" to "DEN",
        "Detroit" to "DET", "Detroit Lions" to "DET",
        "Green Bay" to "GB", "Green Bay Packers" to "GB",
        "Houston" to "HOU", "Houston Texans" to "HOU",
        "Indianapolis" to "IND", "Indianapolis Colts" to "IND",
        "Jacksonville" to "JAX", "Jacksonville Jaguars" to "JAX",
        "Kansas City" to "KC", "Kansas City Chiefs" to "KC",
        "Las Vegas" to "LV", "Las Vegas Raiders" to "LV",
        "LA Chargers" to "LAC", "Los Angeles Chargers" to "LAC",
        "LA Rams" to "LA", "Los Angeles Rams" to "LA",
        "Miami" to "MIA", "Miami Dolphins" to "MIA",
        "Minnesota" to "MIN", "Minnesota Vikings" to "MIN",
        "New England" to "NE", "New England Patriots" to "NE",
        "New Orleans" to "NO", "New Orleans Saints" to "NO",
        "New York Giants" to "NYG",
        "New York Jets" to "NYJ",
        "Philadelphia" to "PHI", "Philadelphia Eagles" to "PHI",
        "Pittsburgh" to "PIT", "Pittsburgh Steelers" to "PIT",
        "San Francisco" to "SF", "San Francisco 49ers" to "SF",
        "Seattle" to "SEA", "Seattle Seahawks" to "SEA",
        "Tampa Bay" to "TB", "Tampa Bay Buccaneers" to "TB",
        "Tennessee" to "TEN", "Tennessee Titans" to "TEN",
        "Washington" to "WAS", "Washington Commanders" to "WAS"
    )

    return aliases.entries.firstOrNull {
        it.key.equals(name.trim(), ignoreCase = true)
    }?.value ?: name.trim().uppercase(Locale.US)
}


private data class Game(
    val date: String,
    val week: String,
    val away: String,
    val home: String,
    val awayScore: Double?,
    val homeScore: Double?,
    val spreadLine: Double? = null
)

private data class Projection(
    val game: Game,
    val awayElo: Double,
    val homeElo: Double,
    val homeWin: Double,
    val margin: Double
)

private data class LiveOddsGame(
    val date: String,
    val away: String,
    val home: String,
    val spread: String,
    val total: String,
    val moneyline: String,
    val homeSpread: Double? = null
)


private data class Validation(
    val games: Int,
    val marginMae: Double,
    val marginRmse: Double,
    val candidateMarginMae: Double,
    val candidateMarginRmse: Double,
    val combinedMarginMae: Double,
    val combinedMarginRmse: Double,
    val winAccuracy: Double,
    val brierScore: Double,
    val atsPicks: Int,
    val atsWins: Int,
    val atsLosses: Int,
    val atsPushes: Int,
    val atsNetUnits: Double
)


private data class ModelData(
    val projections: List<Projection>,
    val historicalGames: Int,
    val validation: Validation
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SpreadLabScreen()
                }
            }
        }
    }
}

@Composable
private fun SpreadLabScreen() {
    var data by remember { mutableStateOf<ModelData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var liveOdds by remember { mutableStateOf<List<LiveOddsGame>>(emptyList()) }
var oddsError by remember { mutableStateOf("") }
var bestBetsSyncMessage by remember { mutableStateOf("") }
var performanceStats by remember {
    mutableStateOf<org.json.JSONObject?>(null)
}
var savedBestBets by remember {
    mutableStateOf<org.json.JSONArray?>(null)
}
var selectedPage by remember { mutableStateOf("Dashboard") }


    // Holds manually entered sportsbook lines while the screen is open.
    var spreadInputs by remember { mutableStateOf(mapOf<String, String>()) }

    
LaunchedEffect(refresh) {
    loading = true
    error = ""
    try {
        data = withContext(Dispatchers.IO) { loadModelData() }
    } catch (e: Exception) {
        error = e.message ?: "Unable to load NFL data."
    }

    oddsError = ""
    try {
        liveOdds = withContext(Dispatchers.IO) { loadLiveOdds() }
    } catch (e: Exception) {
        oddsError = e.message ?: "Unable to load live sportsbook odds."
    }

        try {
            val currentModel = data
            val currentOdds = liveOdds

            if (currentModel != null && currentOdds.isNotEmpty()) {
                val picks = buildBestBetPayload(
                    currentModel.projections,
                    currentOdds
                )

                if (picks.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        saveBestBets(picks)
                    }

                    bestBetsSyncMessage =
                        "Sent ${picks.size} qualifying picks to tracking."
                } else {
                    bestBetsSyncMessage =
                        "No qualifying picks to sync."
                }
            } else {
                bestBetsSyncMessage =
                    "Live odds unavailable; picks not synced."
            }
        } catch (e: Exception) {
            bestBetsSyncMessage =
                "Best Bets sync failed: ${e.message ?: "Unknown error"}"
        }
        try {
    savedBestBets = loadSavedBestBets()
} catch (e: Exception) {
    savedBestBets = null
}
        try {
            performanceStats = loadBestBetsStats()
        } catch (e: Exception) {
            performanceStats = null
        }

    loading = false
}


    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "NFL SPREAD LAB",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black
        )
        Text("Historical ratings, validation, and spread analysis")
        

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp)
) {
    listOf("Dashboard", "Matchups").forEach { page ->
        FilterChip(
            selected = selectedPage == page,
            onClick = { selectedPage = page },
            label = { Text(page) }
        )
    }
}

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp)
) {
    listOf("Live Odds", "Performance", "Best Bets").forEach { page ->
        FilterChip(
            selected = selectedPage == page,
            onClick = { selectedPage = page },
            label = { Text(page) }
        )
    }
}



        Button(
            onClick = { refresh++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Loading NFL results..." else "Refresh data and predictions")
        }

        if (loading) CircularProgressIndicator()

        if (error.isNotBlank()) {
            Text("Data error: $error")
            Text("Check your connection and try refreshing.")
        }

        data?.let { model ->
            
if (selectedPage == "Dashboard") {

            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("MODEL STATUS", fontWeight = FontWeight.Bold)
                    Text("Historical games processed: ${model.historicalGames}")
                    Text("Model: Elo, K = ${ELO_K.toInt()}")
                    Text("Source: public nflverse games.csv")
                    Text("Home advantage: ${HOME_ADVANTAGE_ELO.toInt()} Elo points")
                    Text("Validation period: 2015 onward")
                }
            }
        }


            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    
if (selectedPage == "Performance") {

                    Text(
                        "MODEL PERFORMANCE",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    val stats = performanceStats

                    if (stats == null) {
                        Text(
                            "Performance statistics unavailable. " +
                                "Refresh to try again."
                        )
                    } else {
                        val graded = stats.optInt("graded", 0)
                        val wins = stats.optInt("wins", 0)
                        val losses = stats.optInt("losses", 0)
                        val pushes = stats.optInt("pushes", 0)
                        val pending = stats.optInt("pending", 0)
                        val units = stats.optString("units", "0.0000")

                        val winPercentage =
                            if (stats.isNull("win_percentage")) {
                                "N/A"
                            } else {
                                "${stats.optDouble("win_percentage")}%"
                            }

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "Tracked Best Bets Record",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "$wins W - $losses L - $pushes P",
                                    style = MaterialTheme.typography.headlineMedium
                                )

                                HorizontalDivider()

                                Text("Win percentage: $winPercentage")
                                Text("Graded picks: $graded")
                                Text("Pending picks: $pending")
                                Text("Net units: $units")
                            }
                        }
                    }

                    Text(
                        "WALK-FORWARD VALIDATION",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Games tested: ${model.validation.games}")
                    Text(
    "Elo Model MAE: ${decimal(model.validation.marginMae)} points"
)
Text(
    "Elo Model RMSE: ${decimal(model.validation.marginRmse)} points"
)
Text(
    "Candidate Model MAE: ${decimal(model.validation.candidateMarginMae)} points"
)

  
Text(
    "Candidate Model RMSE: ${decimal(model.validation.candidateMarginRmse)} points"
)
Text(
    "Combined Model MAE: ${decimal(model.validation.combinedMarginMae)} points"
)
Text(
    "Combined Model RMSE: ${decimal(model.validation.combinedMarginRmse)} points"
)
                  Text("Winner accuracy: ${pct(model.validation.winAccuracy)}")
                    Text("Win probability Brier score: ${decimal(model.validation.brierScore)}")
                    Text("ATS picks tested: ${model.validation.atsPicks}")
                    Text(
    "ATS record (W-L-P): " +
        "${model.validation.atsWins}-" +
        "${model.validation.atsLosses}-" +
        "${model.validation.atsPushes}"
)
val atsDecisions = model.validation.atsWins + model.validation.atsLosses
Text(
    "ATS win rate: " +
        if (atsDecisions > 0)
            pct(model.validation.atsWins.toDouble() / atsDecisions)
        else "N/A"
)
Text("Simulated net units at -110: ${signed(model.validation.atsNetUnits)}")
Text(
    "Simulated ROI: " +
        if (atsDecisions > 0)
            pct(model.validation.atsNetUnits / atsDecisions)
        else "N/A"
)
Text(
    "ATS test uses a minimum 1.5-point model edge. " +
        "Results are historical simulations, not guaranteed future returns.",
    style = MaterialTheme.typography.bodySmall
)

                    Text(
                        "Each historical game is predicted before its result updates ratings. " +
                            "Model settings are preliminary; this is not verified betting performance.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

if (selectedPage == "Live Odds") {            
Text(
    "LIVE SPORTSBOOK ODDS",
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold
)

Text("Source: TheRundown API. Books shown when available: DraftKings, BetMGM, and FanDuel.")

if (oddsError.isNotBlank()) {
    Text("Live odds unavailable: $oddsError")
} else if (liveOdds.isEmpty()) {
    Text("No upcoming odds were returned. Try refreshing later.")
} else {
    Text("Upcoming games returned: ${liveOdds.size}")

    liveOdds.forEach { odds ->
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "${odds.away} at ${odds.home}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(odds.date)
                Text("Spread: ${odds.spread}")
                Text("Total: ${odds.total}")
                Text("Moneyline: ${odds.moneyline}")
            }
        }
    }
}
}

if (selectedPage == "Best Bets") {
    Text(
        "BEST BETS",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )

    Text(
        "Model vs. sportsbook spread edges. " +
            "Minimum qualifying edge: 2.0 points."
    )
    
Text(
    bestBetsSyncMessage,
    style = MaterialTheme.typography.bodySmall
)

Text(
    "Saved picks from backend: ${savedBestBets?.length() ?: "Not loaded"}"
)

    val bestBets = model.projections.mapNotNull { p ->
        val matchingOdds = liveOdds.firstOrNull {
            normalizeTeamName(it.home) ==
                normalizeTeamName(p.game.home) &&
            normalizeTeamName(it.away) ==
                normalizeTeamName(p.game.away)
        }

        val sportsbookHomeSpread = matchingOdds?.homeSpread

        if (sportsbookHomeSpread == null) {
            null
        } else {
            val edge = p.margin + sportsbookHomeSpread

            if (kotlin.math.abs(edge) >= 2.0) {
                p to edge
            } else {
                null
            }
        }
    }.sortedByDescending { kotlin.math.abs(it.second) }

    if (bestBets.isEmpty()) {
        Text(
            "No qualifying bets found. Check that sportsbook " +
                "spreads are available for upcoming matchups."
        )
    }

    bestBets.forEach { (p, edge) ->
        val sigma =
            if (model.validation.marginRmse > 0.0) {
                model.validation.marginRmse
            } else {
                13.5
            }

        val homeCover = normalCdfApprox(edge / sigma)
        val isHomeBet = edge > 0
        val coverProbability =
            if (isHomeBet) homeCover else 1.0 - homeCover

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "${p.game.away} at ${p.game.home}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "BET: ${
                        if (isHomeBet) p.game.home
                        else p.game.away
                    } against the spread"
                )

                Text("Model edge: ${signed(kotlin.math.abs(edge))} points")

                Text(
                    "Estimated cover probability: " +
                        pct(coverProbability)
                )

                Text(
                    "Week ${p.game.week} | ${p.game.date}"
                )
            }
        }
    }
}


if (selectedPage == "Matchups") {

            Text(
                "UPCOMING GAME PROJECTIONS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Enter the sportsbook's HOME spread for a matchup. " +
                    "Use a negative number when the home team is favored, " +
                    "for example -3.5. Use a positive number when it is the underdog."
            )

            if (model.projections.isEmpty()) {
                Text("No upcoming regular-season games found in the feed.")
            }

            model.projections.forEach { p ->
                val key = "${p.game.date}_${p.game.away}_${p.game.home}"
                
val matchingOdds = liveOdds.firstOrNull {
    normalizeTeamName(it.home) == normalizeTeamName(p.game.home) &&
    normalizeTeamName(it.away) == normalizeTeamName(p.game.away)
}
val autoHomeSpread = matchingOdds?.homeSpread

val lineText = spreadInputs[key]
    ?.takeIf { it.isNotBlank() }
    ?: (autoHomeSpread?.toString() ?: "")
                val vegasHomeSpread = lineText.toDoubleOrNull()

                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${p.game.away} at ${p.game.home}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("${p.game.date} | Week ${p.game.week}")
                        HorizontalDivider()

                        Text("Home Elo: ${p.homeElo.toInt()} | Away Elo: ${p.awayElo.toInt()}")
                        Text("Home win probability: ${pct(p.homeWin)}")
                        Text("Projected home margin: ${signed(p.margin)} points")
                        Text("Model fair home spread: ${signed(-p.margin)}")

                        OutlinedTextField(
                            value = lineText,
                            onValueChange = {
                                spreadInputs = spreadInputs + (key to it)
                            },
                          label = { Text("Feed spread / Vegas override") },
                            placeholder = { Text("Example: -3.5") },
                          keyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Text
),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (lineText.isNotBlank() && vegasHomeSpread == null) {
                            Text("Enter a valid number, such as -3.5 or +3.5.")
                        }

                        if (vegasHomeSpread != null) {
                            val edge = p.margin + vegasHomeSpread
                            val sigma = if (model.validation.marginRmse > 0.0) {
                                model.validation.marginRmse
                            } else {
                                13.5
                            }
                            val homeCover = normalCdfApprox(edge / sigma)
                            val awayCover = 1.0 - homeCover

                            HorizontalDivider()
                            Text("SPREAD COMPARISON", fontWeight = FontWeight.Bold)
                            Text("Sportsbook home spread: ${signed(vegasHomeSpread)}")
                            Text("Sportsbook implied home margin: ${signed(-vegasHomeSpread)}")
                            Text("Model edge toward home: ${signed(edge)} points")
                            Text("Estimated home cover probability: ${pct(homeCover)}")
                            Text("Estimated away cover probability: ${pct(awayCover)}")

                            Text(
                                "Experimental estimate using historical margin RMSE " +
                                    "(${decimal(sigma)} points) as the uncertainty scale. " +
                                    "Pushes are not modeled. This is not a verified betting edge.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                "Enter a real sportsbook line to calculate an experimental cover estimate. " +
                                  "The feed spread may not be the latest Vegas line. Verify it before relying on the cover estimate.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        } 

if (selectedPage == "Performance") {
            Text(
                "IMPORTANT LIMITATIONS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "This is a basic Elo model, not a proven betting system. " +
                    "It omits injuries, quarterback changes, weather, and market odds. " +
                    "Cover probabilities use a rough error approximation and are not yet calibrated " +
                    "or validated against historical sportsbook spreads. " +
                    "Historical results do not guarantee future performance."
            )
        }
    }
}
}

private fun loadLiveOdds(): List<LiveOddsGame> {
    val connection = (URL("https://nfl-spread-lab-api.onrender.com/consensus")
        .openConnection() as HttpURLConnection)

    connection.connectTimeout = 20000
    connection.readTimeout = 45000
    connection.setRequestProperty("User-Agent", "NFL-Spread-Lab-Android")

    val body = try {
        val status = connection.responseCode
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val response = stream.bufferedReader().use { it.readText() }

        if (status !in 200..299) {
            error("Odds API returned HTTP $status: $response")
        }
        response
    } finally {
        connection.disconnect()
    }

    val root = org.json.JSONObject(body)
    val events = root.optJSONArray("events") ?: org.json.JSONArray()

    fun marketSummary(items: org.json.JSONArray?): String {
        if (items == null || items.length() == 0) {
            return "Not available"
        }

        val parts = mutableListOf<String>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val selection = item.optString("selection", "Line")
            val lineValue = item.opt("line")

            val line = if (
                lineValue == null ||
                lineValue == org.json.JSONObject.NULL
            ) {
                ""
            } else {
                " " + lineValue.toString()
            }

            val prices = item.optJSONArray("prices") ?: org.json.JSONArray()
            val bookParts = mutableListOf<String>()

            for (j in 0 until prices.length()) {
                val book = prices.optJSONObject(j) ?: continue
                val name = book.optString("sportsbook", "")
                val price = book.opt("price")

                if (
                    name.isNotBlank() &&
                    price != null &&
                    price != org.json.JSONObject.NULL
                ) {
                    bookParts.add("$name $price")
                }
            }

            parts.add(
                selection + line +
                    if (bookParts.isNotEmpty()) {
                        " (" + bookParts.joinToString(", ") + ")"
                    } else {
                        ""
                    }
            )
        }
        

        return parts.joinToString(" | ").ifBlank { "Not available" }
    }

    
fun extractHomeSpread(
    items: org.json.JSONArray?,
    homeTeam: String,
    awayTeam: String
): Double? {
    if (items == null) return null

    val normalizedHome = normalizeTeamName(homeTeam)
    val normalizedAway = normalizeTeamName(awayTeam)

    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val selection = item.optString("selection", "").trim()
        if (selection.isBlank()) continue

        // Prefer a signed spread embedded in the selection name.
        val selectionLine = Regex(
            """\(?\s*([+-]?\d+(?:\.\d+)?)\s*\)?\s*$"""
        ).find(selection)

        val line = selectionLine?.groupValues?.get(1)?.toDoubleOrNull()
            ?: item.opt("line")
                ?.toString()
                ?.toDoubleOrNull()
            ?: continue

        val teamName = selection.replace(
            Regex("""\s*\(?[+-]?\d+(?:\.\d+)?\)?\s*$"""),
            ""
        ).trim()

        val selectedTeam = normalizeTeamName(teamName)

        when (selectedTeam) {
            normalizedHome -> return line
            normalizedAway -> return -line
        }
    }

    return null
}


    val result = mutableListOf<LiveOddsGame>()

    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue

        result.add(
            LiveOddsGame(
                date = event.optString("date", "Date unavailable")
                    .replace("T", " ")
                    .take(16),
                away = event.optString("awayTeam", "Away team"),
                home = event.optString("homeTeam", "Home team"),
                spread = marketSummary(event.optJSONArray("spread")),
                total = marketSummary(event.optJSONArray("total")),
                moneyline = marketSummary(event.optJSONArray("moneyline")),
                homeSpread = extractHomeSpread(
            event.optJSONArray("spread"),
            event.optString("homeTeam", ""),
            event.optString("awayTeam", "")
        )
    )
)

    }

    return result
}
private data class TeamRecentStats(
    val pointsFor: Double,
    val pointsAgainst: Double,
    val games: Int
)

private fun nflSeason(date: String): Int {
    val year = date.substring(0, 4).toInt()
    val month = date.substring(5, 7).toInt()
    return if (month >= 7) year else year - 1
}

private fun loadModelData(): ModelData {
    val url = URL(
        "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
    )
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 30000
    connection.setRequestProperty("User-Agent", "NFL-Spread-Lab-Android")

    val csv = try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }

    val lines = csv.lineSequence().iterator()
    if (!lines.hasNext()) error("The NFL data feed was empty.")

    val header = parseCsvLine(lines.next())
        .mapIndexed { index, name -> name.trim().trim('"') to index }
        .toMap()

    fun index(name: String): Int =
        header[name] ?: error("Missing expected data column: $name")

    val dateI = index("gameday")
    val typeI = index("game_type")
    val weekI = index("week")
    val awayI = index("away_team")
    val homeI = index("home_team")
    val awayScoreI = index("away_score")
    val homeScoreI = index("home_score")
    val spreadI = index("spread_line")

    val games = mutableListOf<Game>()

    while (lines.hasNext()) {
        val line = lines.next()
        if (line.isBlank()) continue
        val row = parseCsvLine(line)

        if (row.size <= listOf(
        dateI, typeI, weekI, awayI, homeI,
        awayScoreI, homeScoreI, spreadI
    ).max()) continue

        if (row[typeI].trim().trim('"') != "REG") continue

        val date = row[dateI].trim().trim('"')
        val away = row[awayI].trim().trim('"')
        val home = row[homeI].trim().trim('"')
        if (date.isBlank() || away.isBlank() || home.isBlank()) continue

        games.add(
            Game(
                date = date,
                week = row[weekI].trim().trim('"'),
                away = away,
                home = home,
                awayScore = row[awayScoreI].trim().trim('"').toDoubleOrNull(),
homeScore = row[homeScoreI].trim().trim('"').toDoubleOrNull(),
spreadLine = row[spreadI].trim().trim('"').toDoubleOrNull()
            )
        )
    }

    val completed = games
        .filter { it.homeScore != null && it.awayScore != null }
        .sortedBy { it.date }

    
val ratings = mutableMapOf<String, Double>()

// Existing recent-scoring history; preserve for baseline comparison.
val recentScores =
    mutableMapOf<String, MutableList<Pair<Double, Double>>>()

// Opponent-aware history: points scored, points allowed, opponent.
data class RecentGame(
    val pointsFor: Double,
    val pointsAgainst: Double,
    val opponent: String
)

val opponentRecentScores =
    mutableMapOf<String, MutableList<RecentGame>>()

    var processed = 0
    var tested = 0
    var candidateAbsoluteError = 0.0
var candidateSquaredError = 0.0
var candidateTested = 0
var combinedAbsoluteError = 0.0
var combinedSquaredError = 0.0
var combinedTested = 0
    var absoluteError = 0.0
    var squaredError = 0.0
    var brierTotal = 0.0
    var correctWinners = 0
    var decisiveGames = 0
    var atsPicks = 0
var atsWins = 0
var atsLosses = 0
var atsPushes = 0
var atsNetUnits = 0.0
var activeSeason: Int? = null
    
    for (g in completed) {
        val currentSeason = nflSeason(g.date)

if (activeSeason != currentSeason) {
    recentScores.clear()
    opponentRecentScores.clear()
    activeSeason = currentSeason
}
        val homeRating = ratings.getOrDefault(g.home, 1500.0)
        val awayRating = ratings.getOrDefault(g.away, 1500.0)

        // Predict first; this game's result has not been used yet.
        val predictedWin = expectedHomeWin(homeRating, awayRating)
        val predictedMargin =
            (homeRating - awayRating + HOME_ADVANTAGE_ELO) / ELO_PER_POINT

        // Opponent-adjusted candidate model.
        // Uses only games recorded before the current game.
        val leagueAverage = recentScores.values
            .flatMap { it }
            .map { it.first }
            .average()
            .takeIf { !it.isNaN() } ?: 22.0

        // Estimate opponent offense and defense, shrinking small
        // samples toward the current league scoring average.
        fun opponentStrength(
            opponent: String,
            excludedTeam: String
        ): Pair<Double, Double> {
            val history = opponentRecentScores[opponent]
                .orEmpty()
                .filter { it.opponent != excludedTeam }

            if (history.isEmpty()) {
                return leagueAverage to leagueAverage
            }

            val shrink = history.size.toDouble() /
                (history.size + 5.0)

            val rawOffense = history
                .map { it.pointsFor }
                .average()

            val rawDefense = history
                .map { it.pointsAgainst }
                .average()

            val adjustedOffense = leagueAverage +
                (rawOffense - leagueAverage) * shrink

            val adjustedDefense = leagueAverage +
                (rawDefense - leagueAverage) * shrink

            return adjustedOffense to adjustedDefense
        }

        // Return opponent-adjusted offense and points allowed.
        fun adjustedRecent(team: String): Pair<Double, Double> {
            val history = opponentRecentScores[team].orEmpty()

            if (history.isEmpty()) {
                return leagueAverage to leagueAverage
            }

            val adjustedOffense = history.map { game ->
                val opponentDefense = opponentStrength(
                    game.opponent, team
                ).second

                game.pointsFor +
                    (leagueAverage - opponentDefense)
            }.average()

            val adjustedDefense = history.map { game ->
                val opponentOffense = opponentStrength(
                    game.opponent, team
                ).first

                game.pointsAgainst -
                    (opponentOffense - leagueAverage)
            }.average()

            return adjustedOffense to adjustedDefense
        }

        val homeAdjusted = adjustedRecent(g.home)
        val awayAdjusted = adjustedRecent(g.away)

        val homeRecentOffense = homeAdjusted.first
        val homeRecentDefense = homeAdjusted.second
        val awayRecentOffense = awayAdjusted.first
        val awayRecentDefense = awayAdjusted.second

        val candidateMargin =
            ((homeRecentOffense + awayRecentDefense) / 2.0) -
            ((awayRecentOffense + homeRecentDefense) / 2.0) +
            2.0

        
if (g.date >= VALIDATION_START) {
    val actual = g.homeScore!! - g.awayScore!!

    val candidateError = candidateMargin - actual
    candidateAbsoluteError += abs(candidateError)
    candidateSquaredError += candidateError * candidateError
    candidateTested++

    val combinedMargin = 0.5 * predictedMargin + 0.5 * candidateMargin
    val combinedError = combinedMargin - actual
    combinedAbsoluteError += abs(combinedError)
    combinedSquaredError += combinedError * combinedError
    combinedTested++
}

        val actualMargin = g.homeScore!! - g.awayScore!!
        if (g.date >= VALIDATION_START && g.spreadLine != null) {
    val edge = predictedMargin - g.spreadLine

    // Only test picks with at least a 1.5-point model edge.
    if (abs(edge) >= 1.5) {
        atsPicks++

        when {
            actualMargin == g.spreadLine -> {
                atsPushes++
            }

            (edge > 0.0 && actualMargin > g.spreadLine) ||
            (edge < 0.0 && actualMargin < g.spreadLine) -> {
                atsWins++
                atsNetUnits += 100.0 / 110.0
            }

            else -> {
                atsLosses++
                atsNetUnits -= 1.0
            }
        }
    }
}

        if (g.date >= VALIDATION_START) {
            val error = predictedMargin - actualMargin
            absoluteError += abs(error)
            squaredError += error * error

            val actualWin = when {
                actualMargin > 0.0 -> 1.0
                actualMargin < 0.0 -> 0.0
                else -> 0.5
            }
            brierTotal += (predictedWin - actualWin).pow(2)
            tested++

            if (actualMargin != 0.0) {
                decisiveGames++
                if ((predictedWin >= 0.5) == (actualMargin > 0.0)) {
                    correctWinners++
                }
            }
        }

        // Update ratings only after the prediction is scored.
        val actualHome = when {
            actualMargin > 0.0 -> 1.0
            actualMargin < 0.0 -> 0.0
            else -> 0.5
        }
        val change = ELO_K * (actualHome - predictedWin)
        ratings[g.home] = homeRating + change
ratings[g.away] = awayRating - change

// Record this game only after its prediction is complete.
fun recordRecentScore(
    team: String,
    pointsFor: Double,
    pointsAgainst: Double
) {
    val history = recentScores.getOrPut(team) { mutableListOf() }
    history.add(pointsFor to pointsAgainst)

    // Keep only the eight most recent games.
    while (history.size > 8) {
        history.removeAt(0)
    }
}

fun recordOpponentRecentGame(
    team: String,
    pointsFor: Double,
    pointsAgainst: Double,
    opponent: String
) {
    val history = opponentRecentScores.getOrPut(team) {
        mutableListOf()
    }

    history.add(
        RecentGame(
            pointsFor = pointsFor,
            pointsAgainst = pointsAgainst,
            opponent = opponent
        )
    )

    // The new model uses the last five games.
    while (history.size > 5) {
        history.removeAt(0)
    }
}

recordRecentScore(g.home, g.homeScore!!, g.awayScore!!)
recordRecentScore(g.away, g.awayScore!!, g.homeScore!!)

recordOpponentRecentGame(
    g.home, g.homeScore!!, g.awayScore!!, g.away
)
recordOpponentRecentGame(
    g.away, g.awayScore!!, g.homeScore!!, g.home
)

processed++
    }


val validation = Validation(
    games = tested,
    marginMae = if (tested > 0) absoluteError / tested else 0.0,
    marginRmse = if (tested > 0) sqrt(squaredError / tested) else 0.0,
    candidateMarginMae = if (candidateTested > 0)
        candidateAbsoluteError / candidateTested else 0.0,
    candidateMarginRmse = if (candidateTested > 0)
        sqrt(candidateSquaredError / candidateTested) else 0.0,
    combinedMarginMae = if (combinedTested > 0)
        combinedAbsoluteError / combinedTested else 0.0,
    combinedMarginRmse = if (combinedTested > 0)
        sqrt(combinedSquaredError / combinedTested) else 0.0,
    winAccuracy = if (decisiveGames > 0)
        correctWinners.toDouble() / decisiveGames else 0.0,
    brierScore = if (tested > 0) brierTotal / tested else 0.0,
    atsPicks = atsPicks,
    atsWins = atsWins,
    atsLosses = atsLosses,
    atsPushes = atsPushes,
    atsNetUnits = atsNetUnits
)

    val today = LocalDate.now().toString()
    val upcoming = games
        .filter {
            it.date >= today &&
                it.homeScore == null &&
                it.awayScore == null
        }
        .sortedBy { it.date }
        .take(16)
        .map { g ->
            val homeRating = ratings.getOrDefault(g.home, 1500.0)
            val awayRating = ratings.getOrDefault(g.away, 1500.0)
            
val eloWin = expectedHomeWin(homeRating, awayRating)
val eloMargin =
    (homeRating - awayRating + HOME_ADVANTAGE_ELO) / ELO_PER_POINT



val leagueAverage = games
    .filter {
       
nflSeason(it.date) == nflSeason(g.date) &&
    it.date < g.date &&
    it.homeScore != null &&
    it.awayScore != null
    }
    .flatMap { listOf(it.homeScore!!, it.awayScore!!) }
    .average()
    .takeIf { !it.isNaN() } ?: 22.0

data class RecentGame(
    val pointsFor: Double,
    val pointsAgainst: Double,
    val opponent: String
)

val recentScores = mutableMapOf<String, MutableList<RecentGame>>()

games
    .filter {
        
nflSeason(it.date) == nflSeason(g.date) &&
    it.date < g.date &&
    it.homeScore != null &&
    it.awayScore != null
    }
    .sortedByDescending { it.date }
    .forEach { game ->
        recentScores.getOrPut(game.home) { mutableListOf() }
            .add(
                RecentGame(
                    game.homeScore!!.toDouble(),
                    game.awayScore!!.toDouble(),
                    game.away
                )
            )

        recentScores.getOrPut(game.away) { mutableListOf() }
            .add(
                RecentGame(
                    game.awayScore!!.toDouble(),
                    game.homeScore!!.toDouble(),
                    game.home
                )
            )
    }

fun opponentStrength(opponent: String): Pair<Double, Double> {
    val history = recentScores[opponent].orEmpty().take(5)

    if (history.isEmpty()) return leagueAverage to leagueAverage

    val shrink = history.size.toDouble() / (history.size + 5.0)

    val offense = history.map { it.pointsFor }.average()
    val defense = history.map { it.pointsAgainst }.average()

    return (
        leagueAverage + (offense - leagueAverage) * shrink
    ) to (
        leagueAverage + (defense - leagueAverage) * shrink
    )
}

fun adjustedRecent(team: String): Pair<Double, Double> {
    val history = recentScores[team].orEmpty().take(5)

    if (history.isEmpty()) return leagueAverage to leagueAverage

    val offense = history.map { game ->
        val opponentDefense = opponentStrength(game.opponent).second
        game.pointsFor + (leagueAverage - opponentDefense)
    }.average()

    val defense = history.map { game ->
        val opponentOffense = opponentStrength(game.opponent).first
        game.pointsAgainst - (opponentOffense - leagueAverage)
    }.average()

    return offense to defense
}

val homeAdjusted = adjustedRecent(g.home)
val awayAdjusted = adjustedRecent(g.away)

val homeOffense = homeAdjusted.first
val homeDefense = homeAdjusted.second
val awayOffense = awayAdjusted.first
val awayDefense = awayAdjusted.second

val candidateMargin =
    ((homeOffense + awayDefense) / 2.0) -
    ((awayOffense + homeDefense) / 2.0) + 2.0

val margin = 0.5 * eloMargin + 0.5 * candidateMargin
val win = 0.5 * eloWin +
    0.5 * (1.0 / (1.0 + exp(-margin / 6.5)))


Projection(g, awayRating, homeRating, win, margin)

        }

    return ModelData(upcoming, processed, validation)
}

private fun expectedHomeWin(home: Double, away: Double): Double {
    val difference = home - away + HOME_ADVANTAGE_ELO
    return 1.0 / (1.0 + 10.0.pow(-difference / 400.0))
}

// Logistic approximation to the standard normal cumulative distribution.
private fun normalCdfApprox(z: Double): Double {
    return 1.0 / (1.0 + exp(-1.702 * z))
}

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0

    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted &&
                i + 1 < line.length && line[i + 1] == '"' -> {
                cell.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                values.add(cell.toString())
                cell.setLength(0)
            }
            else -> cell.append(c)
        }
        i++
    }

    values.add(cell.toString())
    return values
}

private fun signed(value: Double): String =
    String.format(Locale.US, "%+.1f", value)

private fun pct(value: Double): String =
    String.format(Locale.US, "%.1f%%", value * 100.0)

private fun decimal(value: Double): String =
    String.format(Locale.US, "%.3f", value)

private fun saveBestBets(picks: List<org.json.JSONObject>): String {
    val connection = (URL(
        "https://nfl-spread-lab-api.onrender.com/best-bets/generate"
    ).openConnection() as HttpURLConnection)

    connection.requestMethod = "POST"
    connection.connectTimeout = 20000
    connection.readTimeout = 45000
    connection.doOutput = true
    connection.setRequestProperty(
        "Content-Type",
        "application/json; charset=utf-8"
    )

    val payload = org.json.JSONObject().put(
        "picks",
        org.json.JSONArray().apply {
            picks.forEach { put(it) }
        }
    )

    try {
        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val response = stream?.bufferedReader()?.use {
            it.readText()
        } ?: ""

        if (status !in 200..299) {
            error("Best Bets API returned HTTP $status: $response")
        }

        return response
    } finally {
        connection.disconnect()
    }
}

private fun buildBestBetPayload(
    projections: List<Projection>,
    oddsGames: List<LiveOddsGame>
): List<org.json.JSONObject> {
    return projections.mapNotNull { p ->
        val matchingOdds = oddsGames.firstOrNull {
            normalizeTeamName(it.home) ==
                normalizeTeamName(p.game.home) &&
            normalizeTeamName(it.away) ==
                normalizeTeamName(p.game.away)
        }

        val homeSpread = matchingOdds?.homeSpread
            ?: return@mapNotNull null

        val edge = p.margin + homeSpread

        if (kotlin.math.abs(edge) < 2.0) {
            return@mapNotNull null
        }

        val isHomeBet = edge > 0.0
        val selection = if (isHomeBet) {
            p.game.home
        } else {
            p.game.away
        }

        val selectedLine = if (isHomeBet) {
            homeSpread
        } else {
            -homeSpread
        }

        val eventId =
            "${p.game.date}_${normalizeTeamName(p.game.away)}_" +
            normalizeTeamName(p.game.home)

        org.json.JSONObject()
            .put("eventId", eventId)
            .put("date", p.game.date)
            .put("awayTeam", p.game.away)
            .put("homeTeam", p.game.home)
            .put("market", "spread")
            .put("selection", selection)
            .put("line", selectedLine)
            .put("odds", -110)
            .put("modelEdge", edge)
    }
}


private suspend fun loadSavedBestBets(): org.json.JSONArray {
    return withContext(Dispatchers.IO) {
        val connection = (URL(
            "https://nfl-spread-lab-api.onrender.com/best-bets"
        ).openConnection() as HttpURLConnection)

        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 45000

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (status !in 200..299) {
                error("Best Bets API returned HTTP $status: $response")
            }

            org.json.JSONArray(response)
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun loadBestBetsStats(): org.json.JSONObject {
    return withContext(Dispatchers.IO) {
        val connection = (URL(
            "https://nfl-spread-lab-api.onrender.com/best-bets/stats"
        ).openConnection() as HttpURLConnection)

        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 45000

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (status !in 200..299) {
                error("Stats API returned HTTP $status: $response")
            }

            org.json.JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
