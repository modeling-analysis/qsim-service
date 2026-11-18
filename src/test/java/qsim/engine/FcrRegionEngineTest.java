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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A region holding a whole fork-join initializes only under the patched engine. */
class FcrRegionEngineTest {

  @Test
  void forkJoinRegionInitializesUnderThePatch() throws Exception {
    String xml = Files.readString(Path.of("src/test/resources/models/fcr-forkjoin.sim.xml"));
    JmtRunner runner = new JmtRunner();
    RunResult result = runner.run(xml, 1L, 60, true);
    try {
      assertTrue(result.outputFile().exists());
    } finally {
      runner.cleanup(result);
    }
  }
}
