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
package qsim.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import qsim.model.*;

public class ContractValidator {

  private static final double EPS = 1e-6;

  /**
   * Node and class names a caller may send. Deliberately narrow, because these names are not only
   * XML attribute values and routing-map keys — they become *filenames*. A measure's {@code name} is
   * built from the node and class name ({@code MeasureMapper.map}), and JMT writes a verbose
   * measure's per-sample log to {@code <logPath>/<measure name>.csv} (issue #15), so a name holding a
   * path separator or a {@code ..} segment is a write outside the run's temp directory. Requiring the
   * first character to be alphanumeric excludes {@code .} and {@code ..} without a special case, and
   * the 64-character cap keeps the composed measure name inside every filesystem's limit.
   *
   * <p>Every node and class name in the repo's fixtures and examples is a plain lowercase
   * identifier, so this tightens the contract without breaking a caller we know about — it is a
   * documented contract change nonetheless (README).
   */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public void validate(SimulationRequest req) {
    List<String> errors = new ArrayList<>();
    NetworkModel model = req.model();
    if (model == null || model.nodes() == null || model.classes() == null) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("model, model.nodes and model.classes are required"));
    }

    for (JobClass c : model.classes()) {
      checkName(errors, "class name", c.name());
    }
    for (Node n : model.nodes()) {
      checkName(errors, "node name", n.name());
    }

    Set<String> nodeNames = model.nodes().stream().map(Node::name).collect(Collectors.toSet());

    // Queue invariants: servers >= 1; capacity null or positive.
    for (Node n : model.nodes()) {
      if (n instanceof QueueNode q) {
        if (q.servers() == null || q.servers() < 1) {
          errors.add("queue '" + q.name() + "': servers must be >= 1");
        }
        if (q.capacity() != null && q.capacity() <= 0) {
          errors.add("queue '" + q.name() + "': capacity must be null (infinite) or a positive integer");
        }
      }
    }

    // Open class anchored to exactly one source.
    for (JobClass c : model.classes()) {
      if ("open".equals(c.type())) {
        long sources = model.nodes().stream()
            .filter(n -> n instanceof SourceNode)
            .map(n -> (SourceNode) n)
            .filter(s -> s.arrivals() != null && s.arrivals().containsKey(c.name()))
            .count();
        if (sources != 1) {
          errors.add("open class '" + c.name() + "' must be listed in exactly one source's arrivals (found "
              + sources + ")");
        }
      } else if ("closed".equals(c.type())) {
        if (c.population() == null || c.population() < 1) {
          errors.add("closed class '" + c.name() + "' must have a population >= 1");
        }
      } else {
        errors.add("class '" + c.name() + "': type must be 'open' or 'closed'");
      }
    }

    // Routing: targets exist; probabilities sum to 1 per (node, class).
    Map<String, List<RoutingEdge>> routing = model.routing() == null ? Map.of() : model.routing();
    for (Map.Entry<String, List<RoutingEdge>> e : routing.entrySet()) {
      String clazz = e.getKey();
      Map<String, List<RoutingEdge>> byFrom = new HashMap<>();
      for (RoutingEdge edge : e.getValue()) {
        if (!nodeNames.contains(edge.from())) {
          errors.add("routing[" + clazz + "]: 'from' node '" + edge.from() + "' does not exist");
        }
        if (!nodeNames.contains(edge.to())) {
          errors.add("routing[" + clazz + "]: 'to' node '" + edge.to() + "' does not exist");
        }
        byFrom.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
      }
      for (Map.Entry<String, List<RoutingEdge>> g : byFrom.entrySet()) {
        List<RoutingEdge> edges = g.getValue();
        if (edges.size() == 1) {
          continue; // single edge defaults to probability 1.0
        }
        double sum = 0;
        for (RoutingEdge edge : edges) {
          if (edge.probability() == null) {
            errors.add("routing[" + clazz + "] from '" + g.getKey()
                + "': each of multiple edges must set a probability");
            sum = Double.NaN;
            break;
          }
          sum += edge.probability();
        }
        if (!Double.isNaN(sum) && Math.abs(sum - 1.0) > EPS) {
          errors.add("routing[" + clazz + "] from '" + g.getKey()
              + "': probabilities must sum to 1.0 (got " + sum + ")");
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE, errors);
    }
  }

  private static void checkName(List<String> errors, String kind, String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      errors.add(kind + " " + (name == null ? "(null)" : "'" + name + "'")
          + " is not allowed: use 1-64 characters from [A-Za-z0-9._-], starting with a letter or "
          + "digit. These names become per-measure log filenames, so path separators, spaces and "
          + "'.'/'..' segments are rejected.");
    }
  }
}
