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
  - **`relativeFrequency`** - a value used for visualizing bucket height in UI (0-100 scale):
    - For **standard histograms**: percentage of total occurrences, calculated as `(occurrences / overallCount) * 100`
    - For **equalized histograms**: normalized value density that considers both occurrences and bucket width:
      1. Raw frequency is calculated as `occurrences * (totalRange / bucketWidth)` - this rewards buckets with many occurrences packed into narrow ranges
      2. Values are then normalized to sum to 100 across all buckets
      3. Empty buckets always have relativeFrequency = 0
  - **`requested`**:
    - contains `true` if the query didn't contain any [attributeBetween](../filtering/comparable.md#attribute-between)
      or [priceBetween](../filtering/price.md#price-between) constraints
    - contains `true` if the query contained [attributeBetween](../filtering/comparable.md#attribute-between)
      or [priceBetween](../filtering/price.md#price-between) constraint for particular attribute / price
      and the bucket threshold lies within the range (inclusive) of the constraint
    - contains `false` otherwise

<Note type="info">

The identity `overallCount = sum of bucket occurrences` always holds. For histograms built over a **range-typed
source** (reference histograms only — see [reference histograms](../../use/schema.md#reference-histograms)), a single
element can fall into several buckets at once, so `overallCount` may exceed the number of distinct contributing
elements. For every scalar-source histogram the two are equal.

`relativeFrequency` stays a valid 0–100 visualization in both cases — it is a ratio of `occurrences` to `overallCount`
(standard buckets still sum to 100, equalized buckets are still normalized to 100), and for a range source both
numerator and denominator count the same overlap attributions. The only difference is interpretive: a range-source
bucket's height reflects the share of **(element × overlapped-bucket) attributions** rather than the share of distinct
elements, so positions covered by more overlapping ranges appear proportionally taller.

</Note>

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
part of the filter. Range selections on attributes placed inside the
[`userFilter`](../filtering/behavioral.md#user-filter) container — both
[`attributeBetween`](../filtering/comparable.md#attribute-between) and
[`histogramHaving`](../filtering/references.md#histogram-having) — are **excluded** from the attribute-histogram
baseline so the slider does not contract under its own handle as the user drags it. Facet selections
([`facetHaving`](../filtering/references.md#facet-having)) and the price range
([`priceBetween`](../filtering/price.md#price-between)) remain applied, so the histogram reflects the range of
attribute values actually reachable under the user's current facet and price picks. The rationale and a worked
example are covered in [Baseline relaxation](#baseline-relaxation--sliders-dont-contract-under-their-own-handles)
below.

To demonstrate the use of the histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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
is computed from the [price for sale](../filtering/price.md). Only
[`priceBetween`](../filtering/price.md#price-between) placed inside
[`userFilter`](../filtering/behavioral.md#user-filter) is **excluded** from the price-histogram baseline so the
price slider does not contract under its own handle as the user drags it. Attribute range sliders
([`attributeBetween`](../filtering/comparable.md#attribute-between),
[`histogramHaving`](../filtering/references.md#histogram-having)) and facet selections
([`facetHaving`](../filtering/references.md#facet-having)) remain applied, so the price histogram reflects the
prices actually reachable under the user's current attribute range and facet picks.

The [`priceType`](price.md#price-type) requirement the source price property for the histogram computation. If no
requirement, the histogram visualizes the price with tax.

### Price histogram granularity and inner-record handling {#price-histogram-granularity}

The histogram answers *"what prices are reachable in the candidate pool?"* The answer depends on how the collection
handles inner records (`PriceInnerRecordHandling`), because that determines what constitutes one price data point:

| Inner-record handling | Histogram data point per entity |
|-----------------------|--------------------------------|
| `NONE`                | One — the price for sale of the entity |
| `SUM`                 | One — the cumulated price of all inner records |
| `LOWEST_PRICE`        | **One per inner-record id** — the winning price of each variant |

To demonstrate the use of the histogram, we will use the following example:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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

## Baseline relaxation — sliders don't contract under their own handles

Every histogram answers a "what-if" question: *what range of values would still be reachable if I let go of this
slider and moved it to the extremes?* A histogram whose `[min, max]` shrank every time the user dragged the slider
inward would trap the user in a collapsing range — each drag would make the next drag have less room, and returning
to a wider range would be impossible without resetting the slider to its full extent. To avoid this, every
histogram's `[min, max]` baseline must **hide the user's own range picks** while still honouring picks made on
other filter surfaces (facet buttons, the price slider, etc.).

### How evitaDB applies the relaxation

evitaDB classifies every child of [`userFilter`](../filtering/behavioral.md#user-filter) into one of three
mutually exclusive *filter surfaces*:

1. **Attribute range sliders** — [`attributeBetween`](../filtering/comparable.md#attribute-between) and
   [`histogramHaving`](../filtering/references.md#histogram-having). These drive attribute histograms, both on
   plain entity attributes and on reference-level histograms.
2. **Facet selections** — [`facetHaving`](../filtering/references.md#facet-having). These drive the facet summary
   and its impact calculations.
3. **Price range** — [`priceBetween`](../filtering/price.md#price-between). This drives the price histogram.

When an extra-result projection (attribute histogram, facet summary impact, price histogram) is computed, evitaDB
peels away **only the surface that projection belongs to** and leaves the other two applied. The main entity page
returned by the query is still narrowed by **all three** surfaces — the relaxation applies strictly to the
`[min, max]` spans and bucket distributions of the extra-result projections.

### Worked example

Suppose the user is browsing `Product` and has made three independent picks:

```evitaql
userFilter(
    facetHaving("brand", entityHaving(attributeEquals("code", "amazon"))),
    attributeBetween("height", 50, 120),
    priceBetween(100, 500)
)
```

and the query also requests `attributeHistogram(20, "height", "width")`, `priceHistogram(20)`, and a facet summary
with `IMPACT`. evitaDB computes four baselines in one pass:

| Self-computation | What the baseline hides | What the baseline keeps applied |
|------------------|-------------------------|---------------------------------|
| **height histogram** | every attribute range slider — `attributeBetween("height", …)` and every other `attributeBetween` or `histogramHaving` in the same `userFilter` | `facetHaving("brand", …)`, `priceBetween(100, 500)` |
| **width histogram** | the same — every attribute range slider is peeled for any attribute histogram in the query | `facetHaving("brand", …)`, `priceBetween(100, 500)` |
| **facet impact** for other brands | every `facetHaving` selection | `attributeBetween("height", …)`, `priceBetween(100, 500)` |
| **price histogram** | `priceBetween(100, 500)` | `facetHaving("brand", …)`, `attributeBetween("height", …)` |

This also means that **adding a second slider on the same filter surface does not contract the first one**: if the
query contains both `attributeBetween("height", 50, 120)` and `attributeBetween("width", 10, 40)`, each attribute
histogram is computed with *both* range sliders peeled, so neither slider contracts the other's `[min, max]` as
the user drags.

### Recommended range carriers

Pick the `userFilter` child that matches where the slider lives — each one is recognised by evitaDB as a range
carrier and is peeled from the appropriate histogram baseline:

| Slider lives on … | Recommended `userFilter` child |
|-------------------|--------------------------------|
| a plain entity attribute (`Product.width`, `Product.height`, …) | [`attributeBetween`](../filtering/comparable.md#attribute-between) |
| a reference-level histogram (e.g. `parameterValues.height` on `Product`) | [`histogramHaving`](../filtering/references.md#histogram-having) — the first-class carrier for reference histograms; also disambiguates between multiple histograms on the same reference |
| the price for sale | [`priceBetween`](../filtering/price.md#price-between) |
| a facet selection | [`facetHaving`](../filtering/references.md#facet-having) |

Plain [`referenceHaving`](../filtering/references.md#reference-having) is **not** accepted inside `userFilter` —
it has no slider semantics and would not participate in baseline relaxation. Use
[`histogramHaving`](../filtering/references.md#histogram-having) for slider carriers on references.
