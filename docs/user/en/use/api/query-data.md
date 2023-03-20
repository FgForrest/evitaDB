---
title: Query data
perex:
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

**Work in progress**

This article will contain description of the data API regarding entity querying. It should not contain information
about query language, which is described elsewhere.
Part of this chapter can reuse information from:

- [data fetching](https://evitadb.io/research/assignment/index#data-fetching)
- [query API](https://evitadb.io/research/assignment/querying/query_api)

... and will refer to [query language](../query/basics.md)

## Lazy-loading

(if you need a thorough comparison that compares all model data, you need to take advantage of
the `differsFrom` method in the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ContentComparator.java</SourceClass>
interface).