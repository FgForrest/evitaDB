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

package io.evitadb.core.collection;

import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
// the engine's own index-family discriminator, deliberately kept qualified: `AttributeIndexType` in this file is the
// API-facing enum this projection reports, and the two are distinct types that must not be confused at a call site
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects an entity collection's live indexes into the {@link CollectionIndexCardinality} component. Contains no
 * arithmetic of its own beyond counting - every figure it reports is read straight off an index structure - so the one
 * decision it makes is *which* indexes to describe.
 *
 * **Only the schema-bounded index kinds are described.** A collection holds one
 * {@link EntityIndexType#GLOBAL} index per scope and one {@link EntityIndexType#REFERENCED_ENTITY_TYPE} /
 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} index per reference schema per scope - all bounded by the
 * schema. It also holds one index per *referenced entity* and per *hierarchy node*, and those grow with the data:
 * describing them would make the response size a function of the catalog's contents, multiplied again by the
 * attributes indexed within each. They are counted into
 * {@link CollectionIndexCardinality#omittedIndexCount()} instead.
 *
 * **The omitted indexes are never even visited.** Production collections reach hundreds of thousands of
 * per-referenced-entity indexes, so walking the index map to sort the schema-bounded ones out would cost one map
 * iteration - and, since `ChampMap.entrySet()` materialises an entry object per element, one throwaway allocation -
 * per index on every request, to discard almost all of them. The described keys are instead *constructed* from the
 * schema and looked up individually (`O(1)` each), and the omitted count falls out of `size()` (also `O(1)`) minus
 * what was found. The cost is therefore proportional to the **schema**, not to the data: a collection with two
 * indexes and one with two million pay the same.
 *
 * A consequence worth knowing: an index whose reference name is no longer in the schema - a reference dropped whose
 * index has not been reclaimed yet - is counted as omitted rather than described. That is the accurate reading; it is
 * not a live part of the schema any more.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CollectionIndexCardinality
 */
final class IndexCardinalityProjection {

	private IndexCardinalityProjection() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Describes the cardinality of every schema-bounded index the collection holds.
	 *
	 * **The map must be one stable state, not the live one.** The lookups below and the `size()` they are subtracted
	 * from are separate reads; taken against a map a warm-up writer is still mutating, a removal in between makes
	 * `omittedIndexCount` negative. The caller passes a snapshot for exactly this reason.
	 *
	 * @param indexes        a sealed snapshot of the collection's index map, never the live one
	 * @param referenceNames names of the reference schemas the collection declares, which is what bounds the number
	 *                       of reference indexes that can be described
	 * @return the {@link io.evitadb.api.statistics.CatalogStatisticsComponent#INDEX_CARDINALITY} component
	 */
	@Nonnull
	static CollectionIndexCardinality describe(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull Set<String> referenceNames
	) {
		final Scope[] scopes = Scope.values();
		final List<IndexCardinality> described =
			new ArrayList<>(scopes.length * (1 + 2 * referenceNames.size()));
		for (final Scope scope : scopes) {
			describeIfPresent(indexes, new EntityIndexKey(EntityIndexType.GLOBAL, scope), described);
			for (final String referenceName : referenceNames) {
				describeIfPresent(
					indexes,
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName),
					described
				);
				describeIfPresent(
					indexes,
					new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName),
					described
				);
			}
		}
		return new CollectionIndexCardinality(
			described.toArray(IndexCardinality[]::new),
			// everything the targeted lookups did not find: the per-referenced-entity, per-hierarchy-node and
			// per-group-entity indexes, whose number grows with the data. Both terms are `O(1)` on the snapshot the
			// caller passed, and cannot go negative because they read the same state
			indexes.size() - described.size()
		);
	}

	/**
	 * Describes the index under `indexKey` when the collection actually holds one.
	 *
	 * A miss is the ordinary case rather than an error - a reference declared but never indexed has no index, and
	 * neither does a scope nothing has been written into yet.
	 *
	 * @param indexes   the collection's live index map
	 * @param indexKey  key of the index to describe
	 * @param described accumulator the readings are appended to
	 */
	private static void describeIfPresent(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull EntityIndexKey indexKey,
		@Nonnull List<IndexCardinality> described
	) {
		final EntityIndex entityIndex = indexes.get(indexKey);
		if (entityIndex != null) {
			described.add(describeIndex(indexKey, describeDiscriminator(indexKey), entityIndex));
		}
	}

	/**
	 * Describes one index - its coverage, its reference cardinality when it maintains one, and the cardinality of
	 * every attribute index it holds.
	 *
	 * **A key that no longer resolves is skipped, and that is the accurate answer rather than a silent omission.**
	 * Each attribute index is read as a key-set walk followed by a lookup per key, and the two are separate reads of
	 * a live structure: the bulk-load path mutates its indexes in place (see `SortIndex`'s class javadoc), so a key
	 * present when the set was taken can be gone by the time it is resolved. Reporting it would mean inventing
	 * readings for an index that does not exist; throwing would kill a statistics call over a benign race.
	 *
	 * The discriminator is handed in rather than derived here, because the two callers legitimately render it
	 * differently: this component describes only the schema-bounded kinds, whose discriminator *is* a reference name
	 * and whose premise {@link #describeDiscriminator} asserts, while {@link IndexDetailProjection} describes any
	 * single index and needs the full rendering `BrowsedIndex` carries. Deriving it here would force one of the two
	 * to be wrong.
	 *
	 * @param indexKey      key identifying the index
	 * @param discriminator rendered discriminator of the index, or null for one that carries none
	 * @param entityIndex   the index itself
	 * @return the readings of this one index
	 */
	@Nonnull
	static IndexCardinality describeIndex(
		@Nonnull EntityIndexKey indexKey,
		@Nullable String discriminator,
		@Nonnull EntityIndex entityIndex
	) {
		final List<AttributeCardinality> attributes = new ArrayList<>(16);
		// `forEachAttributeIndexKey` rather than the set-returning accessors: those hand out a map view the backing
		// map then keeps, and this call would leave one on every index it describes - see `TransactionalMap#forEach`
		entityIndex.forEachAttributeIndexKey(AttributeIndexStoragePart.AttributeIndexType.UNIQUE, key -> {
			final UniqueIndex uniqueIndex = entityIndex.getUniqueIndex(key);
			if (uniqueIndex != null) {
				// `size()` is the membership bitmap, which under-counts a record owning several values in one index -
				// a localized attribute that is also unique globally has one locale-less key, and the bitmap drops the
				// record on the first of its values removed. Reported anyway, and documented on `AttributeCardinality`:
				// this bitmap is what the engine queries the index through, so substituting a separately-computed
				// count here would describe an index the engine does not have
				attributes.add(
					toAttributeCardinality(
						key, AttributeIndexType.UNIQUE, uniqueIndex.getDistinctValueCount(), uniqueIndex.size()
					)
				);
			}
		});
		entityIndex.forEachAttributeIndexKey(AttributeIndexStoragePart.AttributeIndexType.FILTER, key -> {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(key);
			if (filterIndex != null) {
				attributes.add(
					toAttributeCardinality(
						key, AttributeIndexType.FILTER, filterIndex.getDistinctValueCount(), filterIndex.size()
					)
				);
			}
		});
		entityIndex.forEachAttributeIndexKey(AttributeIndexStoragePart.AttributeIndexType.SORT, key -> {
			final SortIndex sortIndex = entityIndex.getSortIndex(key);
			if (sortIndex != null) {
				attributes.add(
					toAttributeCardinality(
						key, AttributeIndexType.SORT, sortIndex.getDistinctValueCount(), sortIndex.size()
					)
				);
			}
		});
		return new IndexCardinality(
			indexKey.type(),
			indexKey.scope(),
			discriminator,
			entityIndex.getAllPrimaryKeys().size(),
			entityIndex instanceof ReferencedTypeEntityIndex referencedTypeIndex ?
				referencedTypeIndex.getAllTrackedReferencedEntityPrimaryKeys().size() : null,
			attributes.toArray(AttributeCardinality[]::new)
		);
	}

	/**
	 * Renders the index discriminator for a response. Only the schema-bounded reference indexes carry one, and theirs
	 * is always the reference name.
	 *
	 * @param indexKey key identifying the index
	 * @return the reference name, or null for the {@link EntityIndexType#GLOBAL} index
	 */
	@Nullable
	private static String describeDiscriminator(@Nonnull EntityIndexKey indexKey) {
		if (indexKey.type() == EntityIndexType.GLOBAL) {
			return null;
		}
		final Object discriminator = indexKey.discriminator();
		Assert.isPremiseValid(
			discriminator instanceof String,
			() -> "Reference index `" + indexKey + "` is expected to be discriminated by its reference name!"
		);
		return (String) discriminator;
	}

	/**
	 * Assembles the readings of one attribute index.
	 *
	 * @param key                key identifying the attribute index within its owning index
	 * @param indexType          which structure the readings came from
	 * @param distinctValueCount how many distinct values the structure holds
	 * @param recordsCovered     how many records those values cover between them
	 * @return the readings of this one attribute index
	 */
	@Nonnull
	private static AttributeCardinality toAttributeCardinality(
		@Nonnull AttributeIndexKey key,
		@Nonnull AttributeIndexType indexType,
		int distinctValueCount,
		int recordsCovered
	) {
		return new AttributeCardinality(
			key.attributeName(), key.referenceName(), key.locale(), indexType, distinctValueCount, recordsCovered
		);
	}

}
