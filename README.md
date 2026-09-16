What the library does now
Multiple smoke tests (and regression, performance)

runTestJobs in BuildService.groovy was rewritten to handle any number of jobs in one stage, for example two hundred smoke jobs on two hundred different URLs.
A job entry can be a local Jenkins job path, a full remote job URL given as job: or url:, or a bare URL string in the list.
tests.<type>.defaults is merged into every job entry, so shared settings such as type, timeout and credentials are written once.
tests.<type>.urls is a shorthand list where each element is just a remote job URL.
Job names are derived from the URL when name is absent, and duplicated names get a numeric suffix so parallel branch names stay unique.
The job type defaults to remote when the reference is an HTTP URL, so no remoteJenkins entry is needed for URL based jobs.
remoteJenkinsName is now sent only when configured, remoteJenkinsUrl is supported as an override, and credentialsId is converted into a CredentialsAuth object for the remote trigger plugin.
Concurrency is capped by tests.maxParallel, default twenty, with a per stage override in tests.<type>.maxParallel.
When the job count exceeds the cap, a worker pool pulls jobs from a shared cursor; below the cap each job still gets its own parallel branch for visibility in Jenkins.
Every job runs even when others fail, and the stage fails once at the end with an aggregated message that lists the first ten failures and counts the rest.
Timeouts are recorded as TIMEOUT while a user abort is rethrown, so aborting a build no longer looks like a test failure.
Results are stored per project and also aggregated, so multi project builds no longer overwrite each other's job results.
Boolean job options are parsed through BuildUtils.booleanValue, so a YAML string such as "false" is no longer treated as true.
The report stage box shows counter chips, lists the unsuccessful jobs first, and hides the full list behind a collapsible element when there are more than ten jobs.
A dedicated Smoke tests card renders a scrollable table with index, name, type, status, duration and a build link per job, grouped by project.
GoldenFix remediation after a Nexus IQ failure

NexusIqService.groovy triggers the remediation whenever the policy is violated, and the pull request link is appended to the stage error message.
The feature follows the hexagonal layout of the project: ports in remediation/port, the application service in GoldenFixService.groovy, adapters in scanner, scm and updater.
GoldenFixFactory.groovy is the composition root and replaced an eleven line construction block that was duplicated in three pipelines.
The Nexus IQ adapter resolves the application internal id, reads the policy report of the scan, and asks the Component Remediation API for a target version per component.
The Golden Version, meaning the remediation type recommended-non-breaking-with-dependencies, is preferred, with documented fallbacks for ecosystems where Sonatype does not offer it.
Only direct dependencies with non waived violations at or above a configurable threat level are considered, limited to the Maven, npm and pypi ecosystems.
Maven manifests are updated in dependencies and dependencyManagement, including versions expressed as properties, which are resolved in a second pass across parent POMs.
Gradle manifests are updated for string notation, map notation, Kotlin DSL named arguments, gradle.properties, ext, val and extra variables, and version catalogs including version.ref entries.
npm manifests are updated in dependencies, devDependencies, peerDependencies and optionalDependencies, the range operator such as caret or tilde is preserved, and node_modules is skipped.
pip manifests are updated in requirements*.txt, constraints*.txt and pyproject.toml for both PEP 621 and Poetry syntax, while hash pinned files are left untouched with a note.
The whole source tree is searched, so multi module and monorepo layouts are covered, with build output and virtual environment directories excluded.
A version comparison utility makes sure only real upgrades are written, and ranges, wildcards and unresolved properties are reported instead of being rewritten.
All edits happen in a separate git worktree, so the pipeline workspace stays untouched and later stages still build the original sources.
The branch and the pull request are both named GoldenFix-YYYYMMDDHHMM, exactly as requested.
Bitbucket Server and Bitbucket Cloud are both supported, and the repository is derived from a browse link, a clone link, a REST link or a cloud link given in scm.bitbucket.url.
Git credentials are passed through a credential helper or GIT_CONFIG_* variables, so no secret ever appears in the remote URL or in the process arguments.
All projects of one build share a single pull request; a second project pushes an additional commit onto the same branch instead of opening a new one.
The report states that a pull request was raised, with the link, in the Nexus IQ stage box, in the Security Gates table and in a dedicated GoldenFix card that lists every applied change and every fix that could not be applied automatically.
Remediation failures are caught and recorded with an ERROR status, so they never mask or replace the original Nexus IQ policy failure.
AppScan scan id and the DAST report as PDF

dastStartScan now declares a String return type instead of void, which was also the reason the file did not compile with a plain Groovy compiler.
The DAST report is downloaded twice, as HTML for parsing the vulnerability counts and as PDF for linking.
Both files are renamed with the scan name, and the PDF name is recorded in the pipeline state as a scan artifact.
The PDF is archived in three pipelines and linked in four places of the report, the same way the SAST report is linked.
parseHtmlCounts reads the report through readFile instead of shelling out to cat, which also removed a Windows specific branch.
Project thresholds and the release gate

A project may now raise its own thresholds in config.yaml for SAST, SCA, Nexus IQ and unit test coverage, and for nothing else.
ConfigLoader.groovy computes effective limits from the project configuration and hard limits from the library defaults, recomputes them on every project switch, and records them per project.
DAST thresholds are deliberately taken from the library defaults only and ignore any project override.
Exceeding a project threshold, or missing the project coverage minimum, fails the stage and the pipeline, with no override path.
ReleaseGate.groovy compares the recorded counts and the measured coverage against the library policy and decides what may leave the pipeline.
When the library policy is exceeded, the snapshot delivery, the release delivery and the QC deployment are all skipped, while the RD deployment still runs so developers can work.
A blocked stage is marked BLOCKED with the exact reason, and the build is set to UNSTABLE rather than silently passing.
The verdict is written to release-gate.json, archived by the security pipeline and copied by the extended pipeline, so a policy violation found before DAST still blocks the release afterwards.
The report shows a blocking banner, a Release policy card listing every violation, and renders blocked stages in amber with their reason.
Cleanup and deduplication

All AppScan REST calls for DAST now go through one curlCommand builder plus helpers for the API URL and the authorization headers, replacing seven copies of the same flags.
The repeated shell proxy preamble in the AppScan CLI scripts became a single constant used in four places.
An optional asoc.insecureTls flag is the single place that can add curl -k, and it is off by default; no -k or --insecure exists anywhere else in the repository.
The report service was restructured around small builders for table cells, links, cards, banners and scanner rows, so the HTML is generated from parameters instead of being pasted repeatedly.
buildHtml was split into a context map plus separate builders for the stage flow, the security gates, the coverage card and the Sonar badge cards.
StageLogger.groovy replaced the stage logging and section helpers that were duplicated in four pipeline files.
deployRd and deployQc in VmDeployService.groovy were reduced to a shared deployTo with a vmConfig helper.
Groovy comments were removed from the sources, while the intentionally commented out stage bodies, the YAML configuration comments and the comments inside generated shell scripts were preserved.
Dead code was deleted: tagInNexus, verifyRdVersion, the unused Skopeo helpers in the OpenShift service, and an unused enforce method in the policy engine.
What was replaced or removed during the work
The first deployment gate, which allowed RD below ten findings and required zero for QC, was removed completely and replaced by the release gate driven by the library defaults.
The deployGate configuration section was deleted from the defaults and replaced by releaseGate with the scanner list, the coverage switch and the state file name.
The checkDeployGate helpers added to the pipelines were removed, and RD deployment is no longer gated at all.
The build trigger detection, which distinguished automatic from manual runs, disappeared together with the deployment gate because the decision no longer depends on it.
OVERRIDE_BBH_POLICIES was removed from the parameters of every pipeline and from the policy engine, the Nexus IQ service, the DAST policy check, the test job runner, the report and both documentation files.
The overridePolicies and unstableStage fields were dropped from the pipeline state, since the release gate now sets the build result itself.
The SCA defaults, which allowed ten critical and twenty high findings, were corrected to zero, and the old ten vulnerability wording was removed from the documentation.
The explanatory comments I had added to the new classes were removed later at your request, so the code carries no comments.
Verification and open points
Everything compiles locally with Groovy 2.4 and JDK 11, which is the version Jenkins uses, including the AppScan service that previously failed to compile.
Test suite	Result
Release gate, threshold overrides, cross pipeline propagation	22 / 22
GoldenFix, manifest updaters, Bitbucket parsing, test job normalization, end to end on a real git repository	75 / 75
HTML report, including PDF links, GoldenFix card and release policy card	15 / 15
The shell scripts and REST commands generated by the AppScan service were captured before and after the refactor and compared byte by byte; only the intended DAST changes appear.
Nothing was verified against a live Jenkins, Nexus IQ, Bitbucket or AppScan instance, so the first real pipeline run is still the acceptance test.
Two methods are called but missing in VmDeployService, namely deployDvWithDodPlugin used by the main pipeline and pushToNexus used by the security pipeline; they will fail at runtime in this copy of the repository.
The SAST, SCA and Nexus IQ stage bodies are still commented out in the main pipeline, so in that pipeline the release gate has no scan data to judge and will allow the release.
