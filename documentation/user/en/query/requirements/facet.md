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

<dl>
    <dt>argument:enum(COUNTS|IMPACT)</dt>
    <dd>
        optional argument of type <SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetStatisticsDepth.java</SourceClass>
        that allows you to specify the computation depth of the facet summary:

        - **COUNTS**: each facet contains the number of results that match the facet option only 
        - **IMPACT**: each non-selected facet contains the prediction of the number of results that would be returned 
          if the facet option was selected (the impact analysis), this calculation is affected by the require
          constraints changing the default facet calculation behaviour: [conjunction](#facet-conjunction),
          [disjunction](#facet-disjunction), [negation](#facet-negation)
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        optional filter constraint that narrows the facets displayed and calculated in the summary to the ones that
        match the specified filter constraint
    </dd>
    <dt>filterConstraint:filterGroupBy</dt>
    <dd>
        optional filter constraint that narrows the entire facet groups whose facets are displayed and calculated in
        the summary to the ones that belong tothe facet groups matching the filtering constraint
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        optional order constraint that specifies the order of the facet options within each facet group
    </dd>
    <dt>orderConstraint:orderGroupBy</dt>
    <dd>
        optional order constraint that specifies the order of the facet groups
    </dd>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity body; the `entityFetch` 
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch` 
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity group body; the `entityGroupFetch` 
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch` 
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
</dl>

The requirement triggers the calculation of the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/FacetSummary.java</SourceClass>
that contains the facet summary calculation. The calculated facet summary will contain all the entity references that
are marked as `faceted` in the [entity schema](../../use/schema.md). The facet summary might be further altered by
[facet summary of reference](#facet-summary-of-reference) constraint that allows you to override the general facet
summary behaviour stated in generic facet summary require constraint.

<Note type="warning">

The `faceted` property affects the size that are held in memory of the indexes and the scope / complexity of the general
facet summary (i.e. summary generated by `facetSummary` requirement). It is recommended to mark only the references
that are used for faceted filtering as `faceted` to keep the indexes small and the facet summary calculation fast and
simple in the user interface. The combinatory complexity of the facet summary is quite high for large datasets and
you may be forced to optimize it by narrowing the summary using [filtering](#filtering-facet-summary) facility or 
selecting only [a few references](#facet-summary-of-reference) for the summary.

</Note>

### Facet summary structure

The facet summary contains only entities, that are referenced by the entities returned in the current query response and
is organized in three-tier structure:

- **[reference](#1st-tier-reference)**: the top-level contains the names of the references that are marked as `faceted` 
  in the [entity schema](../../use/schema.md)
- **[facet group](#2nd-tier-facet-group)**: the second-level contains the groups that are specified in 
  the returned [entity's references](../../use/data-model.md#references)
- **[facet](#3rd-tier-facet)**: the third-level contains the facet options that represents entities of the returned 
  [entity's references](../../use/data-model.md#references)

#### 1st tier: reference

For each entity's reference that is marked as `faceted` in the facet summary there is a separate data container 
for each [2nd tier facet groups](#2nd-tier-facet-group). If the facets for this reference are not organized in groups
(the reference lacks the group information), the facet summary will contain only one facet group called "non-grouped
facets".

#### 2nd tier: facet group

Facet group lists all the [facet options](#3rd-tier-facet) that are available for the given group and reference 
combination. It also contains `count` of all the entities in current query result that match at least one facet in the 
group / reference. Optionally it can contain the body of the group entity if the [`entityGroupFetch`](#entity-group-fetch)
requirement is specified.

#### 3rd tier: facet

Facet contains the statistics of the particular facet option:

<dl>
  <dt>count</dt>
  <dd>
    It represents the number of all the entities in current query result that match this facet (has reference to entity
    with this primary key).
  </dd>
  <dt>requested</dt>
  <dd>
    `TRUE` in case this facet is requested in [`user filter`](../filtering/behavioral.md#user-filter) container of this
    query, `FALSE` otherwise (this property allows you to easily mark the facet checkbox as "checked" in the user
    interface)
  </dd>
</dl>

And optionally the body of the facet (referenced) entity if the [`entityFetch`](#entity-fetch) requirement is specified.
When the `IMPACT` statistics depth is requested in the facet summary the statistics will also contain the `requestImpact`
calculation that contains following data:

<dl>
  <dt>matchCount</dt>
  <dd>
    It represents the number of all the entities that would match a new query derived from current query should this
    particular facet option be selected (has reference to entity with this primary key). Current query is left intact
    including the [`user filter`](../filtering/behavioral.md#user-filter) part, but a new facet request is virtually 
    added to user filter to calculate the hypothetical impact of the facet option selection.
  </dd>
  <dt>difference</dt>
  <dd>
    It represents the difference between the `matchCount` (hypothetical result should this facet be selected) and 
    the current count of returned entities. It represents the scope of the impact on the current result. Can be either
    positive (the facet option would extend the current result) or negative (the facet option would limit the current
    result). The difference might be `0` if the facet option doesn't change the current result.
  </dd>
  <dt>hasSense</dt>
  <dd>
    `TRUE` in case the facet option combined with current query still produces some results (`matchCount > 0`), `FALSE` 
    otherwise. This property allows you to easily mark the facet checkbox as "disabled" in the user interface.
  </dd>
</dl>

The facet summary is always computed as a side result of main entity query and respects all filtering constraints placed
upon the queried entities. To demonstrate the facet summary calculation we will use the following example:

<SourceCodeTabs langSpecificTabOnly>

[Facet summary calculation for products in "accessories" category](/documentation/user/en/query/requirements/examples/facet/facet-summary-simple.evitaql)

</SourceCodeTabs>

To better understand the data in the facet calculation let's extend the query a little bit and fetch additional data
using [`entityFetch`](#entity-fetch) and [`entityGroupFetch`](#entity-group-fetch) requirement. To make the example more
realistic, let's fetch for each entity the localized name in English localization:

<SourceCodeTabs langSpecificTabOnly>

[Facet summary calculation for products in "accessories" category](/documentation/user/en/query/requirements/examples/facet/facet-summary.evitaql)

</SourceCodeTabs>

If you want to get more familiar with the facet summary calculation, you can try to play with the query and see how it
affects the visualization tab you can find in our [evitaLab](https://evita.ai/lab) console:

![Facet summary visualization in evitaLab console](assets/facet_visualization.png)

The visualization is organized the same as the facet summary itself. At the top level you see the references that are
marked by icon <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><title>link-variant</title><path d="M10.59,13.41C11,13.8 11,14.44 10.59,14.83C10.2,15.22 9.56,15.22 9.17,14.83C7.22,12.88 7.22,9.71 9.17,7.76V7.76L12.71,4.22C14.66,2.27 17.83,2.27 19.78,4.22C21.73,6.17 21.73,9.34 19.78,11.29L18.29,12.78C18.3,11.96 18.17,11.14 17.89,10.36L18.36,9.88C19.54,8.71 19.54,6.81 18.36,5.64C17.19,4.46 15.29,4.46 14.12,5.64L10.59,9.17C9.41,10.34 9.41,12.24 10.59,13.41M13.41,9.17C13.8,8.78 14.44,8.78 14.83,9.17C16.78,11.12 16.78,14.29 14.83,16.24V16.24L11.29,19.78C9.34,21.73 6.17,21.73 4.22,19.78C2.27,17.83 2.27,14.66 4.22,12.71L5.71,11.22C5.7,12.04 5.83,12.86 6.11,13.65L5.64,14.12C4.46,15.29 4.46,17.19 5.64,18.36C6.81,19.54 8.71,19.54 9.88,18.36L13.41,14.83C14.59,13.66 14.59,11.76 13.41,10.59C13,10.2 13,9.56 13.41,9.17Z" /></svg>. Under them there are groups discovered inside those reference types, which are marked with icon
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><title>format-list-group</title><path d="M5 5V19H7V21H3V3H7V5H5M20 7H7V9H20V7M20 11H7V13H20V11M20 15H7V17H20V15Z" /></svg>, and finally under the groups there are facet options themselves. If you move your mouse 
cursor over the tag next to facet, you will see the description of the calculated numbers, but let's explain it here 
as well:

- <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><title>set-all</title><path d="M9,5C10.04,5 11.06,5.24 12,5.68C12.94,5.24 13.96,5 15,5A7,7 0 0,1 22,12A7,7 0 0,1 15,19C13.96,19 12.94,18.76 12,18.32C11.06,18.76 10.04,19 9,19A7,7 0 0,1 2,12A7,7 0 0,1 9,5M8.5,12C8.5,13.87 9.29,15.56 10.56,16.75L11.56,16.29C10.31,15.29 9.5,13.74 9.5,12C9.5,10.26 10.31,8.71 11.56,7.71L10.56,7.25C9.29,8.44 8.5,10.13 8.5,12M15.5,12C15.5,10.13 14.71,8.44 13.44,7.25L12.44,7.71C13.69,8.71 14.5,10.26 14.5,12C14.5,13.74 13.69,15.29 12.44,16.29L13.44,16.75C14.71,15.56 15.5,13.87 15.5,12Z" /></svg> 
- <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><title>set-right</title><path d="M15,19C13.96,19 12.94,18.76 12,18.32C11.06,18.76 10.04,19 9,19A7,7 0 0,1 2,12A7,7 0 0,1 9,5C10.04,5 11.06,5.24 12,5.68C12.94,5.24 13.96,5 15,5A7,7 0 0,1 22,12A7,7 0 0,1 15,19M9,17L10,16.89C8.72,15.59 8,13.83 8,12C8,10.17 8.72,8.41 10,7.1L9,7A5,5 0 0,0 4,12A5,5 0 0,0 9,17M12,16C13.26,15.05 14,13.57 14,12C14,10.43 13.26,8.95 12,8C10.74,8.95 10,10.43 10,12C10,13.57 10.74,15.05 12,16Z" /></svg>
- <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><title>counter</title><path d="M4,4H20A2,2 0 0,1 22,6V18A2,2 0 0,1 20,20H4A2,2 0 0,1 2,18V6A2,2 0 0,1 4,4M4,6V18H11V6H4M20,18V6H18.76C19,6.54 18.95,7.07 18.95,7.13C18.88,7.8 18.41,8.5 18.24,8.75L15.91,11.3L19.23,11.28L19.24,12.5L14.04,12.47L14,11.47C14,11.47 17.05,8.24 17.2,7.95C17.34,7.67 17.91,6 16.5,6C15.27,6.05 15.41,7.3 15.41,7.3L13.87,7.31C13.87,7.31 13.88,6.65 14.25,6H13V18H15.58L15.57,17.14L16.54,17.13C16.54,17.13 17.45,16.97 17.46,16.08C17.5,15.08 16.65,15.08 16.5,15.08C16.37,15.08 15.43,15.13 15.43,15.95H13.91C13.91,15.95 13.95,13.89 16.5,13.89C19.1,13.89 18.96,15.91 18.96,15.91C18.96,15.91 19,17.16 17.85,17.63L18.37,18H20M8.92,16H7.42V10.2L5.62,10.76V9.53L8.76,8.41H8.92V16Z" /></svg>
- 

<Note type="info">

<NoteTitle toggles="true">

##### The result of facet summary in "accessories" category

</NoteTitle>

The query returns list of "active" products in "accessories" category and in the extra results index it also contains
the facet summary calculation:

<MDInclude sourceVariable="data.extraResults.facetSummary">[The result of facet summary in "accessories" category](/documentation/user/en/query/requirements/examples/facet/facet-summary.json.md)</MDInclude>

</Note>

### Filtering facet summary
### Ordering facet summary

## Facet summary of reference
## Entity group fetch
## Entity fetch
## Facet conjunction
## Facet disjunction
## Facet negation