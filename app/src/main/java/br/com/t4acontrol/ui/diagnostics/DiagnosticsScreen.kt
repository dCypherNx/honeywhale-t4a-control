package br.com.t4acontrol.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R
import br.com.t4acontrol.ui.MdiIcon
import br.com.t4acontrol.ui.T4AUiActions
import br.com.t4acontrol.ui.T4AUiTokens
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun DashboardEvents(entries: List<String>, foreground: Color, actions: T4AUiActions) {
    val visibleEntries = entries.takeLast(10)
    var expanded by remember { mutableStateOf(actions.sectionExpanded("events", false)) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        actions.setSectionExpanded("events", expanded)
                    }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdiIcon("cmd-format-list-bulleted", T4AUiTokens.Blue, 20.dp, Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.events), color = T4AUiTokens.Blue, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                MdiIcon(if (expanded) "cmd-chevron-up" else "cmd-chevron-down", T4AUiTokens.Blue, 18.dp, Modifier.size(28.dp))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    visibleEntries.asReversed().forEach { entry ->
                        Text(entry, color = foreground, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RawLogCard(
    rawHistory: List<String>,
    rawLogFilter: String,
    foreground: Color,
    actions: T4AUiActions,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val filterScroll = rememberScrollState()
    val origins = rawHistory.asSequence().map(::rawLogOrigin).filter { it.isNotBlank() }.distinct().sorted().toList()
    val filterOptions = listOf("ALL") + origins
    LaunchedEffect(filterOptions) {
        if (rawLogFilter !in filterOptions) actions.setRawLogFilter("ALL")
    }
    val visibleEntries = if (rawLogFilter == "ALL") rawHistory else rawHistory.filter { rawLogOrigin(it) == rawLogFilter }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MdiIcon("cmd-format-list-bulleted", T4AUiTokens.Blue, 20.dp, Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.raw_log), color = T4AUiTokens.Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(filterScroll).padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filterOptions.forEach { filter ->
                    FilterChip(
                        selected = rawLogFilter == filter,
                        onClick = { actions.setRawLogFilter(filter) },
                        label = { Text(if (filter == "ALL") stringResource(R.string.raw_filter_all) else filter, fontSize = 11.sp) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            ) {
                Column(modifier = Modifier.padding(end = 8.dp).horizontalScroll(horizontal).verticalScroll(vertical)) {
                    visibleEntries.asReversed().forEach { entry ->
                        Text(entry, color = foreground, fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false)
                    }
                }
                VerticalScrollIndicator(scrollState = vertical, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = actions::copyRawLog) { Text(stringResource(R.string.copy)) }
                OutlinedButton(onClick = actions::saveRawLog) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun VerticalScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    var viewportHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(modifier.onSizeChanged { viewportHeight = it.height }) {
        if (scrollState.maxValue > 0 && viewportHeight > 0) {
            val viewport = viewportHeight.toFloat()
            val content = viewport + scrollState.maxValue.toFloat()
            val minThumb = with(density) { 24.dp.toPx() }
            val thumb = max(minThumb, viewport * viewport / content).coerceAtMost(viewport)
            val travel = viewport - thumb
            val y = travel * scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(99.dp)),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y.roundToInt()) }
                    .width(3.dp)
                    .height(with(density) { thumb.toDp() })
                    .background(T4AUiTokens.Blue.copy(alpha = 0.72f), RoundedCornerShape(99.dp)),
            )
        }
    }
}

private fun rawLogOrigin(entry: String): String {
    val payload = entry.substringAfter(' ', "").trim()
    if (payload.isEmpty()) return "OTHER"
    val bracketed = Regex("^\\[([^]]+)]").find(payload)?.groupValues?.getOrNull(1)?.trim()
    if (!bracketed.isNullOrEmpty()) return bracketed.uppercase(Locale.ROOT)
    val prefix = payload.substringBefore(':').substringBefore(' ').trim().trim('[', ']', '(', ')')
    return prefix.ifEmpty { "OTHER" }.uppercase(Locale.ROOT)
}
