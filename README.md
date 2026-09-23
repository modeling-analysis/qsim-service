# qsim-service

A small, stateless HTTP/JSON service that runs one discrete-event simulation of a
queueing network using the headless [JMT](https://jmt.sourceforge.net/) engine and
returns per-station / per-class performance measures with confidence intervals.

**License: GPL v2 or later.** This service links JMT (GPL) in-process, so it is a GPL
derivative. It is consumed over HTTP as a plain JSON API — callers (e.g. an Apache-2.0
optimizer) never touch JMT code. See `NOTICE`.

## Build

```bash
mvn package            # runs the full test suite (requires the JMT engine, headless)
```

The unmodified `JMT-singlejar-1.4.0.jar` is bundled in `lib/` and referenced as a
system-scoped Maven dependency.

## Run

Two options — a container, or a plain Java process. Both start the same server and read
the same environment variables, except that overriding `QSIM_PORT` under Docker also
needs a matching `-p` mapping (e.g. `-e QSIM_PORT=9090 -p 9090:9090`).

### As a Docker container

```bash
docker build -t qsim-service:0.1.0 .
docker run --rm -p 8080:8080 qsim-service:0.1.0
```

### As a local Java process

Requires a JDK 17 or later. `mvn package` (above) writes `target/qsim-service.jar` and
copies the runtime dependencies to `target/dependency/`. The bundled JMT jar is
*system*-scoped, so it is not copied there and has to be named on the classpath
explicitly:

```bash
mvn package
java -cp "target/qsim-service.jar:target/dependency/*:lib/JMT-singlejar-1.4.0.jar" \
     qsim.http.App
```

The jar has no `Main-Class`, so run it with `-cp` and the main class rather than
`java -jar`. Override the port and other settings with the env vars below:

```bash
QSIM_PORT=9090 java -cp "target/qsim-service.jar:target/dependency/*:lib/JMT-singlejar-1.4.0.jar" \
     qsim.http.App
```

`App.main` sets `java.awt.headless=true` itself, so no extra flag is needed. Stop the
server with Ctrl-C.

Configuration via env vars: `QSIM_PORT` (default 8080), `QSIM_DEFAULT_ALPHA`,
`QSIM_DEFAULT_PRECISION`, `QSIM_DEFAULT_MIN_SAMPLES`, `QSIM_DEFAULT_MAX_SAMPLES`,
`QSIM_DEFAULT_MAX_WALLCLOCK_SECONDS`, `QSIM_TEMP_DIR`.

### The sample floor, and the engine log line it causes

`stopping.minSamples` is a floor on the samples a measure must collect before the
confidence-interval rule is allowed to stop the run. The service always fills it in — from
`QSIM_DEFAULT_MIN_SAMPLES` (10,000) when a request omits it — so **every** run carries a
floor: a model that would have converged in fewer samples now keeps going to the floor,
returning a tighter interval for more wall-clock time. Send `"minSamples": 0`, or set
`QSIM_DEFAULT_MIN_SAMPLES=0`, for no floor.

A floor above `maxSamples` is rejected with 400. Inside the engine the ceiling wins, so
such a run would stop short of the floor and report `completed: false` with nothing to
explain why. A `maxSamples` below 1 is rejected too — the engine would end at its first
sample, so the run cannot produce anything.

Because the attribute is always written, every simulation prints one non-fatal line to
stderr — including runs with the floor set to 0:

```
[Error] :2:<column>: cvc-complex-type.3.2.2: Attribute 'minSamples' is not allowed to appear in element 'sim'.
```

The column tracks the length of the attributes that precede it, so it differs per model;
match on `Attribute 'minSamples' is not allowed` if you need to filter the line out. It is
benign. `minSamples` is a real control that JMT's model loader reads and honours, but the
XSD bundled in the JMT jar never declared the attribute, so the engine's own validation
pass complains about it while loading the model anyway. Suppressing it would mean forking
all six bundled schemas. See the `ENGINE_ONLY_SIM_ATTRS` note in `JsimgWriter` and issue
#10.

## API

### `GET /health` → `{"status":"ok"}`

### `POST /simulate`

Request and response contract: see the design spec at
`docs/superpowers/specs/2026-07-25-qsim-service-design.md` §5. Minimal M/M/1 example:

```bash
curl -X POST localhost:8080/simulate -H 'Content-Type: application/json' -d '{
  "model": {
    "name": "mm1",
    "classes": [{"name":"web","type":"open"}],
    "nodes": [
      {"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
      {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"exponential","rate":2.0}}}},
      {"name":"snk","type":"sink"}
    ],
    "routing": {"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}
  },
  "seed": 12345,
  "measures": ["utilization","response-time","throughput"]
}'
```

**Supported `measures`.** Every station measure is reported per class, one row per
node/class pair. Omit the field and you get `response-time`, `utilization`, `throughput`,
`queue-length`.

| Measure | What it reports |
|---|---|
| `response-time` | time in the station, queueing included (fork-to-join sojourn on a `fork-join` node) |
| `residence-time` | response time weighted by visits |
| `queue-time` | waiting time before service |
| `queue-length` | number of customers at the station |
| `utilization` | busy fraction of the station's servers |
| `throughput` | completions per unit time (a rate) |
| `drop-rate` | losses per unit time at a finite-capacity queue (a rate) |
| `interarrival-time` | mean time between arrivals at a station, per class — `variance` and `stdDev` included, so `scv = variance / mean^2` |
| `system-response-time` | end-to-end response time for the whole network, per class |

Each response measure carries `mean`, CI (`lower`/`upper`), `alpha`, `precision`, `success`,
`samplesAnalyzed`, `samplesDiscarded`, `variance`, `stdDev`. `completed:false`
means a cap fired before all CIs converged — the caller decides whether to trust or re-run.

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

**`precision` binds, so budget for it.** `stopping.precision` is a *relative* CI half-width target,
and a run continues until it is met or a cap fires. Halving it costs roughly 4x the samples, so a
tight target is easily the difference between a sub-second run and a multi-minute one: on an M/M/1 at
`rho=0.8`, `precision: 0.10` converges in ~55k samples while `precision: 0.005` needs ~22M. Set
`maxSamples` and `maxWallClockSeconds` to bounds you can actually wait for, and read `completed` to
find out whether a cap fired first. (Before [#12](https://github.com/modeling-analysis/qsim-service/issues/12)
the target was never enforced, so runs returned almost immediately and this cost was invisible.)

**Fork-join measures:** on a `fork-join` node, `response-time` is the whole fork-to-join sojourn —
the time from the job splitting to all required branches having rejoined — not any one branch's or
the join's own residence time. `interarrival-time` is likewise measured at the fork, so it is the gap
between jobs *entering* the region and not the gap between sibling branches arriving at the join.
Per-branch numbers are not reported separately; a fork-join's measures come back under its own node
name.

> **Caveat:** `response-time` and `interarrival-time` are currently the *only* measures with
> fork-join-region semantics.
> `queue-length`, `residence-time`, `queue-time`, `utilization`, `throughput` and `drop-rate` on a
> fork-join node are measured at its internal join station, so e.g. `queue-length` is the join's
> synchronization backlog rather than the fork-join's in-flight population. Do not read those as
> region figures — see [#8](https://github.com/modeling-analysis/qsim-service/issues/8).

**Node and class names** must be 1-64 characters from `[A-Za-z0-9._-]` and start with a letter or
digit. They are not free text: JMT names each per-sample log after the measure it belongs to, so a
node or class name becomes part of a filename on the server.

**Distributions:** named (`{"type":"exponential","rate":r}`, `{"type":"deterministic","value":v}`)
or moment form (`{"mean":m,"scv":c}` → Exponential/Deterministic/Gamma). v1 implements these
three forms; the remaining JMT named distributions are a mechanical registry extension.

**Replication:** one request = one seed = one run. Run many seeds (e.g. many containers) and
aggregate per the independent-replications method (design spec §9).

## Examples

Worked, runnable examples live in [`examples/`](examples/). The
[machine-repairmen](examples/machine-repairmen/) example drives a closed network — an
infinite-server delay station feeding a single-server FCFS repair queue — three
equivalent ways: [bash/curl](examples/machine-repairmen/bash/),
[python](examples/machine-repairmen/python/) (with a plot), and
[go](examples/machine-repairmen/go/). It also demonstrates the independent-replications
workflow above (each point averaged over independent seeds). See
[`expected-output.md`](examples/machine-repairmen/expected-output.md) for a sample run.

## Error responses

| Status | Meaning |
|--------|---------|
| 400 | Malformed JSON, or unsupported measure type |
| 422 | Semantic model error (dangling routing, open class without a source, probabilities not summing to 1) or JSIMG schema failure |
| 500 | Simulation engine failure |
| 200 + `completed:false` | Watchdog fired before convergence — partial measures |
