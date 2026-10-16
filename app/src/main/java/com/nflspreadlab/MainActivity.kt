
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
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.exp

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

private data class ScheduledGame(
    val date: String,
    val away: String,
    val home: String,
    val week: String
)

private data class ModelResult(
    val expectedMargin: Double,
    val homeWinProbability: Double,
    val homeCoverProbability: Double
)

@Composable
private fun SpreadLabScreen() {
    var games by remember { mutableStateOf<List<ScheduledGame>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var updated by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    var home by remember { mutableStateOf("Home team") }
    var away by remember { mutableStateOf("Away team") }
    var homeRating by remember { mutableStateOf("1500") }
    var awayRating by remember { mutableStateOf("1500") }
    var homeAdvantage by remember { mutableStateOf("2.0") }
    var marketLine by remember { mutableStateOf("-3.0") }
    var result by remember { mutableStateOf<ModelResult?>(null) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = ""
        try {
            val fetched = withContext(Dispatchers.IO) {
                fetchUpcomingGames()
            }
            games = fetched
            updated = LocalDateTime.now().toString()
                .replace("T", " ").take(16)
        } catch (e: Exception) {
            error = e.message ?: "Unable to load schedule."
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "NFL SPREAD LAB",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black
        )
        Text("NFL matchup research and spread analysis")

        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "UPCOMING GAMES",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text("Source: nflverse public schedule data")

                Button(
                    onClick = { refreshKey++ },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Loading schedule..." else "Refresh NFL schedule")
                }

                if (error.isNotBlank()) {
                    Text("Schedule error: $error")
                    Text("Check your internet connection and try again.")
                } else if (loading) {
                    CircularProgressIndicator()
                } else if (games.isEmpty()) {
                    Text("No upcoming regular-season games were found.")
                } else {
                    games.forEach { game ->
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "${game.away} at ${game.home}",
                                fontWeight = FontWeight.Bold
                            )
                            Text("Date: ${game.date}  |  Week ${game.week}")
                            Text(
                                "Schedule only — prediction not yet available.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (updated.isNotBlank()) {
                    Text(
                        "Retrieved: $updated (device time)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Text(
            "MODEL LAB",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text("Manual sandbox only; not yet historically validated.")

        OutlinedTextField(
            away, { away = it },
            label = { Text("Away team") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            home, { home = it },
            label = { Text("Home team") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                "Away rating", awayRating, { awayRating = it },
                Modifier.weight(1f)
            )
            NumberField(
                "Home rating", homeRating, { homeRating = it },
                Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                "Home advantage", homeAdvantage, { homeAdvantage = it },
                Modifier.weight(1f)
            )
            NumberField(
                "Market home line", marketLine, { marketLine = it },
                Modifier.weight(1f)
            )
        }

        Button(
            onClick = {
                val h = homeRating.toDoubleOrNull()
                val a = awayRating.toDoubleOrNull()
                val adv = homeAdvantage.toDoubleOrNull()
                val line = marketLine.toDoubleOrNull()
                result = if (h != null && a != null && adv != null && line != null) {
                    calculate(h, a, adv, line)
                } else null
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Calculate sandbox estimate")
        }

        result?.let { r ->
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("SANDBOX RESULT", fontWeight = FontWeight.Bold)
                    Text("${home.ifBlank { "Home team" }} projected margin: ${signed(r.expectedMargin)}")
                    Text("Model fair home spread: ${signed(-r.expectedMargin)}")
                    Text("Home win probability: ${percent(r.homeWinProbability)}")
                    Text("Home cover probability: ${percent(r.homeCoverProbability)}")
                    Text("Edge vs entered line: ${signed(r.expectedMargin + (marketLine.toDoubleOrNull() ?: 0.0))}")
                    Text("Illustrative only; assumptions are not calibrated.")
                }
            }
        }

        Text("VALIDATION STATUS", fontWeight = FontWeight.Bold)
        Text("No verified walk-forward results yet. We still need historical testing before treating these estimates as predictions.")
    }
}

private fun fetchUpcomingGames(): List<ScheduledGame> {
    val url = URL(
        "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
    )
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 20000
    connection.setRequestProperty("User-Agent", "NFL-Spread-Lab-Android")

    try {
        val csv = connection.inputStream.bufferedReader().use { it.readText() }
        val today = LocalDate.now().toString()

        return csv.lineSequence()
            .drop(1)
            .map { it.split(',') }
            .filter { row ->
                row.size > 10 &&
                row[2].trim().trim('"') == "REG" &&
                row[4].trim().trim('"') >= today &&
                (row[8].isBlank() || row[8].trim() == "NA") &&
                (row[10].isBlank() || row[10].trim() == "NA")
            }
            .map { row ->
                ScheduledGame(
                    date = row[4].trim().trim('"'),
                    away = row[7].trim().trim('"'),
                    home = row[9].trim().trim('"'),
                    week = row[3].trim().trim('"')
                )
            }
            .sortedBy { it.date }
            .take(10)
            .toList()
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value, onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun calculate(
    homeRating: Double,
    awayRating: Double,
    homeAdvantage: Double,
    marketHomeLine: Double
): ModelResult {
    val margin = (homeRating - awayRating) / 25.0 + homeAdvantage
    val win = 1.0 / (1.0 + exp(-margin / 6.0))
    val cover = 1.0 / (
        1.0 + exp(-((margin + marketHomeLine) / (13.5 * 0.55)))
    )
    return ModelResult(margin, win, cover)
}

private fun signed(value: Double) =
    String.format(Locale.US, "%+.1f", value)

private fun percent(value: Double) =
    String.format(Locale.US, "%.1f%%", value * 100.0)
