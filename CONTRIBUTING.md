# Contributing to MongrelDB Kotlin

Thanks for taking the time to help the MongrelDB Kotlin client. This document
describes how to propose a change, what we expect from a pull request, and the
coding standards that apply to the codebase.

If anything here is unclear or out of date, open an issue or a PR.

## Code of conduct

Be kind, be specific, assume good faith. Disagree about the technical details,
not the person. Public reviews stay focused on the diff.

## How to propose a change

The MongrelDB Kotlin client uses a standard **fork -> branch -> pull request**
workflow on GitHub.

1. **Fork** [`visorcraft/MongrelDB-Kotlin`](https://github.com/visorcraft/MongrelDB-Kotlin)
   to your GitHub account.
2. **Clone** your fork and add the upstream remote:

   ```sh
   git clone git@github.com:<you>/MongrelDB-Kotlin.git
   cd MongrelDB-Kotlin
   git remote add upstream https://github.com/visorcraft/MongrelDB-Kotlin.git
   ```

3. **Branch** from `master`. Pick a descriptive, kebab-case branch name:
   `fix-query-alias`, `feature/sparse-vector`, `docs/auth-guide`.

   ```sh
   git fetch upstream
   git switch -c my-change upstream/master
   ```

4. **Make focused commits.** One logical change per commit. Run the preflight
   (see below) before pushing.
5. **Open a pull request** against `master` on `visorcraft/MongrelDB-Kotlin`.
   Fill in the PR template:
   - **What.** One paragraph summary of the change.
   - **Why.** Bug fix? New feature? Doc fix? Link the issue if one exists.
   - **How to test.** The exact commands a reviewer should run.
   - **Risk.** What might break? What did you not test?

## Before you push: preflight

Run the full CI preflight locally with the Gradle wrapper:

```sh
./gradlew build
```

All steps must pass. If a check fails, fix the root cause rather than silencing
the compiler or skipping a test.

To run the live integration suite (requires a running `mongreldb-server`):

```sh
MONGRELDB_URL=http://127.0.0.1:8453 ./gradlew test
```

Live tests self-skip when no server is reachable, so `./gradlew test` always
exercises the offline checks (health-when-unreachable and the in-process auth
header test).

## What we look for in a review

- The change does one thing and does it well.
- Behavior changes ship with tests. New client behavior: a unit test alongside
  the code in `src/test/`. Query wire-format changes: cover the exact outgoing
  JSON keys. Daemon-dependent coverage: a live test that skips cleanly when no
  server is available.
- The change keeps this repo a thin client over `mongreldb-server`. Do not
  re-implement storage, indexing, WAL, or SQL planning logic here.
- Documentation is updated alongside the code (`docs/`, `README.md`) if the
  change affects users.
- Commits have clear messages (see below).

## Coding standards

### Kotlin

- **Version.** Kotlin/JVM, targeting JVM 11 bytecode. The build is wired in
  `build.gradle.kts`; do not raise the bytecode target casually.
- **Runtime dependencies.** No external runtime dependencies beyond the Kotlin
  standard library and the JDK. The transport is the JDK's
  `java.net.HttpURLConnection`. Do not add an HTTP client or JSON library; the
  internal `MongrelDB.Json` codec is intentionally minimal. New dependencies
  must be MIT or Apache-2.0 licensed and justified.
- **Test dependency.** JUnit 5 (Jupiter) only. Use `org.junit.jupiter.api`
  assertions rather than `kotlin.test` so no extra test framework is pulled in.
- **Idioms.** Write idiomatic Kotlin: `val` by default, data classes for value
  types, default arguments over builder overloads, `check`/`require` for
  precondition failures, nullability on types (no `!!` in library code except
  deliberate test scaffolding). Public API uses `public` explicitly and is
  documented with KDoc.
- **Concurrency.** A `MongrelDB` instance is safe for concurrent use; each
  request opens and closes its own connection. Avoid shared mutable state in
  library code; where it is unavoidable (for example `QueryBuilder.truncated`),
  guard it with `@Volatile` or synchronization and document the contract.
- **Errors.** Throw the typed exception hierarchy (`MongrelDBException` base,
  `AuthException`, `NotFoundException`, `ConflictException`, `QueryException`)
  carrying the HTTP status and decoded server envelope, not generic
  `RuntimeException`s.

### Commit messages

- Subject line: imperative mood, at most 72 characters, no trailing period.
  Example: `Add sparse vector match condition to query builder`.
- Body: wrap at 72 characters. Explain *why*, not *what* (the diff shows the
  what).
- Reference issues with `Fixes #123` / `Refs #123` on a final line when
  applicable.
- **Never** add AI/assistant attribution (no `Co-Authored-By`, no `Generated
  with`, no tool names).

## Issue reports

A useful bug report includes:

- The MongrelDB Kotlin client version (from `build.gradle.kts`).
- Your JVM version (`java -version`) and OS.
- The `mongreldb-server` version if the issue involves live requests.
- The exact code or commands that reproduce the issue.
- The expected result and the actual result.
- Any error output or stack trace.

Feature requests are welcome. Please describe the problem you are trying to
solve before proposing the solution.

## Security

If you find a vulnerability, **do not** open a public GitHub issue. Report it
privately through GitHub's private vulnerability reporting - the repository's
**Security** tab -> **Report a vulnerability**. The full policy is in
[`SECURITY.md`](SECURITY.md).

## Licensing

The MongrelDB Kotlin client is dual-licensed under MIT OR Apache-2.0. By
contributing, you agree that your changes are made available under the same
license.

- Do **not** paste code from other database clients unless you have done a
  license review first.
- New third-party dependencies must be MIT or Apache-2.0 licensed.

Thanks again - looking forward to your PR.
