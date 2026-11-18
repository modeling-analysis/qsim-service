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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.SimulationService;
import qsim.model.*;

/**
 * Golden tests for finite capacity regions against exact queueing-theory answers (spec §8,
 * G1-G8), end to end through {@link SimulationService#simulate}. The exact CTMC numbers come
 * from docs/fcr/ctmc_cap2.py and the G6/G7 oracle from docs/fcr/qcsc.py.
 */
class RegionGoldenTest {

  private final SimulationService service = new SimulationService(Config.defaults());

  private static final Stopping STOP =
      new Stopping(0.05, 0.02, 50_000, 5_000_000, null, null, 300, false);

  private static Distribution exp(double rate) {
    return new Distribution("exponential", rate, null, null, null);
  }

  private static Map<String, ServiceSpec> svc(String cls, double rate) {
    return Map.of(cls, new ServiceSpec(exp(rate)));
  }

  /** src(lambda) -> fj(exp 1, exp 1) -> snk, with optional region around fj. */
  private NetworkModel fjModel(double lambda, Region region) {
    List<Region> regions = region == null ? null : List.of(region);
    return new NetworkModel("fj",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(lambda)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(svc("web", 1.0)), new Branch(svc("web", 1.0))), "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))),
        regions);
  }

  private static Region fjRegion(int cap, String whenFull) {
    return new Region("R", List.of("fj"), cap, null, whenFull);
  }

  private SimulationResponse run(NetworkModel m, long seed, List<String> measures) {
    return service.simulate(new SimulationRequest(m, seed, STOP, measures));
  }

  /** Station null means system level (blank station). */
  private MeasureResult pick(SimulationResponse r, String station, String clazz, String type) {
    return r.measures().stream()
        .filter(m -> m.type().equals(type)
            && (station == null ? (m.station() == null || m.station().isBlank())
                                : station.equals(m.station()))
            && (clazz == null || clazz.equals(m.jobClass())))
        .findFirst().orElseThrow(() -> new AssertionError(
            "missing measure " + station + "/" + clazz + "/" + type));
  }

  private void assertBrackets(MeasureResult m, double expected) {
    assertTrue(m.lower() <= expected && expected <= m.upper(),
        m.type() + " CI [" + m.lower() + ", " + m.upper() + "] must bracket " + expected
            + " (mean=" + m.mean() + ", success=" + m.success() + ")");
  }

  /**
   * G1 (spec §8): cap 1, wait. M/G/1 with S = max of two exp(1): E[S]=1.5, E[S^2]=3.5, so
   * Wq = 0.4*3.5/(2*0.4) = 1.75, T = 3.25; the fork-join alone takes 1.5.
   */
  @Test
  void g1CapOneWaitIsMG1() {
    SimulationResponse r = run(fjModel(0.4, fjRegion(1, "wait")), 1001L,
        List.of("system-response-time", "region-queue-time", "region-response-time",
            "response-time"));
    assertBrackets(pick(r, null, "web", "system-response-time"), 3.25);
    assertBrackets(pick(r, "R", "web", "region-queue-time"), 1.75);
    assertBrackets(pick(r, "R", "web", "region-response-time"), 3.25);
    assertBrackets(pick(r, "fj", "web", "response-time"), 1.5);
  }

  /**
   * G2 (spec §8): cap 1, drop. M/G/1/1 loss is insensitive to S: P_drop = rho/(1+rho) with
   * rho = 0.4*1.5 = 0.6, i.e. 0.375, so drop rate 0.15, X = 0.25, T = E[S] = 1.5.
   */
  @Test
  void g2CapOneDropIsErlangLoss() {
    SimulationResponse r = run(fjModel(0.4, fjRegion(1, "drop")), 1001L,
        List.of("region-drop-rate", "region-throughput", "system-response-time"));
    assertBrackets(pick(r, "R", "web", "region-drop-rate"), 0.15);
    assertBrackets(pick(r, "R", "web", "region-throughput"), 0.25);
    assertBrackets(pick(r, null, "web", "system-response-time"), 1.5);
  }

  /** G3 (spec §8): a region that never binds changes nothing; same seed gives equal means. */
  @Test
  void g3UnboundedCapMatchesNoRegion() {
    List<String> ms = List.of("system-response-time");
    SimulationResponse with = run(fjModel(0.4, fjRegion(1000, "wait")), 1001L, ms);
    SimulationResponse without = run(fjModel(0.4, null), 1001L, ms);
    MeasureResult a = pick(with, null, "web", "system-response-time");
    MeasureResult b = pick(without, null, "web", "system-response-time");
    assertTrue(Double.isFinite(a.mean()) && Double.isFinite(b.mean()));
    assertEquals(b.mean(), a.mean(), 0.0);
  }

  /**
   * An interior member's interarrival time is untouched by a region, and an entry member's row is
   * omitted (JMT counts each arrival there twice: once at the Queue, once after admission).
   */
  @Test
  void interarrivalTimeOmittedAtEntryAndUnchangedInside() {
    Map<String, ServiceSpec> s1 = svc("web", 1.0);
    java.util.function.Function<List<Region>, NetworkModel> build = regions -> new NetworkModel("chain",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new QueueNode("q1", "queue", 1, "fcfs", null, s1),
                new QueueNode("q2", "queue", 1, "fcfs", null, s1),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q1", null), new RoutingEdge("q1", "q2", null),
            new RoutingEdge("q2", "snk", null))),
        regions);
    List<String> ms = List.of("interarrival-time");
    SimulationResponse with = run(build.apply(List.of(new Region("R", List.of("q1", "q2"), 1000, null, null))),
        1001L, ms);
    SimulationResponse without = run(build.apply(null), 1001L, ms);
    assertTrue(with.measures().stream().noneMatch(m -> "q1".equals(m.station())),
        "no interarrival-time row at the region entry");
    double a = pick(with, "q2", "web", "interarrival-time").mean();
    double b = pick(without, "q2", "web", "interarrival-time").mean();
    assertTrue(Double.isFinite(a) && Double.isFinite(b));
    assertEquals(b, a, 0.0);
  }

  /**
   * G4 (spec §8, §8.1): cap 2, wait, lambda=0.4. Exact CTMC (docs/fcr/ctmc_cap2.py 0.4 wait 300 2):
   * T = 2.54545, time inside = 2.01399. Asserted across 5 seeds with a t-interval (t=2.776, df 4).
   */
  @Test
  void g4CapTwoWaitMatchesCtmc() {
    long[] seeds = {1001, 1002, 1003, 1004, 1005};
    double[] sys = new double[seeds.length];
    double[] fj = new double[seeds.length];
    for (int i = 0; i < seeds.length; i++) {
      SimulationResponse r = run(fjModel(0.4, fjRegion(2, "wait")), seeds[i],
          List.of("system-response-time", "response-time"));
      sys[i] = pick(r, null, "web", "system-response-time").mean();
      fj[i] = pick(r, "fj", "web", "response-time").mean();
    }
    assertTInterval(sys, 2.54545, "system-response-time");
    assertTInterval(fj, 2.01399, "fj response-time");
  }

  private static void assertTInterval(double[] x, double exact, String what) {
    int n = x.length;
    double mean = 0;
    for (double v : x) mean += v;
    mean /= n;
    double ss = 0;
    for (double v : x) ss += (v - mean) * (v - mean);
    double half = 2.776 * Math.sqrt(ss / (n - 1) / n);
    System.out.printf("G4 %s: per-seed means %s, cross-seed mean %.5f +- %.5f, exact %.5f%n",
        what, java.util.Arrays.toString(x), mean, half, exact);
    assertTrue(Math.abs(mean - exact) <= half,
        what + " cross-seed interval [" + (mean - half) + ", " + (mean + half)
            + "] must contain " + exact);
  }

  /**
   * G5 (spec §8, §8.1): cap 2, drop, lambda=0.4. Exact CTMC (ctmc_cap2.py 0.4 drop 0 2):
   * drop rate 0.06095, X = 0.33905, T = 1.85393.
   */
  @Test
  void g5CapTwoDropMatchesCtmc() {
    SimulationResponse r = run(fjModel(0.4, fjRegion(2, "drop")), 1001L,
        List.of("region-drop-rate", "region-throughput", "system-response-time"));
    assertBrackets(pick(r, "R", "web", "region-drop-rate"), 0.06095);
    assertBrackets(pick(r, "R", "web", "region-throughput"), 0.33905);
    assertBrackets(pick(r, null, "web", "system-response-time"), 1.85393);
  }

  // ---- G6 / G7: QCSC-shaped model (spec §8 topology; docs/fcr/qcsc.py is the oracle) ----

  private static final List<String> QCSC_MEMBERS = List.of(
      "cpu_init_ps", "cpu_term_ps", "cpu_init_sp", "cpu_term_sp",
      "qpu_psq", "qpu_psg", "qpu_ssq", "qpu_ssg",
      "gpu_psq", "gpu_psg", "gpu_ssq", "gpu_ssg", "fj_pp", "fj_sp");

  private static Map<String, ServiceSpec> both(double rate) {
    return Map.of("ps", new ServiceSpec(exp(rate)), "sp", new ServiceSpec(exp(rate)));
  }

  private static RoutingEdge e(String f, String t, Double p) {
    return new RoutingEdge(f, t, p);
  }

  private NetworkModel qcsc(String whenFull) {
    List<Node> nodes = new ArrayList<>();
    nodes.add(new SourceNode("src", "source", Map.of(
        "ps", new ArrivalSpec(exp(0.15)), "sp", new ArrivalSpec(exp(0.10)))));
    for (String n : List.of("cpu_init_ps", "cpu_term_ps", "cpu_init_sp", "cpu_term_sp")) {
      nodes.add(new QueueNode(n, "queue", 1, "fcfs", null, both(4.0)));
    }
    for (String n : List.of("qpu_psq", "qpu_psg", "qpu_ssq", "qpu_ssg")) {
      nodes.add(new QueueNode(n, "queue", 1, "fcfs", null, both(1.5)));
    }
    for (String n : List.of("gpu_psq", "gpu_psg", "gpu_ssq", "gpu_ssg")) {
      nodes.add(new QueueNode(n, "queue", 1, "fcfs", null, both(2.5)));
    }
    nodes.add(new ForkJoinNode("fj_pp", "fork-join",
        List.of(new Branch(both(1.0)), new Branch(both(2.0))), "all"));
    nodes.add(new ForkJoinNode("fj_sp", "fork-join",
        List.of(new Branch(both(1.2)), new Branch(both(3.0))), "all"));
    nodes.add(new SinkNode("snk", "sink"));
    Map<String, List<RoutingEdge>> routing = Map.of(
        "ps", List.of(e("src", "cpu_init_ps", null), e("cpu_init_ps", "fj_pp", null),
            e("fj_pp", "qpu_psq", 0.3), e("fj_pp", "gpu_psg", 0.7),
            e("qpu_psq", "gpu_psq", null), e("gpu_psq", "cpu_term_ps", null),
            e("gpu_psg", "qpu_psg", null), e("qpu_psg", "cpu_term_ps", null),
            e("cpu_term_ps", "snk", null)),
        "sp", List.of(e("src", "cpu_init_sp", null),
            e("cpu_init_sp", "qpu_ssq", 0.3), e("cpu_init_sp", "gpu_ssg", 0.7),
            e("qpu_ssq", "gpu_ssq", null), e("gpu_ssq", "fj_sp", null),
            e("gpu_ssg", "qpu_ssg", null), e("qpu_ssg", "fj_sp", null),
            e("fj_sp", "cpu_term_sp", null), e("cpu_term_sp", "snk", null)));
    return new NetworkModel("qcsc-shaped",
        List.of(new JobClass("ps", "open", null, null), new JobClass("sp", "open", null, null)),
        nodes, routing,
        List.of(new Region("R", QCSC_MEMBERS, 1, null, whenFull)));
  }

  /**
   * G6 (spec §8): QCSC-shaped, cap 1, wait. Multi-class M/G/1 FCFS: rho=0.65952, Wq=3.12312,
   * T_ps = 5.85646, T_sp = 5.61836; nothing ever queues at a member (docs/fcr/qcsc.py).
   */
  @Test
  void g6QcscShapedCapOneIsMultiClassMG1() {
    List<String> ms = List.of("system-response-time", "queue-time");
    SimulationResponse r = run(qcsc("wait"), 1001L, ms);
    assertBrackets(pick(r, null, "ps", "system-response-time"), 5.85646);
    assertBrackets(pick(r, null, "sp", "system-response-time"), 5.61836);
    int checked = 0;
    for (MeasureResult m : r.measures()) {
      if (m.type().equals("queue-time") && QCSC_MEMBERS.contains(m.station())) {
        assertEquals(0.0, m.mean(), 0.0, "queue-time at " + m.station() + "/" + m.jobClass());
        checked++;
      }
    }
    assertTrue(checked > 0, "no member queue-time rows found");
  }

  /**
   * G7 (spec §8): same, drop. P_drop = rho/(1+rho) = 0.39742 for both classes, so drop rates are
   * 0.15*0.39742 = 0.059613 (ps) and 0.10*0.39742 = 0.039742 (sp); admitted jobs see no wait, so
   * T_r = E[S_r] = 2.73333 / 2.49524.
   */
  @Test
  void g7QcscShapedCapOneDrop() {
    SimulationResponse r = run(qcsc("drop"), 1001L,
        List.of("region-drop-rate", "system-response-time"));
    assertBrackets(pick(r, "R", "ps", "region-drop-rate"), 0.059613);
    assertBrackets(pick(r, "R", "sp", "region-drop-rate"), 0.039742);
    assertBrackets(pick(r, null, "ps", "system-response-time"), 2.73333);
    assertBrackets(pick(r, null, "sp", "system-response-time"), 2.49524);
  }

  /**
   * G8 (spec §8): src(2) -> delay(exp 1) in a cap-3 drop region is a 3-server loss system.
   * Erlang B with a=2, K=3: B = (a^3/3!)/(1+a+a^2/2+a^3/6) = 1.3333/6.3333 = 0.210526, so the
   * drop rate is 2*0.210526 = 0.421053, throughput 1.578947, sojourn 1.
   */
  @Test
  void g8DelayInCapThreeDropIsErlangB() {
    NetworkModel m = new NetworkModel("delay",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(2.0)))),
                new DelayNode("d", "delay", svc("web", 1.0)),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(e("src", "d", null), e("d", "snk", null))),
        List.of(new Region("R", List.of("d"), 3, null, "drop")));
    SimulationResponse r = run(m, 1001L,
        List.of("region-drop-rate", "region-throughput", "system-response-time"));
    assertBrackets(pick(r, "R", "web", "region-drop-rate"), 0.421053);
    assertBrackets(pick(r, "R", "web", "region-throughput"), 1.578947);
    assertBrackets(pick(r, null, "web", "system-response-time"), 1.0);
  }

  /** Review Focus 1: region measures coexist with per-sample second-moment logging. */
  @Test
  void regionMeasuresSurviveSecondMoments() {
    SimulationResponse r = service.simulate(new SimulationRequest(
        fjModel(0.4, fjRegion(1, "wait")), 1001L, STOP,
        List.of("region-response-time", "region-queue-time", "region-queue-length",
            "region-throughput", "region-drop-rate"), true));
    assertNotNull(pick(r, "R", "web", "region-response-time").variance());
    assertNull(pick(r, "R", "web", "region-throughput").variance());
  }
}
