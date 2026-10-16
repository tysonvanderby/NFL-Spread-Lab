
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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

private const val ELO_K = 20.0
private const val HOME_ADVANTAGE_ELO = 55.0
private const val ELO_PER_POINT = 25.0
private const val VALIDATION_START = "2015-01-01"

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

private data class Validation(
    val games: Int,
    val marginMae: Double,
    val marginRmse: Double,
    val winAccuracy: Double,
    val brierScore: Double
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
        Text("Historical ratings, validation, and game projections")

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

            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("WALK-FORWARD VALIDATION",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("Games tested: ${model.validation.games}")
                    Text("Margin MAE: ${decimal(model.validation.marginMae)} points")
                    Text("Margin RMSE: ${decimal(model.validation.marginRmse)} points")
                    Text("Winner accuracy: ${pct(model.validation.winAccuracy)}")
                    Text("Win probability Brier score: ${decimal(model.validation.brierScore)}")
                    Text(
                        "Each game is predicted before its result updates the ratings. " +
                        "The margin conversion and Elo settings are preliminary. " +
                        "No sportsbook lines are included, so this is not an against-the-spread record.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text("UPCOMING GAME PROJECTIONS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)

            if (model.projections.isEmpty()) {
                Text("No upcoming regular-season games found in the feed.")
            }

            model.projections.forEach { p ->
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("${p.game.away} at ${p.game.home}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("${p.game.date} | Week ${p.game.week}")
                        HorizontalDivider()
                        Text("Home Elo: ${p.homeElo.toInt()} | Away Elo: ${p.awayElo.toInt()}")
                        Text("Home win probability: ${pct(p.homeWin)}")
                        Text("Projected home margin: ${signed(p.margin)} points")
                        Text("Experimental fair home spread: ${signed(-p.margin)}")
                        Text(
                            "Sportsbook lines are not connected. Cover probability and betting edge are not available yet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text("IMPORTANT LIMITATIONS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "This is a basic Elo model, not a proven betting system. " +
                "It currently omits injuries, quarterback changes, weather, and market odds. " +
                "Historical results do not guarantee future performance."
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
                homeScore = row[homeScoreI].trim().trim('"').toDoubleOrNull()
            )
        )
    }

    val completed = games
        .filter { it.homeScore != null && it.awayScore != null }
        .sortedBy { it.date }

    val ratings = mutableMapOf<String, Double>()
    var processed = 0
    var tested = 0
    var absoluteError = 0.0
    var squaredError = 0.0
    var brierTotal = 0.0
    var correctWinners = 0
    var decisiveGames = 0

    for (g in completed) {
        val homeRating = ratings.getOrDefault(g.home, 1500.0)
        val awayRating = ratings.getOrDefault(g.away, 1500.0)

        // Make the prediction BEFORE using this game's result.
        val predictedWin = expectedHomeWin(homeRating, awayRating)
        val predictedMargin =
            (homeRating - awayRating + HOME_ADVANTAGE_ELO) / ELO_PER_POINT

        val actualMargin = g.homeScore!! - g.awayScore!!

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

        // Only now update Elo using the game's actual outcome.
        val actualHome = when {
            actualMargin > 0.0 -> 1.0
            actualMargin < 0.0 -> 0.0
            else -> 0.5
        }
        val change = ELO_K * (actualHome - predictedWin)
        ratings[g.home] = homeRating + change
        ratings[g.away] = awayRating - change
        processed++
    }

    val validation = Validation(
        games = tested,
        marginMae = if (tested > 0) absoluteError / tested else 0.0,
        marginRmse = if (tested > 0) sqrt(squaredError / tested) else 0.0,
        winAccuracy = if (decisiveGames > 0)
            correctWinners.toDouble() / decisiveGames else 0.0,
        brierScore = if (tested > 0) brierTotal / tested else 0.0
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
            val win = expectedHomeWin(homeRating, awayRating)
            val margin =
                (homeRating - awayRating + HOME_ADVANTAGE_ELO) / ELO_PER_POINT

            Projection(g, awayRating, homeRating, win, margin)
        }

    return ModelData(upcoming, processed, validation)
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
