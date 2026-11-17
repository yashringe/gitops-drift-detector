# Example manifests

Used by the end-to-end job in `.github/workflows/ci.yml`:

1. a kind cluster is created and these manifests are applied,
2. the workflow then changes the cluster by hand (scale, edit a ConfigMap, delete the Service, add an unmanaged ConfigMap),
3. the detector runs against this folder and must report exactly those changes.

`cpu: 0.1` is on purpose: the API server stores it as `100m`, and the detector must not call that drift.
