---
title: Label
date: '12.12.2024'
perex: Labels allow tagging the query for later identification.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## Label

```evitaql-syntax
label(
    argument:string!,
    argument:any!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        mandatory string argument representing the name of the label
    </dd>
    <dt>argument:any!</dt>
    <dd>
        mandatory any argument representing the value of the label, 
        any [supported type](../../use/data-types.md#simple-data-types) can be used
    </dd>
</dl>

This `label` constraint allows a single label name with associated value to be specified in the query header and
propagated to the trace generated for the query. A query can be tagged with multiple labels.

Labels are also recorded with the query in the [traffic record](../../operate/observe.md#traffic-recording) and can be
used to look up the query in the traffic inspection or traffic replay. Labels are also attached to JFR events related
to the query.

Each label is a key-value pair appended to the query header, as shown in the following example:

<SourceCodeTabs requires="/evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Attaching labels to query](/documentation/user/en/query/header/examples/labels.evitaql)

</SourceCodeTabs>

<Note type="info">

You can also provide labels using HTTP request headers in the form of `X-EvitaDB-Label: <label-name>=<label-value>`.
You may set multiple labels by providing multiple `X-EvitaDB-Label` headers in the same request.

There are also automatic labels that are added to the query by the system, such as:

- `client-ip`: the IP address of the client that sent the query (real client IP address can be propagated using the
  [X-Forwarded-For](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-For) header)
- `client-uri`: the URI of the client that sent the query, present only if [X-Forwarded-Uri](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-Uri) header is present
- `client-id`: the identification of the client - see [clientId](../../use/connectors/java.md#configuration)
- `trace-id`: current trace ID if [tracing](../../operate/observe.md#tracing) is enabled

<LS to="g">If you use GraphQL API there is also `operation-name` label derived from the query name (if any name is defined).</LS>

</Note>

### Label cardinality and Prometheus export

Labels are designed to tag a query for later identification in traces and traffic recordings, where an unbounded
number of distinct values is expected and harmless - every trace or recorded query is stored individually anyway.
This is **not** true for [Prometheus metrics](../../operate/observe.md#metrics): each distinct combination of label
values becomes its own time series, so a label with unbounded or per-request values (a user ID, a session ID, a
timestamp, a full URL, a free-text string) would keep creating new time series forever and can overwhelm Prometheus
and any dashboard built on top of it.

For this reason no label is exported to Prometheus by default. An operator can opt individual label names in via the
observability API's `exportedQueryLabels` setting (see
[Observability configuration](../../operate/configure.md#observability-configuration)) - the label names are arbitrary
and chosen by the operator, who thereby takes responsibility for keeping their values bounded. Until a name is
configured, its values are only ever visible in traces, traffic recordings and JFR events, never in Prometheus.

A few inherently high-cardinality labels attached automatically by the system - `trace-id`, `client-id`, `ip-address`
and `uri` - are reserved and can never be exported to Prometheus, regardless of configuration. When choosing which
labels to export (or when deciding whether a value is safe to attach to a query at all), keep them bounded and
enum-like - a batch job identifier, a REST endpoint or controller method name - rather than anything derived from user
input, request identifiers or timestamps.

