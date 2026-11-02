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
package qsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class HeadlessSmokeTest {

  @Test
  void runsHeadless() {
    assertEquals("true", System.getProperty("java.awt.headless"));
  }

  @Test
  void jmtDispatcherClassLoadsUnderJava17() throws Exception {
    Class<?> dispatcher = Class.forName("simDispatcher.DispatcherJSIMschema");
    assertNotNull(dispatcher);
  }
}
