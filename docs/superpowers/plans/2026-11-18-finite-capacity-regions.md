# Finite Capacity Regions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a `/simulate` model declare regions: sets of queue, delay and fork-join nodes that
hold at most N customers, where arrivals at a full region wait at the entry or are dropped. The
response reports region-level measures.

**Architecture:**
- **Patch.** A build-time ASM patch of JMT 1.5.0's `simEngine.Simulation.initialize()` lets
  Fork/Join sections be region members. The patched class lands in `target/classes`, ahead of
  the unmodified jar on every classpath. A startup check refuses to run an unpatched engine.
- **Pipeline.** The contract gains `model.regions`. The validator rejects every shape JMT would
  mishandle. The writer emits `<blockingRegion>`. Five `region-*` measure types map to JMT
  `nodeType="region"` measures.

**Tech Stack:** Java 17, Maven, Jackson 2.17.1, JUnit 5, ASM 9.10.1 (build only),
exec-maven-plugin, JMT 1.5.0 single jar (system scope).

**Spec:** `docs/superpowers/specs/2026-11-18-finite-capacity-regions-design.md`. Read it
first; section references below (§n) point into it.

## Global Constraints

- Every model without `regions` renders **byte-identical** JSIMG to `main` @ e55cc93
  (spec §2 anti-goals).
- ASM must not be a runtime dependency. The patcher (`qsim.build.*`) must not be in
  `target/qsim-service.jar`, though it may sit in `target/classes`.
- The patch applies only to `simEngine/Simulation.class` with SHA-256
  `c8ff675c8c5b8ce05afbd011e70343b8effc96d9adea0d9374caef2b07c80343` (from
  `lib/JMT-singlejar-1.5.0.jar`), and only with exactly **6** rewrites. Anything else fails the
  build.
- The JMT quarantine stays: only `qsim.engine` imports JMT classes. The patcher manipulates
  bytes and imports ASM only.
- Every new invalid model is a 422 (`ValidationException.Kind.UNPROCESSABLE`). A `region-*`
  measure requested for a model without regions is a 400 (`BAD_REQUEST`), like an unsupported
  measure type.
- Region per-class children are written **grouped by tag**: all `dropRules`, then all
  `classWeight`, then all `classSize`.
- `globalMemoryConstraint maxMemory="-1"` is always written.
- `whenFull` defaults to `"wait"` for every class.
- There is no CI. Every "passes" claim means `mvn -o test` run locally, quoting the surefire
  totals. Expected noise: the `minSamples` `cvc-complex-type` lines and one SEVERE
  `FileNotFoundException` trace.
- Commits end with `Co-Authored-By: Claude <209825114+claude[bot]@users.noreply.github.com>`.
- Work happens in worktree `.claude/worktrees/fcr` on `feat/finite-capacity-regions`. Re-check
  the branch before each commit; the checkout is shared with other sessions.

## Review Focus

Things the spec implies but no other task's tests exercise. Each one's test is added to the
owning task.

1. **`secondMoments: true` with region measures requested:** the run completes, and region
   rows carry `variance`/`stdDev` (or null for the rate types), with no crash from verbose
   logging on a `nodeType="region"` measure. Owner: Task 5 (`regionMeasuresSurviveSecondMoments`).
2. **`whenFull` given as a map that omits some classes:** the omitted classes wait. Owner:
   Task 3 (`whenFullMapDefaultsMissingClassesToWait`).
3. **`measures` omitted on a model with a region:** the default measure set is unchanged, with
   no `region-*` rows. Owner: Task 4 (`defaultsAddNoRegionRows`).
4. **A `region-*` type requested where one class never enters the region:** no row for that
   class, and rows for the classes that do enter. Owner: Task 4
   (`regionRowsOnlyForEnteringClasses`).
5. **A closed class elsewhere in the model, never routed into a region:** accepted, so the v1
   closed-class rule doesn't over-reject. Owner: Task 2
   (`acceptsClosedClassThatNeverEntersARegion`).

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/qsim/build/JmtFcrPatcher.java` (new) | Build-time: read `Simulation.class` from the jar, check the hash, apply the 6 rewrites and the marker field, write it to the output dir. `main(String[] {jarPath, outputDir})`. |
| `src/main/java/qsim/engine/FcrShim.java` (new) | Runtime helpers that the patched bytecode calls. |
| `src/main/java/qsim/engine/FcrPatchCheck.java` (new) | `verify()`: fails if the loaded `simEngine.Simulation` lacks the marker. |
| `src/main/java/qsim/model/Region.java` (new) | Region record plus `whenFullFor(class)`. |
| `src/main/java/qsim/model/NetworkModel.java` | Adds a `regions` component, keeping a 4-argument constructor. |
| `src/main/java/qsim/contract/ContractValidator.java` | `checkRegions(...)`: the spec §5.3 rules. |
| `src/main/java/qsim/translate/JsimgWriter.java` | `writeRegions(sim, model)`. |
| `src/main/java/qsim/translate/MeasureMapper.java` | The `REGION` type map and expansion. |
| `src/main/java/qsim/result/SolutionsParser.java` | Maps `nodeType="region"` rows to `region-*` types and the region name. |
| `src/main/java/qsim/engine/JmtRunner.java` | `killSimulation()` on failure (Task 6, conditional). |
| `pom.xml` | ASM `provided`; exec-maven-plugin at `process-classes`; maven-jar-plugin excludes `qsim/build/**`. |
| `docs/fcr/ctmc_cap2.py`, `docs/fcr/qcsc.py` (already copied) | Reproducible references for the golden tests. |
| README, NOTICE, 2026-07-25 spec | Docs (Task 7). |

---

### Task 1: Build-time JMT patch, shim and startup check

**Files:**
- Create: `src/main/java/qsim/build/JmtFcrPatcher.java`, `src/main/java/qsim/engine/FcrShim.java`, `src/main/java/qsim/engine/FcrPatchCheck.java`
- Modify: `pom.xml`, `src/main/java/qsim/http/SimulationService.java` (constructor), `src/main/java/qsim/http/App.java` (nothing beyond what the service constructor does), `README.md` (the Build section's one-time online fetch)
- Test: `src/test/java/qsim/build/JmtFcrPatcherTest.java`, `src/test/java/qsim/engine/FcrPatchCheckTest.java`, `src/test/java/qsim/engine/FcrRegionEngineTest.java`

**Interfaces:**
- Produces:
  - `JmtFcrPatcher.EXPECTED_SHA256` (the hash in Global Constraints);
  - `static Result rewrite(byte[] classBytes)` where `record Result(byte[] bytes, int rewrites)`.
    It applies the rewrites and adds the marker, with no hash check;
  - `static byte[] patch(byte[] classBytes)`: checks the hash, calls `rewrite`, requires
    `rewrites == 6`, and throws `IllegalStateException` otherwise. Each message names the spec
    file;
  - `public static void main(String[] args)`: args `{jarPath, outputDir}`. It writes
    `outputDir/simEngine/Simulation.class`.
- Produces: `qsim.engine.FcrShim` with exactly these JVM descriptors (the patched bytecode
  calls them by name):
  - `public static boolean storageOrJoin(Object)`
  - `public static void redirect(Object, QueueNet.BlockingRegion)`
  - `public static boolean linkageOrFork(Object)`
  - `public static void border(Object, QueueNet.BlockingRegion)`
- Produces: the marker field `public static final boolean QSIM_FCR_PATCHED = true` on
  `simEngine.Simulation`, and `FcrPatchCheck.verify()`, which throws `IllegalStateException`
  when the field is absent.

- [ ] **Step 1: pom changes.**
  - Add `org.ow2.asm:asm:9.10.1` with `<scope>provided</scope>`.
  - Add `exec-maven-plugin` (pin a current release, e.g. 3.5.0): execution `patch-jmt`, phase
    `process-classes`, goal `java`, `mainClass` `qsim.build.JmtFcrPatcher`,
    `classpathScope` `compile`, arguments `${project.basedir}/lib/JMT-singlejar-1.5.0.jar` and
    `${project.build.outputDirectory}`.
  - Add `maven-jar-plugin` (pin, e.g. 3.4.2) with `<excludes><exclude>qsim/build/**</exclude></excludes>`.
  - Run `mvn -q validate` **online once** so the plugins resolve. Later runs use `-o`.

- [ ] **Step 2: Write the failing tests.** `JmtFcrPatcherTest`, reading the class bytes
  straight from the jar via `ZipFile`:
  - `patchesTheBundledClassWithExactlySixRewrites`: `rewrite(original).rewrites() == 6`, and
    `patch(original)` returns without throwing.
  - `refusesAClassWithADifferentHash`: `patch(bytes of any other class in the jar, e.g.
    simEngine/SimLoader.class)` throws `IllegalStateException` whose message contains `SHA-256`
    and the spec filename.
  - `refusesWhenTheRewriteCountIsWrong`: `rewrite(SimLoader bytes).rewrites() == 0`.
  - `patchedClassCarriesTheMarker`: load `patch(original)` through a throwaway `ClassLoader`
    whose `findClass` defines only `simEngine.Simulation`, delegating everything else to the
    test loader. Assert that `getField("QSIM_FCR_PATCHED").getBoolean(null)` is true.
  - `patchedInitializeCallsTheShim`: use an ASM `ClassReader` over `patch(original)` and collect
    the `invokestatic` owners in `initialize()V`. Each of `storageOrJoin`, `redirect`,
    `linkageOrFork` and `border` appears once with owner `qsim/engine/FcrShim`. No
    `checkcast NodeSections/Storage` or `NodeSections/Linkage` is left.

  `FcrPatchCheckTest`:
  - `theTestClasspathRunsThePatchedEngine`: `FcrPatchCheck.verify()` doesn't throw. This proves
    `target/classes` beats the jar under surefire.

  `FcrRegionEngineTest`:
  - `forkJoinRegionInitializesUnderThePatch`: run the committed
    `src/test/resources/models/fcr-forkjoin.sim.xml` through `new JmtRunner().run(xml, 1L, 60,
    true)` and assert that it returns. That file is a minimal `<sim>`: src(λ=0.4) → fork-join
    (2 branches, exponential rate 1) → sink, plus a `<blockingRegion>` holding `fj`, `fj__b0`,
    `fj__b1` and `fj__join`, with `maxJobs="1"` and one `System Response Time` measure at
    precision 0.05. Generate it once with `JsimgWriter` and hand-add the region element as in
    spec §6.2. Unpatched, this throws `EngineException` mentioning "not compliant output
    section".

- [ ] **Step 3: Run them and see them fail.** `mvn -o test -Dtest='JmtFcrPatcherTest,FcrPatchCheckTest,FcrRegionEngineTest'`
  fails to compile (no patcher yet). With only the shim and an empty patcher it fails on the
  rewrite counts.

- [ ] **Step 4: Implement.**
  - **`JmtFcrPatcher`:** a `ClassVisitor` over `ClassWriter(reader, 0)`, which keeps the
    original frames (stack effects are unchanged; spec §6.1). Inside `initialize()V` only:
    - `INSTANCEOF NodeSections/Storage` becomes `INVOKESTATIC qsim/engine/FcrShim.storageOrJoin`;
    - `INSTANCEOF NodeSections/Linkage` becomes `linkageOrFork`;
    - `CHECKCAST` of either is dropped;
    - `INVOKEVIRTUAL NodeSections/Storage.redirectionTurnON` becomes `redirect` with descriptor
      `(Ljava/lang/Object;LQueueNet/BlockingRegion;)V`;
    - `INVOKEVIRTUAL NodeSections/Linkage.borderRouterTurnON` becomes `border`;
    - in `visitEnd`, add the marker field (`ACC_PUBLIC|ACC_STATIC|ACC_FINAL`, constant `1`).

    Count each rewrite.
  - **`FcrShim`:** the Storage and Linkage paths keep the original calls. A `Join` input or a
    `Fork` output does nothing, with no logging. The Javadoc states the safety argument
    (spec §3, "Why the restriction is overly conservative here") and that it holds only
    because the contract keeps whole fork-joins inside (spec §5.3).
  - **`FcrPatchCheck.verify()`:** call
    `Class.forName("simEngine.Simulation").getField("QSIM_FCR_PATCHED")`. On
    `NoSuchFieldException`, throw `IllegalStateException("JMT is unpatched: simEngine.Simulation was loaded from <code source>; the patched class in qsim's classes must precede the JMT jar on the classpath")`.
  - **`SimulationService(Config, JmtRunner)`:** call `FcrPatchCheck.verify()` first.

- [ ] **Step 5: Run them and see them pass.** Run the same command: all green. Then
  `mvn -o test`: the full suite stays green (128 before this work, plus the new tests).
  - Run `mvn -o -q package -DskipTests` and `unzip -l target/qsim-service.jar | grep -c
    'qsim/build/'`: expect `0`.
  - `unzip -l target/qsim-service.jar | grep simEngine/Simulation.class`: expect 1 line.
- [ ] **Step 6: README Build note.** One paragraph: the first build on a machine needs network
  access, because Maven fetches exec-maven-plugin and maven-jar-plugin; `-o` works afterwards.
- [ ] **Step 7: Commit:** `feat: patch JMT at build time so fork-joins can sit inside a finite capacity region`.

### Task 2: Region contract and validation

**Files:**
- Create: `src/main/java/qsim/model/Region.java`
- Modify: `src/main/java/qsim/model/NetworkModel.java`, `src/main/java/qsim/contract/ContractValidator.java`
- Test: `src/test/java/qsim/contract/RegionValidationTest.java`, `src/test/java/qsim/model/RequestBindingTest.java` (add binding cases)

**Interfaces:**
- Produces:
  - `public record Region(String name, List<String> nodes, Integer capacity, Map<String,Integer> classCapacity, Object whenFull)`;
  - `whenFull` binds a JSON string to `String` and a JSON object to `Map<String,String>`, or
    null;
  - `public String whenFullFor(String className)` returns `"wait"`/`"drop"` and defaults to
    `"wait"`;
  - `public boolean drops(String className)`.
- Produces: `NetworkModel(String name, List<JobClass> classes, List<Node> nodes, Map<String,List<RoutingEdge>> routing, List<Region> regions)`,
  plus the existing 4-argument constructor delegating with `regions = null`.

- [ ] **Step 1: Write the failing tests.**
  - **Binding** (`RequestBindingTest`): `bindsRegionWithStringWhenFull` (`"drop"` →
    `whenFullFor("x")=="drop"`), `bindsRegionWithMapWhenFull`, and `modelWithoutRegionsBindsNull`.
  - **`RegionValidationTest`:** build on a helper model (src → q1 → fj → q2 → snk, open class
    `a`, plus open class `b` src → q3 → snk). For each case, assert a 422 whose detail contains
    the quoted fragment:

    | Test | Input | Detail contains |
    |---|---|---|
    | `acceptsAValidRegion` | `{name:"R", nodes:[q1,fj,q2], capacity:1}` | — (accepted) |
    | `rejectsEmptyNodes` | | `region 'R'` + `nodes` |
    | `rejectsUnknownMember` | `ghost` | `'ghost'` |
    | `rejectsSourceOrSinkMember` | `src`, then `snk` | `'src'` / `'snk'` + `queue, delay or fork-join` |
    | `rejectsNodeInTwoRegions` | | `'q1'` + `more than one region` |
    | `rejectsDuplicateRegionName` | | `region name 'R'` |
    | `rejectsRegionNamedLikeANode` | `name:"q3"` | `'q3'` |
    | `rejectsInputStationCollision` | a node named `R_inputStation` | `R_inputStation` |
    | `rejectsUnsafeRegionName` | `"a b"` | `region name` (existing `checkName`) |
    | `rejectsCapacityBelowOne` | `0` | `capacity` |
    | `rejectsClassCapacityForUnknownClass` | | `'zz'` |
    | `rejectsClassCapacityBelowOneOrAboveCapacity` | `0` / `capacity+1` | `classCapacity` |
    | `rejectsWhenFullUnknownValueOrClass` | `"maybe"` / `{zz:"wait"}` | `whenFull` |
    | `rejectsPartialJoinMember` | fj with `join:"1"` | `'fj'` + `join "all"` |
    | `rejectsFiniteCapacityQueueMember` | q1 `capacity:5` | `'q1'` + `capacity` |
    | `rejectsClosedClassIntoRegion` | a closed class with an edge into q1 | `closed class` + `'R'` |
    | `acceptsClosedClassThatNeverEntersARegion` | a closed class cycling think ↔ q4 outside R | — (accepted; Review Focus 5) |

- [ ] **Step 2: Run them and see them fail** (`Region` doesn't exist):
  `mvn -o test -Dtest='RegionValidationTest,RequestBindingTest'`.
- [ ] **Step 3: Implement.**
  - The `Region` record and `NetworkModel`'s 5th component.
  - `ContractValidator.checkRegions(List<String> errors, NetworkModel model, Set<String> nodeNames)`,
    called from `validate` before the routing checks. It enforces exactly spec §5.3.
  - "Routed into a region" for a closed class means: an edge of that class whose `to` is a
    member and whose `from` isn't, **or** a reference station (`referenceStationOf`) that is a
    member.
- [ ] **Step 4: Run them and see them pass.** Run the same command, then `mvn -o test` green.
- [ ] **Step 5: Commit:** `feat: accept and validate finite capacity regions in the model contract`.

### Task 3: Write `<blockingRegion>`

**Files:**
- Modify: `src/main/java/qsim/translate/JsimgWriter.java`
- Test: `src/test/java/qsim/translate/JsimgWriterRegionTest.java`

**Interfaces:**
- Consumes: `Region`, `NetworkModel.regions()`; `branchStationName`/`joinStationName` (existing
  statics).
- Produces: `private void writeRegions(Element sim, NetworkModel model)`, called between
  `writeConnections` and `writePreload` in `toDocument`.

- [ ] **Step 1: Write the failing tests** (XPath, in the style of `JsimgWriterMultiClassRoutingTest`):
  - `writesOneBlockingRegionAfterConnectionsBeforePreload`: there is no `connection` after
    `blockingRegion`. With a closed class present, `preload` follows it.
  - `expandsAForkJoinMemberIntoAllItsStations`: members `[q1, fj]` with 2 branches give
    `regionNode` names exactly `{q1, fj, fj__b0, fj__b1, fj__join}`.
  - `writesMandatoryConstraints`: `globalConstraint/@maxJobs = "1"` and
    `globalMemoryConstraint/@maxMemory = "-1"`.
  - `writesClassConstraint`: `classCapacity {a:1}` gives
    `classConstraint[@jobClass='a']/@maxJobsPerClass = "1"`.
  - `groupsPerClassChildrenByTag`: for 2 classes, the child element sequence after the
    constraints is `dropRules, dropRules, classWeight, classWeight, classSize, classSize`.
  - `whenFullMapDefaultsMissingClassesToWait`: `whenFull {a:"drop"}` gives
    `dropRules[@jobClass='a']/@dropThisClass = "true"` and `[@jobClass='b'] = "false"`
    (Review Focus 2).
  - `modelWithoutRegionsIsByteIdentical`: `toXmlString` of `src/test/resources/fixtures/mm1.json`
    and `qopt-3station.json` equals the committed snapshots
    `src/test/resources/snapshots/{mm1,qopt-3station}.sim.xml`. Generate the snapshots **on
    `main` before this task's code change** (same seed and measures as the test).
  - Also `writer.validate(doc)` passes for a region model, as the existing tests do: no XSD
    error.
- [ ] **Step 2: Run them and see them fail:** `mvn -o test -Dtest=JsimgWriterRegionTest`.
- [ ] **Step 3: Implement `writeRegions`.** One element per region,
  `name=<region>`, `type="default"`, with children in spec §6.2 order. A `classConstraint`
  appears only for `classCapacity` entries. `dropRules`/`classWeight`/`classSize` cover every
  declared class.
- [ ] **Step 4: Run them and see them pass.** Run the same command, then `mvn -o test` green.
- [ ] **Step 5: Commit:** `feat: write finite capacity regions to JSIMG`.

### Task 4: Region measures

**Files:**
- Modify: `src/main/java/qsim/translate/MeasureMapper.java`, `src/main/java/qsim/result/SolutionsParser.java`
- Test: `src/test/java/qsim/translate/MeasureMapperTest.java`, `src/test/java/qsim/result/SolutionsParserTest.java`

**Interfaces:**
- Produces: `MeasureMapper.REGION`, an ordered map with exactly these entries:
  - `region-response-time` → `Response Time`
  - `region-queue-time` → `Queue Time`
  - `region-queue-length` → `Number of Customers`
  - `region-throughput` → `Throughput`
  - `region-drop-rate` → `Drop Rate`
- Each expands to `MeasureSpec("region_" + r + "_" + c + "_" + t, jmt, r, c, "region")` for
  each region `r` and each **entering** class `c`. Entering means the class has an edge
  `from ∉ members`, `to ∈ members`, using domain names.
- Produces: the parser emits rows with `type = region-*` and `station = <region name>` when
  `nodeType == "region"`.
- The writer needs no change. `expandedMeasureNode` returns a non-node `referenceNode`
  unchanged, and `checkMeasures` only inspects fork-join types. A test pins this.

- [ ] **Step 1: Verify the open item from spec §12.1.** Run the Task 1 fixture with one region
  `Queue Time` measure added, then read JMT's output `station` attribute for it: is it `R` or
  `R_inputStation`? Record the answer in a comment in `SolutionsParser`.
- [ ] **Step 2: Write the failing tests.**
  - **`MeasureMapperTest`:**
    - `mapsRegionTypesPerEnteringClass`;
    - `regionRowsOnlyForEnteringClasses` (Review Focus 4): class `b` routed around R gets no
      spec;
    - `defaultsAddNoRegionRows` (Review Focus 3): `measures=null` on a region model gives
      exactly the current defaults;
    - `regionTypeWithoutRegionsIsBadRequest`.
  - **`SolutionsParserTest`:** `parsesRegionRows`, from a small hand-written `<solutions>`
    fixture with `nodeType="region"` rows, using the station value found in Step 1. Rows map to
    `region-queue-time` etc. with station `R`. `Throughput` stays rate-typed (variance null).
  - **Writer:** `regionMeasureKeepsRegionAsReferenceNode` (`referenceNode="R"`,
    `nodeType="region"`).
- [ ] **Step 3: Run them and see them fail:** `mvn -o test -Dtest='MeasureMapperTest,SolutionsParserTest,JsimgWriterRegionTest'`.
- [ ] **Step 4: Implement.** In the parser, branch on `nodeType` *before* the `REVERSE`
  lookup: region rows must not become `response-time`. Strip a trailing `_inputStation` only if
  Step 1 showed it.
- [ ] **Step 5: Run them and see them pass.** Run the same command, then `mvn -o test` green.
- [ ] **Step 6: Commit:** `feat: report region measures`.

### Task 5: Golden tests against exact answers

**Files:**
- Create: `src/test/java/qsim/golden/RegionGoldenTest.java`
- Commit with it: `docs/fcr/ctmc_cap2.py`, `docs/fcr/qcsc.py` (already in the worktree, untracked)
- Test: same

**Interfaces:**
- Consumes: the whole pipeline via `SimulationService.simulate`. Reuse the `assertBrackets`
  and `pick(r, station, class, type)` style from `GoldenAnalyticTest`.

- [ ] **Step 1: Write the tests.** All models are exponential; `Stopping(0.05, 0.02, 50_000,
  5_000_000, null, null, 300, false)` unless noted. Each test's Javadoc cites spec §8 and the
  derivation. Expected values are hardcoded:

  | Test | Model | Assertions (the CI brackets the value) |
  |---|---|---|
  | `g1CapOneWaitIsMG1` | src λ=0.4 → fj(1,1) → snk; R={fj} cap 1 wait | `system-response-time`=3.25; `region-queue-time`=1.75; `region-response-time`=3.25; fj `response-time`=1.5, which is the fork-join's time when alone, so nothing queues inside. Don't assert `queue-time` on fj: a fork-join's station measures report its join station, which is always 0 |
  | `g2CapOneDropIsErlangLoss` | same, drop | `region-drop-rate`=0.15; `region-throughput`=0.25; `system-response-time`=1.5 |
  | `g3UnboundedCapMatchesNoRegion` | same, cap 1000, seed S, vs the same model without regions, seed S | every `system-response-time` mean equal (`assertEquals(…, 0.0)`) |
  | `g4CapTwoWaitMatchesCtmc` | src λ=0.4 → fj(1,1); cap 2 wait; seeds 1001..1005 | the cross-seed t-interval (t₀.₉₇₅,₄ = 2.776) of the `system-response-time` means contains 2.54545; the same for fj `response-time` vs 2.01399 |
  | `g5CapTwoDropMatchesCtmc` | same, drop, one seed | `region-drop-rate`=0.06095; `region-throughput`=0.33905; `system-response-time`=1.85393 |
  | `g6QcscShapedCapOneIsMultiClassMG1` | spec §8 G6 topology and rates; R = all 14 | `system-response-time` ps=5.85646, sp=5.61836; every member's `queue-time`==0 |
  | `g7QcscShapedCapOneDrop` | same, drop | `region-drop-rate`/λ_r = 0.39742 for both classes (bracket the rate 0.059613 for ps, 0.039742 for sp); `system-response-time` ps=2.73333, sp=2.49524 |
  | `g8DelayInCapThreeDropIsErlangB` | src λ=2 → delay (rate 1) → snk; R={delay} cap 3 drop | `region-drop-rate`=0.421053; `region-throughput`=1.578947; `system-response-time`=1.0 |
  | `regionMeasuresSurviveSecondMoments` | G1 with `secondMoments=true` and all five `region-*` types | completes; `region-response-time` row has non-null `variance`; `region-throughput` has `variance==null` (Review Focus 1) |

  The references come from `docs/fcr/ctmc_cap2.py` (G4/G5: `python3 docs/fcr/ctmc_cap2.py 0.4
  wait 300 2` / `drop 0 2`) and `docs/fcr/qcsc.py` (G6/G7). Don't seed-shop: if a fixed seed
  misses, investigate before changing it.
- [ ] **Step 2: Run them:** `mvn -o test -Dtest=RegionGoldenTest`. Expect a pass, since every
  piece was built test-first, with total time under about 90 s. Report the time. If a case
  fails, debug it (superpowers:systematic-debugging); never loosen the expected value.
- [ ] **Step 3: Commit** (tests plus `docs/fcr/`): `test: golden finite-capacity-region cases against exact answers`.

### Task 6: Stop JMT's watchdog on a failed run (conditional)

**Files:**
- Modify: `src/main/java/qsim/engine/JmtRunner.java`
- Test: `src/test/java/qsim/engine/JmtRunnerTest.java`

- [ ] **Step 1: Write the failing test.** `failedRunLeavesNoWatchdogThread`: run an XML that
  fails at JMT init (the Task 1 fixture with the `globalMemoryConstraint` removed throws
  `LoadException`), with `maxWallClockSeconds=600`. Assert that `EngineException` is thrown and
  that, within 2 s, no live thread started during the call remains. Compare
  `Thread.getAllStackTraces()` before and after.
- [ ] **Step 2: Run it.** If it already passes (the watchdog isn't started on this failure
  path), keep it as a regression guard, skip Step 3, and say so in the PR.
- [ ] **Step 3: Implement.** In `run`'s generic `catch (Exception e)`, call
  `dispatcher.killSimulation()` inside its own try/catch before wrapping. That means hoisting
  `dispatcher` out of the try. If the test still fails because `killSimulation` doesn't stop
  the watchdog, revert, document the bounded leak in a comment and in the PR, and keep the test
  `@Disabled` with the reason.
- [ ] **Step 4: Run** `mvn -o test` green. **Commit:** `fix: stop JMT's watchdog when a run fails`.

### Task 7: Docs

**Files:**
- Modify: `README.md`, `NOTICE`, `docs/superpowers/specs/2026-07-25-qsim-service-design.md`

- [ ] **Step 1: README.**
  - A "Regions" subsection under the contract: the JSON example from spec §5.1, the field table,
    and the five measures with the spec §5.2 wording. Add: "time inside = `region-response-time`
    − `region-queue-time` (means only)".
  - The v1 limits: no closed classes into regions; members' `capacity` must be unset;
    fork-joins must use `join: "all"`.
  - Extend the 422 row with the region rules.
- [ ] **Step 2: NOTICE.** Replace "The bundled artifact … is unmodified." with: the jar in
  `lib/` is unmodified, but at build time qsim rewrites `simEngine.Simulation.initialize()` (6
  instructions) so Fork/Join sections may be finite-capacity-region members. The patcher source
  is `src/main/java/qsim/build/JmtFcrPatcher.java`, and the patched class ships in
  `qsim-service.jar`.
- [ ] **Step 3: The 2026-07-25 spec.** Add a one-line pointer in §5.1 and §6.1 to the regions
  spec.
- [ ] **Step 4:** run `mvn -o test` one last time and quote the totals. **Commit:** `docs: finite capacity regions, and the JMT build-time patch`.
