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
package qsim.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.SimulationService;
import qsim.model.*;

/**
 * Issue #15, end to end against analytic oracles.
 *
 * <p>The oracles: a Poisson arrival stream has interarrival SCV 1 by definition, and by Burke's
 * theorem an M/M/1's departure process is Poisson as well — so in a tandem Src -> Q1 -> Q2 with
 * exponential arrivals *both* stations must measure SCV ~ 1. Replacing the arrivals with a
 * deterministic stream gives SCV 0 at Q1 and a clearly positive SCV at Q2, because the queue
 * reshapes the stream it passes on. Asserting both directions is what separates a real measurement
 * from a plausible constant: a stubbed 1.0 would pass the exponential case and fail the
 * deterministic one.
 *
 * <p>The tolerances are wide on purpose. This is a stochastic simulation at a loose precision, and
 * the test's job is to catch a measure wired to the wrong quantity or the wrong units, not to
 * certify convergence.
 */
class InterarrivalMomentsTest {

  private Distribution exp(double rate) {
    return new Distribution("exponential", rate, null, null, null);
  }

  private Distribution det(double t) {
    return new Distribution("deterministic", null, t, null, null);
  }

  /** Src -> q1 -> q2 -> snk, one open class, exponential service at rate 1.0 (rho = 0.5). */
  private NetworkModel tandem(Distribution arrivals) {
    return new NetworkModel("tandem",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(arrivals))),
                new QueueNode("q1", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new QueueNode("q2", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q1", null),
                              new RoutingEdge("q1", "q2", null),
                              new RoutingEdge("q2", "snk", null))));
  }

  /** Loose precision with a real floor: enough samples for a stable SCV, small enough logs. */
  private Stopping stopping() {
    return new Stopping(0.05, 0.10, 20_000, 400_000, null, null, 300, false);
  }

  private MeasureResult find(SimulationResponse res, String station, String type) {
    return res.measures().stream()
        .filter(m -> station.equals(m.station()) && type.equals(m.type()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "no " + type + " measure for " + station + " in " + res.measures()));
  }

  private double scv(MeasureResult m) {
    assertNotNull(m.mean(), "mean must be reported for " + m.station());
    assertNotNull(m.variance(), "variance must be reported for " + m.station());
    return m.variance() / (m.mean() * m.mean());
  }

  /** The real engine and the real collaborators — the same wiring SimulationServiceTest uses. */
  private SimulationResponse simulate(SimulationRequest request) {
    return new SimulationService(Config.defaults()).simulate(request);
  }

  @Test
  void poissonArrivalsGiveInterarrivalScvOneAtBothStationsOfATandem() {
    SimulationResponse res = simulate(new SimulationRequest(
        tandem(exp(0.5)), 20260923L, stopping(), List.of("interarrival-time"), null));

    MeasureResult a1 = find(res, "q1", "interarrival-time");
    MeasureResult a2 = find(res, "q2", "interarrival-time");

    // E[A] = 1/lambda = 2.0 at both stations: in an open tandem every job passes through both.
    assertEquals(2.0, a1.mean(), 0.15, "mean interarrival time at q1 must be 1/lambda");
    assertEquals(2.0, a2.mean(), 0.15, "throughput is conserved, so q2 sees the same mean");

    assertEquals(1.0, scv(a1), 0.15, "Poisson arrivals have interarrival SCV 1");
    assertEquals(1.0, scv(a2), 0.20, "Burke: an M/M/1's departures are Poisson too");

    // stdDev is just sqrt(variance) - pinned so the two attributes cannot come from different places.
    assertEquals(Math.sqrt(a1.variance()), a1.stdDev(), 1e-9);

    // No CI on this measure: JMT's interval is on the rate (see the parser). alpha still describes
    // the run, so its presence is what distinguishes "dropped" from "measure never ran".
    assertNull(a1.lower());
    assertNull(a1.upper());
    assertEquals(0.05, a1.alpha());
    assertTrue(a1.samplesAnalyzed() >= 20_000, "the sample floor must still apply: " + a1);
  }

  @Test
  void deterministicArrivalsGiveScvZeroAtTheFirstStationAndPositiveAtTheSecond() {
    SimulationResponse res = simulate(new SimulationRequest(
        tandem(det(2.0)), 20260923L, stopping(), List.of("interarrival-time"), null));

    MeasureResult a1 = find(res, "q1", "interarrival-time");
    MeasureResult a2 = find(res, "q2", "interarrival-time");

    assertEquals(2.0, a1.mean(), 0.05, "deterministic arrivals every 2.0 time units");
    assertEquals(0.0, scv(a1), 1e-6, "a deterministic stream has zero interarrival variance");

    assertEquals(2.0, a2.mean(), 0.15, "throughput is conserved");
    assertTrue(scv(a2) > 0.1,
        "an exponential-service queue must reshape a deterministic stream, but q2 measured SCV "
            + scv(a2) + " — a measure reporting a constant would pass the q1 assertion above");
  }

  /**
   * Per class, like every other station measure, and not an aggregate: two classes with different
   * arrival rates must come back as two measures with their own means. Poisson superposition means
   * each stream is still Poisson, so both SCVs are ~ 1.
   */
  @Test
  void interarrivalTimeIsReportedPerClass() {
    NetworkModel m = new NetworkModel("twoclass",
        List.of(new JobClass("fast", "open", null, null), new JobClass("slow", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("fast", new ArrivalSpec(exp(0.3)), "slow", new ArrivalSpec(exp(0.2)))),
                new QueueNode("q1", "queue", 1, "fcfs", null,
                    Map.of("fast", new ServiceSpec(exp(2.0)), "slow", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("fast", List.of(new RoutingEdge("src", "q1", null), new RoutingEdge("q1", "snk", null)),
               "slow", List.of(new RoutingEdge("src", "q1", null), new RoutingEdge("q1", "snk", null))));

    SimulationResponse res = simulate(
        new SimulationRequest(m, 20260923L, stopping(), List.of("interarrival-time"), null));

    MeasureResult fast = res.measures().stream()
        .filter(x -> "fast".equals(x.jobClass())).findFirst().orElseThrow();
    MeasureResult slow = res.measures().stream()
        .filter(x -> "slow".equals(x.jobClass())).findFirst().orElseThrow();

    assertEquals(2, res.measures().size(), "one measure per class, no aggregate row");
    assertEquals(1.0 / 0.3, fast.mean(), 0.3);
    assertEquals(1.0 / 0.2, slow.mean(), 0.4);
    assertEquals(1.0, scv(fast), 0.2);
    assertEquals(1.0, scv(slow), 0.2);
  }

  /**
   * Review Focus 1, end to end: even with secondMoments on, a rate-typed measure reports no second
   * moments, while an ordinary one does. Same run, so this is about the measure and not the flag.
   */
  @Test
  void throughputReportsNoVarianceEvenWithSecondMomentsOn() {
    SimulationResponse res = simulate(new SimulationRequest(
        tandem(exp(0.5)), 20260923L, stopping(), List.of("throughput", "response-time"), true));

    MeasureResult tp = find(res, "q1", "throughput");
    assertEquals(0.5, tp.mean(), 0.05, "throughput is still reported as a rate");
    assertNull(tp.variance(), "its samples are interdeparture times, not rates");
    assertNull(tp.stdDev());

    MeasureResult rt = find(res, "q1", "response-time");
    assertNotNull(rt.variance(), "an ordinary measure's moments do arrive with secondMoments on");
    assertEquals(Math.sqrt(rt.variance()), rt.stdDev(), 1e-9);
  }
}
