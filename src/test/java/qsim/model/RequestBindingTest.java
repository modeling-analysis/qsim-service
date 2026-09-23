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
package qsim.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import qsim.http.Json;
import org.junit.jupiter.api.Test;

class RequestBindingTest {

  @Test
  void deserializesNodesByTypeDiscriminator() throws Exception {
    String json = """
        {
          "model": {
            "name": "mixed",
            "classes": [
              {"name": "web", "type": "open"},
              {"name": "batch", "type": "closed", "population": 20, "referenceStation": "think"}
            ],
            "nodes": [
              {"name": "src1", "type": "source",
               "arrivals": {"web": {"distribution": {"type": "exponential", "rate": 10.0}}}},
              {"name": "q1", "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": null,
               "service": {"web": {"distribution": {"type": "exponential", "rate": 12.0}},
                           "batch": {"distribution": {"mean": 0.5, "scv": 2.0}}}},
              {"name": "think", "type": "delay",
               "service": {"batch": {"distribution": {"type": "exponential", "rate": 0.2}}}},
              {"name": "sink", "type": "sink"}
            ],
            "routing": {
              "web": [{"from": "src1", "to": "q1"}, {"from": "q1", "to": "sink"}]
            }
          },
          "seed": 12345,
          "stopping": {"alpha": 0.05, "precision": 0.05, "maxSamples": 1000000, "maxWallClockSeconds": 120},
          "measures": ["response-time", "utilization"]
        }
        """;

    SimulationRequest req = Json.MAPPER.readValue(json, SimulationRequest.class);

    assertEquals("mixed", req.model().name());
    assertEquals(12345L, req.seed());
    assertEquals(2, req.model().classes().size());
    assertInstanceOf(SourceNode.class, req.model().nodes().get(0));
    assertInstanceOf(QueueNode.class, req.model().nodes().get(1));
    assertInstanceOf(DelayNode.class, req.model().nodes().get(2));
    assertInstanceOf(SinkNode.class, req.model().nodes().get(3));

    QueueNode q1 = (QueueNode) req.model().nodes().get(1);
    assertEquals(2.0, q1.service().get("batch").distribution().scv());
    assertEquals(10.0, ((SourceNode) req.model().nodes().get(0))
        .arrivals().get("web").distribution().rate());
    assertEquals(1, req.model().routing().get("web").size() - 1);
  }

  @Test
  void serializesResponseWithClassKey() throws Exception {
    MeasureResult m = new MeasureResult("q1", "web", "response-time",
        0.42, 0.40, 0.44, 0.05, 0.048, true, 45000, 1200, 0.011, 0.105);
    SimulationResponse resp = new SimulationResponse(
        "mixed", "simulation", 12345L, 8.3, true, java.util.List.of(m));

    String out = Json.MAPPER.writeValueAsString(resp);
    assertTrue(out.contains("\"class\":\"web\""), "measure job class must serialize as key 'class'");
    assertTrue(out.contains("\"solutionMethod\":\"simulation\""));
    assertTrue(out.contains("\"completed\":true"));
  }

  @Test
  void bindsSecondMomentsFlag() throws Exception {
    String json = """
        {"model":{"name":"m","classes":[],"nodes":[],"routing":{}},
         "measures":["interarrival-time"],"secondMoments":true}
        """;
    SimulationRequest req = Json.MAPPER.readValue(json, SimulationRequest.class);
    assertEquals(Boolean.TRUE, req.secondMoments());
    assertEquals(java.util.List.of("interarrival-time"), req.measures());
  }

  @Test
  void secondMomentsIsNullWhenAbsent() throws Exception {
    String json = """
        {"model":{"name":"m","classes":[],"nodes":[],"routing":{}}}
        """;
    assertNull(Json.MAPPER.readValue(json, SimulationRequest.class).secondMoments());
  }
}
