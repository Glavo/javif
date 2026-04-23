# Java Code Style Requirements

These rules apply to all Java code written or modified in this repository.

## Nullability

- Annotate every class with JetBrains Annotations `@NotNullByDefault`.
- Any type, field, parameter, return value, local variable, or generic type argument that may be `null` must be explicitly annotated with `@Nullable`.
- Nullability must never be implicit.

## Optional Values

- Do not use Java `Optional`.
- Represent optional or absent values with `@Nullable` instead.
- Do not introduce APIs that require callers to unwrap `Optional`.

## Runtime Dependencies

- Except for tests, all code in this repository must run with no runtime dependencies other than the `java.base` module.
- Do not introduce runtime dependencies on external libraries, Maven artifacts, or JDK modules other than `java.base`.
- If a feature would require another dependency at runtime, redesign it, remove it, or confine it strictly to tests.

## Documentation

- Every class, field, and method must have documentation.
- Documentation must use `///` Markdown-style Javadoc comments.
- Keep documentation accurate and specific to the actual behavior, constraints, and side effects.
- Add concise implementation comments inside complex logic whenever they materially improve readability or explain non-obvious behavior.

## Gradle

- When invoking Gradle in this repository, always set `GRADLE_USER_HOME` to the workspace-local `.gradle-user-home` directory.
- Prefer commands such as `./gradlew -g .gradle-user-home ...` or the equivalent environment-variable-based configuration.
