/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.gateb

import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.KeliverAppService
import dev.keliver.treehouse.ZiplineTreehouseUi
import kotlinx.serialization.KSerializer

/** Gate B-e's guest service: one screen, the animation benchmark, in a [mode] ("host" or "guest"). */
@KeliverAppService
public interface GatebAppService : AppService {
  public fun launch(mode: String): ZiplineTreehouseUi

  public companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : GeneratedGatebAppServiceAdapter(serializers, serialName)
  }
}
