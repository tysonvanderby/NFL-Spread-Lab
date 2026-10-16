package com.nflspreadlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.exp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpreadLabScreen()
                }
            }
        }
    }
}

@Composable
private fun SpreadLabScreen() {
    var home by remember { mutableStateOf("Home team") }
    var away by remember { mutableStateOf("Away team") }
    var homeRating by remember { mutableStateOf("1500") }
    var awayRating by remember { mutableStateOf("1500") }
    var homeAdvantage by remember { mutableStateOf("2.0") }
    var marketLine by remember { mutableStateOf("-3.0") }
    var result by remember { mutableStateOf<ModelResult?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("NFL SPREAD LAB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("A transparent workspace for NFL spread research")

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("UPCOMING GAMES", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Live schedules and sportsbook lines are not connected yet. No current games or odds are being fabricated.")
                Text("Next: connect a free schedule/results feed and label its source and update time.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("MODEL LAB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Try an Elo-style matchup calculation. This is a sandbox, not a validated betting prediction.")
        OutlinedTextField(away, { away = it }, label = { Text("Away team") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(home, { home = it }, label = { Text("Home team") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Away rating", awayRating, { awayRating = it }, Modifier.weight(1f))
            NumberField("Home rating", homeRating, { homeRating = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Home advantage", homeAdvantage, { homeAdvantage = it }, Modifier.weight(1f))
            NumberField("Market home line", marketLine, { marketLine = it }, Modifier.weight(1f))
        }
        Button(onClick = {
            val h = homeRating.toDoubleOrNull()
            val a = awayRating.toDoubleOrNull()
            val adv = homeAdvantage.toDoubleOrNull()
            val line = marketLine.toDoubleOrNull()
            result = if (h != null && a != null && adv != null && line != null) calculate(h, a, adv, line) else null
        }, modifier = Modifier.fillMaxWidth()) { Text("Calculate sandbox estimate") }

        result?.let { r ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SANDBOX RESULT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${home.ifBlank { "Home team" }} projected margin: ${signed(r.expectedMargin)} points", style = MaterialTheme.typography.titleMedium)
                    Text("Model fair spread (home): ${signed(-r.expectedMargin)}")
                    Text("Home win probability: ${percent(r.homeWinProbability)}")
                    Text("Home cover probability: ${percent(r.homeCoverProbability)}")
                    Text("Model edge vs entered market line: ${signed(r.expectedMargin + (marketLine.toDoubleOrNull() ?: 0.0))} points")
                    Text("Illustrative only: rating-to-points and probability assumptions need historical calibration.")
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("VALIDATION STATUS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("No walk-forward results yet. Once backtesting is implemented, report sample size, ATS record, margin error, closing-line comparison, and probability calibration.")
        Text("Planned data approach: free public NFL schedule and results data, with source and last-updated time shown when connected.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private data class ModelResult(val expectedMargin: Double, val homeWinProbability: Double, val homeCoverProbability: Double)

private fun calculate(homeRating: Double, awayRating: Double, homeAdvantage: Double, marketHomeLine: Double): ModelResult {
    // Deliberately provisional rating conversion and variance; calibrate on historical games before use.
    val expectedMargin = (homeRating - awayRating) / 25.0 + homeAdvantage
    val homeWin = 1.0 / (1.0 + exp(-expectedMargin / 6.0))
    // Home line -3 means the home team needs a margin above 3; push probability is not separately shown.
    val homeCover = 1.0 / (1.0 + exp(-((expectedMargin + marketHomeLine) / (13.5 * 0.55))))
    return ModelResult(expectedMargin, homeWin, homeCover)
}

private fun signed(value: Double): String = if (value > 0) "+%.1f".format(value) else "%.1f".format(value)
private fun percent(value: Double): String = "%.1f%%".format(value * 100.0)
