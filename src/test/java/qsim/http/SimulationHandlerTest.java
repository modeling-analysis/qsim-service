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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import qsim.contract.ValidationException;
import qsim.engine.EngineException;

class SimulationHandlerTest {

  private final SimulationHandler handler = new SimulationHandler(new SimulationService(Config.defaults()));

  @Test
  void malformedJsonMapsTo400() {
    SimulationHandler.Result r = handler.process("{ this is not json ".getBytes(StandardCharsets.UTF_8));
    assertEquals(400, r.status());
    assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("error"));
  }

  @Test
  void semanticErrorMapsTo422() {
    // open class with no source -> ContractValidator throws UNPROCESSABLE
    String json = """
        {"model":{"name":"bad",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"snk","type":"sink"}],
          "routing":{"web":[]}},
         "seed":1}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(422, r.status());
  }

  @Test
  void nullRegionEntryMapsTo422() {
    // Valid JSON that binds a null region: must be a validation error, not an NPE and a 500 (PR #20).
    String json = """
        {"model":{"name":"r",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
                   {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"exponential","rate":2.0}}}},
                   {"name":"snk","type":"sink"}],
          "routing":{"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]},
          "regions":[null]},
         "seed":1}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(422, r.status());
    assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("regions[0]"));
  }

  @Test
  void unsupportedMeasureMapsTo400() {
    String json = """
        {"model":{"name":"m",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
                   {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"exponential","rate":2.0}}}},
                   {"name":"snk","type":"sink"}],
          "routing":{"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}},
         "seed":1,
         "measures":["teleportation-latency"]}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(400, r.status());
  }

  @Test
  void unsupportedDistributionTypeMapsTo422() {
    String json = """
        {"model":{"name":"m",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
                   {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"weibull","rate":2.0}}}},
                   {"name":"snk","type":"sink"}],
          "routing":{"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}},
         "seed":1}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(422, r.status());
  }

  @Test
  void negativeDistributionRateMapsTo422() {
    String json = """
        {"model":{"name":"m",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
                   {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"exponential","rate":-1.0}}}},
                   {"name":"snk","type":"sink"}],
          "routing":{"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}},
         "seed":1}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(422, r.status());
  }

  @Test
  void missingQueueServiceMapMapsTo422() {
    String json = """
        {"model":{"name":"m",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
                   {"name":"q","type":"queue","servers":1,"scheduling":"fcfs"},
                   {"name":"snk","type":"sink"}],
          "routing":{"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}},
         "seed":1}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(422, r.status());
  }

  @Test
  void statusForMapsExceptionTypes() {
    assertEquals(422, handler.statusFor(
        new ValidationException(ValidationException.Kind.UNPROCESSABLE, java.util.List.of("x"))));
    assertEquals(400, handler.statusFor(
        new ValidationException(ValidationException.Kind.BAD_REQUEST, java.util.List.of("x"))));
    assertEquals(500, handler.statusFor(new EngineException("boom", null)));
  }

  @Test
  void readBoundedAcceptsBodyAtOrUnderLimit() throws Exception {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] result = SimulationHandler.readBounded(new ByteArrayInputStream(data), data.length);
    assertArrayEquals(data, result);
  }

  @Test
  void readBoundedThrowsWhenBodyExceedsLimit() {
    byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
    assertThrows(SimulationHandler.PayloadTooLargeException.class,
        () -> SimulationHandler.readBounded(new ByteArrayInputStream(data), 5));
  }
}
