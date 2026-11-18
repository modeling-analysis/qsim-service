# Design Spec: finite capacity regions (bounded subnetworks)

Date: 2026-11-18
Status: Draft, pending Asser's approval.

Context: qsim models an open (or closed) network of independent stations; each station is
configured on its own. This adds the first relationship *between* stations: a set of
interconnected nodes, called a **region**, that holds at most N customers. A customer that
arrives at a full region either waits in a queue at the region's entry or is discarded. JMT
supports this natively as a **Finite Capacity Region** (FCR, `<blockingRegion>` in the model
XML). The work is mostly translation, plus one small patch to JMT, which is needed because the
motivating case has fork-joins inside the region.

The evidence behind this spec is a spike run on 2026-11-17/18: an FCR prototype on the bundled
JMT 1.5.0 jar, checked against exact answers. §10 summarises it. Spike code is not in the repo;
the reference numbers in §8 are what it produced.

---

## 1. Intent

**Why.** qopt's reservation configuration for the quantum (QCSC) system allows only one job
inside the whole QCSC network at a time, with the other jobs queued outside. qopt can't express
that today, and qsim can't simulate it. The QCSC network has two fork-joins (`fj_pp`, `fj_sp`),
so the region has to be able to contain fork-joins.

**Success.**
- A caller declares a region (members, capacity, wait or drop) in the existing `/simulate` JSON.
- qsim returns region-level measures (total time, entry wait, customers, throughput, drop rate)
  next to the usual station measures.
- The golden cases in §8 bracket their exact answers.
- Every existing request produces byte-identical JSIMG and unchanged results.

## 2. Goals, non-goals, anti-goals

**Goals (v1)**
- An optional `regions` list in the model: members, a global capacity, an optional per-class
  capacity, and wait or drop per class.
- Members can be `queue`, `delay` and `fork-join` nodes. Several entry and exit points are
  allowed; JMT derives them from routing.
- Region measures in the response.
- The JMT patch applied at build time, guarded so that a JMT upgrade or a classpath mistake
  fails loudly.

**Non-goals (v1)**
- Memory, size, weight, group constraints and soft deadlines (JMT has them; nobody needs them).
- Nested or overlapping regions.
- Closed classes routed into a region (see §5.3, why).
- A finite-capacity `queue` (`capacity` set) as a region member (see §5.3, why).
- The qopt side (a separate spec in the optimizer repo).
- Sending the patch upstream to JMT. That's worth doing, but it's an outreach action, not code
  here.

**Anti-goals** (failures even if the feature "works")
- **Silent wrong numbers.** Every model JMT would mishandle must be rejected with a 422 rather
  than simulated. That is the lesson of #18/#19: the validator's idea of where a class starts
  and where it can go must match what the writer emits.
- **A JMT patch that drifts silently.** If the bundled jar changes and the patch no longer
  applies exactly, the build must fail, not ship an unpatched engine.
- **Any change to existing outputs.** Models without `regions` must render byte-identical
  JSIMG.

## 3. Constraints and facts (verified)

These were verified against the JMT source and the 1.5.0 jar. See also the repo memory notes on
JMT engine facts.

- **Element shape.** `<blockingRegion name type="default">` contains:
  - `regionNode nodeName`*;
  - `globalConstraint maxJobs`, **mandatory** (-1 means ∞);
  - `globalMemoryConstraint maxMemory`, **also mandatory**, or `SimLoader` throws
    `LoadException`;
  - optional per-class `classConstraint maxJobsPerClass`, `dropRules jobClass dropThisClass`,
    `classWeight`, `classSize`.

  It sits after the `connection`s and before `preload` in `<sim>`. The engine's schema check
  wants per-class children **grouped by tag** (all `dropRules`, then all `classWeight`, then all
  `classSize`). Interleaving them per class prints a non-fatal `cvc-complex-type` error. Apart
  from that, the bundled XSDs accept the element.
- **Defaults to override.** With no `dropRules`, JMT drops open classes and makes closed classes
  wait. qsim writes the rule explicitly for every class.
- **Mechanism.**
  - `Simulation.initialize` adds a hidden node `<region>_inputStation` (BlockingQueue +
    ServiceTunnel + BlockingRouter) that holds waiting jobs.
  - A member's input `Queue` redirects jobs arriving from outside the region to that station,
    which admits, queues or drops them and increments the occupancy.
  - A member's output `Router` decrements the occupancy when a job's next hop is outside.
  - Waiting jobs are admitted FCFS among equal-priority classes; qsim writes priority 0 for
    every class. `BlockingQueue.getNextNotBlockedJob` lets a later job overtake only on strictly
    higher class priority.
- **The restriction the patch lifts.** `Simulation.initialize` requires every member to have a
  `Queue`/`Storage` input and a `Router`/`Linkage` output, else it throws `NetException "inner
  station … has a not compliant input/output section"`. A JMT Fork (output section) and Join
  (input section) fail this check, so a fork-join can't sit inside an FCR. This was confirmed
  by a run: with no patch, a qsim fork-join in a region fails at init.
- **Why the restriction is overly conservative here.** For a fork-join entirely inside a region:
  - the fork node's input is a `Queue`, so jobs entering it from outside are still redirected;
  - the forked tasks move only between members, so no hook fires on them;
  - the join's output is a `Router`, so the merged parent job is counted out once.

  The occupancy therefore tracks parent jobs correctly. It would not if a fork's branch left the
  region or a join took input from outside, and the contract makes both impossible (§5.3).
- **Region measures** use `nodeType="region"` and `referenceNode=<region name>`, and are
  attached to the hidden input station. Measured semantics (spike, cap 1 wait, λ=0.4, exact
  answers in brackets):

  | JMT region type | Measured | What it is |
  |---|---|---|
  | `Response Time` | 3.260 [3.25] | **total**: entry wait + time inside |
  | `Queue Time` | 1.767 [1.75] | **entry wait** only (0 in drop mode) |
  | `Number of Customers` | 1.304 [1.30] | customers waiting **plus** inside (not 0.6) |
  | `Throughput` | 0.403 [0.40] | admitted throughput |
  | `Drop Rate` | 0.149 [0.15] in drop mode | losses per unit time |
  | `Utilization` | 0 | meaningless; not exposed |
  | `Residence Time` | equals Response Time | redundant; not exposed |

- **Classpath order.**
  - Docker runs with `-cp app.jar:dependency/*`, and surefire puts `target/classes` before the
    system-scoped JMT jar. A patched `simEngine/Simulation.class` written into `target/classes`
    therefore takes precedence in tests and in the image, and the jar itself stays unmodified.
  - JMT 1.5.0 classes are Java 8 (version 52); qsim compiles with release 17. Mixing them at
    runtime is fine.
- **Build environment.**
  - Local builds run `mvn -o` (offline), and offline plugin resolution is limited: the
    `dependency:` prefix failed to resolve offline. ASM 9.10.1 is in the local repository.
  - The Docker build stage has network access but uses JDK 17, so the JDK 24+ ClassFile API is
    not available there. Use ASM.
- **Engine-failure path.**
  - When JMT setup fails, `solveModel()` throws, `JmtRunner` wraps it in `EngineException`, and
    the caller gets an error response; the service doesn't hang.
  - But the watchdog thread started by `setSimulationMaxDuration` stays alive until its
    deadline. In a CLI harness that kept the JVM running.

## 4. Approaches (decided)

| Approach | Trade-off | Decision |
|---|---|---|
| **(a) Patch JMT at build time.** A build step rewrites `Simulation.initialize` in the bundled jar's class and writes the patched class plus a small helper class into `target/classes`. | Supports fork-joins in regions now, which the QCSC case needs. qsim no longer runs JMT unmodified: the patch is published as source here and redone on each JMT upgrade (the guard in §6.1 makes that loud). | **Chosen** by Asser, 2026-11-18. |
| (b) Regions without fork-joins; upstream the fix and wait. | No patch, but the motivating case waits on JMT's release schedule. | Rejected. |
| (c) No simulation for the QCSC case. With capacity 1 the system is exactly M/G/1 with S = one job's sojourn through an empty network, which qopt can solve analytically. | Exact only for capacity 1; no simulation cross-check. | Kept as a cross-check (§8 uses it as an oracle), not as the feature. |

## 5. Contract

### 5.1 Request

`model.regions` is optional and defaults to none. Example:

```json
"regions": [
  { "name": "qcsc",
    "nodes": ["cpu_init_ps", "fj_pp", "qpu_psq", "gpu_psq", "..."],
    "capacity": 1,
    "classCapacity": { "ps": 1 },
    "whenFull": "wait" }
]
```

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Region name. It appears as `station` in region measure rows. |
| `nodes` | string[] | Member node names (domain names; a `fork-join` counts as one node). |
| `capacity` | int ≥ 1 | Global maximum of customers inside, summed over classes. |
| `classCapacity` | map class → int ≥ 1, optional | Per-class maximum inside. |
| `whenFull` | `"wait"` \| `"drop"`, or a map class → `"wait"`\|`"drop"` | What happens to an arrival that finds the region full. The default is `"wait"` for every class. |

**`whenFull` defaults to `"wait"` (my call).** JMT's own default is to drop open classes. qsim's
motivating case is waiting, and an explicit field is clearer than inheriting JMT's default.

### 5.2 Response

There's no shape change. A region measure is a normal `MeasureResult` row with `station` = the
region name and `class` = the class. The new measure types are requested through the existing
`measures` list:

| Measure | JMT region type | What it reports |
|---|---|---|
| `region-response-time` | Response Time | total time in the region, entry wait included |
| `region-queue-time` | Queue Time | wait at the region entry before admission |
| `region-queue-length` | Number of Customers | customers waiting at the entry plus inside |
| `region-throughput` | Throughput | admitted customers per unit time (a rate) |
| `region-drop-rate` | Drop Rate | customers discarded at a full region per unit time (a rate) |

My calls:
- **Expansion.** Each requested region type expands to one row per region × per class that
  enters the region (a class with an edge into a member from a non-member). Requesting a region
  type for a model with no regions is a 400, consistent with unsupported measure types.
- **Defaults.** With `measures` omitted, region types are *not* added; the defaults stay as they
  are. That keeps existing callers' responses unchanged even if they add a region.
- **No "time inside" measure in v1.** Time inside = `region-response-time` − `region-queue-time`
  (means only). A derived row would need an honest confidence interval, and issue #14 is
  exactly about not implying more than JMT measured. Document the subtraction instead.
- **Rate types.** `region-throughput` and `region-drop-rate` join `MeasureMapper.RATE_TYPED`,
  so their CI handling matches the station `throughput`/`drop-rate`.
- **Station measures on members are unchanged.** `response-time` on a member is still the time
  in that station, not including the region's entry wait. The entry wait is attributed to the
  region only.

### 5.3 Contract invariants (new 422s)

**Region definition**
- `nodes` is non-empty.
- Every member exists and is a `queue`, `delay` or `fork-join` node. Sources and sinks are
  rejected.
- A node belongs to at most one region (JMT has no nesting).
- Region names are unique, pass the existing `checkName` rules (a name can end up in file
  paths if verbose logging touches region measures), and don't collide with any node name,
  because rows share the `station` field. `<name>_inputStation` must not collide with a node
  name either, because JMT creates that node.
- `capacity` ≥ 1. Each `classCapacity` key is a declared class, and each value is ≥ 1.
  - **My call:** reject a value greater than `capacity`. It's harmless, but almost certainly a
    mistake.
- `whenFull` keys, if given as a map, are declared classes.

**Fork-joins.** A `fork-join` member is wholly inside, because its fork, branches and join are
generated together from one domain node. The contract therefore cannot express a fork-join
that crosses the boundary, which is the one case the patch is unsafe for. **No extra check is
needed**, but the writer must list all four generated stations (`fj`, `fj__b<i>`, `fj__join`)
as `regionNode`s, never a subset.

**Partial joins (v1, my call): a `fork-join` member must use `join: "all"`.** The occupancy
argument in §3 assumes the merged parent leaves only after every branch is done. With
`join: "<k>"`, the parent leaves, and decrements the occupancy, while sibling tasks still hold
branch servers inside, so capacity would stop bounding the work inside. It's also unverified
what happens to the late siblings at the border. qopt always sends `"all"`
(`qopt/station.py:1206`). Lift it with a JMT investigation and a golden test.

**Closed classes (v1, my call): a closed class may not be routed into any region member.**
Reason: a closed population preloaded *onto* a member bypasses the entry station, so its jobs
would be decremented on exit without ever being counted in, and the occupancy would go
negative. A closed class merely routed in from outside would probably be fine, since JMT's
default for closed classes is to wait. But nothing has tested that, and the motivating case is
open. The rule also forbids drop for closed classes, which is meaningless anyway. Lift it later
with a golden test.

**Finite-capacity queues (v1, my call): a `queue` with `capacity` set may not be a region
member.** Reason: unverified whether a job dropped *inside* a region decrements the region's
occupancy. If it doesn't, the region slowly fills with ghosts and deadlocks. Lift it after
verifying in `Queue`/`BlockingRegion`, with a test.

**Consistency with #19.** A region doesn't change where a class can go: routing is unchanged,
and JMT redirects transparently. The existing "every reached node has an exit" check and the
writer's routing logic need no change. A member must still have exits per the existing rule.

## 6. Implementation design

### 6.1 The JMT patch (build time)

**What it does.** In `simEngine.Simulation.initialize()`, the six instructions spike-verified
on 1.5.0 bytecode, offsets about 755–904:

| Original | Replacement |
|---|---|
| `instanceof NodeSections/Storage` | `invokestatic <Shim>.storageOrJoin(Object)Z` |
| `checkcast NodeSections/Storage` | removed |
| `invokevirtual Storage.redirectionTurnON(BlockingRegion)` | `invokestatic <Shim>.redirect(Object, BlockingRegion)` |
| `instanceof NodeSections/Linkage` | `invokestatic <Shim>.linkageOrFork(Object)Z` |
| `checkcast NodeSections/Linkage` | removed |
| `invokevirtual Linkage.borderRouterTurnON(BlockingRegion)` | `invokestatic <Shim>.border(Object, BlockingRegion)` |

The shim keeps the original behaviour for `Storage`/`Linkage`, and accepts a `Join` input or a
`Fork` output with no hook. The `Queue`/`Router` branches are untouched. Stack effects match,
so the original stack-map frames stay valid: merge points carry no stack values and the locals
are unchanged. The spike wrote the class with `ClassWriter(cr, 0)` and the frames verified.

**Requirements**
1. **Exact-match guard.** The patcher counts its rewrites and fails the build unless it made
   exactly 6, each inside `initialize()V`.
   - It also checks the input class's SHA-256 against the value recorded for the bundled 1.5.0
     jar. A changed `Simulation.class` (a JMT upgrade) then fails the build with a message
     pointing at this spec, even if 6 matches happened to occur.
2. **Runs in both** `mvn -o test` (local, offline) and `mvn package` (Docker, network). It runs
   after compile (`process-classes`) and writes `target/classes/simEngine/Simulation.class`.
   The shim lives in qsim's own sources (proposed: `qsim.engine.fcr.FcrShim`). It references
   JMT section classes, so it compiles against the jar normally.
3. **Patcher placement (decided by Asser, 2026-11-18): exec-maven-plugin**, bound to
   `process-classes`, running a patcher class with ASM in **plugin scope**. ASM must not ship
   at runtime, and the patcher must not end up in `app.jar`; a separate source root compiled
   for the plugin's classpath only is the builder's call. The local Maven cache has only the
   compiler, resources and surefire plugins, so each dev machine needs **one online `mvn`
   run** to fetch the plugin (and maven-jar/maven-dependency for `package`). After that,
   `mvn -o test` works as before. Docker builds online and is unaffected. The README's build
   section says so. Rejected alternatives:
   - a committed pre-patched `.class` with a regenerate-and-compare test: offline-proof, but a
     generated binary lives in git;
   - an annotation processor on the cached compiler plugin: unusual and fragile.
4. **Fail closed at startup.** `App` (and `SimulationService` construction, so tests see it
   too) verifies that the loaded `simEngine.Simulation` is the patched one, and refuses to
   start otherwise. Suggested marker: the patcher adds a `public static final boolean
   QSIM_FCR_PATCHED = true` field, or the check confirms the class was loaded from qsim's code
   source rather than the JMT jar. Exactly how is the builder's call. A classpath that puts the
   JMT jar first must be caught, not silently produce an unpatched engine, which would then
   reject fork-join regions at runtime with an obscure `NetException`.
5. **Silent shim.** The spike's stderr lines go; at most a debug log.
6. **GPL.** The patcher and shim sources are in the repo. `NOTICE` and the README state that
   `simEngine.Simulation` is modified at build time, what the modification is, and that the
   jar in `lib/` is unmodified.

### 6.2 Model, validator, writer

- **Model.** New record `Region(String name, List<String> nodes, Integer capacity,
  Map<String,Integer> classCapacity, JsonNode/Object whenFull)`, plus
  `NetworkModel.regions` (nullable).
  - `whenFull` accepts a string or a map. The builder chooses the Jackson binding (a custom
    deserializer, or `Object` normalised in the validator).
  - Adding a record component to `NetworkModel` changes its canonical constructor. Keep a
    4-argument constructor so the many existing `new NetworkModel(...)` call sites compile
    unchanged. That's the same pattern as the `SimulationRequest` constructor kept for #15.
- **Validator.** The invariants in §5.3. Same style as today: accumulate messages, one 422.
- **Writer** (`JsimgWriter`), one `<blockingRegion>` per region, after the connections and
  before `<preload>`:
  - the members are expanded: a `fork-join` member contributes `fj`, every `fj__b<i>`, and
    `fj__join`;
  - `globalConstraint maxJobs=capacity`;
  - `globalMemoryConstraint maxMemory="-1"`;
  - a `classConstraint` for each `classCapacity` entry;
  - then the **grouped** per-class children for every declared class: all `dropRules`, all
    `classWeight weight="1"`, all `classSize size="1"`.

  With `regions` null or empty the writer emits nothing, which preserves byte-identity.
- **Measures** (`MeasureMapper`): the five `region-*` types map to `MeasureSpec(name, jmtType,
  referenceNode=<region>, referenceUserClass=<class>, nodeType="region")`.
  - `JsimgWriter.checkMeasures`/`expandedMeasureNode` must accept a region name as a reference
    node for `nodeType=region`; today they resolve node names.
  - `SolutionsParser`: the measure's `station` attribute in JMT's output for a region measure
    needs verifying. It might be the region name, or `<region>_inputStation`. Map it back to the
    region name either way.
- **Engine-failure hardening (my call, small).** On a `solveModel()` exception, `JmtRunner`
  calls `dispatcher.killSimulation()` before wrapping, so a failed run can't leave the watchdog
  thread behind in the long-lived server. Verify that `killSimulation` actually stops the
  watchdog. If it doesn't, document the leak and move on; it's bounded by
  `maxWallClockSeconds`.

## 7. Error handling

All new invalid models are 422s with specific messages (§5.3). Requesting `region-*` measures
without regions is a 400 (unsupported for this model). There are no new engine-error paths,
apart from the startup self-check, which is a hard failure to start.

## 8. Testing

**Unit tests**
- **Writer:**
  - element order, grouped per-class children, the mandatory memory constraint, fork-join
    expansion into all four stations, `classConstraint`;
  - byte-identical output for a model without `regions` (assert against the current XML of the
    existing fixtures).
- **Validator:** every §5.3 rule, in both its accept and reject form.
- **Patch:** the guard fails on a class with fewer or more matches, and the runtime check
  rejects an unpatched class.

**Golden tests against the patched engine.** All open, exponential, and passing in the spike.
Assert that the CI brackets the exact value.

| # | Model | Exact reference | Spike result |
|---|---|---|---|
| G1 | src(λ=0.4) → 2-branch fork-join (rates 1, 1) → sink; region cap 1, **wait** | M/G/1 with S = max of the two branches: E[S]=1.5, E[S²]=3.5. T=3.25, region-queue-time=1.75, fork-join response time=1.5, branch `queue-time`=0 exactly | T 3.269 [3.247, 3.291]; Wq 1.767 [1.734, 1.800] |
| G2 | same, cap 1, **drop** | M/G/1/1 loss, insensitive to the distribution of S: P_drop=ρ/(1+ρ)=0.375, drop rate 0.15, X=0.25, T=1.5 | drop 0.1495, X 0.2499, T 1.497 |
| G3 | same, cap 1000 | identical to the same model without a region (same seed: the spike saw bit-identical means) | identical |
| G4 | src(λ=0.4) → same fork-join; cap **2**, wait | exact CTMC (§8.1): T=2.54545, inside 2.01399 | not run at λ=0.4. At λ=0.55: 10-seed mean T 3.7555, CI [3.747, 3.764] vs exact 3.7604 |
| G5 | same, cap 2, drop | CTMC | at λ=0.55: drop 0.1271 vs 0.12681 |
| G6 | QCSC-shaped, 2 open classes (ps λ=0.15, sp λ=0.10), 12 single-server queues + 2 fork-joins, all in one region, cap 1, wait | multi-class M/G/1 FCFS: ρ=0.65952, Wq=3.12312; T_ps=5.85646, T_sp=5.61836 | T_ps 5.841 [5.784, 5.899], T_sp 5.639 [5.593, 5.685]; every member `queue-time`=0 |
| G7 | same as G6, drop | P_drop=ρ/(1+ρ)=0.39742 for both classes; T_r=E[S_r]=2.73333 / 2.49524 | 0.3985 / 0.3960; T 2.740 / 2.487 |
| G8 | src(λ) → **delay** node with exponential service, in a region cap K, drop | Erlang B (M/G/K/K; a delay inside a cap-K region with drop is a K-server loss system) | not run. It covers delay members, which the spike didn't |

Notes for the builder:
- **G4/G5 are at λ=0.4** (cheaper than the spike's 0.55, still binding: P(full)=0.26). Compute
  the exact cap-2 drop numbers at λ=0.4 with the CTMC in §8.1.
- **Single fixed-seed runs bracket the exact value most of the time but not always.** One cap-2
  run at λ=0.55 landed about 2.9 sd low. For G4, assert across 5 seeds: the cross-seed
  t-interval contains the exact value. For the others, a single fixed seed is fine (the suite is
  deterministic per seed), but don't seed-shop: if a seed fails, investigate before changing
  it.
- **Keep the suite affordable.** The spike's runs took 2–30 s each at precision 0.01. Use
  precision about 0.02 where the CI still discriminates. Total added suite time should stay
  under about 90 s.
- **G6/G7 need the #19 routing fix** (on `main` since e55cc93). The spike first saw class
  mixing without it.
- **G6/G7 topology** (exactly the spike's model, shaped after the paper topology in the
  optimizer repo's `docs/superpowers/specs/2026-07-31-qcsc-example-network-design.md` §3, but
  with the two streams as two classes rather than one class with a probabilistic split). One
  source `src`, one sink `snk`, all queues single-server FCFS exponential. Every station has
  service entries for both classes, so per-class station measures exist everywhere, as in qopt.
  - Class `ps`:
    - `src → cpu_init_ps → fj_pp`;
    - then with probability 0.3 `fj_pp → qpu_psq → gpu_psq → cpu_term_ps`, else
      `fj_pp → gpu_psg → qpu_psg → cpu_term_ps`;
    - then `cpu_term_ps → snk`.
  - Class `sp`:
    - `src → cpu_init_sp`;
    - then with probability 0.3 `cpu_init_sp → qpu_ssq → gpu_ssq → fj_sp`, else
      `cpu_init_sp → gpu_ssg → qpu_ssg → fj_sp`;
    - then `fj_sp → cpu_term_sp → snk`.

  That makes 12 single-server queues: 4 CPU (`cpu_init_ps/sp`, `cpu_term_ps/sp`), 4 QPU
  (`qpu_psq/psg/ssq/ssg`) and 4 GPU (`gpu_psq/psg/ssq/ssg`). The region holds all 12 plus
  `fj_pp` and `fj_sp`. These tests are a self-contained oracle check, not a mirror of qopt's
  live example; qopt sends one class.
- **G6/G7 oracle.** For each class, S is the sum of independent stages: E[S] = Σ means and
  Var = Σ variances, with the max-of-two-exponentials moments for each fork-join (mean
  1/a+1/b−1/(a+b), second moment 2/a²+2/b²−2/(a+b)²). Both tandem orders have the same
  moments. Then pool the classes by λ_r/λ: Wq = λE[S²]/(2(1−ρ)), T_r = Wq + E[S_r]. Exact rates
  used in the spike:
  - CPU stations 4.0, QPU stations 1.5, GPU stations 2.5;
  - `fj_pp` branches (1.0, 2.0), `fj_sp` branches (1.2, 3.0);
  - quantum-first probability 0.3.

### 8.1 Cap-2 CTMC reference

For a 2-branch fork-join with FCFS branches inside a cap-K region, the state is (n waiting
outside, k inside ≤ K, a = jobs with branch 0 done, b = jobs with branch 1 done), with
min(a, b) = 0. Completions are in FCFS order per branch, so the "done" sets are prefixes.

Transitions:
- arrival: k+1 if k < K; else n+1 (wait) or lost (drop);
- branch-i completion at μ_i if its count < k;
- when a ≥ 1 and b ≥ 1, the head job leaves (k, a, b all −1), then one waiting job is admitted.

Solve by Gauss–Seidel with the outside queue truncated; the tail mass is negligible at about
n=300. The spike's solver reproduced cap 1 (3.25000 / 0.37500) and cap 12 ≈ ∞ (2.41667, the
exact two-branch fork-join value (12−ρ)/8 · 1/(μ−λ)) before being trusted.

**Recommendation (my call):** compute the reference numbers once and hardcode them in the
test with this derivation in a comment. Also commit the solver script under `docs/` so the
numbers stay reproducible, rather than porting it to Java.

## 9. Docs

- **README:** the `regions` contract, the five measures, the subtraction for time inside, the
  v1 limits (no closed classes into regions; no finite-capacity member queues), and the patch
  and GPL note.
- **Design spec §5/§6** (2026-07-25): a short pointer to this spec.

## 10. Spike summary (evidence)

- **Patch and engine.** Spike code in `/tmp/fcr-spike` (ephemeral): an ASM patch of 1.5.0
  `Simulation.initialize` plus a shim; models built with qsim's `JsimgWriter` and the region
  injected into the DOM. Unpatched, JMT rejected a fork-join region as expected.
- **Results.** Patched, every case in §8 G1–G7 matched its exact value. Bracketing failed only
  once, the single λ=0.55 cap-2 run discussed in the G4 note.
- **Bug found on the way.** The spike surfaced #18, a multi-class routing bug unrelated to
  regions (fixed in #19).
- **Region measure semantics** (§3 table) and **FCFS admission among equal priorities** were
  confirmed by runs.

## 11. Effort

About 5–6.5 days:
- patch, guard and startup check: about 1.5 days;
- contract and validator: about 1 day;
- writer and measures: about 1 day;
- golden tests: about 1–1.5 days;
- docs: about 0.5 day;
- slack for the two "verify" items (§6.2 parser station name; `killSimulation`).

## 12. Open items for the builder to verify

1. The JMT output `station` attribute for region measures (region name or
   `<region>_inputStation`).
2. Whether `killSimulation()` stops the max-duration watchdog thread.
3. The plugin versions to pin (exec-maven-plugin, and jar/dependency for `package`); fetch them online once.
4. G8's Erlang-B match (the first delay-member run).
