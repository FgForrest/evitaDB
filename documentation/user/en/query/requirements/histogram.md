---
title: Histogram
date: '7.11.2023'
perex: |
    Histograms serve a pivotal role in e-commerce parametrized filtering by visually representing the distribution of
    product attributes, enabling customers to adjust their search criteria efficiently. They facilitate a more
    interactive and precise filtering experience, allowing users to modify the range of properties like price or size
    based on actual item availability.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

There are actually only a few use cases in e-commerce websites where histograms are used. The most common is the price
histogram, which is used to filter products by price. You can see an example of such a histogram on the Booking.com
website:

![Booking.com price histogram filter](assets/price-histogram.png "Booking.com price histogram filter")

It's a shame that the histogram isn't used more often, because it's a very useful tool for gaining insight into
the distribution of product attributes with high cardinality values such as weight, height, width and so on.

The histogram data structure is optimized for frontend rendering. It contains the following fields:

- **`min`** - the minimum value of the attribute in the current filter context
- **`max`** - the maximum value of the attribute in the current filter context
- **`overallCount`** - the number of elements whose attribute value falls into any of the buckets (it's basically a sum of all bucket occurrences)
- **`buckets`** - an *sorted* array of buckets, each of which contains the following fields:
  - **`threshold`** - the minimum value of the attribute in the bucket, the maximum value is the threshold of the next bucket (or `max` for the last bucket)
  - **`occurrences`** - the number of elements whose attribute value falls into the bucket
  - **`relativeFrequency`** - a value used for visualizing bucket height in UI:
    - For **standard histograms**: percentage of total occurrences (0-100), calculated as `(occurrences / overallCount) * 100`
    - For **equalized histograms**: value density calculated as `totalRange / bucketWidth`, where higher values indicate denser data concentration (values packed into narrower bucket range)
  - **`requested`**:
    - contains `true` if the query didn't contain any [attributeBetween](../filtering/comparable.md#attribute-between)
      or [priceBetween](../filtering/price.md#price-between) constraints
    - contains `true` if the query contained [attributeBetween](../filtering/comparable.md#attribute-between)
      or [priceBetween](../filtering/price.md#price-between) constraint for particular attribute / price
      and the bucket threshold lies within the range (inclusive) of the constraint
    - contains `false` otherwise

## Attribute histogram

<LS to="e,j,r,c">

```evitaql-syntax
attributeHistogram(
    argument:int!,
    argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED),
    argument:string+
)
```

<dl>
    <dt>argument:int!</dt>
    <dd>
        the number of columns (buckets) in the histogram; number should be chosen so that the histogram fits well
        into the available space on the screen
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)</dt>
    <dd>
        The behavior of the histogram calculation:
        <ul>
            <li><strong>STANDARD</strong> (default): Returns exactly the requested number of buckets with equal-width intervals across the value range.</li>
            <li><strong>OPTIMIZED</strong>: Returns fewer buckets when data is sparse to avoid large gaps (empty buckets).</li>
            <li><strong>EQUALIZED</strong>: Returns exactly the requested number of buckets, but positions bucket boundaries based on cumulative frequency distribution so each bucket covers approximately equal portion of total records. This provides better user experience when data is heavily skewed.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Combines EQUALIZED bucketing with optimization to reduce empty buckets.</li>
        </ul>
    </dd>
    <dt>argument:string+</dt>
    <dd>
        one or more names of the [entity attribute](../../use/schema.md#attributes) whose values will be used to generate
        the histograms
    </dd>
</dl>

</LS>

The <LS to="e,j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/AttributeHistogram.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/AttributeHistogram.cs</SourceClass></LS>
<LS to="g,r">attribute histogram</LS>
can be computed from any [filterable attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
whose type is numeric. The histogram is computed only from the attributes of elements that match the current mandatory
part of the filter. The interval related constraints - i.e. [`attributeBetween`](../filtering/comparable.md#attribute-between)
and [`priceBetween`](../filtering/price.md#price-between) in the [`userFilter`](../filtering/behavioral.md#user-filter)
part are excluded for the sake of histogram calculation. If this weren't the case, the user narrowing the filtered range
based on the histogram results would be driven into a narrower and narrower range and eventually into a dead end.

To demonstrate the use of the histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Attribute histogram over `width` and `height` attributes](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql)

</SourceCodeTabs>

The simplified result looks like this:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[The result of `width` and `height` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### The result of `width` and `height` attribute histogram in JSON format

</NoteTitle>

The histogram result in JSON format is a bit more verbose, but it's still quite readable:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.AttributeHistogram">[The result of `width` and `height` attribute histogram in JSON format](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.attributeHistogram">[The result of `width` and `height` attribute histogram in JSON format](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.attributeHistogram">[The result of `width` and `height` attribute histogram in JSON format](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.rest.json.md)</MDInclude>

</LS>

</Note>

### Attribute histogram contents optimization

During user testing, we found that histograms with scarce data are not very useful. Besides the fact that they don't
look good, they are often harder to manipulate with the widget that controls the histogram and tries to stick to
the bucket thresholds. Therefore, we have introduced a new histogram calculation mode - `OPTIMIZED`. In this mode,
the histogram calculation algorithm tries to reduce the number of buckets when the data is sparse and there would be
large gaps (empty buckets) between buckets. This results in more compact histograms that provide a better user
experience.

To demonstrate the optimization of the histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Optimized attribute histogram over `width` attribute](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql)

</SourceCodeTabs>

The simplified result looks like this:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[The result of optimized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### The optimized result of `width` and `height` attribute histogram in JSON format

</NoteTitle>

The optimized histogram result in JSON format is a bit more verbose, but it's still quite readable:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.AttributeHistogram">[The result of optimized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.attributeHistogram">[The result of optimized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.attributeHistogram">[The result of optimized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.rest.json.md)</MDInclude>

</LS>

</Note>

As you can see, the number of buckets has been adjusted to fit the data, contrary to the default behavior.

### Attribute histogram equalization

Standard histograms use equal-width buckets across the entire value range. This works well for uniformly distributed
data but can be problematic when data is heavily skewed. For example, if 90% of products have width between 10-50 cm
and only 10% have width between 50-500 cm, equal-width buckets would cram most products into the first few buckets
while leaving many empty buckets in the upper range.

The **EQUALIZED** behavior solves this by positioning bucket boundaries based on cumulative frequency distribution.
Instead of dividing the value range into equal intervals, it divides the *records* into approximately equal groups.
Each bucket then covers roughly the same number of items, providing a more balanced and informative histogram.

This technique is inspired by [histogram equalization in image processing](https://www.howdoi.me/blog/slider-scale.html),
adapted for filter slider UX. The algorithm:

1. Calculates the total weight (sum of all record counts)
2. Calculates cumulative frequency for each unique value
3. Positions bucket boundaries at points where cumulative frequency crosses threshold (i/bucketCount)
4. Counts actual occurrences in each resulting bucket

To demonstrate equalized histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Equalized attribute histogram over `width` attribute](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql)

</SourceCodeTabs>

The simplified result looks like this:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[The result of equalized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### The equalized result of `width` attribute histogram in JSON format

</NoteTitle>

The equalized histogram result in JSON format is a bit more verbose, but it's still quite readable:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.AttributeHistogram">[The result of equalized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.attributeHistogram">[The result of equalized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.attributeHistogram">[The result of equalized `width` attribute histogram](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.rest.json.md)</MDInclude>

</LS>

</Note>

As you can see, unlike standard histograms where bucket widths are equal, equalized histograms adjust bucket widths
to distribute records more evenly. This makes the histogram more useful for filtering when data has a skewed distribution.

## Price histogram

<LS to="e,j,r,c">

```evitaql-syntax
priceHistogram(
    argument:int!,
    argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)
)
```

<dl>
    <dt>argument:int!</dt>
    <dd>
        the number of columns (buckets) in the histogram; number should be chosen so that the histogram fits well
        into the available space on the screen
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)</dt>
    <dd>
        The behavior of the histogram calculation:
        <ul>
            <li><strong>STANDARD</strong> (default): Returns exactly the requested number of buckets with equal-width intervals across the value range.</li>
            <li><strong>OPTIMIZED</strong>: Returns fewer buckets when data is sparse to avoid large gaps (empty buckets).</li>
            <li><strong>EQUALIZED</strong>: Returns exactly the requested number of buckets, but positions bucket boundaries based on cumulative frequency distribution so each bucket covers approximately equal portion of total records. This provides better user experience when data is heavily skewed.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Combines EQUALIZED bucketing with optimization to reduce empty buckets.</li>
        </ul>
    </dd>
</dl>

</LS>

The <LS to="e,j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/PriceHistogram.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/PriceHistogram.cs</SourceClass></LS>
<LS to="g,r">price histogram</LS>
is computed from the [price for sale](../filtering/price.md). The interval related constraints - i.e.
[`attributeBetween`](../filtering/comparable.md#attribute-between) and [`priceBetween`](../filtering/price.md#price-between)
in the [`userFilter`](../filtering/behavioral.md#user-filter) part are excluded for the sake of histogram calculation.
If this weren't the case, the user narrowing the filtered range based on the histogram results would be driven into
a narrower and narrower range and eventually into a dead end.

The [`priceType`](price.md#price-type) requirement the source price property for the histogram computation. If no
requirement, the histogram visualizes the price with tax.

To demonstrate the use of the histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql)

</SourceCodeTabs>

The simplified result looks like this:

<MDInclude sourceVariable="extraResults.PriceHistogram">[The result of price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### The result of price histogram in JSON format

</NoteTitle>

The histogram result in JSON format is a bit more verbose, but it's still quite readable:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[The result of price histogram in JSON format](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[The result of price histogram in JSON format](/documentation/user/en/query/requirements/examples/histogram/price-histogram.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[The result of price histogram in JSON format](/documentation/user/en/query/requirements/examples/histogram/price-histogram.rest.json.md)</MDInclude>

</LS>

</Note>

### Price histogram contents optimization

During user testing, we found that histograms with scarce data are not very useful. Besides the fact that they don't
look good, they are often harder to manipulate with the widget that controls the histogram and tries to stick to
the bucket thresholds. Therefore, we have introduced a new histogram calculation mode - `OPTIMIZED`. In this mode,
the histogram calculation algorithm tries to reduce the number of buckets when the data is sparse and there would be
large gaps (empty buckets) between buckets. This results in more compact histograms that provide a better user
experience.

To demonstrate the optimization of the histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Optimized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql)

</SourceCodeTabs>

The simplified result looks like this:

<MDInclude sourceVariable="extraResults.PriceHistogram">[The result of optimized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### The result of optimized price histogram in JSON format

</NoteTitle>

The optimized histogram result in JSON format is a bit more verbose, but it's still quite readable:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[The result of optimized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[The result of optimized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[The result of optimized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.rest.json.md)</MDInclude>

</LS>

</Note>

As you can see, the number of buckets has been adjusted to fit the data, contrary to the default behavior.

### Price histogram equalization

Just as with attribute histograms, standard price histograms use equal-width buckets which can be problematic for
skewed price distributions. For example, in a marketplace where most items cost $10-$50 but a few luxury items cost
$500-$5000, equal-width buckets would waste slider space on the expensive (but sparse) end.

The **EQUALIZED** behavior for price histograms positions bucket boundaries based on cumulative frequency distribution,
so each bucket covers approximately the same number of products. This provides a better filtering experience, especially
for e-commerce catalogs with diverse price ranges.

To demonstrate equalized price histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Equalized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql)

</SourceCodeTabs>

The simplified result looks like this:

<MDInclude sourceVariable="extraResults.PriceHistogram">[The result of equalized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### The result of equalized price histogram in JSON format

</NoteTitle>

The equalized histogram result in JSON format is a bit more verbose, but it's still quite readable:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[The result of equalized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[The result of equalized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[The result of equalized price histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.rest.json.md)</MDInclude>

</LS>

</Note>

As you can see, the bucket boundaries are positioned to distribute products more evenly across the slider range.