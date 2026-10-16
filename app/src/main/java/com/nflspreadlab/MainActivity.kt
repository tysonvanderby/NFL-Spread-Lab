
package com.nflspreadlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.math.pow

private const val ELO_K = 20.0
private const val HOME_ADVANTAGE_ELO = 55.0
private const val ELO_PER_POINT = 25.0

private data class Game(
    val date: String,
    val week: String,
    val away: String,
    val home: String,
    val awayScore: Double?,
    val homeScore: Double?
)

private data class Projection(
    val game: Game,
    val awayElo: Double,
    val homeElo: Double,
    val homeWin: Double,
    val margin: Double
)

private data class ModelData(
    val projections: List<Projection>,
    val historicalGames: Int
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

    LaunchedEffect(refresh) {
        loading = true
        error = ""
        try {
            data = withContext(Dispatchers.IO) { loadModelData() }
        } catch (e: Exception) {
            error = e.message ?: "Unable to load NFL data."
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
        Text("Historical ratings and experimental game projections")

        Button(
            onClick = { refresh++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Loading NFL results..." else "Refresh data and predictions")
        }

        if (loading) {
            CircularProgressIndicator()
        }

        if (error.isNotBlank()) {
            Text("Data error: $error")
            Text("Check your connection and try refreshing.")
        }

        data?.let { model ->
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "MODEL STATUS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Historical games processed: ${model.historicalGames}")
                    Text("Model: Elo ratings, K = ${ELO_K.toInt()}")
                    Text("Source: public nflverse games.csv")
                    Text("Home-field advantage: $HOME_ADVANTAGE_ELO Elo points")
                    Text("Rating-to-points conversion: provisional")
                }
            }

            Text(
                "UPCOMING GAME PROJECTIONS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (model.projections.isEmpty()) {
                Text("No upcoming regular-season games found in the feed.")
            }

            model.projections.forEach { p ->
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${p.game.away} at ${p.game.home}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("${p.game.date}  |  Week ${p.game.week}")
                        HorizontalDivider()
                        Text(
                            "Home Elo: ${p.homeElo.toInt()}  |  Away Elo: ${p.awayElo.toInt()}"
                        )
                        Text("Home win probability: ${pct(p.homeWin)}")
                        Text("Projected home margin: ${signed(p.margin)} points")
                        Text("Experimental fair home spread: ${signed(-p.margin)}")
                        Text(
                            "No sportsbook line connected. Cover probability and betting edge are not available yet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text(
                "HOW TO READ THIS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Elo ratings update from completed regular-season games in date order. " +
                    "Upcoming games use ratings calculated before those games are played."
            )
            Text(
                "The win probability is a model estimate, not a guarantee. " +
                    "The point-margin conversion and home advantage still need walk-forward calibration."
            )
            Text(
                "VALIDATION: Not yet completed. No verified ATS record or profitability claim is being made."
            )
        }
    }
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

    val games = mutableListOf<Game>()

    while (lines.hasNext()) {
        val line = lines.next()
        if (line.isBlank()) continue

        val row = parseCsvLine(line)
        if (row.size <= listOf(
                dateI, typeI, weekI, awayI, homeI, awayScoreI, homeScoreI
            ).max()
        ) continue

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
                homeScore = row[homeScoreI].trim().trim('"').toDoubleOrNull()
            )
        )
    }

    val completed = games
        .filter { it.homeScore != null && it.awayScore != null }
        .sortedBy { it.date }

    val ratings = mutableMapOf<String, Double>()
    var processed = 0

    for (g in completed) {
        val homeRating = ratings.getOrDefault(g.home, 1500.0)
        val awayRating = ratings.getOrDefault(g.away, 1500.0)

        val expectedHome = expectedHomeWin(homeRating, awayRating)
        val actualHome = when {
            g.homeScore!! > g.awayScore!! -> 1.0
            g.homeScore < g.awayScore -> 0.0
            else -> 0.5
        }

        val change = ELO_K * (actualHome - expectedHome)
        ratings[g.home] = homeRating + change
        ratings[g.away] = awayRating - change
        processed++
    }

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
            val win = expectedHomeWin(homeRating, awayRating)
            val margin =
                (homeRating - awayRating + HOME_ADVANTAGE_ELO) / ELO_PER_POINT

            Projection(
                game = g,
                awayElo = awayRating,
                homeElo = homeRating,
                homeWin = win,
                margin = margin
            )
        }

    return ModelData(upcoming, processed)
}

private fun expectedHomeWin(home: Double, away: Double): Double {
    val difference = home - away + HOME_ADVANTAGE_ELO
    return 1.0 / (1.0 + 10.0.pow(-difference / 400.0))
}

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0

    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length &&
                line[i + 1] == '"' -> {
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
