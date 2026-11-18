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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.SimulationService;
import qsim.model.*;

class GoldenAnalyticTest {

  private final SimulationService service = new SimulationService(Config.defaults());

  private Distribution named(String type, double rate) {
    return new Distribution(type, rate, null, null, null);
  }
  private Distribution det(double value) {
    return new Distribution("deterministic", null, value, null, null);
  }
  private Distribution moment(double mean, double scv) {
    return new Distribution(null, null, null, mean, scv);
  }

  /** Single open M/G/1 queue: source -> q -> sink. lambda arrivals, given service dist. */
  private SimulationRequest mg1(double lambda, Distribution service) {
    NetworkModel m = new NetworkModel("mg1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new QueueNode("q", "queue", 1, "fcfs", null,
                    Map.of("web", new ServiceSpec(service))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 50_000, 2_000_000, null, null, 120, false);
    return new SimulationRequest(m, 4242L, s, List.of("utilization", "throughput", "response-time"));
  }

  private MeasureResult pick(SimulationResponse r, String type) {
    return r.measures().stream()
        .filter(m -> m.type().equals(type) && m.station().equals("q"))
        .findFirst().orElseThrow(() -> new AssertionError("missing measure " + type));
  }

  private void assertBrackets(MeasureResult m, double expected) {
    assertTrue(m.lower() <= expected && expected <= m.upper(),
        m.type() + " CI [" + m.lower() + ", " + m.upper() + "] must bracket " + expected
            + " (mean=" + m.mean() + ", success=" + m.success() + ")");
  }

  private double pkResponseTime(double lambda, double mean, double scv) {
    double rho = lambda * mean;
    return mean + (lambda * mean * mean * (1 + scv)) / (2 * (1 - rho));
  }

  @Test
  void mm1BracketsClosedForms() {
    double lambda = 1.0, mu = 2.0, mean = 1 / mu, rho = lambda * mean;
    SimulationResponse r = service.simulate(mg1(lambda, named("exponential", mu)));
    assertBrackets(pick(r, "utilization"), rho);                 // 0.5
    assertBrackets(pick(r, "throughput"), lambda);               // 1.0
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, 1.0)); // 1.0
  }

  @Test
  void md1BracketsClosedForms() {
    double lambda = 1.0, mean = 0.5;
    SimulationResponse r = service.simulate(mg1(lambda, det(mean)));
    assertBrackets(pick(r, "utilization"), lambda * mean);       // 0.5
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, 0.0)); // 0.75
  }

  @Test
  void momentGammaBracketsPkFormula() {
    // mean*scv != 1 so that Gamma scale (0.5) and rate (2.0) differ — at mean=0.5, scv=2.0 they
    // coincide and this test cannot see a convention flip. See GammaConventionTest.
    double lambda = 1.0, mean = 0.25, scv = 2.0;
    SimulationResponse r = service.simulate(mg1(lambda, moment(mean, scv)));
    assertBrackets(pick(r, "utilization"), lambda * mean);       // 0.25 (mean check)
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, scv)); // 0.375 (mean + SCV)
  }

  @Test
  void identicalSeedIsDeterministicThroughTheService() {
    SimulationResponse a = service.simulate(mg1(1.0, named("exponential", 2.0)));
    SimulationResponse b = service.simulate(mg1(1.0, named("exponential", 2.0)));
    List<MeasureResult> ma = a.measures(), mb = b.measures();
    assertTrue(ma.size() == mb.size());
    for (int i = 0; i < ma.size(); i++) {
      assertTrue(ma.get(i).mean().equals(mb.get(i).mean()),
          "same seed must yield identical means for measure " + ma.get(i).type());
    }
  }

  /**
   * The measure a caller actually asks for: {@code response-time} on the fork-join node itself.
   * In a network whose only service is the fork-join, the fork-join response time IS the system
   * response time, so {@code system-response-time} is an exact oracle for it — and the rigorous
   * bound T_FJ >= max branch E[T] pins the direction of any error. Both assertions fail while a
   * fork-join {@code response-time} is taken as a station measure at the internal join station,
   * which reports only the per-sibling synchronization wait (issue #6).
   */
  @Test
  void forkJoinNodeResponseTimeIsTheForkToJoinSojourn() {
    double lambda = 1.0;
    NetworkModel m = new NetworkModel("fj-rt",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(named("exponential", 5.0)))),
                            new Branch(Map.of("web", new ServiceSpec(named("exponential", 10.0))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 50_000, 2_000_000, null, null, 120, false);
    SimulationResponse r = service.simulate(new SimulationRequest(m, 20260729L, s,
        List.of("response-time", "system-response-time")));

    MeasureResult fj = r.measures().stream()
        .filter(x -> x.type().equals("response-time") && x.station().equals("fj"))
        .findFirst().orElseThrow(() -> new AssertionError("no response-time for station fj"));
    MeasureResult sys = r.measures().stream()
        .filter(x -> x.type().equals("system-response-time"))
        .findFirst().orElseThrow();

    // The fork-join is the whole network: the two must be the same measurement. Equality is exact,
    // not approximate, and deliberately asserted that way: the two accumulators (the fork station's
    // fork-join job list vs the global job list) observe the same per-job sojourns under the same
    // alpha/precision, so they converge on the same batch and the means agree bit-for-bit. Do not
    // loosen this tolerance to paper over a divergence — a divergence means one of them stopped
    // early or is measuring something else.
    assertTrue(Math.abs(fj.mean() - sys.mean()) <= 1e-9,
        "fork-join response-time=" + fj.mean() + " must equal system-response-time=" + sys.mean());
    // T_FJ >= E[T] of the slower branch: M/M/1 at lambda=1, mu=5 => 1/(5-1) = 0.25.
    double slowestBranchET = 1.0 / (5.0 - lambda);
    assertTrue(fj.mean() >= slowestBranchET,
        "fork-join response-time=" + fj.mean() + " must be >= slowest-branch E[T]=" + slowestBranchET);
  }

  @Test
  void forkJoinResponseTimeRespectsLowerBound() {
    // 2-branch homogeneous fork-join; T_FJ >= single-branch E[T] (rigorous lower bound).
    double lambda = 0.5, mu = 2.0, mean = 1 / mu;
    NetworkModel m = new NetworkModel("fj",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(named("exponential", mu)))),
                            new Branch(Map.of("web", new ServiceSpec(named("exponential", mu))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
    Stopping s = new Stopping(0.05, 0.05, 50_000, 2_000_000, null, null, 120, false);
    SimulationResponse r = service.simulate(new SimulationRequest(m, 4242L, s,
        List.of("system-response-time")));
    MeasureResult sys = r.measures().stream()
        .filter(x -> x.type().equals("system-response-time"))
        .findFirst().orElseThrow();
    double singleBranchET = 1 / (mu - lambda); // 1/(2-0.5) = 0.667
    assertTrue(sys.mean() >= singleBranchET * 0.95,
        "fork-join E[T]=" + sys.mean() + " must be >= slowest-branch E[T]=" + singleBranchET);
  }

  /**
   * Issue #18: two classes leave {@code src} by different single edges (a -> qa, b -> qb). JSIMG
   * connections are class-agnostic, so a RandomStrategy router at src would split each class 50/50
   * across qa and qb: both queues would see the mixed rate 0.75, and every number below would be
   * wrong with no error. With correct routing each queue is an independent M/M/1 for its own class.
   */
  @Test
  void multiClassDisjointRoutesKeepEachClassOnItsOwnStream() {
    double la = 1.0, lb = 0.5, mua = 2.0, mub = 4.0;
    Map<String, ServiceSpec> qaService = Map.of(
        "a", new ServiceSpec(named("exponential", mua)), "b", new ServiceSpec(named("exponential", mua)));
    Map<String, ServiceSpec> qbService = Map.of(
        "a", new ServiceSpec(named("exponential", mub)), "b", new ServiceSpec(named("exponential", mub)));
    NetworkModel m = new NetworkModel("disjoint",
        List.of(new JobClass("a", "open", null, null), new JobClass("b", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of(
                    "a", new ArrivalSpec(named("exponential", la)),
                    "b", new ArrivalSpec(named("exponential", lb)))),
                new QueueNode("qa", "queue", 1, "fcfs", null, qaService),
                new QueueNode("qb", "queue", 1, "fcfs", null, qbService),
                new SinkNode("snk", "sink")),
        Map.of("a", List.of(new RoutingEdge("src", "qa", null), new RoutingEdge("qa", "snk", null)),
               "b", List.of(new RoutingEdge("src", "qb", null), new RoutingEdge("qb", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 50_000, 2_000_000, null, null, 120, false);
    SimulationResponse r = service.simulate(new SimulationRequest(m, 20261118L, s,
        List.of("throughput", "response-time", "system-response-time")));

    for (String[] stray : new String[][] {{"qa", "b"}, {"qb", "a"}}) {
      MeasureResult x = pick(r, stray[0], stray[1], "throughput");
      assertTrue(x.mean() == null || x.mean() == 0.0,
          "class " + stray[1] + " is never routed to " + stray[0] + " but has throughput " + x.mean());
    }
    assertBrackets(pick(r, "qa", "a", "throughput"), la);
    assertBrackets(pick(r, "qb", "b", "throughput"), lb);
    assertBrackets(pick(r, "qa", "a", "response-time"), 1 / (mua - la));         // 1.0
    assertBrackets(pick(r, "qb", "b", "response-time"), 1 / (mub - lb));         // 0.2857
    assertBrackets(pick(r, null, "a", "system-response-time"), 1 / (mua - la));
    assertBrackets(pick(r, null, "b", "system-response-time"), 1 / (mub - lb));
  }

  /** A measure by station (null = system-level, any station) and class. */
  private MeasureResult pick(SimulationResponse r, String station, String clazz, String type) {
    return r.measures().stream()
        .filter(m -> m.type().equals(type) && clazz.equals(m.jobClass())
            && (station == null || station.equals(m.station())))
        .findFirst().orElseThrow(() -> new AssertionError(
            "missing measure " + type + " for class " + clazz + " at " + station));
  }
}
