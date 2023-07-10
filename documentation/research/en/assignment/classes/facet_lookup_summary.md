---
title: Facet lookup summary
perex: |
    Facet lookup summary is a data structure containing information on facet groups and facets in a given 
    hierarchy section or entity set. It outlines the object structure, including FacetGroupStatistics and 
    FacetStatistics, and provides details on their respective components and the Impact section, which contains 
    projected entity statistics for modified facet requirements.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

The facet lookup summary contains list of <Term location="docs/research/en/assignment/index.md" name="facet group">facet groups</Term> and
<Term location="docs/research/en/assignment/index.md" name="facet">facets</Term> inside them, that are present on entities in the current hierarchy
section or the entire entity set. The structure of the object is as follows:

- **[List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html) of [FacetGroupStatistics](#facet-group-statistics)**

## Facet group statistics

contains:

- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** facet group id
- **[List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html) of [FacetStatistics](#facet-statistics)**
- **[Impact](#impact)** the impact on the query result if no facet in this group is requested (empty if no facet of this group
is requested by the current query)

## Facet statistics

contains:

- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** facet id
- **[boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** requested (TRUE if facet
presence is currently requested in search query)
- **[Impact](#impact)** the impact on query result if this facet is requested as well (empty if facet is requested by
the current query)

## Impact

contains statistics of the entities that would be returned by a query with modified facet requirements.

- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** projected number of entities in result
- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** projected number of added entities
in result in comparison to current result
- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** projected number of removed entities
from result in comparison to current result
- **[boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** selection has sense - TRUE
if there is at least one entity still present in the result if the query is altered by modified facet requirements
