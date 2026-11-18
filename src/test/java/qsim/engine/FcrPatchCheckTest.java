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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

class FcrPatchCheckTest {

  /** Proves target/classes precedes the JMT jar on the surefire classpath. */
  @Test
  void theTestClasspathRunsThePatchedEngine() {
    assertDoesNotThrow(() -> FcrPatchCheck.verify());
  }

  /** The stock class, loaded from the JMT jar alone, must be rejected (spec section 8). */
  @Test
  void rejectsTheUnpatchedStockClass() throws Exception {
    URL jar = new File("lib/JMT-singlejar-1.5.0.jar").toURI().toURL();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jar}, ClassLoader.getPlatformClassLoader())) {
      Class<?> stock = loader.loadClass("simEngine.Simulation");
      IllegalStateException e = assertThrows(IllegalStateException.class, () -> FcrPatchCheck.verify(stock));
      assertTrue(e.getMessage().contains("unpatched"), e.getMessage());
    }
  }
}
