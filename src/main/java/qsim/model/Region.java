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
import java.util.Map;

/**
 * A finite capacity region: a set of member nodes that together hold at most {@code capacity}
 * customers (JMT's blocking region).
 *
 * <p>{@code whenFull} is deliberately an {@code Object}: the JSON is either one string applying to
 * every class ({@code "drop"}) or a class-to-policy map, and Jackson binds those to {@code String}
 * and {@code Map<String,String>} respectively. {@link #whenFullFor} is the only way callers should
 * read it; it hides the two shapes and applies the default, "wait" (spec section 5.1), which differs
 * from JMT's own default of dropping open classes.
 */
public record Region(
    String name,
    List<String> nodes,
    Integer capacity,
    Map<String, Integer> classCapacity,
    Object whenFull) {

  public static final String WAIT = "wait";
  public static final String DROP = "drop";

  /** The policy for a class: "wait" or "drop". A class a map omits, or a null whenFull, waits. */
  public String whenFullFor(String className) {
    if (whenFull instanceof String s) {
      return s;
    }
    if (whenFull instanceof Map<?, ?> m && m.get(className) instanceof String s) {
      return s;
    }
    return WAIT;
  }

  public boolean drops(String className) {
    return DROP.equals(whenFullFor(className));
  }

  /**
   * True when the edge crosses into this region from outside: its source is not a member and its
   * target is. This is the single definition of "a class enters a region", shared by the
   * closed-class validation rule and the region measure expansion.
   */
  public boolean isEntry(RoutingEdge e) {
    return nodes != null && !nodes.contains(e.from()) && nodes.contains(e.to());
  }
}
