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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import qsim.model.*;
import org.junit.jupiter.api.Test;

class ContractValidatorTest {

  private final ContractValidator validator = new ContractValidator();

  private SimulationRequest req(NetworkModel model) {
    return new SimulationRequest(model, 1L, null, null);
  }

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  @Test
  void acceptsAValidOpenModel() {
    NetworkModel m = new NetworkModel("ok",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    assertDoesNotThrow(() -> validator.validate(req(m)));
  }

  @Test
  void rejectsOpenClassNotAnchoredToExactlyOneSource() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SinkNode("snk", "sink")),
        Map.of("web", List.of()));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("web") && s.contains("source")));
  }

  @Test
  void rejectsClosedClassWithoutPopulation() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("batch", "closed", null, "think")),
        List.of(new DelayNode("think", "delay", Map.of("batch", new ServiceSpec(exp(0.2))))),
        Map.of("batch", List.of(new RoutingEdge("think", "think", 1.0))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("population")));
  }

  @Test
  void rejectsDanglingRoutingTarget() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "ghost", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("ghost")));
  }

  @Test
  void rejectsProbabilitiesNotSummingToOne() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("a", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new QueueNode("b", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(
            new RoutingEdge("src", "a", 0.5), new RoutingEdge("src", "b", 0.3),
            new RoutingEdge("a", "snk", 1.0), new RoutingEdge("b", "snk", 1.0))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("probab")));
  }

  @Test
  void rejectsQueueWithZeroServersOrNegativeCapacity() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 0, "fcfs", -5, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("servers")));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("capacity")));
  }

  /**
   * The measure name a node name ends up inside becomes a CSV filename once a measure is verbose
   * (issue #15), so a name carrying a path is a write outside the run's temp directory.
   */
  @Test
  void rejectsNodeNameThatWouldEscapeTheLogDirectory() {
    String escaping = "../../../../tmp/pwn";
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode(escaping, "queue", 1, "fcfs", null,
                Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", escaping, null),
                              new RoutingEdge(escaping, "snk", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("node name") && s.contains("pwn")));
  }

  @Test
  void rejectsClassNameWithASeparatorOrDotSegment() {
    for (String bad : List.of("web/x", "web\\x", "..", ".hidden", "a b")) {
      NetworkModel m = new NetworkModel("bad",
          List.of(new JobClass(bad, "open", null, null)),
          List.of(
              new SourceNode("src", "source", Map.of(bad, new ArrivalSpec(exp(1.0)))),
              new QueueNode("q", "queue", 1, "fcfs", null, Map.of(bad, new ServiceSpec(exp(2.0)))),
              new SinkNode("snk", "sink")),
          Map.of(bad, List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
      ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)),
          "must reject class name '" + bad + "'");
      assertTrue(ex.details().stream().anyMatch(s -> s.contains("class name")),
          "details must name the offending class for '" + bad + "': " + ex.details());
    }
  }

  @Test
  void rejectsNameLongerThan64Characters() {
    String tooLong = "q".repeat(65);
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode(tooLong, "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", tooLong, null),
                              new RoutingEdge(tooLong, "snk", null))));
    assertThrows(ValidationException.class, () -> validator.validate(req(m)));
  }

  /** The names every fixture and example in the repo actually uses must keep working. */
  @Test
  void acceptsOrdinaryIdentifierNames() {
    // "src" and "snk" are deliberately absent: reusing them as the queue's name collides with the
    // source and sink in this fixture and trips the routing rules, which is a different test.
    for (String ok : List.of("q", "web", "machine-repairmen", "q_2", "Q.1", "fj", "Q1", "a")) {
      NetworkModel m = new NetworkModel("ok",
          List.of(new JobClass("web", "open", null, null)),
          List.of(
              new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
              new QueueNode(ok, "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
              new SinkNode("snk", "sink")),
          Map.of("web", List.of(new RoutingEdge("src", ok, null), new RoutingEdge(ok, "snk", null))));
      assertDoesNotThrow(() -> validator.validate(req(m)), "must accept node name '" + ok + "'");
    }
  }

  // ---- issue #18: a class must be able to leave every non-sink node it reaches -----------------

  /** Two open classes on disjoint routes out of one source; {@code bRoutes} is class b's routing. */
  private NetworkModel twoClass(List<RoutingEdge> bRoutes) {
    Map<String, ServiceSpec> both = Map.of("a", new ServiceSpec(exp(2.0)), "b", new ServiceSpec(exp(2.0)));
    return new NetworkModel("two",
        List.of(new JobClass("a", "open", null, null), new JobClass("b", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("a", new ArrivalSpec(exp(1.0)), "b", new ArrivalSpec(exp(1.0)))),
            new QueueNode("qa", "queue", 1, "fcfs", null, both),
            new QueueNode("qb", "queue", 1, "fcfs", null, both),
            new SinkNode("snk", "sink")),
        Map.of("a", List.of(new RoutingEdge("src", "qa", null), new RoutingEdge("qa", "snk", null)),
               "b", bRoutes));
  }

  @Test
  void acceptsClassesOnDisjointRoutes() {
    assertDoesNotThrow(() -> validator.validate(req(twoClass(
        List.of(new RoutingEdge("src", "qb", null), new RoutingEdge("qb", "snk", null))))));
  }

  @Test
  void rejectsAClassThatReachesANodeItCannotLeave() {
    // b enters qb but has no edge out of it. The writer would hand b to whatever edges other
    // classes declared at qb (none here, but in general a silent cross-class leak).
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(twoClass(
        List.of(new RoutingEdge("src", "qb", null))))));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("routing[b]") && s.contains("'qb'")),
        ex.details().toString());
  }

  @Test
  void rejectsAnOpenClassWithNoEdgeOutOfItsSource() {
    // b arrives at src but has no edge out of it: a RandomStrategy router would send b down a's
    // edge src -> qa, exactly the class mixing of issue #18.
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(twoClass(
        List.of()))));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("routing[b]") && s.contains("'src'")),
        ex.details().toString());
  }
}
