# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Bring in the bundled JMT jar first so the system-scope dependency resolves.
COPY lib/ lib/
COPY pom.xml .
# Warm the dependency cache for faster rebuilds. The system-scoped JMT jar is
# provided via systemPath (see pom.xml) and can't be resolved from a repository,
# so it's excluded here and this step is intentionally non-fatal — `mvn package`
# re-resolves anything missing anyway.
RUN mvn -q -DskipTests -DexcludeArtifactIds=jmt-singlejar dependency:go-offline || true
COPY src/ src/
# Package + copy runtime dependencies (see pom dependency-plugin, Task 1).
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/qsim-service.jar app.jar
COPY --from=build /src/target/dependency/ dependency/
# System-scoped JMT is excluded from copy-dependencies; bring it explicitly.
COPY --from=build /src/lib/JMT-singlejar-1.5.0.jar dependency/JMT-singlejar-1.5.0.jar
COPY NOTICE LICENSE ./
ENV QSIM_PORT=8080
EXPOSE 8080
# Headless is also set in App.main; -Djava.awt.headless=true is belt-and-suspenders.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-cp", "app.jar:dependency/*", "qsim.http.App"]
