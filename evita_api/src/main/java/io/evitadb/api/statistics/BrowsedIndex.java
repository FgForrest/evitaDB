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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One index, as described by an index browse.
 *
 * {@link CollectionIndexSummary} answers *how many* indexes of each kind a collection holds; this answers *which ones*,
 * one page at a time. It exists because a count of forty thousand `REFERENCED_ENTITY` indexes tells an operator that
 * something is wrong but not which reference caused it.
 *
 * **One record shape describes both an entity collection's indexes and the catalog's own.** A catalog index - the
 * globally-unique attribute index there is one of per {@link Scope} - is browsed through the same call, filtered by the
 * same criteria and rendered into the same row as a collection's, so a client holds one code path rather than two. What
 * a catalog index does *not* have is stated by absence rather than by a stand-in value: {@link #entityType()} is null,
 * and with it {@link #indexType()}, {@link #entityCount()} and all three discriminator fields.
 *
 * **Identity is the pair {@link #entityType()} + {@link #indexPrimaryKey()}.** The integer alone identifies the index
 * within its owner, and the same value under another owner is another index entirely - a catalog index and some
 * collection's first index both answer to `0`. Treat the integer as an **opaque handle**: it means nothing on its own,
 * and it is what a client compares, deduplicates on and hands back to ask about one index in particular.
 *
 * The two owners derive it differently, and the difference is visible to a client that holds a handle across a removal:
 *
 * - A **collection** assigns it from a forward-only sequence whose high-water mark is persisted, so it is **never
 *   reused** and a row held across the index's removal can only fail to resolve, never resolve to something else.
 * - The **catalog** derives it from the index's {@link Scope}, so it denotes the same logical index whether or not that
 *   index exists right now. The `ARCHIVED` catalog index is created lazily, so its handle can fail to resolve and later
 *   *start* resolving - to the same logical index it always denoted, never to a different one.
 *
 * Everything else here is for a human to read. In particular {@link #referenceName()} and
 * {@link #discriminatorPrimaryKey()} do not identify an index *between them*: two distinct indexes can agree on both,
 * because a reference whose targets are told apart by representative attribute values has one index per distinct value
 * set, all sharing one reference name and one target primary key. {@link #discriminator()} does tell such a pair apart
 * and is what to display, but the key to compare is the pair above.
 *
 * How the readable parts are populated:
 *
 * - A **catalog** index carries no discriminator at all - all three fields are null, as is {@link #indexType()}.
 * - `GLOBAL` indexes carry no discriminator either - all three fields are null.
 * - The per-reference-*type* kinds carry a reference name and no primary key: one index covers the whole reference.
 * - The per-referenced-*entity* kinds carry both, plus whatever else distinguishes the target, which is why
 *   {@link #discriminator()} rather than the pair is the one to show.
 *
 * @param entityType              name of the entity collection holding this index, or null for an index the catalog
 *                                holds directly; carried on the row rather than left to the caller's memory of what it
 *                                asked for, so a client concatenating a catalog browse and a collection browse into one
 *                                table still has the other half of each row's identity
 * @param indexPrimaryKey         identity of this index within its owner, and the handle to pass back when asking about
 *                                it; opaque and owner-scoped - see above
 * @param indexType               kind of the index, or null for a catalog index. **Null is a statement about the
 *                                owner, not a missing reading**: the engine addresses catalog indexes by scope alone,
 *                                because there is exactly one kind of them, and no value of this enum describes one.
 *                                Should catalog-level indexes ever diversify they get their own enum rather than a
 *                                constant here that every entity-index switch would have to reject
 * @param scope                   scope the index belongs to
 * @param discriminator           stable rendering of everything that distinguishes this index from its siblings of the
 *                                same kind and scope, or null for an index that has no siblings to be told apart from -
 *                                a `GLOBAL` index, or a catalog index, of which there is one per scope; display it, but
 *                                compare the identity pair above
 * @param referenceName           name of the reference this index is bound to, or null for an index that is bound to no
 *                                reference; not unique on its own
 * @param discriminatorPrimaryKey primary key of the referenced entity this index is bound to, or null when the index
 *                                covers a whole reference type rather than one target entity; not unique on its own
 * @param entityCount             how many entities the index covers - a cardinality reading of the index's primary-key
 *                                bitmap, never a walk of its contents - or null for a catalog index, which has no such
 *                                bitmap; see {@link #entityCountIfKnown()}.
 *
 *                                **It counts entities, and is not a stand-in for how much memory the index occupies.**
 *                                Heap is driven by how many attributes are indexed and how many distinct values they
 *                                hold, which no entity count can see: on a measured production catalog a `GLOBAL` index
 *                                ran ~8.7 KB per entity against ~2.4 KB for a large `REFERENCED_ENTITY` one. Nothing
 *                                derived from this number is reported as a memory figure, deliberately - the ratio is a
 *                                property of the catalog's own schema and data, so any coefficient applied to it here
 *                                would be a number wrong in an unknown direction on every other dataset.
 * @param queryCount              how many executed query plans have chosen this index as part of their winning target
 *                                index set.
 *
 *                                **It counts *chosen*, not *consulted*.** Planning also probes candidate indexes that
 *                                lose the cost comparison, reaches a collection's super price index from a
 *                                reduced-index plan, and pulls referenced-entity indexes to enrich what is fetched -
 *                                none of that is counted here. The reading means *"this index was the filtering
 *                                backbone of an executed query"*, which is what makes it actionable; counting every
 *                                consultation would inflate the losers and say nothing about what to drop.
 *
 *                                **Counted since the catalog was loaded, and never persisted** - see
 *                                {@link #updateCount()} for why, and read it as a rate over the observation window
 *                                rather than as a lifetime total.
 * @param updateCount             how many entity mutations have acquired this index for modification - one increment
 *                                per entity mutation per index, never per attribute write.
 *
 *                                **It counts work performed, including work a rollback later undoes.** The increment
 *                                happens when the mutation finishes applying, before the commit-or-rollback decision,
 *                                deliberately: a rolled-back transaction still *paid* the index-maintenance cost, and
 *                                this reading measures that cost rather than surviving state.
 *
 *                                **A `GLOBAL` index is acquired by essentially every entity mutation**, so its reading
 *                                is close to the collection's total mutation count. That is accurate rather than
 *                                misleading - a global index is never a drop candidate. The actionable readings are
 *                                the ones on reduced indexes, which are acquired only when genuinely touched.
 *
 *                                **Maintenance driven by a cross-entity trigger is not counted at all.** A mutation of
 *                                one collection can maintain another collection's indexes: the dispatch runs through
 *                                `EntityCollection#applyIndexMutations` into the executors registered in
 *                                `IndexMutationExecutorRegistry`, which acquire what they write straight from the
 *                                collection's `EntityIndexMaintainer` - never through the entity mutation's own
 *                                executor, which is what collects the acquisitions this reading counts. An index
 *                                maintained mostly by writes to a *different* collection therefore reports less
 *                                maintenance than it performs; read its number as a floor.
 *
 *                                **A live catalog counts one write more than once, and a warming one does not.** A
 *                                transactional mutation is applied to the index in the writing session's isolated
 *                                layer and applied again when the trunk is incorporated from the write-ahead log, so
 *                                both passes count - because both are maintenance genuinely performed. Compare
 *                                indexes against each other rather than against an expected mutation count.
 *
 *                                **Counted since the catalog was loaded, and never persisted.** Both counters and both
 *                                stamps reset when a catalog is loaded, exactly as {@link ActivityStatistics} counts
 *                                "since this instance was created": their operational use is a rate over an
 *                                observation window, which persisting them would not improve, while a hot mutable
 *                                value in an index's manifest would cost a rewrite on every commit.
 * @param lastQueriedAt           when the last query that chose this index was planned, or null when no query has
 *                                chosen it since the catalog was loaded; see {@link #lastQueriedAtIfKnown()}
 * @param lastUpdatedAt           when the last entity mutation that acquired this index finished applying, or null
 *                                when none has since the catalog was loaded; see {@link #lastUpdatedAtIfKnown()}
 * @param observedSince           when observation of **this** index began - the moment its activity holder was
 *                                constructed, which is catalog load for an index restored from disk and first creation
 *                                for one born later.
 *
 *                                **Always present, unlike the two stamps above** - there is no absence to decode,
 *                                because an index has been observed since the moment it came into existence.
 *
 *                                **It is the denominator the two counts are read against.** Dividing either by the
 *                                time elapsed since this instant states a lifetime average rate, and it is what
 *                                qualifies a zero count into something actionable: *"not queried in the twenty
 *                                minutes since this index was created"* is a statement an operator can act on, where
 *                                a bare zero is not.
 *
 *                                **Per index, deliberately not per catalog load.** An index created hours after the
 *                                catalog opened was not observable before it existed, so billing it the catalog's
 *                                window would make its zero count read as a far stronger statement than it is.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see IndexBrowseCriteria
 * @see CollectionIndexSummary
 */
public record BrowsedIndex(
	@Nullable String entityType,
	int indexPrimaryKey,
	@Nullable EntityIndexType indexType,
	@Nonnull Scope scope,
	@Nullable String discriminator,
	@Nullable String referenceName,
	@Nullable Integer discriminatorPrimaryKey,
	@Nullable Integer entityCount,
	long queryCount,
	long updateCount,
	@Nullable OffsetDateTime lastQueriedAt,
	@Nullable OffsetDateTime lastUpdatedAt,
	@Nonnull OffsetDateTime observedSince
) {

	public BrowsedIndex {
		Objects.requireNonNull(scope, "Scope must not be null!");
		// checked rather than annotated only: this is the denominator a client divides the two counts by, and a null
		// reaching a caller would turn "how often is this index queried" into a null pointer at the reading site
		Objects.requireNonNull(observedSince, "Observation window must not be null!");
		// the two nulls travel together by construction - only a catalog index lacks an owning collection, and only a
		// catalog index lacks an entity-index kind. Checked rather than assumed because a converter that dropped one
		// field would otherwise produce a row describing an index that cannot exist
		Assert.isPremiseValid(
			(entityType == null) == (indexType == null),
			() -> "An index is either a collection's, carrying both an entity type and a kind, or the catalog's, " +
				"carrying neither - but `" + entityType + "` / `" + indexType + "` is neither shape!"
		);
	}

	/**
	 * The number of entities this index covers, when it covers entities at all.
	 *
	 * **Empty is a statement about the owner, not a missing measurement.** A catalog index holds globally-unique
	 * attribute values pointing at entities of *any* collection, and maintains no primary-key bitmap to take a
	 * cardinality of; reporting `0` for it would read as "this index covers nothing". What one holds is reported where
	 * it is not misnamed - as the per-attribute distinct-value and covered-record counts an
	 * {@link IndexDetail} carries.
	 *
	 * @return how many entities the index covers, empty for a catalog index
	 */
	@Nonnull
	public OptionalInt entityCountIfKnown() {
		return this.entityCount == null ? OptionalInt.empty() : OptionalInt.of(this.entityCount);
	}

	/**
	 * When the last query that chose this index was planned.
	 *
	 * **Empty means "not since the catalog was loaded"**, never "never" - the counters and their stamps are reset by a
	 * catalog load, so an index that has served queries for months reports empty here on a freshly started server.
	 *
	 * **It does not imply {@link #queryCount()}, in either direction.** A row is a snapshot taken field by field: a
	 * recording advances the count and *then* writes the stamp, while the projection rendering the row reads the count
	 * *first*, so a row taken while an index's very first query is being recorded can carry either reading without the
	 * other. On an index nothing is recording into, the two always agree - that is what makes them readable side by
	 * side, and it is not a guarantee to assert on.
	 *
	 * @return when this index was last chosen by a query, empty when it has not been since the catalog was loaded
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastQueriedAtIfKnown() {
		return Optional.ofNullable(this.lastQueriedAt);
	}

	/**
	 * When the last entity mutation that acquired this index for modification finished applying.
	 *
	 * **Empty means "not since the catalog was loaded"** - see {@link #lastQueriedAtIfKnown()}.
	 *
	 * @return when this index was last updated, empty when it has not been since the catalog was loaded
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastUpdatedAtIfKnown() {
		return Optional.ofNullable(this.lastUpdatedAt);
	}

}
