---
title: Stop guessing! Consult the metrics to see what the database is doing
perex: |
  Metrics are crucial for database operators because they provide the insights needed to maintain 
  performance, reliability, and security. Metrics allow operators to monitor key indicators like query response times, 
  CPU usage, and disk I/O, helping to identify and resolve issues before they impact users. Observability goes beyond 
  simple monitoring, offering a holistic view of the system by capturing logs, traces, and real-time data. 
  This comprehensive understanding is essential for diagnosing complex problems, optimizing performance, and ensuring 
  that the database supports business needs effectively.
date: '25.8.2024'
author: 'Jan Novotný'
motive: assets/images/14-observability.png
proofreading: 'todo'
---

Metrics are the key part of the observability domain and represent one of the main sources of information for 
reliability engineers ([SRE](https://en.wikipedia.org/wiki/Site_reliability_engineering)). evitaDB produces 
[quite a lot of metrics](https://evitadb.io/documentation/operate/observe?lang=java#metrics) in the form of 
[Prometheus text format](https://prometheus.io/docs/instrumenting/exposition_formats/), which is being standardized as 
[Open Metrics format](https://github.com/OpenObservability/OpenMetrics). This format is easily ingested by most popular 
monitoring and observability tools such as [Grafana](https://grafana.com/), 
[Victoria Metrics](https://victoriametrics.com/), or [Datadog](https://www.datadoghq.com/).

We use Grafana and Victoria Metrics for metrics scraping and visualization internally, so we have a dashboard that 
captures all the critical information already at disposal. In this article, we want to show you what this dashboard 
looks like, what graphs it contains, and how they are constructed. You can also download the JSON configuration for 
the dashboard and import it into your Grafana instance to quickly bootstrap your own.

## The Big Picture: High-Level Metrics Visualization

![High-Level Metrics Visualization](assets/images/14-bird-view.png)

First block of graph aggregates key metrics in a simplified way. First graph is a *Current status*, which tells you, 
whether the database is healthy or unhealthy base on the last response of the [liveness probe](https://evitadb.io/documentation/operate/observe#liveness-probe).
If there is any problem, the graph turns red and displays the type of the problem.

```promql
io_evitadb_probe_health_problem{host="$host", job="$job"}
```

Next graph *JVM Errors* displays a counter of internal JVM errors including their short name. Only `java.lang.Error` are
tracked here, so if any of such an error occur or repeats, the database is worth restarting.  

```promql
jvm_errors_total{host="$host", job="$job"}
```

The JVM graph is followed by *evitaDB errors* graph that displays a counter of evitaDB internal errors. It tracks all
the exceptions that are not caused by inappropriate handling from the client side, but rather by the unexpected situation
in the server implementation. Most of such errors are recoverable ones, but they should be tracked carefully and reported
to the evitaDB team for consultation:

```promql
io_evitadb_errors_total{host="$host", job="$job"}
```

*Base statistics* panel contains overall information about database contents - how many catalogs are loaded, how many
of those catalogs are corrupted (unusable) and how many collections were found inside loaded catalogs. This information
should inform you about the gross size of the database.

<Note type="info">

Most of the graphs in this big picture view are clickable, which brings you a more detailed view or specialized graph
related to a particular metric.

</Note>

```promql
sum(last_over_time(io_evitadb_storage_evita_dbcomposition_changed_catalogs{host="$host", job="$job"}) != 0 or vector(0))
sum(last_over_time(io_evitadb_storage_evita_dbcomposition_changed_corrupted_catalogs{host="$host", job="$job"}) != 0 or vector(0))
sum(last_over_time(io_evitadb_storage_catalog_statistics_entity_collections{host="$host", job="$job"}) != 0 or vector(0))
```

Panel *Actual query throughput* shows the last known throughput of queries per second.

```promql
sum(rate(io_evitadb_query_finished_total{host="$host", job="$job"}[$i]))
```

And the graph *Query latency* next to it displays the time interval within which 95% of all queries to the database are 
processed.

```promql
histogram_quantile(0.95, sum(rate(io_evitadb_query_finished_duration_milliseconds_bucket{host="$host", job="$job"}[$i])) by (le))
```

<Note type="info">

The number is not totally precise because it's calculated from histogram information that contains only a limited number
of discrete thresholds.

</Note>

The panel *Fetched Bytes* summarizes how many Bytes are currently being read from disk. Since evitaDB is keeps all 
the search indexes in memory, fetches represent only those bodies of selected entities that are not trapped in 
the database cache.

```promql
rate(sum(increase(io_evitadb_query_finished_fetched_size_bytes_sum{host="$host", job="$job"}[$i])))
```

Graph *CPU Utilization* tracks how many host machine cores are occupied by evitaDB processing. If your host has 8 CPUs
and evitaDB has 0.34 CPU Utilization, it means that it consumes total of *0.34 * 8 = 2,72%* of the system CPU resources.

```promql
rate(process_cpu_seconds_total{host="$host",job="$job"}[$i])
```

*Memory utilization* graph displays how much of the memory dedicated to evitaDB process was actually used at the moment
of the last scrape. JVM has allocated more memory than this, but this value displays how much memory is actively consumed
by data structures of evitaDB.

```promql
sum(last_over_time(jvm_memory_used_bytes{host="$host",job="$job"}[$i]))
```

Next panel *Tx per second* tells you how many transactions system handles per second (either committed or rolled back).

```promql
sum(rate(io_evitadb_transaction_transaction_finished_total{host="$host", job="$job"}[$i]))
```

It's followed by *Active sessions* panel that tells you how many sessions are active right now.

```promql
sum(last_over_time(io_evitadb_session_closed_active_sessions{host="$host", job="$job"}[$i]))
```

The panel *WAL Bytes written* displays how many data were written by committed transactions to the Write-Ahead-Log 
over the selected period of time.

```promql
sum(increase(io_evitadb_transaction_transaction_appended_to_wal_appended_wal_bytes_total{host="$host", job="$job"}))
```

Finally, the panel *Total size on disk* visualizes a how large portion of disk space is occupied by the evitaDB files.

```promql
sum(last_over_time(io_evitadb_storage_catalog_statistics_occupied_disk_space_bytes{host="$host", job="$job"}) or vector(0) != 0)
```

Last row tracks information from the [liveness](https://evitadb.io/documentation/operate/observe#liveness-probe) and 
[readiness]((https://evitadb.io/documentation/operate/observe#readyness-probe)) probes in more detail. The panel
*Identified health problems* contains time flow visualizing period of times when certain health problems occurred.
Look for the periods with ☠️ instead of ❤️ icon.

```promql
io_evitadb_probe_health_problem{host="$host", job="$job"}
```

And the *External API Status* visualizes the observed availability of each of the database's enabled web APIs. Again
look for periods marked by ❌ instead of ↩️ icon.

```promql
io_evitadb_probe_api_readiness{host="$host", job="$job"}
```

Last panel *Background task execution* collect occurrences of system background tasks executions allowing to detect
excessive number of operations.

```promql
sum(
  increase(io_evitadb_system_background_task_started_total{host="$host", job="$job", catalogName=~"$catalogName|N/A"}[$i])
  -
  increase(io_evitadb_system_background_task_finished_total{host="$host", job="$job", catalogName=~"$catalogName|N/A"}[$i])
) by (taskName)
+
sum(
  (increase(io_evitadb_system_background_task_started_total{host="$host", job="$job", catalogName=~"$catalogName|N/A"}[$i]))
    and
  (
    increase(io_evitadb_system_background_task_started_total{host="$host", job="$job", catalogName=~"$catalogName|N/A"}[$i])
    -
    increase(io_evitadb_system_background_task_finished_total{host="$host", job="$job", catalogName=~"$catalogName|N/A"}[$i])
  ) == 0
) by (taskName)
```

## Java Virtual Machine

TODO
