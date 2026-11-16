/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.keliver.compose.foundation.background
import dev.keliver.compose.foundation.clickable
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.Row
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.fillMaxWidth
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.layout.padding
import dev.keliver.compose.foundation.layout.width
import dev.keliver.compose.foundation.lazy.LazyColumn
import dev.keliver.compose.foundation.lazy.items
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.text.style.TextOverflow
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.ui.unit.sp
import dev.keliver.sample.threeway.data.Item
import dev.keliver.sample.threeway.data.ItemsPage
import dev.keliver.sample.threeway.data.ThreewayApi
import dev.keliver.sample.threeway.data.ThreewayMarks
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

// The three-way benchmark's screens (docs/THREE_WAY_BENCHMARK.md), written once as plain Compose.
// The guest compiles this file against the generated API; the Android host compiles a copy whose
// imports say `androidx.compose.` instead of `dev.keliver.compose.` (only imports differ). The web
// form (host-android/src/threeway/assets/threeway-web/index.html) draws the same layout in HTML.
//
// Fixed geometry (the benchmark driver taps by position, the same in every form): header 56 dp,
// action row 48 dp, status line 24 dp, list rows 72 dp each.

/** Navigation marks are numbered from here, request marks from 1: one key space for both. */
private const val NAV_BASE = 100_000

private val Indigo = Color(0xFF3F51B5)
private val Ink = Color(0xFF1C1B1F)
private val Muted = Color(0xFF6B6B73)
private val ActionBg = Color(0xFFE8EAF6)

private fun chipColors(status: String): Pair<Color, Color> = when (status) {
  "Paid" -> Color(0xFFDFF5E1) to Color(0xFF1B5E20)
  "Pending" -> Color(0xFFFFF3CD) to Color(0xFF7A5B00)
  "Shipped" -> Color(0xFFDCEBFF) to Color(0xFF0D47A1)
  "Refunded" -> Color(0xFFEDE7F6) to Color(0xFF4527A0)
  else -> Color(0xFFFDE2E1) to Color(0xFFB71C1C)
}

/**
 * How the app stacks its screens: [keys] bottom to top, the last one shown, each composed by
 * [content]. [onBack] goes back one screen (the stack calls it for a system back press).
 */
interface ThreewayStack {
  @Composable
  fun Show(keys: List<Any>, onBack: () -> Unit, content: @Composable (Any) -> Unit)
}

/** The list's key in a [ThreewayStack] (a detail's key is its [Item]). */
private object ListKey

/**
 * The app: the list (loads 20 items at start), the two reload actions (20 or 1,000 items, each a
 * new request), and the detail opened by tapping a row (from the data already loaded: navigation
 * only, no request).
 *
 * [stack] null is the app as shipped: only the shown screen is composed. The guest's `retain`
 * experiment passes a retained stack (keliver-nav's KeliverRetainedStack, RetainedStack.kt), which
 * keeps the list composed, hidden, under the detail.
 */
@Composable
fun ThreewayApp(api: ThreewayApi, marks: ThreewayMarks, stack: ThreewayStack? = null) {
  var page by remember { mutableStateOf<ItemsPage?>(null) }
  var request by remember { mutableStateOf(1) }
  var size by remember { mutableStateOf("small") }
  var selected by remember { mutableStateOf<Item?>(null) }
  var nav by remember { mutableStateOf(0) }
  val scope = rememberCoroutineScope()

  // The `split` experiment only: marks the start of the frame that handles a click (`frame`). The
  // frame callback is registered here, before the click's state writes reach the recomposer, so it
  // runs first in that frame, before the recomposition.
  fun frameMark(seq: Int) {
    if (marks.frameMarks) scope.launch(start = CoroutineStart.UNDISPATCHED) { withFrameNanos { marks.mark(seq, "frame") } }
  }

  LaunchedEffect(request) {
    val seq = request
    marks.mark(seq, "request")
    page = api.items(size, seq)
  }

  val load = { newSize: String ->
    marks.mark(request + 1, "click")
    frameMark(request + 1)
    size = newSize
    request += 1
  }

  if (stack != null) {
    // The `retain` experiment (NF1): the list stays composed under the detail, so back shows it
    // without composing it, and its "composed" mark for a back is made here instead of in ListScreen.
    val item = selected
    val open: (Item) -> Unit = remember {
      { opened ->
        marks.mark(NAV_BASE + nav + 1, "click")
        frameMark(NAV_BASE + nav + 1)
        nav += 1
        selected = opened
      }
    }
    val back: () -> Unit = remember {
      {
        marks.mark(NAV_BASE + nav + 1, "click")
        frameMark(NAV_BASE + nav + 1)
        nav += 1
        selected = null
      }
    }
    DisposableEffect(nav) {
      if (nav > 0 && item == null) marks.composed("list", NAV_BASE + nav)
      onDispose {}
    }
    stack.Show(if (item == null) listOf(ListKey) else listOf(ListKey, item), back) { key ->
      Column(Modifier.fillMaxSize().background(Color.White)) {
        if (key == ListKey) {
          ListScreen(page = page, nav = 0, marks = marks, onLoad = load, onOpen = open)
        } else if (marks.heavyDetail) {
          HeavyDetailScreen(item = key as Item, nav = nav, marks = marks, onBack = back)
        } else {
          DetailScreen(item = key as Item, nav = nav, marks = marks, onBack = back)
        }
      }
    }
    return
  }

  Column(Modifier.fillMaxSize().background(Color.White)) {
    val item = selected
    if (item == null) {
      ListScreen(
        page = page,
        nav = nav,
        marks = marks,
        onLoad = load,
        onOpen = {
          marks.mark(NAV_BASE + nav + 1, "click")
          frameMark(NAV_BASE + nav + 1)
          nav += 1
          selected = it
        },
      )
    } else if (marks.heavyDetail) {
      HeavyDetailScreen(
        item = item,
        nav = nav,
        marks = marks,
        onBack = {
          marks.mark(NAV_BASE + nav + 1, "click")
          frameMark(NAV_BASE + nav + 1)
          nav += 1
          selected = null
        },
      )
    } else {
      DetailScreen(
        item = item,
        nav = nav,
        marks = marks,
        onBack = {
          marks.mark(NAV_BASE + nav + 1, "click")
          frameMark(NAV_BASE + nav + 1)
          nav += 1
          selected = null
        },
      )
    }
  }
}

@Composable
private fun ListScreen(page: ItemsPage?, nav: Int, marks: ThreewayMarks, onLoad: (String) -> Unit, onOpen: (Item) -> Unit) {
  Header("Orders", onBack = null)
  Row(Modifier.fillMaxWidth().height(48.dp)) {
    Action("Load 20", Modifier.weight(1f)) { onLoad("small") }
    Action("Load 1,000", Modifier.weight(1f)) { onLoad("large") }
  }
  if (page == null) {
    Text("Loading…", Modifier.padding(16.dp), color = Muted, fontSize = 14.sp)
    return
  }
  // The list's content is in this frame: data (a new page) or navigation (back from a detail).
  DisposableEffect(page.seq) {
    marks.composed("data", page.seq)
    onDispose {}
  }
  DisposableEffect(nav) {
    if (nav > 0) marks.composed("list", NAV_BASE + nav)
    onDispose {}
  }
  Text(
    "${page.count} items · request ${page.seq}",
    Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp),
    color = Muted,
    fontSize = 13.sp,
  )
  LazyColumn(Modifier.fillMaxSize()) {
    items(page.items, key = { it.id }) { item ->
      ItemRow(item) { onOpen(item) }
    }
  }
}

@Composable
private fun ItemRow(item: Item, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(item.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(item.subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(item.amount, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
      StatusChip(item.status)
    }
  }
}

@Composable
private fun StatusChip(status: String) {
  val (bg, fg) = chipColors(status)
  Box(Modifier.padding(top = 4.dp).background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
    Text(status, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun DetailScreen(item: Item, nav: Int, marks: ThreewayMarks, onBack: () -> Unit) {
  DisposableEffect(item.id, nav) {
    marks.composed("detail", NAV_BASE + nav)
    onDispose {}
  }
  Header(item.title, onBack = onBack)
  Column(Modifier.fillMaxWidth().padding(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(item.amount, Modifier.weight(1f), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
      StatusChip(item.status)
    }
    Field("Customer", item.subtitle)
    Field("Ship to", item.address)
    Field("Reference", item.reference)
    Field("Note", item.note)
    Text("Order id ${item.id} · opened ${nav / 2 + 1}×", Modifier.padding(top = 16.dp), color = Muted, fontSize = 12.sp)
  }
}

/**
 * The heavy detail variant (the `heavy` experiment): a first visit that builds about 100 widgets at
 * once, text only. Header (4), the amount and status (4), a row of four chips (9), four sections of
 * a title and six label/value rows (4 × 19), a row of three buttons (7), the footer (1), in one
 * column (1): 102 widgets. index.html draws the same layout and the same strings.
 */
@Composable
private fun HeavyDetailScreen(item: Item, nav: Int, marks: ThreewayMarks, onBack: () -> Unit) {
  DisposableEffect(item.id, nav) {
    marks.composed("detail", NAV_BASE + nav)
    onDispose {}
  }
  Header(item.title, onBack = onBack)
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(item.amount, Modifier.weight(1f), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
      StatusChip(item.status)
    }
    Row(Modifier.height(28.dp)) {
      for (chip in HeavyChips) StatusChip(chip)
    }
    for ((section, fields) in heavySections(item)) {
      Text(section, Modifier.padding(top = 8.dp), color = Indigo, fontSize = 14.sp, fontWeight = FontWeight.Medium)
      for ((label, value) in fields) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(label, Modifier.width(112.dp), color = Muted, fontSize = 13.sp)
          Text(value, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp)) {
      for (label in listOf("Call", "Email", "Cancel")) Action(label, Modifier.weight(1f)) {}
    }
    Text("Order id ${item.id} · opened ${nav / 2 + 1}×", Modifier.padding(top = 8.dp), color = Muted, fontSize = 12.sp)
  }
}

private val Tiers = listOf("Gold", "Silver", "Bronze")
private val Methods = listOf("Standard", "Express", "Pickup")
private val Carriers = listOf("Northline", "Bluebird", "Courier 9")
private val Payments = listOf("Card", "UPI", "Wallet", "Bank")

/** The heavy detail's row of chips (fixed; styled as the statuses). */
private val HeavyChips = listOf("Paid", "Shipped", "Refunded", "Pending")

/** The heavy detail's four sections of six label/value rows, derived from the item (index.html: the same). */
private fun heavySections(item: Item): List<Pair<String, List<Pair<String, String>>>> {
  val id = item.id
  return listOf(
    "Order" to listOf(
      "Order id" to "#$id", "Title" to item.title, "Status" to item.status,
      "Amount" to item.amount, "Reference" to item.reference, "Placed" to "Day ${id % 28 + 1}",
    ),
    "Customer" to listOf(
      "Name" to item.subtitle, "Account" to "AC-${id * 7 % 100000}", "Tier" to Tiers[id % 3],
      "Since" to "20${10 + id % 15}", "Orders" to "${id % 40 + 1}", "Note" to item.note,
    ),
    "Shipping" to listOf(
      "Address" to item.address, "Method" to Methods[id % 3], "Carrier" to Carriers[id % 3],
      "Tracking" to "TRK${id * 31}", "Weight" to "${id % 9 + 1}.${id % 10} kg", "Packages" to "${id % 4 + 1}",
    ),
    "Payment" to listOf(
      "Method" to Payments[id % 4], "Subtotal" to item.amount, "Tax" to "${id % 50}.00",
      "Discount" to "0.00", "Total" to item.amount, "Invoice" to "INV-${id + 10000}",
    ),
  )
}

@Composable
private fun Field(label: String, value: String) {
  Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
    Text(label, color = Muted, fontSize = 12.sp)
    Text(value, color = Ink, fontSize = 15.sp)
  }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)?) {
  Row(
    Modifier.fillMaxWidth().height(56.dp).background(Indigo),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      Box(Modifier.width(72.dp).height(56.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Text("Back", color = Color.White, fontSize = 16.sp)
      }
    } else {
      Box(Modifier.width(16.dp).height(56.dp))
    }
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
  }
}

@Composable
private fun Action(label: String, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier.height(48.dp).padding(4.dp).background(ActionBg, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = Indigo, fontSize = 14.sp, fontWeight = FontWeight.Medium)
  }
}
