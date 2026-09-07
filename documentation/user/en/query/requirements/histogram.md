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
  - **`relativeFrequency`** - the height the bar should be drawn at, on a 0-100 scale. It is a *rendering intensity*,
    never a count and never a probability - use `occurrences` for anything numeric you show to a person, and
    `occurrences / overallCount` for a share:
    - For **standard histograms**: percentage of total occurrences, calculated as `(occurrences / overallCount) * 100`.
      The values sum to 100 and empty buckets are 0.
    - For **equalized histograms**: the smoothed **value density** at the bucket, normalized against the maximum of
      the density curve, so the value lies in `(0, 100]` where 100 is the tallest point of the distribution. The
      values do **not** sum to 100, and there are no empty buckets. See
      [equalized histograms in practice](#equalized-histograms-in-practice) for what a client must and must not do
      with it.
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

`relativeFrequency` stays a valid 0–100 visualization in both cases. For a standard histogram it is the ratio of
`occurrences` to `overallCount`, and for a range source both numerator and denominator count the same overlap
attributions — a range-source bucket's height then reflects the share of **(element × overlapped-bucket)
attributions** rather than the share of distinct elements, so positions covered by more overlapping ranges appear
proportionally taller. For an equalized histogram the value is a density read off the value axis and is not derived
from `overallCount` at all, so the range-source caveat does not apply to it.

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
            <li><strong>EQUALIZED</strong>: Positions bucket boundaries on the empirical quantile function so each bucket covers approximately equal portion of total records. This provides better user experience when data is heavily skewed. Never returns more buckets than requested and returns fewer whenever a single value is held by so many records that it collapses several quantile intervals into one.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Identical to EQUALIZED. The equalized algorithm places every boundary on a value the data actually contains and therefore never produces an empty bucket, so there is nothing left to optimize away.</li>
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
3. Places a boundary at the first value whose cumulative frequency reaches each rank `k / bucketCount` — this is the
   empirical quantile function, sampled at evenly spaced ranks
4. Drops duplicate boundaries, and, when one value absorbed two or more ranks, additionally opens a bucket at the
   *next* value so that the heavy value's records are closed into a bucket of their own
5. Counts actual occurrences in each resulting bucket

Step 4 is what makes the result honest on real retail data. A price like 999 can be shared by hundreds of products, and
a boundary can only ever be placed *at* a value the data contains — you cannot split a price in half. Charging that
value for every rank it swallowed keeps the buckets after it from being starved, at the cost of returning fewer buckets
than requested. **This is normal and correct: always render however many buckets came back, never assume you got
`bucketCount` of them.**

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
            <li><strong>EQUALIZED</strong>: Positions bucket boundaries on the empirical quantile function so each bucket covers approximately equal portion of total records. This provides better user experience when data is heavily skewed. Never returns more buckets than requested and returns fewer whenever a single value is held by so many records that it collapses several quantile intervals into one.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Identical to EQUALIZED. The equalized algorithm places every boundary on a value the data actually contains and therefore never produces an empty bucket, so there is nothing left to optimize away.</li>
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

The rule is applied **per entity**, using that entity's own handling. A candidate pool that mixes handling
modes — a category listing containing both simple products and master/variant products, for instance —
therefore contributes the union of both rules: every `LOWEST_PRICE` master still expands into one data point
per variant, while its `NONE` and `SUM` neighbours contribute a single price for sale each.

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

## Equalized histograms in practice

The equalized behaviour exists to solve two concrete problems that show up on real catalogues, one created by fixing
the other. Neither is obvious until you put a slider in front of a shopper.

### Problem 1 — a linear slider spends its track on the wrong products

Retail catalogues are heavily skewed: most products sit in a narrow band, and a handful of expensive outliers stretch
the range far beyond it. A slider drawn on a linear scale gives equal *width* to equal *value*, which means it gives
almost all of its width to the part of the range where almost nothing is for sale.

This is a well-documented usability failure, not a theoretical one. Baymard Institute's slider research puts it
plainly:

> Linear slider scales will very often not be appropriate within e-commerce filtering, especially for price and
> budget. Normally the vast majority of products will be clustered within a relative narrow range with only a few
> outliers at either end of the scale.
>
> — Christian Holst, [*Improve Form Slider UX With These 5 Requirements for Slider Interfaces*](https://baymard.com/blog/slider-interfaces),
> Baymard Institute, 2015

Their worked example found a site where **50% of the slider width controlled just 2% of the products**, while 5% of
the width controlled 50% of them — leaving the slider "needlessly sensitive" exactly where the shopper needs
precision. The same study found that more than half of test subjects misread dual-point price sliders in the first
place, so any additional imprecision lands on users who are already struggling. Baymard's recommendation is to use "a
biased-scale, a logarithmic scale, or similar".

A production evitaDB catalogue of 3 237 products priced from 9 to 2 990 measures almost identically:

| On a linear track | Share of the catalogue |
|---|---|
| first half of the track | **90.9%** of products |
| second half of the track | **8.2%** of products |
| the track occupied by the middle 80% of products | **36.6%** |

Half the control does nothing, and the half that does everything is too sensitive to aim with. `EQUALIZED` replaces
the linear scale with the catalogue's own quantile function, so every position on the track moves the shopper past
roughly the same number of products.

<Note type="info">

Baymard also recommends pairing any price slider with text inputs so an exact value can be typed. That is
complementary to this feature, not replaced by it — an equalized track makes dragging viable, but typing is still the
faster route to a specific number.

</Note>

### Problem 2 — equalizing the track makes the columns meaningless

Fixing the slider breaks the chart above it. Once bucket boundaries are chosen so that every bucket holds about the
same number of products, plotting the number of products per bucket draws **a flat row of identical bars** — it is
constant by construction and tells the shopper nothing. The histogram's whole job is to show where the products are,
and equalizing the axis is precisely the operation that removes that information from the counts.

So `relativeFrequency` has to carry something else. On an equalized axis, what a reader actually perceives is how
*tightly packed* the values are at each position — the density of prices, not the count of products.

The tempting way to get that number is to derive it from each bucket's own width: a bucket holding many products
across a narrow span is dense, so `occurrences / bucketWidth` looks like the answer. It is not, and the reason matters.
A bucket's width is the distance between two adjacent prices, so such an estimate rests on a **single pair of
neighbouring values** — and price grids are arbitrary. Reprice one product and the bar it falls in can change by
orders of magnitude while the catalogue, to a shopper, has not changed at all. On the production catalogue above, a
width-derived estimate renders the bucket holding 81 products at `35.15` and the bucket holding 338 products at
`6.03` — a bucket with a quarter of the products drawing almost six times taller than one holding four times as many.

evitaDB therefore estimates the density **once, across the whole price axis**, and reads that curve at each bucket.
Bar heights become a property of the catalogue rather than of where the boundaries happened to fall, which is what
makes them stable under repricing, comparable across the chart, and safe to draw directly.

### What this gives you

- **Uniform precision along the track.** Every slider position moves past roughly the same number of products,
  instead of one half of the control doing 91% of the work.
- **No dead slider positions.** Every threshold is a price that actually occurs, so every stop selects a different set
  of products.
- **Bars that mean something and stay put.** Heights show where prices genuinely cluster, and repricing a single
  product moves the tallest bar by about 0.01%.
- **A profile you can draw as-is.** The tallest-to-shortest bar ratio on the production catalogue is roughly 13:1 — a
  readable chart with no compressing transform on the client.
- **The trade:** you may get fewer buckets than you asked for. When one price is shared by more products than a bucket
  is worth, there is no distinct price to split it at. Render however many came back.

<Note type="info">

<NoteTitle toggles="true">

##### Rendering an equalized histogram — what a client must and must not do

</NoteTitle>

Equalizing the axis changes what the numbers in the response mean, and a client that renders them the way it renders a
standard histogram will draw the wrong picture. The rules below apply to both `EQUALIZED` and `EQUALIZED_OPTIMIZED`,
for attribute and price histograms alike.

`relativeFrequency` is a **rendering intensity in `(0, 100]`**, where `100` is the maximum of the underlying density
curve. It is not a count, not a share, and not a probability.

**Do**

- Scale the bar height against the **constant `100`** — `height = chartHeight * relativeFrequency / 100`.
- Draw each bar spanning `[bucket.threshold, nextBucket.threshold)`, and the last one up to `max`. The value describes
  the **whole bucket**, not a point inside it.
- **Give the last bar a minimum width.** Its threshold can equal `max` — that happens whenever the largest value is
  numerous enough to be closed into a bucket of its own — so a bar drawn strictly to scale would be zero pixels wide
  even when it is the tallest one in the chart.
- Take slider stops from `threshold`. Every threshold is a real, selectable value, so every slider position yields a
  different result set.
- Use `occurrences` for anything numeric shown to the user ("142 products"), and `occurrences / overallCount` for a
  share.

**Don't**

- **Don't apply `sqrt` or `log`.** The value is already a linear rendering intensity with a moderate dynamic range —
  tallest-to-median is roughly 1.2–2.0 — so a compressing transform flattens a profile that is legitimately readable
  as it stands. Draw it directly.
- **Don't divide by the sum of the buckets.** Equalized values are normalized against the tallest point of the curve,
  not against each other, so they do not sum to 100.
- **Don't scale against `max()` of the returned buckets.** That re-couples the rendering to `bucketCount` — ask for
  more buckets and every bar would change height even though the distribution did not.
- **Don't assume exactly one bucket reads `100`.** The denominator is the curve maximum over all observed values, not
  over the returned buckets, so a response may legitimately contain zero buckets at 100, or several. Only
  `0 < relativeFrequency <= 100` is guaranteed.
- **Don't assume `bucketCount` buckets came back.** Fewer is normal and correct, as explained above.
- **Don't compare `relativeFrequency` across behaviours or across two different histograms.** It is a per-response
  rendering scale.

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### The mathematics behind the calculation

</NoteTitle>

**You do not need any of this to use the feature.** Everything above is sufficient to request an equalized histogram
and render it correctly. This section is for readers who want to know which established statistical methods are used
and why the standard textbook forms had to be adapted — it is background, not instructions.

**Where the boundaries go.** Equalizing an axis means sampling the *quantile function* — the inverse of the cumulative
distribution function, `Q(u) = F⁻¹(u)` — at evenly spaced ranks `u = k / bucketCount`. Where a standard histogram cuts
the *value* range into equal pieces, this cuts the *rank* range into equal pieces, which is what makes every bucket
hold roughly the same number of items.

There is one constraint that has no analogue in the standard histogram: **a boundary can only be placed at a value that
actually occurs.** A slider stop that sits between two adjacent prices selects exactly the same products as the price
below it, so it is not a distinct position at all. On real catalogues this bites constantly, because retail pricing is
full of ties — a single price like 999 can be shared by hundreds of products, which is more than a bucket's worth. When
one value spans several quantile ranks there are only two honest options: let it bleed into the following buckets, or
close it into a bucket of its own and return fewer buckets than requested. Bleeding starves everything after it, so the
value is charged for every rank it covers and the bucket count comes back short. **That is the reason fewer buckets is
normal rather than exceptional.**

**Why the bar height is not a count.** The boundaries were chosen precisely so that each bucket holds about the same
number of items, so plotting `occurrences` on an equalized axis draws a flat line by construction — it carries no
information about the distribution. What a reader actually perceives on an equal-pixel equalized axis is how *tightly
packed* the values are at each position. That quantity has a name in the statistical literature: the
**density-quantile function** `f(F⁻¹(u))`, introduced by Parzen. It is the density of the underlying values, sampled
along the equalized axis, and it is what `relativeFrequency` reports.

**How the density is estimated.** By *kernel density estimation*, the standard non-parametric approach:

```
f̂(x) = 1 / (n · h) · Σ K( (x − xᵢ) / h )
```

Each observation contributes a small bump of width `h` centred on itself, and the bumps are summed. The kernel `K` is
triangular, `K(u) = max(0, 1 − |u|)`: it has *compact support*, so an observation influences only the values within `h`
of it, which keeps the whole curve computable in a single linear pass over the data.

The only parameter that matters is the bandwidth `h`. It follows **Silverman's rule of thumb**:

```
h ≈ 0.9 · min(σ, IQR / 1.34) · n^(−1/5)
```

The `min` is what makes it robust: `σ` is sensitive to a single outlier, the interquartile range is not, and taking the
smaller of the two prevents one distant value from flattening the whole curve. The `1.34` is the normal-consistency
constant — for a normal distribution `IQR ≈ 1.34 σ` — so the two candidates are expressed in the same units. The rule
is stated for a kernel measured in standard deviations, while `h` here is a *half-width*; a triangular kernel of
half-width `h` has variance `h² / 6`, so the two are related by `h = √6 · σ`.

**Three deliberate departures from the textbook rule.** Each exists because a histogram that is *redrawn* as a shopper
filters has a requirement an ordinary statistical estimate does not: it must be a **continuous** function of the data.
A chart that visibly re-shapes itself because one product was added or repriced reads as a bug, whatever its
statistical merits.

- *The count term is the number of **distinct** values, not the number of records.* Silverman's `n^(−1/5)` assumes you
  are inferring an unknown distribution from a sample, so more observations justify a sharper estimate. Here the
  catalogue is known in full — this is a smoothing of data already in hand, not an inference about a population behind
  it. Cloning every product would leave the distribution's shape identical, so it must leave the curve identical;
  counting records instead would sharpen it by about 13% for every doubling of an unchanged catalogue.
- *A value holding a large share of the data is capped before the spread is measured.* A single value holding more than
  half the weight spans the entire interquartile range on its own, which drives the `IQR` term towards zero and
  collapses the bandwidth with it. The cap is applied as a smooth `min(w, (N − w) / 2)` rather than as an
  `if (w > N / 2)` switch, because a threshold is discontinuous exactly where real data tends to sit — a catalogue at
  50.1% on one value would otherwise be redrawn by a single product crossing 50%. The cap affects only the spread
  estimate, never the bucket contents.
- *The quartiles are averaged over a band of ranks rather than read at a point.* A point-valued quantile is a step
  function of the weights: it jumps by a whole gap the moment one observation crosses a rank boundary. Averaging the
  quantile function over a narrow band around each quartile — an *L-estimator*, `Q̄(p) = 1/(2r) · ∫ Q(u) du` — makes it
  move continuously instead. Linear interpolation between neighbouring order statistics would also be continuous, but
  it can return a value that lies *inside* a gap where no product exists, which on a catalogue with one very expensive
  item puts the estimate somewhere no data is.

**Why the scale is the curve maximum.** The bars are normalized against the tallest point of the density curve rather
than against each other. Normalizing against the returned buckets would tie the picture to `bucketCount`: asking for
more bars would change the height of every existing bar even though nothing about the catalogue had changed. Anchoring
to the curve makes the shape a property of the data alone — which is also why the value cannot be compared between two
different histograms.

**Further reading.** [Histogram equalization in image processing](https://www.howdoi.me/blog/slider-scale.html) for the
original idea; Parzen (1979), *Nonparametric statistical data modeling*, for the density-quantile function; Silverman
(1986), *Density Estimation for Statistics and Data Analysis*, for the bandwidth rule.

</Note>

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
