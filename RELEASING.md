# Releasing javif

Formal releases publish `org.glavo:avif` to Maven Central and attach the library, sources,
Javadoc, and SHA-256 checksums to a GitHub release. The release workflow accepts annotated or
lightweight tags whose names use `v<major>.<minor>.<patch>` with an optional pre-release suffix.
`-SNAPSHOT` versions are never accepted by the release tasks or workflow.

## One-time configuration

Create a GitHub environment named `maven-central`. Configure required reviewers for that
environment so a validated release cannot publish without approval. Add these environment secrets:

- `SONATYPE_USERNAME`: the username from a Central Portal user token;
- `SONATYPE_PASSWORD`: the password from the same Central Portal user token;
- `SIGNING_KEY`: an ASCII-armored private OpenPGP key;
- `SIGNING_PASSWORD`: the private key password;
- `SIGNING_KEY_ID`: the key identifier, when the private key contains more than one signing key;
- `SONATYPE_STAGING_PROFILE_ID`: the Central namespace, normally `org.glavo`; this secret is
  optional because the build defaults it to the Maven group.

The build uses Sonatype's Portal OSSRH Staging API compatibility endpoint. Legacy OSSRH tokens and
server URLs do not work. The signing key's public key must be available from a public key server
accepted by Maven Central.

For a local release, the same values may be supplied as environment variables or placed outside
the repository in `~/.gradle/maven-central-publish.properties` with these property names:

```properties
sonatypeUsername=...
sonatypePassword=...
sonatypeStagingProfileId=org.glavo
signing.keyId=...
signing.password=...
signing.key=...
```

Escape line breaks in `signing.key` as `\n` when using a Java properties file. The
repository-local `gradle/maven-central-publish.properties` path is ignored as an additional
safeguard, but the user-home file is preferred so secrets never enter the worktree.

## Release procedure

1. Finish and merge the release commit into `main`.
2. From the Actions page, run `Corpus Check` with `corpus=all` for that exact commit and wait for
   every AOMedia, Argon, Firefox, and Chromium job to succeed.
3. Create and push the version tag:

   ```text
   git tag -a v0.1.0 -m "Release 0.1.0"
   git push origin v0.1.0
   ```

4. Wait for the unprivileged validation job to verify the tag, the complete corpus run, and the
   ordinary release gate.
5. Approve the `maven-central` environment deployment after checking the selected commit and
   version.
6. Wait for the publication job to complete. It validates a signed local publication, publishes
   and closes the Central staging repository, and then creates the GitHub release.

The workflow does not create or move tags. Maven Central releases are immutable, so a failed
release must be fixed under a new version if any artifact has already reached Central.

## Local validation

Use JDK 23 or newer for any local command that generates Javadoc or publication artifacts. The
release workflow uses JDK 25 for those steps while retaining a separate JDK 17 compatibility gate.

The release version and POM can be checked without credentials or remote changes:

```text
./gradlew -g .gradle-user-home '-Pversion=0.1.0' verifyReleaseVersion generatePomFileForMavenPublication
```

With credentials configured, validate signing against the local Maven repository before any
remote publication:

```text
./gradlew -g .gradle-user-home '-Pversion=0.1.0' verifyReleaseConfiguration publishMavenPublicationToMavenLocal
```

Do not invoke `publishToSonatype` for a version that is not intended to become immutable.
