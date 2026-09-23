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
package qsim.translate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import qsim.model.*;

/**
 * Verbose measure logging (issue #15). JMT computes a measure's second moments by re-reading a
 * per-sample CSV it writes under {@code <sim logPath>}, and only for measures marked
 * {@code verbose="true"} — so these two attributes are the whole mechanism behind the
 * {@code variance}/{@code stdDev} the response has always documented and never populated (issue #14).
 */
class JsimgWriterVerboseTest {

  private final JsimgWriter writer = new JsimgWriter();

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private NetworkModel mm1() {
    return new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  private Element sim(Document doc) { return doc.getDocumentElement(); }

  private Element measureNamed(Document doc, String name) {
    NodeList ms = doc.getElementsByTagName("measure");
    for (int i = 0; i < ms.getLength(); i++) {
      Element m = (Element) ms.item(i);
      if (name.equals(m.getAttribute("name"))) {
        return m;
      }
    }
    throw new AssertionError("no measure named " + name);
  }

  @Test
  void withoutALogPathTheDocumentIsUnchanged() {
    List<MeasureSpec> specs =
        List.of(new MeasureSpec("q_web_response-time", "Response Time", "q", "web", "station"));
    Document doc = writer.toDocument(mm1(), null, 42L, specs, null);
    assertEquals("", sim(doc).getAttribute("logPath"));
    assertEquals("false", measureNamed(doc, "q_web_response-time").getAttribute("verbose"));
  }

  @Test
  void aVerboseSpecGetsVerboseTrueAndTheSimGetsTheLogPath() {
    List<MeasureSpec> specs = List.of(
        new MeasureSpec("q_web_response-time", "Response Time", "q", "web", "station", true),
        new MeasureSpec("q_web_utilization", "Utilization", "q", "web", "station", false));
    Document doc = writer.toDocument(mm1(), null, 42L, specs, "/tmp/qsim-logs-test");

    assertEquals("/tmp/qsim-logs-test", sim(doc).getAttribute("logPath"));
    // The CSV format JMT reads back with StatisticalOutputsLoader.
    assertEquals(",", sim(doc).getAttribute("logDelimiter"));
    assertEquals(".", sim(doc).getAttribute("logDecimalSeparator"));
    assertEquals("0", sim(doc).getAttribute("logReplaceMode"));

    assertEquals("true", measureNamed(doc, "q_web_response-time").getAttribute("verbose"));
    assertEquals("false", measureNamed(doc, "q_web_utilization").getAttribute("verbose"));
  }

  /**
   * Unlike {@code minSamples} (issue #10), none of these attributes is missing from the bundled
   * schema, so no {@code ENGINE_ONLY_SIM_ATTRS} exemption is needed and no stderr noise is expected.
   */
  @Test
  void aVerboseDocumentStillPassesXsdValidation() {
    List<MeasureSpec> specs =
        List.of(new MeasureSpec("q_web_response-time", "Response Time", "q", "web", "station", true));
    Document doc = writer.toDocument(mm1(), null, 42L, specs, "/tmp/qsim-logs-test");
    assertDoesNotThrow(() -> writer.validate(doc));
  }
}
