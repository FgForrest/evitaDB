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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Map;

/**
 * Concrete [AttributeIndex] specialization that stores attributes defined directly on the entity.
 * Used by [io.evitadb.index.GlobalEntityIndex]. The class is structurally distinguishable from
 * [ReferenceAttributeIndex] so call sites can fan out mutations by index type instead of by
 * schema inspection.
 *
 * Constructor invariants enforce that this index never holds reference-keyed data:
 *
 * - The owning [io.evitadb.index.EntityIndexKey] discriminator must NOT be a
 *   [RepresentativeReferenceKey] — encoded as `referenceKey == null` at the class boundary.
 *
 * Subclass identity is reconstructed from `EntityIndexKey.discriminator()` on reload, so the
 * Kryo registry continues to register the parent [AttributeIndex] type only.
 */
@ThreadSafe
public final class EntityAttributeIndex extends AttributeIndex {

	@Serial private static final long serialVersionUID = 5712368294736210501L;

	/**
	 * Builds an empty entity-scoped attribute index.
	 *
	 * @param entityType the owning entity type
	 */
	public EntityAttributeIndex(@Nonnull String entityType) {
		super(entityType, null);
	}

	/**
	 * Builds an entity-scoped attribute index pre-populated from deserialized maps. Used by the
	 * persistence reload path in [io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService].
	 *
	 * The `referenceKey` parameter mirrors the legacy [AttributeIndex] constructor signature but is
	 * required to be `null` — passing a non-null value indicates the caller mistakenly routed
	 * reference-scoped data into the entity-scoped index. The runtime check prevents that class of
	 * bug at the construction boundary.
	 *
	 * @param entityType   the owning entity type
	 * @param referenceKey must be `null` — the parameter is kept for signature symmetry with
	 *                     [ReferenceAttributeIndex]
	 * @param uniqueIndex  pre-loaded unique sub-index map
	 * @param filterIndex  pre-loaded filter sub-index map
	 * @param sortIndex    pre-loaded sort sub-index map
	 * @param chainIndex   pre-loaded chain sub-index map
	 */
	public EntityAttributeIndex(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndex
	) {
		super(entityType, referenceKey, uniqueIndex, filterIndex, sortIndex, chainIndex);
		Assert.isPremiseValid(
			referenceKey == null,
			"EntityAttributeIndex must not be associated with a representative reference key — got " + referenceKey
		);
	}

	@Nonnull
	@Override
	public AttributeScope getScope() {
		return AttributeScope.ENTITY;
	}

	@Nonnull
	@Override
	protected AttributeIndex createCopy(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndex
	) {
		return new EntityAttributeIndex(
			entityType, referenceKey, uniqueIndex, filterIndex, sortIndex, chainIndex
		);
	}

}
