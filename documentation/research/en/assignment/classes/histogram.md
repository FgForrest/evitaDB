---
title: Histogram
perex: |
    The article explains the Histogram class and its components, such as data arrays and bin edges, which are 
    used for frequency distribution visualization.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

The data transfer record containing information about the interval attribute and its histogram.
The histogram can be rendered as a column graph, where the first column is computed as:

**first threshold** (inclusive) - **second threshold** (exclusive)
...
**last - 1 threshold** (inclusive) - **maxValue** (inclusive)

- **[BigDecimal](https://docs.oracle.com/javase/8/docs/api/java/math/BigDecimal.html) maxValue:** maximal value of the attribute present
- **array of [HistogramThreshold](#histogram-threshold):** returns histogram buckets with a count of entities in them.
The array is then computed for a specified maximum number of buckets (usually derived from the visual space dedicated to histogram in the UI)

## Histogram threshold

- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) index:** index o the bucket starting with zero (order of the bucket in the histogram)
- **[BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) threshold:** contains threshold (left bound - inclusive) of the bucket
- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) occurrences:** count of entities in this bucket
