package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.debug.DebugRepository
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.VitalCard
import com.example.vitalcoreai.ui.components.VitalScreenScaffold
import com.example.vitalcoreai.ui.components.VitalTopBar
import com.example.vitalcoreai.ui.viewmodel.DebugViewModel

/**
 * T-14 — the developer screen.
 *
 * Reached by triple-tapping the title on Home, and only in a debug build. It exists so a
 * change to the engines can be verified in minutes against synthetic history instead of
 * waiting a month for a real baseline to accumulate.
 *
 * Everything here is diagnostic. Nothing on this screen is user-facing copy, so it is
 * deliberately exempt from the strings.xml rule — a resource indirection on a developer
 * tool buys nothing and hides what is being shown.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Developer",
                subtitle = "Diagnostics and synthetic data",
                accent = StrainAccent,
                onBack = onBack
            )
        }
    ) {
        if (state.isLoading && snapshot == null) {
            item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StrainAccent, strokeWidth = 2.dp)
                }
            }
            return@VitalScreenScaffold
        }

        state.lastActionResult?.let { result ->
            item {
                VitalCard(accent = ActivityAccent) {
                    Text(result, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                }
            }
        }

        if (snapshot != null) {
            item { BuildCard(snapshot) }
            item { SyncCard(snapshot) }
            item { EngineCard(snapshot) }
            item { DatabaseCard(snapshot) }
        }

        item {
            SyntheticDataCard(
                scenarios = state.scenarios,
                isWorking = state.isWorking,
                onLoad = viewModel::loadScenario,
                onRestoreClock = viewModel::restoreRealClock
            )
        }

        if (snapshot != null) {
            item {
                CoachContextCard(
                    json = snapshot.coachContextJson,
                    expanded = state.showCoachContext,
                    onToggle = viewModel::toggleCoachContext
                )
            }
            item { ErrorCard(snapshot, onClear = viewModel::clearErrors) }
        }

        item {
            OutlinedButton(
                onClick = viewModel::refresh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Refresh diagnostics") }
        }
    }
}

@Composable
private fun BuildCard(s: DebugRepository.Snapshot) = DebugCard("Build") {
    KeyValue("Version", "${s.versionName} (${s.versionCode})")
    KeyValue("Build type", if (s.isDebugBuild) "debug" else "release")
    KeyValue("Room schema", "v${s.schemaVersion}")
    KeyValue("Time zone", s.zone)
    KeyValue("Today (epoch day)", s.todayEpochDay.toString())
    KeyValue("Day length", "${VitalTime.lengthOfDayMinutes(s.todayEpochDay)} min")
}

@Composable
private fun SyncCard(s: DebugRepository.Snapshot) = DebugCard("Sync & permissions") {
    KeyValue("Health Connect", if (s.healthConnectAvailable) "available" else "unavailable")
    KeyValue("Permissions", s.permissionSummary)
    KeyValue(
        "Last sync",
        s.lastSyncMs?.let {
            val ageMin = ((VitalTime.nowMs() - it) / 60_000L).coerceAtLeast(0)
            "${VitalTime.formatDurationMinutes(ageMin.toInt())} ago"
        } ?: "never"
    )
    KeyValue("Days of history", s.daysOfHistory.toString())
    Spacer(Modifier.height(Spacing.sm))
    Text("RECORDS TODAY", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
    s.recordsToday.forEach { (name, count) -> KeyValue(name, count.toString()) }
}

@Composable
private fun EngineCard(s: DebugRepository.Snapshot) = DebugCard("Analytics engines") {
    s.timings.forEach { t ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status is a word, not just a tint — the debug screen follows the same
            // no-colour-only rule as the rest of the app.
            Text(
                if (t.ok) "OK" else "FAIL",
                style = VitalCoreType.monoTiny,
                color = if (t.ok) PositiveDelta else AlertRed,
                modifier = Modifier.width(44.dp)
            )
            Text(
                t.name,
                style = MaterialTheme.typography.bodyLarge,
                color = OnBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (t.millis > 0) "${t.millis} ms" else "",
                style = VitalCoreType.monoTiny,
                color = OnSurfaceDim
            )
        }
        Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
    }
}

@Composable
private fun DatabaseCard(s: DebugRepository.Snapshot) = DebugCard("Database") {
    KeyValue("File size", "${s.databaseBytes / 1024} KB")
    s.rowCounts.forEach { (table, count) ->
        KeyValue(table, if (count < 0) "unreadable" else count.toString())
    }
}

@Composable
private fun SyntheticDataCard(
    scenarios: List<com.example.vitalcoreai.debug.SyntheticDataGenerator.Descriptor>,
    isWorking: Boolean,
    onLoad: (String) -> Unit,
    onRestoreClock: () -> Unit
) = DebugCard("Synthetic data") {
    Text(
        "Replaces the derived tables with a 30-day fixture and re-scores every day. " +
            "Check-ins and journal entries are left alone. Debug builds only.",
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurfaceDim
    )
    Spacer(Modifier.height(Spacing.sm))

    scenarios.forEach { scenario ->
        Column(Modifier.padding(vertical = Spacing.xs)) {
            Button(
                onClick = { onLoad(scenario.id) },
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StrainAccent.copy(alpha = Alphas.tintedFill),
                    contentColor = OnBackground
                )
            ) {
                Icon(
                    Icons.Filled.Science,
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.iconSm),
                    tint = StrainAccent
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(scenario.title, style = MaterialTheme.typography.bodyLarge)
            }
            Text(scenario.description, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
    }

    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(
        onClick = onRestoreClock,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text("Restore the real clock and zone") }
}

@Composable
private fun CoachContextCard(
    json: String?,
    expanded: Boolean,
    onToggle: () -> Unit
) = DebugCard("AI context preview") {
    Text(
        "The exact payload an external model would receive. Nothing is sent anywhere: " +
            "the app declares no INTERNET permission and ships no HTTP client.",
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurfaceDim
    )
    Spacer(Modifier.height(Spacing.sm))
    if (json == null) {
        Text("Not enough data to build a context today.", style = MaterialTheme.typography.bodyLarge, color = OnSurfaceDim)
        return@DebugCard
    }
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text(if (expanded) "Hide JSON (${json.length} chars)" else "Show JSON (${json.length} chars)") }

    if (expanded) {
        Spacer(Modifier.height(Spacing.sm))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(VitalShapes.Tile)
                .background(SurfaceL3)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                json,
                style = VitalCoreType.monoTiny,
                color = OnBackground,
                modifier = Modifier
                    .padding(Spacing.md)
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun ErrorCard(s: DebugRepository.Snapshot, onClear: () -> Unit) = DebugCard("Errors") {
    if (s.errors.isEmpty()) {
        Text("Nothing recorded.", style = MaterialTheme.typography.bodyLarge, color = OnSurfaceDim)
        return@DebugCard
    }
    s.errors.forEach { e ->
        Column(Modifier.padding(vertical = Spacing.xxs)) {
            Text(
                "${VitalTime.formatClock(VitalTime.minuteOfDay(e.timestampMs))} · ${e.source}",
                style = VitalCoreType.monoTiny,
                color = AlertRed
            )
            Text(e.message, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        }
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(
        onClick = onClear,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text("Clear") }
}

@Composable
private fun DebugCard(title: String, content: @Composable () -> Unit) {
    VitalCard(modifier = Modifier.fillMaxWidth(), accent = StrainAccent) {
        Text(
            title.uppercase(),
            style = VitalCoreType.eyebrow,
            color = StrainAccent,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(Spacing.sm))
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceDim)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = OnBackground
        )
    }
}
