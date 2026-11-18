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

    checkRegions(errors, model, nodeNames);

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
        // Nothing downstream catches this: the JSIMG is schema-valid, and JMT only logs
        // "could not find entity" and returns zero-sample, unsuccessful measures.
        if (c.referenceStation() != null && !nodeNames.contains(c.referenceStation())) {
          errors.add("closed class '" + c.name() + "': referenceStation '" + c.referenceStation()
              + "' does not exist");
        }
      } else {
        errors.add("class '" + c.name() + "': type must be 'open' or 'closed'");
      }
    }

    // Routing: targets exist; probabilities sum to 1 per (node, class).
    Map<String, List<RoutingEdge>> routing = model.routing() == null ? Map.of() : model.routing();
    Set<String> classNames = model.classes().stream().map(JobClass::name).collect(Collectors.toSet());
    for (Map.Entry<String, List<RoutingEdge>> e : routing.entrySet()) {
      String clazz = e.getKey();
      if (!classNames.contains(clazz)) {
        // The writer emits a connection for every routing entry, so an undeclared key (a typo)
        // would add physical links that other classes' routers could spread traffic over.
        errors.add("routing[" + clazz + "]: '" + clazz + "' is not a declared class");
      }
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

    checkEveryReachedNodeHasAnExit(errors, model, routing, nodeNames);

    if (!errors.isEmpty()) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE, errors);
    }
  }

  /**
   * Finite capacity region rules (spec section 5.3). The v1 restrictions (no partial joins, no
   * closed classes routed in, no finite-capacity queue members) are not arbitrary taste: each guards
   * a case where JMT's occupancy count could go wrong, so the region would stop bounding the work
   * inside it, or deadlock. They are listed in the spec with the reason for each.
   */
  private static void checkRegions(List<String> errors, NetworkModel model, Set<String> nodeNames) {
    if (model.regions() == null) {
      return;
    }
    Map<String, Node> nodeByName = new HashMap<>();
    for (Node n : model.nodes()) {
      nodeByName.put(n.name(), n);
    }
    Set<String> classNames = model.classes().stream().map(JobClass::name).collect(Collectors.toSet());
    Set<String> regionNames = new java.util.HashSet<>();
    Map<String, String> memberOf = new HashMap<>();

    for (Region r : model.regions()) {
      String rn = r.name();
      checkName(errors, "region name", rn);
      String label = "region '" + rn + "'";
      if (rn != null) {
        if (!regionNames.add(rn)) {
          errors.add("region name '" + rn + "' is used by more than one region");
        }
        if (nodeNames.contains(rn)) {
          errors.add(label + ": its name collides with node '" + rn
              + "' (measure rows share the station field)");
        }
        // JMT creates a node called <name>_inputStation for the region's entry queue.
        if (nodeNames.contains(rn + "_inputStation")) {
          errors.add(label + ": node '" + rn + "_inputStation' collides with the entry station JMT "
              + "creates for the region");
        }
      }

      if (r.nodes() == null || r.nodes().isEmpty()) {
        errors.add(label + ": nodes must be a non-empty list");
      } else {
        for (String member : r.nodes()) {
          Node n = nodeByName.get(member);
          if (n == null) {
            errors.add(label + ": node '" + member + "' does not exist");
            continue;
          }
          if (!(n instanceof QueueNode || n instanceof DelayNode || n instanceof ForkJoinNode)) {
            errors.add(label + ": node '" + member + "' is a " + n.type()
                + "; members must be a queue, delay or fork-join");
          }
          String other = memberOf.putIfAbsent(member, String.valueOf(rn));
          if (other != null) {
            errors.add("node '" + member + "' is in more than one region ('" + other + "' and '"
                + rn + "')");
          }
          // A partial join lets the merged parent leave, and so decrement the occupancy, while
          // sibling tasks still hold branch servers inside the region. An omitted join means "all".
          if (n instanceof ForkJoinNode f && f.join() != null && !"all".equals(f.join())) {
            errors.add(label + ": fork-join '" + member + "' must use join \"all\" inside a region");
          }
          // Unverified whether a job dropped inside a region decrements its occupancy; if not,
          // the region fills with ghosts and deadlocks.
          if (n instanceof QueueNode q && q.capacity() != null) {
            errors.add(label + ": queue '" + member + "' has a finite capacity, which a region "
                + "member may not have");
          }
        }
      }

      if (r.capacity() == null || r.capacity() < 1) {
        errors.add(label + ": capacity must be an integer >= 1");
      }
      if (r.classCapacity() != null) {
        for (Map.Entry<String, Integer> cc : r.classCapacity().entrySet()) {
          if (!classNames.contains(cc.getKey())) {
            errors.add(label + ": classCapacity names '" + cc.getKey() + "', which is not a declared class");
          }
          Integer v = cc.getValue();
          if (v == null || v < 1 || (r.capacity() != null && v > r.capacity())) {
            errors.add(label + ": classCapacity['" + cc.getKey() + "'] must be between 1 and capacity");
          }
        }
      }
      Object wf = r.whenFull();
      if (wf instanceof String s) {
        checkWhenFullValue(errors, label, null, s);
      } else if (wf instanceof Map<?, ?> m) {
        for (Map.Entry<?, ?> en : m.entrySet()) {
          String clazz = String.valueOf(en.getKey());
          if (!classNames.contains(clazz)) {
            errors.add(label + ": whenFull names '" + clazz + "', which is not a declared class");
          }
          checkWhenFullValue(errors, label, clazz, en.getValue());
        }
      } else if (wf != null) {
        errors.add(label + ": whenFull must be \"wait\", \"drop\" or a map from class to one of them");
      }

      // A closed population preloaded onto a member bypasses the region entry, so its jobs would
      // leave without ever being counted in and the occupancy would go negative. Routed in from
      // outside is probably fine but untested; both are rejected until a golden test lifts this.
      for (JobClass c : model.classes()) {
        if (!"closed".equals(c.type())) {
          continue;
        }
        List<RoutingEdge> edges = model.routing() == null ? List.of()
            : model.routing().getOrDefault(c.name(), List.of());
        String ref = referenceStationOf(model, c);
        boolean enters = edges.stream().anyMatch(r::isEntry)
            || (ref != null && r.nodes() != null && r.nodes().contains(ref));
        if (enters) {
          errors.add(label + ": closed class '" + c.name() + "' is routed into the region; a "
              + "closed class may not enter a region");
        }
      }
    }
  }

  private static void checkWhenFullValue(List<String> errors, String label, String clazz, Object v) {
    if (!Region.WAIT.equals(v) && !Region.DROP.equals(v)) {
      errors.add(label + ": whenFull" + (clazz == null ? "" : "['" + clazz + "']")
          + " must be \"wait\" or \"drop\", not " + (v == null ? "null" : "'" + v + "'"));
    }
  }

  /**
   * The station a closed class's population starts at (spec section 5.3): its explicit
   * {@code referenceStation}, else its delay node if it has one, else the source of its first
   * routing edge. Returns null when none applies. The writer preloads onto exactly this node, so
   * it must be the one validation treats as reached.
   */
  public static String referenceStationOf(NetworkModel model, JobClass c) {
    if (c.referenceStation() != null) {
      return c.referenceStation();
    }
    for (Node n : model.nodes()) {
      if (n instanceof DelayNode d && d.service() != null && d.service().containsKey(c.name())) {
        return d.name();
      }
    }
    List<RoutingEdge> edges = model.routing() == null ? List.of()
        : model.routing().getOrDefault(c.name(), List.of());
    return edges.isEmpty() ? null : edges.get(0).from();
  }

  /**
   * A class must be able to leave every non-sink node it reaches. JSIMG connections are
   * class-agnostic, so the writer gives a class with no edges out of a node a RandomStrategy
   * router, which would send that class down whatever edges OTHER classes declared there: a silent
   * cross-class leak (issue #18). If no class leaves the node, the job is simply stuck.
   *
   * <p>A class reaches its source (open), its reference station (closed; explicit or defaulted,
   * see {@link #referenceStationOf}), and the target
   * of each of its edges. Counting the target of every edge, not only the edges reachable from the
   * entry point, is deliberately strict: an edge into a dead end is a broken model even when no
   * job happens to take it.
   */
  private static void checkEveryReachedNodeHasAnExit(List<String> errors, NetworkModel model,
      Map<String, List<RoutingEdge>> routing, Set<String> nodeNames) {
    Set<String> sinks = model.nodes().stream()
        .filter(n -> n instanceof SinkNode).map(Node::name).collect(Collectors.toSet());
    for (JobClass c : model.classes()) {
      List<RoutingEdge> edges = routing.getOrDefault(c.name(), List.of());
      Set<String> exits = edges.stream().map(RoutingEdge::from).collect(Collectors.toSet());
      Set<String> reached = new java.util.LinkedHashSet<>();
      for (Node n : model.nodes()) {
        if ("open".equals(c.type()) && n instanceof SourceNode s
            && s.arrivals() != null && s.arrivals().containsKey(c.name())) {
          reached.add(n.name());
        }
      }
      if ("closed".equals(c.type())) {
        String ref = referenceStationOf(model, c);
        if (ref != null) {     // null: the writer reports it as undeterminable
          reached.add(ref);
        }
      }
      edges.forEach(e -> reached.add(e.to()));
      for (String n : reached) {
        // Unknown names are reported elsewhere: edge endpoints by the routing checks (which covers a
        // reference defaulted to an edge source) and an explicit referenceStation by the class checks.
        if (nodeNames.contains(n) && !sinks.contains(n) && !exits.contains(n)) {
          errors.add("routing[" + c.name() + "]: class reaches '" + n
              + "' but has no edge out of it");
        }
      }
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
