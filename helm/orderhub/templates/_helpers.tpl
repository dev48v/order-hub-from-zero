{{/*
Day 44 (Phase 6 — DEPLOY) — named-template HELPERS.
====================================================================
Helm's DRY toolbox: reusable label/selector/image snippets `include`d from the
object templates so the label scheme is defined ONCE and every Deployment, Service,
ConfigMap, Secret and Ingress carries an identical, correct set.

Note on context: inside a `{{- range $name, $svc := .Values.services }}` the dot (`.`)
is rebound to the service value, so the templates pass the ROOT context explicitly as
`$` (e.g. `include "orderhub.commonLabels" $`), and pass the per-service name via a
one-key dict (e.g. `include "orderhub.selectorLabels" (dict "name" $name)`).
*/}}

{{/* Chart name, overridable. */}}
{{- define "orderhub.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* chart-version label value, e.g. orderhub-0.1.0 (SemVer '+' is illegal in a label). */}}
{{- define "orderhub.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels — stamped on EVERY object. Takes the root context ($).
app.kubernetes.io/part-of ties the whole release together (as Day 43 did by hand);
managed-by/chart/version are the standard Helm provenance labels.
*/}}
{{- define "orderhub.commonLabels" -}}
app.kubernetes.io/part-of: orderhub
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ include "orderhub.chart" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{/*
Selector labels — the STABLE match between a Deployment, its pods and its Service.
Takes a dict with a "name" key: {{ include "orderhub.selectorLabels" (dict "name" $name) }}.
Kept minimal (just `app`) because selectors are immutable — provenance labels that
change per release must NOT leak in here.
*/}}
{{- define "orderhub.selectorLabels" -}}
app: {{ .name }}
{{- end -}}

{{/*
Fully-qualified image reference. Takes a dict {svc, root}: prefixes the per-service
image with global.imageRegistry when set, else uses the bare local image name.
  {{ include "orderhub.image" (dict "svc" $svc "root" $) }}
*/}}
{{- define "orderhub.image" -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- if $registry -}}
{{ $registry }}/{{ .svc.image }}:{{ .svc.tag }}
{{- else -}}
{{ .svc.image }}:{{ .svc.tag }}
{{- end -}}
{{- end -}}
