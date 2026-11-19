// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable internal fun InsetRuleSurface(enabled: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = .18f),
        border = BorderStroke(1.dp, if (pressed || focused) colors.primary.copy(alpha = .65f) else colors.outlineVariant.copy(alpha = .45f))) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun RuleOrderDialog(rules: List<String>, enabled: Boolean, dismiss: () -> Unit, save: (List<Int>) -> Unit) {
    var order by remember(rules) { mutableStateOf(rules.indices.toList()) }
    var error by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maxSpeed = with(density) { 700.dp.toPx() }
    var heldId by remember { mutableStateOf<Int?>(null) }
    var heldTop by remember { mutableFloatStateOf(0f) }
    var heldHeight by remember { mutableIntStateOf(0) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var beforeDrag by remember { mutableStateOf<List<Int>?>(null) }
    var pendingAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun move(from: Int, to: Int) {
        if (from == to || from !in 0 until order.lastIndex || to !in 0 until order.lastIndex) return
        val anchor = list.firstVisibleItemIndex
        val anchorOffset = list.firstVisibleItemScrollOffset
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        // Stable keys must identify the held row, but must not make moving the
        // top visible row scroll the viewport to follow its old key.
        if (from == anchor || to == anchor) pendingAnchor = anchor to anchorOffset
    }
    fun moveHeld() {
        val id = heldId ?: return
        val slots = list.layoutInfo.visibleItemsInfo.map { RuleDragSlot(it.index, it.offset, it.size) }
        val target = RuleDragGeometry.closestIndex(heldTop + heldHeight / 2f, slots) ?: return
        move(order.indexOf(id), target)
    }
    fun finishDrag(cancel: Boolean) {
        if (cancel) beforeDrag?.let { order = it }
        heldId = null
        beforeDrag = null
    }

    // Apply the anchor after the new keyed items enter composition. Doing it
    // inside the pointer callback can remeasure the previous item order.
    LaunchedEffect(order) {
        pendingAnchor?.let { (index, offset) ->
            pendingAnchor = null
            list.scrollToItem(index, offset)
        }
    }
    LaunchedEffect(enabled) { if (!enabled) finishDrag(cancel = true) }
    LaunchedEffect(heldId) {
        if (heldId == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (heldId != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val layout = list.layoutInfo
            val velocity = RuleDragGeometry.scrollVelocity(pointerY, layout.viewportStartOffset,
                layout.viewportEndOffset, edge, maxSpeed)
            if ((velocity < 0 && list.canScrollBackward) || (velocity > 0 && list.canScrollForward)) {
                list.scrollBy(velocity * seconds)
                moveHeld()
            }
        }
    }

    AlertDialog(onDismissRequest = dismiss, title = { Text("规则排序") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 400.dp)
                        .pointerInput(enabled, rules) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { point ->
                                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
                                        point.y >= it.offset && point.y < it.offset + it.size
                                    }
                                    if (item != null) {
                                        beforeDrag = order.toList()
                                        heldId = item.key as Int
                                        heldTop = item.offset.toFloat()
                                        heldHeight = item.size
                                        pointerY = point.y
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, amount ->
                                    if (heldId != null) {
                                        change.consume()
                                        pointerY = change.position.y
                                        heldTop += amount.y
                                        moveHeld()
                                    }
                                },
                                onDragEnd = { finishDrag(cancel = false) },
                                onDragCancel = { finishDrag(cancel = true) })
                        }) {
                    itemsIndexed(order.dropLast(1), key = { _, id -> id }) { index, id ->
                        val actions = if (enabled && heldId == null) buildList {
                            if (index > 0) add(CustomAccessibilityAction("上移") { move(index, index - 1); true })
                            if (index < order.lastIndex - 1) add(CustomAccessibilityAction("下移") { move(index, index + 1); true })
                        } else emptyList()
                        SortRuleRow(rules[id], index, false, modifier = Modifier
                            .animateItemPlacement()
                            .alpha(if (id == heldId) 0f else 1f)
                            .semantics(mergeDescendants = true) {
                                stateDescription = "第 ${index + 1} 条规则，长按拖动"
                                customActions = actions
                            })
                    }
                }
                heldId?.let { id ->
                    SortRuleRow(rules[id], order.indexOf(id), true, modifier = Modifier
                        .offset { IntOffset(0, heldTop.roundToInt()) }.zIndex(1f))
                }
            }
            // MATCH is outside the draggable list: it remains visible and can
            // neither be picked up nor become a drop target during scrolling.
            HorizontalDivider()
            SortRuleRow(rules.last(), order.lastIndex, false, fixed = true)
            if (error.isNotBlank()) Note(error, error = true)
        }
    }, confirmButton = { TextButton(enabled = enabled && heldId == null, onClick = {
        runCatching { save(order) }.onFailure { error = it.message ?: "排序失败。" }
    }) { Text("完成") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun SortRuleRow(rule: String, index: Int, lifted: Boolean,
    modifier: Modifier = Modifier, fixed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurfaceVariant
    val title = if (fixed) "其余所有流量 · MATCH" else rule.split(',').take(2).joinToString(" · ")
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = if (lifted) colors.primaryContainer else colors.surfaceVariant.copy(alpha = .2f),
        border = BorderStroke(1.dp, if (lifted) colors.primary.copy(alpha = .55f) else colors.outlineVariant.copy(alpha = .35f)),
        shadowElevation = if (lifted) 6.dp else 0.dp) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${index + 1}", color = ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 22.dp))
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!fixed) Canvas(Modifier.size(22.dp).semantics { contentDescription = "长按拖动" }) {
                for (fraction in listOf(.3f, .5f, .7f)) drawLine(ink,
                    Offset(size.width * .2f, size.height * fraction), Offset(size.width * .8f, size.height * fraction),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
