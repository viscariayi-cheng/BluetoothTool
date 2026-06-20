package com.example.bluetoothtool.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bluetoothtool.model.ThroughputStats

@Composable
fun AppCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatsCard(stats: ThroughputStats) {
    AppCard(title = "Throughput") {
        Text(
            text = "%.3f Mbps".format(stats.mbps),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusLine("Bytes", stats.bytes.toString())
        StatusLine("Elapsed", "%.1f s".format(stats.elapsedMillis / 1_000.0))
    }
}

@Composable
fun LogsCard(logs: List<String>) {
    AppCard(title = "Logs") {
        if (logs.isEmpty()) {
            Text(
                text = "No logs yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                logs.takeLast(16).forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}