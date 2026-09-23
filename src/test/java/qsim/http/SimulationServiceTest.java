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
package qsim.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import qsim.contract.ValidationException;
import qsim.engine.JmtRunner;
import qsim.engine.RunResult;
import qsim.model.*;

class SimulationServiceTest {

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  /** M/M/1, lambda=1, mu=2 -> rho = U = 0.5. */
  private SimulationRequest mm1() {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 10000, 500_000, null, null, 60, false);
    return new SimulationRequest(m, 12345L, s, List.of("utilization", "response-time"));
  }

  @Test
  void runsEndToEndAndEchoesRequestMetadata() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse resp = service.simulate(mm1());

    assertEquals("mm1", resp.modelName());
    assertEquals("simulation", resp.solutionMethod());
    assertEquals(12345L, resp.seed());
    assertTrue(resp.wallClockSeconds() >= 0.0);
    assertFalse(resp.measures().isEmpty());
    // every measure references a domain node name (never an internal fj__ name)
    assertTrue(resp.measures().stream().allMatch(mr -> !mr.station().contains("__")));
  }

  @Test
  void utilizationBracketsHalf() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse resp = service.simulate(mm1());
    MeasureResult u = resp.measures().stream()
        .filter(mr -> mr.type().equals("utilization") && mr.station().equals("q"))
        .findFirst().orElseThrow();
    // U = rho = 0.5; the CI must bracket it (sanity gate; full golden checks in Task 13)
    assertTrue(u.lower() <= 0.5 && 0.5 <= u.upper(),
        "U CI [" + u.lower() + "," + u.upper() + "] must bracket 0.5");
  }

  @Test
  void missingSeedIsGeneratedAndEchoed() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationRequest noSeed = new SimulationRequest(mm1().model(), null, mm1().stopping(),
        List.of("utilization"));
    SimulationResponse resp = service.simulate(noSeed);
    assertTrue(resp.seed() != null, "service must generate and echo a seed when none is supplied");
  }

  // ---- sample-bound guard --------------------------------------------------
  //
  // Now that minSamples actually reaches the engine (issue #10), a floor above the ceiling is a
  // request the engine cannot satisfy: addSample terminates at maxData while the floor only gates
  // the CI stop rule, so the run would end short and report completed:false with no explanation.
  // Rejecting is the point — clamping the floor down to the ceiling would silently deliver less
  // than was asked for, which is the shape of #10 itself.

  /** A stopping rule that varies only the two sample bounds. */
  private Stopping bounds(Integer minSamples, Integer maxSamples) {
    return new Stopping(0.05, 0.03, minSamples, maxSamples, null, null, 60, false);
  }

  /** Both bounds sent explicitly, so requested and effective coincide. */
  private ValidationException rejected(Stopping s) {
    SimulationService service = new SimulationService(Config.defaults());
    return assertThrows(ValidationException.class, () -> service.validateStopping(s, s));
  }

  @Test
  void rejectsAFloorAboveTheCeiling() {
    ValidationException e = rejected(bounds(500_000, 100_000));
    assertEquals(ValidationException.Kind.BAD_REQUEST, e.kind(), "a bad bound is a 400, not a 422");
    assertTrue(e.getMessage().contains("500000") && e.getMessage().contains("100000"),
        "the message must name both bounds so the caller can see the contradiction: " + e.getMessage());
  }

  @Test
  void rejectsANegativeFloor() {
    assertEquals(ValidationException.Kind.BAD_REQUEST, rejected(bounds(-1, 100_000)).kind());
  }

  /**
   * A ceiling below one sample is not the silent-shortfall class #10 was about — the engine ends at
   * its first sample and honestly reports {@code completed: false} — but the run is guaranteed
   * useless, so there is no reason to spend an engine start on it.
   */
  @Test
  void rejectsACeilingBelowOneSample() {
    assertEquals(ValidationException.Kind.BAD_REQUEST, rejected(bounds(0, 0)).kind());
  }

  /**
   * With a nonsense ceiling, the floor-versus-ceiling comparison would add "raise maxSamples or
   * lower minSamples" — advice that cannot be followed against a ceiling of 0. One clear error.
   */
  @Test
  void reportsOnlyTheCeilingWhenTheCeilingIsItselfInvalid() {
    ValidationException e = rejected(bounds(500_000, 0));
    assertEquals(1, e.details().size(), "expected a single error, got: " + e.details());
    assertTrue(e.getMessage().contains("maxSamples must be >= 1"), e.getMessage());
  }

  /** A floor equal to the ceiling is satisfiable — the engine can reach it exactly. */
  @Test
  void acceptsAFloorEqualToTheCeiling() {
    SimulationService service = new SimulationService(Config.defaults());
    Stopping s = bounds(100_000, 100_000);
    assertDoesNotThrow(() -> service.validateStopping(s, s));
  }

  /** Zero is the documented way to ask for no floor at all. */
  @Test
  void acceptsAZeroFloor() {
    SimulationService service = new SimulationService(Config.defaults());
    Stopping s = bounds(0, 100_000);
    assertDoesNotThrow(() -> service.validateStopping(s, s));
  }

  /**
   * The common shape: a caller raises the floor and never mentions a ceiling, so the contradiction
   * is against the *default* maxSamples. The check therefore has to run on the effective stopping
   * rule rather than the raw request, and the message has to say where the ceiling came from.
   */
  @Test
  void rejectsAFloorThatOnlyContradictsTheDefaultedCeiling() {
    SimulationService service = new SimulationService(Config.defaults());
    Stopping requested = bounds(Config.defaults().defaultMaxSamples() + 1, null);
    ValidationException e = assertThrows(ValidationException.class,
        () -> service.validateStopping(requested, service.effectiveStopping(requested)));
    assertTrue(e.getMessage().contains("QSIM_DEFAULT_MAX_SAMPLES"),
        "the caller never sent a ceiling, so the message must attribute it to the default: "
            + e.getMessage());
  }

  /**
   * The mirror of the case above, and the one that keeps the attribution honest: a caller who sends
   * a ceiling that happens to equal the default must not be told the ceiling was not theirs.
   * 1,000,000 is the value the design spec's own example uses, so this is the likely collision
   * rather than an exotic one — and pointing such a caller at an env var they never set sends them
   * looking in the wrong place.
   */
  @Test
  void doesNotBlameTheDefaultForACeilingTheCallerSent() {
    SimulationService service = new SimulationService(Config.defaults());
    int explicitCeiling = Config.defaults().defaultMaxSamples();
    Stopping requested = bounds(explicitCeiling + 1, explicitCeiling);
    ValidationException e = assertThrows(ValidationException.class,
        () -> service.validateStopping(requested, service.effectiveStopping(requested)));
    assertFalse(e.getMessage().contains("QSIM_DEFAULT_MAX_SAMPLES"),
        "the caller sent maxSamples=" + explicitCeiling + " explicitly, so the message must not "
            + "attribute it to the default: " + e.getMessage());
  }

  /** The guard must fire before the engine is started, not after a wasted run. */
  @Test
  void rejectsBeforeInvokingTheEngine() {
    SimulationService service = new SimulationService(Config.defaults(), new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        throw new AssertionError("bad sample bounds must be rejected before the engine is invoked");
      }
    });
    SimulationRequest req = new SimulationRequest(mm1().model(), 42L, bounds(500_000, 100_000),
        List.of("utilization"));
    assertEquals(ValidationException.Kind.BAD_REQUEST,
        assertThrows(ValidationException.class, () -> service.simulate(req)).kind());
  }

  // ---- per-sample logging (issues #14, #15) --------------------------------

  /** The default measures, or interarrival-time, on the existing mm1 model. */
  private SimulationRequest requestWith(Boolean secondMoments) {
    return new SimulationRequest(mm1().model(), 42L, null, List.of("response-time"), secondMoments);
  }

  /** Loose bounds: the zero-sample test is about surviving JMT's log, not about convergence. */
  private Stopping looseStopping() {
    return new Stopping(0.05, 0.10, 1_000, 50_000, null, null, 60, false);
  }

  private String logPathOf(String xml) {
    Matcher m = Pattern.compile("logPath=\"([^\"]*)\"").matcher(xml);
    return m.find() ? m.group(1) : null;
  }

  /**
   * A real solutions document in a throwaway file. Copied rather than handed over directly, because
   * {@code JmtRunner.cleanup} deletes the output file and a test resource must survive the run.
   */
  private RunResult cannedRunResult() {
    try {
      Path out = Files.createTempFile("qsim-canned-", ".xml");
      try (var in = getClass().getResourceAsStream("/results/mm1.solutions.xml")) {
        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return new RunResult(out.toFile(), 0.1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Review Focus 3 and 5. JMT never deletes the per-sample CSVs it writes, so the service owns the
   * directory: a fresh one per run (measure names become filenames, so two runs of one model would
   * collide) and gone afterwards whether the run succeeded or blew up.
   */
  @Test
  void eachRunGetsItsOwnLogDirectoryAndItIsRemovedAfterwards() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);
    List<String> logPathsSeen = new ArrayList<>();

    // A runner stub that records the logPath it was handed and returns a canned output document.
    // Subclassing JmtRunner is how SimulationServiceTest already stubs the engine (see
    // rejectsBeforeInvokingTheEngine) — note the parameter is Integer, not int, or this does not
    // override anything and the real engine runs.
    JmtRunner runner = new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        logPathsSeen.add(logPathOf(xml));
        return cannedRunResult();
      }
    };
    SimulationService service = new SimulationService(config, runner);

    service.simulate(requestWith(/* secondMoments */ true));
    service.simulate(requestWith(/* secondMoments */ true));

    assertEquals(2, logPathsSeen.size());
    assertNotNull(logPathsSeen.get(0), "a verbose run must be given a log directory");
    assertNotEquals(logPathsSeen.get(0), logPathsSeen.get(1),
        "measure names become filenames, so two runs must not share a directory");
    for (String p : logPathsSeen) {
      assertFalse(Files.exists(Path.of(p)), "the log directory must be removed after the run: " + p);
    }
  }

  @Test
  void theLogDirectoryIsRemovedWhenTheEngineFails() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);

    JmtRunner runner = new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        throw new IllegalStateException("engine exploded");
      }
    };
    SimulationService service = new SimulationService(config, runner);

    assertThrows(IllegalStateException.class, () -> service.simulate(requestWith(true)));
    try (var entries = Files.list(temp)) {
      assertEquals(List.of(), entries.toList(), "no log directory may be left behind on failure");
    }
  }

  /**
   * Review Focus 3, for the half {@link #theLogDirectoryIsRemovedWhenTheEngineFails} cannot see. That
   * test stubs the engine, so it only exercises failures that happen after the run starts — but the
   * directory is created before translation, and translation raises ordinary caller-reachable 422s
   * that {@link qsim.contract.ContractValidator} does not catch first ({@code JsimgWriter} rejects an
   * unsupported join policy, inconsistent fork-join branch classes, and any XSD failure). A client
   * looping on such a model would add one orphaned directory per request, and JMT never cleans them
   * up.
   */
  @Test
  void theLogDirectoryIsRemovedWhenTranslationRejectsTheModel() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);

    // Valid to the contract layer and to validateDistributions, rejected by the writer: v1 supports
    // only join "all" or a count.
    NetworkModel badJoin = new NetworkModel("bad-join",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new ForkJoinNode("fj", "fork-join", List.of(
                    new Branch(Map.of("web", new ServiceSpec(exp(4.0)))),
                    new Branch(Map.of("web", new ServiceSpec(exp(4.0))))), "bogus"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));

    SimulationService service = new SimulationService(config, new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        throw new AssertionError("translation must fail before the engine is invoked");
      }
    });

    assertThrows(ValidationException.class, () -> service.simulate(new SimulationRequest(
        badJoin, 42L, looseStopping(), List.of("interarrival-time"), null)));

    try (var entries = Files.list(temp)) {
      assertEquals(List.of(), entries.toList(),
          "a 422 from the writer must not leak a log directory");
    }
  }

  @Test
  void aRequestWithoutSecondMomentsGetsNoLogDirectory() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);
    List<String> logPathsSeen = new ArrayList<>();
    JmtRunner runner = new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        logPathsSeen.add(logPathOf(xml));
        return cannedRunResult();
      }
    };
    SimulationService service = new SimulationService(config, runner);

    // Default measures only: no verbose measure, so no directory and no disk cost for callers who
    // never asked for second moments.
    service.simulate(requestWith(/* secondMoments */ null));

    assertNull(logPathsSeen.get(0), "an ordinary request must not create a log directory");
    try (var entries = Files.list(temp)) {
      assertEquals(List.of(), entries.toList());
    }
  }

  /**
   * A {@code secondMoments} request that also asks for a rate-typed measure runs end to end, and the
   * rate-typed measure still reports no second moments (Review Focus 1, through the whole service
   * rather than the parser alone). Note that drop rate is never made verbose — {@link
   * qsim.translate.MeasureMapper#RATE_TYPED} is excluded by {@code withVerbose} — so this does not
   * exercise the zero-sample log path; that is
   * {@link #aVerboseMeasureWithNoSamplesDoesNotFailTheRequest}.
   */
  @Test
  void aZeroSampleVerboseMeasureDoesNotFailTheRequest() throws Exception {
    // Real engine and the real temp dir: the SEVERE-log path is JMT's, so a stub proves nothing.
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse res = service.simulate(new SimulationRequest(
        mm1().model(), 7L, looseStopping(), List.of("drop-rate", "response-time"), true));

    MeasureResult drop = res.measures().stream()
        .filter(m -> "drop-rate".equals(m.type())).findFirst().orElseThrow();
    assertNull(drop.variance(), "no samples, and rate-typed besides");
    assertNotNull(res.measures().stream()
        .filter(m -> "response-time".equals(m.type())).findFirst().orElseThrow().mean(),
        "the other measures must be unaffected");
  }

  /**
   * Review Focus 2, for real. A verbose measure that collects no samples makes JMT log a
   * {@code FileNotFoundException} at SEVERE from {@code XMLSimulationOutput.writeMeasure} — it looks
   * for a per-sample CSV that was never written — and omit the statistics attributes. The run and
   * every other measure survive, so this must come back as a measure with nulls, not a 500.
   *
   * <p>The zero-sample measure here is the second class's interarrival time: {@code rare} arrives at
   * 1e-12/s, so over the ~50,000 simulated time units the {@code web} class's sample ceiling buys,
   * the expected number of its arrivals is 5e-8. {@code interarrival-time} is always verbose (it has
   * nothing to report without the sample log), which is what makes this reachable at all — a
   * rate-typed measure like drop rate never carries the verbose flag.
   */
  @Test
  void aVerboseMeasureWithNoSamplesDoesNotFailTheRequest() {
    NetworkModel twoClasses = new NetworkModel("mm1-plus-idle",
        List.of(new JobClass("web", "open", null, null), new JobClass("rare", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(exp(1.0)), "rare", new ArrivalSpec(exp(1e-12)))),
                new QueueNode("q", "queue", 1, "fcfs", null,
                    Map.of("web", new ServiceSpec(exp(2.0)), "rare", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null)),
               "rare", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));

    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse res = service.simulate(new SimulationRequest(
        twoClasses, 7L, looseStopping(), List.of("interarrival-time"), null));

    MeasureResult starved = res.measures().stream()
        .filter(m -> "rare".equals(m.jobClass())).findFirst()
        .orElseThrow(() -> new AssertionError("the starved measure must still be reported: "
            + res.measures()));
    assertNull(starved.variance(), "no samples, so no second moments to report");

    MeasureResult busy = res.measures().stream()
        .filter(m -> "web".equals(m.jobClass())).findFirst().orElseThrow();
    assertNotNull(busy.mean(), "the other measures must be unaffected: " + busy);
    assertNotNull(busy.variance(), "interarrival-time logs its samples, so variance is populated");
  }
}
