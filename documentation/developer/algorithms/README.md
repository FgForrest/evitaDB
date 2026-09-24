# Algorithms

One page per algorithm whose *principle* is not evident from reading its implementation.

The rest of `documentation/developer/` is organised by subsystem — what a component is, where it sits, what it talks
to. These pages are the other axis: a single algorithm explained from the problem it solves down to a worked
example, for a reader who has the code open and still cannot see why it is shaped that way.

## What belongs here

A page is worth writing when all three hold:

1. **The principle is non-obvious.** Reading the implementation top to bottom does not yield it — there is an
   insight (a reformulation, an invariant, a representation choice) that the code only expresses in its
   consequences.
2. **A worked example carries it.** If the explanation lands as prose alone, it is a class-level JavaDoc comment,
   not a page.
3. **Its reach outlives one class.** Someone will otherwise re-derive it, or re-propose the thing it rejected.

Optimisations, refactors and per-change decisions do **not** belong here — those are
[decision records](../../adr/). An ADR says *what was decided and why the alternatives lost*; a page here says
*how the thing works*. Where both exist they link to each other, and neither repeats the other.

## Pages

| Page | Answers |
|---|---|
| [Range counting kernel](range-counting-kernel.md) | How interval queries (`attributeInRange`, `attributeBetween`, price validity) are resolved by a signed count over bitmap families, and why a set formulation gets multi-interval entities wrong |
