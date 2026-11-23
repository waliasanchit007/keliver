// The three-way benchmark's screens as a Flutter module (docs/THREE_WAY_BENCHMARK.md, "Flutter on
// the A12"): the same list, detail and heavy detail as ThreewayScreens.kt, the same fixture API, the
// same fixed geometry (header 56, actions 48, status line 24, rows 72), navigation as a state switch
// (no route, no transition), as in the other forms. Marks are epoch ms (DateTime.now(), the phone's
// wall clock, as System.currentTimeMillis() on the host) taken in Dart and sent to the host over the
// `threeway` method channel AFTER they are taken, so the channel's own latency is not in any mark.
//
// Started by the host (ThreewayFlutter.kt) with entrypoint args `x=<experiments>` and
// `api=<base url>`. "frameEnd" is the end of the UI thread's work for the frame that shows the
// content (a post-frame callback); `rasterEnd`, sent later, is when the raster thread finished that
// frame (FrameTiming).
import 'dart:async';
import 'dart:convert';
import 'dart:developer' show Timeline;
import 'dart:io';
import 'dart:ui' show FrameTiming, FramePhase;

import 'package:flutter/gestures.dart' show PointerDeviceKind, VelocityTracker;
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

const int navBase = 100000;
const channel = MethodChannel('threeway');

const indigo = Color(0xFF3F51B5);
const ink = Color(0xFF1C1B1F);
const muted = Color(0xFF6B6B73);
const actionBg = Color(0xFFE8EAF6);

int now() => DateTime.now().millisecondsSinceEpoch;

Set<String> experiments = {};
String apiBase = 'http://localhost:8491';

final Map<int, Map<String, int>> marks = {};
void mark(int seq, String name, [int? at]) => (marks[seq] ??= {})[name] = at ?? now();

void send(String method, Map<String, Object?> args) {
  channel.invokeMethod<void>(method, args);
}

void main(List<String> args) {
  final mainAt = now();
  WidgetsFlutterBinding.ensureInitialized();
  for (final a in args) {
    if (a.startsWith('x=')) experiments = a.substring(2).split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toSet();
    if (a.startsWith('api=')) apiBase = a.substring(4);
  }
  channel.setMethodCallHandler((call) async {
    if (call.method == 'startScroll') ScrollProbe.instance.start();
    return null;
  });
  FrameTimings.instance.start();
  send('mark', {'name': 'dartMain', 'at': mainAt});
  runApp(const ThreewayFlutterApp());
}

class Item {
  Item(this.id, this.title, this.subtitle, this.amount, this.status, this.address, this.note, this.reference);
  final int id;
  final String title, subtitle, amount, status, address, note, reference;

  static Item fromJson(Map<String, dynamic> j) => Item(
        j['id'] as int,
        j['title'] as String,
        j['subtitle'] as String,
        j['amount'] as String,
        j['status'] as String,
        (j['address'] ?? '') as String,
        (j['note'] ?? '') as String,
        (j['reference'] ?? '') as String,
      );
}

class ItemsPage {
  ItemsPage(this.seq, this.count, this.items);
  final int seq, count;
  final List<Item> items;
}

/// One client for the process (keep-alive), as the other forms' one OkHttp client.
final HttpClient http = HttpClient();

/// The list's request: dart:io's HttpClient, then jsonDecode and a hand mapping, on the UI isolate
/// (Flutter's documentation moves only large parses to an isolate; the timed requests are the small
/// list's, 4.5 KB).
Future<ItemsPage> fetchItems(String size, int seq) async {
  final req = await http.getUrl(Uri.parse('$apiBase/items?size=$size&seq=$seq'));
  final res = await req.close();
  final body = await res.transform(utf8.decoder).join();
  if (res.statusCode != 200) throw StateError('HTTP ${res.statusCode}');
  mark(seq, 'response');
  final j = jsonDecode(body) as Map<String, dynamic>;
  final page = ItemsPage(j['seq'] as int, j['count'] as int,
      (j['items'] as List).map((e) => Item.fromJson(e as Map<String, dynamic>)).toList(growable: false));
  mark(seq, 'parsed');
  return page;
}

/// Matches content reported at a post-frame callback to its FrameTiming (buildStart <= t <= buildFinish)
/// and sends the frame's raster end as a late mark.
class FrameTimings {
  static final instance = FrameTimings();
  final List<FrameTiming> all = [];
  final List<(int, String, int, int, int)> pending = []; // seq, screen, epoch ms, monotonic µs, the frame's vsync µs
  bool keepAll = false;

  void start() => SchedulerBinding.instance.addTimingsCallback(_onTimings);

  void _onTimings(List<FrameTiming> timings) {
    if (keepAll) all.addAll(timings);
    if (pending.isEmpty) return;
    // The frame the post-frame callback ran in: its vsync timestamp (the engine's clock, as
    // FrameTiming's) was recorded there.
    pending.removeWhere((p) {
      final (seq, screen, epoch, mono, vsyncUs) = p;
      FrameTiming? t;
      for (final c in timings) {
        if ((c.timestampInMicroseconds(FramePhase.vsyncStart) - vsyncUs).abs() < 2000) t = c;
      }
      if (t == null) return false;
      final tt = t;
      int at(FramePhase ph) => epoch + ((tt.timestampInMicroseconds(ph) - mono) / 1000).round();
      send('late', {
        'seq': seq,
        'screen': screen,
        'vsyncStart': at(FramePhase.vsyncStart),
        'buildStart': at(FramePhase.buildStart),
        'buildEnd': at(FramePhase.buildFinish),
        'rasterStart': at(FramePhase.rasterStart),
        'rasterEnd': at(FramePhase.rasterFinish),
        'frameNumber': tt.frameNumber,
      });
      return true;
    });
  }
}

final Set<String> reported = {};

/// The screen's content for [seq] is in this frame's tree (`composed`, as the Compose forms' apply
/// phase); its end (`frameEnd`) is the post-frame callback of the first frame with a non-empty view
/// (a pre-warmed engine builds before the activity attaches its view).
void reportContent(String screen, int seq) {
  if (!reported.add('$screen:$seq')) return;
  mark(seq, 'composed');
  void after(Duration _) {
    final view = WidgetsBinding.instance.platformDispatcher.implicitView;
    if (view == null || view.physicalSize.isEmpty) {
      SchedulerBinding.instance.addPostFrameCallback(after);
      return;
    }
    final at = now();
    final mono = Timeline.now;
    mark(seq, 'frameEnd', at);
    FrameTimings.instance.pending
        .add((seq, screen, at, mono, SchedulerBinding.instance.currentSystemFrameTimeStamp.inMicroseconds));
    send('composed', {'screen': screen, 'seq': seq, 'marks': jsonEncode({'$seq': marks.remove(seq)})});
  }

  SchedulerBinding.instance.addPostFrameCallback(after);
}

class ThreewayFlutterApp extends StatelessWidget {
  const ThreewayFlutterApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: indigo)),
        home: const Material(color: Colors.white, child: ThreewayAppScreen()),
      );
}

class ThreewayAppScreen extends StatefulWidget {
  const ThreewayAppScreen({super.key});

  @override
  State<ThreewayAppScreen> createState() => _ThreewayAppScreenState();
}

class _ThreewayAppScreenState extends State<ThreewayAppScreen> {
  ItemsPage? page;
  int request = 1;
  String size = 'small';
  Item? selected;
  int nav = 0;

  bool get heavy => experiments.contains('heavy');

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final seq = request;
    mark(seq, 'request');
    final p = await fetchItems(size, seq);
    if (!mounted) return;
    setState(() => page = p);
  }

  void _onLoad(String newSize) {
    mark(request + 1, 'click');
    setState(() {
      size = newSize;
      request += 1;
    });
    _load();
  }

  void _open(Item item) {
    mark(navBase + nav + 1, 'click');
    setState(() {
      nav += 1;
      selected = item;
    });
  }

  void _back() {
    mark(navBase + nav + 1, 'click');
    setState(() {
      nav += 1;
      selected = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final item = selected;
    if (item == null) {
      final p = page;
      if (p != null) reportContent('data', p.seq);
      if (nav > 0) reportContent('list', navBase + nav);
      return ListScreen(page: page, onLoad: _onLoad, onOpen: _open);
    }
    reportContent('detail', navBase + nav);
    return heavy
        ? HeavyDetailScreen(item: item, nav: nav, onBack: _back)
        : DetailScreen(item: item, nav: nav, onBack: _back);
  }
}

(Color, Color) chipColors(String status) => switch (status) {
      'Paid' => (const Color(0xFFDFF5E1), const Color(0xFF1B5E20)),
      'Pending' => (const Color(0xFFFFF3CD), const Color(0xFF7A5B00)),
      'Shipped' => (const Color(0xFFDCEBFF), const Color(0xFF0D47A1)),
      'Refunded' => (const Color(0xFFEDE7F6), const Color(0xFF4527A0)),
      _ => (const Color(0xFFFDE2E1), const Color(0xFFB71C1C)),
    };

TextStyle style(double size, Color color, [FontWeight weight = FontWeight.normal]) =>
    TextStyle(fontSize: size, color: color, fontWeight: weight);

class Header extends StatelessWidget {
  const Header({super.key, required this.title, this.onBack});
  final String title;
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) => Container(
        height: 56,
        color: indigo,
        child: Row(children: [
          if (onBack != null)
            SizedBox(
              width: 72,
              height: 56,
              child: InkWell(onTap: onBack, child: Center(child: Text('Back', style: style(16, Colors.white)))),
            )
          else
            const SizedBox(width: 16, height: 56),
          Flexible(
            child: Text(title, maxLines: 1, overflow: TextOverflow.clip, style: style(20, Colors.white, FontWeight.w500)),
          ),
        ]),
      );
}

class Action extends StatelessWidget {
  const Action({super.key, required this.label, required this.onTap});
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Expanded(
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: Material(
            color: actionBg,
            borderRadius: BorderRadius.circular(8),
            clipBehavior: Clip.antiAlias,
            child: InkWell(onTap: onTap, child: Center(child: Text(label, style: style(14, indigo, FontWeight.w500)))),
          ),
        ),
      );
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});
  final String status;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = chipColors(status);
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10)),
        child: Text(status, style: style(12, fg, FontWeight.w500)),
      ),
    );
  }
}

class ListScreen extends StatelessWidget {
  const ListScreen({super.key, required this.page, required this.onLoad, required this.onOpen});
  final ItemsPage? page;
  final void Function(String) onLoad;
  final void Function(Item) onOpen;

  @override
  Widget build(BuildContext context) {
    final p = page;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const Header(title: 'Orders'),
      SizedBox(
        height: 48,
        child: Row(children: [
          Action(label: 'Load 20', onTap: () => onLoad('small')),
          Action(label: 'Load 1,000', onTap: () => onLoad('large')),
        ]),
      ),
      if (p == null)
        Padding(padding: const EdgeInsets.all(16), child: Text('Loading…', style: style(14, muted)))
      else ...[
        Container(
          height: 24,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          alignment: Alignment.centerLeft,
          child: Text('${p.count} items · request ${p.seq}', style: style(13, muted)),
        ),
        Expanded(
          child: ScrollProbe.wrap(
            ListView.builder(
              padding: EdgeInsets.zero,
              itemCount: p.items.length,
              itemBuilder: (context, i) {
                final item = p.items[i];
                return ItemRow(key: ValueKey(item.id), item: item, onTap: () => onOpen(item));
              },
            ),
          ),
        ),
      ],
    ]);
  }
}

class ItemRow extends StatelessWidget {
  const ItemRow({super.key, required this.item, required this.onTap});
  final Item item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: 72,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(children: [
              Expanded(
                child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: style(16, ink, FontWeight.w500)),
                  Text(item.subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: style(13, muted)),
                ]),
              ),
              Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                Text(item.amount, style: style(15, ink, FontWeight.w500)),
                StatusChip(item.status),
              ]),
            ]),
          ),
        ),
      );
}

class Field extends StatelessWidget {
  const Field(this.label, this.value, {super.key});
  final String label, value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: style(12, muted)),
          Text(value, style: style(15, ink)),
        ]),
      );
}

class DetailScreen extends StatelessWidget {
  const DetailScreen({super.key, required this.item, required this.nav, required this.onBack});
  final Item item;
  final int nav;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Header(title: item.title, onBack: onBack),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(item.amount, style: style(28, ink, FontWeight.bold))),
              StatusChip(item.status),
            ]),
            Field('Customer', item.subtitle),
            Field('Ship to', item.address),
            Field('Reference', item.reference),
            Field('Note', item.note),
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Text('Order id ${item.id} · opened ${nav ~/ 2 + 1}×', style: style(12, muted)),
            ),
          ]),
        ),
      ]);
}

const tiers = ['Gold', 'Silver', 'Bronze'];
const methods = ['Standard', 'Express', 'Pickup'];
const carriers = ['Northline', 'Bluebird', 'Courier 9'];
const payments = ['Card', 'UPI', 'Wallet', 'Bank'];
const heavyChips = ['Paid', 'Shipped', 'Refunded', 'Pending'];

List<(String, List<(String, String)>)> heavySections(Item item) {
  final id = item.id;
  return [
    ('Order', [
      ('Order id', '#$id'), ('Title', item.title), ('Status', item.status),
      ('Amount', item.amount), ('Reference', item.reference), ('Placed', 'Day ${id % 28 + 1}'),
    ]),
    ('Customer', [
      ('Name', item.subtitle), ('Account', 'AC-${id * 7 % 100000}'), ('Tier', tiers[id % 3]),
      ('Since', '20${10 + id % 15}'), ('Orders', '${id % 40 + 1}'), ('Note', item.note),
    ]),
    ('Shipping', [
      ('Address', item.address), ('Method', methods[id % 3]), ('Carrier', carriers[id % 3]),
      ('Tracking', 'TRK${id * 31}'), ('Weight', '${id % 9 + 1}.${id % 10} kg'), ('Packages', '${id % 4 + 1}'),
    ]),
    ('Payment', [
      ('Method', payments[id % 4]), ('Subtotal', item.amount), ('Tax', '${id % 50}.00'),
      ('Discount', '0.00'), ('Total', item.amount), ('Invoice', 'INV-${id + 10000}'),
    ]),
  ];
}

class HeavyDetailScreen extends StatelessWidget {
  const HeavyDetailScreen({super.key, required this.item, required this.nav, required this.onBack});
  final Item item;
  final int nav;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Header(title: item.title, onBack: onBack),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(
              height: 40,
              child: Row(children: [
                Expanded(child: Text(item.amount, style: style(28, ink, FontWeight.bold))),
                StatusChip(item.status),
              ]),
            ),
            SizedBox(height: 28, child: Row(children: [for (final c in heavyChips) StatusChip(c)])),
            for (final (section, fields) in heavySections(item)) ...[
              Padding(padding: const EdgeInsets.only(top: 8), child: Text(section, style: style(14, indigo, FontWeight.w500))),
              for (final (label, value) in fields)
                SizedBox(
                  height: 20,
                  child: Row(children: [
                    SizedBox(width: 112, child: Text(label, style: style(13, muted))),
                    Flexible(child: Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: style(13, ink))),
                  ]),
                ),
            ],
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: SizedBox(
                height: 40,
                child: Row(children: [for (final l in ['Call', 'Email', 'Cancel']) Action(label: l, onTap: () {})]),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text('Order id ${item.id} · opened ${nav ~/ 2 + 1}×', style: style(12, muted)),
            ),
          ]),
        ),
      ]);
}

/// The scrolling series (`bench=scroll`, after the host's startScroll): per fling, from the DOWN to
/// the list's last move, the frames from FrameTiming (vsync start → raster end, the UI thread's
/// build and the raster thread's time) and the missed vsyncs between consecutive moving frames (as
/// the host's ScrollTracker counts them from FrameMetrics' intended vsync). Logged through the host
/// as one line per fling, in the host's fling format, so its summary runs unchanged.
class ScrollProbe {
  static final instance = ScrollProbe();
  bool on = false;
  int index = 0;
  _Fling? fling;

  static Widget wrap(Widget list) => Listener(
        onPointerDown: (e) => instance._down(e),
        onPointerMove: (e) => instance.fling?.velocity.addPosition(e.timeStamp, e.position),
        onPointerUp: (e) => instance._up(e),
        child: NotificationListener<ScrollNotification>(
          onNotification: (n) {
            final f = instance.fling;
            if (f != null) {
              if (n is ScrollUpdateNotification && (n.scrollDelta ?? 0) != 0) {
                f.travelPx += (n.scrollDelta!).abs();
                f.lastMoveUs = Timeline.now;
              }
              if (n is OverscrollNotification) f.edge = true;
              f.atEnd = n.metrics.extentBefore == 0 || n.metrics.extentAfter == 0;
            }
            return false;
          },
          child: list,
        ),
      );

  void start() {
    on = true;
    FrameTimings.instance.keepAll = true;
    FrameTimings.instance.all.clear();
  }

  void _down(PointerDownEvent e) {
    if (!on) return;
    final previous = fling;
    if (previous != null) _finish(previous, 'touch');
    fling = _Fling(Timeline.now)..velocity.addPosition(e.timeStamp, e.position);
  }

  void _up(PointerUpEvent e) {
    final f = fling;
    if (f == null) return;
    f.upUs = Timeline.now;
    f.velocity.addPosition(e.timeStamp, e.position);
    f.upVelocityDpS = f.velocity.getVelocity().pixelsPerSecond.dy;
    _check(f);
  }

  void _check(_Fling f) {
    Timer(const Duration(milliseconds: 100), () {
      if (fling != f) return;
      final last = f.lastMoveUs > f.upUs ? f.lastMoveUs : f.upUs;
      if (Timeline.now - last < 500000) return _check(f);
      // Settled; FrameTiming arrives in batches (up to about a second in release): wait for it.
      fling = null;
      Timer(const Duration(milliseconds: 1500), () => _finish(f, 'settled', alreadyClosed: true));
    });
  }

  void _finish(_Fling f, String closedBy, {bool alreadyClosed = false}) {
    if (!alreadyClosed) fling = null;
    index++;
    final view = WidgetsBinding.instance.platformDispatcher.implicitView!;
    final dpr = view.devicePixelRatio;
    final hz = view.display.refreshRate;
    final periodUs = 1e6 / hz;
    final stopUs = f.lastMoveUs != 0 ? f.lastMoveUs : f.upUs;
    final timings = FrameTimings.instance.all
        .where((t) => t.timestampInMicroseconds(FramePhase.vsyncStart) >= f.downUs - 8000)
        .toList()
      ..sort((a, b) => a.timestampInMicroseconds(FramePhase.vsyncStart).compareTo(b.timestampInMicroseconds(FramePhase.vsyncStart)));
    final moving = timings.where((t) => t.timestampInMicroseconds(FramePhase.vsyncStart) <= stopUs).toList();
    final afterStop = timings.where((t) => t.timestampInMicroseconds(FramePhase.vsyncStart) > stopUs).toList();
    var missed = 0;
    for (var k = 1; k < moving.length; k++) {
      final gap = moving[k].timestampInMicroseconds(FramePhase.vsyncStart) - moving[k - 1].timestampInMicroseconds(FramePhase.vsyncStart);
      final m = (gap / periodUs).round() - 1;
      if (m > 0) missed += m;
    }
    double ms(Duration d) => (d.inMicroseconds / 10).round() / 100;
    final line = {
      'fling': index,
      'closedBy': closedBy,
      'edge': f.edge || f.atEnd,
      'upVelocityPxS': (f.upVelocityDpS * dpr).round(),
      'upVelocityDpS': f.upVelocityDpS.round(),
      'travelPx': (f.travelPx * dpr).round(),
      'stopAfterUpMs': f.upUs == 0 ? 0 : ((stopUs - f.upUs) / 1000).round(),
      'framesCount': timings.length,
      'movingFrames': [for (final t in moving) ms(t.totalSpan)],
      'afterStopFrames': [for (final t in afterStop) ms(t.totalSpan)],
      'movingBuildMs': [for (final t in moving) ms(t.buildDuration)],
      'movingRasterMs': [for (final t in moving) ms(t.rasterDuration)],
      'missedVsyncs': missed,
      'vsyncPeriodMs': (periodUs / 10).round() / 100,
    };
    FrameTimings.instance.all.removeWhere((t) => t.timestampInMicroseconds(FramePhase.vsyncStart) <= stopUs);
    send('fling', {'line': jsonEncode(line)});
  }
}

class _Fling {
  _Fling(this.downUs);
  final int downUs;
  int upUs = 0;
  int lastMoveUs = 0;
  double travelPx = 0;
  double upVelocityDpS = 0;
  bool edge = false;
  bool atEnd = false;
  final VelocityTracker velocity = VelocityTracker.withKind(PointerDeviceKind.touch);
}
