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

public record NetworkModel(
    String name,
    List<JobClass> classes,
    List<Node> nodes,
    Map<String, List<RoutingEdge>> routing,
    List<Region> regions) {

  /** A model without finite capacity regions; {@code regions} is null, as when the JSON omits it. */
  public NetworkModel(String name, List<JobClass> classes, List<Node> nodes,
      Map<String, List<RoutingEdge>> routing) {
    this(name, classes, nodes, routing, null);
  }
}
