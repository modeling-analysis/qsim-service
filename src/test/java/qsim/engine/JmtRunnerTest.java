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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JmtRunnerTest {

  private String mm1Xml() throws Exception {
    try (var in = getClass().getResourceAsStream("/models/mm1.sim.xml")) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void runsModelAndProducesSolutionsFile() throws Exception {
    JmtRunner runner = new JmtRunner();
    RunResult r = runner.run(mm1Xml(), 12345L, 60, true);
    assertTrue(r.outputFile().exists(), "engine must produce an output file");
    String out = Files.readString(Path.of(r.outputFile().toURI()));
    assertTrue(out.contains("<measure"), "output must contain measures");
    assertTrue(r.wallClockSeconds() >= 0.0);
    runner.cleanup(r);
  }

  @Test
  void identicalSeedProducesIdenticalMeasures() throws Exception {
    JmtRunner runner = new JmtRunner();
    RunResult a = runner.run(mm1Xml(), 999L, 60, true);
    RunResult b = runner.run(mm1Xml(), 999L, 60, true);
    String outA = Files.readString(Path.of(a.outputFile().toURI()));
    String outB = Files.readString(Path.of(b.outputFile().toURI()));
    // meanValue attributes must match bit-for-bit for the same seed
    assertEquals(meanValues(outA), meanValues(outB), "same seed must be deterministic");
    runner.cleanup(a);
    runner.cleanup(b);
  }

  private static java.util.List<String> meanValues(String xml) {
    var matcher = java.util.regex.Pattern.compile("meanValue=\"([^\"]*)\"").matcher(xml);
    var out = new java.util.ArrayList<String>();
    while (matcher.find()) {
      out.add(matcher.group(1));
    }
    return out;
  }

  @Test
  void failedRunLeavesNoWatchdogThread() throws Exception {
    String xml;
    try (var in = getClass().getResourceAsStream("/models/fcr-forkjoin.sim.xml")) {
      xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    String broken = xml.replaceAll("<globalMemoryConstraint[^>]*/>", "");
    assertTrue(!broken.equals(xml), "fixture must have had a globalMemoryConstraint to remove");

    Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
    assertThrows(EngineException.class, () -> new JmtRunner().run(broken, 1L, 600, true));

    Set<Thread> leaked = Set.of();
    long deadline = System.nanoTime() + 2_000_000_000L;
    do {
      leaked = Thread.getAllStackTraces().keySet().stream()
          .filter(t -> !before.contains(t) && t.isAlive())
          .collect(Collectors.toSet());
      if (leaked.isEmpty()) {
        break;
      }
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    assertTrue(leaked.isEmpty(),
        "threads left running after a failed run: "
            + leaked.stream().map(Thread::getName).collect(Collectors.toList()));
  }
}
