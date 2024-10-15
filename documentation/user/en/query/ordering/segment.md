---
title: Segmentation
perex: |
  Segmentation allows different parts of the search result to be ordered differently. Some e-commerce sites prefer to 
  show new products first in the default view, while others might use top picks for the user based on their preferences 
  (top low cost, top high quality, golden middle, etc.). This is where segmentation comes in.
date: '15.10.2024'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

Without segmentation support, the client would have to run multiple queries and merge the results on the client side, 
with each additional query excluding the results of the previous one. This is not only inefficient, but also prone to 
error. With segmentation, developers can simply define rules for ordering different segments, limit the size of each 
segment, and let the server do the heavy lifting.

### Segments

```evitaql-syntax
segments(
    requireConstraint:segment+   
)
```

<dl>
    <dt>requireConstraint:segment+</dt>
    <dd>
        one or more constraints that specify rules for each segment
    </dd>
</dl>

Segments constraint container allows you to define multiple segments with limited size and different ordering rules.
Entities listed in previous segments are excluded from the next segments. Each segment lists all entities that provide 
data for a particular order (see Note for more details) until the limit is reached. The order of the segments is
important because it determines the order of the segments in the final result.

<Note type="info">

<NoteTitle toggles="false">

##### What is meant by "provide data for specific ordering"?

</NoteTitle>

If you sort by data that may not be present in all entities, such as attribute, the entities that do not have 
the attribute for sorting are automatically excluded from the segment. It's an implicit filtering for each of
the segments that doesn't need to be explicitly specified in the [`segment`](#segment) constraint.

</Note>

Each segment allows you to define an additional filtering constraint that is applied to the query result to select only 
a subset of entities that could be included in that particular segment.

Let's look at an example. Let's say we want to show first two newly added products, then the top-selling product with 
price over 500€, then the top-selling product with price under 500€, then the rest of the products that are currently 
in stock, and finally the rest of the products that we need to order from our suppliers. We can define the segments as 
follows:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Segmented ordering in practice](/documentation/user/en/query/requirements/examples/segment/segments.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of segmented ordering in practice

</NoteTitle>

As you can see, the first two positions are occupied by the newly added products. The third position is occupied by 
the top-selling product with a price over 500€. The fourth position is occupied by the best-selling product with a price
below 500€. The fifth and subsequent positions are occupied by products that are currently in stock. If we run out of 
products in stock, the remaining positions are occupied by the products we need to order from our suppliers.

<LS to="e,j,c">

<MDInclude>[The result of segmented ordering in practice](/documentation/user/en/query/requirements/examples/segment/segments.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude>[The result of segmented ordering in practice](/documentation/user/en/query/requirements/examples/segment/segments.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude>[The result of segmented ordering in practice](/documentation/user/en/query/requirements/examples/segment/segments.rest.json.md)</MDInclude>

</LS>

</Note>

### Segment

```evitaql-syntax
segment(
    filterConstraint:entityHaving?,
    orderConstraint:orderBy,
    requireConstraint:limit?
)
```

<dl>
    <dt>filterConstraint:entityHaving?</dt>
    <dd>
        an optional filtering constraint that takes the query result and applies additional filtering to it to select
        only the entities that could be included in this particular segment
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        an ordering constraint that specifies how the entities in this segment should be ordered
    </dd>
    <dt>requireConstraint:limit?</dt>
    <dd>
        an optional constraint that specifies the maximum number of entities that should be included in this segment
    </dd>
</dl>

The `setment` requirement specifies a single rule for segmentation of the query result. Detailed usage is documented in
the [segments constraint](#segments) chapter.