# ValueDedupRepresentationSpike — are the projections real?

**Question.** [`ValueDedupCensus`](ValueDedupCensus.md) prices two candidate representations with a
byte-level model. Does that model match what the JVM actually allocates?

A projection is only as good as its object model, and a census whose model is wrong is a census that
argues for the wrong design with confident numbers. This spike builds the candidate representations
from **real sampled reduced trees** and measures them with an explicit-stop-set deep-retained walker.

## What it established

**The dictionary spine model is byte-exact on 3,713 of 3,713 trees.** The K = 1 case was settled by
enumerating all four possible shapes rather than sampling them.

**The container model misses exactly one array header per temporal-keyed tree** — the engine carries a
parallel seconds/nanos pair-column where the census modelled one array. The error is bounded at 0.34%
of the affected domains and runs in the **optimistic** direction, which is recorded rather than
corrected so that the two numbers stay comparable across runs.

**The per-tree B+ tree alternative measures 3.2–10.2× the exact-array spine** at its own favourable
block size. That retires the "just use a smaller tree" rejection with numbers instead of an assertion.

**The strings-in-place variant isolates the dictionary's marginal value over a plain container** — the
measurement that turned the dictionary lever from a headline (+502.7 MB) into a marginal (+128.2 MB over
the container), and with it into deferred scope.

## The coupling to the census, and why it is deliberate

The census exposes package-private widenings of its two projection models and their strata/lever
vocabulary, so this spike tests **the same code** rather than a copy of it. A copy would drift, and a
drifted validator validates nothing. The widening was proven neutral by a byte-identical census re-run
on the rebuilt jar.

## Why it is still live

The model it validates is the one any future change to the container design must still satisfy. If the
container shape in [#1486](https://github.com/FgForrest/evitaDB/issues/1486) changes during
implementation — a chunked array instead of an exact one, say — this is where the new shape is proven
before the census is trusted again.

## Running it

Needs `-Djol.magicFieldOffset=true` and the JOL `--add-opens` set, against a catalog snapshot. Keep
`-Xmx` below 32 GB.

## Related

- [`ValueDedupCensus`](ValueDedupCensus.md) — the model under test.
