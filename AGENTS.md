# Repository Guidelines

## Project Structure & Module Organization
- **Source**: Core Vert.x leader election code lives in `src/main/java/com/inqwise/leader`, separated into `LeaderConsensus` runtime logic and `LeaderConsensusOptions` configuration.
- **Resources & Generated Code**: Use `src/main/resources` for packaged assets and `src/main/generated` when Maven produces sources; clean output is written to `target/`.
- **Tests**: Integration-style unit tests sit in `src/test/java/com/inqwise/leader`, mirroring the production package; shared fixtures belong in `src/test/resources`.
- **Build Meta**: Maven configuration in `pom.xml` defines Java 21, Vert.x 4.5, Log4j 2.22, and the Surefire test runner.

## Build, Test, and Development Commands
- `mvn clean compile`: Rebuilds the project from scratch and regenerates any sources.
- `mvn test`: Runs the JUnit 5 + Vert.x test suite; required before any pull request.
- `mvn package`: Produces the distributable JAR with attached sources and Javadoc.
- `mvn clean verify`: Preferred for release validation; executes tests and Maven enforcer checks.

## Coding Style & Naming Conventions
- **Language**: Target Java 21; ensure your IDE uses that bytecode level.
- **Formatting**: Keep the existing tab-based indentation (tabs for blocks, spaces for alignment) and wrap lines around 120 columns.
- **Naming**: Classes/records use PascalCase, methods and variables camelCase, constants UPPER_SNAKE_CASE; keep everything under the `com.inqwise.leader` package unless justified.
- **Logging & Nullability**: Use Log4j (`LogManager.getLogger`) for diagnostics and prefer `Objects.requireNonNull` over manual checks.

## Testing Guidelines
- **Frameworks**: Tests leverage JUnit 5 with the Vert.x JUnit extension; structure new tests as `*Test.java` classes under the mirrored package.
- **Asynchronous Coordination**: Reuse `VertxTestContext` and timers as in `LeaderConsensusTest` to await cluster events.
- **Execution**: Run `mvn test` locally before pushing; aim to keep coverage for new public APIs at parity with existing examples.

## Commit & Pull Request Guidelines
- **Commit Messages**: Follow the observed `type: summary` style (e.g., `fix: guard null leaderId`); keep subjects under 72 characters.
- **Scope**: Group related changes logically; avoid mixing formatting-only adjustments with functional updates.
- **Pull Requests**: Provide a problem statement, implementation notes, and test evidence; link GitHub issues or discussions and include reproduction steps or screenshots when relevant.
- **Validation**: Confirm Maven builds succeed on a clean workspace (`mvn clean verify`) and note any deviations explicitly in the PR description.

## Environment & Tooling Notes
- **Prerequisites**: Install Maven ≥ 3.6.3 and JDK 21; ensure `JAVA_HOME` points to the matching runtime.
- **Cluster Simulation**: Local tests create Vert.x verticles in-process—no external services are required, but keep test timers conservative to avoid flakes.
- **Security**: Dependencies are managed via Maven; monitor `pom.xml` for Snyk or Dependabot alerts and run `mvn versions:display-dependency-updates` when refreshing libraries.
