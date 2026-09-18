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
11. [Policy thresholds](#11-policy-thresholds)
12. [Advanced: using library methods directly](#12-advanced-using-library-methods-directly)
13. [Supported build tools](#13-supported-build-tools)
14. [Supported deployment targets](#14-supported-deployment-targets)
15. [Troubleshooting](#15-troubleshooting)
16. [SelfService – onboard a project step by step](#16-selfservice--onboard-a-project-step-by-step)

---

## 1. What the library provides

The library executes the following pipeline automatically when you call `devSecOpsPipeline()` from your Jenkinsfile:

| # | Stage | What it does |
|---|-------|-------------|
| 1 | Monitor source changes | Checkout, AppScan setup, IRX generation per project |
| 2 | Unit tests | Build tool tests plus JaCoCo/lcov line coverage check against the library minimum |
| 3 | Dependencies scan (Nexus IQ) | Dependency scan, an orange stage on a policy violation and an automatic GoldenFix pull request |
| 4 | SAST – HCL AppScan | Static Application Security Testing, queued per project |
| 5 | SCA (SonarQube) | Static code analysis and quality gate |
| 6 | Nexus delivery (static analysis passed) | Publish the snapshot artifact, always, even when an earlier stage is orange |
| 7 | Lower test region deployment | Deploy to RD (VM over SSH or OpenShift) |
| 8 | Regression tests | Trigger regression jobs, at least one job must be configured |
| 9 | Smoke tests | Trigger smoke jobs, at least one job must be configured |
| 10 | Performance tests | Trigger performance jobs, at least one job must be configured |
| 11 | DAST – HCL AppScan | Dynamic Application Security Testing, HTML report, HCL console link and PDF report |
| 12 | Nexus delivery (safe artifact) | Publish the release artifact, blocked when any earlier stage is orange |
| 13 | Higher test environment deployment | Deploy to QC, only when every earlier stage is green and the checkbox is selected |

### Which pipeline to use

The library ships three entry points. They share the same services, thresholds, release gate and report.

| Entry point | Runs | Use it when |
|-------------|------|-------------|
| `devSecOpsPipeline` | All thirteen stages in one build: unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery, RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment | One job should cover the whole flow |
| `devSecOpsSecurityPipeline` | Unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery; archives `config.yaml` and the release gate verdict | The static part runs on every commit and the rest is a separate job |
| `devSecOpsExtendedPipeline` | RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment | Downstream of the security pipeline; name that job in `securityPipeline:` and it inherits the release verdict through the copied `release-gate.json` |

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
  The project must have runnable unit tests. For Gradle and Maven, JaCoCo must be configured in the build script to produce a coverage XML report. The library requires a minimum line coverage of 60 %, taken from `resources/defaults.yaml` only; a project cannot set its own value. Below that level the Unit tests stage turns orange, the pipeline keeps running and reaches RD, and the Nexus release and the QC deployment stay blocked. Flutter projects must use the `--coverage` flag which produces `coverage/lcov.info`.

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
    │   ├── PolicyEngine.groovy         <- library policy checks, marks a stage orange and keeps the build running
    │   ├── ReleaseGate.groovy          <- blocks the Nexus release and the QC deployment when a stage is not green
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
| `resources/defaults.yaml` (inside library) | Library team only | Security thresholds, required coverage, timeouts, tool defaults |
| `config.yaml` in your project repo | Project team | Project-specific settings (appId, servers, test jobs, deploy targets) |

Projects **cannot** override the `defaults` – they are embedded in the library. Thresholds for SAST, SCA, Nexus IQ, DAST and the required line coverage exist in one place only, `resources/defaults.yaml`. To change one, the library must be updated and redeployed.

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

### Script security

The library runs inside the Jenkins script sandbox and uses no construct that requires an administrator to approve a signature. Collections are built from literals, JSON is read and written with the `readJSON` and `writeJSON` steps, and regular expressions use the Groovy operators. Nothing has to be approved under *Manage Jenkins, In-process Script Approval* to onboard a new project.

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

devSecOpsPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

Replace `my-app` with the key name(s) you will use in `config.yaml`, and `agentNames` with the Jenkins agent labels your builds may run on. Both entries are required: `agentNames` fills the `AGENT_NAME` build parameter.

For multiple projects (e.g. a monorepo with GUI and API):

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent', 'windows-agent']
)
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
| `DEPLOY_HIGHER_ENV` | `false` | When `true`, the pipeline deploys to the QC environment, provided every earlier stage is green. |

A policy violation never fails the pipeline. The stage turns orange, the build is marked UNSTABLE and keeps running, and the Nexus release plus the QC deployment stay blocked until the findings are fixed. There is no override parameter and no project threshold.

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
- **Stage 12** is skipped when any earlier stage is orange.
- **Stage 13** only runs when `DEPLOY_HIGHER_ENV=true` is selected at build time and every earlier stage is green.
- Orange stages do not stop the run: every stage from unit tests to DAST is executed on every build.

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
      reportPath: ""                 # custom JaCoCo XML path (optional, auto-detected if empty)
                                     # the required 60% comes from the library defaults and cannot be set here

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

    sast:
      scanName: ""                   # AppScan scan name (auto-generated from sonar.projectName if empty)

    sca:
      scanName: ""                   # reserved for future use

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
- Compares the line coverage with `coverage.minLine` of `resources/defaults.yaml` (60 %)

The stage never fails on coverage. Below the required value the stage turns **orange**, the report box shows the measured value next to the required one, and the reason states that the step failed and that the Nexus release and the QC deployment are blocked until the coverage is raised. A missing coverage report is treated the same way, because the required level cannot be proven.

### Stage 3: Dependencies scan (Nexus IQ)

Runs `nexusPolicyEvaluation` with your configured scan patterns and compares the critical, severe and moderate counts with the library thresholds (`tools.nexusIq.max*` of `resources/defaults.yaml`, all 0). A violation turns the stage orange with the counts in the reason, starts the GoldenFix remediation described below and blocks the release and QC, while the pipeline keeps running.

### Stage 4: SAST – HCL AppScan

Queues all projects for SAST scan simultaneously, then waits for results sequentially. Downloads HTML reports, parses vulnerability counts and compares them with `sast.maxCritical`, `sast.maxHigh` and `sast.maxMedium` of `resources/defaults.yaml` (all 0). Above the limits the stage turns orange with the counts, and the release and QC stay blocked.

#### GoldenFix remediation (automatic pull request)

When the Nexus IQ policy is violated (critical, high or medium findings above the limits) the library:

1. Fetches the violating components of the evaluation from Nexus IQ (`/api/v2/applications/{app}/reports/{scanId}/policy`) and keeps only **direct dependencies** with a non-waived violation of at least medium threat level.
2. Asks the Component Remediation API (`/api/v2/components/remediation/application/{id}`) for the target version. The Golden Version (`recommended-non-breaking-with-dependencies`, Maven only) is preferred, then `next-no-violations(-with-dependencies)`, `recommended-non-breaking` and `next-non-failing(-with-dependencies)`.
3. Searches the whole source tree (multi-module projects included, `node_modules`, `target`, `build`, virtualenvs skipped) and updates:
   - Maven: `pom.xml` dependencies, dependencyManagement and the `<properties>` they reference (also in parent POMs)
   - Gradle: `build.gradle`, `build.gradle.kts`, `gradle.properties`, `ext`/`val` version variables, version catalogs `*.versions.toml`
   - npm: `package.json` dependencies, devDependencies, peerDependencies, optionalDependencies (range operator preserved)
   - pip: `requirements*.txt`, `constraints*.txt`, `pyproject.toml` (PEP 621 and Poetry)
4. Commits the changes in a separate git worktree (the pipeline workspace is not modified), pushes branch `GoldenFix-YYYYMMDDHHMM` and raises a Bitbucket pull request with the same name. All projects of one build share the pull request.
5. The HTML report shows the pull request link in the Nexus IQ stage, in the Security Gates table and in the **Nexus IQ GoldenFix** card together with the applied changes and the fixes that could not be applied automatically (e.g. versions managed by a BOM, ranges, hash-pinned requirements).

GoldenFix never changes the result of the Nexus IQ stage: the stage stays orange on the policy violation, and a GoldenFix error is only reported. Lock files (`package-lock.json`, `poetry.lock`, pinned requirements) are not regenerated. Requires `scm.bitbucket.url` and `scm.bitbucket.credentialsId` and a Linux/macOS agent with git.

### Stage 5: SCA (SonarQube)

Runs SonarQube analysis and waits for the quality gate. Fetches issue counts (Blocker+Critical, Major, Minor, Info) for the HTML report. A failed quality gate or a failed scan turns the stage orange with the reason and blocks the release and QC, without stopping the pipeline.

### Stages 6–10: Delivery and tests

Stage 6 publishes the snapshot artifact and stage 7 deploys to RD. Both always run, even when an earlier stage is orange, so developers always get a build on the lower test region. Then the tests run in the order regression, smoke, performance, followed by DAST. Each test stage needs at least one configured job.

Every test stage runs **all** configured jobs, even when some of them fail, and then turns orange with a summary of the failed jobs. A stage may contain any number of jobs (e.g. 200 smoke tests on different remote Jenkins instances). At most `tests.maxParallel` (default 20, per-stage override `tests.<type>.maxParallel`) jobs run at the same time. Remote jobs can be referenced by full job URL (`url:` or `job: https://...`) without a Remote Jenkins global configuration; `credentialsId` provides user + API token. The HTML report shows per-project counters (total / passed / failed / not configured), the failed jobs, a collapsible list of all jobs and a **Smoke tests** table with a build link for every job.

### Stage 11: DAST – HCL AppScan

Runs only for projects with `dast.enabled: true`. Queues a DAST scan against `dast.targetUrl`. Uses optional `dast.presenceId` for scanning internal (non-internet-accessible) environments.

The report is downloaded twice: as HTML (parsed for the vulnerability counts) and as **PDF**. Both are archived as build artifacts and linked from the pipeline report: in the DAST stage box and in the Security Gates table, which offers three entries, the HTML report, the **HCL AppScan** console page of the scan and the PDF report. Above the DAST thresholds of `resources/defaults.yaml` the stage turns orange with the critical, high and medium counts, and the release and QC stay blocked.

### Stage 12: Nexus delivery (safe artifact)

This is the second delivery to Nexus, into the release repository. It is skipped as soon as **any** earlier stage is orange, so a build with open findings, failed tests or missing coverage is never released.

### Stage 13: Higher test environment deployment

Runs only when `DEPLOY_HIGHER_ENV=true` is selected **and** every earlier stage is green.

### How a failure is reported: orange stages and the release gate

No security or test finding stops the pipeline. Every stage from the unit tests to DAST is executed on every build. A stage that breaks the library policy is marked **orange** (`WARN`), the build becomes UNSTABLE, and the reason printed in the console and shown in the report names the failed step, the counts behind it and the consequence.

| Stage | On a policy violation | Consequence |
|-------|-----------------------|-------------|
| Unit tests | Orange, with the measured and required line coverage | Release and QC blocked |
| Dependencies scan (Nexus IQ) | Orange, with critical / high / medium counts, GoldenFix pull request raised | Release and QC blocked |
| SAST | Orange, with critical / high / medium counts | Release and QC blocked |
| SCA (SonarQube) | Orange, with the quality gate status | Release and QC blocked |
| Nexus delivery (snapshot) | Always runs | – |
| Lower test region deployment (RD) | Always runs | – |
| Regression, smoke, performance | Orange, with the failed jobs | Release and QC blocked |
| DAST | Orange, with critical / high / medium counts | Release and QC blocked |
| Nexus delivery (release) | Skipped when anything above is orange | No release artifact |
| Higher test environment deployment (QC) | Skipped unless everything is green and the checkbox is selected | No QC deployment |

The **release gate** collects the verdict. It blocks the second Nexus delivery and the QC deployment when any stage is not green, when a scanner count exceeds the library policy, or when the line coverage is below the required 60 %. The HTML report shows a **Release policy** card listing every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0` or `line coverage 41.0% below the required 60%`.

Each pipeline evaluates the results it owns and hands its verdict to the next one through the archived `release-gate.json`, so a security pipeline with an orange stage also blocks the release in the extended pipeline.

## 11. Policy thresholds

All thresholds live in `resources/defaults.yaml` inside the library. A project **cannot** change any of them in `config.yaml`: a value put there is ignored. Exceeding a threshold colours the stage orange and blocks the Nexus release and the QC deployment, and never fails the build.

| Scanner | Metric | Library policy | Overridable by a project |
|---------|--------|----------------|--------------------------|
| SAST | maxCritical / maxHigh / maxMedium | 0 | no |
| SCA (SonarQube) | maxCritical / maxHigh / maxMedium | 0 | no |
| Dependencies (Nexus IQ) | maxCritical / maxHigh / maxMedium | 0 | no |
| DAST | maxCritical / maxHigh / maxMedium | 0 | no |
| Coverage | minLine | 60 % | no |
| Test jobs | maxParallel | 20 | yes, per stage in `tests.*.maxParallel` |
| SAST IRX generation | prepareTimeoutMin | 120 min | no |
| SAST result polling | pollTimeoutMin / pollIntervalSec | 50 min / 30 s | no |
| DAST result polling | pollTimeoutMin / pollIntervalSec | 60 min / 60 s | no |

The required coverage is shown in the report exactly as configured in `resources/defaults.yaml`, so raising or lowering it there immediately changes the value the report checks against and displays.

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
                    devSecOpsPipeline.checkCoverage()
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
| `initialize()` | Load config, apply the library policy, detect OS |
| `appscanSetup()` | Download and extract SAClientUtil |
| `appscanLogin()` | Authenticate the AppScan CLI |
| `appscanResolveSourceDir(String path = null)` | Set the IRX source directory |
| `appscanGenerateIRX(int timeoutMin = 120)` | Compile and generate the IRX archive |
| `appscanQueue(Map config)` | Queue the SAST scan for the current project |
| `appscanWait()` | Poll ASoC until the SAST scan completes |
| `appscanDownloadReports()` | Download the SAST report and parse the counts |
| `appscanRenameSastReport()` | Rename the SAST report with the scan name |
| `appscanRenameDastReport()` | Rename the DAST HTML and PDF reports with the scan name |
| `appscanEnforcePolicy()` | Check the SAST findings against the library policy, returns critical + high + medium |
| `sonarscanEnforcePolicy()` | Enforce the SonarQube quality gate |
| `dastScan()` | Full DAST run, downloads the HTML and PDF reports, returns the count |
| `buildArtifact()` | Build with Gradle, Maven or Flutter |
| `unitTests()` | Run unit tests with coverage collection |
| `checkCoverage()` | Compare the line coverage with the required value of `resources/defaults.yaml` |
| `depVulnScan()` | Nexus IQ scan; raises the GoldenFix pull request on a violation |
| `codeQualityScan()` | SonarQube analysis |
| `reportArtifactBuild()`, `reportUnitTests()` | BBH build reporting |
| `smokeTests()`, `regressionTests()`, `performanceTests()` | Run all configured jobs with the configured parallelism |
| `releaseAllowed(String stageName)` | Release gate check; marks the stage `BLOCKED` and the build UNSTABLE when an earlier stage is not green |
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
| `switchProject(String name)` | Switch the project context |
| `stageStart/stageDone/stagePass/stageFail/stageWarn/stageError` | Stage bookkeeping for the report |
| `finishStage(String name)` | Close a stage: green unless the stage already recorded an orange or red result |
| `failStage(String name)` | Close a stage as failed and log the result |
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
devSecOpsPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
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

### Tests stage is orange with "no test jobs configured"

All three test stages (smoke, regression, performance) require at least one configured job:
```yaml
tests:
  smoke:
    jobs:
      - name: "My job"
        type: local
        job:  "path/to/job"
```
An entry with an empty `job:` is reported as `NOT_CONFIGURED` and counts as a job that did not succeed, so the stage turns orange. When the whole `jobs:` list is missing, the stage turns orange as well and the release and QC stay blocked.

### Nexus release or QC deployment was skipped

The release gate blocked it because at least one earlier stage is orange. The **Release policy** card of the report lists every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0`, `stage 'Smoke tests' is WARN` or `line coverage 41.0% below the required 60%`. Remediate the findings, merge the GoldenFix pull request, or accept that this build stays on RD. The snapshot delivery and the RD deployment are never blocked.

### A stage is orange and the build is UNSTABLE

That is the normal reaction to a policy violation or a failed test job. The stage box in the report carries a **Reason** line with the failed step, the vulnerability counts and the sentence that the Nexus release and the QC deployment stay blocked. The pipeline continues with every remaining stage.

### GoldenFix pull request was not raised

The GoldenFix card shows the status. `NOT_CONFIGURED` means `scm.bitbucket.url` or `scm.bitbucket.credentialsId` is missing. `NO_FIXES` means Nexus IQ offered no remediation version for the violating direct dependencies. `NO_MANIFEST_CHANGES` means the vulnerable versions are not declared in any supported manifest, typically because a parent POM or a BOM manages them. `ERROR` means the `[GOLDENFIX]` lines in the console hold the reason, usually Nexus IQ or Bitbucket permissions.

### DAST PDF report is missing in the pipeline report

The stage downloads the report as HTML and as PDF and archives both. When only the HTML link is present, look for `[DAST] PDF report not found` in the console; the PDF generation in ASoC may have taken longer than the download window.

### Hundreds of smoke jobs

All jobs are executed even when some fail, and at most `tests.maxParallel` run at the same time. Use `tests.smoke.defaults` for shared settings and `tests.smoke.urls` for plain URL lists. Raise `maxParallel` only when the remote Jenkins instances have enough executors.

---

## 16. SelfService – onboard a project step by step

Everything below is done by the project team, without any request to the DevSecOps team.

### Step 1 – Check the prerequisites of your application

- The repository is reachable by Jenkins and builds with Gradle, Maven or Flutter.
- Unit tests produce a JaCoCo XML report (Gradle, Maven) or `coverage/lcov.info` (Flutter), with at least 60 % line coverage.
- The build produces a JAR or WAR under `build/libs` or `target`.
- For VM deployment: a deployment script and a `version.properties` with `APP_VERSION=`, plus SSH access from the agent to the RD and QC hosts.
- For OpenShift deployment: `deployConfig.yml` in the repository root and cluster tokens in Jenkins credentials.

### Step 2 – Register your application in the tools

| Tool | What you need | Where it goes in `config.yaml` |
|------|---------------|-------------------------------|
| HCL AppScan on Cloud | Application id (UUID) and an API key pair | `appId`, `asoc.keyId`, `asoc.keySecret` |
| SonarQube | Project key, project name, badge token | `tools.sonar.*` |
| Nexus IQ | Application public id | `tools.nexusIq.application` |
| Bitbucket | Repository URL and credentials with push and pull request rights | `scm.bitbucket.*` |
| InfluxDB (optional) | Write endpoint and token | `influx.*` |

### Step 3 – Create the test jobs

Create at least one Jenkins job for regression, one for smoke and one for performance tests. They may live on this Jenkins instance (`type: local`, `job: "folder/job-name"`) or on any other instance, referenced by full URL. A stage may hold hundreds of jobs; at most `tests.*.maxParallel` run at the same time.

### Step 4 – Add `config.yaml` to your repository root

Copy `config.yaml.template` from this library, keep only the `projects:` section and fill in your values. Do not add thresholds or a coverage minimum: they come from the library. A complete two project example with SAST, DAST, SonarQube, Nexus IQ, GoldenFix, smoke, regression, performance and both deployment targets is in `examples/CertScanner/config.yaml`.

### Step 5 – Add the `Jenkinsfile` to your repository root

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent', 'windows-agent']
)
```

Use `devSecOpsSecurityPipeline` plus `devSecOpsExtendedPipeline` instead when the static part should run on every commit and the rest in a separate job. The extended job takes `securityPipeline: '<name of the static job>'` and copies `config.yaml` and `release-gate.json` from it. Chain the two jobs in Jenkins, the library does not start the second one by itself. A complete example of both is in `examples/README.md`.

### Step 6 – Create the Jenkins job

A Pipeline job with **Pipeline script from SCM**, your Git repository, your branch and the script path `Jenkinsfile`. Nothing else has to be configured.

### Step 7 – Run the pipeline

Click **Build Now**. Select `DEPLOY_HIGHER_ENV` only when you want the QC deployment; it happens only when every stage is green.

### Step 8 – Read the report

Open **Pipeline Report** in the build sidebar:

- the stage flow with one box per stage and per project, green when the stage passed and orange when it broke the policy,
- the **Reason** line of each orange box, with the counts and the consequence,
- the **Security Gates** table with the findings of SAST, DAST, Nexus IQ and SonarQube, and links to the reports, to the HCL AppScan console and to the PDF,
- the **Release policy** card telling you whether the artifact was released to Nexus and whether QC is allowed,
- the **Nexus IQ GoldenFix** card with the pull request that upgrades the vulnerable dependencies,
- the **Smoke tests** table with one row per job.

### Step 9 – Fix what is orange

Merge the GoldenFix pull request, fix the SAST, SonarQube or DAST findings, raise the coverage or repair the failing test jobs. The next green build is released to Nexus and may be deployed to QC.

### Onboarding checklist

- [ ] `Jenkinsfile` and `config.yaml` committed at the repository root
- [ ] `appId`, `asoc.keyId` and `asoc.keySecret` filled in for every project
- [ ] SonarQube project key and Nexus IQ application configured
- [ ] At least one job configured for regression, smoke and performance
- [ ] `scm.bitbucket.url` and `credentialsId` set, so GoldenFix can raise pull requests
- [ ] Deployment target configured, `deploy.vm.*` or `deploy.openshift.*`
- [ ] Unit tests reach 60 % line coverage
- [ ] First build green, report opened, release policy card checked
