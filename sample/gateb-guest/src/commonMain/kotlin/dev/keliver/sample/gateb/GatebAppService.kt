/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.gateb

import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.KeliverAppService
import dev.keliver.treehouse.ZiplineTreehouseUi
import kotlinx.serialization.KSerializer

/**
 * Gate B-e's guest service: one screen, the animation benchmark, in one of two modes (the app
 * service code generator takes functions without parameters, so one function per mode).
 */
@KeliverAppService
public interface GatebAppService : AppService {
  /** `animate*AsState` as animated references: the host animates. */
  public fun launchHost(): ZiplineTreehouseUi

  /** `Animatable` on the guest's frame clock. */
  public fun launchGuest(): ZiplineTreehouseUi

  public companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : GeneratedGatebAppServiceAdapter(serializers, serialName)
  }
}
