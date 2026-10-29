# Publish PageCurl Core to Maven Central

The release destination is Maven Central through Sonatype Central Portal. The existing coordinates are `org.peyilo.pagecurl:pagecurl-core:0.1.0`. Verify the parent namespace `org.peyilo` using the domain `peyilo.org`; publishing under its `org.peyilo.pagecurl` child does not require a separate website or Maven server.

The repository prepares the release AAR, source JAR, generated API documentation JAR, POM, checksums and optional PGP signatures. The GitHub publishing workflow is manual and stages a deployment for review. It does not automatically make a version permanent.

## Owner setup checklist

| Step | Action | Completion evidence |
| --- | --- | --- |
| 1 | Register a Central Portal account and verify its email | You can sign in to Central Portal |
| 2 | Register `org.peyilo` and verify the domain by DNS TXT | Namespace status is Verified |
| 3 | Create a dedicated signing key and publish its public key | The public fingerprint can be retrieved from a supported keyserver |
| 4 | Generate a Central Portal publishing token | Its token username and password are saved securely |
| 5 | Add four repository Actions secrets | All four secret names below appear in GitHub Settings |
| 6 | Review the version and create a release tag | The tag matches VERSION_NAME in its commit |
| 7 | Run the staging workflow and review Central validation | The deployment is VALIDATED |
| 8 | Publish the validated deployment in Central Portal | The deployment reaches PUBLISHED |

## Register the account and namespace

Sign in or register at [Central Portal](https://central.sonatype.com/). Use an email account you can continue accessing. Open [Namespaces](https://central.sonatype.com/publishing/namespaces), add `org.peyilo`, and copy the generated verification key.

In the service that hosts the authoritative DNS for `peyilo.org`, add this record:

| DNS field | Value |
| --- | --- |
| Type | TXT |
| Name or host | `@`, or `peyilo.org` if the provider requires a full name |
| Value | The exact verification key supplied by Central Portal |
| TTL | The provider default is sufficient |

The record belongs on `peyilo.org`, not on `www.peyilo.org` or a Maven subdomain. Keep existing TXT records. No website hosting, HTTPS certificate or domain email service is required for this DNS proof.

Confirm the TXT record is publicly visible before clicking Verify Namespace:

```powershell
Resolve-DnsName peyilo.org -Type TXT -Server 1.1.1.1
```

Alternatively, use `nslookup -type=TXT peyilo.org`. Compare the result with the key from the portal. Only then submit verification and wait for Verified status. See the [official namespace instructions](https://central.sonatype.org/register/namespace/).

## Create and publish a signing key

Use GnuPG locally so that the private key and passphrase remain under your control. On Windows, use a native GnuPG installation in PowerShell, or open Git Bash to use the GnuPG bundled with Git. Run the following key commands in that chosen shell; Git's bundled GnuPG should run inside Git Bash so its agent can start correctly.

```sh
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
```

Choose RSA and RSA, a 4096-bit key, an expiration you will track, your publishing identity and a strong passphrase. The primary key must support signing. Copy its full fingerprint from the listing. Back up the key and generated revocation certificate outside this repository, then publish only the public key:

```sh
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys YOUR_FULL_FINGERPRINT
```

Use a fresh temporary keyring to confirm that the public key can be retrieved before the first release. See [Sonatype's signing instructions](https://central.sonatype.org/publish/requirements/gpg/) for supported keyservers and expiration updates. Private keys and passphrases belong in local secure storage and GitHub secrets, never in an issue, commit or chat message.

## Generate the publishing token

Open [Central Portal user tokens](https://central.sonatype.com/usertoken), generate a named token for this repository, and choose an expiration you can track. Save both generated fields before closing the dialog. These are the publishing token's username and password, not your login credentials. See the [official token instructions](https://central.sonatype.org/publish/generate-portal-token/).

## Add GitHub Actions secrets

Open [repository Actions secrets](https://github.com/Peyilo/PageCurl-Core/settings/secrets/actions) and create:

| Secret | Content |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Username returned by Generate User Token |
| `MAVEN_CENTRAL_PASSWORD` | Password returned by Generate User Token |
| `SIGNING_IN_MEMORY_KEY` | Complete ASCII-armored private key, including BEGIN and END lines |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Signing key passphrase |

With GitHub CLI, the first, second and fourth values can be entered at its hidden prompt:

```powershell
gh secret set MAVEN_CENTRAL_USERNAME --repo Peyilo/PageCurl-Core
gh secret set MAVEN_CENTRAL_PASSWORD --repo Peyilo/PageCurl-Core
gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD --repo Peyilo/PageCurl-Core
```

Export the private key directly into the remaining secret without printing it or writing an export into the checkout:

```sh
gpg --armor --export-secret-keys YOUR_FULL_FINGERPRINT | gh secret set SIGNING_IN_MEMORY_KEY --repo Peyilo/PageCurl-Core
```

After setting secrets, confirm their names with `gh secret list --repo Peyilo/PageCurl-Core`. GitHub does not reveal stored values. Replace the secrets when a token expires or a signing key changes.

## Verify locally without publishing

```powershell
.\gradlew.bat :pagecurl-core:publishReleasePublicationToLocalRepository :pagecurl-core:lintDebug
```

This builds the release, generates documentation and writes an unsigned Maven repository under `build/repository`. It requires no publishing credentials. Inspect the AAR, `-sources.jar`, `-javadoc.jar` and POM there. The POM must include the MIT license, developer identity and source-control URL.

If Python 3 is available, run `python scripts/verify-publication.py` to check archive contents, required POM metadata and checksums. After a signed local build, add `--require-signatures` to check signature-file presence too. This is a local structural check; Central performs namespace and public-key validation during staging.

When signing locally, provide these environment variables only for the build process:

```text
ORG_GRADLE_PROJECT_mavenCentralUsername
ORG_GRADLE_PROJECT_mavenCentralPassword
ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

The signing key is the same complete armored text as the GitHub secret. Do not add any of these values to the repository's `gradle.properties`. Run the local-publication task with the key variables to verify signatures without uploading anything.

## Stage and publish a release

Review the public API, MIT license, developer contact in `pagecurl-core/build.gradle.kts`, and `VERSION_NAME` in `gradle.properties`. Central versions are immutable after publication, so correct a published mistake with a new version. Keep `GROUP=org.peyilo.pagecurl` stable after consumers adopt it.

After the desired source and version are committed, create and push an annotated tag:

```powershell
git tag -a v0.1.0 -m 'Release 0.1.0'
git push origin v0.1.0
```

Run **Stage Maven Central release** from [GitHub Actions](https://github.com/Peyilo/PageCurl-Core/actions/workflows/publish.yml), using `v0.1.0` as the tag. Or run:

```powershell
gh workflow run publish.yml --repo Peyilo/PageCurl-Core -f tag=v0.1.0
```

The workflow checks that the tag matches its committed version, builds without credentials, then signs and calls `publishToMavenCentral`. Pushing a tag alone does not upload a release. The plugin deliberately leaves the deployment in Central Portal for review.

Open [Central deployments](https://central.sonatype.com/publishing/deployments). If validation fails, correct the namespace, metadata or signature issue before publishing. When the deployment is VALIDATED and the version is ready, click Publish and wait for PUBLISHED. See the [official publishing guide](https://central.sonatype.org/publish/publish-portal-guide/).

Only after the version is available in Central can consuming applications use:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("org.peyilo.pagecurl:pagecurl-core:0.1.0")
}
```

The first release has not been published by merely preparing this configuration. Follow the owner checklist to complete account, DNS and secret setup before running the staging workflow.
