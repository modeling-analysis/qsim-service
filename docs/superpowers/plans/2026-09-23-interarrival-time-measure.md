# Interarrival-Time Measure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a per-station, per-class `interarrival-time` measure carrying `mean`, `variance` and `stdDev`, so a caller can compute the arrival-process SCV itself, and populate the `variance`/`stdDev` fields the response contract already promises.

**Architecture:** JMT already measures this — `"Arrival Rate"` is a station measure type whose raw samples *are* the interarrival times, and JMT writes `mean`/`variance`/`standardDeviation` for any measure marked `verbose="true"` in a terminal simulation, computed by re-reading a per-sample CSV it writes under `<sim logPath>`. So the work is: turn verbose on for the measures that need it, give each run a private temp directory for those CSVs and delete it afterwards, take the mean of an `Arrival Rate` measure from the raw-sample `mean` attribute rather than the rate-valued `meanValue`, and refuse to report second moments for the other rate-typed measures whose two attributes are in different units. Because the measure name becomes the CSV filename and node/class names come from request JSON, name validation lands first.

**Tech Stack:** Java 17, Maven, JUnit 5.10.2, Jackson 2.x (`jackson-databind`), JDK `com.sun.net.httpserver`, JMT 1.4.0 as a system-scope jar in `lib/`.

**Spec:** This plan implements [issue #15](https://github.com/modeling-analysis/qsim-service/issues/15) as revised by the feasibility assessment posted at [#15 (comment)](https://github.com/modeling-analysis/qsim-service/issues/15#issuecomment-5800584319), which is the design argument for every decision below and should be read alongside this plan. It also closes the second item of [issue #14](https://github.com/modeling-analysis/qsim-service/issues/14) (`variance`/`stdDev` always null — the gating condition is identified in the assessment). The service's overall contract is `docs/superpowers/specs/2026-07-25-qsim-service-design.md`.

**Branch:** `feat/interarrival-time-measure`. This checkout is shared by parallel sessions on stacked PRs — confirm the branch before the first commit, or work in a git worktree (`superpowers:using-git-worktrees`).

## Global Constraints

- Java 17 (`maven.compiler.release=17`). Build and test with `mvn`.
- **No new dependencies.** Jackson, JUnit and the bundled JMT jar are the whole stack.
- Every new `.java` file starts with the GPL v2+ header used by every existing source file (copy it verbatim from `src/main/java/qsim/model/MeasureResult.java`).
- `qsim.engine.JmtRunner` is the **only** class that may import `jmt.*` — the licensing/quarantine boundary. Nothing in this plan changes that.
- The response contract is `qsim.model.MeasureResult`; its field set does **not** change. No SCV field, no `cov_a` field. Callers compute `scv = variance / mean^2`.
- Existing behaviour must stay byte-identical for requests that do not opt in: with no `secondMoments` and no `interarrival-time`, the generated JSIMG must be unchanged and no temp log directory may be created.
- `<measure alpha>` is a significance level, never `1 - alpha` (issue #12). Do not touch that line.
- Station measures are per-(node, class) with `referenceUserClass` set, for every type. Do not add an aggregate mode.

## Review Focus

Five conditions the design implies that no task's happy path exercises. Each has a test pinned to the task that owns the code.

1. **`throughput` and `drop-rate` must report `variance: null` even when second moments are switched on** — JMT reports their mean as a rate while their verbose statistics describe inter-event times, so passing both through hands a caller doing `variance / mean^2` a number wrong by ~120x. Tests in Task 4 (parser) and Task 6 (end-to-end).
2. **A zero-sample verbose measure must not fail the request** — JMT logs `java.io.FileNotFoundException` at SEVERE from `XMLSimulationOutput.writeMeasure` and omits the statistics attributes; the run and every other measure survive, so the response must come back with nulls in that measure rather than a 500. Test in Task 5.
3. **The temp log directory must be removed on the failure path too**, not only after a clean run — an engine exception or a wall-clock cap must not leak a directory of per-sample CSVs, which JMT never deletes. Test in Task 5.
4. **`interarrival-time` on a source or sink node yields no measure, silently** — `servedClasses` returns empty for both, exactly as for every other station type. That is the consistent behaviour, but it is a surprise worth pinning so it cannot change by accident. Test in Task 3.
5. **Concurrent or successive runs must not share a log directory** — filenames come from measure names, so two runs of the same model would collide on the same CSV paths. Test in Task 5.

---

## File Structure

**Modified:**
- `src/main/java/qsim/contract/ContractValidator.java` — gains node/class name validation (Task 1).
- `src/main/java/qsim/translate/MeasureSpec.java` — gains a `verbose` component (Task 2).
- `src/main/java/qsim/translate/JsimgWriter.java` — emits `<sim logPath>` and per-measure `verbose`; splits fork-anchored from fork-join-only types (Tasks 2, 3).
- `src/main/java/qsim/translate/MeasureMapper.java` — registers `interarrival-time`; holds the JMT measure-type facts both the writer and the parser need (Tasks 2, 3).
- `src/main/java/qsim/result/SolutionsParser.java` — mean from the raw-sample attribute for `Arrival Rate`; suppresses second moments for rate-typed measures (Task 4).
- `src/main/java/qsim/model/SimulationRequest.java` — gains `secondMoments` (Task 5).
- `src/main/java/qsim/engine/JmtRunner.java` — per-run log directory create/delete (Task 5).
- `src/main/java/qsim/http/SimulationService.java` — wires the flag and the directory lifecycle (Task 5).
- `README.md`, `docs/superpowers/specs/2026-07-25-qsim-service-design.md` — documentation (Task 7).

**Created:**
- `src/test/java/qsim/translate/JsimgWriterVerboseTest.java` (Task 2)
- `src/test/java/qsim/engine/VerboseMeasureStatsTest.java` (Task 2)
- `src/test/resources/results/interarrival.solutions.xml` (Task 4)
- `src/test/java/qsim/golden/InterarrivalMomentsTest.java` (Task 6)

**Extended test files:** `ContractValidatorTest` (Task 1), `MeasureMapperTest` (Task 3), `SolutionsParserTest` (Task 4), `RequestBindingTest` and `SimulationServiceTest` (Task 5).

---

## Task 1: Reject node and class names that are unsafe as filenames

A measure's `name` is built from the node and class name (`MeasureMapper.map`: `n.name() + "_" + clazz + "_" + t`), and JMT writes a verbose measure's per-sample log to `<logPath>/<measure name>.csv`. `ContractValidator` validates servers, capacity, class anchoring, population and routing — but **no names at all**. Once Task 2 enables verbose logging, an unvalidated node name is a path, so this lands first and independently.

**Files:**
- Modify: `src/main/java/qsim/contract/ContractValidator.java`
- Test: `src/test/java/qsim/contract/ContractValidatorTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: the guarantee every later task relies on — a node or class name reaching `MeasureMapper` matches `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. No new public API.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/qsim/contract/ContractValidatorTest.java`:

```java
  /**
   * The measure name a node name ends up inside becomes a CSV filename once a measure is verbose
   * (issue #15), so a name carrying a path is a write outside the run's temp directory.
   */
  @Test
  void rejectsNodeNameThatWouldEscapeTheLogDirectory() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode("../../../../tmp/pwn", "queue", 1, "fcfs", null,
                Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "../../../../tmp/pwn", null),
                              new RoutingEdge("../../../../tmp/pwn", "snk", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("node name") && s.contains("pwn")));
  }

  @Test
  void rejectsClassNameWithASeparatorOrDotSegment() {
    for (String bad : List.of("web/x", "web\\x", "..", ".hidden", "a b")) {
      NetworkModel m = new NetworkModel("bad",
          List.of(new JobClass(bad, "open", null, null)),
          List.of(
              new SourceNode("src", "source", Map.of(bad, new ArrivalSpec(exp(1.0)))),
              new QueueNode("q", "queue", 1, "fcfs", null, Map.of(bad, new ServiceSpec(exp(2.0)))),
              new SinkNode("snk", "sink")),
          Map.of(bad, List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
      ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)),
          "must reject class name '" + bad + "'");
      assertTrue(ex.details().stream().anyMatch(s -> s.contains("class name")),
          "details must name the offending class for '" + bad + "': " + ex.details());
    }
  }

  @Test
  void rejectsNameLongerThan64Characters() {
    String tooLong = "q".repeat(65);
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode(tooLong, "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", tooLong, null),
                              new RoutingEdge(tooLong, "snk", null))));
    assertThrows(ValidationException.class, () -> validator.validate(req(m)));
  }

  /** The names every fixture and example in the repo actually uses must keep working. */
  @Test
  void acceptsOrdinaryIdentifierNames() {
    for (String ok : List.of("q", "src", "snk", "web", "machine-repairmen", "q_2", "Q.1", "fj")) {
      NetworkModel m = new NetworkModel("ok",
          List.of(new JobClass("web", "open", null, null)),
          List.of(
              new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
              new QueueNode(ok, "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
              new SinkNode("snk", "sink")),
          Map.of("web", List.of(new RoutingEdge("src", ok, null), new RoutingEdge(ok, "snk", null))));
      assertDoesNotThrow(() -> validator.validate(req(m)), "must accept node name '" + ok + "'");
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=ContractValidatorTest`
Expected: FAIL — `rejectsNodeNameThatWouldEscapeTheLogDirectory`, `rejectsClassNameWithASeparatorOrDotSegment` and `rejectsNameLongerThan64Characters` fail because no `ValidationException` is thrown at all. `acceptsOrdinaryIdentifierNames` already passes.

- [ ] **Step 3: Implement the name check**

In `src/main/java/qsim/contract/ContractValidator.java`, add the import `java.util.regex.Pattern` next to the existing `java.util.*` imports, then add the constant below `EPS`:

```java
  /**
   * Node and class names a caller may send. Deliberately narrow, because these names are not only
   * XML attribute values and routing-map keys — they become *filenames*. A measure's {@code name} is
   * built from the node and class name ({@code MeasureMapper.map}), and JMT writes a verbose
   * measure's per-sample log to {@code <logPath>/<measure name>.csv} (issue #15), so a name holding a
   * path separator or a {@code ..} segment is a write outside the run's temp directory. Requiring the
   * first character to be alphanumeric excludes {@code .} and {@code ..} without a special case, and
   * the 64-character cap keeps the composed measure name inside every filesystem's limit.
   *
   * <p>Every node and class name in the repo's fixtures and examples is a plain lowercase
   * identifier, so this tightens the contract without breaking a caller we know about — it is a
   * documented contract change nonetheless (README).
   */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
```

Add the checks as the **first** thing after the null guard in `validate`, before `nodeNames` is computed, so an unusable name is reported ahead of errors derived from it:

```java
    for (JobClass c : model.classes()) {
      checkName(errors, "class name", c.name());
    }
    for (Node n : model.nodes()) {
      checkName(errors, "node name", n.name());
    }
```

And the helper, next to the other private members at the end of the class:

```java
  private static void checkName(List<String> errors, String kind, String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      errors.add(kind + " " + (name == null ? "(null)" : "'" + name + "'")
          + " is not allowed: use 1-64 characters from [A-Za-z0-9._-], starting with a letter or "
          + "digit. These names become per-measure log filenames, so path separators, spaces and "
          + "'.'/'..' segments are rejected.");
    }
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=ContractValidatorTest`
Expected: PASS.

- [ ] **Step 5: Run the whole suite — this tightens a shared contract**

Run: `mvn -q test`
Expected: PASS. Any failure here is a fixture or test model using a name the pattern rejects; fix the name in that fixture rather than widening the pattern, and say which fixture changed in the commit message.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/contract/ContractValidator.java src/test/java/qsim/contract/ContractValidatorTest.java
git commit -m "fix: reject node and class names that are unsafe as measure log filenames

Measure names are composed from node and class names, and JMT writes a verbose
measure's per-sample log to <logPath>/<measure name>.csv. ContractValidator
validated no names at all, so enabling verbose logging (#15) would have made an
unvalidated node name a path. Restricts both to [A-Za-z0-9._-], 1-64 characters,
starting alphanumeric.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Task 2: Emit `<sim logPath>` and per-measure `verbose`

JMT writes second moments only for a measure marked `verbose="true"` in a terminal simulation, and only when `<sim>` names a `logPath` to write the per-sample CSVs into. `JsimgWriter.writeMeasure` hardcodes `verbose="false"` and no `logPath` is ever emitted — which is why `variance` and `stdDev` are null for every measure today (issue #14, second item). This task gives the writer the ability; no measure asks for it yet.

**Files:**
- Modify: `src/main/java/qsim/translate/MeasureSpec.java`
- Modify: `src/main/java/qsim/translate/JsimgWriter.java:38-48` (overloads and `<sim>` attributes), `:562-581` (`writeMeasure`)
- Test: `src/test/java/qsim/translate/JsimgWriterVerboseTest.java` (create)
- Test: `src/test/java/qsim/engine/VerboseMeasureStatsTest.java` (create)

**Interfaces:**
- Consumes: Task 1's name guarantee.
- Produces:
  - `MeasureSpec(String name, String jmtType, String referenceNode, String referenceUserClass, String nodeType, boolean verbose)` — canonical constructor; plus a 5-argument constructor defaulting `verbose` to `false`.
  - `JsimgWriter.toDocument(NetworkModel, Stopping, long, List<MeasureSpec>, String logPath)` and `JsimgWriter.toXmlString(..., String logPath)`; the existing 4-argument forms delegate with `logPath = null`.

- [ ] **Step 1: Write the failing writer test**

Create `src/test/java/qsim/translate/JsimgWriterVerboseTest.java` (GPL header first, copied verbatim from `MeasureResult.java`):

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=JsimgWriterVerboseTest`
Expected: FAIL to compile — no 6-argument `MeasureSpec` constructor and no 5-argument `toDocument`.

- [ ] **Step 3: Add the `verbose` component to `MeasureSpec`**

Replace the body of `src/main/java/qsim/translate/MeasureSpec.java` (keep the GPL header and `package` line):

```java
/**
 * One JMT {@code <measure>} to request.
 *
 * @param verbose whether JMT should log this measure's individual samples so it reports second
 *     moments for it. JMT computes {@code mean}/{@code variance}/{@code standardDeviation} by
 *     re-reading the per-sample CSV it writes under {@code <sim logPath>}, and only for verbose
 *     measures in a terminal simulation — so this flag is what makes the response's
 *     {@code variance}/{@code stdDev} fields non-null (issues #14, #15). It costs roughly 40 bytes
 *     of temp file per sample and 25-30% wall clock, which is why it is per measure rather than
 *     global.
 */
public record MeasureSpec(String name, String jmtType, String referenceNode,
                          String referenceUserClass, String nodeType, boolean verbose) {

  /** A measure with no per-sample logging — the shape every measure had before issue #15. */
  public MeasureSpec(String name, String jmtType, String referenceNode,
                     String referenceUserClass, String nodeType) {
    this(name, jmtType, referenceNode, referenceUserClass, nodeType, false);
  }
}
```

- [ ] **Step 4: Emit `logPath` and per-measure `verbose` in the writer**

In `src/main/java/qsim/translate/JsimgWriter.java`, replace the two entry points at lines 38-48 with delegating overloads:

```java
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
```

Then in the `Xml.child(doc, "sim", ...)` call add these four attributes after `"polling", "1.0"` (`Xml.child` drops a pair whose value is null, so a null `logPath` omits all four):

```java
        "logPath", logPath,
        // The CSV dialect StatisticalOutputsLoader reads back. Written explicitly rather than left
        // to the schema's defaults so the samples JMT parses are the samples it wrote.
        "logDelimiter", logPath == null ? null : ",",
        "logDecimalSeparator", logPath == null ? null : ".",
        "logReplaceMode", logPath == null ? null : "0");
```

Finally, in `writeMeasure` (line 562) replace the hardcoded verbose attribute:

```java
        "verbose", m.verbose() ? "true" : "false");
```

- [ ] **Step 5: Run the writer test to verify it passes**

Run: `mvn -q test -Dtest=JsimgWriterVerboseTest`
Expected: PASS.

- [ ] **Step 6: Write the engine test that proves JMT actually reports the moments**

The writer test proves the XML shape. Only a real run proves the mechanism. Create `src/test/java/qsim/engine/VerboseMeasureStatsTest.java` (GPL header first):

```java
package qsim.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.model.*;
import qsim.translate.JsimgWriter;
import qsim.translate.MeasureSpec;

/**
 * Gates the mechanism behind issue #14's second item: {@code variance} and {@code stdDev} were
 * always null not because the parser missed them but because nothing in the document asked JMT for
 * them. {@code XMLSimulationOutput.writeMeasure} writes the second moments only for a measure whose
 * verbose output exists and only in a terminal simulation, computing them by re-reading the
 * per-sample CSV. Both halves are asserted here, in one run each way, so a regression in either the
 * {@code verbose} attribute or the {@code logPath} shows up as a missing attribute.
 */
class VerboseMeasureStatsTest {

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private NetworkModel mm1() {
    return new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  /** Loose precision and a modest floor: this test is about attributes, not convergence. */
  private Stopping stopping() {
    return new Stopping(0.05, 0.10, 5_000, 200_000, null, null, 120, false);
  }

  private String runAndReadOutput(boolean verbose, Path logDir) throws Exception {
    List<MeasureSpec> specs = List.of(
        new MeasureSpec("q_web_response-time", "Response Time", "q", "web", "station", verbose));
    String xml = new JsimgWriter().toXmlString(mm1(), stopping(), 4242L, specs,
        logDir == null ? null : logDir.toString());
    JmtRunner runner = new JmtRunner();
    RunResult result = runner.run(xml, 4242L, 120, /* terminal */ true);
    try {
      return Files.readString(result.outputFile().toPath());
    } finally {
      runner.cleanup(result);
    }
  }

  @Test
  void aVerboseMeasureReportsVarianceAndStandardDeviation() throws Exception {
    Path logDir = Files.createTempDirectory("qsim-verbose-test-");
    try {
      String out = runAndReadOutput(true, logDir);
      assertTrue(out.contains("variance=\""), "verbose measure must report variance: " + out);
      assertTrue(out.contains("standardDeviation=\""),
          "verbose measure must report standardDeviation: " + out);
      // JMT writes one CSV per verbose measure, named after the measure, and never deletes it.
      assertTrue(Files.exists(logDir.resolve("q_web_response-time.csv")),
          "JMT must write the per-sample log named after the measure");
    } finally {
      try (var paths = Files.walk(logDir)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @Test
  void aNonVerboseMeasureReportsNeither() throws Exception {
    String out = runAndReadOutput(false, null);
    assertFalse(out.contains("variance=\""),
        "a non-verbose measure must not report variance (this is why #14's fields were null): " + out);
  }
}
```

- [ ] **Step 7: Run the engine test**

Run: `mvn -q test -Dtest=VerboseMeasureStatsTest`
Expected: PASS. Both runs take a few seconds. `aVerboseMeasureReportsVarianceAndStandardDeviation` failing with no `variance` attribute means the `verbose` or `logPath` attribute is not reaching the engine.

- [ ] **Step 8: Run the whole suite**

Run: `mvn -q test`
Expected: PASS — the 5-argument `MeasureSpec` constructor keeps every existing call compiling.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/qsim/translate/MeasureSpec.java src/main/java/qsim/translate/JsimgWriter.java src/test/java/qsim/translate/JsimgWriterVerboseTest.java src/test/java/qsim/engine/VerboseMeasureStatsTest.java
git commit -m "feat: let the writer request per-measure verbose sample logging

JMT reports a measure's second moments only for measures marked verbose=\"true\"
in a terminal simulation, computed by re-reading a per-sample CSV written under
<sim logPath>. JsimgWriter hardcoded verbose=\"false\" and emitted no logPath,
which is why variance and stdDev have always been null (#14). MeasureSpec now
carries the flag and the writer emits both attributes; nothing requests it yet.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Task 3: Register `interarrival-time`, anchored on the fork station

`"Arrival Rate"` is a real JMT station measure type whose raw samples are the interarrival times themselves (`LinkedJobInfoList.updateArrivalRate` records `getTime() - getLastJobInTime()` on every arrival, per class when `referenceUserClass` is set). It needs verbose logging to report anything usable, and it needs to stay anchored on the fork station inside a fork-join expansion, where the join sees one arrival per sibling branch rather than one per job.

`MeasureMapper.FORK_JOIN_TYPES` currently means two things at once — "stays on the fork station" (`JsimgWriter.expandedMeasureNode`) and "is valid only on a fork-join node" (`JsimgWriter.checkMeasures`). `"Arrival Rate"` needs the first and not the second, so the two meanings split.

**Files:**
- Modify: `src/main/java/qsim/translate/MeasureMapper.java`
- Modify: `src/main/java/qsim/translate/JsimgWriter.java:596-614` (`expandedMeasureNode`), `:615-632` (`checkMeasures`)
- Test: `src/test/java/qsim/translate/MeasureMapperTest.java`

**Interfaces:**
- Consumes: `MeasureSpec`'s 6-argument constructor (Task 2).
- Produces:
  - `"interarrival-time"` in `MeasureMapper.SUPPORTED`, mapping to JMT `"Arrival Rate"`, emitted with `verbose = true`.
  - `MeasureMapper.RAW_SAMPLE_MEAN` — `public static final Set<String>` of JMT type strings whose `MeasureResult.mean` must come from the raw-sample `mean` attribute. Task 4 reads it.
  - `MeasureMapper.RATE_TYPED` — `public static final Set<String>` of JMT type strings whose reported mean is a rate while their verbose statistics are inter-event times. Task 4 reads it.
  - `MeasureMapper.withVerbose(List<MeasureSpec>)` — every spec with `verbose` forced on except the `RATE_TYPED` ones. Task 5 calls it.
  - `MeasureMapper.FORK_ANCHORED_TYPES` and `MeasureMapper.FORK_JOIN_ONLY_TYPES` (package-private) replace `FORK_JOIN_TYPES`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/qsim/translate/MeasureMapperTest.java`:

```java
  @Test
  void interarrivalTimeMapsToArrivalRatePerClassAndAsksForSampleLogging() {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));

    List<MeasureSpec> specs = new MeasureMapper().map(m, List.of("interarrival-time"));

    // Source and sink serve no classes, so only the queue yields a measure - same as every type.
    assertEquals(1, specs.size());
    MeasureSpec s = specs.get(0);
    assertEquals("q_web_interarrival-time", s.name());
    assertEquals("Arrival Rate", s.jmtType());
    assertEquals("q", s.referenceNode());
    assertEquals("web", s.referenceUserClass());  // per-class, like every station measure
    assertEquals("station", s.nodeType());
    assertTrue(s.verbose(), "the interarrival moments only exist when JMT logs the samples");
  }

  /**
   * Review Focus 4: a source or sink yields nothing, silently, exactly as for response-time. Pinned
   * so the behaviour cannot drift into a surprise.
   */
  @Test
  void interarrivalTimeYieldsNoMeasureForSourcesOrSinks() {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "snk", null))));

    assertEquals(List.of(), new MeasureMapper().map(m, List.of("interarrival-time")));
  }

  @Test
  void withVerboseTurnsLoggingOnExceptForRateTypedMeasures() {
    List<MeasureSpec> in = List.of(
        new MeasureSpec("q_web_response-time", "Response Time", "q", "web", "station", false),
        new MeasureSpec("q_web_throughput", "Throughput", "q", "web", "station", false),
        new MeasureSpec("q_web_drop-rate", "Drop Rate", "q", "web", "station", false));

    List<MeasureSpec> out = MeasureMapper.withVerbose(in);

    assertTrue(out.get(0).verbose(), "response time's moments are reportable");
    // Their mean is a rate while their samples are inter-event times: the moments would be
    // suppressed by the parser anyway, so logging them is pure cost.
    assertFalse(out.get(1).verbose(), "throughput is rate-typed");
    assertFalse(out.get(2).verbose(), "drop rate is rate-typed");
    assertEquals("q_web_throughput", out.get(1).name(), "withVerbose must not alter anything else");
  }

  @Test
  void interarrivalTimeIsNotADefaultMeasure() {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.5)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));

    assertTrue(new MeasureMapper().map(m, null).stream().noneMatch(s -> s.verbose()),
        "sample logging costs disk and wall clock; it must be opted into, never defaulted");
  }
```

Make sure `MeasureMapperTest` imports `assertFalse` and `assertTrue` from `org.junit.jupiter.api.Assertions`; add whichever is missing.

Add to `src/test/java/qsim/translate/JsimgWriterClosedForkTest.java`:

```java
  /**
   * An arrival measure inside a fork-join expansion must stay on the fork station. The join's job
   * list is fed one arrival per sibling branch rather than one per job, so an interarrival time
   * taken there is the inter-sibling gap, not the gap between jobs entering the fork-join (measured
   * 0.248 against the fork's 0.083 on three branches). Unlike "Fork Join Response Time" this type is
   * perfectly valid on an ordinary station, which is why the writer's fork-anchored set is wider
   * than its fork-join-only set.
   */
  @Test
  void anArrivalRateMeasureOnAForkJoinStaysOnTheForkStation() {
    NetworkModel m = forkNet();   // the fork model the other tests in this class use
    List<MeasureSpec> specs = new MeasureMapper().map(m, List.of("interarrival-time"));
    String xml = new JsimgWriter().toXmlString(m, null, 7L, specs, "/tmp/qsim-logs-test");

    assertTrue(xml.contains("type=\"Arrival Rate\""), xml);
    assertTrue(xml.contains("referenceNode=\"fj\""),
        "the arrival measure must stay on the fork station, not fj__join: " + xml);
    assertFalse(xml.contains("referenceNode=\"fj__join\" type=\"Arrival Rate\""), xml);
  }
```

`forkNet()` is the private helper the class's other fork tests already use — reuse it rather than building a second fork model. The class does not currently import `assertFalse`; add `import static org.junit.jupiter.api.Assertions.assertFalse;`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=MeasureMapperTest+JsimgWriterClosedForkTest`
Expected: FAIL — `interarrival-time` is rejected as an unsupported measure type, and `MeasureMapper.withVerbose` does not exist.

- [ ] **Step 3: Register the type and split the two fork-join meanings**

In `src/main/java/qsim/translate/MeasureMapper.java`, add to the `static` initializer alongside the other station types:

```java
    STATION.put("interarrival-time", "Arrival Rate");
```

Replace the `FORK_JOIN_TYPES` declaration with the two sets, and add the type facts the parser needs. Note `DEFAULTS` is deliberately unchanged — sample logging is opt-in:

```java
  /**
   * JMT measure types {@link JsimgWriter} must leave anchored on the fork station instead of
   * remapping onto the internal join station.
   *
   * <p>Two different reasons put a type here. {@link #FORK_JOIN_STATION}'s measures are collected
   * from the job list the *fork* station's input section maintains between fork and join, so the
   * join is simply the wrong anchor and the type is meaningless anywhere else (issue #6).
   * {@code "Arrival Rate"} is anchored for an unrelated reason: it is valid on any station, but the
   * join's job list is fed one arrival per sibling branch rather than one per job, so an
   * interarrival time taken there is the inter-sibling gap (measured 0.248 against the fork's 0.083
   * on three branches) rather than the gap between jobs entering the fork-join. Hence the split from
   * {@link #FORK_JOIN_ONLY_TYPES}: everything here stays on the fork, but only those are *rejected*
   * elsewhere.
   */
  static final Set<String> FORK_ANCHORED_TYPES;

  /** Types meaningful only on a fork-join node; {@link JsimgWriter} rejects them on any other. */
  static final Set<String> FORK_JOIN_ONLY_TYPES = Set.copyOf(FORK_JOIN_STATION.values());

  static {
    var anchored = new java.util.HashSet<>(FORK_JOIN_ONLY_TYPES);
    anchored.add("Arrival Rate");
    FORK_ANCHORED_TYPES = Set.copyOf(anchored);
  }

  /**
   * JMT measure types whose {@code MeasureResult.mean} must be read from the output document's
   * raw-sample {@code mean} attribute rather than {@code meanValue}.
   *
   * <p>{@code "Arrival Rate"} is an {@code InverseMeasure}: {@code getMeanValue()} returns
   * {@code 1.0 / analyzer.getMean()}, so {@code meanValue} is a rate while the samples underneath —
   * and therefore the {@code mean}, {@code variance} and {@code standardDeviation} attributes — are
   * interarrival times. Reporting the rate as {@code mean} next to a variance over times would hand
   * a caller computing {@code variance / mean^2} an answer wrong by orders of magnitude, so qsim
   * reports the interarrival time itself and names the domain type after it. The arrival rate is
   * {@code 1 / mean}, and {@code throughput} already reports it directly.
   */
  public static final Set<String> RAW_SAMPLE_MEAN = Set.of("Arrival Rate");

  /**
   * JMT measure types qsim reports as a rate even though their verbose statistics describe the
   * inter-event times between the events being counted — the same {@code InverseMeasure} mismatch as
   * {@link #RAW_SAMPLE_MEAN}, but for types whose rate is the figure a caller wants. Their second
   * moments are in the wrong units to sit beside that mean (a measured rate of 0.30088 with a
   * variance of 10.747 gives an SCV of 118.7 against a true 0.961), so
   * {@link qsim.result.SolutionsParser} drops them and {@link #withVerbose} does not pay to log
   * them. Mirrors {@code EngineUtils.isInverseMeasure} for the subset qsim emits; exposing these
   * moments properly would mean a separate {@code interdeparture-time} type.
   */
  public static final Set<String> RATE_TYPED = Set.of("Throughput", "Drop Rate");

  /** Domain measure types whose figures exist only when JMT logs the individual samples. */
  private static final Set<String> REQUIRES_VERBOSE = Set.of("interarrival-time");
```

Set the flag when building station specs — replace the `specs.add(...)` call inside the station branch of `map`:

```java
            specs.add(new MeasureSpec(n.name() + "_" + clazz + "_" + t, jmtForNode,
                n.name(), clazz, "station", REQUIRES_VERBOSE.contains(t)));
```

And add `withVerbose` as a public static method after `map`:

```java
  /**
   * Every spec with per-sample logging switched on, except {@link #RATE_TYPED} measures whose second
   * moments the parser discards anyway. Backs the request-level {@code secondMoments} opt-in: JMT's
   * verbose output is per measure, so turning it on globally is a decision about which specs carry
   * the flag rather than a separate switch.
   */
  public static List<MeasureSpec> withVerbose(List<MeasureSpec> specs) {
    List<MeasureSpec> out = new ArrayList<>(specs.size());
    for (MeasureSpec s : specs) {
      out.add(new MeasureSpec(s.name(), s.jmtType(), s.referenceNode(), s.referenceUserClass(),
          s.nodeType(), !RATE_TYPED.contains(s.jmtType())));
    }
    return out;
  }
```

In `src/main/java/qsim/translate/JsimgWriter.java`, point each usage at the set that now carries its meaning — `expandedMeasureNode` (line ~597):

```java
    if (MeasureMapper.FORK_ANCHORED_TYPES.contains(m.jmtType())) {
```

and `checkMeasures` (line ~622):

```java
      if (MeasureMapper.FORK_JOIN_ONLY_TYPES.contains(m.jmtType())
```

Update the second paragraph of `expandedMeasureNode`'s Javadoc to name `FORK_ANCHORED_TYPES` instead of `FORK_JOIN_TYPES`, and add a sentence: "Not every anchored type is fork-join-only — see `MeasureMapper.FORK_ANCHORED_TYPES` for why `Arrival Rate` is anchored without being restricted."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=MeasureMapperTest+JsimgWriterClosedForkTest`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/translate/MeasureMapper.java src/main/java/qsim/translate/JsimgWriter.java src/test/java/qsim/translate/MeasureMapperTest.java src/test/java/qsim/translate/JsimgWriterClosedForkTest.java
git commit -m "feat: register interarrival-time as a station measure (#15)

Maps to JMT's \"Arrival Rate\", whose raw samples are the interarrival times
themselves, per class like every other station measure, with sample logging on
since the moments do not exist without it. Splits FORK_JOIN_TYPES into
FORK_ANCHORED_TYPES and FORK_JOIN_ONLY_TYPES: an arrival measure must stay on
the fork station, where the job list sees one arrival per job rather than the
join's one per sibling branch, but unlike the fork-join types it is valid on any
station. Also records which JMT types are rate-typed, for the parser.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Task 4: Parse the raw-sample moments, and refuse to mix units

JMT's output element for a verbose measure carries both `meanValue` (which for an `InverseMeasure` is `1.0 / analyzer.getMean()`, a rate) and `mean`/`variance`/`standardDeviation` (which are over the raw samples, i.e. inter-event times). The parser reads `meanValue` today. For `Arrival Rate` that would report a rate as the mean next to a variance over times — a caller computing `variance / mean^2` on the probe's numbers gets 118.7 instead of 0.961. So: `Arrival Rate` reports the raw-sample mean, and the other rate-typed measures report their rate with the second moments dropped.

**Files:**
- Modify: `src/main/java/qsim/result/SolutionsParser.java:34-46` (`REVERSE`), `:76-89` (result construction)
- Create: `src/test/resources/results/interarrival.solutions.xml`
- Test: `src/test/java/qsim/result/SolutionsParserTest.java`

**Interfaces:**
- Consumes: `MeasureMapper.RAW_SAMPLE_MEAN` and `MeasureMapper.RATE_TYPED` (Task 3).
- Produces: no new API. Behaviour: an `Arrival Rate` measure parses to `type: "interarrival-time"` with `mean` from the `mean` attribute and `lower`/`upper` null; a `Throughput` or `Drop Rate` measure parses with `variance`/`stdDev` null whatever the document says.

- [ ] **Step 1: Write the fixture**

Create `src/test/resources/results/interarrival.solutions.xml`. The two `measure` elements are real engine output, captured from the probe described in the issue assessment: `q1` is an `Arrival Rate` measure on an exponential arrival stream at rate 0.3 with verbose logging on, `q1`'s throughput is a rate-typed measure whose verbose statistics describe interdeparture times.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<solutions modelName="probe" solutionMethod="simulation">
  <measure alfa="0.05" analyzedSamples="10240" class="web"
    coefficientOfVariation="0.9803411447931643" discardedSamples="100"
    firstPowerMoment="3.3440541634186807" fourthPowerMoment="2651.990619119153"
    kurtosis="4.983622314497387" lowerLimit="0.2935860105446729" maxSamples="400000"
    maxValue="28.896790324830363" mean="3.3440541634186807" meanValue="0.30088256380549294"
    measureType="Arrival Rate" nodeType="station" precision="0.03"
    secondPowerMoment="21.930040188369862" skeweness="1.8750404301072088"
    standardDeviation="3.2783138868162167" station="q1" successful="true"
    upperLimit="0.3085510461620826" variance="10.74734194049205"/>
  <measure alfa="0.05" analyzedSamples="10240" class="web" discardedSamples="100"
    lowerLimit="0.2935860105446729" maxSamples="400000" mean="3.3440541634186807"
    meanValue="0.30088256380549294" measureType="Throughput" nodeType="station"
    precision="0.03" standardDeviation="3.2783138868162167" station="q1" successful="true"
    upperLimit="0.3085510461620826" variance="10.74734194049205"/>
</solutions>
```

- [ ] **Step 2: Write the failing tests**

Add to `src/test/java/qsim/result/SolutionsParserTest.java`:

```java
  /**
   * Issue #15. An "Arrival Rate" measure is an InverseMeasure: meanValue is 1/E[A] while the samples
   * underneath — and so the mean/variance/standardDeviation attributes — are the interarrival times
   * themselves. qsim reports the time, because that is the quantity the variance beside it describes;
   * a caller wanting the rate has throughput, or 1/mean.
   */
  @Test
  void anArrivalRateMeasureReportsTheInterarrivalTimeNotTheRate() throws Exception {
    SolutionsParser.Parsed p =
        new SolutionsParser().parse(resource("/results/interarrival.solutions.xml"));

    MeasureResult ia = p.measures().get(0);
    assertEquals("interarrival-time", ia.type());
    assertEquals("q1", ia.station());
    assertEquals("web", ia.jobClass());
    assertEquals(3.3440541634186807, ia.mean());          // the `mean` attribute, not meanValue
    assertEquals(10.74734194049205, ia.variance());
    assertEquals(3.2783138868162167, ia.stdDev());
    assertEquals(10240, ia.samplesAnalyzed());
    assertEquals(100, ia.samplesDiscarded());
    assertTrue(ia.success());

    // scv = variance / mean^2 is the caller's to compute, and now it is consistent: reporting
    // meanValue here would make this 118.7 rather than 0.961.
    assertEquals(0.961, ia.variance() / (ia.mean() * ia.mean()), 1e-3);
  }

  /**
   * JMT's confidence interval on an arrival measure is computed on the rate, so it does not bracket
   * the mean qsim reports. Issue #15 accepts a mean with no interval, so the limits are dropped
   * rather than transformed — an interval that does not contain its own point estimate is worse than
   * no interval. (The reciprocal transform is noted as future work in this plan.)
   */
  @Test
  void anArrivalRateMeasureReportsNoConfidenceInterval() throws Exception {
    SolutionsParser.Parsed p =
        new SolutionsParser().parse(resource("/results/interarrival.solutions.xml"));
    MeasureResult ia = p.measures().get(0);
    assertNull(ia.lower(), "the CI is on the rate; it would not bracket the interarrival mean");
    assertNull(ia.upper());
    assertEquals(0.05, ia.alpha(), "alpha and precision still describe the run");
    assertEquals(0.03, ia.precision());
  }

  /**
   * Review Focus 1. Throughput's mean is a rate (correctly — that is the figure callers want) but its
   * verbose statistics are over interdeparture times, so the two cannot sit side by side: a caller
   * computing variance/mean^2 would get 118.7 against a true 0.961. Dropping them is the honest
   * answer; reporting interdeparture moments means a separate domain type.
   */
  @Test
  void rateTypedMeasuresReportNoSecondMoments() throws Exception {
    SolutionsParser.Parsed p =
        new SolutionsParser().parse(resource("/results/interarrival.solutions.xml"));

    MeasureResult tp = p.measures().get(1);
    assertEquals("throughput", tp.type());
    assertEquals(0.30088256380549294, tp.mean(), "throughput stays a rate");
    assertNull(tp.variance(), "variance is over interdeparture times, not over the rate");
    assertNull(tp.stdDev());
    assertEquals(0.2935860105446729, tp.lower(), "its CI *is* on the rate, so it is kept");
    assertEquals(0.3085510461620826, tp.upper());
  }
```

Add `import static org.junit.jupiter.api.Assertions.assertNull;` to the test file.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=SolutionsParserTest`
Expected: FAIL — `interarrival-time` comes back as the raw string `"Arrival Rate"`, `mean` is `0.30088…`, the limits are populated, and throughput's variance is `10.747…`.

- [ ] **Step 4: Implement**

In `src/main/java/qsim/result/SolutionsParser.java`, add the reverse entry inside `REVERSE`:

```java
      Map.entry("Arrival Rate", "interarrival-time"),
```

Add `import qsim.translate.MeasureMapper;` — the parser needs the same JMT measure-type facts the mapper holds, and one owner of those facts beats two copies drifting apart.

Then replace the result construction inside the loop (keeping the issue #12 comment block above `lower`/`upper` intact):

```java
        String jmtType = m.getAttribute("measureType");

        // Which attribute holds the mean depends on the measure. For an InverseMeasure JMT reports
        // meanValue = 1/E[sample] while `mean`, `variance` and `standardDeviation` describe the raw
        // samples; for "Arrival Rate" the raw sample IS the quantity qsim reports, so the mean must
        // come from the sample-side attribute or it would not agree with the variance next to it
        // (issue #15). See MeasureMapper.RAW_SAMPLE_MEAN.
        boolean rawSampleMean = MeasureMapper.RAW_SAMPLE_MEAN.contains(jmtType);
        Double mean = parseD(m.getAttribute(rawSampleMean ? "mean" : "meanValue"));

        // JMT's confidence interval is computed on whatever meanValue reports, so for a raw-sample
        // measure it brackets the rate rather than the mean above. Issue #15 explicitly accepts a
        // mean with no interval; an interval that does not contain its point estimate would be worse
        // than none. A reciprocal transform is possible later (see the plan's deferred work).
        if (rawSampleMean) {
          lower = null;
          upper = null;
        }

        // The remaining rate-typed measures keep their rate as the mean — that is the figure callers
        // want — which leaves their second moments in the wrong units to sit beside it. Dropped
        // rather than converted; exposing interdeparture moments needs its own domain type.
        boolean rateTyped = MeasureMapper.RATE_TYPED.contains(jmtType);
        Double variance = rateTyped ? null : parseD(m.getAttribute("variance"));
        Double stdDev = rateTyped ? null : parseD(m.getAttribute("standardDeviation"));

        results.add(new MeasureResult(
            domainStation(m.getAttribute("station")),
            m.getAttribute("class"),
            REVERSE.getOrDefault(jmtType, jmtType),
            mean,
            lower,
            upper,
            parseD(m.getAttribute("alfa")),
            parseD(m.getAttribute("precision")),
            success,
            parseI(m.getAttribute("analyzedSamples")),
            parseI(m.getAttribute("discardedSamples")),
            variance,
            stdDev));
```

`lower` and `upper` are already local variables in this loop; leave their declarations where they are so the issue #12 comment still sits directly above them.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=SolutionsParserTest`
Expected: PASS, including the four pre-existing tests — `mm1.solutions.xml` has no `Arrival Rate` and no `variance` attributes, so nothing there changes.

- [ ] **Step 6: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/qsim/result/SolutionsParser.java src/test/java/qsim/result/SolutionsParserTest.java src/test/resources/results/interarrival.solutions.xml
git commit -m "feat: parse interarrival moments; drop rate-typed second moments (#15)

An Arrival Rate measure reports meanValue as a rate while its mean, variance and
standardDeviation describe the raw interarrival samples. The parser now reads the
sample-side mean for it, so the reported mean and variance are in the same units
(variance/mean^2 gives 0.961 rather than 118.7), and drops its confidence
interval, which JMT computed on the rate. Throughput and drop rate keep their
rate and lose their second moments for the same unit mismatch.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Task 5: Wire `secondMoments` and a per-run log directory through the service

Everything above is inert until a request can ask for it and a run has somewhere to put the CSVs. JMT never deletes those files, so the service owns the directory's whole lifecycle — including on the failure path.

**Files:**
- Modify: `src/main/java/qsim/model/SimulationRequest.java`
- Modify: `src/main/java/qsim/engine/JmtRunner.java`
- Modify: `src/main/java/qsim/http/SimulationService.java`
- Test: `src/test/java/qsim/model/RequestBindingTest.java`
- Test: `src/test/java/qsim/http/SimulationServiceTest.java`

**Interfaces:**
- Consumes: `MeasureMapper.withVerbose` (Task 3), `JsimgWriter.toXmlString(..., String logPath)` (Task 2).
- Produces:
  - `SimulationRequest(NetworkModel model, Long seed, Stopping stopping, List<String> measures, Boolean secondMoments)` canonical; a 4-argument constructor keeps every existing call site and passes `null`.
  - `JmtRunner.createLogDir(String tempDir)` → `Path`, and `JmtRunner.deleteLogDir(Path)` (null-tolerant, never throws).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/qsim/model/RequestBindingTest.java`:

```java
  @Test
  void bindsSecondMomentsFlag() throws Exception {
    String json = """
        {"model":{"name":"m","classes":[],"nodes":[],"routing":{}},
         "measures":["interarrival-time"],"secondMoments":true}
        """;
    SimulationRequest req = new ObjectMapper().readValue(json, SimulationRequest.class);
    assertEquals(Boolean.TRUE, req.secondMoments());
    assertEquals(List.of("interarrival-time"), req.measures());
  }

  @Test
  void secondMomentsIsNullWhenAbsent() throws Exception {
    String json = """
        {"model":{"name":"m","classes":[],"nodes":[],"routing":{}}}
        """;
    assertNull(new ObjectMapper().readValue(json, SimulationRequest.class).secondMoments());
  }
```

Match the file's existing imports and JSON-fixture style; if it constructs its `ObjectMapper` in a field, reuse that field instead of a new instance.

Add to `src/test/java/qsim/http/SimulationServiceTest.java`:

```java
  /**
   * Review Focus 3 and 5. JMT never deletes the per-sample CSVs it writes, so the service owns the
   * directory: a fresh one per run (measure names become filenames, so two runs of one model would
   * collide) and gone afterwards whether the run succeeded or blew up.
   */
  @Test
  void eachRunGetsItsOwnLogDirectoryAndItIsRemovedAfterwards() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);
    List<String> logPathsSeen = new ArrayList<>();

    // A runner stub that records the logPath it was handed and returns a canned output document.
    // Subclassing JmtRunner is how SimulationServiceTest already stubs the engine (see
    // rejectsBeforeInvokingTheEngine) — note the parameter is Integer, not int, or this does not
    // override anything and the real engine runs.
    JmtRunner runner = new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        logPathsSeen.add(logPathOf(xml));
        return cannedRunResult();
      }
    };
    SimulationService service = new SimulationService(config, runner);

    service.simulate(requestWith(/* secondMoments */ true));
    service.simulate(requestWith(/* secondMoments */ true));

    assertEquals(2, logPathsSeen.size());
    assertNotNull(logPathsSeen.get(0), "a verbose run must be given a log directory");
    assertNotEquals(logPathsSeen.get(0), logPathsSeen.get(1),
        "measure names become filenames, so two runs must not share a directory");
    for (String p : logPathsSeen) {
      assertFalse(Files.exists(Path.of(p)), "the log directory must be removed after the run: " + p);
    }
  }

  @Test
  void theLogDirectoryIsRemovedWhenTheEngineFails() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);

    JmtRunner runner = new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        throw new IllegalStateException("engine exploded");
      }
    };
    SimulationService service = new SimulationService(config, runner);

    assertThrows(IllegalStateException.class, () -> service.simulate(requestWith(true)));
    try (var entries = Files.list(temp)) {
      assertEquals(List.of(), entries.toList(), "no log directory may be left behind on failure");
    }
  }

  @Test
  void aRequestWithoutSecondMomentsGetsNoLogDirectory() throws Exception {
    Path temp = Files.createTempDirectory("qsim-service-test-");
    Config config = new Config(8080, temp.toString(), 0.05, 0.05, 1_000, 100_000, 120);
    List<String> logPathsSeen = new ArrayList<>();
    JmtRunner runner = new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        logPathsSeen.add(logPathOf(xml));
        return cannedRunResult();
      }
    };
    SimulationService service = new SimulationService(config, runner);

    // Default measures only: no verbose measure, so no directory and no disk cost for callers who
    // never asked for second moments.
    service.simulate(requestWith(/* secondMoments */ null));

    assertNull(logPathsSeen.get(0), "an ordinary request must not create a log directory");
    try (var entries = Files.list(temp)) {
      assertEquals(List.of(), entries.toList());
    }
  }

  /**
   * Review Focus 2. A verbose measure that collects no samples — drop rate on a lossless queue is the
   * easy case — makes JMT log java.io.FileNotFoundException at SEVERE from
   * XMLSimulationOutput.writeMeasure and omit the statistics attributes. The run and every other
   * measure survive, so this must come back as a measure with nulls, not a 500.
   */
  @Test
  void aZeroSampleVerboseMeasureDoesNotFailTheRequest() throws Exception {
    // Real engine and the real temp dir: the SEVERE-log path is JMT's, so a stub proves nothing.
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse res = service.simulate(
        new SimulationRequest(mm1(), 7L, looseStopping(), List.of("drop-rate", "response-time"), true));

    MeasureResult drop = res.measures().stream()
        .filter(m -> "drop-rate".equals(m.type())).findFirst().orElseThrow();
    assertNull(drop.variance(), "no samples, and rate-typed besides");
    assertNotNull(res.measures().stream()
        .filter(m -> "response-time".equals(m.type())).findFirst().orElseThrow().mean(),
        "the other measures must be unaffected");
  }
```

Three new private helpers; `mm1()` already exists in this class (it returns a `SimulationRequest`, so use `mm1().model()`), and `looseStopping()` should mirror whatever bounds helper the class already has.

```java
  /** The default measures, or interarrival-time, on the existing mm1 model. */
  private SimulationRequest requestWith(Boolean secondMoments) {
    return new SimulationRequest(mm1().model(), 42L, null, List.of("response-time"), secondMoments);
  }

  private String logPathOf(String xml) {
    Matcher m = Pattern.compile("logPath=\"([^\"]*)\"").matcher(xml);
    return m.find() ? m.group(1) : null;
  }

  /**
   * A real solutions document in a throwaway file. Copied rather than handed over directly, because
   * {@code JmtRunner.cleanup} deletes the output file and a test resource must survive the run.
   */
  private RunResult cannedRunResult() {
    try {
      Path out = Files.createTempFile("qsim-canned-", ".xml");
      try (var in = getClass().getResourceAsStream("/results/mm1.solutions.xml")) {
        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return new RunResult(out.toFile(), 0.1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
```

Add the imports these need: `java.nio.file.Files`, `java.nio.file.Path`, `java.util.ArrayList`, `java.util.regex.Matcher`, `java.util.regex.Pattern`, and the static `assertNotNull`, `assertNotEquals` and `assertNull`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=RequestBindingTest+SimulationServiceTest`
Expected: FAIL to compile — `secondMoments()` does not exist and there is no 5-argument `SimulationRequest`.

- [ ] **Step 3: Add `secondMoments` to the request**

In `src/main/java/qsim/model/SimulationRequest.java`:

```java
/**
 * @param secondMoments request per-sample logging for every measure that can carry second moments,
 *     so {@code variance} and {@code stdDev} come back populated (issues #14, #15). Off by default
 *     because it costs roughly 40 bytes of temp file per sample and 25-30% wall clock — at a
 *     {@code maxSamples} of 1,000,000 that is tens of megabytes per measure. {@code
 *     interarrival-time} logs its own samples regardless, since it has nothing to report without
 *     them.
 */
public record SimulationRequest(NetworkModel model, Long seed, Stopping stopping,
                                List<String> measures, Boolean secondMoments) {

  /** A request that does not ask for second moments — the shape callers sent before issue #15. */
  public SimulationRequest(NetworkModel model, Long seed, Stopping stopping, List<String> measures) {
    this(model, seed, stopping, measures, null);
  }
}
```

Jackson binds the canonical constructor for records, so `secondMoments` binds with no annotation; the 4-argument constructor keeps the existing test call sites compiling.

- [ ] **Step 4: Add the log-directory lifecycle to `JmtRunner`**

In `src/main/java/qsim/engine/JmtRunner.java`, add `java.io.IOException`, `java.nio.file.Files`, `java.nio.file.Path` and `java.util.Comparator` to the imports if absent, then:

```java
  /**
   * A fresh directory for one run's verbose measure logs, under the configured temp directory.
   *
   * <p>Per run, not per service: JMT names each CSV after the measure, so two runs of the same model
   * would write the same filenames. JMT never deletes them either, so the caller must pass the
   * returned path to {@link #deleteLogDir} in a finally block.
   */
  public static Path createLogDir(String tempDir) throws IOException {
    return Files.createTempDirectory(Path.of(tempDir), "qsim-logs-");
  }

  /**
   * Remove a run's log directory and everything in it. Null-tolerant and never throws: this runs on
   * the failure path, where losing the original exception to a cleanup problem would be worse than
   * leaking a temp directory. A failure to delete is reported on stderr and otherwise ignored.
   */
  public static void deleteLogDir(Path logDir) {
    if (logDir == null) {
      return;
    }
    try (var paths = Files.walk(logDir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> {
        if (!p.toFile().delete()) {
          System.err.println("qsim: could not delete measure log " + p);
        }
      });
    } catch (IOException e) {
      System.err.println("qsim: could not clean up measure log directory " + logDir + ": " + e);
    }
  }
```

- [ ] **Step 5: Wire it through the service**

In `src/main/java/qsim/http/SimulationService.java`, inside `simulate`, after the `measureMapper.map(...)` call and before `writer.toDocument(...)`:

```java
    // JMT's verbose output is per measure, so the request-level flag is applied by flipping the
    // specs. interarrival-time already carries the flag from the mapper — its figures do not exist
    // without the sample log — so an ordinary request asking only for it still gets a log directory.
    if (Boolean.TRUE.equals(req.secondMoments())) {
      measures = MeasureMapper.withVerbose(measures);
    }
    boolean anyVerbose = measures.stream().anyMatch(MeasureSpec::verbose);
    Path logDir = anyVerbose ? JmtRunner.createLogDir(config.tempDir()) : null;
```

The local is already declared as `List<MeasureSpec> measures` at line 58, so it can be reassigned as written. Pass the directory to the writer (the existing call uses `req.model()`):

```java
    var doc = writer.toDocument(req.model(), stopping, seed, measures,
        logDir == null ? null : logDir.toString());
```

The `runner.run` call currently sits **outside** the `try`, so widening the block is part of this change — otherwise a run that throws leaks the directory (Review Focus 3). Replace lines 64-71 with:

```java
    RunResult run = null;
    try {
      run = runner.run(xml, seed, stopping.maxWallClockSeconds(), /* terminal */ true);
      SolutionsParser.Parsed parsed = parser.parse(run.outputFile());
      return new SimulationResponse(req.model().name(), "simulation", seed,
          run.wallClockSeconds(), parsed.completed(), parsed.measures());
    } finally {
      if (run != null) {
        runner.cleanup(run);
      }
      // The engine can throw, or be cut off by the wall-clock cap, and JMT never removes these
      // files — so this cannot sit on the success path only.
      JmtRunner.deleteLogDir(logDir);
    }
```

Add imports for `java.io.IOException` and `java.nio.file.Path`; `MeasureSpec`, `JmtRunner` and `RunResult` are already imported. `createLogDir` throws `IOException`; if `simulate` does not already declare or wrap it, wrap it:

```java
    Path logDir = null;
    if (anyVerbose) {
      try {
        logDir = JmtRunner.createLogDir(config.tempDir());
      } catch (IOException e) {
        throw new IllegalStateException(
            "cannot create a measure log directory under " + config.tempDir()
                + "; second moments need somewhere to write per-sample logs", e);
      }
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=RequestBindingTest+SimulationServiceTest`
Expected: PASS. `aZeroSampleVerboseMeasureDoesNotFailTheRequest` runs the real engine and will print a JMT SEVERE `FileNotFoundException` for the drop-rate log — that is the documented behaviour the test exists to pin, not a failure. If the request 500s instead, the run is aborting on that log line and the service needs to tolerate a measure with no statistics attributes.

- [ ] **Step 7: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/qsim/model/SimulationRequest.java src/main/java/qsim/engine/JmtRunner.java src/main/java/qsim/http/SimulationService.java src/test/java/qsim/model/RequestBindingTest.java src/test/java/qsim/http/SimulationServiceTest.java
git commit -m "feat: add the secondMoments request flag and per-run log directories

A request may now ask for variance/stdDev on every measure that can carry them
(#14, #15); off by default, since per-sample logging costs ~40 bytes per sample
and 25-30% wall clock. Each run that needs it gets a fresh temp directory --
JMT names each CSV after the measure, so runs would otherwise collide -- removed
in a finally block, because JMT never deletes these files.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Task 6: Gate the numbers against analytic oracles

The measure now flows end to end. This task asks whether the numbers are *right*, against oracles rather than against recorded output: a Poisson arrival stream has interarrival SCV 1, and by Burke's theorem the departures of an M/M/1 are Poisson too, so the second station of a tandem must also see SCV 1 — while a deterministic arrival stream gives SCV 0 at the first station and a visibly positive SCV at the second, because the queue reshapes it. That contrast is what distinguishes a real measurement from a constant.

**Files:**
- Create: `src/test/java/qsim/golden/InterarrivalMomentsTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-5. No new production code — if this task needs production changes, they are bug fixes and belong in the task that owns the file.

- [ ] **Step 1: Write the test**

Create `src/test/java/qsim/golden/InterarrivalMomentsTest.java` (GPL header first):

```java
package qsim.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.model.*;

/**
 * Issue #15, end to end against analytic oracles.
 *
 * <p>The oracles: a Poisson arrival stream has interarrival SCV 1 by definition, and by Burke's
 * theorem an M/M/1's departure process is Poisson as well — so in a tandem Src -> Q1 -> Q2 with
 * exponential arrivals *both* stations must measure SCV ~ 1. Replacing the arrivals with a
 * deterministic stream gives SCV 0 at Q1 and a clearly positive SCV at Q2, because the queue
 * reshapes the stream it passes on. Asserting both directions is what separates a real measurement
 * from a plausible constant: a stubbed 1.0 would pass the exponential case and fail the
 * deterministic one.
 *
 * <p>The tolerances are wide on purpose. This is a stochastic simulation at a loose precision, and
 * the test's job is to catch a measure wired to the wrong quantity or the wrong units, not to
 * certify convergence.
 */
class InterarrivalMomentsTest {

  private Distribution exp(double rate) {
    return new Distribution("exponential", rate, null, null, null);
  }

  private Distribution det(double t) {
    return new Distribution("deterministic", null, t, null, null);
  }

  /** Src -> q1 -> q2 -> snk, one open class, exponential service at rate 1.0 (rho = 0.5). */
  private NetworkModel tandem(Distribution arrivals) {
    return new NetworkModel("tandem",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(arrivals))),
                new QueueNode("q1", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new QueueNode("q2", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q1", null),
                              new RoutingEdge("q1", "q2", null),
                              new RoutingEdge("q2", "snk", null))));
  }

  /** Loose precision with a real floor: enough samples for a stable SCV, small enough logs. */
  private Stopping stopping() {
    return new Stopping(0.05, 0.10, 20_000, 400_000, null, null, 300, false);
  }

  private MeasureResult find(SimulationResponse res, String station, String type) {
    return res.measures().stream()
        .filter(m -> station.equals(m.station()) && type.equals(m.type()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "no " + type + " measure for " + station + " in " + res.measures()));
  }

  private double scv(MeasureResult m) {
    assertNotNull(m.mean(), "mean must be reported for " + m.station());
    assertNotNull(m.variance(), "variance must be reported for " + m.station());
    return m.variance() / (m.mean() * m.mean());
  }

  /** The real engine and the real collaborators — the same wiring SimulationServiceTest uses. */
  private SimulationResponse simulate(SimulationRequest request) {
    return new SimulationService(Config.defaults()).simulate(request);
  }

  @Test
  void poissonArrivalsGiveInterarrivalScvOneAtBothStationsOfATandem() {
    SimulationResponse res = simulate(new SimulationRequest(
        tandem(exp(0.5)), 20260923L, stopping(), List.of("interarrival-time"), null));

    MeasureResult a1 = find(res, "q1", "interarrival-time");
    MeasureResult a2 = find(res, "q2", "interarrival-time");

    // E[A] = 1/lambda = 2.0 at both stations: in an open tandem every job passes through both.
    assertEquals(2.0, a1.mean(), 0.15, "mean interarrival time at q1 must be 1/lambda");
    assertEquals(2.0, a2.mean(), 0.15, "throughput is conserved, so q2 sees the same mean");

    assertEquals(1.0, scv(a1), 0.15, "Poisson arrivals have interarrival SCV 1");
    assertEquals(1.0, scv(a2), 0.20, "Burke: an M/M/1's departures are Poisson too");

    // stdDev is just sqrt(variance) - pinned so the two attributes cannot come from different places.
    assertEquals(Math.sqrt(a1.variance()), a1.stdDev(), 1e-9);

    // No CI on this measure: JMT's interval is on the rate (see the parser). alpha still describes
    // the run, so its presence is what distinguishes "dropped" from "measure never ran".
    assertNull(a1.lower());
    assertNull(a1.upper());
    assertEquals(0.05, a1.alpha());
    assertTrue(a1.samplesAnalyzed() >= 20_000, "the sample floor must still apply: " + a1);
  }

  @Test
  void deterministicArrivalsGiveScvZeroAtTheFirstStationAndPositiveAtTheSecond() {
    SimulationResponse res = simulate(new SimulationRequest(
        tandem(det(2.0)), 20260923L, stopping(), List.of("interarrival-time"), null));

    MeasureResult a1 = find(res, "q1", "interarrival-time");
    MeasureResult a2 = find(res, "q2", "interarrival-time");

    assertEquals(2.0, a1.mean(), 0.05, "deterministic arrivals every 2.0 time units");
    assertEquals(0.0, scv(a1), 1e-6, "a deterministic stream has zero interarrival variance");

    assertEquals(2.0, a2.mean(), 0.15, "throughput is conserved");
    assertTrue(scv(a2) > 0.1,
        "an exponential-service queue must reshape a deterministic stream, but q2 measured SCV "
            + scv(a2) + " — a measure reporting a constant would pass the q1 assertion above");
  }

  /**
   * Per class, like every other station measure, and not an aggregate: two classes with different
   * arrival rates must come back as two measures with their own means. Poisson superposition means
   * each stream is still Poisson, so both SCVs are ~ 1.
   */
  @Test
  void interarrivalTimeIsReportedPerClass() {
    NetworkModel m = new NetworkModel("twoclass",
        List.of(new JobClass("fast", "open", null, null), new JobClass("slow", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("fast", new ArrivalSpec(exp(0.3)), "slow", new ArrivalSpec(exp(0.2)))),
                new QueueNode("q1", "queue", 1, "fcfs", null,
                    Map.of("fast", new ServiceSpec(exp(2.0)), "slow", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("fast", List.of(new RoutingEdge("src", "q1", null), new RoutingEdge("q1", "snk", null)),
               "slow", List.of(new RoutingEdge("src", "q1", null), new RoutingEdge("q1", "snk", null))));

    SimulationResponse res = simulate(
        new SimulationRequest(m, 20260923L, stopping(), List.of("interarrival-time"), null));

    MeasureResult fast = res.measures().stream()
        .filter(x -> "fast".equals(x.jobClass())).findFirst().orElseThrow();
    MeasureResult slow = res.measures().stream()
        .filter(x -> "slow".equals(x.jobClass())).findFirst().orElseThrow();

    assertEquals(2, res.measures().size(), "one measure per class, no aggregate row");
    assertEquals(1.0 / 0.3, fast.mean(), 0.3);
    assertEquals(1.0 / 0.2, slow.mean(), 0.4);
    assertEquals(1.0, scv(fast), 0.2);
    assertEquals(1.0, scv(slow), 0.2);
  }

  /**
   * Review Focus 1, end to end: even with secondMoments on, a rate-typed measure reports no second
   * moments, while an ordinary one does. Same run, so this is about the measure and not the flag.
   */
  @Test
  void throughputReportsNoVarianceEvenWithSecondMomentsOn() {
    SimulationResponse res = simulate(new SimulationRequest(
        tandem(exp(0.5)), 20260923L, stopping(), List.of("throughput", "response-time"), true));

    MeasureResult tp = find(res, "q1", "throughput");
    assertEquals(0.5, tp.mean(), 0.05, "throughput is still reported as a rate");
    assertNull(tp.variance(), "its samples are interdeparture times, not rates");
    assertNull(tp.stdDev());

    MeasureResult rt = find(res, "q1", "response-time");
    assertNotNull(rt.variance(), "an ordinary measure's moments do arrive with secondMoments on");
    assertEquals(Math.sqrt(rt.variance()), rt.stdDev(), 1e-9);
  }
}
```

`SimulationService(Config)` wires every real collaborator itself, so no test helper is needed — add `import qsim.http.Config;` and `import qsim.http.SimulationService;` to the test.

- [ ] **Step 2: Run the test**

Run: `mvn -q test -Dtest=InterarrivalMomentsTest`
Expected: PASS. Five real engine runs at a 20,000-sample floor; expect roughly 30-90 seconds total.

Diagnosing a failure here:
- A mean near 0.5 rather than 2.0 means `meanValue` is being reported — Task 4's `RAW_SAMPLE_MEAN` branch is not firing.
- An SCV near 118 means a rate mean beside a time variance — the same bug seen from the other side.
- Null `variance` on `interarrival-time` means the `verbose` flag or the `logPath` is not reaching the engine — re-run `VerboseMeasureStatsTest`.
- `scv(a2)` at exactly 0.0 in the deterministic case means the measure is not reading per-station samples at all.
- A near-miss outside the tolerance, with the right order of magnitude, is convergence: raise `minSamples` rather than widening the tolerance, and say so in the commit.

- [ ] **Step 3: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/qsim/golden/InterarrivalMomentsTest.java
git commit -m "test: gate interarrival moments against Burke's theorem (#15)

Poisson arrivals must measure interarrival SCV ~1 at both stations of a tandem,
since an M/M/1's departures are Poisson; deterministic arrivals must measure 0 at
the first station and clearly positive at the second, which is what rules out a
measure reporting a constant. Also pins per-class reporting and that throughput
carries no second moments even with secondMoments on.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Task 7: Document the measure, the flag and the costs

The response contract has documented `variance`/`stdDev` since the beginning and never populated them (issue #14). A caller reading the README needs to know what turns them on, what they cost, and which measures deliberately withhold them.

**Files:**
- Modify: `README.md` (the measure list, and the section on measure fields at ~line 117)
- Modify: `docs/superpowers/specs/2026-07-25-qsim-service-design.md`

**Interfaces:** none.

- [ ] **Step 1: Update the README**

Add `interarrival-time` to the supported measure list with a one-line description: "mean time between arrivals at a station, per class — `variance` and `stdDev` included, so `scv = variance / mean^2`."

Replace the sentence at `README.md:117` with an accurate account:

```markdown
Each response measure carries `mean`, CI (`lower`/`upper`), `alpha`, `precision`, `success`,
`samplesAnalyzed`, `samplesDiscarded`, `variance`, `stdDev`.

`variance` and `stdDev` are populated only when JMT logs the measure's individual samples, which is
off by default — it costs roughly 40 bytes of temporary disk per sample and 25-30% wall clock. Two
things switch it on:

- `"measures": ["interarrival-time"]` — always, because the measure has nothing to report without
  it.
- `"secondMoments": true` at the top level of the request — for every other measure that can carry
  second moments.

The samples are written to per-measure CSV files in a fresh temporary directory under
`QSIM_TEMP_DIR`, which the service deletes when the run ends. Budget for it: at a `maxSamples` of
1,000,000 a single measure's log is tens of megabytes, and `precision` is per measure, so a tight
precision on many measures multiplies both the disk and the time. The warm-up transient is excluded
— JMT computes these statistics from the samples after `samplesDiscarded`.

`throughput` and `drop-rate` report `variance: null` and `stdDev: null` even with `secondMoments`
on. Their `mean` is a rate, while the samples underneath are the times *between* events, so the two
numbers are in different units and `variance / mean^2` off them would be meaningless (about 120x
wrong). If you need the variability of a departure stream, ask for `interarrival-time` at the
downstream station.

`interarrival-time` reports no confidence interval (`lower` and `upper` are null): JMT computes its
interval on the arrival *rate*, which would not bracket the mean interarrival time reported beside
it. `alpha`, `precision` and the sample counts still describe the run.
```

Also note the name restriction next to wherever the README describes the model JSON: "Node and class names must be 1-64 characters from `[A-Za-z0-9._-]` and start with a letter or digit — they become per-measure log filenames."

- [ ] **Step 2: Update the design spec as-built**

In `docs/superpowers/specs/2026-07-25-qsim-service-design.md`, in the measures section, add `interarrival-time` → JMT `"Arrival Rate"` to the mapping table and record, in the as-built style the spec already uses for issues #10 and #12:

- Second moments require `verbose="true"` plus a terminal simulation plus `<sim logPath>`; JMT computes them by re-reading the per-sample CSV, which is why they were always null before.
- `"Arrival Rate"` is an `InverseMeasure`, so `meanValue` is a rate while `mean`/`variance`/`standardDeviation` are over the raw interarrival samples; qsim reports the sample-side mean and names the domain type after the sampled quantity.
- `Throughput` and `Drop Rate` are inverse measures too; their second moments are suppressed rather than reported in mismatched units.
- An arrival measure inside a fork-join expansion stays anchored on the fork station, because the join's job list sees one arrival per sibling branch.
- **Deferred work, recorded so it is a decision rather than an oversight:**
  - A reciprocal CI for `interarrival-time`: `lower_A = 1/upper_rate`, `upper_A = 1/lower_rate`, which is exact for a monotone transform. Needs guards for JMT's degenerate paths (`isZero` gives lower 0 / upper `nullMeasureUpper`; `confInt == 0` gives both 0), so a zero endpoint must not become an infinite one. Issue #15 accepts a mean with no interval, so this was not worth the risk in the first pass.
  - An `interdeparture-time` type, which would expose the second moments currently suppressed on `throughput`, measured at the sending station rather than inferred at the receiver.
  - Measured *service*-time moments, which #15 asked for "ideally": JMT 1.4.0 has no service-time measure type at all (`Response Time` includes queueing), so this is not a qsim-side change.

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/specs/2026-07-25-qsim-service-design.md
git commit -m "docs: document interarrival-time, secondMoments and their costs

Records what populates variance/stdDev (they were documented and always null,
#14), what it costs in disk and wall clock, why throughput and drop-rate withhold
them, why interarrival-time has no confidence interval, and the new node/class
name restriction. Notes the deferred reciprocal CI and interdeparture-time type
as decisions rather than oversights.

Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>"
```

---

## Deferred, by decision

Recorded here as well as in the spec so a reviewer can see these were weighed:

| Not built | Why |
|---|---|
| An `scv` or `cov_a` response field | Application-specific. qsim reports what it measures — mean and variance — in the same shape as every other measure, and the caller divides. |
| A confidence interval on `interarrival-time` | JMT's interval is on the rate. The reciprocal transform is exact but has degenerate endpoints to guard; issue #15 says a mean with no CI is fine. |
| Second moments on `throughput`/`drop-rate` | Their mean is a rate and their samples are inter-event times. Reporting both is a unit trap; a real fix is an `interdeparture-time` type. |
| Measured service-time SCV (asked for in #15) | JMT 1.4.0 has no service-time measure. Nothing qsim can map to. |
| An aggregate (all-class) variant | Every station measure in qsim is per-class. Consistency over a special case. |
| `secondMoments` on by default | Up to tens of megabytes of temp CSV and +25-30% wall clock per measure. Existing callers must not pay for a feature they did not ask for. |
