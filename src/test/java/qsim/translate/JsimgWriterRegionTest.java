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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import qsim.http.Json;
import qsim.model.*;

/** Finite capacity regions are written as one {@code <blockingRegion>} each (spec sections 3, 6.2). */
class JsimgWriterRegionTest {

  private final JsimgWriter writer = new JsimgWriter();
  private final XPath xp = XPathFactory.newInstance().newXPath();

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private Map<String, ServiceSpec> svc() {
    return Map.of("a", new ServiceSpec(exp(2.0)), "b", new ServiceSpec(exp(2.0)));
  }

  private static RoutingEdge e(String from, String to) { return new RoutingEdge(from, to, null); }

  private Stopping stopping() {
    return new Stopping(0.05, 0.05, 10000, 1_000_000, null, null, 120, false);
  }

  /** src -> q1 -> fj(2 branches) -> snk, for open classes a and b. */
  private NetworkModel model(boolean withClosed, Region region) {
    List<JobClass> classes = new ArrayList<>(List.of(
        new JobClass("a", "open", null, null), new JobClass("b", "open", null, null)));
    List<Node> nodes = new ArrayList<>(List.of(
        new SourceNode("src", "source", Map.of(
            "a", new ArrivalSpec(exp(0.4)), "b", new ArrivalSpec(exp(0.4)))),
        new QueueNode("q1", "queue", 1, "fcfs", null, svc()),
        new ForkJoinNode("fj", "fork-join",
            List.of(new Branch(svc()), new Branch(svc())), "all"),
        new SinkNode("snk", "sink")));
    Map<String, List<RoutingEdge>> routing = new java.util.LinkedHashMap<>();
    for (String c : List.of("a", "b")) {
      routing.put(c, List.of(e("src", "q1"), e("q1", "fj"), e("fj", "snk")));
    }
    if (withClosed) {
      classes.add(new JobClass("c", "closed", 2, "q2"));
      nodes.add(new QueueNode("q2", "queue", 1, "fcfs", null,
          Map.of("c", new ServiceSpec(exp(1.0)))));
      routing.put("c", List.of(e("q2", "q2")));
    }
    return new NetworkModel("m", classes, nodes, routing, List.of(region));
  }

  private Region region(Map<String, Integer> classCap, Object whenFull) {
    return new Region("R", List.of("q1", "fj"), 1, classCap, whenFull);
  }

  private Document doc(NetworkModel m) {
    return writer.toDocument(m, stopping(), 42L, List.of());
  }

  @Test
  void writesOneBlockingRegionAfterConnectionsBeforePreload() throws Exception {
    Document d = doc(model(true, region(null, null)));
    assertEquals("1", xp.evaluate("count(/sim/blockingRegion)", d));
    assertEquals("R", xp.evaluate("/sim/blockingRegion/@name", d));
    assertEquals("default", xp.evaluate("/sim/blockingRegion/@type", d));
    assertEquals("0", xp.evaluate("count(/sim/blockingRegion/following-sibling::connection)", d));
    assertEquals("1", xp.evaluate("count(/sim/blockingRegion/following-sibling::preload)", d));
    assertEquals("1", xp.evaluate("count(/sim/blockingRegion/preceding-sibling::connection[1])", d));
  }

  @Test
  void expandsAForkJoinMemberIntoAllItsStations() throws Exception {
    Document d = doc(model(false, region(null, null)));
    NodeList names = (NodeList) xp.evaluate("/sim/blockingRegion/regionNode/@nodeName", d,
        XPathConstants.NODESET);
    Set<String> got = new java.util.HashSet<>();
    for (int i = 0; i < names.getLength(); i++) {
      got.add(names.item(i).getNodeValue());
    }
    assertEquals(Set.of("q1", "fj", "fj__b0", "fj__b1", "fj__join"), got);
    assertEquals("5", xp.evaluate("count(/sim/blockingRegion/regionNode)", d));
  }

  @Test
  void writesMandatoryConstraints() throws Exception {
    Document d = doc(model(false, region(null, null)));
    assertEquals("1", xp.evaluate("/sim/blockingRegion/globalConstraint/@maxJobs", d));
    assertEquals("-1", xp.evaluate("/sim/blockingRegion/globalMemoryConstraint/@maxMemory", d));
  }

  @Test
  void writesClassConstraintOnlyForClassCapacityEntries() throws Exception {
    Document d = doc(model(false, region(Map.of("a", 1), null)));
    assertEquals("1", xp.evaluate(
        "/sim/blockingRegion/classConstraint[@jobClass='a']/@maxJobsPerClass", d));
    assertEquals("1", xp.evaluate("count(/sim/blockingRegion/classConstraint)", d));
  }

  @Test
  void groupsPerClassChildrenByTag() throws Exception {
    Document d = doc(model(false, region(Map.of("a", 1), null)));
    NodeList kids = (NodeList) xp.evaluate("/sim/blockingRegion/*", d, XPathConstants.NODESET);
    List<String> tags = new ArrayList<>();
    for (int i = 0; i < kids.getLength(); i++) {
      org.w3c.dom.Node k = kids.item(i);
      if (!k.getNodeName().equals("regionNode")) {
        tags.add(k.getNodeName());
      }
    }
    assertEquals(List.of("globalConstraint", "globalMemoryConstraint", "classConstraint",
        "dropRules", "dropRules", "classWeight", "classWeight", "classSize", "classSize"), tags);
    assertEquals("1", xp.evaluate("/sim/blockingRegion/classWeight[@jobClass='b']/@weight", d));
    assertEquals("1", xp.evaluate("/sim/blockingRegion/classSize[@jobClass='b']/@size", d));
  }

  @Test
  void whenFullMapDefaultsMissingClassesToWait() throws Exception {
    Document d = doc(model(false, region(null, Map.of("a", "drop"))));
    assertEquals("true", xp.evaluate("/sim/blockingRegion/dropRules[@jobClass='a']/@dropThisClass", d));
    assertEquals("false", xp.evaluate("/sim/blockingRegion/dropRules[@jobClass='b']/@dropThisClass", d));
  }

  @Test
  void whenFullStringAppliesToEveryClassAndOmittedWaits() throws Exception {
    Document drop = doc(model(false, region(null, "drop")));
    assertEquals("2", xp.evaluate("count(/sim/blockingRegion/dropRules[@dropThisClass='true'])", drop));
    Document wait = doc(model(false, region(null, null)));
    assertEquals("2", xp.evaluate("count(/sim/blockingRegion/dropRules[@dropThisClass='false'])", wait));
  }

  @Test
  void regionModelPassesXsdValidation() throws Exception {
    Document d = doc(model(true, region(Map.of("a", 1), Map.of("a", "drop"))));
    assertDoesNotThrow(() -> writer.validate(d));
  }

  @Test
  void modelWithoutRegionsIsByteIdentical() throws Exception {
    for (String n : List.of("mm1", "qopt-3station")) {
      SimulationRequest r;
      try (var in = getClass().getResourceAsStream("/fixtures/" + n + ".json")) {
        r = Json.MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
            SimulationRequest.class);
      }
      var specs = new MeasureMapper().map(r.model(), r.measures());
      String xml = writer.toXmlString(r.model(), r.stopping(), r.seed(), specs);
      String expected;
      try (var in = getClass().getResourceAsStream("/snapshots/" + n + ".sim.xml")) {
        expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      assertEquals(expected, xml, n + " must render byte-identically to main");
    }
  }
}
