# Design Spec: `qsim-service` — a JMT-backed queueing-network simulation service

Date: 2026-07-25
Status: Approved design — pending final review before implementation planning.

Context: `quantum-optimizer` (`qopt`) currently evaluates a *collection of independent
analytical queueing stations* (G/G/1 mean-value; 2-server heterogeneous fork-join via the
UL approximation). See `docs/superpowers/specs/2026-07-10-optimizer-design.md`. A planned
enhancement is to run the optimizer against a *simulated queueing network* that captures
inter-station coupling (variability propagation through routing) which the independent-station
analytics fundamentally cannot represent. Rather than build a new discrete-event simulator,
we wrap the headless simulation engine of **JMT (Java Modelling Tools)**.

This spec covers **only the simulation service** (subsystem "A"). The `qopt`-side
`SimulationAnalyzer` client and the noise-aware convergence changes (subsystem "B") are a
**separate, later spec**; this service is designed to be consumed by anything, not just `qopt`.

---

## 1. Goal

Provide a small, stateless HTTP/JSON service that, given a description of a queueing network,
runs one discrete-event simulation of it using JMT's headless engine and returns per-station /
per-class performance measures with confidence intervals.

Primary purpose: enable optimization on a **close-to-system** (simulated) environment. The
existing analytic models are kept alongside as a validity / robustness cross-check — having
both lets us answer how good the approximations are.

## 2. Scope

**In scope (v1):**

- A single synchronous, blocking endpoint: `POST /simulate` (plus `GET /health`).
- A **domain-level queueing-network JSON contract** (JMT-agnostic), covering: mixed open/closed
  job classes; multiple sources and sinks; `queue` (1..N servers), `fork-join` (heterogeneous
  branches), `delay` (infinite-server), `source`, `sink` node types; probabilistic routing;
  named or moment-based (mean + SCV) service and inter-arrival distributions.
- Translation of that contract to JMT's JSIMG (`<archive>/<sim>`) XML, execution of the
  headless engine, and translation of JMT's results XML back to domain JSON.
- JMT's native per-measure confidence-interval stopping criteria plus min/max samples,
  simulated-time / event caps, and a wall-clock watchdog.
- Containerization (Docker), running fully headless.

**Out of scope (deferred / owned elsewhere):**

- **Replication and cross-run aggregation.** A request is *one* simulation run (one seed). The
  caller decides how to run many (e.g. multiple containers in parallel with different seeds)
  and how to aggregate. The service exposes `seed` and returns enough per-run detail
  (mean, CI, samples, variance) for the caller to aggregate correctly.
- The `qopt` `SimulationAnalyzer` client and noise-aware fixed-point convergence (separate spec).
- Richer routing strategies (round-robin, JSQ, load-dependent, class-switch), tail/percentile
  metrics, closed-form/analytic solving. The contract is designed so these can be added without
  breaking existing callers.

## 3. Repository & licensing

- **New standalone repository, working name `qsim-service`.**
- **License: GPL v2 (or later).** JMT is GPL v2-or-later, and this service links JMT engine
  classes in-process, so it is a GPL derivative. Keeping it in its own repo behind an HTTP
  boundary is the licensing firewall: `qopt` (Apache 2.0) consumes it as a plain HTTP client
  and never touches JMT code, so `qopt` stays clean. The JSON contract is the public interface.
- The unmodified `JMT-singlejar-1.4.0.jar` is bundled in the image; honor GPL obligations
  (license text, notices, offer of source).

## 4. Architecture

A single long-lived JVM per container (warm, to avoid per-request JVM cold start), **stateless**,
handling **one simulation at a time** (concurrency 1; parallelism comes from running multiple
containers). Three layers:

1. **HTTP frontend** — `POST /simulate` (blocking until the run completes) and `GET /health`.
   Lightweight: JDK built-in `com.sun.net.httpserver` + Jackson for JSON. No heavy framework.
2. **Contract layer** — validate the incoming domain-QN JSON, apply defaults, enforce invariants
   (see §5.3), reject malformed input with structured errors.
3. **Translation + execution** — domain-QN JSON → JSIMG XML (validated against
   `SIMmodeldefinition.xsd`) → run JMT's engine once via `jmt.engine.simDispatcher.DispatcherJSIMschema`
   (`setTerminalSimulation(true)`, `setSimulationSeed`, `setSimulationMaxDuration`) → parse JMT's
   `<solutions>/<measure>` results XML → domain-QN JSON. **JMT is fully quarantined in this layer.**

Rationale for stateless single-run (rather than an in-service worker pool / replication engine):
it dissolves the JMT-static-state concurrency risk entirely, keeps the service simple, and matches
the "caller decides how to run many" model.

**Residual implementation note (not a contract concern):** a warm JVM serving sequential requests
must start each simulation from clean JMT engine state (JMT has some static/global state). The
JMT GUI reuses one JVM across many runs, so this is expected to be fine; verify during
implementation. Fallback if a leak surfaces: spawn a fresh JMT subprocess per request (the HTTP
contract is unchanged either way).

## 5. JSON contract

### 5.1 Request — `POST /simulate`

```jsonc
{
  "model": {
    "name": "mixed-network",

    "classes": [
      { "name": "web",   "type": "open" },                 // anchored to whichever source lists it
      { "name": "batch", "type": "closed",
        "population": 20, "referenceStation": "think" }     // referenceStation optional; defaults sensibly
    ],

    "nodes": [
      { "name": "src1", "type": "source",
        "arrivals": {                                       // inter-arrival-time distribution, per class
          "web": { "distribution": { "type": "exponential", "rate": 10.0 } }
        } },

      { "name": "q1", "type": "queue",
        "servers": 1, "scheduling": "fcfs", "capacity": null,   // capacity null = infinite; N = finite/blocking
        "service": {
          "web":   { "distribution": { "type": "exponential", "rate": 12.0 } },
          "batch": { "distribution": { "mean": 0.5, "scv": 2.0 } }   // moment form → Gamma
        } },

      { "name": "fj", "type": "fork-join",
        "branches": [
          { "service": { "web": { "distribution": { "type": "exponential", "rate": 8.0  } } } },
          { "service": { "web": { "distribution": { "type": "exponential", "rate": 16.0 } } } }
        ],
        "join": "all" },                                    // "all" = wait for every branch (JMT NormalJoin)

      { "name": "think", "type": "delay",
        "service": { "batch": { "distribution": { "type": "exponential", "rate": 0.2 } } } },

      { "name": "sink", "type": "sink" }
    ],

    "routing": {                                            // per class; >1 edge from a node ⇒ probabilities
      "web":   [ { "from": "src1", "to": "q1" },
                 { "from": "q1",   "to": "fj" },
                 { "from": "fj",   "to": "sink" } ],
      "batch": [ { "from": "think", "to": "q1", "probability": 1.0 },
                 { "from": "q1",    "to": "think", "probability": 1.0 } ]
    }
  },

  "seed": 12345,                                            // explicit ⇒ reproducible; caller varies per replication

  "stopping": {
    "alpha": 0.05, "precision": 0.05,                       // default per-measure CI target (95%, ±5%)
    "minSamples": 10000, "maxSamples": 1000000,
    "maxSimulatedTime": null, "maxEvents": null,
    "maxWallClockSeconds": 120,                             // watchdog kill (JMT -maxtime)
    "disableStatisticStop": false
  },

  "measures": ["response-time", "utilization", "throughput", "queue-length"],  // omit ⇒ default set

  "secondMoments": false                                    // true ⇒ per-sample logging, so variance/stdDev
                                                            // come back populated (§5.2 as-built note)
}
```

**Distributions.** Two interchangeable forms wherever a distribution is expected (service and
inter-arrival):

- **Named:** `{ "type": "exponential", "rate": r }`, plus `deterministic`, `erlang`, `gamma`,
  `hyperexp`, `lognormal`, `pareto`, `weibull`, `uniform`, `normal`, `map`/`mmpp2` (correlated),
  `replayer` (trace) — the JMT `jmt.engine.random.*` vocabulary.
- **Moment:** `{ "mean": m, "scv": c }`. Mapped to a canonical distribution (§6.2): `scv==1` →
  Exponential; `scv==0` → Deterministic; otherwise → **Gamma** (exact on the first two moments).

Exponential inter-arrival ⇒ Poisson arrivals. `map`/`mmpp2` allow correlated/bursty arrivals.

### 5.2 Response

```jsonc
{
  "modelName": "mixed-network",
  "solutionMethod": "simulation",
  "seed": 12345,
  "wallClockSeconds": 8.3,
  "completed": true,                    // false ⇒ a cap fired before all CIs converged
  "measures": [
    { "station": "q1", "class": "web", "type": "response-time",
      "mean": 0.42, "lower": 0.40, "upper": 0.44,
      "alpha": 0.05, "precision": 0.048, "success": true,   // success = this measure's CI target met
      "samplesAnalyzed": 45000, "samplesDiscarded": 1200,
      "variance": 0.011, "stdDev": 0.105 }
    // ... one per (station × class) requested, plus system-level (station:"system")
    //     and fork-join response time for fork-join nodes
  ]
}
```

Every measure carries `mean` + CI + `samples` + `variance` — the per-run detail a caller needs to
run independent replications and aggregate them correctly (see §9). `success:false` /
`completed:false` tell the caller when a run did not reach its target so it can extend or discard it.

> **As built** (correction, not a design change — two fields above and in the §5.1 example overstate
> what ships).
>
> - **`precision` in the response echoes the request, not the achieved value.** The `0.048` against a
>   requested `0.05` in the example implies achieved precision; JMT writes the requested parameter back
>   into its output and the parser reports it verbatim. A caller cannot read achieved precision off the
>   response — compute it from `(upper - lower) / 2 / mean` instead.
> - **`variance` and `stdDev` are populated only on request** — they were unconditionally `null`
>   until issue [#15](https://github.com/modeling-analysis/qsim-service/issues/15). The later
>   conditional branch in `XMLSimulationOutput` that writes them fires only for a measure marked
>   `verbose="true"` in a terminal simulation, where JMT re-reads the per-sample CSV it logged. The
>   service now asks for that per measure: always for `interarrival-time`, and for every other
>   non-rate-typed measure when the request carries `"secondMoments": true`. Off by default, because
>   the sample log costs ~40 bytes per sample and 25-30% wall clock. `throughput` and `drop-rate`
>   report `null` even then (see §6.1). The `precision` bullet above is still open.
>
> `success` *does* now mean "this measure's CI target met", as the example claims — but only since
> [#12](https://github.com/modeling-analysis/qsim-service/issues/12); before that it was set true regardless of
> achieved precision. The `precision` bullet is tracked on
> [#14](https://github.com/modeling-analysis/qsim-service/issues/14) and remains open; when it lands, restore
> the §5.1 example to the achieved-value behaviour it already describes.

Measure `type` values (v1): `response-time`, `residence-time`, `queue-time`, `queue-length`,
`utilization`, `throughput`, `arrival-rate`, `drop-rate`, plus system-level (`system-response-time`,
`system-throughput`) and `fork-join-response-time`.

> **As built** (correction, not a design change — the list above overstates what shipped). The
> implemented set is `MeasureMapper.SUPPORTED`: `response-time`, `residence-time`, `queue-time`,
> `queue-length`, `utilization`, `throughput`, `drop-rate`, `system-response-time`, plus
> `interarrival-time` (issue #15). Three entries above were never implemented —
> `arrival-rate`, `system-throughput`, and `fork-join-response-time`.
>
> `interarrival-time` replaces the `arrival-rate` the list names, and is not a rename: the spec's
> `arrival-rate` would have been a rate with `variance: null`, which is the one thing a caller asking
> about an arrival process cannot use. Reporting the mean *time* between arrivals, with `variance` and
> `stdDev` beside it, lets the caller compute `scv = variance / mean^2` — the input a decomposition
> method needs. The service deliberately does not expose `scv` itself: every other measure reports
> moments and leaves the derived quantities to the caller.
>
> There is **no separate `fork-join-response-time` type**: `response-time` requested on a `fork-join`
> node *is* the fork-to-join sojourn, translated to JMT's `"Fork Join Response Time"` measure anchored
> on the fork station (issue #6). Every other station measure on a fork-join node is still taken at
> the internal join station (issue #8).
>
> Also divergent: system-level measures come back with `station: ""`, not `station: "system"` as the
> §5.2 example comment says.

### 5.3 Contract invariants (enforced by the contract layer)

- Every **open** class is listed in **exactly one** source's `arrivals` (JMT anchors an open class
  to a single reference source). Independent general (non-Poisson) arrivals at several entry points
  ⇒ model as one open class + one source per entry point.
- Every **closed** class has a `population`; `referenceStation` defaults to the class's `delay`/think
  node if present, else its first routed station.
- All routing `from`/`to` targets name existing nodes; per node per class, edge `probability` values
  sum to 1 (a single edge defaults to 1.0).
- `queue.servers >= 1`; `capacity` is null (infinite) or a positive integer (finite ⇒ blocking/drop).

## 6. Translation & moment-matching

### 6.1 Domain-QN → JSIMG mapping

| Domain concept        | JMT / JSIMG realization |
|-----------------------|-------------------------|
| open class            | `userClass type="open"`, `referenceSource` = its source node |
| closed class          | `userClass type="closed"` + `customers`(population); reference station |
| `source`              | node: `RandomSource` (arrival `ServiceTimeStrategy`) + `Router` |
| `queue`               | node: `Queue` (capacity/drop) + `Server` (`maxJobs`=servers, scheduling) + `Router` |
| `fork-join`           | `Fork` node → N branch `Server` stations → `Join` (`NormalJoin`) |
| `delay`               | node: `Queue` + `Delay` (infinite server) + `Router` |
| `sink`                | `JobSink` |
| routing edges         | `<connection>` + per-class `EmpiricalStrategy` (probabilities) in the `Router` |
| measures + stopping   | `<measure alpha precision>` + `<sim>` attrs (`seed`,`maxSamples`,`minSamples`,`maxSimulated`,`maxEvents`,`disableStatisticStop`) + `DispatcherJSIMschema.setSimulationMaxDuration` (wall clock) |
| `interarrival-time`   | `<measure type="Arrival Rate">` on the station, `referenceUserClass` set (per class) — read as inter-event times, not as a rate (below) |
| second moments        | `<measure verbose="true">` + `<sim logPath>` + `setTerminalSimulation(true)`; the service creates one throwaway log directory per run and deletes it in a `finally` |

> **Two engine conventions worth not rediscovering** (both cost real debugging time):
>
> - **`<measure alpha>` carries the significance level, not the confidence level.** JMT passes it
>   straight to `TStudent.ICDF`, whose contract is "significance in, two-sided critical value out".
>   Write `1 - alpha` and it returns the negated *one-sided* quantile, which makes the half-width
>   negative; `NewDynamicDataAnalyzer.HWtest` compares `precision > confInt / extMean` with no
>   absolute value, so the stopping rule then passes unconditionally and every interval comes back
>   inverted and too narrow. See [#12](https://github.com/modeling-analysis/qsim-service/issues/12).
> - **`minSamples` is read off `<sim>` but is absent from the bundled XSD.** `SimLoader` pulls it via
>   `getAttribute("minSamples")` exactly like `maxSamples`; the schema is simply stale. Emitting it is
>   correct and costs one non-fatal validation complaint per run from the engine's own loader. See
>   [#10](https://github.com/modeling-analysis/qsim-service/issues/10).
> - **`"Arrival Rate"` and `"Throughput"` are `InverseMeasure`s: their `meanValue` and their `mean`
>   are in different units.** The raw samples JMT collects for either are the times *between* events;
>   `meanValue` is `1.0 / analyzer.getMean()` of those times, i.e. a rate, while `mean`, `variance`
>   and `standardDeviation` are moments of the times themselves. Mixing the two — dividing the rate's
>   square into the time-domain variance — gives a number about 123x wrong (118.7 where the true SCV
>   was 0.961). So `SolutionsParser` reads `mean` for `interarrival-time` and `meanValue` for
>   everything else (`MeasureMapper.RAW_SAMPLE_MEAN`), and suppresses `variance`/`stdDev` entirely for
>   the rate-typed measures that keep reporting a rate (`MeasureMapper.RATE_TYPED`: `throughput`,
>   `drop-rate`). It also drops the confidence interval on `interarrival-time`: JMT computes the
>   interval around the *rate*, so it would not bracket the mean time reported next to it.
> - **Second moments need `verbose="true"` *and* a terminal simulation *and* a writable `logPath`.**
>   `XMLSimulationOutput.writeMeasure` only emits `variance`/`standardDeviation` when it can re-read
>   the measure's per-sample CSV, which JMT writes as `<logPath>/<measure name>.csv` and never deletes
>   — hence the per-run directory, and hence the `[A-Za-z0-9._-]{1,64}` guard on node and class names
>   in `ContractValidator`, since a measure name is built from them and becomes a filename.
>   `StatisticalOutputsLoader` starts reading at `discardedSamples`, so the warm-up transient is
>   excluded. A verbose measure that collected no samples makes JMT log a `FileNotFoundException` at
>   SEVERE and omit the attributes; the run and every other measure survive, and the measure comes
>   back with `variance: null`.
> - **An arrival measure on a `fork-join` node must stay anchored on the fork station,** like
>   `response-time` (issue #6) and unlike every other station measure (issue #8). The join sees one
>   arrival per sibling branch, so anchoring there reports the branch arrival rate rather than the
>   region's: 0.248 against the fork's 0.083 on a three-branch model.

Node sections are instantiated reflectively by JMT (`jmt.engine.NodeSections.*`); the translation
layer emits the section class names and typed parameter blocks the loader expects.

### 6.2 Moment form → distribution

- `scv == 1` → Exponential(rate = 1/mean)
- `scv == 0` → Deterministic(mean)
- otherwise → **Gamma**(shape = 1/scv, scale = mean·scv) — matches mean and SCV exactly for any
  scv > 0. (Exponential is the shape-1 special case; chosen over an Erlang/hyperexp split for
  exactness and simplicity.)

## 7. Error handling

The service must produce **trustworthy numbers or a clear failure — never a silent bad answer.**

| Condition | Response |
|-----------|----------|
| Malformed / schema-invalid request JSON | **400** with field-level detail |
| Semantic model error caught pre-run (dangling routing target, open class with no source, open station with arrival rate ≥ total service capacity, probabilities not summing to 1) | **422** with a specific message |
| JMT load / XSD-validation failure | **422**, JMT's error translated |
| Simulation runtime error, or `HeadlessException` on an abnormal JMT path | **500** with captured detail |
| Watchdog (`maxWallClockSeconds`) fires before CIs converge | **200**, `completed:false`, partial measures with `success:false` — caller decides whether to trust / re-run |

Clients should set an HTTP read timeout greater than `maxWallClockSeconds`.

## 8. Testing

- **Translation unit tests:** domain JSON → JSIMG XML, validated against `SIMmodeldefinition.xsd`;
  snapshot the emitted XML for representative models.
- **Golden analytic checks (also the validity cross-check the project cares about):** M/M/1 — assert
  the simulation CI brackets the closed forms (E[T] = 1/(μ−λ), U = ρ, X = λ). Fork-join within known
  bounds / `qopt`'s UL approximation. Doubles as the correctness gate.
- **Moment-matching tests:** Gamma / Exponential / Deterministic produce the target mean and SCV
  (empirically, within CI).
- **Determinism:** identical `seed` ⇒ identical results.
- **Headless:** runs under `-Djava.awt.headless=true` with no display.
- **Error-path tests** for each row of §7.
- **Integration fixture:** the shipped `qopt` 3-station mixed network (M/M/1 + M/D/1 + fork-join).

## 9. Caller-side replication (informative — not implemented here)

Because the service returns one run's per-measure `mean`, `variance`, and `samples`, a caller can run
K independent replications (K seeds, e.g. K containers) and aggregate with the textbook
independent-replications method (Law & Kelton): the point estimate is the unweighted mean of the K
per-run means, and the cross-run CI is a Student-t interval on those K values. This works uniformly
for JMT's standard indices because they are all means (response time, queue length, utilization =
time-average fraction, throughput = rate). Genuinely non-linear measures (percentiles, max) cannot be
averaged — but v1 exposes none. Unequal-length runs can be precision-weighted using the returned
per-run `samples`/`variance` — which for measures other than `interarrival-time` means sending
`"secondMoments": true` (§5.2 as-built note), and for `throughput`/`drop-rate` is not available at
all, so weight those by `samples` alone.

## 10. Tech & deployment

- **Language:** Java (required for warm JVM + JMT classes). **Build:** Maven. **Target:** Java 17 LTS
  (verify JMT 1.4.0 runs on 17 during implementation; fall back to 11/8 if needed).
- **HTTP:** JDK `com.sun.net.httpserver` + Jackson (one endpoint, minimal deps). Javalin is an
  acceptable alternative if richer routing is wanted.
- **JMT:** bundle `JMT-singlejar-1.4.0.jar`; invoke `jmt.engine.simDispatcher.DispatcherJSIMschema`
  (the GUI-free path). Run with `-Djava.awt.headless=true`.
- **Container:** `eclipse-temurin:17-jre` base, headless, expose `:8080`. Config via env vars
  (port, default `stopping` parameters, temp dir for model/result files).

## 11. Open items to confirm during implementation

- JMT 1.4.0 JVM compatibility (target 17).
- JMT engine state cleanliness across sequential runs in one JVM (§4 residual note); adopt
  subprocess-per-request fallback only if a leak is observed.
- Exact JMT section-parameter blocks for `Gamma`, `MAP`/`MMPP2`, and `Fork`/`Join` strategies
  (drive from the `examples/jsim/qn_models/*.jsimg` templates, e.g. `open_1class_3stat_fork.jsimg`).

### 11.1 Deferred from the arrival-process work (issue #15)

Three things a caller might reasonably expect from `interarrival-time` that are not built:

- **A confidence interval on the mean interarrival time.** JMT's interval is around the arrival rate,
  and reporting `[1/upper, 1/lower]` would be a reciprocal transform of a *t*-interval — valid for the
  median, only approximate for the mean, and easy to misread as exact. Deferred rather than
  approximated; `alpha` and the sample counts still describe the run.
- **An `interdeparture-time` type.** JMT has no departure-process measure; the natural substitute is
  `interarrival-time` at the downstream station, which is exactly the departure stream in a tandem.
  A real one would mean a synthetic downstream probe or an engine-side measure.
- **Measured service-time moments.** JMT 1.4.0 collects no service-time samples of its own, so there
  is nothing to parse: a caller wanting service-time variability has to read it off the distribution it
  supplied. Not a parser gap — the measure does not exist in the engine.
