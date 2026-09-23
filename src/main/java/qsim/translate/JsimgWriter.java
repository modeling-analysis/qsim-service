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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import qsim.contract.ValidationException;
import qsim.distribution.CanonicalDistribution;
import qsim.distribution.DistParam;
import qsim.distribution.DistributionResolver;
import qsim.model.*;

public class JsimgWriter {

  private final DistributionResolver resolver = new DistributionResolver();

  public String toXmlString(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures) {
    return toXmlString(model, stopping, seed, measures, null);
  }

  public String toXmlString(NetworkModel model, Stopping stopping, long seed,
                            List<MeasureSpec> measures, String logPath) {
    return Xml.serialize(toDocument(model, stopping, seed, measures, logPath));
  }

  public Document toDocument(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures) {
    return toDocument(model, stopping, seed, measures, null);
  }

  /**
   * @param logPath directory JMT writes one per-sample CSV into for each measure with
   *     {@link MeasureSpec#verbose()} set, named {@code <measure name>.csv}; it re-reads them at the
   *     end of the run to compute the second moments (issue #15). Pass {@code null} when no measure
   *     is verbose: the attribute is then omitted and the document is exactly what it was before.
   *     The caller owns the directory's lifecycle — JMT never deletes these files.
   */
  public Document toDocument(NetworkModel model, Stopping stopping, long seed,
                             List<MeasureSpec> measures, String logPath) {
    checkMeasures(model, measures);
    Document doc = Xml.newDocument();
    Element sim = Xml.child(doc, "sim",
        "name", model.name(),
        "seed", Long.toString(seed),
        "maxSamples", stopping == null || stopping.maxSamples() == null ? "1000000" : stopping.maxSamples().toString(),
        // Sample floor (issue #10). JMT's SimLoader reads minSamples off <sim> exactly like
        // maxSamples, but the bundled SIMmodeldefinition.xsd never declared it — see the
        // ENGINE_ONLY_SIM_ATTRS note on validate(). Xml.child drops null values, so an absent
        // floor omits the attribute entirely and leaves JMT's own default of 0 (no floor).
        "minSamples", stopping == null || stopping.minSamples() == null ? null : stopping.minSamples().toString(),
        "maxEvents", stopping == null || stopping.maxEvents() == null ? "-1" : stopping.maxEvents().toString(),
        "maxSimulated", stopping == null || stopping.maxSimulatedTime() == null ? "-1.0" : stopping.maxSimulatedTime().toString(),
        "disableStatisticStop", stopping != null && Boolean.TRUE.equals(stopping.disableStatisticStop()) ? "true" : "false",
        "polling", "1.0",
        "logPath", logPath,
        // The CSV dialect StatisticalOutputsLoader reads back. Written explicitly rather than left
        // to the schema's defaults so the samples JMT parses are the samples it wrote.
        "logDelimiter", logPath == null ? null : ",",
        "logDecimalSeparator", logPath == null ? null : ".",
        "logReplaceMode", logPath == null ? null : "0");
    // Declare the xsi namespace binding the engine requires (Task 2). Set as a real
    // namespace declaration so it is exempt from XSD validation.
    sim.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsi",
        "http://www.w3.org/2001/XMLSchema-instance");

    writeUserClasses(sim, model);
    for (Node n : model.nodes()) {
      writeNode(sim, model, n);
    }
    if (measures != null) {
      for (MeasureSpec m : measures) {
        writeMeasure(sim, model, m, stopping);
      }
    }
    writeConnections(sim, model);
    writePreload(sim, model);
    return doc;
  }

  // ---- user classes --------------------------------------------------------

  private void writeUserClasses(Element sim, NetworkModel model) {
    for (JobClass c : model.classes()) {
      if ("open".equals(c.type())) {
        Xml.child(sim, "userClass",
            "name", c.name(), "type", "open", "priority", "0",
            "referenceSource", sourceOf(model, c.name()));
      } else {
        // closed: filled in Task 8 (customers + reference station)
        writeClosedUserClass(sim, model, c);
      }
    }
  }

  /** Name of the single source whose arrivals include this open class. */
  private String sourceOf(NetworkModel model, String className) {
    return model.nodes().stream()
        .filter(n -> n instanceof SourceNode)
        .map(n -> (SourceNode) n)
        .filter(s -> s.arrivals() != null && s.arrivals().containsKey(className))
        .map(SourceNode::name)
        .findFirst()
        .orElseThrow(() -> new ValidationException(ValidationException.Kind.UNPROCESSABLE,
            List.of("open class '" + className + "' has no source")));
  }

  /**
   * Closed class: {@code customers} carries the population and {@code referenceSource} the
   * reference station (verified attribute names against jmt.engine.simEngine.SimLoader, which
   * reads {@code referenceSource} for both open and closed classes).
   */
  protected void writeClosedUserClass(Element sim, NetworkModel model, JobClass c) {
    String ref = c.referenceStation() != null ? c.referenceStation() : defaultReference(model, c.name());
    Xml.child(sim, "userClass",
        "name", c.name(), "type", "closed", "priority", "0",
        "customers", c.population().toString(),
        "referenceSource", ref);
  }

  /** Section 5.3: default to the class's delay node if present, else its first routed station. */
  private String defaultReference(NetworkModel model, String className) {
    for (Node n : model.nodes()) {
      if (n instanceof DelayNode d && d.service().containsKey(className)) {
        return d.name();
      }
    }
    List<RoutingEdge> edges = model.routing() == null ? List.of()
        : model.routing().getOrDefault(className, List.of());
    if (!edges.isEmpty()) {
      return edges.get(0).from();
    }
    throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
        List.of("cannot determine referenceStation for closed class '" + className + "'"));
  }

  /**
   * The engine's {@code SimLoader} never reads {@code userClass@customers} — it only seeds
   * closed-class populations from a {@code <preload>} block (verified against
   * jmt.engine.simEngine.SimLoader lines ~486-546). Without this, a closed network would
   * simulate with zero customers and produce degenerate (but not XSD-invalid) results.
   */
  private void writePreload(Element sim, NetworkModel model) {
    var byStation = new java.util.LinkedHashMap<String, java.util.List<JobClass>>();
    for (JobClass c : model.classes()) {
      if ("closed".equals(c.type())) {
        String ref = c.referenceStation() != null ? c.referenceStation() : defaultReference(model, c.name());
        byStation.computeIfAbsent(ref, k -> new java.util.ArrayList<>()).add(c);
      }
    }
    if (byStation.isEmpty()) {
      return;
    }
    Element preload = Xml.child(sim, "preload");
    for (var entry : byStation.entrySet()) {
      Element sp = Xml.child(preload, "stationPopulations", "stationName", entry.getKey());
      for (JobClass c : entry.getValue()) {
        Xml.child(sp, "classPopulation", "refClass", c.name(), "population", c.population().toString());
      }
    }
  }

  // ---- nodes ---------------------------------------------------------------

  private void writeNode(Element sim, NetworkModel model, Node n) {
    Element node = Xml.child(sim, "node", "name", n.name());
    if (n instanceof SourceNode src) {
      writeRandomSource(node, model, src);
      writeServiceTunnel(node);
      writeRouter(node, model, n.name());
    } else if (n instanceof QueueNode q) {
      String sizeValue = q.capacity() == null ? "-1" : q.capacity().toString();
      String dropStrategyValue = q.capacity() == null ? "waiting queue" : "drop";
      writeQueueSectionFull(node, model, sizeValue, dropStrategyValue);
      writeServer(node, q);
      writeRouter(node, model, n.name());
    } else if (n instanceof SinkNode) {
      Xml.child(node, "section", "className", "JobSink");
    } else {
      writeNonOpenNode(sim, node, model, n); // delay / fork-join → Task 8
    }
  }

  protected void writeNonOpenNode(Element sim, Element node, NetworkModel model, Node n) {
    if (n instanceof DelayNode d) {
      writeDelayNode(node, model, d);
    } else if (n instanceof ForkJoinNode fj) {
      writeForkJoin(sim, node, model, fj);
    } else {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("unknown node type '" + n.type() + "'"));
    }
  }

  private void writeDelayNode(Element node, NetworkModel model, DelayNode d) {
    writeQueueSectionFull(node, model, "-1", "waiting queue");
    Element delay = Xml.child(node, "section", "className", "Delay");
    Element svc = Xml.child(delay, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy", "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : d.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
    writeRouter(node, model, d.name());
  }

  /**
   * A {@code Queue} section matching the real JMT
   * {@code Queue(Integer, String[], QueueGetStrategy, QueuePutStrategy[])} constructor —
   * verified against the extracted fork template (src/test/resources/jmt/mm1.sim.xml) and, as of
   * Task 9, against a real engine run of an open {@code QueueNode} model. Task 7's original
   * single-attribute Queue writer (for open queue nodes) emitted a shape the engine's reflective
   * loader rejects with {@code LoadException} -&gt; {@code NoSuchMethodException} on
   * {@code jmt.engine.NodeSections.Queue.<init>}; every open-queue caller now goes through this
   * full-constructor shape instead, parameterized by {@code sizeValue} so finite-capacity
   * {@code QueueNode}s keep their existing size/drop semantics while delay/fork/branch callers
   * keep passing {@code "-1"}.
   */
  private void writeQueueSectionFull(Element node, NetworkModel model, String sizeValue, String dropStrategyValue) {
    Element section = Xml.child(node, "section", "className", "Queue");
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "size"), "value", sizeValue);
    Element drop = Xml.child(section, "parameter",
        "classPath", "java.lang.String", "name", "dropStrategies", "array", "true");
    for (JobClass c : model.classes()) {
      Element rc = Xml.child(drop, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(c.name()));
      Xml.textEl(Xml.child(drop, "subParameter", "classPath", "java.lang.String", "name", "dropStrategy"),
          "value", dropStrategyValue);
    }
    Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.QueueGetStrategies.FCFSstrategy", "name", "FCFSstrategy");
    Element put = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.QueuePutStrategy", "name", "QueuePutStrategy", "array", "true");
    for (JobClass c : model.classes()) {
      Element rc = Xml.child(put, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(c.name()));
      Xml.child(put, "subParameter",
          "classPath", "jmt.engine.NetStrategies.QueuePutStrategies.TailStrategy", "name", "TailStrategy");
    }
  }

  /** {@code forkNode + "__b" + index} — package-visible so the measure mapper can remap onto it. */
  static String branchStationName(String forkNode, int index) {
    return forkNode + "__b" + index;
  }

  /** {@code forkNode + "__join"} — package-visible so the measure mapper can remap onto it. */
  static String joinStationName(String forkNode) {
    return forkNode + "__join";
  }

  /**
   * Expands a domain fork-join node into 2+N JSIMG nodes: the fork node itself (kept under the
   * domain name, sections Queue+ServiceTunnel+Fork), one branch Server station per branch, and a
   * join node (Join+ServiceTunnel+Router). Verified against the real fork template extracted at
   * src/test/resources/jmt/mm1.sim.xml (JMT's open_1class_3stat_fork.jsimg): the Fork section's
   * 4-arg constructor requires a ForkStrategy array parameter (ProbabilitiesFork/OutPath per
   * branch) even though isSimplifiedFork=true means actual routing follows the node's real
   * output connections — omitting it leaves no matching Fork constructor and the engine's
   * reflective loader throws LoadException.
   */
  private void writeForkJoin(Element sim, Element forkNodeEl, NetworkModel model, ForkJoinNode fj) {
    checkBranchClassConsistency(fj);
    writeQueueSectionFull(forkNodeEl, model, "-1", "waiting queue");
    Xml.child(forkNodeEl, "section", "className", "ServiceTunnel");
    Element fork = Xml.child(forkNodeEl, "section", "className", "Fork");
    Xml.textEl(Xml.child(fork, "parameter", "classPath", "java.lang.Integer", "name", "jobsPerLink"), "value", "1");
    Xml.textEl(Xml.child(fork, "parameter", "classPath", "java.lang.Integer", "name", "block"), "value", "-1");
    Xml.textEl(Xml.child(fork, "parameter", "classPath", "java.lang.Boolean", "name", "isSimplifiedFork"),
        "value", "true");
    Element forkStrategy = Xml.child(fork, "parameter",
        "classPath", "jmt.engine.NetStrategies.ForkStrategy", "name", "ForkStrategy", "array", "true");
    for (JobClass c : model.classes()) {
      Element rc = Xml.child(forkStrategy, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(c.name()));
      Element branchProb = Xml.child(forkStrategy, "subParameter",
          "classPath", "jmt.engine.NetStrategies.ForkStrategies.ProbabilitiesFork", "name", "Branch Probabilities");
      Element outPaths = Xml.child(branchProb, "subParameter",
          "classPath", "jmt.engine.NetStrategies.ForkStrategies.OutPath", "name", "EmpiricalEntryArray",
          "array", "true");
      for (int i = 0; i < fj.branches().size(); i++) {
        writeOutPathEntry(outPaths, branchStationName(fj.name(), i));
      }
    }

    for (int i = 0; i < fj.branches().size(); i++) {
      writeBranchStation(sim, model, fj, i);
    }

    Element joinNode = Xml.child(sim, "node", "name", joinStationName(fj.name()));
    Element join = Xml.child(joinNode, "section", "className", "Join");
    Element joinStrategy = Xml.child(join, "parameter",
        "classPath", "jmt.engine.NetStrategies.JoinStrategy", "name", "JoinStrategy", "array", "true");
    String numRequired = numRequired(fj);
    for (JobClass c : model.classes()) {
      Element rc = Xml.child(joinStrategy, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(c.name()));
      Element normalJoin = Xml.child(joinStrategy, "subParameter",
          "classPath", "jmt.engine.NetStrategies.JoinStrategies.NormalJoin", "name", "Standard Join");
      Xml.textEl(Xml.child(normalJoin, "subParameter", "classPath", "java.lang.Integer", "name", "numRequired"),
          "value", numRequired);
    }
    Xml.child(joinNode, "section", "className", "ServiceTunnel");
    writeRouter(joinNode, model, joinStationName(fj.name()));
  }

  /** One {@code OutPathEntry} (stationName + deterministic single-job link) for a fork branch. */
  private void writeOutPathEntry(Element outPaths, String branchName) {
    Element outPathEntry = Xml.child(outPaths, "subParameter",
        "classPath", "jmt.engine.NetStrategies.ForkStrategies.OutPath", "name", "OutPathEntry");
    Element outUnit = Xml.child(outPathEntry, "subParameter",
        "classPath", "jmt.engine.random.EmpiricalEntry", "name", "outUnitProbability");
    Xml.textEl(Xml.child(outUnit, "subParameter", "classPath", "java.lang.String", "name", "stationName"),
        "value", branchName);
    Xml.textEl(Xml.child(outUnit, "subParameter", "classPath", "java.lang.Double", "name", "probability"),
        "value", "1.0");
    Element jobsPerLinkDis = Xml.child(outPathEntry, "subParameter",
        "classPath", "jmt.engine.random.EmpiricalEntry", "name", "JobsPerLinkDis", "array", "true");
    Element entry = Xml.child(jobsPerLinkDis, "subParameter",
        "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntry");
    Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.String", "name", "numbers"), "value", "1");
    Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.Double", "name", "probability"),
        "value", "1.0");
  }

  /** A branch station: Queue (infinite) + single-server Server + Router (fork's routes to it are external). */
  private void writeBranchStation(Element sim, NetworkModel model, ForkJoinNode fj, int index) {
    Branch b = fj.branches().get(index);
    String branchName = branchStationName(fj.name(), index);
    Element bnode = Xml.child(sim, "node", "name", branchName);
    writeQueueSectionFull(bnode, model, "-1", "waiting queue");
    Element server = Xml.child(bnode, "section", "className", "Server");
    Xml.textEl(Xml.child(server, "parameter", "classPath", "java.lang.Integer", "name", "maxJobs"), "value", "1");
    Element nov = Xml.child(server, "parameter",
        "classPath", "java.lang.Integer", "name", "numberOfVisits", "array", "true");
    for (String className : b.service().keySet()) {
      Element rc = Xml.child(nov, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(className));
      Xml.textEl(Xml.child(nov, "subParameter", "classPath", "java.lang.Integer", "name", "numberOfVisits"),
          "value", "1");
    }
    Element svc = Xml.child(server, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy", "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : b.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
    writeRouter(bnode, model, branchName);
    // The internal fork->branch->join connections are emitted from writeConnections (not here):
    // the XSD requires every <node> to precede every <connection> in document order, and this
    // method runs interleaved with sibling <node> writes during the main node loop.
  }

  /**
   * Every branch must serve exactly the same set of classes: the Fork section routes every class
   * in {@code model.classes()} into every branch (fork semantics send each job to every branch),
   * but a branch station only carries a {@code ServiceStrategy}/{@code numberOfVisits} entry for
   * the classes in its own {@code service()} map. A branch missing a class another branch serves
   * would silently route that class into a Server section with no matching strategy, which the
   * real engine's reflective loader would reject with the same class of {@code LoadException}
   * already confirmed for Task 7's Queue section — fail loudly here instead.
   */
  private static void checkBranchClassConsistency(ForkJoinNode fj) {
    List<Branch> branches = fj.branches();
    if (branches.size() < 2) {
      return;
    }
    java.util.Set<String> reference = branches.get(0).service().keySet();
    for (int i = 1; i < branches.size(); i++) {
      java.util.Set<String> current = branches.get(i).service().keySet();
      if (current.equals(reference)) {
        continue;
      }
      java.util.Set<String> onlyInReference = new java.util.TreeSet<>(reference);
      onlyInReference.removeAll(current);
      java.util.Set<String> onlyInCurrent = new java.util.TreeSet<>(current);
      onlyInCurrent.removeAll(reference);
      boolean missingFromCurrent = !onlyInReference.isEmpty();
      String offendingClass = missingFromCurrent ? onlyInReference.iterator().next() : onlyInCurrent.iterator().next();
      int servingBranch = missingFromCurrent ? 0 : i;
      int missingBranch = missingFromCurrent ? i : 0;
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("fork-join '" + fj.name() + "' has inconsistent branch class coverage: class '"
              + offendingClass + "' is served by branch " + servingBranch + " but not by branch " + missingBranch));
    }
  }

  /** §5.3/design: v1 only supports {@code "all"} (NormalJoin numRequired=-1 = wait for every branch). */
  private static String numRequired(ForkJoinNode fj) {
    if (fj.join() == null || "all".equals(fj.join())) {
      return "-1";
    }
    try {
      return Integer.toString(Integer.parseInt(fj.join()));
    } catch (NumberFormatException e) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("fork-join '" + fj.name() + "' has unsupported join policy '" + fj.join() + "'"));
    }
  }

  private void writeRandomSource(Element node, NetworkModel model, SourceNode src) {
    Element section = Xml.child(node, "section", "className", "RandomSource");
    // ServiceStrategy parameter: one entry per open class served here.
    Element param = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy",
        "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ArrivalSpec> e : src.arrivals().entrySet()) {
      writeServiceStrategyEntry(param, e.getKey(), e.getValue().distribution());
    }
  }

  private void writeServiceTunnel(Element node) {
    Xml.child(node, "section", "className", "ServiceTunnel");
  }

  /**
   * The {@code Server} section's {@code numberOfVisits} parameter must be the per-class
   * array/refClass form ({@code Integer[]} keyed by class), not a bare scalar — verified against
   * the real engine, matching {@link #writeBranchStation}'s Server section (Task 8, already
   * confirmed to load). A scalar {@code numberOfVisits} is one of Task 7's minimal-shape mistakes
   * that the real loader rejects.
   */
  private void writeServer(Element node, QueueNode q) {
    Element section = Xml.child(node, "section", "className", "Server");
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "maxJobs"),
        "value", Integer.toString(q.servers()));
    Element nov = Xml.child(section, "parameter",
        "classPath", "java.lang.Integer", "name", "numberOfVisits", "array", "true");
    for (String className : q.service().keySet()) {
      Element rc = Xml.child(nov, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(className));
      Xml.textEl(Xml.child(nov, "subParameter", "classPath", "java.lang.Integer", "name", "numberOfVisits"),
          "value", "1");
    }
    Element svc = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy",
        "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : q.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
  }

  // ---- shared: a distribution as a ServiceStrategy array entry --------------

  /** Emits one refClass subParameter + the ServiceTimeStrategy holding the distribution. */
  void writeServiceStrategyEntry(Element serviceStrategyParam, String className, Distribution dist) {
    CanonicalDistribution c = resolver.resolve(dist);
    Element refClass = Xml.child(serviceStrategyParam, "refClass");
    refClass.appendChild(refClass.getOwnerDocument().createTextNode(className));
    Element strat = Xml.child(serviceStrategyParam, "subParameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategies.ServiceTimeStrategy",
        "name", "ServiceTimeStrategy");
    // distribution wrapper (empty) + its Par block
    Xml.child(strat, "subParameter", "classPath", c.distributionClass(), "name", c.label());
    Element par = Xml.child(strat, "subParameter",
        "classPath", c.parameterClass(), "name", "distrPar");
    for (DistParam p : c.params()) {
      Element sp = Xml.child(par, "subParameter", "classPath", p.javaType(), "name", p.name());
      Xml.textEl(sp, "value", p.value());
    }
  }

  // ---- routing -------------------------------------------------------------

  private void writeRouter(Element node, NetworkModel model, String fromNode) {
    // A join station's outgoing routes are the domain fork-join node's out-edges (the join is
    // where routing "out of the fork-join" actually happens per the design decision). Resolved by
    // set membership against the model's actual ForkJoinNodes (not a "__join" string-suffix
    // heuristic), so a user-named node that happens to end in "__join" is never misrouted.
    String routingKey = joinRoutingKey(model, fromNode);
    Element section = Xml.child(node, "section", "className", "Router");
    Element param = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.RoutingStrategy",
        "name", "RoutingStrategy", "array", "true");
    for (JobClass c : model.classes()) {
      List<RoutingEdge> edges = outEdges(model, c.name(), routingKey);
      Element refClass = Xml.child(param, "refClass");
      refClass.appendChild(refClass.getOwnerDocument().createTextNode(c.name()));
      if (edges.size() <= 1) {
        Xml.child(param, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.RandomStrategy",
            "name", "Random");
      } else {
        Element emp = Xml.child(param, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.EmpiricalStrategy",
            "name", "Probabilities");
        Element entries = Xml.child(emp, "subParameter",
            "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntryArray", "array", "true");
        for (RoutingEdge edge : edges) {
          Element entry = Xml.child(entries, "subParameter",
              "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntry");
          Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.String", "name", "stationName"),
              "value", edge.to());
          double prob = edge.probability() == null ? 1.0 / edges.size() : edge.probability();
          Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.Double", "name", "probability"),
              "value", Double.toString(prob));
        }
      }
    }
  }

  /**
   * If {@code fromNode} is the writer-generated join station for some domain {@code ForkJoinNode},
   * returns that fork-join's domain name (whose routing edges are what the join's Router should
   * reflect); otherwise returns {@code fromNode} unchanged. Built from the model's actual
   * fork-join nodes rather than a {@code "__join"} string-suffix check, so a station a user
   * happens to name e.g. {@code "checkout__join"} is never misrouted.
   */
  private static String joinRoutingKey(NetworkModel model, String fromNode) {
    for (Node n : model.nodes()) {
      if (n instanceof ForkJoinNode fj && joinStationName(fj.name()).equals(fromNode)) {
        return fj.name();
      }
    }
    return fromNode;
  }

  private static List<RoutingEdge> outEdges(NetworkModel model, String className, String fromNode) {
    List<RoutingEdge> out = new ArrayList<>();
    List<RoutingEdge> edges = model.routing() == null ? List.of() : model.routing().getOrDefault(className, List.of());
    for (RoutingEdge e : edges) {
      if (e.from().equals(fromNode)) {
        out.add(e);
      }
    }
    return out;
  }

  private void writeConnections(Element sim, NetworkModel model) {
    // Distinct (from,to) pairs across all classes — connections are class-agnostic in JSIMG.
    // An edge leaving a fork-join node actually leaves the join station (see writeForkJoin);
    // an edge into a fork-join node still targets the fork node, which keeps the domain name.
    var forkJoinNames = new java.util.HashSet<String>();
    for (Node n : model.nodes()) {
      if (n instanceof ForkJoinNode) {
        forkJoinNames.add(n.name());
      }
    }
    var seen = new java.util.LinkedHashSet<String>();
    // Internal fork -> branch -> join connections for each fork-join node.
    for (Node n : model.nodes()) {
      if (n instanceof ForkJoinNode fj) {
        for (int i = 0; i < fj.branches().size(); i++) {
          String branchName = branchStationName(fj.name(), i);
          if (seen.add(fj.name() + "->" + branchName)) {
            Xml.child(sim, "connection", "source", fj.name(), "target", branchName);
          }
          if (seen.add(branchName + "->" + joinStationName(fj.name()))) {
            Xml.child(sim, "connection", "source", branchName, "target", joinStationName(fj.name()));
          }
        }
      }
    }
    if (model.routing() != null) {
      for (List<RoutingEdge> edges : model.routing().values()) {
        for (RoutingEdge e : edges) {
          String source = forkJoinNames.contains(e.from()) ? joinStationName(e.from()) : e.from();
          if (seen.add(source + "->" + e.to())) {
            Xml.child(sim, "connection", "source", source, "target", e.to());
          }
        }
      }
    }
  }

  // ---- measures ------------------------------------------------------------

  private void writeMeasure(Element sim, NetworkModel model, MeasureSpec m, Stopping stopping) {
    // The attribute carries the SIGNIFICANCE level (a tail probability), not the confidence level
    // (issue #12). JMT feeds it straight to TStudent.ICDF, whose contract is "significance in,
    // two-sided critical value out" — it is exact for 0.01/0.05/0.10/0.20. Handed 1 - alpha instead,
    // it falls onto a path returning the NEGATED one-sided quantile, which makes confInt negative;
    // NewDynamicDataAnalyzer.HWtest then compares `precision > confInt / extMean` with no absolute
    // value, so the stopping rule passes unconditionally and every interval comes back inverted and
    // ~17% too narrow. Do not "restore" the 1 - alpha conversion here.
    String alpha = stopping == null || stopping.alpha() == null ? "0.01" : stopping.alpha().toString();
    String precision = stopping == null || stopping.precision() == null ? "0.03" : stopping.precision().toString();
    Xml.child(sim, "measure",
        "name", m.name(),
        "type", m.jmtType(),
        "referenceNode", expandedMeasureNode(model, m),
        "referenceUserClass", m.referenceUserClass(),
        "nodeType", m.nodeType(),
        "alpha", alpha,
        "precision", precision,
        "verbose", m.verbose() ? "true" : "false");
  }

  /**
   * A measure whose {@code referenceNode} names a domain fork-join node must be remapped to the
   * join station: {@code MeasureMapper} (Task 6, unchanged) has no notion of the writer's internal
   * fork/branch/join expansion and sets {@code referenceNode} to the fork-join's domain name, which
   * is the JSIMG fork node — the *entry* of the fork-join, not its exit. Confined to the writer per
   * the brief's design decision so Task 6 stays agnostic of Task 8's expansion.
   *
   * <p>{@link MeasureMapper#FORK_ANCHORED_TYPES} are the exception: JMT's dedicated fork-join
   * measures are collected from the job list the *fork* station's input section maintains between
   * fork and join, so remapping them onto the join station would silently measure the wrong thing
   * (issue #6). Those stay on the domain name, which is already the fork station; that the node
   * really is a fork-join has been established by {@link #checkMeasures} before any writing starts.
   * Not every anchored type is fork-join-only — see {@code MeasureMapper.FORK_ANCHORED_TYPES} for why
   * {@code Arrival Rate} is anchored without being restricted.
   */
  private static String expandedMeasureNode(NetworkModel model, MeasureSpec m) {
    if (MeasureMapper.FORK_ANCHORED_TYPES.contains(m.jmtType())) {
      return m.referenceNode();
    }
    for (Node n : model.nodes()) {
      if (n instanceof ForkJoinNode fj && fj.name().equals(m.referenceNode())) {
        return joinStationName(fj.name());
      }
    }
    return m.referenceNode();
  }

  /**
   * A fork-join measure type is only meaningful on a fork-join node: JMT collects it from the job
   * list the *fork* station's input section maintains between fork and join, and on any other
   * station would attach it to a never-filled list and report a zero-sample measure rather than
   * failing (issue #6). Checked up front — like {@link #checkBranchClassConsistency} — so a bad
   * spec cannot reach serialization and {@link #expandedMeasureNode} stays a pure mapping.
   */
  private static void checkMeasures(NetworkModel model, List<MeasureSpec> measures) {
    if (measures == null) {
      return;
    }
    List<String> details = new ArrayList<>();
    for (MeasureSpec m : measures) {
      if (MeasureMapper.FORK_JOIN_ONLY_TYPES.contains(m.jmtType())
          && !(nodeNamed(model, m.referenceNode()) instanceof ForkJoinNode)) {
        details.add("measure type '" + m.jmtType() + "' applies only to a fork-join node, but '"
            + m.referenceNode() + "' is not one");
      }
    }
    if (!details.isEmpty()) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE, details);
    }
  }

  /** The model node with this name, or {@code null} if the model has no such node. */
  private static Node nodeNamed(NetworkModel model, String name) {
    for (Node n : model.nodes()) {
      if (n.name().equals(name)) {
        return n;
      }
    }
    return null;
  }

  // ---- XSD validation ------------------------------------------------------

  /**
   * Attributes the engine's {@code SimLoader} reads off {@code <sim>} but the bundled
   * {@code SIMmodeldefinition.xsd} does not declare, so XSD validation would reject them.
   *
   * <p>{@code minSamples} is the case in point (issue #10): {@code SimLoader} pulls it straight off
   * the element via {@code getAttribute("minSamples")} and pushes it into
   * {@code SimParameters.setMinSamples}, right alongside {@code maxSamples} — the schema is simply
   * stale relative to the loader. Patching the schema instead is not viable: it would mean forking
   * all six bundled XSDs (SIMmodeldefinition includes Archive, which includes three more) and
   * shadowing them off the classpath ahead of the jar.
   *
   * <p>Consequence worth knowing: the engine validates the model against that same stale schema
   * when it loads, so every run prints one non-fatal {@code [Error] ... Attribute 'minSamples' is
   * not allowed} line to stderr. The model loads and the floor is honoured regardless — see
   * {@code MinSamplesFloorTest}.
   */
  private static final Set<String> ENGINE_ONLY_SIM_ATTRS = Set.of("minSamples");

  public void validate(Document doc) {
    try {
      SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      // Resolve via the resource URL so the schema's relative <xs:include Archive.xsd>
      // (which defines the shared jdouble type) resolves within the JMT jar.
      var xsdUrl = JsimgWriter.class.getResource("/jmt/common/xml/SIMmodeldefinition.xsd");
      if (xsdUrl == null) {
        throw new IllegalStateException("SIMmodeldefinition.xsd not found on classpath (JMT jar missing?)");
      }
      Schema schema = sf.newSchema(new javax.xml.transform.stream.StreamSource(xsdUrl.toExternalForm()));
      Validator v = schema.newValidator();
      // Validate a copy with the engine-only attributes removed: everything else still faces the
      // full schema, and the caller's document keeps the attributes the engine needs. The service
      // always fills in minSamples, so the copy is taken on every request — unconditionally, since
      // a "is any present?" pre-scan would just be a check that is always true.
      Document toCheck = (Document) doc.cloneNode(true);
      Element checkedRoot = toCheck.getDocumentElement();
      if (checkedRoot != null) {
        for (String attr : ENGINE_ONLY_SIM_ATTRS) {
          // removeAttributeNS(null, ...) mirrors the setAttributeNS(null, ...) in Xml.child.
          checkedRoot.removeAttributeNS(null, attr);
        }
      }
      v.validate(new DOMSource(toCheck));
    } catch (org.xml.sax.SAXException e) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("generated JSIMG failed XSD validation: " + e.getMessage()));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("XSD validation I/O error", e);
    }
  }
}
