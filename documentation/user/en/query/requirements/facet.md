---
title: Facet filtering
date: '29.10.2023'
perex: |
  Faceted filtering, also known as parameterized filtering, is a user interface feature that allows users to refine 
  search results by applying multiple filters based on various properties or "facets" like category, parameter, or 
  brand. Users can toggle these filters on or off to drill down into a data set interactively, essentially performing 
  real-time complex queries without technical knowledge. The benefits are twofold: First, it improves user experience 
  by enabling more targeted and efficient searching. Second, it can increase conversion rates for e-commerce sites by
  helping users quickly find and purchase products that meet their specific criteria.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<img src="assets/facet_filtering.png" style="float: right"/>

The key success factor of faceted search is to help users to avoid situation when their filter combination returns no
results. It works the best if we gradually limit the facet options that doesn't make sense with already selected ones
and also provide accurate, in-place and real-time feedback about the number of results that will extend or limit
the current selection if other facet is selected.

Facets are usually displayed as a list of checkboxes, radio buttons, dropdowns or sliders and are organized in groups.
The options within a group usually extend current selection (logical conjunction) and the groups are usually combined
with logical disjunction. Some of the facets can be negated (logical negation) to exclude the results that match the
facet option.

Facets with high cardinality are sometimes displayed in the form of a search box or interval slider (sometime with
a [histogram](histogram.md) of the distribution of the values) to allow users to specify the exact value or range of
values they are looking for.

evitaDB supports all the above-mentioned forms of facet search using operators documented in this and 
[histogram](histogram.md) chapter.

## Facet summary

```evitaql-syntax
facetSummary(
    argument:enum(COUNTS|IMPACT),
    filterConstraint:filterBy,   
    filterConstraint:filterGroupBy,   
    orderConstraint:orderBy,   
    orderConstraint:orderGroupBy,
    requireConstraint:entityFetch,   
    requireConstraint:entityGroupFetch   
)
```

### Filtering facet summary
### Ordering facet summary

## Facet summary of reference
## Entity group fetch
## Entity fetch
## Facet conjunction
## Facet disjunction
## Facet negation