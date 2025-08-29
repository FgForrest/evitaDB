---
title: Introduction
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

evitaDB helps developers to create fast product catalog applications, which are the heart of every e-commerce site.
Catalogs work with hierarchical structures, facet search, price search, range search, localisation and fulltext.
All these requirements could be solved by general-purpose databases - either relational like [PosgreSQL](https://www.postgresql.org/),
[MySQL](https://www.mysql.com/), no-sql like [Elasticsearch](https://www.elastic.co/), [MongoDB](https://www.mongodb.com/).
But in reality, these tasks are quite complex for all these databases and require a lot of work on the part of 
the application developer. The e-commerce problems can be solved in many ways in these databases, but initial and
often naive implementations happen to be very under-performing when the dataset grows larger, or quickly become hard
to maintain and evolve further.

The developers of evitaDB have been implementing e-commerce stores on various database platforms for several years. We
have experience with multi-page SQL queries, lock wait timeouts, data de-normalisation and other side-effects of 
traditional relational solutions. We have also tasted the dark side of distributed non-SQL databases
which also required multi-page queries and revealed problems with (lack of) transactionality, eventual consistency
and hard-to-understand schema definition and querying. We have always felt that we trade a lot of simplicity for the sake of
acceptable latency, and we aim for a system that is both simple and performant for the majority of e-commerce
use cases. The plug and play device that just works.

That's why we applied for [an EU research grant](https://evitadb.io/project-info), which allowed us to dedicate necessary
time trying to create an alternative to general-purpose databases, that would meet our needs. The [progress
and results of our research](https://evitadb.io/research/introduction) happening between 2019 and 2022 are documented 
in the separate part of this site.

<Note type="warning">

<NoteTitle toggles="false">

##### Use at your own risk and responsibility
</NoteTitle>

In the summer of 2024, we started to release beta versions of evitaDB in regular intervals and deploy it to our own
customers to gain first-hand experience using it ourselves. evitaDB is currently operated in production and works
reliably meeting our expectations regarding performance and stability.

However, evitaDB is currently in beta version and a lot of development is still going on. We plan to finish the full-featured
version in 2026. Until that time, the storage format may change at any time, which may require all existing data to be
dropped and re-indexed from the primary storage. We make our best to provide automatic data migration tools, so hopefully
this will not be necessary, but we cannot guarantee it yet.

**Please do not use evitaDB to store your primary data for the reasons mentioned above.**

</Note>

## Get started

1. [Run evitaDB](get-started/run-evitadb.md)
   1. [Run embedded in you application](use/connectors/java.md)
   2. [Run as service inside Docker](operate/run.md)
2. [Create your first database](get-started/create-first-database.md)
3. [Query our dataset](get-started/query-our-dataset.md)

## Use

1. [Data model](use/data-model.md)
   1. [Data types](use/data-types.md)
   2. [Schema](use/schema.md)
2. **Connectors**
   1. [GraphQL](use/connectors/graphql.md)
   2. [REST](use/connectors/rest.md)
   3. [gRPC](use/connectors/grpc.md)
   4. [Java](use/connectors/java.md)
   5. [C#](use/connectors/c-sharp.md)
3. **API**
   1. [Define schema](use/api/schema-api.md)
   2. [Upsert data](use/api/write-data.md)
   3. [Query data](use/api/query-data.md)
   4. [Write tests](use/api/write-tests.md)
   5. [Troubleshoot](use/api/troubleshoot.md)

## Query

1. [Basics](query/basics.md)
2. **Filtering**
   1. [Behavioral](query/filtering/behavioral.md)
   2. [Comparable](query/filtering/comparable.md)
   3. [Constant](query/filtering/constant.md)
   4. [Hierarchy](query/filtering/hierarchy.md)
   5. [Locale](query/filtering/locale.md)
   6. [Logical](query/filtering/logical.md)
   7. [Price](query/filtering/price.md)
   8. [Range](query/filtering/range.md)
   9. [References](query/filtering/references.md)
   10. [String](query/filtering/string.md)
3. **Ordering**
   1. [Behavioral](query/ordering/behavioral.md)
   2. [Comparable](query/ordering/comparable.md)
   3. [Constant](query/ordering/constant.md)
   4. [Price](query/ordering/price.md)
   5. [Random](query/ordering/random.md)
   6. [Reference](query/ordering/reference.md)
   7. [Segmentation](query/ordering/segment.md)
4. **Requirements**
   1. [Behavioral](query/requirements/behavioral.md)
   2. [Facet](query/requirements/facet.md)
   3. [Fetching](query/requirements/fetching.md)
   4. [Hierarchy](query/requirements/hierarchy.md)
   5. [Histogram](query/requirements/histogram.md)
   6. [Paging](query/requirements/paging.md)
   7. [Price](query/requirements/price.md)
   8. [Telemetry](query/requirements/telemetry.md)

## Operate

1. [Configure](operate/configure.md)
   1. [Setup TLS](operate/tls.md) 
2. [Run](operate/run.md)
3. [Backup & Restore](operate/backup-restore.md)
4. [Observe](operate/observe.md)

## Deep dive

1. [Storage model](deep-dive/storage-model.md)
2. [Bulk vs. incremental indexing](deep-dive/bulk-vs-incremental-indexing.md)
3. [Transactions](deep-dive/transactions.md)
4. [Price for sale calculation](deep-dive/price-for-sale-calculation.md)
5. [Cache](deep-dive/cache.md)
6. [Observe changes](deep-dive/observing.md)

## Solve

1. [Routing](solve/routing.md)
2. [Render category menu](solve/render-category-menu.md)
   1. [Mega-menu](solve/render-category-menu.md#mega-menu)
   2. [Dynamic collapsible menu](solve/render-category-menu.md#dynamic-collapsible-menu)
   3. [Listing sub-categories](solve/render-category-menu.md#listing-sub-categories)
   4. [Hybrid menu](solve/render-category-menu.md#hybrid-menu)
   5. [Hide parts of the category menu](solve/render-category-menu.md#hiding-parts-of-the-category-tree)
3. [Filter products in category](solve/filtering-products-in-category.md)
   1. [With faceted search](solve/filtering-products-in-category.md#faceted-search)
   2. [With price filter](solve/filtering-products-in-category.md#price-filter)
4. [Render referenced brand](solve/render-referenced-brand.md)
   1. [With product listing](solve/render-referenced-brand.md#product-listing)
   2. [With involved categories listing](solve/render-referenced-brand.md#category-listing)
5. [Handle images & binaries](solve/handling-images-binaries.md)
6. [Model price policies](solve/model-price-policies.md)