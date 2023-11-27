```evitaql-syntax
facetSummaryOfReference(
    argument:string!,
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
    <dt>argument:string!</dt>
    <dd>
      mandatory argument specifying the name of the [reference](../../use/schema.md#reference) that is requested by this
      constraint, the reference must be marked as `faceted` in the [entity schema](../../use/schema.md)
    </dd>
    <dt>argument:enum(COUNTS|IMPACT)</dt>
    <dd>
        <p>**Default:** `COUNTS`</p>
        <p>optional argument of type <SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetStatisticsDepth.java</SourceClass>
        that allows you to specify the computation depth of the facet summary:</p>

        <p>
        - **COUNTS**: each facet contains the number of results that match the facet option only 
        - **IMPACT**: each non-selected facet contains the prediction of the number of results that would be returned 
            if the facet option were selected (the impact analysis), this calculation is affected by the required 
            constraints that change the default facet calculation behavior: [conjunction](#facet-groups-conjunction), 
            [disjunction](#facet-groups-disjunction), [negation](#facet-groups-negation).
        </p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        optional filter constraint that limits the facets displayed and calculated in the summary to those that match
        the specified filter constraint.
    </dd>
    <dt>filterConstraint:filterGroupBy</dt>
    <dd>
        optional filter constraint that restricts the entire facet group whose facets are displayed and calculated in 
        the summary to those that belong to the facet group matching the filter constraint.
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        optional order constraint that specifies the order of the facet options within each facet group
    </dd>
    <dt>orderConstraint:orderGroupBy</dt>
    <dd>
        optional order constraint that specifies the order of the facet groups
    </dd>
    <dt>requireConstraint:entityFetch</dt>
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