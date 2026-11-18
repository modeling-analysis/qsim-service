/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.engine;

import NodeSections.Join;
import NodeSections.Linkage;
import NodeSections.Storage;
import QueueNet.BlockingRegion;

/**
 * Called by the build-time-patched {@code simEngine.Simulation.initialize()} (see
 * {@code qsim.build.JmtFcrPatcher}) in place of JMT's hard-wired Storage/Linkage checks, so a
 * finite capacity region may contain fork-joins.
 *
 * <p>Why skipping the hook is safe (spec section 3, "Why the restriction is overly conservative
 * here"): the region's occupancy is incremented where a job enters through a Storage input and
 * decremented where it leaves through a Linkage output. A Join input or a Fork output is an
 * interior hop: a job crossing it stays inside the region, and it is the fork's parent job that
 * enters and leaves. So nothing is lost by not hooking them. That holds only because the contract
 * keeps whole fork-joins inside a region, never straddling its border (spec section 5.3).
 *
 * <p>The signatures are fixed: the patched bytecode references them by name and descriptor.
 */
public final class FcrShim {

  private FcrShim() {}

  /** Replaces {@code instanceof Storage}; a Join input is also accepted. */
  public static boolean storageOrJoin(Object section) {
    return section instanceof Storage || section instanceof Join;
  }

  /** Replaces {@code Storage.redirectionTurnON}; a Join input has nothing to redirect. */
  public static void redirect(Object section, BlockingRegion region) {
    if (section instanceof Storage storage) {
      storage.redirectionTurnON(region);
    }
  }

  /** Replaces {@code instanceof Linkage}; a Fork output is also accepted. */
  public static boolean linkageOrFork(Object section) {
    return section instanceof Linkage || section instanceof NodeSections.Fork;
  }

  /** Replaces {@code Linkage.borderRouterTurnON}; a Fork output has no border to mark. */
  public static void border(Object section, BlockingRegion region) {
    if (section instanceof Linkage linkage) {
      linkage.borderRouterTurnON(region);
    }
  }
}
