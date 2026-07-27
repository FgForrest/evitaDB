/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Map;

/**
 * Concrete [AttributeIndex] specialization owned by reference-tracking entity indexes:
 *
 * - [io.evitadb.index.ReducedEntityIndex] — keyed by a
 *   [RepresentativeReferenceKey] discriminator
 * - [io.evitadb.index.ReducedGroupEntityIndex] — keyed by a
 *   [RepresentativeReferenceKey] discriminator with a group primary key
 * - [io.evitadb.index.ReferencedTypeEntityIndex] — keyed by a raw reference name (no
 *   [RepresentativeReferenceKey]); the `referenceKey` argument is `null` for this subclass
 *
 * Because of the [io.evitadb.index.ReferencedTypeEntityIndex] case the constructor permits a
 * `null` `referenceKey` — the structural invariant is **scope**, not **representative key
 * non-nullness**.
 *
 * Within a reference-attribute index both reference-defined and entity-defined attribute schemas
 * can appear (entity-level attributes are fanned out into reduced indexes for grouped references
 * via [io.evitadb.index.mutation.local.ReferenceIndexMutator]). The base class' key-construction
 * helper handles the resulting null-vs-non-null reference name discrimination uniformly, so no
 * additional invariant is enforced at construction time.
 *
 * Subclass identity is reconstructed from `EntityIndexKey.discriminator()` on reload, so the
 * Kryo registry continues to register the parent [AttributeIndex] type only.
 */
@ThreadSafe
public final class ReferenceAttributeIndex extends AttributeIndex {

	@Serial private static final long serialVersionUID = -3826145918174926431L;

	/**
	 * Builds an empty reference-scoped attribute index.
	 *
	 * @param entityType   the owning entity type
	 * @param referenceKey the representative reference key — `null` for
	 *                     [io.evitadb.index.ReferencedTypeEntityIndex] which uses the index key's
	 *                     reference-name discriminator directly
	 */
	public ReferenceAttributeIndex(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey
	) {
		super(entityType, referenceKey);
	}

	/**
	 * Builds a reference-scoped attribute index pre-populated from deserialized maps. Used by the
	 * persistence reload path in [io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService].
	 *
	 * @param entityType   the owning entity type
	 * @param referenceKey the representative reference key — `null` for
	 *                     [io.evitadb.index.ReferencedTypeEntityIndex]
	 * @param uniqueIndex  pre-loaded standalone (owner) unique sub-index map
	 * @param filterIndex  filter VIEW map (carries each key's attributeType; rebuilt over the shared trees)
	 * @param uniqueViewIndex folded-unique VIEW map (carries each foldable key; rebuilt over the shared trees)
	 * @param sortIndex    pre-loaded sort sub-index map
	 * @param chainIndex   pre-loaded chain sub-index map
	 * @param sharedValueIndex pre-loaded shared value→ValueToRecord tree map
	 * @param sharedRangeIndex pre-loaded shared range-structure map
	 */
	public ReferenceAttributeIndex(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueViewIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndex,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndex
	) {
		super(entityType, referenceKey, uniqueIndex, filterIndex, uniqueViewIndex, sortIndex, chainIndex, sharedValueIndex, sharedRangeIndex);
	}

	@Nonnull
	@Override
	public AttributeScope getScope() {
		return AttributeScope.REFERENCE;
	}

	@Nonnull
	@Override
	protected AttributeIndex createCopy(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueViewIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndex,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndex
	) {
		return new ReferenceAttributeIndex(
			entityType, referenceKey, uniqueIndex, filterIndex, uniqueViewIndex, sortIndex, chainIndex, sharedValueIndex, sharedRangeIndex
		);
	}

}
