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
 * A collection holds one {@link EntityIndexKind#GLOBAL} index per scope, one
 * {@link EntityIndexKind#REFERENCED_ENTITY_TYPE} / {@link EntityIndexKind#REFERENCED_GROUP_ENTITY_TYPE} index per
 * reference schema per scope - and one {@link EntityIndexKind#REFERENCED_ENTITY} /
 * {@link EntityIndexKind#REFERENCED_GROUP_ENTITY} index per *referenced entity*, of which there can be tens of
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
 * Every reading here is an `O(1)` counter read except the number of records covered by a *filter* index, which sums
 * the record counts of the index's buckets and is therefore `O(distinct values)` - cheap precisely in the pathological
 * case this component exists to find, and proportional to the reading itself in every other. The component is still
 * classified as expensive and must never join a polled refresh: it is the *response size* and the walk over index keys,
 * not any single counter, that make it so.
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
	 * @param indexKind             kind of this index
	 * @param scope                 scope this index belongs to
	 * @param discriminator         what distinguishes this index from its siblings of the same kind - the reference
	 *                              name for a reference index, `null` for the {@link EntityIndexKind#GLOBAL} index,
	 *                              which has no sibling within its scope
	 * @param entityCount           how many entities this index covers - the denominator every distinct-value count
	 *                              below should be read against
	 * @param referencedEntityCount how many distinct referenced entities this index tracks, or `null` for an index
	 *                              that tracks none; see {@link #referencedEntityCountIfKnown()}
	 * @param attributes            one entry per attribute index held by this index, in no guaranteed order
	 */
	public record IndexCardinality(
		@Nonnull EntityIndexKind indexKind,
		@Nonnull Scope scope,
		@Nullable String discriminator,
		int entityCount,
		@Nullable Integer referencedEntityCount,
		@Nonnull AttributeCardinality[] attributes
	) {

		/**
		 * The number of distinct referenced entities this index tracks, when it tracks any.
		 *
		 * **Empty is a statement about the index kind, not a missing measurement.** Only the reference indexes
		 * maintain a reference cardinality; the {@link EntityIndexKind#GLOBAL} index has no reference dimension, and
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
			return this.entityCount == that.entityCount &&
				this.indexKind == that.indexKind &&
				this.scope == that.scope &&
				Objects.equals(this.discriminator, that.discriminator) &&
				Objects.equals(this.referencedEntityCount, that.referencedEntityCount) &&
				Arrays.equals(this.attributes, that.attributes);
		}

		@Override
		public int hashCode() {
			int result = this.indexKind.hashCode();
			result = 31 * result + this.scope.hashCode();
			result = 31 * result + (this.discriminator == null ? 0 : this.discriminator.hashCode());
			result = 31 * result + this.entityCount;
			result = 31 * result + (this.referencedEntityCount == null ? 0 : this.referencedEntityCount.hashCode());
			return 31 * result + Arrays.hashCode(this.attributes);
		}

		@Nonnull
		@Override
		public String toString() {
			return "IndexCardinality{indexKind=" + this.indexKind +
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
	 * @param recordsCovered    how many records those values cover between them; `recordsCovered` divided by
	 *                          `distinctValueCount` is the average number of records sharing one value, and a large
	 *                          quotient is what "this index is not earning its keep" looks like
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
