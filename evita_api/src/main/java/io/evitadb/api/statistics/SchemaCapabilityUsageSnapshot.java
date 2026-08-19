/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.statistics;

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * How often one **schema capability** was asked for by queries, against how often mutations had to maintain it - the
 * reading that answers *"you never filter by EAN, so why are you paying to keep its filter index up to date?"*.
 *
 * One row describes one capability flag, on one schema element, in one scope: `filterable()` on the entity attribute
 * `ean` in the live scope, `sortable()` on the `categories` reference's `priority` attribute, and so on. That is the
 * granularity an operator can act on, because the remedial action is a **schema mutation** which removes every physical
 * index maintaining the flag at once.
 *
 * # `requestedCount` is not physical index usage, and must never be presented as such
 *
 * This is the one thing to understand before using either count, because {@link BrowsedIndex#queryCount()} sounds
 * identical and answers a different question.
 *
 * - {@link BrowsedIndex#queryCount()} counts the times **one physical index** was in the winning target index set of an
 *   executed plan - *"is this index earning the heap it occupies?"*. A candidate index the planner probed and then
 *   discarded is deliberately excluded, because counting it would inflate the losers.
 * - {@link #requestedCount()} counts the times **a logical query asked for this capability**, whichever plan won -
 *   *"would dropping this flag from the schema break somebody's query?"*. For that question a losing candidate plan is
 *   not a false positive at all: the query named the element, so removing `filterable()` would have made it invalid
 *   regardless of which index ended up serving it.
 *
 * The two therefore disagree by design, and the difference is not an error to be reconciled.
 *
 * **It is counted once per logical query, not once per candidate plan.** The planner translates a filter afresh for
 * every candidate it considers, so a count taken where the translation happens would measure how many alternatives the
 * planner weighed rather than what the workload does.
 *
 * **The debug modes are the one caveat.** `VERIFY_ALTERNATIVE_INDEX_RESULTS` and `VERIFY_POSSIBLE_CACHING_TREES` make
 * the engine build and execute a query's alternative plans a second time to compare their results. That re-executes
 * genuine physical work - which the per-index counters see - but the capability is still counted **once**, because one
 * logical query was issued. A catalog running with those debug modes on therefore shows this surface and the per-index
 * one diverging further than usual, and this surface is the one that stayed faithful to the workload.
 *
 * # What `updatedCount` counts
 *
 * **Entity mutations that touched the element**, deduplicated per entity mutation rather than per affected index: one
 * upsert writing an attribute that lives in the global index and five reduced indexes is one, not six. The fan-out
 * width is a legitimately different metric - *"physical maintenance operations"* - and it is not what this measures;
 * {@link BrowsedIndex#updateCount()} is where fan-out is visible.
 *
 * Like the per-index counters it measures work **performed**, including work a later rollback undoes, because the
 * maintenance was paid either way.
 *
 * # Why this is a separate surface from {@link IndexDetail}
 *
 * A capability is maintained by *many* physical indexes at once - a `filterable()` entity attribute has a filter index
 * in the global index and in every reduced index that carries it. These counts are therefore an **aggregate over all of
 * them**, and no per-index row can carry one without either double counting it across the collection's rows or
 * arbitrarily attributing a collection-wide reading to a single index. Pairing a collection-wide aggregate with one
 * index's cost on the same row also invites the wrong reading, which is the one failure this surface exists to prevent:
 * a flag reported as dead being dropped while a query still depends on it. The two surfaces are read side by side, not
 * merged.
 *
 * # Every declared capability has a row, including one nothing has touched
 *
 * A listing is **complete with respect to the schema**: it carries one row per capability the schema declares and the
 * owner's indexes maintain, from the moment it is declared, whether or not anything has ever queried or written it.
 * A capability nobody touches is therefore reported as `requestedCount == 0`, `updatedCount == 0` and both stamps
 * absent - not omitted.
 *
 * The one exception runs the other way, and is transient: a schema mutation that **added** a capability and was then
 * rolled back leaves its row behind until the owner next adopts any schema version, because the rows are aligned when
 * a version is published rather than when the transaction that published it commits. Such a row reads `0 / 0`, which
 * is the reading a flag nobody uses would give - and acting on it is harmless, since the flag it names does not exist
 * to be dropped.
 *
 * This is what makes a zero readable. **A zero count means the capability was genuinely unused over the window
 * {@link #observedSince()} states**, not merely that nothing has been observed yet - so *"idle"* and *"not declared"*
 * are two different answers rather than the same missing row, and an operator can act on the first without diffing the
 * schema by hand to rule out the second.
 *
 * The completeness is stated over *maintained* capabilities rather than over every flag a schema mentions, and the one
 * place the two differ is the catalog-owned listing: **it carries only what the catalog itself physically maintains**,
 * which is the `FILTER` and `UNIQUE` of the globally-unique attributes its uniqueness index costs, and nothing else.
 * A global attribute's `sortable()`, and the flags of a global attribute that is not globally unique, are maintained by
 * the collections declaring it and are reported in *their* listings. `sortable()` in particular never appears on a
 * catalog row at all, not even after a collection-less `orderBy` names it - the row's update count could never leave
 * zero, and a maintenance count of zero beside a live request count is precisely the *"drop this flag"* misreading this
 * surface exists to prevent.
 *
 * # Lifetime
 *
 * **Since the catalog was loaded, and never persisted** - the same contract {@link ActivityStatistics} carries, for the
 * same reason: the operational use is a rate over an observation window, which persisting would not improve, and
 * {@link #observedSince()} is the denominator that makes a zero count reportable rather than merely unknown. An element
 * dropped from the schema and re-added starts over, with a fresh window, because the capability genuinely was not
 * maintained in between.
 *
 * @param entityType      name of the entity collection whose schema declares the element, or null for a row the catalog
 *                        owns itself - the capabilities of a **globally-unique attribute the catalog schema declares**.
 *                        Those live on the catalog because a query filtering by such an attribute may name no
 *                        collection at all, being served from the catalog's own global unique index, and because
 *                        dropping the flag is a catalog schema mutation. A catalog-owned row always carries
 *                        {@link ElementKind#ATTRIBUTE} and a null {@link #containerName()}: the catalog schema declares
 *                        no references and no compounds
 * @param elementKind     what kind of schema element the row describes - the only thing telling an attribute apart from
 *                        a sortable compound carrying the same name in the same container
 * @param containerName   name of the reference the element is declared on, or null when the entity - or the catalog -
 *                        declares it directly. Attribute names are unique within their owner and not across owners, so
 *                        `priority` on the entity and `priority` on the `categories` reference are routinely both
 *                        present and are different elements
 * @param elementName     name of the attribute or sortable compound itself
 * @param capability      which of the element's flags this row counts
 * @param scope           the scope whose indexes maintain the capability - a flag may be declared for the live data set
 *                        and the archive independently, and so may be dropped from one and kept in the other
 * @param requestedCount  how many logical queries asked for this capability since the catalog was loaded - read the
 *                        section above before acting on it, and never present it as physical index earning
 * @param updatedCount    how many entity mutations touched the element since the catalog was loaded
 * @param lastRequestedAt when the last query asking for this capability was planned, or null when none has since the
 *                        catalog was loaded; see {@link #lastRequestedAtIfKnown()}. **Accurate to the second** - the
 *                        stamp is not rewritten while the recorded instant already falls in the current second, which
 *                        is what keeps a capability requested thousands of times a second down to one store
 * @param lastUpdatedAt   when the last entity mutation touching the element finished applying, or null when none has
 *                        since the catalog was loaded; coarsened exactly like {@link #lastRequestedAt()}
 * @param observedSince   when observation of **this capability** began - catalog load for one the schema already
 *                        declared, the schema mutation itself for one declared later. It is the instant the capability
 *                        came into existence for this server, **not** the instant something first touched it: the
 *                        counters exist from the declaration onwards, so a capability first queried a month after the
 *                        catalog was loaded still reports a month-wide window and a rate computed over it is the real
 *                        one.
 *
 *                        **Never absent**, unlike the two stamps above: a capability has been observed from the moment
 *                        it came into existence, so there is no "not yet" case for an absence to mean. It is the
 *                        denominator the two counts are read against - dividing either by the time elapsed since this
 *                        instant states a lifetime average rate, and it is what qualifies a zero into something
 *                        actionable: *"not requested in the twenty minutes since this flag was added"* is a statement
 *                        an operator can act on, where a bare zero is not
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see IndexDetail
 */
public record SchemaCapabilityUsageSnapshot(
	@Nullable String entityType,
	@Nonnull ElementKind elementKind,
	@Nullable String containerName,
	@Nonnull String elementName,
	@Nonnull Capability capability,
	@Nonnull Scope scope,
	long requestedCount,
	long updatedCount,
	@Nullable OffsetDateTime lastRequestedAt,
	@Nullable OffsetDateTime lastUpdatedAt,
	@Nonnull OffsetDateTime observedSince
) {

	/**
	 * Rejects a half-described row rather than letting it reach an operator - only the two stamps and the two
	 * owner-shaped fields may be absent, and each of those absences carries a meaning of its own.
	 */
	public SchemaCapabilityUsageSnapshot {
		Objects.requireNonNull(elementKind, "Element kind must not be null!");
		Objects.requireNonNull(elementName, "Element name must not be null!");
		Objects.requireNonNull(capability, "Capability must not be null!");
		Objects.requireNonNull(scope, "Scope must not be null!");
		Objects.requireNonNull(observedSince, "Observation window must not be null!");
	}

	/**
	 * When the last query asking for this capability was planned.
	 *
	 * **Empty means "not since the catalog was loaded"**, never "never" - the counters and their stamps are reset by a
	 * catalog load, so a capability queried for months reports empty here on a freshly started server.
	 *
	 * **It does not imply {@link #requestedCount()}, in either direction.** A row is assembled field by field from
	 * readings that advance independently: a recording increments the count and *then* writes the stamp, and the stamp
	 * is skipped altogether while the resident value already falls in the current second. A row taken while a
	 * capability's very first request is being recorded can therefore carry either reading without the other.
	 *
	 * @return when this capability was last requested, empty when it has not been since the catalog was loaded
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastRequestedAtIfKnown() {
		return Optional.ofNullable(this.lastRequestedAt);
	}

	/**
	 * When the last entity mutation touching the element finished applying.
	 *
	 * **Empty means "not since the catalog was loaded"** - see {@link #lastRequestedAtIfKnown()}.
	 *
	 * @return when the element was last touched by a mutation, empty when it has not been since the catalog was loaded
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastUpdatedAtIfKnown() {
		return Optional.ofNullable(this.lastUpdatedAt);
	}

	/**
	 * What kind of schema element a row describes. Deliberately **not** split by owner - an entity attribute and a
	 * reference attribute are the same kind of thing declared in two places, and {@link #containerName()} already says
	 * which place. What this separates is the two things that would otherwise be indistinguishable: an attribute and a
	 * sortable compound may carry the same name in the same container.
	 *
	 * The vocabulary lives here, on the public surface that reports it, rather than beside the engine-internal counters
	 * that feed it - one enum the engine's key, this row and the wire all speak, so no two of them can drift apart.
	 */
	public enum ElementKind {

		/**
		 * An attribute, of the entity, of one of its references, or of the catalog schema itself.
		 */
		ATTRIBUTE,

		/**
		 * A sortable attribute compound, of the entity or of one of its references.
		 */
		SORTABLE_COMPOUND

	}

	/**
	 * One flag of a schema element, and one line of maintenance cost the workload either justifies or does not.
	 *
	 * **Not to be confused with {@link AttributeIndexType}**, whose three values carry the same names. That one names a
	 * *physical structure* a cardinality reading came from; this one names a *schema flag* an operator can drop. The
	 * distinction is the whole point of this surface - see the record documentation.
	 *
	 * The values are exactly the flags a request can currently be attributed to; prices, facets and hierarchy are
	 * maintained by their own structures and reached through their own query paths, so they get their own values when
	 * the accumulation sites that can report them exist - not before.
	 */
	public enum Capability {

		/**
		 * The element can be filtered by - `filterable()`, and the inverted indexes it costs. A `unique()` attribute
		 * carries this capability too, because uniqueness implies filterability and a filter is served from the
		 * uniqueness index.
		 */
		FILTER,

		/**
		 * The element can be ordered by - `sortable()`, and the sorted record arrays it costs.
		 */
		SORT,

		/**
		 * The element's values are unique - `unique()`, whether within the entity collection or globally, and the
		 * uniqueness index it costs.
		 */
		UNIQUE

	}

}
