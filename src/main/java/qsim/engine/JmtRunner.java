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
package qsim.engine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import simDispatcher.DispatcherJSIMschema;

/**
 * The ONLY class in the service that directly imports JMT engine classes — the licensing/quarantine boundary.
 *
 * <p>Each {@link #run} call constructs a fresh {@link DispatcherJSIMschema}, giving each
 * simulation clean engine state (spec §4 residual note); a subprocess fallback is deferred unless
 * a state leak between runs is later observed.
 */
public class JmtRunner {

  public RunResult run(String jsimgXml, long seed, Integer maxWallClockSeconds, boolean terminal) {
    File model = null;
    try {
      model = File.createTempFile("qsim-model-", ".xml");
      Files.writeString(model.toPath(), jsimgXml, StandardCharsets.UTF_8);

      // KNOWN LEAK (not fixed): DispatcherJSIMschema.solveModel() creates a single-thread executor
      // for the max-duration watchdog after Simulation.initialize(), and calls shutdownNow() only on
      // its success path (it has no exception handling). So a failure thrown from Simulation.run()
      // leaves one non-daemon "pool-N-thread-1" per failed run: it sleeps up to maxWallClockSeconds,
      // aborts the measures, then idles until the JVM exits. Load- and initialize-time failures
      // happen before the executor exists and do not leak. killSimulation() was verified NOT to stop
      // it (it only terminates the net system when simStarted), so it is deliberately not called.
      // A real fix needs reflection on JMT internals or a subprocess engine; both are deferred.
      DispatcherJSIMschema dispatcher = new DispatcherJSIMschema(model);
      dispatcher.setSimulationSeed(seed);
      dispatcher.setTerminalSimulation(terminal);
      if (maxWallClockSeconds != null && maxWallClockSeconds > 0) {
        dispatcher.setSimulationMaxDuration((long) maxWallClockSeconds * 1000L);
      }

      long start = System.nanoTime();
      boolean ok = dispatcher.solveModel();
      double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
      if (!ok) {
        throw new EngineException("JMT solveModel() returned false for model " + model.getName(), null);
      }
      File output = dispatcher.getOutputFile();
      if (output == null || !output.exists()) {
        throw new EngineException("JMT produced no output file", null);
      }
      // The model temp file is no longer needed; the output file is returned.
      model.delete();
      return new RunResult(output, elapsed);
    } catch (EngineException e) {
      if (model != null) {
        model.delete();
      }
      throw e;
    } catch (Exception e) {
      if (model != null) {
        model.delete();
      }
      throw new EngineException("JMT engine failed: " + e.getMessage(), e);
    }
  }

  public void cleanup(RunResult result) {
    if (result != null && result.outputFile() != null) {
      result.outputFile().delete();
    }
  }

  /**
   * A fresh directory for one run's verbose measure logs, under the configured temp directory.
   *
   * <p>Per run, not per service: JMT names each CSV after the measure, so two runs of the same model
   * would write the same filenames. JMT never deletes them either, so the caller must pass the
   * returned path to {@link #deleteLogDir} in a finally block.
   */
  public static Path createLogDir(String tempDir) throws IOException {
    return Files.createTempDirectory(Path.of(tempDir), "qsim-logs-");
  }

  /**
   * Remove a run's log directory and everything in it. Null-tolerant and never throws: this runs on
   * the failure path, where losing the original exception to a cleanup problem would be worse than
   * leaking a temp directory. A failure to delete is reported on stderr and otherwise ignored.
   */
  public static void deleteLogDir(Path logDir) {
    if (logDir == null) {
      return;
    }
    try (var paths = Files.walk(logDir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> {
        if (!p.toFile().delete()) {
          System.err.println("qsim: could not delete measure log " + p);
        }
      });
    } catch (IOException e) {
      System.err.println("qsim: could not clean up measure log directory " + logDir + ": " + e);
    }
  }
}
