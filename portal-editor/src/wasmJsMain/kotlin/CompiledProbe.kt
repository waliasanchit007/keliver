/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.portal.render.CompiledComponentProbe
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChangesSink
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolRedwoodComposition
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Compiled component preview's failure probe: composes the compiled call once
 * in a throwaway guest composition — the canvas's own protocol widget system,
 * whose changes go nowhere — and reports what it threw. The initial
 * composition is synchronous, so the answer is known before the canvas
 * composes the call. A throw in the canvas composition itself would stop the
 * whole preview (Compose cannot catch it around a composable call).
 */
internal object DiscardingCompiledProbe : CompiledComponentProbe {
  private val noBack = object : OnBackPressedDispatcher {
    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable =
      object : Cancellable { override fun cancel() {} }
  }

  override fun failureOf(content: @Composable () -> Unit): Throwable? {
    val guest = DefaultGuestProtocolAdapter(
      hostVersion = guestRedwoodVersion,
      widgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    )
    guest.initChangesSink(object : ChangesSink { override fun sendChanges(changes: List<Change>) {} })
    val job = Job()
    val composition = ProtocolRedwoodComposition(
      scope = CoroutineScope(job + BroadcastFrameClock()),
      guestAdapter = guest,
      widgetVersion = EDITOR_WIDGET_VERSION.toUInt(),
      onBackPressedDispatcher = noBack,
      saveableStateRegistry = null,
      uiConfigurations = MutableStateFlow(UiConfiguration()),
    )
    return try {
      composition.setContent(content)
      null
    } catch (e: Throwable) {
      e
    } finally {
      runCatching { composition.cancel() }
      job.cancel()
    }
  }
}
