/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway

/**
 * NF5 R1 (docs/nf5-r1/README.md), benchmark-only: the keys the guest's `r1prep` stack gives the
 * entries of gate A's retained container, which the host's reveal policy reads. The detail's one
 * entry changes key; the entry itself, and its composition, stay.
 */
public object R1Keys {
  /** The list's entry. */
  public const val LIST: String = "r1:list"

  /** The detail's entry, composed and hidden with the item the next open will tap: revealable. */
  public const val PREPARED: String = "r1:prepared:detail"

  /** The detail's entry after a back, until it is prepared again: hidden, not revealable. */
  public const val PARKED: String = "r1:parked:detail"

  /** The detail's entry while the guest shows it. */
  public const val SHOWN: String = "r1:shown:detail"

  /** Hidden by the stack's order: never shown unless the host reveals it. */
  public fun isPrepared(key: String): Boolean = key == PREPARED || key == PARKED

  public fun isRevealable(key: String): Boolean = key == PREPARED
}
