---
title: The hidden mathematics of a faceted filter panel
perex: |
  A filter panel looks like the simplest part of an e-shop: some checkboxes, a price slider, a few counts. It is nothing of the sort. Every redraw has to answer several structurally different questions at once, each against a different view of what the shopper has picked — and the price slider, the most innocent-looking control on the page, turns out to rest on a century of statistics that retail pricing quietly breaks. This post is about the mechanics behind those panels, the traps waiting in them, and what a correct answer actually has to satisfy.
date: '14.05.2026'
author: 'Ing. Jan Novotný'
motive: assets/images/26-faceted-filter-choreography.png
proofreading: 'done'
draft: true
---

## Too many options, and the dead end at the end of them

A filter panel has one job: take a catalog too large to read and turn it into a set the shopper can actually skim — *without painting them into a corner*. Half a million products is unbrowseable; a couple of dozen is. Every checkbox and slider is a tool for getting from the first state to the second.

The catch is combinatorial. In a multi-group panel, the combinations of picks returning *zero* products vastly outnumber those returning a viable result. A shopper one click from the right product is just as often one click from a panel that shows nothing — a **dead end** that forces them to back out and guess which pick was the spoiler.

The holy grail of faceted browsing is to keep the shopper inside the viable region: narrowing the catalog freely while quietly steering them away from the cliff edges. That is the central challenge, and everything below exists to serve it.

## What every shopper expects

Sit in front of any large e-shop's category page. Click a brand. Drag the price slider. Tick a screen size. Four invariants hold across virtually every successful storefront:

1. **Sliders never collapse under their own handles.** Drag a price slider down to "€50 – €200" and the outer handles still span the catalog's actual range, usually with a distribution drawn behind them. If they shrank to the dragged range, the shopper would be trapped in a one-way ratchet — only ever narrowing, never widening.
2. **A facet you just ticked doesn't make its neighbours read zero.** Tick *Amazon*, and the *Kobo* checkbox beside it should still show a meaningful number — "how many products would Kobo unlock for me?", not "products that are both Amazon and Kobo at once", which is structurally zero. Without this rule the panel dies on the first click.
3. **Facets in different groups multiply, not add.** Tick *Amazon* under *Brand* and *6-inch* under *Screen size* and the shopper expects products that are Amazon AND 6-inch. Across groups the mental model is conjunctive; within a group it is disjunctive.
4. **The category, locale, currency and validity rails are off-limits.** You are on the e-readers page, browsing in EUR. The panel never offers to violate that.

<Note type="info">

<NoteTitle toggles="true">

##### On a side note — when "different groups multiply" stops being true

</NoteTitle>

The conjunctive-across-groups default is right roughly 90 % of the time, but real catalogs have recurring exceptions where the shopper's model is *disjunctive* across groups. The common thread: the groups represent **alternative paths to a single outcome**, not independent attributes of one product.

- **Promotional buckets** — *Black Friday* / *Clearance* / *Loyalty discount*. Promos are typically mutually exclusive, so AND would zero the panel; ticking two means "any discounted product is fine".
- **Fulfillment options** — *Delivery* / *In-store pickup* / *Same-day shipping*. A purchase happens through one channel; ticking two means "whichever works for me".
- **Source or seller** — *Sold by us* / *Sold by partners* / *Marketplace*. The shopper means "I don't care who sells it".
- **Store availability** — *In stock at Store A* / *In stock at Store B*. The shopper means "anywhere nearby".

The diagnostic: groups constraining *independent attributes* of one product (brand, colour, size) keep the AND default; groups offering *alternative routes to one purchase event* (channel, discount, source) flip to OR.

</Note>

These look obvious in hindsight. Each is a different question the database has to answer on every redraw — and, crucially, *each question needs a different baseline*.

## Four questions, four different baselines

Here is the part that surprises people building their first panel. A redraw is not one query with one result. It is several overlapping answers that must agree with the shopper's current picks while staying informative about the picks they have not made yet.

**A facet needs two numbers, not one.** The first is a stable, universe-level count: "*Kobo* has 47 products in e-readers". It is computed as though the shopper had picked nothing at all, so it does not jump around while they play with the panel. The second is a what-if delta: "if I ticked this right now, the result count would become *n*". That one is computed with every current pick applied, simulating the state where this facet joins the selection. The first tells the shopper how big an option is; the second tells them what it would do *for them, right now*. A third signal — whether picking this option on top of the current picks would empty the list — is what lets the UI grey out dead ends before they are clicked.

**A slider must be computed against a baseline that excludes itself.** This is the non-obvious one. If you draw the price distribution from the currently matching products, the slider's own range defines the data behind it, so its handles collapse onto the shopper's selection and can never be widened again. The fix is to compute each slider's distribution with *its own kind of constraint peeled away*, while keeping the others applied. Brand picks legitimately narrow the price histogram, so they stay. The price range itself does not, so it goes. The same applies across sibling sliders of the same kind: dragging *weight* must not shrink *screen size* either, or the panel becomes a ratchet in every dimension at once.

Four answers, one set of shopper picks, four different views of it. Get the baselines wrong and the panel is subtly, infuriatingly broken in ways that are very hard to report as a bug.

## The price slider: where it stops being easy

Everything so far is bookkeeping. The price slider is where a filter panel meets actual mathematics, and where most implementations quietly go wrong.

### A linear scale wastes almost all of the track

Price distributions in retail are severely skewed: a dense cluster of ordinary products and a long thin tail of expensive ones. Map that onto a linear slider and the track is spent almost entirely on the tail. Baymard Institute's slider research puts numbers on it: on a tested retailer, **50 % of the slider width controlled just 2 % of the products**, while **5 % of the width controlled 50 %** of them in the $50–$300 band. Despite that, Baymard found **83 % of sites use a linear scale**, and **over half of test subjects misinterpreted dual-point sliders** — hypersensitivity and scale confusion being the main causes. Their prescription is explicit: use "a biased-scale, a logarithmic scale, or similar".

### Histogram equalization, and where it comes from

The clean fix is older than e-commerce and comes from image processing, where it is used to redistribute the brightness levels of a photograph so that detail spreads evenly across the available range instead of bunching in the shadows. The same idea transfers directly: instead of placing slider pivots at equal *price* intervals, place them at equal *product-count* intervals.

Mathematically that is the **empirical inverse cumulative distribution function**, or quantile function. Sort every price, and put the *k*-th of *B* pivots at the price below which *k/B* of the catalog sits. The shopper's handle then moves linearly over *product count* rather than over price, so every millimetre of travel is worth the same number of products.

A well-known walkthrough of this construction at [howdoi.me](https://www.howdoi.me/blog/slider-scale.html) puts the trick plainly — "we simply pretend that our slider is the new data set, and then use the inverse of the mapping to get the value represented by a point on the slider" — and demonstrates the payoff on a camera-lens catalog: at the slider's midpoint a linear scale still matched **548 of 561 lenses**, whereas the equalized scale matched **269**. The same article is honest about the cost, noting that a biased scale "can make selecting exact filter values difficult, whereas logarithmic or exponential scales maintain better user intuitiveness".

So far, so good. The pivots are fixed. And that is exactly where the trap opens.

### The trap: equalizing the pivots destroys the bars

Both sources are about the **scale** — where the handle sits and how fast it moves. Neither says anything about the histogram usually drawn *behind* the slider. And once the pivots are equalized, that histogram has nothing left to say: every bucket holds the same number of products **by construction**. Plot the counts and you get a flat row of identical bars. The chart that was supposed to tell the shopper where products accumulate has been mathematically emptied of information.

The obvious repair is to plot *density* instead of count — products per unit of price, which is count divided by the bucket's width in currency. That quantity is legitimate; it is a real statistical object with a real name. The problem is how it gets estimated in practice. On an equalized axis, a bucket's width is the gap between two adjacent prices in the catalog — so the entire bar height rests on **a single gap between two neighbouring products**. That is about as fragile as an estimator gets.

The consequences are not subtle, and they are easy to observe on real data:

- In a category of 45 products, one bucket took **59.8 %** of the chart's ink while holding **4.4 %** of the products. Repricing a single item by one crown dropped that bar to **0.06**.
- In a live production category of 3 237 products, the bucket holding **81 products** rendered almost **six times taller** than the bucket holding **338**.
- In another catalog, five products sharing one price rendered at **0.99** while a single product at a different price rendered at **99.01** — the tallest bar in the chart pointing at the emptiest place in the catalog.

Storefronts notice this. At least one deals with it by taking the square root of the value before drawing, purely to compress a dynamic range that should never have been that wide. That is a bandage over an estimator problem, and it hides real structure along with the noise.

### Retail pricing makes it worse: the catalog is mostly ties

Textbook statistics assumes continuous data, where two observations sharing an exact value is a measure-zero accident. Retail pricing is the opposite. Charm pricing — 199, 499, 999 — makes shared prices the *normal* case, and it concentrates enormous numbers of products onto a handful of exact values.

We measured a live category to see how extreme this gets. It holds **3 237 products across only 135 distinct prices** — an average of 24 products per price. The busiest values:

| Price | Products |
|------:|---------:|
| 999 Kč | 241 |
| 799 Kč | 176 |
| 899 Kč | 172 |
| 699 Kč | 110 |
| 599 Kč | 95 |
| 499 Kč | 82 |

These are **point masses**: spikes in the distribution where a single value carries a large fraction of the catalog. Almost every convenient assumption in the standard toolkit — that quantiles are well separated, that a bucket can be split anywhere, that spread can be measured by the distance between two percentiles — degrades or fails outright in their presence. Any equalization scheme that was validated only on synthetic, continuous data will meet its first real catalog and fall over.

### When the mathematics and the product disagree

Point masses also force a genuine product decision, and it is a good example of where a clean construction has to yield.

Suppose 30 % of a category costs exactly 199. The exact inverse CDF says that price should receive **30 % of the slider's travel**, because 30 % of the products live there. Follow the mathematics faithfully and the handle slides across a third of the track while the label reads 199 the entire way.

That is not merely ugly. Every handle position inside that plateau returns the **identical result set** — there is no price between 199 and 199 to filter differently. So a third of the track is not just visually inert, it is *functionally* inert: hundreds of positions that cannot express a distinct query, while the prices at which the catalog actually changes get squeezed into what remains.

The defensible answer is to collapse the plateau to a single selectable position. It is a deliberate deviation from the textbook construction, and it should be documented as one — but it follows from a clear principle: **every distinct slider position should correspond to a distinct achievable result.** Where a price is worth several intervals, the shopper is offered it once, because no intermediate value exists to separate them.

### What an honest answer has to satisfy

If the bar heights are to mean anything, they have to answer one question — *where do products actually pile up?* — stably. The mature tool for that is **kernel density estimation**: instead of measuring one gap, every product contributes a small smooth bump to a curve, and the height at any price is the sum of nearby contributions. Neighbouring prices reinforce each other, so a single reprice moves the answer by a little rather than by orders of magnitude.

That buys stability at the cost of one new decision: **how wide should the bumps be?** Too narrow and every distinct price becomes its own spike; too wide and real structure washes out. This is the bandwidth-selection problem, and it has a substantial literature — Silverman's rule of thumb being the classic starting point.

The caveat matters more than the rule. Rules of that family are derived under normal-theory assumptions and are typically stated for continuous data. Retail prices are neither normal nor continuous, and, as above, a large point mass can distort the very statistics such rules are built from. Anyone implementing this should expect to validate the choice against real catalogs — including ones dominated by a single price — rather than trusting a textbook constant.

<Note type="info">

<NoteTitle toggles="true">

##### On a side note — the fields this quietly touches

</NoteTitle>

A price slider with a distribution behind it sits on top of several distinct areas of mathematics, none of which are visible in the UI:

- **Histogram equalization**, from image processing, is the reason the pivots are placed at quantiles rather than at equal price steps.
- **The empirical distribution function and its inverse** (the quantile function) are the formal object the pivots are read from.
- **Density estimation** is what the bars are trying to be; plotting a count divided by a single inter-price gap is the naive estimator of it, and a famously unstable one.
- **Kernel smoothing and bandwidth selection** are how that estimate is stabilised, and where the remaining judgement calls live.
- **Order statistics on tied data** is the corner that retail pricing drags everything into, and the one most standard treatments quietly assume away.

None of this needs to surface to the shopper. All of it decides whether the chart they see is true.

</Note>

We are currently rebuilding exactly this part of evitaDB — the equalized histogram shipped in an earlier release, and measuring it against production catalogs is what produced most of the numbers above. The work is tracked in the open and the reasoning will be published with it.

## The Boolean logic shoppers actually expect

The default faceted algebra — OR within a group, AND across groups — is not a database convention that happened to stick. It mirrors the consumer mental model documented across filter-UX studies (Baymard; Made in Tandem). When a system enforces AND *within* a group instead, forcing the shopper to view "Blue" shirts, clear the filter, reload, then view "Black" shirts, the interaction overloads working memory and drives abandonment.

Real catalogs need more than the default, and every variation has both a UX story and a prediction story:

- **Conjunctive groups.** A clothing site's *Waterproof* + *Breathable* fabric features: picking both should mean "items that are both", not "items that are either".
- **Exclusive groups.** Radio-button semantics, where selecting one option implicitly replaces the previous one. For attributes representing genuinely alternative paths — Delivery vs. In-store pickup, New vs. Refurbished — psychological contrast effects say the shopper intends to *replace* the previous context rather than add to it (the inclusion/exclusion model from mental-construal research; USC Dornsife). The what-if numbers must model a replacement, not an addition, or they will not match what happens on the next click.
- **Negated groups.** A grocery store's *Allergens* panel: a shopper with a peanut allergy ticks *Peanuts* because they want products *without* them. The count beside the option has to answer "how many products remain if I exclude this?", not "how many contain it".
- **Hierarchical facets.** Ticking *Laptops* should almost always match products tagged *Ultrabooks* too. Whether a category pick extends to its descendants is a decision, and getting it wrong silently under-reports every parent category.

The point is that "what does ticking this checkbox mean" is a modelling decision about the catalog, not something a query layer can guess. Whatever expresses it has to reshape both the filtering *and* the predicted numbers, or the panel will show counts that do not survive the click.

## Why the rules look like this: evidence from the field

None of the above is arbitrary engineering taste. These are established shopper mental models, validated by years of public user-testing research and, in our case, by 15+ years of building and instrumenting production e-shops across many verticals.

**The zero-result trap.** Letting a shopper click into an empty state is the single most destructive event in faceted navigation: industry studies report abandonment rates around **69–73 %** when a faceted query collapses to zero results (Baymard Institute; Prefixbox). The fix every successful e-shop applies is to stop treating the panel as a passive query and start treating it as a prediction problem. Disabling an unviable option — graying it out with a "(0)" — preserves trust; hiding it outright breaks the shopper's mental model of a stable interface (Baymard; BigCommerce; Algolia).

**Scale integrity in sliders.** The peel-the-slider's-own-constraint rule is driven by the cognitive limits described above: if a slider's bounds collapse onto the active selection, the shopper is trapped with no visual cue that inventory exists outside the dragged range (Baymard; UX Stack Exchange; btng.studio). Usability benchmarks therefore require the underlying scale to stay static — which is precisely why the distribution behind it must be computed against a baseline that excludes the slider itself.

**The mandatory rails boundary.** E-commerce UX research draws a strict line between dynamic filters and the page's structural identity. Taxonomical categories, locales and currencies are not perceived as filters at all — they define the universe (Baymard; Search Engine Land). Keeping them outside the relaxable region serves shopper orientation and SEO simultaneously: a category page stays crawlable and canonical no matter which boxes are checked.

## In closing

A modern filter panel is not one thing. On every redraw it is at least four overlapping things — a narrowed product page, facets carrying both a stable count and a what-if delta, attribute distributions, and a price distribution — all of which must *agree* with the shopper's current picks while staying *informative* about the picks they have not made yet.

And the price slider, the most ordinary-looking control on the page, is the one that will punish a naive implementation hardest. Equalizing its scale is well-trodden and well-motivated. Keeping the chart behind it honest afterwards is a genuinely harder problem than it looks, because it lands squarely on the assumptions that retail pricing violates: continuous values, well-separated quantiles, no point masses. A catalog with 3 237 products at 135 distinct prices is not an edge case. It is Tuesday.

If you are building filter panels of any complexity, the question to ask of your stack is not "can it do facets". It is "can it compute every answer consistently, in one round-trip, against the same shopper-controlled picks — and is the distribution it draws actually true?"

If you have war stories, sharper edge cases, or a better mental model for any of this, come tell us on our [Discord server](https://discord.gg/VsNBWxgmSw) — filter-panel pathology is one of our favourite topics.
