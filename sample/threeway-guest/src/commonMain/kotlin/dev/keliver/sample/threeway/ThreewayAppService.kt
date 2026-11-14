/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway

import app.cash.zipline.ZiplineService
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.KeliverAppService
import dev.keliver.treehouse.ZiplineTreehouseUi
import kotlinx.serialization.KSerializer

/** The three-way benchmark's guest service (docs/THREE_WAY_BENCHMARK.md): one app, list and detail. */
@KeliverAppService
public interface ThreewayAppService : AppService {
  public fun launch(): ZiplineTreehouseUi

  public companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : GeneratedThreewayAppServiceAdapter(serializers, serialName)
  }
}

/**
 * Benchmark-only host service: the guest reports that its composition applied a screen, with the
 * marks it recorded on its own clock since the last report (a JSON object of name to epoch ms per
 * sequence number, `{"3":{"click":…,"request":…}}`). One bridge call per composed screen, made
 * before the guest's change batch for that frame is sent.
 */
public interface ThreewayHost : ZiplineService {
  public fun composed(screen: String, seq: Int, marksJson: String)
}
