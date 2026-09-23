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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.model.*;
import qsim.translate.JsimgWriter;
import qsim.translate.MeasureSpec;

/**
 * Gates the mechanism behind issue #14's second item: {@code variance} and {@code stdDev} were
 * always null not because the parser missed them but because nothing in the document asked JMT for
 * them. {@code XMLSimulationOutput.writeMeasure} writes the second moments only for a measure whose
 * verbose output exists and only in a terminal simulation, computing them by re-reading the
 * per-sample CSV. Both halves are asserted here, in one run each way, so a regression in either the
 * {@code verbose} attribute or the {@code logPath} shows up as a missing attribute.
 */
class VerboseMeasureStatsTest {

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private NetworkModel mm1() {
    return new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  /** Loose precision and a modest floor: this test is about attributes, not convergence. */
  private Stopping stopping() {
    return new Stopping(0.05, 0.10, 5_000, 200_000, null, null, 120, false);
  }

  private String runAndReadOutput(boolean verbose, Path logDir) throws Exception {
    List<MeasureSpec> specs = List.of(
        new MeasureSpec("q_web_response-time", "Response Time", "q", "web", "station", verbose));
    String xml = new JsimgWriter().toXmlString(mm1(), stopping(), 4242L, specs,
        logDir == null ? null : logDir.toString());
    JmtRunner runner = new JmtRunner();
    RunResult result = runner.run(xml, 4242L, 120, /* terminal */ true);
    try {
      return Files.readString(result.outputFile().toPath());
    } finally {
      runner.cleanup(result);
    }
  }

  @Test
  void aVerboseMeasureReportsVarianceAndStandardDeviation() throws Exception {
    Path logDir = Files.createTempDirectory("qsim-verbose-test-");
    try {
      String out = runAndReadOutput(true, logDir);
      assertTrue(out.contains("variance=\""), "verbose measure must report variance: " + out);
      assertTrue(out.contains("standardDeviation=\""),
          "verbose measure must report standardDeviation: " + out);
      // JMT writes one CSV per verbose measure, named after the measure, and never deletes it.
      assertTrue(Files.exists(logDir.resolve("q_web_response-time.csv")),
          "JMT must write the per-sample log named after the measure");
    } finally {
      try (var paths = Files.walk(logDir)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @Test
  void aNonVerboseMeasureReportsNeither() throws Exception {
    String out = runAndReadOutput(false, null);
    assertFalse(out.contains("variance=\""),
        "a non-verbose measure must not report variance (this is why #14's fields were null): " + out);
  }
}
