/*
 * Copyright (C) 2026 Konduit contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.keliver.guest

/**
 * Marker identifying this artifact as the `keliver-guest` facade. The
 * module exists primarily to aggregate the public guest-side Konduit
 * API surface via `api` dependencies; this constant gives the KMP
 * compiler something to package so each platform produces a klib /
 * jar artifact suitable for publishing.
 */
@Suppress("unused")
internal const val FACADE_MARKER: String = "keliver-guest"
