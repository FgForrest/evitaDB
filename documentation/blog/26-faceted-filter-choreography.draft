---
title: The hidden choreography of a faceted filter panel
perex: |
  Every well-built e-shop filter panel runs on a delicate choreography. The shopper drags a slider, ticks a checkbox, and the panel must redraw without ever feeling broken — collapsing sliders, facets that read zero, price ranges that shrink to a sliver are the trust-killers. The unspoken UX rules behind those panels translate to a surprisingly precise set of database semantics. In evitaDB they take the shape of `userFilter` and three carrier families. This post walks through that choreography from the shopper's screen to the query.
date: '14.05.2026'
author: 'Ing. Jan Novotný'
motive: assets/images/26-faceted-filter-choreography.png
proofreading: 'done'
draft: true
---

## Too many options and the dead-end problem

A filter panel has one job: take a catalog too large to read and turn it into a set the shopper can actually skim — *without painting them into a corner*. Half a million products is unbrowseable; a couple of dozen is. Every checkbox and slider is a tool that gets the shopper from the first state to the second.

The catch is that in a multi-group filter panel, the combinations of picks returning *zero* products vastly outnumber those returning a viable result. A shopper one click from the right product is just as often one click from a panel that shows nothing — a **dead end** that forces them to back out and guess which pick was the spoiler. Combinatorics is not on the panel's side.

The holy grail of faceted browsing is to keep the shopper inside the viable region — narrowing the catalog freely while quietly steering them away from dead ends. That balance is the central UX challenge, and everything in this post exists to serve it: the four invariants below, the carrier-family mechanics, the per-option `hasSense` flag in evitaDB's reference summary.

## The shopper's expectation

Sit in front of an Amazon category page, a Booking.com search result, or eBay's filter rail. Click a brand. Drag the price slider. Tick a screen size. Four invariants hold across virtually every successful e-shop:

1. **Sliders never collapse under their own handles.** Drag a price slider down to "€50 – €200" and the outer handles still span the catalog's actual range, usually with a histogram or shaded distribution behind them. If they shrank to your dragged range, you'd be trapped — only ever narrowing, never widening.
2. **A facet you just ticked doesn't make the other facets read zero.** Tick *Amazon*. The "Kobo" checkbox next to it should still show a meaningful count — "how many products would Kobo unlock for me?", not "products that are both Amazon and Kobo at once", which is structurally zero. Without this rule the panel dies on the first click.
3. **Facets in different groups multiply, not add.** Tick *Amazon* in *Brand* and *6-inch* in *Screen size* and you expect products that are Amazon AND 6-inch — not Amazon OR 6-inch. Across groups, the mental model is conjunctive.
4. **The category, locale, currency, and validity rails are off-limits.** You're on the e-readers page browsing in EUR. The filter panel never offers to violate that.

<Note type="info">

<NoteTitle toggles="true">

##### On a side note — when "facets in different groups multiply" stops being true

</NoteTitle>

The conjunctive-across-groups default is right roughly 90 % of the time, but real catalogs have a few recurring exceptions where the shopper's mental model is *disjunctive* across groups. The common thread: the groups represent **alternative paths to a single outcome**, not independent attributes of one product.

- **Promotional buckets** — *Black Friday* / *Clearance* / *Loyalty discount*. Promos are typically mutually exclusive in the catalog, so AND would zero the panel; ticking two means "any discounted product is fine".
- **Fulfillment options** — *Delivery* / *In-store pickup* / *Same-day shipping*. A single purchase happens through one channel; ticking two means "whichever channel works for me".
- **Source / seller** — *Sold by us* / *Sold by partners* / *Marketplace*. Each row is a distinct origin; the shopper means "I don't care who sells it".
- **Store / branch availability** — *In stock at Store A* / *In stock at Store B*. The shopper means "anywhere nearby"; only one branch needs to actually have it.

The diagnostic: groups that constrain *independent attributes* of one product (brand, colour, size) keep the AND default; groups offering *alternative routes to one purchase event* (channel, discount, source) flip to OR. Schema authors opt into the disjunctive reading per reference with `facetGroupsDisjunction`, covered later.

</Note>

These four rules look obvious in hindsight, but each is a different question the database has to answer on every redraw — and *each question requires a different baseline*. That's the choreography this post is about.

## One query, many predictions

In evitaDB the entire filter panel — the product page plus every prediction the panel renders — comes back from a single query. Here is what a busy moment on the e-readers page looks like, with the shopper having checked *Amazon*, dragged the price slider to €50 – €200, and the weight slider to 200 – 400 g:

<SourceCodeTabs local>

[The full filter panel as one query](/documentation/blog/en/examples/25-faceted-filter-choreography/01-full-panel.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### On a side note — these queries use names from our demo schema

</NoteTitle>

The identifiers throughout this post — `Product` as the entity collection; `categories` / `brand` / `parameterValues` as references; `intervalParameterValues` as a histogram name; attributes like `code` and `isVisibleInFilter`; values like `"e-readers"` or `"amazon"`; the `EUR` currency and `"basic"` price list — all belong to the schema of the [evitaDB demo dataset](https://demo.evitadb.io). Your own catalog will name everything differently.

Focus on the **mechanics**, not the literal identifiers: what sits outside `userFilter` vs. inside, which `require` constraints pair with which `userFilter` carriers, how the predictions flow back. When you transcribe these queries to your own catalog the structural roles stay the same.

</Note>

The `filterBy` block has two distinct regions:

- **Outside `userFilter`** sit the immovable rails: category, locale, price list, currency, price validity. These define the *universe* the shopper is browsing — the e-readers page in EUR at today's prices. Non-negotiable for this page view.
- **Inside `userFilter`** sit the picks the shopper actually made — the brand checkbox, the price slider, the weight slider. Only these get relaxed when predictions are computed.

The `require` block asks the server for several predictions in one shot: per-brand stats (a stable count and a context-sensitive impact per option), per-parameter histograms over `intervalParameterValues` (so every parameter slider can render its handles and distribution), and a price histogram. Four answers, one `userFilter`, four different baselines.

## Three carrier families

The constraints a shopper can drop into `userFilter` form three disjoint **carrier families**:

| Carrier family             | What it expresses                    | Constraints                                | Powers prediction                                       |
|----------------------------|--------------------------------------|--------------------------------------------|---------------------------------------------------------|
| **Facet carriers**         | "I want this checkbox"               | `facetHaving`                              | Facet COUNT and IMPACT in `referenceSummary`            |
| **Value-range carriers**   | "I want this slider's range"         | `attributeBetween`, `histogramHaving`      | Attribute and per-parameter (reference) histograms      |
| **Price-range carriers**   | "I want this price slice"            | `priceBetween`                             | Price histogram                                         |

Every `userFilter` child belongs to exactly one family. The pairing is one-to-one with one twist: facets return **two** numbers per option — a universe-level COUNT and a context-sensitive IMPACT — and the engine builds a different baseline for each.

## What each prediction sees of `userFilter`

Each redraw asks four structurally different questions, each with its own baseline:

| Computing…                                          | Facet carriers                                  | Value-range carriers | Price-range carriers |
|-----------------------------------------------------|-------------------------------------------------|:--------------------:|:--------------------:|
| **Facet COUNT** (per option, universe-level)        | **dropped** — entire `userFilter` is ignored    | dropped              | dropped              |
| **Facet IMPACT** (per option, delta)                | kept; selection simulated per group rules       | kept                 | kept                 |
| **Parameter / attribute slider baseline**           | kept                                            | **dropped**          | kept                 |
| **Price slider baseline**                           | kept                                            | kept                 | **dropped**          |

### Facet COUNT — the option's universe

For each facet option, the engine computes how many products in the page universe (the mandatory rails — category, locale, currency, validity) carry that option, **ignoring everything the shopper has picked**. The whole `userFilter` is dropped.

This is the **stable upper bound** — "*Kobo* has 47 products in e-readers" — and it doesn't move when the shopper plays with checkboxes or sliders. It tells them how big each option's universe is in the category, independent of the current narrowing.

One special case: a group marked with `facetGroupsNegation` flips COUNT to match the toggle's intent. Picture a grocery store's **Allergens** panel. A shopper with a peanut allergy ticks *Peanuts* — they're not looking for products *with* peanuts, they want products *without* them. The count next to *Peanuts* therefore answers "how many products remain if I exclude this allergen?". COUNT for a negated option shows the **post-exclusion universe** — still stable, still universe-level, just inverted to be the number the shopper actually cares about.

### Facet IMPACT — what if I picked this?

IMPACT answers the shopper-facing **what-if**: *"If I picked this facet right now, what would the result count become?"* The entire `userFilter` is kept as the baseline; the engine **simulates the state where this facet is in the selected set** and reports two numbers — the simulated `matchCount` and the `difference` against the current count.

The shape of "this facet is selected" follows the reference's facet-group rules. By default — OR within a group, AND across groups — the simulated state OR-merges this facet with any sibling picks in the same group. Ticking expands the result set when siblings are present (positive `difference`); with no siblings, ticking narrows from "all category products" to "products with this facet" (negative `difference`). Schema-level overrides (`facetGroupsConjunction`, `facetGroupsExclusivity`, `facetGroupsDisjunction`, `facetGroupsNegation`) reshape this simulation — see [*Facet behaviour is richer than "check this checkbox"*](#facet-behaviour-is-richer-than-check-this-checkbox) below.

The simulation is symmetric — it always builds the "this facet selected" state, regardless of whether the facet is currently in `userFilter`. For a facet already checked under default OR-within-group semantics, the simulated state matches the current state and `difference` reads zero. The third number in the bundle, **`hasSense`**, is evitaDB's per-option [dead-end predictor](#too-many-options-and-the-dead-end-problem) — false when picking this option on top of the current picks would empty the result list. The panel uses it to grey out dead-end options, label them as no-ops, or warn before allowing the click. For an already-checked option with `difference = 0`, `hasSense` also tells the panel whether the option is still doing useful work inside the current narrowing.

COUNT and IMPACT are complementary. COUNT says "how big is this option in absolute terms"; IMPACT says "what would picking it actually do *for me, right now*". Most filter panels render one of the two next to each checkbox; richer ones show both — a primary count and a secondary impact preview on hover.

### Slider baselines — the peel-by-family rule

For histograms, the engine peels one carrier family out of `userFilter` — the family that *backs the slider being painted* — and keeps the other two applied. Each slider stays operable: it never contracts under its own handle, and it still responds to picks in other families.

- **Parameter / attribute slider baseline.** Brand picks and price picks honestly narrow the universe (so they stay applied), but every value-range carrier is peeled — both the slider being painted *and* every sibling slider in the same family. That's why dragging the *weight* slider doesn't shrink its own outer handles, and doesn't shrink the *height* or *screen size* sliders either: they all peel together.
- **Price slider baseline.** Mirror image: brand and value-range picks narrow the price histogram, but the price-range carrier is peeled so the price slider can be reopened.

The four mechanisms compose cleanly. Value-range and price-range carriers stay applied during facet IMPACT and during cross-family slider baselines, so each prediction does the right relaxation for its own question without interfering with the others.

## Watching it play out

Theory is one thing; watching the panel breathe is another. Take the busy-state query from the top of the post — *Amazon* checked, weight at 200–400 g, price at €50–200 — and drop it into [evitaLab](https://demo.evitadb.io) on the demo dataset. The **results visualizer** renders the response the way a real e-commerce panel would: checkboxes with counts and impacts, sliders with handles and distribution histograms, a paged product grid. From there:

- **Comment out individual children of `userFilter`** to retract one shopper pick at a time. Brand impacts shift while the COUNT column stays still. Parameter and price sliders narrow when you remove one knob, and widen back to their catalog-wide spans when you remove their own carriers.
- **Alter the bounds** in `priceBetween(50, 200)` or `histogramHaving(…, 200, 400, …)` to see the slider distributions redraw against the new range.
- **Empty `userFilter` entirely** to land at the "just landed" state — every option's COUNT and IMPACT collapse onto the same number and every slider sits at its catalog-wide span.

The mandatory rails, the page size, and the entire `require` block stay constant. The shopper drives the panel state by editing one subtree.

## Why `histogramHaving` and not just `attributeBetween`?

evitaDB has **two** related but distinct shapes for value-range filtering, and the difference matters once a slider points at something more interesting than a flat attribute.

### Entity-attribute histograms — the simple case

For numeric attributes defined directly on the entity — a product's `width`, say — the pairing is straightforward:

- `attributeBetween("width", 100, 250)` filters the result set to products whose `width` is in the range
- `attributeHistogram(20, "width")` returns the distribution of `width` across the matching products with 20 buckets

Inside `userFilter`, `attributeBetween` is the value-range carrier for `attributeHistogram`: the constraint narrows the result set normally but is peeled out when the histogram baseline is computed. Textbook value-range family. The user query just names the attribute.

### Reference histograms — the complex case

The story changes once the value lives one step away from the entity — on a *reference attribute* or on an attribute of the *referenced entity*. The canonical example in the demo dataset is `parameterValues`, a one-to-many reference where each link can carry a numeric attribute (`intervalParameterValues`) and is *grouped* by a parameter entity that identifies the kind of value — weight, depth, screen size, RAM, and so on.

A single `parameterValues` reference therefore hosts **many independent histograms simultaneously**, one per parameter group. Same reference, same underlying attribute name, but the weight histogram, depth histogram, and screen-size histogram are three different things.

A bare `attributeBetween` cannot serve as the carrier here. If the UI writes `attributeBetween("intervalParameterValues", 200, 400)` into `userFilter`, the engine has no honest way to know:

1. **Which reference** owns the index — `parameterValues`? Some other reference that also has an `intervalParameterValues` attribute?
2. **Which materialized histogram** within that reference — weight, depth, or screen size?
3. **Where the underlying attribute lives** — on the reference relation itself, or on the referenced entity?

`histogramHaving` solves this by carrying the address explicitly. Its arguments are a `(referenceName, histogramName, groupSelector, range)` tuple — the exact coordinates of the materialized histogram the slider is bound to. The engine routes the carrier to the value-range family and knows precisely which histogram to relax when computing its baseline. The same address points to the values that need to satisfy the range, so result-set narrowing falls out as a side effect.

The general principle: **when a query targets a pre-computed structure with an ambiguous identity, the constraint has to address that structure directly**. For entity attributes the address is just the attribute name and `attributeBetween` is enough; for reference histograms the address is a `(reference, histogramName, group)` triple, and `histogramHaving` is the constraint that carries it. The schema knows things — what's indexed, how it's bucketed, what's grouped — that a generic value-range filter can't recover at the query layer.

## Filter-side and require-side: the exact pairing

Each filter-side carrier pairs one-to-one with a require-side prediction:

| When you drop this inside `userFilter`…           | …it acts as a soft carrier for this prediction                                       |
|---------------------------------------------------|--------------------------------------------------------------------------------------|
| `facetHaving(reference, …)`                       | `facetStatistics` inside `referenceSummaryOfReference`                               |
| `attributeBetween(attribute, …)`                  | `attributeHistogram`                                                                 |
| `histogramHaving(reference, histogramName, …)`    | `histogramStatistics` inside `referenceSummaryOfReference`                           |
| `priceBetween(min, max)`                          | `priceHistogram`                                                                     |

*Soft carrier* is the inside-`userFilter` behaviour described above: the constraint narrows the result set normally, but the engine peels it (or simulates around it) when its paired prediction is computed. Outside `userFilter`, the same constraints become hard filters with no special treatment.

Wire a slider to a carrier and forget the matching require-side prediction and the slider has nothing to redraw from. Add the prediction without the carrier and it ignores the shopper's pick. The pair is what makes the panel feel alive.

## Facet behaviour is richer than "check this checkbox"

So far we've assumed the most common rule: within a group, ticked checkboxes combine with OR; across groups, AND; ticking includes products with that facet. That default covers maybe 90 % of e-shop filter panels in the wild. evitaDB lets schema authors override every one of those defaults — and each override changes both the result-set semantics and the impact predictions accordingly.

Five `require`-side constraints reshape the facet algebra:

- **`facetCalculationRules`** sets query-wide defaults for within- and cross-group combination. Per-reference rules below override it.
- **`facetGroupsConjunction(reference, …)`** flips the within-group default from OR to AND. Real use case: a clothing site's *Waterproof* + *Breathable* fabric features — picking both means "items that are both", not "items that are either".
- **`facetGroupsDisjunction(reference, …)`** flips the cross-group default from AND to OR. Rare; mostly used when two groups are alternative views of the same axis (e.g., a "search by Brand *or* by Designer" rail on a fashion site).
- **`facetGroupsNegation(reference, …)`** inverts every selection in the targeted group: a ticked checkbox now *excludes* products with that facet. Renders as "Exclude" toggles. Common for "hide out-of-stock", "hide discontinued", or merchant blacklists.
- **`facetGroupsExclusivity(reference, …)`** declares the group mutually exclusive — radio-button semantics. Selecting one option implicitly deselects the others. Primarily a *prediction* rule: the impact for each unticked option assumes the currently-ticked option would be replaced rather than added.

Conjunction, disjunction, and exclusivity reshape only the **facet IMPACT** in the `referenceSummary` payload — they describe how *multiple* selections combine, and the per-option COUNT computes against a single facet where there's nothing to combine. Conjunction shrinks impacts (AND is stricter than OR); exclusivity makes them replacements rather than additions; disjunction flips the cross-group merge. **Negation is the exception that reshapes both numbers**: COUNT shows the post-exclusion universe (products that remain if this "hide" toggle were active), and IMPACT reflects what happens when that exclusion is added on top of the rest of the shopper's picks. In every case the shopper sees numbers consistent with what would actually happen on click.

Two more constraints live *inside* `facetHaving` and matter for hierarchical references like `categories`:

- **`includingChildren()`** extends a hierarchical facet pick to all descendant categories. Without it, ticking *Laptops* matches only products directly tagged with *Laptops*, not those tagged with *Ultrabooks* (a child). Almost always desired.
- **`includingChildrenHaving(…)` / `includingChildrenExcept(…)`** narrow or invert the children inclusion — useful when only visible subcategories should propagate, or when one branch should be excluded.

The shopper-facing variety of "what does ticking this checkbox mean" is a schema-level decision, not a query-level guess. Each variation has its own UX research story and its own impact-prediction story, and evitaDB encodes both faithfully.

## Why the rules look like this: UX evidence from the field

None of the semantics above are arbitrary engineering choices. They translate established shopper mental models, validated by years of public user-testing research on top e-commerce sites and by FG Forrest's own 15+ years of building, instrumenting, and iterating on production e-shops across many verticals. The carrier families and relaxation rules exist specifically to mitigate documented behavioural failure modes.

### The zero-result trap

Allowing a shopper to click themselves into an empty state is the single most destructive event in faceted navigation: industry studies report abandonment rates around **69–73 %** when a faceted query collapses to zero results (Baymard Institute; Prefixbox). The fix every successful e-shop applies is to abandon passive database queries in favour of predictive simulation. Disabling an unviable option mid-session — graying it out with a "(0)" — preserves trust; hiding it outright breaks the shopper's mental model of interface stability (Baymard; BigCommerce; Algolia). evitaDB returns COUNT, IMPACT, and `hasSense` from the same query so the UI never has to choose between honest counts and avoiding dead ends.

### Scale integrity in range sliders

The peel-by-family rule for sliders is driven by the cognitive limits of linear tracks mapped over non-linear product distributions. Baymard's slider-UX work reports that **over half of test subjects misinterpret dual-point sliders** because of hypersensitivity and scale confusion; if a slider's bounds collapse to match the active selection, the shopper is trapped in a one-way ratchet with no visual cue that more inventory exists outside the dragged range (Baymard; UX Stack Exchange; btng.studio). Usability benchmarks therefore require the underlying scale to stay static — exactly why the database isolates and excludes the slider's own active constraints when predicting its baseline.

### Cognitive load and Boolean logic

The default faceted algebra — OR within a group, AND across groups — is not a SQL convention; it mirrors the consumer mental model documented across filter-UX studies (Baymard; Made in Tandem). When systems erroneously enforce AND-within-group, forcing the shopper to look at "Blue" shirts, clear the filter, reload, then look at "Black" shirts, the interaction overloads working memory and triggers abandonment.

For attributes that represent genuinely alternative paths — Delivery vs. In-Store Pickup, New vs. Refurbished, sort orders — psychological contrast effects dictate that the shopper intends to *replace* the previous context rather than add to it (the inclusion/exclusion model from mental-construal research; USC Dornsife). `facetGroupsExclusivity` and `facetGroupsDisjunction` let the what-if calculations mirror this replacement intent so the IMPACT numbers match what would actually happen on the next click.

### The mandatory rails boundary

E-commerce UX research draws a strict line between dynamic filters and the page's structural identity. Taxonomical categories, geographical locales, and currencies are not perceived as filters — they define the universe (Baymard; Search Engine Land). Excluding them from `userFilter` keeps relaxation passes from altering page identity, which simultaneously serves shopper orientation and SEO indexing: a category page stays crawlable and canonical regardless of which facet boxes are checked.

evitaDB did not invent these patterns — it encoded them directly into the query language and made the implementation efficient enough that an entire filter panel comes back in roughly a millisecond on real-world catalogs. The point of `userFilter` and its three carrier families is that the choreography becomes *expressible*: shopper picks, the predictions they drive, and the mandatory rails are all visible as structural regions of one query, and the relaxation rules are formal enough that the server applies them consistently. The shop developer writes one query per filter panel state; the database does the rest.

## In closing

A modern e-shop filter panel is not one thing. On every redraw it is at least four overlapping things — a narrowed product page, facets carrying both a stable count and a delta impact, slider histograms, and a price histogram — all of which need to *agree* with the shopper's current picks while staying *informative* about the picks they haven't made yet. That's the difference between a panel that earns trust and one that traps the shopper on the first click.

If you're building filter panels of any complexity, the question to ask of your stack isn't "can it do facets". It's "can it compute all four answers consistently, in one round-trip, against the same shopper-controlled subtree?"

If you have war stories, sharper edge cases, or a better mental model for any of this, come tell us on our [Discord server](https://discord.gg/VsNBWxgmSw) — filter-panel pathology is one of our favourite topics.
