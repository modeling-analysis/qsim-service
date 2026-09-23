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
package qsim.translate;

/**
 * One JMT {@code <measure>} to request.
 *
 * @param verbose whether JMT should log this measure's individual samples so it reports second
 *     moments for it. JMT computes {@code mean}/{@code variance}/{@code standardDeviation} by
 *     re-reading the per-sample CSV it writes under {@code <sim logPath>}, and only for verbose
 *     measures in a terminal simulation — so this flag is what makes the response's
 *     {@code variance}/{@code stdDev} fields non-null (issues #14, #15). It costs roughly 40 bytes
 *     of temp file per sample and 25-30% wall clock, which is why it is per measure rather than
 *     global.
 */
public record MeasureSpec(String name, String jmtType, String referenceNode,
                          String referenceUserClass, String nodeType, boolean verbose) {

  /** A measure with no per-sample logging — the shape every measure had before issue #15. */
  public MeasureSpec(String name, String jmtType, String referenceNode,
                     String referenceUserClass, String nodeType) {
    this(name, jmtType, referenceNode, referenceUserClass, nodeType, false);
  }
}
