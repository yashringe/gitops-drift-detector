# gitops-drift-detector

Finds the differences between the Kubernetes manifests in a Git repository and what is actually running in the cluster. Someone scaled a Deployment by hand during an incident, edited a ConfigMap with `kubectl edit`, or deleted a Service. This tool reports it, as a pull request comment, as JSON for scripts, or as Prometheus metrics for alerting.

```
## GitOps drift report
Cluster `kind-chart-testing` compared with `examples/k8s` at 2026-10-28 10:30 UTC.

| Status             | Objects |
|--------------------|--------:|
| In sync            | 1 |
| Drifted            | 2 |
| Missing in cluster | 1 |
| Not in Git         | 1 |

### Drifted
apps/v1 Deployment demo/web: 1 field
| Field          | Git | Cluster |
| `spec.replicas`| `2` | `4`     |
...
```

## How it works

```
 Git checkout ──▶ ManifestLoader ──▶ desired objects ──┐
 (rendered YAML)    (SnakeYAML, safe)                   ├──▶ DriftCalculator ──▶ DriftReport ──▶ Markdown / JSON / metrics
 Kubernetes API ──▶ LiveStateFetcher ──▶ live objects ──┘
                   (Fabric8, get/list only)
```

1. Load every `.yaml`/`.yml` file in the folder (multi-document files and `kind: List` are supported).
2. Fetch each object from the cluster with the Fabric8 client's generic API, so custom resources work too.
3. Compare field by field and classify each object as **in sync**, **drifted**, **missing in cluster**, **not in Git** (labelled as managed but has no manifest) or **could not check** (for example RBAC denies access).

### Comparison rules

The hard part of drift detection is avoiding false alarms. The API server adds defaults and rewrites values, and a tool that reports all of that is useless. So:

* **Only fields written in Git are compared.** Defaults such as `revisionHistoryLimit`, `imagePullPolicy` or `protocol: TCP` are ignored. This follows the same idea as Server-Side Apply's field ownership.
* **Named list items are matched by key**, not by position: containers and env vars by `name`, ports by `name` or `containerPort`. An injected sidecar container does not shift the comparison.
* **Same value, different spelling, is not drift:** `cpu: 0.1` vs `100m`, `1Gi` vs `1024Mi`, `8080` vs `"8080"`. Version-like strings stay strings (`"1.10"` ≠ `"1.1"`).
* **Server-owned fields are never compared:** `status`, `uid`, `resourceVersion`, `managedFields`, `last-applied-configuration`, ...
* **Secrets:** `stringData` in Git is converted to base64 `data` before comparing, and values are always shown as `<redacted>`.
* **Fields owned by other controllers** can be ignored, e.g. `--drift.ignore-fields=spec.replicas` when an HPA scales the Deployment.

## Usage

Build with `mvn package` (JDK 21), then:

```bash
# uses \~/.kube/config like kubectl
java -jar target/gitops-drift-detector-0.1.0-SNAPSHOT.jar \\
  --drift.manifests=examples/k8s \\
  --drift.managed-by-label=app.kubernetes.io/managed-by=gitops \\
  --drift.output=drift-report.md
echo $?   # 0 = in sync, 1 = drift, 2 = check failed
```

|Option|Default||
|-|-|-|
|`--drift.manifests`|(required)|Folder with rendered manifests|
|`--drift.format`|`markdown`|`markdown` or `json`|
|`--drift.output`|stdout|Report file. Logs always go to stderr, so `> report.md` also works.|
|`--drift.default-namespace`|`default`|For objects without `metadata.namespace`|
|`--drift.ignore-fields`|none|Comma-separated path prefixes, e.g. `spec.replicas`|
|`--drift.managed-by-label`|off|`key=value`; finds objects in the cluster with this label but no manifest|

**Helm / Kustomize:** render first and point the tool at the output, so it compares exactly what would be applied:

```bash
helm template my-release ./chart -f values-prod.yaml > rendered/all.yaml
java -jar gitops-drift-detector.jar --drift.manifests=rendered
```

### Server mode

```bash
java -jar gitops-drift-detector.jar --spring.profiles.active=server --drift.manifests=/git/current/k8s --drift.interval=5m
```

* `GET /api/drift` and `GET /api/drift.md`: latest report
* `GET /actuator/prometheus`: `gitops\_drift\_objects{status="drifted"}`, `gitops\_drift\_last\_success\_seconds`, `gitops\_drift\_check\_failures\_total`
* `deploy/k8s/server.yaml` runs it in the cluster next to a [git-sync](https://github.com/kubernetes/git-sync) sidecar, with read-only RBAC (`deploy/k8s/rbac.yaml`) and an example alert (`deploy/prometheus-alert.yaml`).

## CI

`.github/workflows/ci.yml` has two jobs:

1. **build**: `mvn verify` (unit tests plus tests against Fabric8's mock API server) and a Docker build.
2. **e2e-kind**: creates a real Kubernetes cluster with kind, applies `examples/k8s`, and checks that a freshly applied cluster reports **no drift** (no false positives). Then it scales the Deployment, edits the ConfigMap, deletes the Service and adds an unmanaged ConfigMap, and checks that exactly those four changes are reported with exit code 1. On pull requests the report is posted as a comment (updated on every push instead of adding new ones) and added to the job summary.

## Tests

* `DriftCalculatorTest`: defaults, quantities, named lists, injected sidecars, annotation paths, ignore rules, secrets
* `ManifestLoaderTest`: multi-document files, `kind: List`, duplicates, missing names, and a YAML deserialisation attack (`!!javax.script.ScriptEngineManager`) that must be refused
* `DriftDetectorTest`: whole check against an in-memory fake cluster, including RBAC errors and secret redaction
* `KubernetesLiveStateFetcherTest`: the real Fabric8 client against Fabric8's mock API server
* `DriftCheckRunnerTest`: starts the Spring Boot app in CLI mode and checks report file and exit code

## Design decisions

* **Read-only.** The tool only uses `get` and `list`. Fixing drift is the job of the deploy pipeline or Argo CD/Flux; mixing detection and correction in one tool makes it dangerous to run.
* **Exit codes for pipelines.** 1 (drift) and 2 (could not check) are different, so a pipeline can fail on drift but retry on a broken connection.
* **One bad object does not stop the check.** A forbidden or unknown kind becomes a "could not check" line, and the rest of the report is still produced.
* **No templating built in.** Rendering Helm or Kustomize is left to those tools. Reimplementing them would be a source of false results.
* **Interface between logic and cluster.** `DriftDetector` only knows `LiveStateFetcher`, so most tests run without any Kubernetes at all.

## Limitations and ideas

* Items that exist only in the cluster inside a list (e.g. an extra env var) are not reported, because the API server and admission webhooks add such items legitimately.
* Custom resources need their CRD installed in the cluster so the client can discover them.
* A `--drift.fail-on=drifted,missing` option to choose which statuses fail the pipeline.
* Use managed fields to report who changed a field (`kubectl`, a controller, ...).

