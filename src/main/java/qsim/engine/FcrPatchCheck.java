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

/**
 * Fails closed when the JMT engine on the classpath is not the build-time-patched one. An
 * unpatched engine would otherwise accept the service and then reject every region that holds a
 * fork-join with an obscure engine error.
 */
public final class FcrPatchCheck {

  private FcrPatchCheck() {}

  public static void verify() {
    Class<?> sim;
    try {
      sim = Class.forName("simEngine.Simulation");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("JMT engine not found on the classpath", e);
    }
    try {
      sim.getField("QSIM_FCR_PATCHED");
    } catch (NoSuchFieldException e) {
      var source = sim.getProtectionDomain().getCodeSource();
      throw new IllegalStateException("JMT is unpatched: simEngine.Simulation was loaded from "
          + (source == null ? "an unknown location" : source.getLocation())
          + "; the patched class in qsim's classes must precede the JMT jar on the classpath");
    }
  }
}
