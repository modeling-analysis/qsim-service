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

import java.util.List;
import java.util.Map;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import qsim.model.*;

/**
 * Issue #18: JSIMG connections are class-agnostic, and JMT's {@code RandomStrategy} picks uniformly
 * among ALL of a node's connections. So a class with one edge out of a node that other classes
 * leave by different edges must get a routing table naming only its own destination.
 */
class JsimgWriterMultiClassRoutingTest {

  private final JsimgWriter writer = new JsimgWriter();
  private final XPath xp = XPathFactory.newInstance().newXPath();

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private Map<String, ServiceSpec> both(double rate) {
    return Map.of("a", new ServiceSpec(exp(rate)), "b", new ServiceSpec(exp(rate)));
  }

  /** src fans out by class: a -> qa, b -> qb; both rejoin at snk. */
  private NetworkModel disjoint() {
    return new NetworkModel("disjoint",
        List.of(new JobClass("a", "open", null, null), new JobClass("b", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of(
                    "a", new ArrivalSpec(exp(1.0)), "b", new ArrivalSpec(exp(0.5)))),
                new QueueNode("qa", "queue", 1, "fcfs", null, both(2.0)),
                new QueueNode("qb", "queue", 1, "fcfs", null, both(4.0)),
                new SinkNode("snk", "sink")),
        Map.of("a", List.of(new RoutingEdge("src", "qa", null), new RoutingEdge("qa", "snk", null)),
               "b", List.of(new RoutingEdge("src", "qb", null), new RoutingEdge("qb", "snk", null))));
  }

  private Stopping stopping() {
    return new Stopping(0.05, 0.05, 10000, 1_000_000, null, null, 120, false);
  }

  /** The strategy subParameter that follows {@code <refClass>clazz</refClass>} in node's Router. */
  private String strategy(Document doc, String node, String clazz, String what) throws Exception {
    return xp.evaluate("/sim/node[@name='" + node + "']/section[@className='Router']/parameter"
        + "/refClass[.='" + clazz + "']/following-sibling::subParameter[1]" + what, doc);
  }

  @Test
  void singleEdgeOutOfASharedNodeNamesOnlyThatClassesDestination() throws Exception {
    Document doc = writer.toDocument(disjoint(), stopping(), 42L, List.of());
    for (String[] c : new String[][] {{"a", "qa"}, {"b", "qb"}}) {
      assertEquals("jmt.engine.NetStrategies.RoutingStrategies.EmpiricalStrategy",
          strategy(doc, "src", c[0], "/@classPath"), "class " + c[0] + " at src");
      assertEquals("1", xp.evaluate("count(/sim/node[@name='src']/section[@className='Router']/parameter"
          + "/refClass[.='" + c[0] + "']/following-sibling::subParameter[1]/subParameter/subParameter)", doc),
          "class " + c[0] + " must have exactly one routing entry");
      assertEquals(c[1], strategy(doc, "src", c[0],
          "/subParameter/subParameter/subParameter[@name='stationName']/value"));
      assertEquals("1.0", strategy(doc, "src", c[0],
          "/subParameter/subParameter/subParameter[@name='probability']/value"));
    }
  }

  @Test
  void singleEdgeToANodesOnlyConnectionStaysRandom() throws Exception {
    // qa's only connection is snk, so RandomStrategy is exact there and draws no random number:
    // keeping it leaves every model that was already correct byte-identical.
    Document doc = writer.toDocument(disjoint(), stopping(), 42L, List.of());
    assertEquals("jmt.engine.NetStrategies.RoutingStrategies.RandomStrategy",
        strategy(doc, "qa", "a", "/@classPath"));
  }

  @Test
  void singleClassTandemIsUnchanged() throws Exception {
    NetworkModel m = new NetworkModel("tandem",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q1", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new QueueNode("q2", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(3.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q1", null), new RoutingEdge("q1", "q2", null),
                              new RoutingEdge("q2", "snk", null))));
    Document doc = writer.toDocument(m, stopping(), 42L, List.of());
    for (String n : List.of("src", "q1", "q2")) {
      assertEquals("jmt.engine.NetStrategies.RoutingStrategies.RandomStrategy",
          strategy(doc, n, "web", "/@classPath"), n);
    }
  }

  @Test
  void connectionsFromUndeclaredRoutingKeysStillForceATable() throws Exception {
    // writeConnections emits an edge for every routing entry, declared class or not. If the router
    // only looked at declared classes, class a's lone edge src -> qa would look like src's only
    // connection and keep RandomStrategy, which would then split a across qa and qb.
    // ContractValidator rejects such a key; the writer must still not route on a different
    // connection set than the one it writes.
    NetworkModel m = new NetworkModel("undeclared",
        List.of(new JobClass("a", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("a", new ArrivalSpec(exp(1.0)))),
                new QueueNode("qa", "queue", 1, "fcfs", null, Map.of("a", new ServiceSpec(exp(2.0)))),
                new QueueNode("qb", "queue", 1, "fcfs", null, Map.of("a", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("a", List.of(new RoutingEdge("src", "qa", null), new RoutingEdge("qa", "snk", null)),
               "typo", List.of(new RoutingEdge("src", "qb", null), new RoutingEdge("qb", "snk", null))));
    Document doc = writer.toDocument(m, stopping(), 42L, List.of());
    assertEquals("2", xp.evaluate("count(/sim/connection[@source='src'])", doc));
    assertEquals("jmt.engine.NetStrategies.RoutingStrategies.EmpiricalStrategy",
        strategy(doc, "src", "a", "/@classPath"));
    assertEquals("qa", strategy(doc, "src", "a",
        "/subParameter/subParameter/subParameter[@name='stationName']/value"));
  }
}
