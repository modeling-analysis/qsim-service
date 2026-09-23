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
package qsim.model;

import java.util.List;

/**
 * @param secondMoments request per-sample logging for every measure that can carry second moments,
 *     so {@code variance} and {@code stdDev} come back populated (issues #14, #15). Off by default
 *     because it costs roughly 40 bytes of temp file per sample and 25-30% wall clock — at a
 *     {@code maxSamples} of 1,000,000 that is tens of megabytes per measure. {@code
 *     interarrival-time} logs its own samples regardless, since it has nothing to report without
 *     them.
 */
public record SimulationRequest(NetworkModel model, Long seed, Stopping stopping,
                                List<String> measures, Boolean secondMoments) {

  /** A request that does not ask for second moments — the shape callers sent before issue #15. */
  public SimulationRequest(NetworkModel model, Long seed, Stopping stopping, List<String> measures) {
    this(model, seed, stopping, measures, null);
  }
}
