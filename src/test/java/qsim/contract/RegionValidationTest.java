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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.model.*;

class RegionValidationTest {

  private final ContractValidator validator = new ContractValidator();

  private static Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private static Map<String, ServiceSpec> svc(String... classes) {
    Map<String, ServiceSpec> m = new java.util.LinkedHashMap<>();
    for (String c : classes) {
      m.put(c, new ServiceSpec(exp(2.0)));
    }
    return m;
  }

  private static QueueNode queue(String name, Integer capacity, String... classes) {
    return new QueueNode(name, "queue", 1, "fcfs", capacity, svc(classes));
  }

  private static ForkJoinNode fj(String join) {
    return new ForkJoinNode("fj", "fork-join",
        List.of(new Branch(svc("a")), new Branch(svc("a"))), join);
  }

  private static RoutingEdge e(String from, String to) { return new RoutingEdge(from, to, null); }

  /** src -> q1 -> fj -> q2 -> snk for open class a; src -> q3 -> snk for open class b. */
  private NetworkModel model(Node q1, Node fork, List<Region> regions, List<Node> extraNodes,
      List<JobClass> extraClasses, Map<String, List<RoutingEdge>> extraRouting) {
    List<Node> nodes = new ArrayList<>(List.of(
        new SourceNode("src", "source",
            Map.of("a", new ArrivalSpec(exp(1.0)), "b", new ArrivalSpec(exp(1.0)))),
        q1, fork, queue("q2", null, "a"), queue("q3", null, "b"), new SinkNode("snk", "sink")));
    nodes.addAll(extraNodes);
    List<JobClass> classes = new ArrayList<>(List.of(
        new JobClass("a", "open", null, null), new JobClass("b", "open", null, null)));
    classes.addAll(extraClasses);
    Map<String, List<RoutingEdge>> routing = new java.util.LinkedHashMap<>();
    routing.put("a", List.of(e("src", "q1"), e("q1", "fj"), e("fj", "q2"), e("q2", "snk")));
    routing.put("b", List.of(e("src", "q3"), e("q3", "snk")));
    routing.putAll(extraRouting);
    return new NetworkModel("m", classes, nodes, routing, regions);
  }

  private NetworkModel model(List<Region> regions) {
    return model(queue("q1", null, "a"), fj("all"), regions, List.of(), List.of(), Map.of());
  }

  private static Region region(String name, List<String> nodes, Integer cap) {
    return new Region(name, nodes, cap, null, null);
  }

  private static Region valid() { return region("R", List.of("q1", "fj", "q2"), 1); }

  private void rejects(NetworkModel m, String... fragments) {
    ValidationException ex = assertThrows(ValidationException.class,
        () -> validator.validate(new SimulationRequest(m, 1L, null, null)));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    for (String f : fragments) {
      assertTrue(ex.details().stream().anyMatch(s -> s.contains(f)),
          "expected a message containing [" + f + "] in " + ex.details());
    }
  }

  private void accepts(NetworkModel m) {
    assertDoesNotThrow(() -> validator.validate(new SimulationRequest(m, 1L, null, null)));
  }

  @Test
  void acceptsAValidRegion() {
    accepts(model(List.of(valid())));
  }

  @Test
  void rejectsEmptyNodes() {
    rejects(model(List.of(region("R", List.of(), 1))), "region 'R'", "nodes");
  }

  @Test
  void rejectsUnknownMember() {
    rejects(model(List.of(region("R", List.of("q1", "ghost"), 1))), "'ghost'");
  }

  @Test
  void rejectsSourceOrSinkMember() {
    rejects(model(List.of(region("R", List.of("q1", "src"), 1))), "'src'", "queue, delay or fork-join");
    rejects(model(List.of(region("R", List.of("q1", "snk"), 1))), "'snk'", "queue, delay or fork-join");
  }

  @Test
  void rejectsNodeInTwoRegions() {
    rejects(model(List.of(region("R", List.of("q1", "fj"), 1), region("S", List.of("q1", "q2"), 1))),
        "'q1'", "more than one region");
  }

  @Test
  void rejectsDuplicateRegionName() {
    rejects(model(List.of(region("R", List.of("q1"), 1), region("R", List.of("q2"), 1))),
        "region name 'R'");
  }

  @Test
  void rejectsRegionNamedLikeANode() {
    rejects(model(List.of(region("q3", List.of("q1"), 1))), "'q3'");
  }

  @Test
  void rejectsInputStationCollision() {
    NetworkModel m = model(queue("q1", null, "a"), fj("all"), List.of(valid()),
        List.of(queue("R_inputStation", null, "b")), List.of(), Map.of());
    rejects(m, "R_inputStation");
  }

  @Test
  void rejectsUnsafeRegionName() {
    rejects(model(List.of(region("a b", List.of("q1"), 1))), "region name");
  }

  @Test
  void rejectsCapacityBelowOne() {
    rejects(model(List.of(region("R", List.of("q1"), 0))), "capacity");
    rejects(model(List.of(region("R", List.of("q1"), null))), "capacity");
  }

  @Test
  void rejectsClassCapacityForUnknownClass() {
    rejects(model(List.of(new Region("R", List.of("q1"), 2, Map.of("zz", 1), null))), "'zz'");
  }

  @Test
  void rejectsClassCapacityBelowOneOrAboveCapacity() {
    rejects(model(List.of(new Region("R", List.of("q1"), 2, Map.of("a", 0), null))), "classCapacity");
    rejects(model(List.of(new Region("R", List.of("q1"), 2, Map.of("a", 3), null))), "classCapacity");
  }

  @Test
  void rejectsWhenFullUnknownValueOrClass() {
    rejects(model(List.of(new Region("R", List.of("q1"), 1, null, "maybe"))), "whenFull");
    rejects(model(List.of(new Region("R", List.of("q1"), 1, null, Map.of("zz", "wait")))), "whenFull");
    rejects(model(List.of(new Region("R", List.of("q1"), 1, null, Map.of("a", "maybe")))), "whenFull");
  }

  @Test
  void acceptsWhenFullDropAsStringOrMap() {
    accepts(model(List.of(new Region("R", List.of("q1", "fj", "q2"), 1, null, "drop"))));
    accepts(model(List.of(new Region("R", List.of("q1", "fj", "q2"), 1, null, Map.of("a", "drop")))));
  }

  @Test
  void rejectsPartialJoinMember() {
    NetworkModel m = model(queue("q1", null, "a"), fj("1"), List.of(valid()), List.of(), List.of(), Map.of());
    rejects(m, "'fj'", "join \"all\"");
  }

  @Test
  void acceptsDefaultJoinMember() {
    accepts(model(queue("q1", null, "a"), fj(null), List.of(valid()), List.of(), List.of(), Map.of()));
  }

  @Test
  void rejectsFiniteCapacityQueueMember() {
    NetworkModel m = model(queue("q1", 5, "a"), fj("all"), List.of(valid()), List.of(), List.of(), Map.of());
    rejects(m, "'q1'", "capacity");
  }

  private NetworkModel withClosedClass(List<Region> regions, boolean entersRegion) {
    List<Node> extra = new ArrayList<>(List.of(
        new DelayNode("think", "delay", svc("c")), queue("q4", null, "c")));
    Map<String, List<RoutingEdge>> routing = entersRegion
        ? Map.of("c", List.of(e("think", "q1"), e("q1", "think")))
        : Map.of("c", List.of(e("think", "q4"), e("q4", "think")));
    List<Node> q1Nodes = entersRegion
        ? List.of(queue("q1", null, "a", "c"))
        : List.of(queue("q1", null, "a"));
    return model(q1Nodes.get(0), fj("all"), regions, extra,
        List.of(new JobClass("c", "closed", 3, "think")), routing);
  }

  @Test
  void rejectsClosedClassIntoRegion() {
    rejects(withClosedClass(List.of(valid()), true), "closed class", "'R'");
  }

  @Test
  void rejectsClosedClassWhoseReferenceStationIsAMember() {
    NetworkModel m = model(queue("q1", null, "a", "c"), fj("all"), List.of(valid()), List.of(),
        List.of(new JobClass("c", "closed", 3, "q1")), Map.of("c", List.of(e("q1", "q1"))));
    rejects(m, "closed class", "'R'");
  }

  @Test
  void acceptsClosedClassThatNeverEntersARegion() {
    accepts(withClosedClass(List.of(valid()), false));
  }
}
