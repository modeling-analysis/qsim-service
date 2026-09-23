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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import qsim.contract.ContractValidator;
import qsim.contract.ValidationException;
import qsim.distribution.DistributionResolver;
import qsim.engine.JmtRunner;
import qsim.engine.RunResult;
import qsim.model.*;
import qsim.result.SolutionsParser;
import qsim.translate.JsimgWriter;
import qsim.translate.MeasureMapper;
import qsim.translate.MeasureSpec;

public class SimulationService {

  private final Config config;
  private final JmtRunner runner;
  private final ContractValidator validator = new ContractValidator();
  private final DistributionResolver distributionResolver = new DistributionResolver();
  private final MeasureMapper measureMapper = new MeasureMapper();
  private final JsimgWriter writer = new JsimgWriter();
  private final SolutionsParser parser = new SolutionsParser();

  public SimulationService(Config config, JmtRunner runner) {
    this.config = config;
    this.runner = runner;
  }

  public SimulationService(Config config) {
    this(config, new JmtRunner());
  }

  public SimulationResponse simulate(SimulationRequest req) {
    validator.validate(req);
    validateDistributions(req.model());

    Stopping stopping = effectiveStopping(req.stopping());
    validateStopping(req.stopping(), stopping);
    long seed = effectiveSeed(req.seed());
    List<MeasureSpec> measures = measureMapper.map(req.model(), req.measures());

    // JMT's verbose output is per measure, so the request-level flag is applied by flipping the
    // specs. interarrival-time already carries the flag from the mapper — its figures do not exist
    // without the sample log — so an ordinary request asking only for it still gets a log directory.
    if (Boolean.TRUE.equals(req.secondMoments())) {
      measures = MeasureMapper.withVerbose(measures);
    }
    boolean anyVerbose = measures.stream().anyMatch(MeasureSpec::verbose);

    // Everything from the directory's creation onwards sits inside the try: translation itself
    // raises caller-reachable 422s (an unsupported join policy, inconsistent fork-join branch
    // classes, any XSD failure), and a 422 must not leave a directory behind either.
    Path logDir = null;
    RunResult run = null;
    try {
      if (anyVerbose) {
        try {
          logDir = JmtRunner.createLogDir(config.tempDir());
        } catch (IOException e) {
          throw new IllegalStateException(
              "cannot create a measure log directory under " + config.tempDir()
                  + "; second moments need somewhere to write per-sample logs", e);
        }
      }

      var doc = writer.toDocument(req.model(), stopping, seed, measures,
          logDir == null ? null : logDir.toString());
      writer.validate(doc); // XSD gate -> ValidationException(UNPROCESSABLE) on failure
      String xml = qsim.translate.Xml.serialize(doc);

      run = runner.run(xml, seed, stopping.maxWallClockSeconds(), /* terminal */ true);
      SolutionsParser.Parsed parsed = parser.parse(run.outputFile());
      return new SimulationResponse(req.model().name(), "simulation", seed,
          run.wallClockSeconds(), parsed.completed(), parsed.measures());
    } finally {
      if (run != null) {
        runner.cleanup(run);
      }
      // The engine can throw, or be cut off by the wall-clock cap, and JMT never removes these
      // files — so this cannot sit on the success path only.
      JmtRunner.deleteLogDir(logDir);
    }
  }

  Stopping effectiveStopping(Stopping s) {
    if (s == null) {
      return new Stopping(config.defaultAlpha(), config.defaultPrecision(),
          config.defaultMinSamples(), config.defaultMaxSamples(),
          null, null, config.defaultMaxWallClockSeconds(), false);
    }
    return new Stopping(
        s.alpha() != null ? s.alpha() : config.defaultAlpha(),
        s.precision() != null ? s.precision() : config.defaultPrecision(),
        s.minSamples() != null ? s.minSamples() : config.defaultMinSamples(),
        s.maxSamples() != null ? s.maxSamples() : config.defaultMaxSamples(),
        s.maxSimulatedTime(),
        s.maxEvents(),
        s.maxWallClockSeconds() != null ? s.maxWallClockSeconds() : config.defaultMaxWallClockSeconds(),
        s.disableStatisticStop() != null ? s.disableStatisticStop() : false);
  }

  /**
   * Rejects sample bounds the engine cannot satisfy. Checks the <em>effective</em> stopping rule,
   * after {@link #effectiveStopping} has filled the gaps, because the contradiction is usually
   * between a caller's floor and a <em>defaulted</em> ceiling — a request naming only
   * {@code minSamples: 5_000_000} inherits {@code maxSamples: 1_000_000} and is unsatisfiable
   * without ever mentioning a ceiling. {@code requested} is taken alongside it purely to tell
   * whether the ceiling was the caller's or ours: provenance cannot be recovered from the effective
   * value, since a caller may send a ceiling that happens to equal the default, and telling them it
   * came from an env var they never set sends them looking in the wrong place.
   *
   * <p>Worth rejecting rather than clamping. Inside JMT the ceiling wins: {@code addSample}
   * terminates unconditionally at {@code nSamples >= maxData}, while the floor only gates the
   * confidence-interval stop rule ({@code canEnd()} is {@code nSamples >= minData}). So a floor
   * above the ceiling yields a run that stops short of what was asked for and reports
   * {@code completed: false} with nothing to say why — the same silent-shortfall shape as issue #10,
   * which is what emitting {@code minSamples} at all was meant to end.
   */
  void validateStopping(Stopping requested, Stopping effective) {
    List<String> errors = new ArrayList<>();
    Integer min = effective.minSamples();
    Integer max = effective.maxSamples();
    if (min != null && min < 0) {
      errors.add("stopping.minSamples must be >= 0 (0 means no floor), but was " + min);
    }
    if (max != null && max < 1) {
      errors.add("stopping.maxSamples must be >= 1, but was " + max
          + "; a ceiling below one sample ends the run before any measure collects data");
    }
    // Skipped when the ceiling is itself invalid: "raise maxSamples or lower minSamples" is useless
    // advice against a ceiling of 0, and one clear error beats two overlapping ones.
    if (min != null && max != null && max >= 1 && min > max) {
      boolean ceilingIsOurs = requested == null || requested.maxSamples() == null;
      errors.add("stopping.minSamples (" + min + ") must not exceed stopping.maxSamples (" + max
          + "); the ceiling wins inside the engine, so the run would stop short of the floor. "
          + "Raise maxSamples or lower minSamples"
          + (ceilingIsOurs
              ? " (maxSamples is the QSIM_DEFAULT_MAX_SAMPLES default, not a value you sent)" : ""));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(ValidationException.Kind.BAD_REQUEST, errors);
    }
  }

  long effectiveSeed(Long requested) {
    return requested != null ? requested : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }

  /**
   * Pre-run semantic check: resolves every distribution the model carries (source arrivals,
   * queue/delay/fork-join-branch service) through the real {@link DistributionResolver}, and
   * confirms every node that needs a service/arrivals map actually has one. This runs after
   * {@link ContractValidator#validate} and before translation, so an unsupported distribution
   * type, a negative/missing rate, {@code scv < 0}, or a missing map surfaces as a 422
   * UNPROCESSABLE ("semantic model error caught pre-run", design spec §7) instead of an
   * uncaught IllegalArgumentException/NullPointerException from deep inside
   * DistributionResolver/MeasureMapper/JsimgWriter mapping to a 500.
   */
  private void validateDistributions(NetworkModel model) {
    List<String> details = new ArrayList<>();
    for (Node n : model.nodes()) {
      if (n instanceof SourceNode s) {
        if (s.arrivals() == null) {
          details.add("node '" + s.name() + "': arrivals map is required");
        } else {
          for (Map.Entry<String, ArrivalSpec> e : s.arrivals().entrySet()) {
            checkDistribution(details, s.name(), e.getKey(),
                e.getValue() == null ? null : e.getValue().distribution());
          }
        }
      } else if (n instanceof QueueNode q) {
        checkServiceMap(details, q.name(), q.service());
      } else if (n instanceof DelayNode d) {
        checkServiceMap(details, d.name(), d.service());
      } else if (n instanceof ForkJoinNode f) {
        List<Branch> branches = f.branches() == null ? List.of() : f.branches();
        for (int i = 0; i < branches.size(); i++) {
          Branch b = branches.get(i);
          String branchLabel = f.name() + ".branches[" + i + "]";
          checkServiceMap(details, branchLabel, b == null ? null : b.service());
        }
      }
    }
    if (!details.isEmpty()) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE, details);
    }
  }

  private void checkServiceMap(List<String> details, String nodeName, Map<String, ServiceSpec> service) {
    if (service == null) {
      details.add("node '" + nodeName + "': service map is required");
      return;
    }
    for (Map.Entry<String, ServiceSpec> e : service.entrySet()) {
      checkDistribution(details, nodeName, e.getKey(),
          e.getValue() == null ? null : e.getValue().distribution());
    }
  }

  private void checkDistribution(List<String> details, String nodeName, String className, Distribution dist) {
    try {
      distributionResolver.resolve(dist);
    } catch (IllegalArgumentException | NullPointerException e) {
      String message = e.getMessage() != null ? e.getMessage() : "invalid distribution";
      details.add("node '" + nodeName + "' class '" + className + "': " + message);
    }
  }
}
