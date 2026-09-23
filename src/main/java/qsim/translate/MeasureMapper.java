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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import qsim.contract.ValidationException;
import qsim.model.*;

public class MeasureMapper {

  private static final List<String> DEFAULTS =
      List.of("response-time", "utilization", "throughput", "queue-length");

  // domain type -> JMT measure "type" string (station-level)
  private static final Map<String, String> STATION = new LinkedHashMap<>();
  // domain type -> JMT measure "type" string (system-level)
  private static final Map<String, String> SYSTEM = new LinkedHashMap<>();

  static {
    STATION.put("response-time", "Response Time");
    STATION.put("residence-time", "Residence Time");
    STATION.put("queue-time", "Queue Time");
    STATION.put("queue-length", "Number of Customers");
    STATION.put("utilization", "Utilization");
    STATION.put("throughput", "Throughput");
    STATION.put("drop-rate", "Drop Rate");
    STATION.put("interarrival-time", "Arrival Rate");
    SYSTEM.put("system-response-time", "System Response Time");
  }

  /**
   * Fork-join nodes: domain type -> JMT's dedicated fork-region measure string, overriding
   * {@link #STATION}. A station-level {@code "Response Time"} taken anywhere inside the writer's
   * fork/branch/join expansion measures a single station's residence time — at the join station
   * that is only the per-sibling synchronization wait, which is not the fork-to-join sojourn a
   * caller means by {@code response-time} on a fork-join node, and can even fall below a single
   * branch's response time (issue #6). JMT tracks the sojourn under
   * {@code SimConstants.FORK_JOIN_RESPONSE_TIME}, whose job list lives in the *fork* station's
   * input section, so these measures stay anchored on the domain node name.
   *
   * <p>Every other station type still resolves to the join station, so {@code residence-time},
   * {@code queue-time}, {@code queue-length}, {@code utilization}, {@code throughput} and
   * {@code drop-rate} on a fork-join node remain join-station numbers — see the follow-up noted in
   * issue #6 before treating any of them as fork-join-region figures.
   */
  private static final Map<String, String> FORK_JOIN_STATION =
      Map.of("response-time", "Fork Join Response Time");

  /**
   * JMT measure types {@link JsimgWriter} must leave anchored on the fork station instead of
   * remapping onto the internal join station.
   *
   * <p>Two different reasons put a type here. {@link #FORK_JOIN_STATION}'s measures are collected
   * from the job list the *fork* station's input section maintains between fork and join, so the
   * join is simply the wrong anchor and the type is meaningless anywhere else (issue #6).
   * {@code "Arrival Rate"} is anchored for an unrelated reason: it is valid on any station, but the
   * join's job list is fed one arrival per sibling branch rather than one per job, so an
   * interarrival time taken there is the inter-sibling gap (measured 0.248 against the fork's 0.083
   * on three branches) rather than the gap between jobs entering the fork-join. Hence the split from
   * {@link #FORK_JOIN_ONLY_TYPES}: everything here stays on the fork, but only those are *rejected*
   * elsewhere.
   */
  static final Set<String> FORK_ANCHORED_TYPES;

  /** Types meaningful only on a fork-join node; {@link JsimgWriter} rejects them on any other. */
  static final Set<String> FORK_JOIN_ONLY_TYPES = Set.copyOf(FORK_JOIN_STATION.values());

  static {
    var anchored = new java.util.HashSet<>(FORK_JOIN_ONLY_TYPES);
    anchored.add("Arrival Rate");
    FORK_ANCHORED_TYPES = Set.copyOf(anchored);
  }

  /**
   * JMT measure types whose {@code MeasureResult.mean} must be read from the output document's
   * raw-sample {@code mean} attribute rather than {@code meanValue}.
   *
   * <p>{@code "Arrival Rate"} is an {@code InverseMeasure}: {@code getMeanValue()} returns
   * {@code 1.0 / analyzer.getMean()}, so {@code meanValue} is a rate while the samples underneath —
   * and therefore the {@code mean}, {@code variance} and {@code standardDeviation} attributes — are
   * interarrival times. Reporting the rate as {@code mean} next to a variance over times would hand
   * a caller computing {@code variance / mean^2} an answer wrong by orders of magnitude, so qsim
   * reports the interarrival time itself and names the domain type after it. The arrival rate is
   * {@code 1 / mean}, and {@code throughput} already reports it directly.
   */
  public static final Set<String> RAW_SAMPLE_MEAN = Set.of("Arrival Rate");

  /**
   * JMT measure types qsim reports as a rate even though their verbose statistics describe the
   * inter-event times between the events being counted — the same {@code InverseMeasure} mismatch as
   * {@link #RAW_SAMPLE_MEAN}, but for types whose rate is the figure a caller wants. Their second
   * moments are in the wrong units to sit beside that mean (a measured rate of 0.30088 with a
   * variance of 10.747 gives an SCV of 118.7 against a true 0.961), so
   * {@link qsim.result.SolutionsParser} drops them and {@link #withVerbose} does not pay to log
   * them. Mirrors {@code EngineUtils.isInverseMeasure} for the subset qsim emits; exposing these
   * moments properly would mean a separate {@code interdeparture-time} type.
   */
  public static final Set<String> RATE_TYPED = Set.of("Throughput", "Drop Rate");

  /** Domain measure types whose figures exist only when JMT logs the individual samples. */
  private static final Set<String> REQUIRES_VERBOSE = Set.of("interarrival-time");

  public static final Set<String> SUPPORTED;
  static {
    var all = new java.util.HashSet<String>();
    all.addAll(STATION.keySet());
    all.addAll(SYSTEM.keySet());
    // A fork-join-only domain type (one with no plain-station equivalent in STATION) would
    // otherwise be rejected by map() as unsupported. No-op while every FORK_JOIN_STATION key is
    // also a STATION key; here so the next fork-join measure type does not trip over it.
    all.addAll(FORK_JOIN_STATION.keySet());
    SUPPORTED = Set.copyOf(all);
  }

  public List<MeasureSpec> map(NetworkModel model, List<String> requested) {
    List<String> types = (requested == null || requested.isEmpty()) ? DEFAULTS : requested;
    for (String t : types) {
      if (!SUPPORTED.contains(t)) {
        throw new ValidationException(ValidationException.Kind.BAD_REQUEST,
            List.of("unsupported measure type: '" + t + "'; supported: " + SUPPORTED));
      }
    }
    List<MeasureSpec> specs = new ArrayList<>();
    for (String t : types) {
      if (STATION.containsKey(t)) {
        String jmt = STATION.get(t);
        for (Node n : model.nodes()) {
          String jmtForNode = n instanceof ForkJoinNode ? FORK_JOIN_STATION.getOrDefault(t, jmt) : jmt;
          for (String clazz : servedClasses(n)) {
            specs.add(new MeasureSpec(n.name() + "_" + clazz + "_" + t, jmtForNode,
                n.name(), clazz, "station", REQUIRES_VERBOSE.contains(t)));
          }
        }
      } else { // system-level
        String jmt = SYSTEM.get(t);
        for (JobClass c : model.classes()) {
          specs.add(new MeasureSpec("system_" + c.name() + "_" + t, jmt, "", c.name(), ""));
        }
      }
    }
    return specs;
  }

  /**
   * Every spec with per-sample logging switched on, except {@link #RATE_TYPED} measures whose second
   * moments the parser discards anyway. Backs the request-level {@code secondMoments} opt-in: JMT's
   * verbose output is per measure, so turning it on globally is a decision about which specs carry
   * the flag rather than a separate switch.
   */
  public static List<MeasureSpec> withVerbose(List<MeasureSpec> specs) {
    List<MeasureSpec> out = new ArrayList<>(specs.size());
    for (MeasureSpec s : specs) {
      out.add(new MeasureSpec(s.name(), s.jmtType(), s.referenceNode(), s.referenceUserClass(),
          s.nodeType(), !RATE_TYPED.contains(s.jmtType())));
    }
    return out;
  }

  /** Classes with service defined at this node (queue/delay/fork-join). Sources/sinks yield none. */
  private static List<String> servedClasses(Node n) {
    if (n instanceof QueueNode q) {
      return new ArrayList<>(q.service().keySet());
    }
    if (n instanceof DelayNode d) {
      return new ArrayList<>(d.service().keySet());
    }
    if (n instanceof ForkJoinNode fj) {
      var set = new java.util.LinkedHashSet<String>();
      for (Branch b : fj.branches()) {
        set.addAll(b.service().keySet());
      }
      return new ArrayList<>(set);
    }
    return List.of();
  }
}
