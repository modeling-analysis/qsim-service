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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import simDispatcher.DispatcherJSIMschema;
import org.junit.jupiter.api.Test;

class EngineEndToEndTest {

  @Test
  void solvesBareSimModelAndWritesSolutions() throws Exception {
    Path model = Files.createTempFile("mm1", ".xml");
    Files.copy(getClass().getResourceAsStream("/jmt/mm1.sim.xml"), model,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    DispatcherJSIMschema dispatcher = new DispatcherJSIMschema(model.toFile());
    dispatcher.setSimulationSeed(12345L);
    dispatcher.setTerminalSimulation(true);

    boolean ok = dispatcher.solveModel();
    assertTrue(ok, "solveModel() should return true");

    File out = dispatcher.getOutputFile();
    assertTrue(out != null && out.exists() && out.length() > 0,
        "engine should write a non-empty solutions file");
    String xml = Files.readString(out.toPath());
    assertTrue(xml.contains("<solutions") || xml.contains("<measure"),
        "output should be JMT solutions XML");
  }
}
