# qsim-service examples

Runnable examples that drive `qsim-service` as a plain HTTP/JSON API — each one starts
a real simulation run and reads back performance measures. They assume a service is
already running and are not wired into `mvn test`.

## Starting the service

Either of these works; Docker matches the top-level README and is the portable path.

```bash
# Option A — Docker (portable)
docker build -t qsim-service:dev .
docker run --rm -d -p 8080:8080 --name qsim qsim-service:dev
# ... run examples ...
docker stop qsim

# Option B — local jar (faster iteration)
mvn -q -DskipTests package
java -Djava.awt.headless=true \
  -cp "target/qsim-service.jar:target/dependency/*:lib/JMT-singlejar-1.5.0.jar" \
  qsim.http.App &
```

Verify readiness before running any example: `curl -sf localhost:8080/health` →
`{"status":"ok"}`.

## `QSIM_URL`

Every driver reads the service base URL from the `QSIM_URL` environment variable,
defaulting to `http://localhost:8080` if unset. Set it if your service is running
elsewhere:

```bash
export QSIM_URL=http://localhost:8080
```

## Examples

- [`machine-repairmen/`](machine-repairmen/) — closed-chain machine-availability
  model, swept over population size and repair coefficient-of-variation with a
  single repairman, with bash/curl, Python, and Go drivers.

## Shared components

Same-language helpers are kept inside each example for now; when a second example is
added, common request/response types move up to `examples/<lang>/common/`.
