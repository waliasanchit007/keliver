#!/usr/bin/env python3
# Research spike (stone U1): second pass over analyze.py's samples.json.
#
#   python3 -I classify.py <report dir>
#
# Each sample that compiles natively but not as guest code is put in one class by its ROOT
# causes: the names it imports from Compose that the guest API lacks (an unresolved name with no
# import is a member of one of them, or a cascade, and is attributed only when known below).
# Classes, worst first:
#   NEVER   the guest cannot do it by design (guest code run per measure, pointer event or node,
#           platform locals): MS4-07's curated error stubs
#   NOPR    generatable in principle, but no MS4/#312 PR names it
#   EXP     Compose API behind a non-Material3 experimental opt-in, new in 1.12 (scope undecided)
#   MS4     every root cause is in a named MS4 or #312 PR (listed)
# The PR assignment is this spike's reading of docs/MS4_PLAN.md §2.1 and #312 §4; see the
# findings file for the judgement calls.
import collections
import json
import os
import sys

report = sys.argv[1]
samples = json.load(open(os.path.join(report, 'samples.json')))

M3 = {
    'MS4-16': '''Text Button ElevatedButton FilledTonalButton OutlinedButton TextButton IconButton
      FilledIconButton FilledTonalIconButton OutlinedIconButton IconToggleButton FilledIconToggleButton
      FilledTonalIconToggleButton OutlinedIconToggleButton Surface Card ElevatedCard OutlinedCard
      AssistChip ElevatedAssistChip FilterChip ElevatedFilterChip InputChip SuggestionChip
      ElevatedSuggestionChip ListItem ListItemDefaults HorizontalDivider VerticalDivider Divider Badge BadgedBox
      LinearProgressIndicator CircularProgressIndicator ProgressIndicatorDefaults Icon ButtonDefaults
      CardDefaults AssistChipDefaults FilterChipDefaults InputChipDefaults SuggestionChipDefaults
      IconButtonDefaults ChipDefaults ProvideTextStyle LocalTextStyle LocalContentColor
      LocalMinimumInteractiveComponentSize minimumInteractiveComponentSize ContentAlpha
      ButtonColors CardColors'''.split(),
    'MS4-17': '''TextField OutlinedTextField TextFieldDefaults OutlinedTextFieldDefaults Checkbox
      TriStateCheckbox CheckboxDefaults Switch SwitchDefaults RadioButton RadioButtonDefaults Slider
      RangeSlider SliderDefaults SliderState RangeSliderState rememberSliderState rememberRangeSliderState
      SegmentedButton SingleChoiceSegmentedButtonRow MultiChoiceSegmentedButtonRow
      SegmentedButtonDefaults SecureTextField OutlinedSecureTextField'''.split(),
    'MS4-18': '''Scaffold ScaffoldDefaults TopAppBar CenterAlignedTopAppBar MediumTopAppBar LargeTopAppBar
      BottomAppBar BottomAppBarDefaults TopAppBarDefaults NavigationBar NavigationBarItem
      NavigationBarItemDefaults NavigationRail NavigationRailItem NavigationRailItemDefaults
      ModalNavigationDrawer DismissibleNavigationDrawer PermanentNavigationDrawer ModalDrawerSheet
      DismissibleDrawerSheet PermanentDrawerSheet NavigationDrawerItem NavigationDrawerItemDefaults Tab
      TabRow PrimaryTabRow SecondaryTabRow ScrollableTabRow PrimaryScrollableTabRow
      SecondaryScrollableTabRow LeadingIconTab TabRowDefaults TabPosition ModalBottomSheet
      BottomSheetScaffold BottomSheetDefaults AlertDialog BasicAlertDialog AlertDialogDefaults Snackbar
      SnackbarHost SnackbarDuration SnackbarResult DropdownMenu DropdownMenuItem ExposedDropdownMenuBox
      ExposedDropdownMenuDefaults MenuAnchorType ExposedDropdownMenuAnchorType DatePicker
      DatePickerDialog DateRangePicker DatePickerDefaults DisplayMode SelectableDates TimePicker TimeInput
      TimePickerDialog TimePickerDefaults TimePickerLayoutType PlainTooltip RichTooltip TooltipBox
      TooltipDefaults TooltipAnchorPosition SwipeToDismissBox SwipeToDismissBoxValue SearchBar
      DockedSearchBar SearchBarDefaults ExpandedFullScreenSearchBar ExpandedDockedSearchBar TopSearchBar
      FloatingActionButton ExtendedFloatingActionButton SmallFloatingActionButton
      LargeFloatingActionButton FloatingActionButtonDefaults FabPosition PullToRefreshBox
      PullToRefreshDefaults VerticalDragHandle'''.split(),
    'MS4-39': '''rememberModalBottomSheetState SheetState SheetValue rememberDrawerState DrawerState
      DrawerValue SnackbarHostState rememberDatePickerState rememberDateRangePickerState
      DatePickerState rememberTimePickerState TimePickerState rememberTopAppBarState
      TopAppBarScrollBehavior rememberTooltipState TooltipState rememberSwipeToDismissBoxState
      rememberBottomSheetScaffoldState rememberStandardBottomSheetState rememberSearchBarState
      SearchBarState SearchBarValue rememberPullToRefreshState'''.split(),
    'MS4-14': '''MaterialTheme ColorScheme lightColorScheme darkColorScheme Typography Shapes
      contentColorFor surfaceColorAtElevation'''.split(),
}
M3_NOPR = '''HorizontalMultiBrowseCarousel HorizontalUncontainedCarousel HorizontalCenteredHeroCarousel
  rememberCarouselState CarouselDefaults WideNavigationRail WideNavigationRailItem
  ModalWideNavigationRail rememberWideNavigationRailState WideNavigationRailValue
  ShortNavigationBar ShortNavigationBarItem LoadingIndicator ContainedLoadingIndicator'''.split()
m3_pr = {n: pr for pr, names in M3.items() for n in names}

# foundation / ui names -> (class, PR or reason)
NAMES = {}
def put(cls, pr, names):
    for n in names.split():
        NAMES[n] = (cls, pr)

put('MS4', 'MS4-12', '''wrapContentSize wrapContentHeight wrapContentWidth requiredHeight requiredWidth
  requiredSize requiredSizeIn aspectRatio offset absoluteOffset absolutePadding FlowColumn FlowRow
  IntrinsicSize width height heightIn widthIn sizeIn defaultMinSize weight paddingFrom
  paddingFromBaseline alignByBaseline matchParentSize BasicText SelectionContainer DisableSelection
  toggleable triStateToggleable selectable selectableGroup focusable hoverable combinedClickable
  basicMarquee verticalScroll horizontalScroll rememberScrollState clipToBounds clip alpha rotate scale
  shadow blur zIndex progressSemantics Image ContextualFlowRow ContextualFlowColumn
  FlowRowOverflow FlowColumnOverflow''')
put('MS4', 'MS4-09', 'BoxWithConstraints Constraints')
put('MS4', 'MS4-15', '''rememberTextFieldState TextFieldState BasicTextField TextFieldLineLimits
  MutableInteractionSource collectIsPressedAsState collectIsFocusedAsState collectIsHoveredAsState
  collectIsDraggedAsState PressInteraction DragInteraction FocusInteraction HoverInteraction Interaction
  ScrollState scrollBy animateScrollBy animateScrollTo rememberLazyListState LazyListState
  rememberLazyGridState LazyGridState rememberLazyStaggeredGridState LazyStaggeredGridState
  rememberPagerState PagerState TextFieldValue delete insert append replace setTextAndPlaceCursorAtEnd
  clearText''')
put('MS4', 'MS4-38', '''HorizontalPager VerticalPager LazyVerticalGrid LazyHorizontalGrid GridCells
  GridItemSpan LazyVerticalStaggeredGrid LazyHorizontalStaggeredGrid StaggeredGridCells
  StaggeredGridItemSpan PagerDefaults PageSize''')
put('MS4', 'MS4-20', '''AnimatedContent Crossfade updateTransition rememberInfiniteTransition keyframes
  repeatable infiniteRepeatable RepeatMode tween spring snap Transition MutableTransitionState
  rememberTransition animateFloat animateDp animateColor animateValue animateOffset
  animateFloatAsState animateDpAsState animateColorAsState animateIntAsState animateOffsetAsState
  animateSizeAsState animateIntOffsetAsState togetherWith ContentTransform SizeTransform fadeIn fadeOut
  slideIn slideOut slideInVertically slideOutVertically slideInHorizontally slideOutHorizontally
  expandIn shrinkOut expandVertically shrinkVertically expandHorizontally shrinkHorizontally scaleIn
  scaleOut EnterTransition ExitTransition animateEnterExit using KeyframesSpec FastOutSlowInEasing
  LinearEasing FastOutLinearInEasing LinearOutSlowInEasing CubicBezierEasing Easing AnimationSpec
  Spring tween InfiniteTransition animateContentSize AnimatedVisibility animateItem
  createChildTransition AnimatedVisibilityScope AnimatedContentTransitionScope
  rememberSplineBasedDecay exponentialDecay VectorConverter TwoWayConverter''')
put('MS4', 'MS4-21', 'Animatable animateTo animateDecay snapTo animate AnimationVector1D')
put('MS4', 'G1', '''RoundedCornerShape CircleShape CutCornerShape AbsoluteRoundedCornerShape
  GenericShape Brush SolidColor TileMode linearGradient horizontalGradient verticalGradient
  radialGradient sweepGradient TextStyle SpanStyle ParagraphStyle FontWeight FontStyle FontFamily
  TextAlign TextDecoration TextOverflow AnnotatedString buildAnnotatedString withStyle TextRange
  KeyboardOptions KeyboardType ImeAction KeyboardActions PasswordVisualTransformation
  VisualTransformation Orientation ContentScale ColorFilter BlendMode StrokeCap StrokeJoin PathEffect
  Stroke Fill BorderStroke Shadow LineHeightStyle BaselineShift TextIndent Hyphens LineBreak
  TextAutoSize CornerSize TransformOrigin Role''')
put('MS4', 'MS4-13', 'Offset Size Rect DpSize DpOffset IntOffset IntSize center Dp TextUnit Painter painterResource')
put('MS4', 'G3', '''graphicsLayer Canvas drawBehind drawWithContent drawWithCache Path DrawScope
  ContentDrawScope drawRect drawCircle drawLine drawRoundRect drawOval drawArc drawPath drawContent
  toPx roundToPx toDp toSp translate inset clipRect clipPath withTransform drawPoints
  CacheDrawScope onDrawBehind onDrawWithContent GraphicsLayerScope''')
put('MS4', 'MS4-18', '''nestedScroll windowInsetsPadding safeDrawingPadding systemBarsPadding
  statusBarsPadding navigationBarsPadding imePadding WindowInsets consumeWindowInsets
  windowInsetsTopHeight windowInsetsBottomHeight Dialog Popup DialogProperties PopupProperties
  displayCutoutPadding safeContentPadding asPaddingValues''')
put('MS4', 'MS4-19', '')
put('MS4', 'MS4-07', 'Preview')

put('NEVER', 'custom layout (measure in guest)', '''Layout layout measure place Measurable MeasureResult
  MeasureScope Placeable SubcomposeLayout MultiMeasureLayout LayoutModifier LookaheadScope
  IntrinsicMeasurable IntrinsicMeasureScope AlignmentLine FirstBaseline LastBaseline
  HorizontalAlignmentLine VerticalAlignmentLine MeasurePolicy layoutId LayoutIdParentData
  approachLayout ApproachLayoutModifierNode placeRelative LazyLayout LazyLayoutItemProvider
  HorizontalRuler VerticalRuler WindowInsetsRulers Ruler''')
put('NEVER', 'guest pointer code (pointerInput)', '''pointerInput awaitPointerEventScope
  awaitPointerEvent awaitFirstDown awaitEachGesture detectTapGestures detectDragGestures
  detectTransformGestures detectHorizontalDragGestures detectVerticalDragGestures PointerEvent
  PointerEventPass PointerInputChange PointerInputScope AwaitPointerEventScope consume
  positionChange changedToUp changedToDown drag awaitTouchSlopOrCancellation
  verticalDrag horizontalDrag awaitDragOrCancellation PointerType PointerIcon pointerHoverIcon
  pointerInteropFilter onKeyEvent onPreviewKeyEvent Key KeyEvent type key''')
put('NEVER', 'Modifier.Node API (guest node code)', '''Node ModifierNodeElement DelegatingNode
  DrawModifierNode LayoutModifierNode PointerInputModifierNode SemanticsModifierNode
  GlobalPositionAwareModifierNode CompositionLocalConsumerModifierNode TraversableNode
  traverseAncestors traverseChildren traverseDescendants ObserverModifierNode invalidateDraw
  invalidateMeasurement invalidateSemantics delegate currentValueOf requireDensity
  InspectorInfo debugInspectorInfo inspectable modifierLocalOf modifierLocalProvider
  modifierLocalConsumer ModifierLocalModifierNode composed DrawModifier InspectorValueInfo
  NodeCoordinator''')
put('NEVER', 'host locals / layout coordinates read synchronously', '''LocalDensity LocalLayoutDirection
  LocalConfiguration LocalView LocalContext LocalUriHandler LocalClipboardManager LocalClipboard
  LocalHapticFeedback LocalSoftwareKeyboardController LocalFocusManager LocalViewConfiguration
  LayoutCoordinates boundsInRoot positionInRoot positionInWindow boundsInWindow LocalWindowInfo
  rememberTextMeasurer TextMeasurer drawText''')
put('NOPR', 'size/position callbacks (onSizeChanged, onGloballyPositioned)', '''onSizeChanged
  onGloballyPositioned onPlaced onVisibilityChanged onFirstVisible onLayoutRectChanged''')
put('NOPR', 'focus', '''FocusRequester focusRequester requestFocus onFocusChanged onFocusEvent
  focusProperties focusTarget focusGroup FocusDirection FocusState freeFocus captureFocus''')
put('NOPR', 'semantics', '''semantics clearAndSetSemantics SemanticsPropertyReceiver contentDescription
  stateDescription onClick heading role contentType ContentType testTag traversalIndex
  isTraversalGroup customActions CustomAccessibilityAction LiveRegionMode liveRegion''')
put('NOPR', 'shared elements (C7: in no PR)', '''SharedTransitionLayout SharedTransitionScope
  sharedElement sharedBounds rememberSharedContentState skipToLookaheadSize animateBounds
  BoundsTransform renderInSharedTransitionScopeOverlay''')
put('NOPR', 'gesture state with guest callbacks (draggable/scrollable/anchored)', '''draggable
  draggable2D rememberDraggableState rememberDraggable2DState scrollable rememberScrollableState
  ScrollableState transformable rememberTransformableState anchoredDraggable AnchoredDraggableState
  DraggableAnchors overscroll rememberOverscrollEffect OverscrollEffect NestedScrollConnection
  NestedScrollSource NestedScrollDispatcher flingBehavior FlingBehavior SnapLayoutInfoProvider
  rememberSnapFlingBehavior snapFlingBehavior scrollableArea MutatorMutex MutatePriority
  BringIntoViewRequester bringIntoViewRequester DragAndDropTarget dragAndDropSource
  dragAndDropTarget magnifier''')
put('NEVER', 'per-keystroke guest code (#312: outside the mechanism)', '''InputTransformation
  OutputTransformation then byValue maxLength allCaps KeyboardActionHandler''')
put('EXP', 'FlexBox (1.12, ExperimentalFlexBoxApi)', '''FlexBox FlexBoxConfig FlexConfig FlexDirection
  FlexWrap FlexAlignSelf FlexBasis FlexAlignItems FlexAlignContent FlexJustifyContent''')
put('EXP', 'Grid (1.12, ExperimentalGridApi)', 'Grid gridItem fr GridTrackSize GridConfig')
put('EXP', 'Style (1.12, ExperimentalFoundationStyleApi)', '''Style styleable pressed hovered focused
  StyleScope MutableStyleState rememberUpdatedStyleState''')
put('EXP', 'media queries (ExperimentalMediaQueryApi)', 'mediaQuery windowWidth windowHeight')
put('EXP', 'deferred transitions (ExperimentalDeferredTransitionApi)', '''DeferredTargetAnimation
  DeferredTransitionState updateTarget defer''')
put('EXP', 'keyframesWithSpline / arc (ExperimentalAnimationSpecApi)', 'keyframesWithSpline ArcMode ArcAnimationSpec')
# The names the first pass left unmapped (2026-11-25), each placed by its declaration's kind.
put('MS4', 'MS4-18', 'PullToRefreshBox pullToRefresh PullToRefreshDefaults')
put('MS4', 'MS4-39', 'rememberPullToRefreshState PullToRefreshState')
put('MS4', 'MS4-13', 'paint IntRect')
put('MS4', 'G1', '''ToggleableState BlurredEdgeTreatment LayoutDirection MarqueeSpacing TextObfuscationMode
  ExpandPolicy''')
put('MS4', 'MS4-12', 'dropShadow innerShadow')
put('MS4', 'MS4-14', 'isSystemInDarkTheme')
put('MS4', 'trivial (pure-Kotlin ui-util: keep the real library, do not rename)', 'fastForEachIndexed fastForEach fastMap')
put('NOPR', 'text selection state (1.12)', 'rememberSelectionState')
put('NOPR', 'AnnotatedString inline content (C7: in no PR)', 'InlineTextContent appendInlineContent')
put('NOPR', 'custom Indication (guest implementation)', 'LocalIndication indication')
put('NOPR', 'gesture state with guest callbacks (draggable/scrollable/anchored)', '''LazyLayoutScrollScope
  rememberScrollable2DState scrollable2D''')
put('NEVER', 'custom layout (measure in guest)', '''WindowInsetsRulers TextAutoSizeLayoutScope
  PopupPositionProvider''')
put('NEVER', 'host locals / layout coordinates read synchronously', 'Density InterceptPlatformTextInput')
put('NEVER', 'per-keystroke guest code (#312: outside the mechanism)', 'TextFieldBuffer')

# Module-level fallbacks for an imported name no table above lists.
def by_package(pkg, name):
    if pkg.startswith('androidx.compose.material.icons'):
        return ('MS4', 'MS4-19')
    if pkg in ('androidx.compose.material3', 'androidx.compose.material'):
        if name in m3_pr:
            return ('MS4', m3_pr[name])
        if name in M3_NOPR:
            return ('NOPR', 'M3 1.4 additions not in MS4-16..18 (carousel, wide rail, loading indicator)')
        return ('MS4', 'MS4-16..18 (unlisted M3 name)')
    if name in NAMES:
        return NAMES[name]
    for seg in pkg.split('.'):  # a member import such as WindowInsetsRulers.Companion.StatusBars
        if seg[:1].isupper() and seg in NAMES:
            return NAMES[seg]
    if pkg.startswith('androidx.compose.ui.node'):
        return NAMES['Node']
    if pkg.startswith('androidx.compose.ui.input.pointer'):
        return NAMES['pointerInput']
    if pkg.startswith('androidx.compose.foundation.style'):
        return NAMES['Style']
    if pkg.startswith('androidx.compose.ui.semantics'):
        return NAMES['semantics']
    if pkg.startswith('androidx.compose.ui.focus'):
        return NAMES['FocusRequester']
    if pkg.startswith('androidx.compose.animation'):
        return ('MS4', 'MS4-20')
    if pkg.startswith('androidx.compose.ui.text') or pkg.startswith('androidx.compose.foundation.shape'):
        return ('MS4', 'G1')
    if pkg.startswith('androidx.compose.ui.graphics'):
        return ('MS4', 'G1/G3')
    if pkg.startswith('androidx.compose.foundation.lazy'):
        return ('MS4', 'MS4-38')
    if pkg.startswith('androidx.compose.foundation.layout'):
        return ('MS4', 'MS4-12')
    return ('UNMAPPED', pkg + '.' + name)

RANK = {'NEVER': 0, 'NOPR': 1, 'EXP': 2, 'UNMAPPED': 3, 'MS4': 4}


def stub_bucket(what):
    """The prototype's ERROR-level stubs, by the reason their message gives."""
    msg = what.split(' | ', 1)[1]
    if 'raw pointer handlers' in msg:
        return NAMES['pointerInput']
    if 'custom measuring' in msg:
        return NAMES['Layout']
    if "Scaffold's content takes the PaddingValues" in msg:
        return ('MS4', 'MS4-09')
    if 'interaction sources are host state' in msg:
        return ('MS4', 'MS4-15')
    if 'brushes are Gate B' in msg:
        return ('MS4', 'G1')
    if 'nullable modifier value' in msg:
        return ('MS4', 'MS4-08')
    if 'fling' in msg or 'overscroll' in msg:
        return NAMES['draggable']
    return ('MS4', 'MS4 (prototype stub: ' + msg[8:60] + ')')


def pkg_of(s, u):
    pkg = s['imports'][u].rsplit('.', 1)[0]
    return 'androidx.compose.material3' if pkg == 'androidx.compose.material' else pkg  # the swap
CASCADE = set('''it x y width height name value direction maxWidth maxHeight durationMillis div at
  targetState properties column row unaryMinus isTransitioningTo isIdle id text length left
  minHeight minWidth constraints shownItemCount colors scroll times coroutineScope isFocused
  hasBoundedWidth hasBoundedHeight align alignItems alignSelf gap flex grow wrap shrink basis
  background asCharSequence layoutInfo calculateDistanceTo snapToItem format translationX
  translationY rotationZ scaleX scaleY transformOrigin item items itemsIndexed'''.split())

classes = collections.Counter()
per_module = collections.defaultdict(collections.Counter)
pr_blockers = collections.Counter()      # MS4-class samples: PR -> samples needing it
pr_sole = collections.Counter()          # MS4-class samples needing exactly one PR
reason_count = collections.Counter()
rows = []
for s in samples:
    if not s['guest']:
        cls, why = 'COMPILES', []
    elif s['native']:
        cls, why = 'ANDROID', []
    else:
        causes = {u: by_package(pkg_of(s, u), u) for u in s['missing']}
        for kind, what in s['guest']:
            if kind == 'stub':
                causes[what.split(' | ')[0]] = stub_bucket(what)
            elif kind == 'unresolved' and what not in s['imports'] and what in NAMES and what not in CASCADE:
                causes[what] = NAMES[what]  # a known member (DrawScope.drawRect, MeasureScope.layout)
        if not causes:
            # Only parameters, overloads or members of names the guest has: the generated API is
            # incomplete for a family MS4 completes.
            causes = {'(overload/parameter)': ('MS4', 'MS4-16..18/12 (overloads, parameters)')}
        worst = min(RANK[c] for c, _ in causes.values())
        cls = [k for k, v in RANK.items() if v == worst][0]
        why = sorted({(c, r) for c, r in causes.values() if RANK[c] == worst})
        if cls == 'MS4':
            prs = sorted({r for _, r in why})
            for p in prs:
                pr_blockers[p] += 1
            if len(prs) == 1:
                pr_sole[prs[0]] += 1
        for c, r in why:
            reason_count[(cls, r)] += 1
    if s['m2'] and cls == 'COMPILES':
        cls_note = 'COMPILES (after M2->M3 swap)'
    classes[cls] += 1
    per_module[s['module']][cls] += 1
    rows.append((s['module'], s['sample'], cls, '; '.join(r for _, r in why)))

order = ['COMPILES', 'MS4', 'EXP', 'NOPR', 'NEVER', 'UNMAPPED', 'ANDROID']
with open(os.path.join(report, 'classes.md'), 'w') as out:
    out.write('| module | samples | ' + ' | '.join(order) + ' |\n|---|---|' + '---|' * len(order) + '\n')
    for m in ['material3', 'foundation-layout', 'foundation', 'animation', 'animation-core', 'ui']:
        c = per_module[m]
        out.write(f"| {m} | {sum(c.values())} | " + ' | '.join(str(c[k]) for k in order) + ' |\n')
    out.write(f"| **all** | {sum(classes.values())} | " + ' | '.join(str(classes[k]) for k in order) + ' |\n\n')
    out.write('MS4-class samples by PR they need (a sample may need several):\n\n')
    for p, n in pr_blockers.most_common():
        out.write(f'- {p}: {n} (only this PR: {pr_sole[p]})\n')
    out.write('\nReasons for the worse classes:\n\n')
    for (c, r), n in sorted(reason_count.items(), key=lambda kv: (-RANK.get(kv[0][0], 9), -kv[1])):
        if c != 'MS4':
            out.write(f'- {c}: {r}: {n}\n')
with open(os.path.join(report, 'classes.tsv'), 'w') as out:
    out.write('module\tsample\tclass\twhy\n')
    for r in rows:
        out.write('\t'.join(r) + '\n')
m2c = sum(1 for s in samples if s['m2'])

# Realism-weighted priority: how many in-scope samples (not Android-only) use each Compose name,
# and each PR's names. A name never reported unresolved in any guest diagnostic is in the
# prototype's API today ("have").
name_n = collections.Counter()
name_bucket = {}
pr_n = collections.Counter()
for s in samples:
    if s['native']:
        continue
    prs = set()
    stubs = {w.split(' | ')[0]: w for k, w in s['guest'] if k == 'stub'}
    for u in s['used']:
        key = (u, pkg_of(s, u))
        if u in stubs:
            b = stub_bucket(stubs[u])
        elif u in s['missing']:
            b = by_package(key[1], u)
        else:
            b = ('HAVE', 'in the prototype')
        if key in name_bucket and name_bucket[key][0] != 'HAVE':
            b = name_bucket[key]  # missing (or a stub) in any file = not there
        name_n[key] += 1
        name_bucket[key] = b
        prs.add('HAVE' if b[0] == 'HAVE' else f'{b[0]}: {b[1]}')
    for p in prs:
        pr_n[p] += 1
with open(os.path.join(report, 'usage-by-pr.md'), 'w') as out:
    n_in = sum(1 for s in samples if not s['native'])
    out.write(f'In-scope samples: {n_in}. Samples using at least one name of each bucket:\n\n')
    for p, n in pr_n.most_common():
        out.write(f'- {n} ({100 * n // n_in}%): {p}\n')
    out.write('\nTop names (samples using them; bucket):\n\n| # | name | samples | package | bucket |\n|---|---|---|---|---|\n')
    for i, (key, n) in enumerate(name_n.most_common(100), 1):
        c, r = name_bucket[key]
        out.write(f'| {i} | `{key[0]}` | {n} | {key[1].replace("androidx.compose.", "")} | {c}: {r} |\n')
    out.write('\nAll names: names-all.tsv\n')
with open(os.path.join(report, 'names-all.tsv'), 'w') as out:
    out.write('samples\tname\tpackage\tclass\tbucket\n')
    for key, n in name_n.most_common():
        c, r = name_bucket[key]
        out.write(f'{n}\t{key[0]}\t{key[1]}\t{c}\t{r}\n')
print(open(os.path.join(report, 'usage-by-pr.md')).read())
print(open(os.path.join(report, 'classes.md')).read())
print('samples using Material 2 names:', m2c)
