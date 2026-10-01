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

package io.evitadb.core.query.sort.reference.sorter;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static org.mockito.Mockito.mock;

/**
 * Real reduced indexes, type indexes, global indexes and memberships of one reference, served to a
 * {@link PickFirstReducedIndexResolver} through a real {@link QueryPlanningContext}. Every index is registered with
 * the planning context's primary key lookup; the type index of a scope lists the reduced indexes added to it as its
 * family, while the membership of a scope is filled explicitly by the test, so that it can be made to disagree with
 * the indexes. The planning context reads the indexes through views counting every lookup, which is how the tests
 * observe which indexes the resolver looked at.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PickFirstReducedIndexFixture {
	/**
	 * Name of the reference the resolver walks.
	 */
	static final String REFERENCE_NAME = "items";
	/**
	 * Name of another reference of the same owner type.
	 */
	static final String OTHER_REFERENCE_NAME = "other";
	/**
	 * Type of the owner entities.
	 */
	static final String OWNER_TYPE = "owner";
	/**
	 * Primary key of the first type or global index; reduced indexes use keys below it.
	 */
	private static final int INFRASTRUCTURE_PRIMARY_KEY_BASE = 10_000;

	/**
	 * The reference the resolver walks.
	 */
	@Nonnull final ReferenceSchema referenceSchema = ReferenceSchema._internalBuild(
		REFERENCE_NAME, "target", false, Cardinality.ZERO_OR_MORE, null, false, null, null
	);
	/**
	 * Every index of the collection by its primary key, the backing of
	 * {@link QueryPlanningContext#getEntityIndexByPrimaryKeyIfExists(int)}.
	 */
	private final Map<Integer, EntityIndex> indexesByPrimaryKey = new HashMap<>(32);
	/**
	 * The type and global indexes by their key, the backing of
	 * {@link QueryPlanningContext#getIndexIfExists(io.evitadb.index.IndexKey, Class)}.
	 */
	private final Map<EntityIndexKey, EntityIndex> indexesByKey = new HashMap<>(8);
	/**
	 * The view of {@link #indexesByPrimaryKey} the planning context reads.
	 */
	private final CountingIndexMap<Integer> countedIndexesByPrimaryKey =
		new CountingIndexMap<>(this.indexesByPrimaryKey);
	/**
	 * The view of {@link #indexesByKey} the planning context reads.
	 */
	private final CountingIndexMap<EntityIndexKey> countedIndexesByKey = new CountingIndexMap<>(this.indexesByKey);
	/**
	 * The planning context of a query over the owners, built without a session the way an index-only evaluation is.
	 * Its catalog is the only mock: {@link Catalog} cannot be built outside a running engine, and none of the lookups
	 * the resolver makes reads it.
	 */
	@Nonnull final QueryPlanningContext queryContext = new QueryPlanningContext(
		mock(Catalog.class),
		null,
		null,
		new EvitaRequest(query(collection(OWNER_TYPE)), OffsetDateTime.now(), EntityReference.class, null),
		this.countedIndexesByKey,
		this.countedIndexesByPrimaryKey,
		NoCacheSupervisor.INSTANCE
	);
	/**
	 * Type index of the reference per scope, created with the first reduced index of the scope.
	 */
	private final Map<Scope, ReferencedTypeEntityIndex> typeIndexes = new EnumMap<>(Scope.class);
	/**
	 * Global index per scope, created with the first membership of the scope.
	 */
	private final Map<Scope, GlobalEntityIndex> globalIndexes = new EnumMap<>(Scope.class);
	/**
	 * Primary key of the next type or global index.
	 */
	private int nextInfrastructurePrimaryKey = INFRASTRUCTURE_PRIMARY_KEY_BASE;

	/**
	 * Adds a reduced index of {@link #REFERENCE_NAME} without representative values and lists it in the type index
	 * of its scope.
	 *
	 * @param scope      the scope of the index
	 * @param primaryKey primary key of the index
	 * @param target     primary key of the referenced entity
	 * @param owners     owners the index holds
	 * @return the index
	 */
	@Nonnull
	ReducedEntityIndex addIndex(@Nonnull Scope scope, int primaryKey, int target, @Nonnull int... owners) {
		return addIndex(scope, primaryKey, target, ArrayUtils.EMPTY_SERIALIZABLE_ARRAY, owners);
	}

	/**
	 * Adds a reduced index of {@link #REFERENCE_NAME} and lists it in the type index of its scope.
	 *
	 * @param scope                the scope of the index
	 * @param primaryKey           primary key of the index
	 * @param target               primary key of the referenced entity
	 * @param representativeValues representative attribute values telling apart indexes of one target
	 * @param owners               owners the index holds
	 * @return the index
	 */
	@Nonnull
	ReducedEntityIndex addIndex(
		@Nonnull Scope scope,
		int primaryKey,
		int target,
		@Nonnull Serializable[] representativeValues,
		@Nonnull int... owners
	) {
		final ReducedEntityIndex index = addUnlistedIndex(
			scope, primaryKey, REFERENCE_NAME, target, representativeValues, owners
		);
		getOrCreateTypeIndex(scope).insertPrimaryKeyIfMissing(primaryKey, target);
		return index;
	}

	/**
	 * Adds a reduced index known to the collection by its primary key but listed by no type index of
	 * {@link #REFERENCE_NAME} - an index of another reference, or of another scope.
	 *
	 * @param scope                the scope of the index
	 * @param primaryKey           primary key of the index
	 * @param referenceName        the reference of the index
	 * @param target               primary key of the referenced entity
	 * @param representativeValues representative attribute values
	 * @param owners               owners the index holds
	 * @return the index
	 */
	@Nonnull
	ReducedEntityIndex addUnlistedIndex(
		@Nonnull Scope scope,
		int primaryKey,
		@Nonnull String referenceName,
		int target,
		@Nonnull Serializable[] representativeValues,
		@Nonnull int... owners
	) {
		final ReducedEntityIndex index = new ReducedEntityIndex(
			primaryKey, OWNER_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, scope,
				new RepresentativeReferenceKey(new ReferenceKey(referenceName, target), representativeValues)
			)
		);
		for (int owner : owners) {
			index.insertPrimaryKeyIfMissing(owner);
		}
		this.indexesByPrimaryKey.put(primaryKey, index);
		return index;
	}

	/**
	 * Returns a reduced index added to the fixture, without going through the planning context, so that the lookups
	 * a test counts are only those of the code under test.
	 *
	 * @param primaryKey primary key of the index
	 * @return the index
	 */
	@Nonnull
	ReducedEntityIndex getIndex(int primaryKey) {
		return (ReducedEntityIndex) this.indexesByPrimaryKey.get(primaryKey);
	}

	/**
	 * Adds a reduced index of the group family of {@link #REFERENCE_NAME}, known to the collection by its primary
	 * key only.
	 *
	 * @param scope      the scope of the index
	 * @param primaryKey primary key of the index
	 * @param group      primary key of the referenced group
	 * @param owners     owners the index holds
	 */
	void addGroupIndex(@Nonnull Scope scope, int primaryKey, int group, @Nonnull int... owners) {
		final ReducedGroupEntityIndex index = new ReducedGroupEntityIndex(
			primaryKey, OWNER_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, scope,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, group))
			)
		);
		for (int owner : owners) {
			index.insertPrimaryKeyIfMissing(owner, group);
		}
		this.indexesByPrimaryKey.put(primaryKey, index);
	}

	/**
	 * Returns the membership of {@link #REFERENCE_NAME} in the scope, creating it - and the global index holding it -
	 * when the scope has none yet. A scope whose membership is never asked for has none.
	 *
	 * @param scope the scope
	 * @return the membership
	 */
	@Nonnull
	ReducedIndexMembership membership(@Nonnull Scope scope) {
		return getOrCreateGlobalIndex(scope).getOrCreateReducedIndexMembership(REFERENCE_NAME);
	}

	/**
	 * Registers every index of the scope's family in the membership: the listed ones as covered, the rest as
	 * residual.
	 *
	 * @param scope   the scope
	 * @param covered primary keys of the indexes to cover
	 */
	void registerFamily(@Nonnull Scope scope, @Nonnull int... covered) {
		final ReducedIndexMembership membership = membership(scope);
		final OfInt it = getOrCreateTypeIndex(scope).getAllPrimaryKeys().iterator();
		while (it.hasNext()) {
			final int indexPrimaryKey = it.nextInt();
			if (ArrayUtils.contains(covered, indexPrimaryKey)) {
				membership.registerIndex(indexPrimaryKey, this.indexesByPrimaryKey.get(indexPrimaryKey).getAllPrimaryKeys());
			} else {
				membership.registerIndexAsResidual(indexPrimaryKey);
			}
		}
	}

	/**
	 * Returns how many times the planning context looked up the index by its primary key.
	 *
	 * @param indexPrimaryKey primary key of the index
	 * @return the number of lookups
	 */
	int lookups(int indexPrimaryKey) {
		return this.countedIndexesByPrimaryKey.lookups(indexPrimaryKey);
	}

	/**
	 * Returns how many index lookups the planning context made in total, by primary key and by key.
	 *
	 * @return the number of lookups
	 */
	int lookups() {
		return this.countedIndexesByPrimaryKey.lookups() + this.countedIndexesByKey.lookups();
	}

	/**
	 * Creates a fresh execution context of the fixture's planning context, with no entities prefetched.
	 *
	 * @return the execution context
	 */
	@Nonnull
	QueryExecutionContext createExecutionContext() {
		return this.queryContext.createExecutionContext();
	}

	/**
	 * Creates a resolver ordering the targets by their primary key.
	 *
	 * @param descending whether the targets are ordered descending
	 * @param scopes     the scopes the query processes
	 * @return the resolver
	 */
	@Nonnull
	PickFirstReducedIndexResolver resolver(boolean descending, @Nonnull Scope... scopes) {
		return new PickFirstReducedIndexResolver(
			this.queryContext, this.referenceSchema, scopes, null, descending, () -> new ReducedEntityIndex[0]
		);
	}

	/**
	 * Creates a resolver.
	 *
	 * @param targetSorter    sorter ordering the targets, `null` for the ascending primary key order
	 * @param planningIndexes supplier of the planning-time indexes
	 * @param scopes          the scopes the query processes
	 * @return the resolver
	 */
	@Nonnull
	PickFirstReducedIndexResolver resolver(
		@Nullable NestedContextSorter targetSorter,
		@Nonnull Supplier<ReducedEntityIndex[]> planningIndexes,
		@Nonnull Scope... scopes
	) {
		return new PickFirstReducedIndexResolver(
			this.queryContext, this.referenceSchema, scopes, targetSorter, false, planningIndexes
		);
	}

	/**
	 * Returns the type index of the scope, creating it and making it available to the planning context on first use.
	 *
	 * @param scope the scope
	 * @return the type index
	 */
	@Nonnull
	private ReferencedTypeEntityIndex getOrCreateTypeIndex(@Nonnull Scope scope) {
		return this.typeIndexes.computeIfAbsent(
			scope,
			theScope -> {
				final EntityIndexKey key = new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, theScope, REFERENCE_NAME
				);
				final ReferencedTypeEntityIndex typeIndex = new ReferencedTypeEntityIndex(
					this.nextInfrastructurePrimaryKey++, OWNER_TYPE, key
				);
				this.indexesByKey.put(key, typeIndex);
				return typeIndex;
			}
		);
	}

	/**
	 * Returns the global index of the scope, creating it and making it available to the planning context on first
	 * use.
	 *
	 * @param scope the scope
	 * @return the global index
	 */
	@Nonnull
	private GlobalEntityIndex getOrCreateGlobalIndex(@Nonnull Scope scope) {
		return this.globalIndexes.computeIfAbsent(
			scope,
			theScope -> {
				final EntityIndexKey key = new EntityIndexKey(EntityIndexType.GLOBAL, theScope);
				final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
					this.nextInfrastructurePrimaryKey++, OWNER_TYPE, key
				);
				this.indexesByKey.put(key, globalIndex);
				return globalIndex;
			}
		);
	}

	/**
	 * Read-only view of an index map counting the lookups made through it.
	 *
	 * @param <K> type of the key
	 */
	private static final class CountingIndexMap<K> extends AbstractMap<K, EntityIndex> {
		/**
		 * The map the view reads.
		 */
		private final Map<K, EntityIndex> delegate;
		/**
		 * Number of lookups per looked up key.
		 */
		private final Map<Object, Integer> lookupsByKey = new HashMap<>(32);

		CountingIndexMap(@Nonnull Map<K, EntityIndex> delegate) {
			this.delegate = delegate;
		}

		@Override
		public EntityIndex get(Object key) {
			this.lookupsByKey.merge(key, 1, Integer::sum);
			return this.delegate.get(key);
		}

		@Nonnull
		@Override
		public Set<Entry<K, EntityIndex>> entrySet() {
			return this.delegate.entrySet();
		}

		/**
		 * Returns how many times the key was looked up.
		 *
		 * @param key the key
		 * @return the number of lookups
		 */
		int lookups(@Nonnull K key) {
			return this.lookupsByKey.getOrDefault(key, 0);
		}

		/**
		 * Returns how many lookups were made in total.
		 *
		 * @return the number of lookups
		 */
		int lookups() {
			return this.lookupsByKey.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

}
