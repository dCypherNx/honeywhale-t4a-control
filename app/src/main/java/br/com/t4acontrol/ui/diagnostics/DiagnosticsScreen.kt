package br.com.t4acontrol.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.BuildConfig
import br.com.t4acontrol.R
import br.com.t4acontrol.ui.MdiIcon
import br.com.t4acontrol.ui.T4AUiActions
import br.com.t4acontrol.ui.T4AUiTokens
import java.util.Locale

private const val RAW_LOG_DISPLAY_LIMIT = 500

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
    val filterScroll = rememberScrollState()
    val vertical = rememberLazyListState()
    val knownOrigins = remember {
        mutableStateListOf<String>().apply {
            addAll(
                rawHistory.asSequence()
                    .map(::rawLogOrigin)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .toList()
            )
        }
    }
    var processedOriginCount by remember { mutableIntStateOf(rawHistory.size) }

    LaunchedEffect(rawHistory.size) {
        if (rawHistory.size < processedOriginCount) {
            knownOrigins.clear()
            knownOrigins.addAll(
                rawHistory.asSequence()
                    .map(::rawLogOrigin)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .toList()
            )
            processedOriginCount = rawHistory.size
        } else if (rawHistory.size > processedOriginCount) {
            for (index in processedOriginCount until rawHistory.size) {
                val origin = rawLogOrigin(rawHistory[index])
                if (origin.isNotBlank() && origin !in knownOrigins) knownOrigins.add(origin)
            }
            processedOriginCount = rawHistory.size
        }
    }

    val filterOptions = listOf("ALL") + knownOrigins.sorted()
    val visibleEntries = remember(rawHistory.size, rawLogFilter) {
        latestMatchingEntries(rawHistory, rawLogFilter, RAW_LOG_DISPLAY_LIMIT)
    }
    val buildType = if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"
    val buildSequence = BuildConfig.VERSION_CODE % 100000

    var viewportWidthPx by remember(rawLogFilter) { mutableIntStateOf(0) }
    var maxObservedLineWidthPx by remember(rawLogFilter) { mutableIntStateOf(0) }
    var horizontalOffsetPx by remember(rawLogFilter) { mutableFloatStateOf(0f) }
    val maxHorizontalScrollPx = (maxObservedLineWidthPx - viewportWidthPx).coerceAtLeast(0).toFloat()
    val horizontalScrollable = rememberScrollableState { delta ->
        val oldOffset = horizontalOffsetPx
        val newOffset = (oldOffset - delta).coerceIn(0f, maxHorizontalScrollPx)
        horizontalOffsetPx = newOffset
        oldOffset - newOffset
    }

    LaunchedEffect(maxHorizontalScrollPx) {
        horizontalOffsetPx = horizontalOffsetPx.coerceIn(0f, maxHorizontalScrollPx)
    }
    LaunchedEffect(filterOptions) {
        if (rawLogFilter !in filterOptions) actions.setRawLogFilter("ALL")
    }

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
            Text(
                text = "RideDash ${BuildConfig.VERSION_NAME} · f$buildSequence · $buildType · code ${BuildConfig.VERSION_CODE}",
                color = foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 3.dp),
            )
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
                    .padding(8.dp)
                    .clipToBounds()
                    .onSizeChanged { viewportWidthPx = it.width }
                    .scrollable(
                        state = horizontalScrollable,
                        orientation = Orientation.Horizontal,
                    ),
            ) {
                LazyColumn(
                    state = vertical,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 9.dp),
                ) {
                    itemsIndexed(
                        items = visibleEntries,
                        key = { index, entry -> "$index:$entry" },
                    ) { _, entry ->
                        Text(
                            entry,
                            color = foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                            modifier = Modifier
                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                .onSizeChanged { size ->
                                    if (size.width > maxObservedLineWidthPx) {
                                        maxObservedLineWidthPx = size.width
                                    }
                                }
                                .graphicsLayer {
                                    translationX = -horizontalOffsetPx
                                },
                        )
                    }
                }
                LazyVerticalScrollIndicator(
                    state = vertical,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = actions::copyRawLog) { Text(stringResource(R.string.copy)) }
                OutlinedButton(onClick = actions::saveRawLog) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun LazyVerticalScrollIndicator(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems <= visibleItems.size || visibleItems.isEmpty()) return

    val firstVisible = visibleItems.first()
    val itemProgress = if (firstVisible.size > 0) {
        state.firstVisibleItemScrollOffset.toFloat() / firstVisible.size.toFloat()
    } else {
        0f
    }
    val effectiveFirstIndex = state.firstVisibleItemIndex + itemProgress
    val maxFirstIndex = (totalItems - visibleItems.size).coerceAtLeast(1)
    val scrollProgress = (effectiveFirstIndex / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
    val visibleFraction = (visibleItems.size.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)

    BoxWithConstraints(modifier = modifier.width(6.dp)) {
        val thumbHeight = maxOf(28.dp, maxHeight * visibleFraction).coerceAtMost(maxHeight)
        val travel = maxHeight - thumbHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(3.dp),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = travel * scrollProgress)
                .width(4.dp)
                .height(thumbHeight)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f),
                    RoundedCornerShape(3.dp),
                ),
        )
    }
}

private fun latestMatchingEntries(history: List<String>, filter: String, limit: Int): List<String> {
    if (history.isEmpty() || limit <= 0) return emptyList()
    val result = ArrayList<String>(minOf(limit, history.size))
    for (index in history.lastIndex downTo 0) {
        val entry = history[index]
        if (filter == "ALL" || rawLogOrigin(entry) == filter) {
            result.add(entry)
            if (result.size == limit) break
        }
    }
    return result
}

private fun rawLogOrigin(entry: String): String {
    val payload = entry.substringAfter(' ', "").trim()
    if (payload.isEmpty()) return "OTHER"
    val bracketed = Regex("^\\[([^]]+)]").find(payload)?.groupValues?.getOrNull(1)?.trim()
    if (!bracketed.isNullOrEmpty()) return bracketed.uppercase(Locale.ROOT)
    val prefix = payload.substringBefore(':').substringBefore(' ').trim().trim('[', ']', '(', ')')
    return prefix.ifEmpty { "OTHER" }.uppercase(Locale.ROOT)
}
