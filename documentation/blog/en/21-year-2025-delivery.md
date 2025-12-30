---
title: What 2025 Brought Us
perex: |
  The end of the year is a perfect moment to look back at how far evitaDB has come. The key
  theme of this year was stability and adapting to real-world production needs, which we see in
  greater numbers every month.
date: '31.12.2025'
author: 'Jan Novotný'
motive: assets/images/21-year-2025-delivery.png
proofreading: 'done'
draft: true
---

I’ll break the recap into a few thematic topics:

1. Improvements and new features:
   1. [evitaDB core](#core-evolution-of-evitadb)
   2. [evitaLab](#progress-in-evitalab)
   3. [infrastructure and architectural changes](#infrastructure-and-architectural-changes)
2. [Optimizations and stability](#optimizations-and-stability)
3. [Looking ahead to 2026](#looking-ahead-to-2026)

This year we closed 92 tickets and released 8 major database versions. Although we introduced
fundamental changes in how data is stored on disk, seamless upgrades from 2024 versions are still
possible thanks to automatic data migrations. We also have automated tests that verify the
migration process.

## Core evolution of evitaDB

### Multiple references to the same entity

This feature represented the largest core overhaul this year. It shows in full how breaking one of
the key assumptions impacts implementation complexity. The original data model assumed that a
particular entity could reference another entity only once within a single named reference. If the
need arose to have multiple references to the same entity within one reference, a new reference
type would be created. A concrete example sheds more light on this mechanic:

> Consider a product (entity *Product*) named *Christmas tree*. This product has several photos
> represented by entity *Image*. One of the photos is used as the main product image and typically
> appears in product listings. The other photos are used in the product’s gallery. The problem
> arises when we want the main image to also be part of the gallery.

The original design expected the *Product* entity to have two reference types: *mainImage* and
*galleryImages*. That would allow the same photo to be referenced twice within the same product,
but under different reference types. In practice, however, this approach proved inconvenient,
primarily because reference names are part of field names in, for example, GraphQL or OpenAPI
schemas. In real projects, these names are often “configurable” and vary across client projects,
which means queries—typically templated—need to be updated as well. It’s therefore much more
practical to treat these “discriminators” as reference attributes rather than as separate reference
types. That moves them from field names into filters and input parameters where dynamism is
already expected.

The original decision wasn’t random—it enabled a number of optimizations and a far simpler
implementation overall. Dropping this assumption required extensive changes across the database
architecture, including indexing, on-disk storage, and query processing. We had to introduce new
data structures that distinguish references based on a unique combination of “reference
attributes” that form discriminators (at the reference level you can’t rely on any other simple
primary key).

One outcome of this change is a very intuitive and practical API for working with multiple
references in queries:

```graphql
query {
  queryProduct(
    filterBy: {
      attributeCodeInSet: ["lenovo-yoga-tab-11", "samsung-galaxy-tab-s8-12-4-2022"]
    }
  ) {
    recordPage {
      data {
        mainMotive: media(filterBy: {attributeGalleryEquals: "hlavni-motiv"}) {
          ...media
        }
        gallery: media(filterBy: {attributeGalleryEquals: "galerie"}) {
          ...media
        }
      }
    }
  }
}

fragment media on ProductMediaReference {
  referencedEntity {
    attributes {
      fileName
      fileSize
      mediaUrl
    }
  }
}
```

A crucial part of the implementation—besides adding a ton of new tests—was a fuzzy test that
simulates random database operations and then verifies data consistency. Tuning all the edge cases
that are practically impossible to cover with standard tests took several weeks of focused work.

### Controlling traversal across hierarchical references

E‑commerce systems typically operate within some form of hierarchy (product categories,
organization structures, etc.). evitaDB allows you to define hierarchical entities and offers a set
of special operators for filtering and sorting data within these hierarchical structures. With
one‑to‑many references (0..N), however, a question arises: in what order should these references be
traversed, and should it be breadth‑first (i.e., all direct children first, then their children,
etc.) or depth‑first (i.e., deepest level first, then the level above, and so on)? You can now
explicitly control all these modes using new operators in the query language and keep everything
fully under control. You can read more in [this article](https://evitadb.io/blog/17-one-to-many-references).

### Integrating hierarchical search into the facet summary

Another nice addition related to hierarchies is integrating hierarchical search into the facet
filter. Rather than a lengthy explanation, a practical example on **Ikea.com** is more telling:

<p>
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/blog-21-hierarchy-in-facet-filter.webm" type="video/mp4"/>
        Your browser does not support the video tag.
    </video>
  </p>

Within the facet filter’s data structure, evitaDB can now return a computed hierarchy where
entities matching the given criteria were found. Each node in this structure contains an aggregate
count of matching entities that correctly takes into account multiple placements of the same entity
within the hierarchy (i.e., the entity is counted only once on each level in the totals).

### Fetching counts of referenced entities without loading their contents

We’re slowly getting to smaller, yet still useful improvements. One of them is the ability to fetch
the number of references to other entities without having to load the referenced entities
themselves. This saves memory, CPU cycles, and bandwidth, and it’s handy, for example, to show the
number of product reviews without loading the reviews themselves. A common UI scenario is just to
check for the existence of at least one reference of a certain type (e.g., whether a product has at
least one review) and then render or hide the respective UI element (like a “Show reviews” button).

### New missing operator — when at least one child matches

Until now, when traversing hierarchical structures, it was possible to limit traversal to parents
that match a condition. For example: “traverse all categories that have the ‘active’ status.” In
practice, situations arise that can be solved elegantly with a new operator that allows traversal
through a parent if at least one of its children matches a given condition. A practical example is
rendering a category tree where certain specific subcategories carry a special label. Managing
labels on leaf nodes is simple for users, while the application can still display a consistent
category tree from the root to the specifically labeled categories. The new operator is
[documented in the respective section](https://evitadb.io/documentation/query/filtering/hierarchy?lang=evitaql#any-having).

### Default settings for accompanying price

Another small improvement is the ability to define default values for computing accompanying prices
(for example, reference/usual prices) globally for the entire query. Prices are computed in several
places within a single query—besides the prices on the main entity (e.g., a product), they are also
computed on associated entities (alternative products, auxiliary services, etc.). For the selling
price, the price lists, currency, and other parameters are derived from the main filter condition,
but for accompanying prices these parameters had to be specified at every usage site. Now you can
define them once at the query level and avoid repeating the same values. The new syntax is described
in the [pricing documentation](https://evitadb.io/documentation/query/requirements/price?lang=evitaql#default-accompanying-price-lists).

## Progress in evitaLab

### Traffic recording and analysis

At the beginning of the year, we enabled recording of incoming queries (with optional sampling) in
the database core. The recording is asynchronous and implemented so that it does not affect
latency or throughput. In the evitaLab console UI we exposed a section for analyzing these queries.
You can browse individual recorded queries, filter them by various criteria (time, query type,
processing time, number of returned records, etc.), and analyze their structure. Any query can be
immediately executed from the console to inspect its results or execution plan. This feature is very
useful for tuning query performance and identifying potential query issues that may negatively impact
database performance. Each query is recorded both in its original form (e.g., GraphQL) and in its
translated form (evitaQL), enabling detailed analysis and optimization at multiple levels. Read more
in the [related blog post](https://evitadb.io/blog/16-traffic-recording).

### UI refresh and bug fixes

In summer, evitaLab’s UI received a major refresh that brought a host of minor bug fixes and paid
down technical debt caused by some new evitaDB core features not being supported in the console.
The schema view now correctly visualizes cardinalities, multiple references, and index visualization
across different scopes. The entity grid now visualizes per-entity scope and you can filter by it.
The syntax highlighter and auto‑completion recognize the new operators and functions in the query
language as well.

### New desktop application

We also introduced a brand‑new evitaLab desktop application that allows convenient work with the
database (or multiple different databases) directly from the desktop. The app is available for
Windows, macOS, and Linux. It can communicate with databases in various versions and supports
automatic updates of the local driver for a specific database connection. You can read more in the
[related blog post](https://evitadb.io/blog/18-introducing-evitalab-desktop).

### Audit log

The last big addition is exposing audit information directly in the evitaLab console. The
information is sourced from the database’s Write‑Ahead Log and is therefore exact, but the audit
depth matches the WAL retention period configured for the given database instance. In the UI you can
easily browse the complete history of transactions, filter them by type (data/schema changes),
entity type, or a specific entity or its part (attributes, prices, references). For each transaction
you can display detailed information about the changes made. In the entity grid, you can open the
change history of a specific entity, attribute, associated data, reference, or price, and browse the
individual versions over time.

## Infrastructure and architectural changes

### Change Data Capture

In the second half of the year we started preparing for clustered deployments and the related
infrastructure and architecture changes in the database. The first step was introducing a Change
Data Capture (CDC) mechanism that allows tracking data changes in real time. This mechanism is key
for replicating data across cluster nodes and for integrating with other systems that need to be
informed about data changes. You can read more in the
[related blog post](https://evitadb.io/blog/20-change-data-capture).

### Engine‑level Write‑Ahead Log

Related to this, we introduced a new Write‑Ahead Log (WAL) at the whole‑engine level that allows
recording and replicating system operations such as creating new catalogs, renaming or deleting them
and other operations that are not directly tied to a specific catalog’s data (for which the original
WAL is intended). This new WAL is key to replicating these system operations across the cluster and
ensuring data consistency among nodes.

### Full catalog backups

Alongside the existing backup types (point‑in‑time and snapshot), we added the ability to perform
full backups of an entire catalog. A full backup works at the binary level and contains all catalog
data. After restoring from a full backup, the catalog is identical to the original and you can
perform point‑in‑time backups/restores on it just like on the original. In evitaLab you can now
perform all backup and restore types directly from the UI.

## Optimizations and stability

We made big strides in 2025 in the areas of optimizations and stability. Besides fixing many minor
bugs that surfaced over the year in real projects, we performed several key optimizations that
significantly improved performance and stability in production.

### Asynchronous operations

Most operations in evitaDB are very fast, but some—such as committing a large transaction, switching
a catalog from WARM‑UP to transactional mode, or certain management operations—can take several
seconds. Their duration is hard to predict and influenced by many factors, making it impractical to
set sensible client‑side timeouts. For this reason, we introduced an asynchronous mode for most of
these operations. The client immediately receives an acknowledgment that the request has been
accepted, and the processing continues in the background. In the Java client this means returning a
`Future` object that lets you chain completion with further processing or “park” a thread until the
operation is finished. This approach significantly improves the responsiveness of applications
communicating with the database, enables better resource utilization, and increases stability by
eliminating timeout issues. An example of this approach is outlined in the article describing
[asynchronous transaction processing](https://evitadb.io/blog/19-transaction-processing).

### On‑disk compression

Another significant optimization is introducing on‑disk data compression, which dramatically reduces
storage requirements while also improving read performance. Compression is implemented at the level
of individual data structures and is transparent to users. The result is less data to read and write
to disk, which lowers I/O latency and reduces maintenance overhead. For now, we use the standard
Deflate (Zlib) algorithm, but we plan to add other compression options that are better optimized for
the specific data structures used in evitaDB.

### Index optimizations

Analysis of real projects revealed that evitaDB was maintaining a number of secondary indexes that
applications did not actually use. This was also due to the schema definition not allowing a more
granular setup of index creation. We therefore introduced the ability to explicitly define how broad
an index should be for various entity references, reducing both the overhead of maintaining them and
memory consumption. On some of our projects, this optimization yielded up to a 50% reduction in
memory usage while preserving full query functionality.

### Option to disable fSync for tests

Another small improvement is the ability to disable fSync during transaction commits. This setting
is intended strictly for testing scenarios where you need to maximize write throughput at the
expense of durability in the event of a failure. It should never be used in production because it
could lead to data loss during unexpected outages. However, in automated test environments it can
significantly speed up test execution. On our projects, we observed up to a 40% speed‑up of
automated tests that repeatedly created new catalogs, inserted data, and then deleted them again.

## Looking ahead to 2026

Over the past year we released 8 versions of evitaDB and closed 92 tickets. Development is no
longer as rapid as in previous years because we also devote time to consulting and supporting real
projects, which is what funds us and evitaDB’s development. This trend will continue in the coming
years because our primary goal is a stable and reliable database that serves real projects well.

Next year we’d like to finish preparations for cluster deployments and gain experience with operating
in a single‑writer, multiple‑reader mode in real projects—with horizontal scaling, rolling updates,
and other practical scenarios related to clustered operation. Another major challenge will be adding
full‑text and semantic search, which is critical in e‑commerce today. We have lots of plans for 2026
and it’s clear priorities will keep changing and new “urgent” challenges we can’t foresee yet will
emerge. We look forward to them and believe that in the next year we’ll bring lots of useful
improvements and features that help our developer‑users build even better applications on top of
evitaDB.
