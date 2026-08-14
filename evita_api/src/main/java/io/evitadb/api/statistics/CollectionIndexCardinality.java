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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The {@link CatalogStatisticsComponent#INDEX_CARDINALITY} component of one entity collection - how many *distinct
 * values* each of its indexes holds, next to how many records those values cover.
 *
 * This is the statistic that answers **"is this index earning its keep, or is it three distinct values over two
 * million records?"** - a question neither the index count ({@link CollectionIndexSummary}) nor the record count can
 * answer, because both are blind to selectivity. Paired with the schema, which a management client already holds, it
 * turns "this collection has 412 indexes" into "these four are doing nothing".
 *
 * **Only the schema-bounded indexes are described, and that is the whole design**
 *
 * A collection holds one {@link EntityIndexType#GLOBAL} index per scope, one
 * {@link EntityIndexType#REFERENCED_ENTITY_TYPE} / {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} index per
 * reference schema per scope - and one {@link EntityIndexType#REFERENCED_ENTITY} /
 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY} index per *referenced entity*, of which there can be tens of
 * thousands. The first group is bounded by the schema; the second grows with the data.
 *
 * Describing the second group would make this response's size grow with the catalog's data volume, multiplied again by
 * the attributes indexed within each one. Those indexes are therefore **counted but not described**: their number is in
 * {@link #omittedIndexCount()}, and {@link CollectionIndexSummary} already reports how they split by kind and scope.
 * Nothing is lost analytically - a per-referenced-entity index covers the records referencing one entity, so its
 * selectivity is a property of the reference, which the `REFERENCED_ENTITY_TYPE` index above it already summarises.
 *
 * **Cost**
 *
 * Every reading here is an `O(1)` counter read except the number of records **covered** by a *filter* index, which
 * sums the record counts of the index's buckets and is therefore `O(distinct values)`. A unique index is not the
 * expensive one, despite the intuition: its covered-record count is a bitmap cardinality and its distinct-value count
 * a cached bucket counter, neither of which walks anything.
 *
 * How much the filter index costs depends on the attribute, and the two ends are far apart. For a low-cardinality
 * filterable attribute it is a handful of steps - cheap precisely in the pathological case this component exists to
 * find. For a near-unique one it approaches one step per record, because there is a bucket per distinct value: a
 * filterable SKU or e-mail on a collection of two million entities makes reading its covered-record count a
 * two-million-step walk. An attribute declared unique reaches that cost through the filter index it also carries,
 * never through its unique index.
 *
 * That is the reason this component is classified as expensive and must never join a polled refresh - together with
 * the response size, which is what keeps the data-bounded indexes undescribed above.
 *
 * @param indexes           one entry per described index, in no guaranteed order; indexes holding no attribute index
 *                          and no reference cardinality are omitted rather than reported empty
 * @param omittedIndexCount how many of this collection's indexes were counted but not described, for the reason above;
 *                          `0` means every index the collection holds is present in `indexes`
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CollectionIndexSummary
 */
public record CollectionIndexCardinality(
	@Nonnull IndexCardinality[] indexes,
	int omittedIndexCount
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final CollectionIndexCardinality that = (CollectionIndexCardinality) o;
		return this.omittedIndexCount == that.omittedIndexCount &&
			Arrays.equals(this.indexes, that.indexes);
	}

	@Override
	public int hashCode() {
		return 31 * this.omittedIndexCount + Arrays.hashCode(this.indexes);
	}

	@Nonnull
	@Override
	public String toString() {
		return "CollectionIndexCardinality{indexes=" + Arrays.toString(this.indexes) +
			", omittedIndexCount=" + this.omittedIndexCount + '}';
	}

	/**
	 * The cardinality readings of one index of the collection.
	 *
	 * @param indexType             kind of this index, or `null` when it is a catalog index rather than an entity
	 *                              index - see {@link BrowsedIndex#indexType()}. This component never produces one,
	 *                              since it describes a single collection; an {@link IndexDetail} does
	 * @param scope                 scope this index belongs to
	 * @param discriminator         what distinguishes this index from its siblings of the same kind, rendered exactly
	 *                              as {@link BrowsedIndex#discriminator()} renders it - the reference name for the
	 *                              schema-bounded reference indexes this component describes, the full rendering
	 *                              including representative attribute values when an {@link IndexDetail} describes a
	 *                              per-referenced-entity index, and `null` for the {@link EntityIndexType#GLOBAL}
	 *                              index, which has no sibling within its scope, or for a catalog index, of which
	 *                              there is one per scope
	 * @param entityCount           how many entities this index covers - the denominator every distinct-value count
	 *                              below should be read against - or `null` for a catalog index, which maintains no
	 *                              primary-key bitmap to read one off; see {@link #entityCountIfKnown()}
	 * @param referencedEntityCount how many distinct referenced entities this index tracks, or `null` for an index
	 *                              that tracks none; see {@link #referencedEntityCountIfKnown()}
	 * @param attributes            one entry per attribute index held by this index, in no guaranteed order
	 */
	public record IndexCardinality(
		@Nullable EntityIndexType indexType,
		@Nonnull Scope scope,
		@Nullable String discriminator,
		@Nullable Integer entityCount,
		@Nullable Integer referencedEntityCount,
		@Nonnull AttributeCardinality[] attributes
	) {

		/**
		 * The number of entities this index covers, when it covers entities at all.
		 *
		 * **Empty is a statement about the owner, not a missing measurement** - the same one
		 * {@link BrowsedIndex#entityCountIfKnown()} makes, and for the same reason: a catalog index has no
		 * primary-key bitmap, and `0` would read as "this index covers nothing".
		 *
		 * @return how many entities the index covers, empty for a catalog index
		 */
		@Nonnull
		public OptionalInt entityCountIfKnown() {
			return this.entityCount == null ? OptionalInt.empty() : OptionalInt.of(this.entityCount);
		}

		/**
		 * The number of distinct referenced entities this index tracks, when it tracks any.
		 *
		 * **Empty is a statement about the index kind, not a missing measurement.** Only the reference indexes
		 * maintain a reference cardinality; the {@link EntityIndexType#GLOBAL} index has no reference dimension, and
		 * reporting `0` for it would read as "this collection references nothing".
		 *
		 * @return the tracked referenced entity count, empty for an index that tracks no references
		 */
		@Nonnull
		public OptionalInt referencedEntityCountIfKnown() {
			return this.referencedEntityCount == null ?
				OptionalInt.empty() : OptionalInt.of(this.referencedEntityCount);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final IndexCardinality that = (IndexCardinality) o;
			return Objects.equals(this.entityCount, that.entityCount) &&
				this.indexType == that.indexType &&
				this.scope == that.scope &&
				Objects.equals(this.discriminator, that.discriminator) &&
				Objects.equals(this.referencedEntityCount, that.referencedEntityCount) &&
				Arrays.equals(this.attributes, that.attributes);
		}

		@Override
		public int hashCode() {
			int result = this.indexType == null ? 0 : this.indexType.hashCode();
			result = 31 * result + this.scope.hashCode();
			result = 31 * result + (this.discriminator == null ? 0 : this.discriminator.hashCode());
			result = 31 * result + (this.entityCount == null ? 0 : this.entityCount.hashCode());
			result = 31 * result + (this.referencedEntityCount == null ? 0 : this.referencedEntityCount.hashCode());
			return 31 * result + Arrays.hashCode(this.attributes);
		}

		@Nonnull
		@Override
		public String toString() {
			return "IndexCardinality{indexType=" + this.indexType +
				", scope=" + this.scope +
				", discriminator=" + this.discriminator +
				", entityCount=" + this.entityCount +
				", referencedEntityCount=" + this.referencedEntityCount +
				", attributes=" + Arrays.toString(this.attributes) + '}';
		}

	}

	/**
	 * The cardinality readings of one attribute index within one entity index.
	 *
	 * @param attributeName     name of the indexed attribute
	 * @param referenceName     name of the reference the attribute is defined on, `null` for an entity-level attribute
	 * @param locale            locale of the indexed values, `null` when the attribute is not localized - a localized
	 *                          attribute has one index per locale and each is reported separately, because their
	 *                          selectivities genuinely differ
	 * @param indexType         which of the attribute's index structures this reading describes
	 * @param distinctValueCount how many distinct values the structure holds
	 * @param recordsCovered    how many records those values cover between them, as the index's own membership set
	 *                          reports it; `recordsCovered` divided by `distinctValueCount` is the average number of
	 *                          records sharing one value, and a large quotient is what "this index is not earning its
	 *                          keep" looks like.
	 *
	 *                          **For {@link AttributeIndexType#UNIQUE} this is the engine's membership bitmap, and it
	 *                          can read lower than the number of records that still hold a value.** One record owns
	 *                          several values in a single unique index when the attribute is localized *and* unique
	 *                          globally, because that combination has one locale-less key covering every locale. The
	 *                          bitmap is an eager cache that drops a record on the **first** of its values removed and
	 *                          nothing re-adds it while siblings remain, so removing one locale leaves the record
	 *                          uncounted here even though the index still holds its other locales.
	 *
	 *                          Reported as-is deliberately: this bitmap is what the engine itself queries the index
	 *                          through, so a separately-computed "truer" figure would describe an index the engine
	 *                          does not have. Read it as *the index's membership as the engine sees it*, and use
	 *                          `distinctValueCount` when the question is how much the index actually holds.
	 */
	public record AttributeCardinality(
		@Nonnull String attributeName,
		@Nullable String referenceName,
		@Nullable Locale locale,
		@Nonnull AttributeIndexType indexType,
		int distinctValueCount,
		int recordsCovered
	) {
	}

}
