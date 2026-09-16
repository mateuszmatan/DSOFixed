# DevSecOpsJenkinsLibrary

Jenkins Shared Library implementing a full DevSecOps pipeline: build, unit tests, dependency scan (Nexus IQ), code quality (SonarQube), SAST (HCL AppScan), deployment (VM/OpenShift), smoke/regression/performance tests, DAST (HCL AppScan), HTML report, and InfluxDB metrics.

---

## Table of contents

1. [What the library provides](#1-what-the-library-provides)
2. [Application requirements](#2-application-requirements)
3. [Architecture overview](#3-architecture-overview)
4. [Prerequisites](#4-prerequisites)
5. [Step 1 – Register the library in Jenkins](#5-step-1--register-the-library-in-jenkins)
6. [Step 2 – Add two files to your project repository](#6-step-2--add-two-files-to-your-project-repository)
7. [Step 3 – Configure the Jenkins job](#7-step-3--configure-the-jenkins-job)
8. [Step 4 – First run](#8-step-4--first-run)
9. [config.yaml reference](#9-configyaml-reference)
10. [Pipeline stages](#10-pipeline-stages)
11. [Policy thresholds and project overrides](#11-policy-thresholds-and-project-overrides)
12. [Advanced: using library methods directly](#12-advanced-using-library-methods-directly)
13. [Supported build tools](#13-supported-build-tools)
14. [Supported deployment targets](#14-supported-deployment-targets)
15. [Troubleshooting](#15-troubleshooting)

---

## 1. What the library provides

The library executes the following pipeline automatically when you call `devSecOpsPipeline()` from your Jenkinsfile:

| # | Stage | What it does |
|---|-------|-------------|
| 1 | Monitor source changes | Checkout, AppScan setup, IRX generation per project |
| 2 | Unit tests | Build tool tests + JaCoCo/lcov coverage check |
| 3 | Dependencies scan (Nexus IQ) | Dependency vulnerability scan with policy enforcement |
| 4 | SCA (SonarQube) | Static code analysis + quality gate |
| 5 | SAST – HCL AppScan | Static Application Security Testing, queued per project |
| 6 | Nexus delivery (static analysis passed) | Publish artifact to Nexus after all static scans pass |
| 7 | Lower test region deployment | Deploy to RD environment (VM via SSH or OpenShift) |
| 8 | Smoke tests | Trigger smoke test jobs |
| 9 | Regression tests | Trigger regression test jobs (>60% coverage required) |
| 10 | Performance tests | Trigger performance test jobs |
| 11 | DAST – HCL AppScan | Dynamic Application Security Testing per project |
| 12 | Nexus delivery (safe artifact) | Publish clean artifact (only when the library security policy is met) |
| 13 | Higher test environment deployment | Deploy to QC (only when explicitly requested) |

After all stages, the library always:
- Generates an HTML pipeline report with the stage flow, vulnerability counts, policy status, SonarQube badges and coverage
- Adds a **Smoke tests** table with one row per executed job and a link to each build
- Adds a **Nexus IQ GoldenFix** card with the pull request link, the applied dependency upgrades and the fixes that could not be applied automatically
- Adds a **Release policy** card stating whether the artifact may be released to Nexus and deployed to QC
- Archives the HTML reports, the DAST PDF report and the release gate verdict (`release-gate.json`) as build artifacts
- Sends DORA metrics to InfluxDB (if configured)

---

## 2. Application requirements

Before onboarding a project to the DevSecOps pipeline, the application and its repository must meet the following requirements:

- **Git repository accessible from Jenkins.**
  The project must live in a Git repository that the Jenkins controller can clone. The `Jenkinsfile` and `config.yaml` must be committed at the repository root and pushed to the branch configured in the Jenkins job.

- **Supported build tool: Gradle, Maven, or Flutter.**
  The codebase must use one of the three supported build systems. Gradle wrapper (`gradlew`) and Maven wrapper (`mvnw`) are preferred and must be executable and committed to the repository. The build tool is either declared in `config.yaml` or auto-detected.

- **Code must compile successfully.**
  The SAST stage generates an IRX archive by running a Gradle/Maven compile step (`classes testClasses`). Any compilation error will block the pipeline at stage 1. Ensure all compile-time dependencies are resolvable from the configured Nexus repositories.

- **Unit tests with JaCoCo coverage (Gradle/Maven) or lcov (Flutter).**
  The project must have runnable unit tests. For Gradle and Maven, JaCoCo must be configured in the build script to produce a coverage XML report. The library requires a minimum line coverage of 60 %. A project may lower its own threshold in `config.yaml` to keep working on the RD environment, but below the library minimum the artifact is not published to Nexus and the QC deployment is blocked. Flutter projects must use the `--coverage` flag which produces `coverage/lcov.info`.

- **HCL AppScan on Cloud (ASoC) application registered.**
  The application must be registered in ASoC and have a known application ID (UUID). An API key pair (`keyId` + `keySecret`) must be obtained from the ASoC portal and placed in `config.yaml`. Both SAST and DAST scans are uploaded to this application.

- **SonarQube project registered.**
  A project must exist in SonarQube with a unique `projectKey`. A badge token (`badgeToken`) is required for SonarQube metric badges to appear in the HTML pipeline report. The SonarQube server must be reachable from the Jenkins agent.

- **Nexus IQ application registered.**
  The application must be registered in Nexus IQ with its public application ID. Build artifacts (JARs, WARs) must match the configured `scanPatterns` in `config.yaml`. The Nexus IQ server must be reachable from the Jenkins agent.

- **Build artifacts produced in expected locations.**
  Gradle projects must produce artifacts under `build/libs/`. Maven projects under `target/`. These paths must match the `scanPatterns` configured for Nexus IQ and be valid artifact outputs for publishing.

- **Deployment script and version file (VM deployment).**
  For VM-based deployment, the repository must contain a zero-downtime deployment shell script and a `version.properties` file with an `APP_VERSION=<version>` entry. The Jenkins agent must have SSH key-based (passwordless) access to all configured deployment hosts.

- **OpenShift deployment template (OpenShift deployment).**
  For OpenShift-based deployment, the repository must include a `deployConfig.yml` OpenShift process template at the repository root. OpenShift cluster credentials must be stored in Jenkins with the credential IDs configured in `config.yaml`.

- **Test jobs exist in Jenkins.**
  Smoke, regression, and performance test jobs must be created as Jenkins jobs before the pipeline reaches those stages. Their full job paths must be configured in `config.yaml`. All three test types require at least one job entry — placeholder entries with `job: ""` are allowed initially and will show `NOT_CONFIGURED` without failing.

- **DAST target accessible from Jenkins agent (DAST only).**
  When `dast.enabled: true`, the configured `dast.targetUrl` must be reachable from the Jenkins agent running the pipeline. For internal (non-internet) targets, an AppScan Presence must be deployed and its ID provided in `dast.presenceId`.

- **InfluxDB endpoint accessible (optional).**
  If DORA metrics are required, an InfluxDB v2 instance must be reachable from the Jenkins agent and the configured write endpoint must accept the provided auth token with write permissions to the target bucket.

---

## 3. Architecture overview

```
DevSecOpsJenkinsLibrary/           <- library repository root
├── DOCUMENTATION.md               <- this file
├── grafana-queries.md             <- Flux queries for Grafana dashboards (InfluxDB metrics)
├── resources/
│   └── defaults.yaml              <- embedded non-overridable defaults (read via libraryResource)
├── vars/
│   └── devSecOpsPipeline.groovy   <- single entry point + all helper methods
└── src/com/bbh/
    ├── build/BuildService.groovy
    ├── config/ConfigLoader.groovy
    ├── core/
    │   ├── OsHelper.groovy
    │   ├── PipelineState.groovy
    │   ├── PolicyEngine.groovy         <- project thresholds, fails the pipeline when exceeded
    │   ├── ReleaseGate.groovy          <- library policy, blocks the Nexus release and the QC deployment
    │   └── StageLogger.groovy
    ├── deploy/
    │   ├── OpenshiftService.groovy
    │   └── VmDeployService.groovy
    ├── metrics/InfluxDbService.groovy
    ├── report/HtmlReportService.groovy
    ├── remediation/                         <- GoldenFix (hexagonal: application service + ports)
    │   ├── GoldenFixService.groovy          <- orchestrates fetch -> patch manifests -> commit/push -> pull request
    │   ├── model/GoldenFix.groovy
    │   ├── port/                            <- GoldenFixSource, ManifestUpdater, SourceRepository, PullRequestPublisher
    │   └── updater/                         <- MavenPomUpdater, GradleUpdater, NpmPackageJsonUpdater, PipUpdater
    ├── scm/                                 <- adapters: GitSourceRepository (git worktree), Bitbucket pull requests
    ├── utils/                               <- RestClient (curl JSON client), VersionUtils
    └── scanner/
        ├── AppScanService.groovy
        ├── NexusIqService.groovy            <- triggers GoldenFix on policy violation
        ├── NexusIqGoldenFixSource.groovy    <- Nexus IQ report + Component Remediation REST API adapter
        └── SonarService.groovy

examples/                          <- project usage examples (outside the library)
└── CertScanner/
    ├── Jenkinsfile                <- 2-line Jenkinsfile using the library
    └── config.yaml               <- project config (projects: section only)
```

**Config split:**

| Source | Who controls it | Contains |
|--------|----------------|----------|
| `resources/defaults.yaml` (inside library) | Library team only | Security thresholds, timeouts, tool defaults |
| `config.yaml` in your project repo | Project team | Project-specific settings (appId, servers, deploy targets) |

Projects **cannot** override the `defaults` – they are embedded in the library. To change a threshold (e.g. `sast.maxHigh`), the library must be updated and redeployed.

---

## 4. Prerequisites

The Jenkins controller and build agents must have the following installed and configured:

### Jenkins plugins (required)

| Plugin | Purpose |
|--------|---------|
| Pipeline | Core declarative pipeline support |
| Pipeline: Shared Groovy Libraries | Load this shared library |
| Git | Source checkout |
| HTML Publisher | Publish HTML pipeline report |
| JUnit | Publish unit test results |
| SonarQube Scanner | SonarQube integration |
| Nexus Platform | Nexus IQ integration (`nexusPolicyEvaluation`) |
| Parameterized Remote Trigger | Remote smoke, regression and performance jobs (also by full URL) |
| Pipeline Utility Steps | `readYaml`, `readJSON`, `writeFile` |
| Credentials Binding | `withCredentials` for Nexus IQ, ASoC, Bitbucket, remote Jenkins |
| Copy Artifact | Passing `config.yaml` and `release-gate.json` to the extended pipeline |
| OpenShift Client (optional) | OpenShift `openshift.withCluster()` |

### Agent tools

- Java JDK (for Gradle/Maven builds)
- Gradle or Maven wrapper (or system-installed)
- Flutter SDK (if building Flutter applications)
- `curl`, `python3`, `ssh`, `scp` (Linux agents)
- PowerShell (Windows agents)

### Jenkins global configuration

1. **SonarQube server** must be registered in *Manage Jenkins → Configure System → SonarQube servers* with the name `SonarQube`.
2. **Nexus IQ server** must be registered in *Manage Jenkins → Configure System → Sonatype Nexus Platform*.
3. The following credentials must exist in Jenkins Credentials Store:

| Credential ID | Type | Used for |
|---------------|------|---------|
| `nexusiqP` | Username/Password | Nexus IQ authentication (also used by GoldenFix to read reports and remediation versions) |
| `scm.bitbucket.credentialsId` | Username/Password (or Secret text with `authType: bearer`) | GoldenFix: push of branch `GoldenFix-YYYYMMDDHHMM` and pull request creation (repository write permission) |
| `tests.*.jobs[].credentialsId` (optional) | Username/Password (user + API token) | Triggering remote test jobs referenced by full URL |
| `openshift-rd-token` | Secret text | OpenShift RD cluster token |
| `openshift-qc-token` | Secret text | OpenShift QC cluster token |

---

## 5. Step 1 – Register the library in Jenkins

1. Open Jenkins → **Manage Jenkins** → **Configure System**.
2. Scroll to **Global Pipeline Libraries** → click **Add**.
3. Fill in:

| Field | Value |
|-------|-------|
| Name | `DevSecOpsJenkinsLibrary` |
| Default version | `main` (or your release branch) |
| Load implicitly | unchecked |
| Allow default version override | unchecked |
| Retrieval method | **Modern SCM** |
| Source Code Management | **Git** |
| Project Repository | URL to this repository |
| Credentials | credentials to the library repo (if private) |

4. Click **Save**.

That is all you need to do in Jenkins for the library. You do not need to configure anything else globally.

---

## 6. Step 2 – Add two files to your project repository

Your project repository needs exactly **two files**:

### File 1: `Jenkinsfile` (at repository root)

The simplest possible Jenkinsfile:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(projectNames: 'my-app')
```

Replace `my-app` with the key name(s) you will use in `config.yaml`.

For multiple projects (e.g. a monorepo with GUI and API):

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(projectNames: 'gui,backend-api')
```

### File 2: `config.yaml` (at repository root)

This file contains ONLY the `projects:` section. You do not need to specify `defaults:` — the library provides non-overridable defaults automatically.

Minimal example (single project, VM deployment):

```yaml
projects:

  my-app:
    appId:     "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    buildTool: gradle

    asoc:
      keyId:     "bbh_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      keySecret: "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

    tools:
      sonar:
        projectName: "My Application"
        projectKey:  "my-application"
        serverUrl:   "https://tools.bbh.com/sonar"
        badgeToken:  "sqb_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      nexusIq:
        application:        "My-Application"
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       ["**/build/libs/*.jar"]
        stage:              "build"
        failOnNetworkError: false

    dast:
      enabled:   true
      targetUrl: "http://my-rd-server.example.com"

    tests:
      smoke:
        jobs:
          - name:       "My App - smoke"
            type:       local
            job:        "my-app/smoke-tests"
            timeoutMin: 15
      regression:
        jobs:
          - name:       "My App - regression"
            type:       local
            job:        "my-app/regression-tests"
            timeoutMin: 60
      performance:
        jobs:
          - name:       "My App - performance"
            type:       local
            job:        "my-app/performance-tests"
            timeoutMin: 120

    deploy:
      vm:
        rd:
          host:         "rdserver.example.com"
          user:         "deploy"
          deployDir:    "/opt/app/deployment"
          deployScript: "scripts/deployment/deploy.sh"
          versionFile:  "scripts/deployment/version.properties"
        qc:
          host:         "qcserver.example.com"
          user:         "deploy"
          deployDir:    "/opt/app/deployment"
          deployScript: "scripts/deployment/deploy.sh"
          versionFile:  "scripts/deployment/version.properties"
```

> **Important:** `asoc.keyId` and `asoc.keySecret` are required in every project. These are credentials for HCL AppScan on Cloud (ASoC). Obtain them from the ASoC portal under your organization's API key settings.

---

## 7. Step 3 – Configure the Jenkins job

1. Create a new **Pipeline** job in Jenkins.
2. In **Pipeline** section, select **Pipeline script from SCM**.
3. Set **SCM** to **Git** and enter your project repository URL.
4. Set **Branch Specifier** to your default branch (e.g. `*/main`).
5. Set **Script Path** to `Jenkinsfile`.
6. Click **Save**.

No environment variables need to be configured in the job definition. The library reads everything from `config.yaml`.

### Optional: expose parameters in the job

The library automatically adds one boolean build parameter:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `DEPLOY_HIGHER_ENV` | `false` | When `true`, the pipeline deploys to the QC environment after all tests pass, provided the library security policy is met. |

Security violations always fail the pipeline. There is no override parameter: a project that needs more room raises its own thresholds in `config.yaml` (see below).

These appear in the Jenkins UI automatically after the first successful run.

---

## 8. Step 4 – First run

1. Open your pipeline job in Jenkins.
2. Click **Build Now** (or **Build with Parameters** if parameters have already been discovered).
3. Watch the **Stage View** – each stage turns green on success or red on failure.
4. After the build finishes, click **Pipeline Report** in the left sidebar to view the HTML security report.

### What to expect on first run

- **Stage 1** performs `checkout scm` and reads your `config.yaml`. If the file is missing or malformed, the stage fails with a clear error message.
- **Stage 5 (SAST)** uploads your compiled code to ASoC and waits for results. This typically takes 20–50 minutes.
- **Stage 11 (DAST)** is skipped for projects where `dast.enabled: false`.
- **Stage 13** only runs when `DEPLOY_HIGHER_ENV=true` is selected at build time.

---

## 9. config.yaml reference

The `config.yaml` in your project must contain only a `projects:` top-level key. Below is the full reference with all available fields and their defaults (coming from the library):

```yaml
projects:

  <project-name>:                    # arbitrary key, must match projectNames in Jenkinsfile

    appId:     ""                    # HCL AppScan application ID (UUID)
    buildTool: gradle                # gradle | maven | flutter  [default: gradle]
    deployTarget: vm                 # vm | openshift            [default: vm]
    sourceDir:  "."                  # source root for IRX generation [default: .]

    asoc:                            # HCL AppScan on Cloud credentials (required)
      url:       "https://bbh.cloud.appscan.com"
      keyId:     ""
      keySecret: ""
      insecureTls: false             # true adds curl -k to the ASoC REST calls (TLS verification off) - keep false

    influx:                          # InfluxDB DORA metrics (optional)
      enabled:   false
      project:   ""                  # measurement tag
      env:       "dev"               # measurement tag
      url:       ""                  # InfluxDB v2 write endpoint
      token:     ""                  # InfluxDB auth token
      credentialsId: ""              # alternatively: Jenkins credential ID for token

    coverage:
      minLine:    60                 # minimum line coverage %, may be lowered below the library minimum
      reportPath: ""                 # custom JaCoCo XML path (optional, auto-detected if empty)

    tools:
      sonar:
        projectName: ""              # display name in SonarQube
        projectKey:  ""              # unique project key in SonarQube
        serverUrl:   "https://tools.bbh.com/sonar"
        badgeToken:  ""              # token for SonarQube badges in HTML report
      nexusIq:
        application:        ""       # application public ID in Nexus IQ
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       []       # list of Ant patterns, e.g. ["**/build/libs/*.jar"]
        stage:              "build"
        failOnNetworkError: false
        maxCritical:        0        # optional project thresholds
        maxHigh:            0
        maxMedium:          0

    sast:
      scanName: ""                   # AppScan scan name (auto-generated from sonar.projectName if empty)
      maxCritical: 0                 # optional project threshold, library default: 0
      maxHigh:     0
      maxMedium:   0

    sca:
      scanName: ""                   # reserved for future use
      maxCritical: 0                 # optional project threshold for SonarQube findings
      maxHigh:     0
      maxMedium:   0

    dast:
      enabled:    false              # set true to run DAST
      targetUrl:  ""                 # URL to scan
      presenceId: ""                 # AppScan Presence ID (for internal networks, optional)

    scm:
      bitbucket:                     # required to raise GoldenFix pull requests
        url:           ""            # repository link: https://<host>/projects/KEY/repos/slug, .../scm/key/slug.git or https://bitbucket.org/ws/slug
        credentialsId: ""            # Jenkins credentials: Username with password (or HTTP access token as password)
        authType:      basic         # basic | bearer (Secret text with an HTTP access token)
        type:          ""            # server | cloud  [auto-detected from url]
        targetBranch:  ""            # PR target branch  [default: branch that triggered the build]
        cloneUrl:      ""            # push URL override  [default: derived from url]
        reviewers:     []            # Bitbucket user names (server) / account UUIDs (cloud)

    goldenFix:                       # library defaults shown, project values override them
      enabled:                true
      onlyDirectDependencies: true
      minThreatLevel:         2      # 2+ medium, 4+ high, 8+ critical
      ecosystems:             [maven, npm, pypi]
      excludeDirs:            []     # [default: .git, node_modules, target, build, .gradle, dist, venv, ...]
      commitAuthorName:       "DevSecOps GoldenFix"
      commitAuthorEmail:      "devsecops-goldenfix@noreply.local"
      timeZone:               ""     # time zone of the GoldenFix-YYYYMMDDHHMM name

    tests:
      maxParallel: 20                # [library default] max jobs of one test stage running at the same time
      smoke:
        maxParallel: 20              # optional per-stage override
        defaults: {}                 # optional: fields merged into every job entry
        urls: []                     # optional shorthand: list of remote job URLs
        jobs:
          - name:       ""           # display name (derived from the URL when empty)
            type:       local        # local | remote  [default: remote when job/url is a full URL]
            job:        ""           # Jenkins job path ("folder/job-name") or full job URL
            url:        ""           # alias of job for full remote job URLs
            timeoutMin: 15
            parameters: ""           # optional, key=value lines
            remoteJenkins: ""        # Remote Jenkins name from global config (not needed with a full URL)
            remoteJenkinsUrl: ""     # optional remote Jenkins base URL override
            credentialsId: ""        # optional: credentials (user + API token) for the remote Jenkins
          - "https://jenkins.example.com/job/smoke/job/login"   # an entry can also be just the job URL
      regression:
        jobs:
          - name:       ""
            type:       local
            job:        ""
            timeoutMin: 60
      performance:
        jobs:
          - name:       ""
            type:       local
            job:        ""
            timeoutMin: 120

    deploy:
      vm:                            # SSH-based deployment (used when deployTarget: vm)
        rd:                          # lower test environment
          host:         ""
          user:         "taadmin"
          deployDir:    ""           # remote directory for deployment scripts
          deployScript: "scripts/deployment/zero-downtime-deployment.sh"
          versionFile:  "scripts/deployment/version.properties"
        qc:                          # higher test environment
          host:         ""
          user:         "taadmin"
          deployDir:    ""
          deployScript: "scripts/deployment/zero-downtime-deployment.sh"
          versionFile:  "scripts/deployment/version.properties"
      openshift:                     # OpenShift deployment (used when deployTarget: openshift)
        rd:
          appName:           ""
          projectDeployment: ""      # OpenShift project/namespace
          cluster:           ""      # API URL, e.g. https://api.ocp-rd.example.com:6443
          credentialsId:     "openshift-rd-token"
          imageRegistry:     ""
          imageNamespace:    ""
        qc:
          appName:           ""
          projectDeployment: ""
          cluster:           ""
          credentialsId:     "openshift-qc-token"
          imageRegistry:     ""
          imageNamespace:    ""

    flutter:                         # Flutter-specific settings (only when buildTool: flutter)
      platform: apk                  # apk | appbundle | ios | macos | linux | windows | web
```

### Which fields are required

| Field | Required | Notes |
|-------|----------|-------|
| `asoc.keyId` | YES | Pipeline fails at init without it |
| `asoc.keySecret` | YES | Pipeline fails at init without it |
| `appId` | YES | Needed for AppScan SAST and DAST |
| `tools.sonar.projectKey` | Recommended | SonarQube scan is skipped silently without it |
| `tools.nexusIq.application` | Recommended | Nexus IQ scan is skipped silently without it |
| `dast.targetUrl` | Only if `dast.enabled: true` | Pipeline fails at DAST stage without it |
| `deploy.vm.*` | Only if `deployTarget: vm` | |
| `deploy.openshift.*` | Only if `deployTarget: openshift` | |
| `scm.bitbucket.url` | Only for GoldenFix | Without it the fixes are listed in the report but no pull request is raised |
| `scm.bitbucket.credentialsId` | Only for GoldenFix | Needs push rights and permission to create pull requests |

---

## 10. Pipeline stages

### Stage 1: Monitor source changes (download sources)

Performs:
- `checkout scm` (fetches your project code)
- Reads `config.yaml` and merges with library defaults
- Downloads and extracts SAClientUtil (HCL AppScan CLI) from Nexus
- Generates IRX archives (compiled source representation) for each project
- Logs into ASoC

Fails if:
- `config.yaml` is missing or has no `projects:` section
- `asoc.keyId` or `asoc.keySecret` are empty
- SAClientUtil cannot be downloaded
- IRX generation fails (Gradle/Maven compile error)

### Stage 2: Unit tests

Performs:
- Runs unit tests with coverage collection (JaCoCo for Gradle/Maven, lcov for Flutter)
- Checks that line coverage meets the `coverage.minLine` threshold (default: 60%)

Fails when the line coverage is below the project threshold (`coverage.minLine` in `config.yaml`). When the coverage clears the project threshold but stays below the library minimum, the pipeline continues to the RD environment, the artifact is not published to Nexus and the QC deployment is blocked.

### Stage 3: Dependencies scan (Nexus IQ)

Runs `nexusPolicyEvaluation` with your configured scan patterns and compares the critical, severe and moderate counts with the effective thresholds (`tools.nexusIq.max*`, library default 0). A violation fails the stage and starts the GoldenFix remediation described below.

### Stage 4: SCA (SonarQube)

Runs SonarQube analysis and calls `reportSonarQubeAudit`. Fetches issue counts (Blocker+Critical, Major, Minor, Info) for the HTML report.

### Stage 5: SAST – HCL AppScan

Queues all projects for SAST scan simultaneously, then waits for results sequentially. Downloads HTML reports, parses vulnerability counts, and enforces:
- `sast.maxCritical`, `sast.maxHigh`, `sast.maxMedium`: library default 0, may be raised per project in `config.yaml`

#### GoldenFix remediation (automatic pull request)

When the Nexus IQ policy is violated (critical, high or medium findings above the limits, also in override mode) the library:

1. Fetches the violating components of the evaluation from Nexus IQ (`/api/v2/applications/{app}/reports/{scanId}/policy`) and keeps only **direct dependencies** with a non-waived violation of at least medium threat level.
2. Asks the Component Remediation API (`/api/v2/components/remediation/application/{id}`) for the target version. The Golden Version (`recommended-non-breaking-with-dependencies`, Maven only) is preferred, then `next-no-violations(-with-dependencies)`, `recommended-non-breaking` and `next-non-failing(-with-dependencies)`.
3. Searches the whole source tree (multi-module projects included, `node_modules`, `target`, `build`, virtualenvs skipped) and updates:
   - Maven: `pom.xml` dependencies, dependencyManagement and the `<properties>` they reference (also in parent POMs)
   - Gradle: `build.gradle`, `build.gradle.kts`, `gradle.properties`, `ext`/`val` version variables, version catalogs `*.versions.toml`
   - npm: `package.json` dependencies, devDependencies, peerDependencies, optionalDependencies (range operator preserved)
   - pip: `requirements*.txt`, `constraints*.txt`, `pyproject.toml` (PEP 621 and Poetry)
4. Commits the changes in a separate git worktree (the pipeline workspace is not modified), pushes branch `GoldenFix-YYYYMMDDHHMM` and raises a Bitbucket pull request with the same name. All projects of one build share the pull request.
5. The HTML report shows the pull request link in the Nexus IQ stage, in the Security Gates table and in the **Nexus IQ GoldenFix** card together with the applied changes and the fixes that could not be applied automatically (e.g. versions managed by a BOM, ranges, hash-pinned requirements).

GoldenFix never changes the result of the Nexus IQ stage: the build still fails on the policy violation, and a GoldenFix error is only reported. Lock files (`package-lock.json`, `poetry.lock`, pinned requirements) are not regenerated. Requires `scm.bitbucket.url` and `scm.bitbucket.credentialsId` and a Linux/macOS agent with git.

### Stages 6–10: Delivery and tests

Stage 6 builds the artifact and publishes to Nexus. Stages 7–10 deploy and run tests.

Every test stage runs **all** configured jobs, even when some of them fail, and fails afterwards with a summary. A stage may contain any number of jobs (e.g. 200 smoke tests on different remote Jenkins instances). At most `tests.maxParallel` (default 20, per-stage override `tests.<type>.maxParallel`) jobs run at the same time. Remote jobs can be referenced by full job URL (`url:` or `job: https://...`) without a Remote Jenkins global configuration; `credentialsId` provides user + API token. The HTML report shows per-project counters (total / passed / failed / not configured), the failed jobs, a collapsible list of all jobs and a **Smoke tests** table with a build link for every job.

### Stage 11: DAST – HCL AppScan

Runs only for projects with `dast.enabled: true`. Queues a DAST scan against `dast.targetUrl`. Uses optional `dast.presenceId` for scanning internal (non-internet-accessible) environments.

The report is downloaded twice: as HTML (parsed for the vulnerability counts) and as **PDF**. Both are archived as build artifacts and linked from the pipeline report, exactly like the SAST report: in the DAST stage box, in the Security Gates table (`Report / PDF`) and per project in multi-project builds.

### Stage 12: Nexus delivery (safe artifact)

Skipped when the library security policy is exceeded, so a vulnerable artifact is never published to the release repository.

### Stage 13: Higher test environment deployment

Runs only when `DEPLOY_HIGHER_ENV=true` and the library security policy is met.

### Project thresholds and the release gate

Thresholds exist on two levels.

**Project thresholds (`config.yaml`)** decide whether the pipeline keeps running. A project may raise them for SAST, SCA (SonarQube), Nexus IQ and unit test coverage, and for nothing else:

```yaml
    coverage:
      minLine: 35
    sast:
      maxCritical: 5
      maxHigh:     7
      maxMedium:   9
    sca:
      maxCritical: 0
      maxHigh:     0
      maxMedium:   0
    tools:
      nexusIq:
        maxCritical: 3
        maxHigh:     0
        maxMedium:   0
```

Exceeding a project threshold, or missing the project coverage minimum, fails the stage and the pipeline. DAST thresholds are not overridable.

**Library policy (`resources/defaults.yaml`)** decides what may leave the pipeline. When the findings or the coverage are worse than the library policy, the build still deploys to the RD environment, so developers can work with it, but:

- the artifact is **not published to Nexus** (both the snapshot delivery and the release delivery are skipped),
- the **QC deployment is blocked**,
- the build is marked UNSTABLE and the HTML report shows a **Release policy** card listing every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0` or `line coverage 41.0% below the required 60%`.

The gate covers SAST, SCA, Nexus IQ, DAST and line coverage. Low severity findings are not counted. Each pipeline evaluates the results it owns and hands its verdict to the next one through the archived `release-gate.json`, so a security pipeline that exceeded the policy also blocks the release in the extended pipeline.

---

## 11. Policy thresholds and project overrides

The thresholds for SAST, SCA, Nexus IQ and coverage **may be raised per project** in `config.yaml`, which only buys room to keep working on the RD environment. The values below stay the library policy: while they are exceeded the artifact is not published to Nexus and the QC deployment is blocked. DAST thresholds and all timeouts can only be changed by the library team.

| Scanner | Metric | Default | Overridable in `config.yaml` |
|---------|--------|---------|------------------------------|
| SAST | maxCritical / maxHigh / maxMedium | 0 | yes |
| SCA (SonarQube) | maxCritical / maxHigh / maxMedium | 0 | yes |
| Dependencies (Nexus IQ) | maxCritical / maxHigh / maxMedium | 0 | yes |
| DAST | maxCritical / maxHigh / maxMedium | 0 | no |
| Coverage | minLine | 60% | yes |
| SAST IRX generation | prepareTimeoutMin | 20 min |
| SAST result polling | pollTimeoutMin | 50 min |
| SAST result polling | pollIntervalSec | 30 s |
| DAST result polling | pollTimeoutMin | 60 min |
| DAST result polling | pollIntervalSec | 60 s |

---

## 12. Advanced: using library methods directly

If the standard pipeline does not fit your project structure, you can call individual library methods from a custom Jenkinsfile. Import the library and use `devSecOpsPipeline` as an object:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

pipeline {
    agent any

    environment {
        SA_LINUX_URL       = 'https://tools.bbh.com/nexus/repository/releases/...'
        PROXY_HOST         = 'tstproxy.bbh.com'
        PROXY_PORT         = '9090'
        PROXY_USER         = 'PROXY_ASOCJenk'
        APPSCAN_HOST       = 'bbh.cloud.appscan.com'
        APPSCAN_SERVER_URL = 'https://bbh.cloud.appscan.com'
        APPSCAN_TOOLS_DIR  = "${WORKSPACE}/.appscan-tools"
        APPSCAN_LOG_DIR    = "${WORKSPACE}/.appscan-logs"
        APPSCAN_HOME_DIR   = "${WORKSPACE}/.appscan-home"
        APPSCAN_BIN_DIR    = "${WORKSPACE}/.appscan-bin"
    }

    stages {
        stage('Init') {
            steps {
                script {
                    checkout scm
                    devSecOpsPipeline.initialize()
                    devSecOpsPipeline.appscanSetup()
                    def projects = devSecOpsPipeline.getProjects()
                    for (p in projects) {
                        devSecOpsPipeline.switchProject(p)
                        devSecOpsPipeline.appscanResolveSourceDir()
                        devSecOpsPipeline.appscanGenerateIRX()
                    }
                    devSecOpsPipeline.appscanLogin()
                }
            }
        }

        stage('Build and test') {
            steps {
                script {
                    devSecOpsPipeline.buildArtifact()
                    devSecOpsPipeline.unitTests()
                    devSecOpsPipeline.checkCoverage(60)
                    devSecOpsPipeline.depVulnScan()
                    devSecOpsPipeline.codeQualityScan()
                }
            }
        }
    }

    post {
        always {
            script {
                devSecOpsPipeline.generateHtmlReport()
                devSecOpsPipeline.feedInfluxDB()
            }
        }
    }
}
```

### Complete method reference

| Method | Description |
|--------|-------------|
| `initialize()` | Load config, compute effective and library thresholds, detect OS |
| `appscanSetup()` | Download and extract SAClientUtil |
| `appscanLogin()` | Authenticate the AppScan CLI |
| `appscanResolveSourceDir(String path = null)` | Set the IRX source directory |
| `appscanGenerateIRX(int timeoutMin = 120)` | Compile and generate the IRX archive |
| `appscanQueue(Map config)` | Queue the SAST scan for the current project |
| `appscanWait()` | Poll ASoC until the SAST scan completes |
| `appscanDownloadReports()` | Download the SAST report and parse the counts |
| `appscanRenameSastReport()` | Rename the SAST report with the scan name |
| `appscanRenameDastReport()` | Rename the DAST HTML and PDF reports with the scan name |
| `appscanEnforcePolicy()` | Enforce the SAST thresholds, returns critical + high + medium |
| `sonarscanEnforcePolicy()` | Enforce the SonarQube quality gate |
| `dastScan()` | Full DAST run, downloads the HTML and PDF reports, returns the count |
| `buildArtifact()` | Build with Gradle, Maven or Flutter |
| `unitTests()` | Run unit tests with coverage collection |
| `checkCoverage(int min = 60)` | Enforce the project coverage threshold |
| `depVulnScan()` | Nexus IQ scan; raises the GoldenFix pull request on a violation |
| `codeQualityScan()` | SonarQube analysis |
| `reportArtifactBuild()`, `reportUnitTests()` | BBH build reporting |
| `smokeTests()`, `regressionTests()`, `performanceTests()` | Run all configured jobs with the configured parallelism |
| `releaseAllowed(String stageName)` | Release gate check; marks the stage `BLOCKED` and the build UNSTABLE when the library policy is not met |
| `publishReleaseGate()` | Write `release-gate.json` so the downstream pipeline inherits the verdict |
| `deployRD()` / `deployDvWithDodPlugin()` | Deploy to the RD environment |
| `deployQC()` | Deploy to the QC environment |
| `deployOpenshift(String env)` | Deploy to OpenShift, env `rd` or `qc` |
| `publishArtifactQC()`, `pushToNexus()` | Publish the artifact to Nexus |
| `nexusDelivery(String env)` | Tag the image in the registry |
| `buildDockerImage(String projectName)`, `copyImageToNexus()`, `checkDeploymentRepo()` | OpenShift image build and promotion |
| `bumpVersion(String file, String version)` | Update `APP_VERSION` in version.properties |
| `generateHtmlReport()` | Generate the HTML pipeline report |
| `feedInfluxDB(String pipelineType)` | Send DORA metrics to InfluxDB |
| `getProjects()`, `getCFG()` | Project names and the current project config |
| `switchProject(String name)` | Switch the project context and recompute its thresholds |
| `stageStart/stageDone/stagePass/stageFail/stageWarn/stageError` | Stage bookkeeping for the report |
| `logStageResult(String name, String status)` | Print the stage result and the scanner summary |
| `section(String text)`, `startSection(String text)`, `endSection(String text)` | Console separators |

---

## 13. Supported build tools

| Tool | Build | Unit tests | Coverage | Publish |
|------|-------|-----------|---------|---------|
| Gradle | `./gradlew clean build -x test` | `./gradlew clean test jacocoTestReport jacocoTestCoverageVerification` | JaCoCo XML | `./gradlew publish` |
| Maven | `mvn clean package -DskipTests` | `mvn test jacoco:report` | JaCoCo XML | `mvn deploy` |
| Flutter | `flutter build <platform>` | `flutter test --coverage --machine` | lcov | N/A |

The build tool is auto-detected from `buildTool` in config or by file existence (`pubspec.yaml` → Flutter, `pom.xml` → Maven, `build.gradle` → Gradle).

For Gradle and Maven, the library prefers wrapper scripts (`gradlew`, `./mvnw`) if they exist.

### JaCoCo report auto-detection (Gradle)

Searched in order:
1. `build/jacoco/jacoco.xml`
2. `build/reports/jacoco/test/jacocoTestReport.xml`
3. `build/reports/jacoco/jacocoTestReport.xml`
4. `coverage.reportPath` from `config.yaml` (custom path)

### JaCoCo report auto-detection (Maven)

Searched in order:
1. `target/site/jacoco/jacoco.xml`
2. `target/jacoco/jacoco.xml`
3. `coverage.reportPath` from `config.yaml` (custom path)

---

## 14. Supported deployment targets

### VM deployment (SSH)

Set `deployTarget: vm` in your project config (this is the default).

The library connects via SSH and:
1. Verifies connectivity (`ssh ... uptime`)
2. Creates the deploy directory on the remote host
3. Copies `deployScript` and `versionFile` via SCP
4. Executes `deployScript` on the remote host

For this to work:
- The Jenkins agent must have SSH access to the deployment host (key-based auth)
- `deployScript` must exist in your project and be executable
- `versionFile` must contain `APP_VERSION=<version>` for version verification

### OpenShift deployment

Set `deployTarget: openshift` in your project config.

The library uses the OpenShift Jenkins Plugin (`openshift.withCluster()`) and:
1. Reads cluster URL and credentials from `deploy.openshift.<env>`
2. Applies `deployConfig.yml` (must exist in your repository root)
3. Restarts the deployment rollout
4. Waits for rollout to complete

For this to work:
- The OpenShift Client Plugin must be installed in Jenkins
- Cluster credentials must be stored in Jenkins with the configured `credentialsId`
- `deployConfig.yml` must exist in your project repository

---

## 15. Troubleshooting

### "config.yaml not found in workspace"

Your project repository must contain a `config.yaml` file at the repository root. The file must have a `projects:` section.

### "asoc.keyId and asoc.keySecret must be set in config.yaml"

Every project in `config.yaml` must have:
```yaml
asoc:
  keyId:     "bbh_..."
  keySecret: "..."
```
These credentials are obtained from the HCL AppScan on Cloud portal.

### "PROJECT_NAMES not set"

Either pass `projectNames` to `devSecOpsPipeline()`:
```groovy
devSecOpsPipeline(projectNames: 'my-app')
```
Or the library auto-detects all keys from the `projects:` section of your `config.yaml`.

### SAST stage takes very long or times out

- Default `sast.pollTimeoutMin` is 50 minutes. This is a library default that only the library team can change.
- The IRX generation step (compile via Gradle + AppScan prepare) can take 10–20 minutes on large projects.

### "IRX not found after prepare"

- The Gradle/Maven build during IRX generation failed. Check the `prepare.log` file in `.appscan-logs/`.
- Ensure the Gradle wrapper (`gradlew`) is executable and committed to the repository.
- The library sets `chmod +x gradlew` automatically on Linux/Mac.

### SonarQube stage fails with "SonarQube server not configured"

The SonarQube server must be registered in Jenkins as `SonarQube` (exactly this name) under *Manage Jenkins → Configure System → SonarQube servers*.

### Nexus IQ stage shows 0 vulnerabilities but the build is unstable

The Jenkins sandbox may have blocked direct access to the `nexusPolicyEvaluation` result object. The library has a fallback that parses the build console log. If that also fails, counts remain 0 but the build result (UNSTABLE/FAILURE) set by the Nexus IQ plugin itself is still respected.

### HTML report is empty or shows "Report could not be generated"

This happens when the pipeline fails very early (before `initialize()` completes). A fallback minimal HTML file is always written. Check the stage 1 console output for the root cause.

### Tests stage fails with "No test jobs configured"

All three test stages (smoke, regression, performance) require at least one configured job:
```yaml
tests:
  smoke:
    jobs:
      - name: "My job"
        type: local
        job:  "path/to/job"
```
If you do not have test jobs yet, set `job: ""` and `type: local` – the job will be triggered but show `NOT_CONFIGURED` status. Note that an empty `job:` skips execution silently while the stage still passes.

Actually, if `job:` is empty the individual entry is marked `NOT_CONFIGURED` but since it did not fail, the overall stage will not fail. If the entire `jobs:` list is empty or missing, the stage fails with a mandatory error.

### Nexus delivery or QC deployment was skipped

The release gate blocked it. The **Release policy** card of the report lists every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0` or `line coverage 41.0% below the required 60%`. Remediate the findings, merge the GoldenFix pull request, or accept that this build stays on RD.

### GoldenFix pull request was not raised

The GoldenFix card shows the status. `NOT_CONFIGURED` means `scm.bitbucket.url` or `scm.bitbucket.credentialsId` is missing. `NO_FIXES` means Nexus IQ offered no remediation version for the violating direct dependencies. `NO_MANIFEST_CHANGES` means the vulnerable versions are not declared in any supported manifest, typically because a parent POM or a BOM manages them. `ERROR` means the `[GOLDENFIX]` lines in the console hold the reason, usually Nexus IQ or Bitbucket permissions.

### DAST PDF report is missing in the pipeline report

The stage downloads the report as HTML and as PDF and archives both. When only the HTML link is present, look for `[DAST] PDF report not found` in the console; the PDF generation in ASoC may have taken longer than the download window.

### Hundreds of smoke jobs

All jobs are executed even when some fail, and at most `tests.maxParallel` run at the same time. Use `tests.smoke.defaults` for shared settings and `tests.smoke.urls` for plain URL lists. Raise `maxParallel` only when the remote Jenkins instances have enough executors.
