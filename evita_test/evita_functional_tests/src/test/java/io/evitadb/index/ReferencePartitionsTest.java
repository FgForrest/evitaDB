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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the resolution rules of {@link ReferencePartitions} over one collection's indexes held in plain maps: which
 * objects are reference partitions at all, which belong to a scope and a reference, the lenient and strict answers
 * to a primary key that names nothing, the two ways the partitions of one target are found, and the guards that keep a
 * virtual partition - one the registry of single-owner partitions holds a row for - off the routes and write paths
 * that read index objects only.
 *
 * The maps stand in for the lookups the resolver surfaces pass in (a collection's buffer and maps, a query snapshot,
 * a data store reader - {@link MapDataStoreReader} adapts them to the last); those surfaces and their layering are
 * covered by
 * `io.evitadb.api.functional.indexing.ReferencePartitionResolutionTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferencePartitions resolution rules")
@Tag(INDEXING)
@Tag(REFERENCE)
class ReferencePartitionsTest {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "categories";
	private static final String OTHER_REFERENCE_NAME = "brands";
	private static final int TARGET_PK = 7;

	/**
	 * Every index of the fixture collection by key.
	 */
	private final Map<EntityIndexKey, EntityIndex> indexesByKey = CollectionUtils.createHashMap(16);
	/**
	 * Every index of the fixture collection by primary key.
	 */
	private final Map<Integer, EntityIndex> indexesByPrimaryKey = CollectionUtils.createHashMap(16);

	/**
	 * Registers a reduced entity index in both maps.
	 *
	 * @param primaryKey           primary key of the index
	 * @param scope                scope of the index
	 * @param referenceName        reference of the index
	 * @param target               primary key of the referenced entity
	 * @param representativeValues representative attribute values
	 * @return the index
	 */
	@Nonnull
	private ReducedEntityIndex addPartition(
		int primaryKey,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int target,
		@Nonnull Serializable... representativeValues
	) {
		final ReducedEntityIndex index = new ReducedEntityIndex(
			primaryKey, ENTITY_TYPE,
			ReferencePartitions.createKey(
				scope, new RepresentativeReferenceKey(new ReferenceKey(referenceName, target), representativeValues)
			)
		);
		index.insertPrimaryKeyIfMissing(100 + primaryKey);
		register(index);
		return index;
	}

	/**
	 * Registers a reduced group entity index of {@link #REFERENCE_NAME} in both maps.
	 *
	 * @param primaryKey primary key of the index
	 * @param group      primary key of the group
	 * @return the index
	 */
	@Nonnull
	private ReducedGroupEntityIndex addGroupIndex(int primaryKey, int group) {
		final ReducedGroupEntityIndex index = new ReducedGroupEntityIndex(
			primaryKey, ENTITY_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, group))
			)
		);
		register(index);
		return index;
	}

	/**
	 * Registers the type index of {@link #REFERENCE_NAME} in the scope, listing the given partitions for the target.
	 *
	 * @param scope                the scope
	 * @param target               the target the listed partitions belong to
	 * @param partitionPrimaryKeys primary keys the type index lists for the target
	 * @return the type index
	 */
	@Nonnull
	private ReferencedTypeEntityIndex addTypeIndex(
		@Nonnull Scope scope,
		int target,
		@Nonnull int... partitionPrimaryKeys
	) {
		final ReferencedTypeEntityIndex typeIndex = new ReferencedTypeEntityIndex(
			1_000, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, REFERENCE_NAME)
		);
		for (int partitionPrimaryKey : partitionPrimaryKeys) {
			typeIndex.insertPrimaryKeyIfMissing(partitionPrimaryKey, target);
		}
		register(typeIndex);
		return typeIndex;
	}

	/**
	 * Registers an index in both maps.
	 *
	 * @param index the index
	 */
	private void register(@Nonnull EntityIndex index) {
		this.indexesByKey.put(index.getIndexKey(), index);
		this.indexesByPrimaryKey.put(index.getPrimaryKey(), index);
	}

	/**
	 * Resolves the partitions of {@link #TARGET_PK} leniently over the fixture maps, through a data store reader - the
	 * only route to the lenient resolution.
	 *
	 * @param allowsDuplicates whether the reference allows duplicates
	 * @return the partitions
	 */
	@Nonnull
	private ReferencePartition[] ofTarget(boolean allowsDuplicates) {
		return ofTarget(Scope.LIVE, allowsDuplicates);
	}

	/**
	 * Resolves the partitions of {@link #TARGET_PK} in the scope leniently over the fixture maps, through a data store
	 * reader - the only route to the lenient resolution.
	 *
	 * @param scope            the scope of the partitions
	 * @param allowsDuplicates whether the reference allows duplicates
	 * @return the partitions
	 */
	@Nonnull
	private ReferencePartition[] ofTarget(@Nonnull Scope scope, boolean allowsDuplicates) {
		return ReferencePartitions.resolveOfTarget(
			new MapDataStoreReader(this.indexesByKey, this.indexesByPrimaryKey),
			scope, REFERENCE_NAME, allowsDuplicates, TARGET_PK
		);
	}

	/**
	 * Resolves the partitions of {@link #TARGET_PK} strictly over the fixture maps.
	 *
	 * @param allowsDuplicates whether the reference allows duplicates
	 * @return the partitions
	 */
	@Nonnull
	private ReferencePartition[] ofTargetOrThrow(boolean allowsDuplicates) {
		return ReferencePartitions.resolveOfTargetOrThrow(
			this.indexesByKey::get, this.indexesByPrimaryKey::get,
			Scope.LIVE, REFERENCE_NAME, allowsDuplicates, TARGET_PK, ENTITY_TYPE
		);
	}

	@Nested
	@DisplayName("narrowing an index to a partition")
	class Narrowing {

		@Test
		@DisplayName("accepts a reduced entity index as the partition itself, with no wrapper")
		void shouldReturnTheReducedEntityIndexItselfWhenNarrowed() {
			final ReducedEntityIndex index = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK);
			assertSame(index, ReferencePartitions.asPartition(index));
			assertSame(index, ReferencePartitions.requirePartition(index, ENTITY_TYPE, 1));
			assertSame(index, ReferencePartitions.toReducedEntityIndex(index));
		}

		@Test
		@DisplayName("answers null for absence, the group family and the other index kinds")
		void shouldReturnNullWhenIndexIsNotAPartition() {
			final ReducedGroupEntityIndex groupIndex = addGroupIndex(2, TARGET_PK);
			final ReferencedTypeEntityIndex typeIndex = addTypeIndex(Scope.LIVE, TARGET_PK);
			final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
				3, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL)
			);
			assertNull(ReferencePartitions.asPartition(null));
			assertNull(ReferencePartitions.asPartition(groupIndex));
			assertNull(ReferencePartitions.asPartition(typeIndex));
			assertNull(ReferencePartitions.asPartition(globalIndex));
		}

		@Test
		@DisplayName("tells apart a partition of another scope or another reference")
		void shouldTellApartPartitionOfAnotherScopeOrReference() {
			final ReducedEntityIndex archived = addPartition(1, Scope.ARCHIVED, REFERENCE_NAME, TARGET_PK);
			final ReducedEntityIndex otherReference = addPartition(2, Scope.LIVE, OTHER_REFERENCE_NAME, TARGET_PK);
			assertFalse(ReferencePartitions.belongsTo(archived, Scope.LIVE, REFERENCE_NAME));
			assertTrue(ReferencePartitions.belongsTo(archived, Scope.ARCHIVED, REFERENCE_NAME));
			assertTrue(ReferencePartitions.belongsTo(otherReference, Scope.LIVE, OTHER_REFERENCE_NAME));
			assertFalse(ReferencePartitions.belongsTo(otherReference, Scope.LIVE, REFERENCE_NAME));
		}

		@Test
		@DisplayName("strictly rejects absence and the group family, naming the collection and the primary key")
		void shouldThrowWhenRequiredPartitionIsMissingOrOfOtherFamily() {
			final GenericEvitaInternalError missing = assertThrows(
				GenericEvitaInternalError.class, () -> ReferencePartitions.requirePartition(null, ENTITY_TYPE, 42)
			);
			assertTrue(missing.getPrivateMessage().contains("42"), missing.getPrivateMessage());
			assertTrue(missing.getPrivateMessage().contains("`" + ENTITY_TYPE + "`"), missing.getPrivateMessage());
			final ReducedGroupEntityIndex groupIndex = addGroupIndex(2, TARGET_PK);
			final GenericEvitaInternalError otherFamily = assertThrows(
				GenericEvitaInternalError.class, () -> ReferencePartitions.requirePartition(groupIndex, ENTITY_TYPE, 2)
			);
			assertTrue(
				otherFamily.getPrivateMessage().contains(ReducedGroupEntityIndex.class.getName()),
				otherFamily.getPrivateMessage()
			);
		}
	}

	@Nested
	@DisplayName("resolving by key")
	class ByKey {

		@Test
		@DisplayName("resolves a representative reference key to its partition, and to null when absent")
		void shouldResolvePartitionByRepresentativeReferenceKey() {
			final ReducedEntityIndex index = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK, "DE");
			final RepresentativeReferenceKey rrk = index.getRepresentativeReferenceKey();
			final Map<EntityIndexKey, EntityIndex> indexesByKey = ReferencePartitionsTest.this.indexesByKey;
			assertSame(index, ReferencePartitions.resolveIfExists(indexesByKey::get, Scope.LIVE, rrk));
			assertNull(ReferencePartitions.resolveIfExists(indexesByKey::get, Scope.ARCHIVED, rrk));
			assertNull(
				ReferencePartitions.resolveIfExists(
					ReferencePartitionsTest.this.indexesByKey::get, Scope.LIVE,
					new RepresentativeReferenceKey(
						new ReferenceKey(REFERENCE_NAME, TARGET_PK), new Serializable[]{"US"}
					)
				)
			);
		}

		@Test
		@DisplayName("treats a non-partition registered under a partition key as a programming error")
		void shouldThrowWhenPartitionKeyHoldsAnotherIndex() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, 3));
			final ReducedGroupEntityIndex groupIndex = addGroupIndex(2, 3);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferencePartitions.resolveIfExists(key -> groupIndex, Scope.LIVE, rrk)
			);
		}
	}

	@Nested
	@DisplayName("resolving the partitions of one target")
	class OfTarget {

		@Test
		@DisplayName("addresses the single partition of a reference without duplicates by its key")
		void shouldResolveSinglePartitionByKeyWhenReferenceDisallowsDuplicates() {
			final ReducedEntityIndex index = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK);
			// a partition of another target and a type index listing something else must both be ignored
			addPartition(2, Scope.LIVE, REFERENCE_NAME, TARGET_PK + 1);
			addTypeIndex(Scope.LIVE, TARGET_PK, 2);
			assertArrayEquals(new ReferencePartition[]{index}, ofTarget(false));
			assertArrayEquals(new ReferencePartition[]{index}, ofTargetOrThrow(false));
		}

		@Test
		@DisplayName("answers empty for a target with no partition, in both modes")
		void shouldReturnEmptyWhenTargetHasNoPartition() {
			assertSame(ReferencePartitions.EMPTY_PARTITIONS, ofTarget(false));
			assertSame(ReferencePartitions.EMPTY_PARTITIONS, ofTarget(true));
			assertSame(ReferencePartitions.EMPTY_PARTITIONS, ofTargetOrThrow(true));
			addTypeIndex(Scope.LIVE, TARGET_PK + 1, 5);
			assertSame(ReferencePartitions.EMPTY_PARTITIONS, ofTargetOrThrow(true));
		}

		@Test
		@DisplayName("enumerates every partition of a reference with duplicates through its type index")
		void shouldEnumeratePartitionsThroughTypeIndexWhenReferenceAllowsDuplicates() {
			final ReducedEntityIndex german = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK, "DE");
			final ReducedEntityIndex american = addPartition(2, Scope.LIVE, REFERENCE_NAME, TARGET_PK, "US");
			// the key without representative values names nothing for a reference with duplicates
			addTypeIndex(Scope.LIVE, TARGET_PK, 1, 2);
			assertArrayEquals(new ReferencePartition[]{german, american}, ofTarget(true));
			assertArrayEquals(new ReferencePartition[]{german, american}, ofTargetOrThrow(true));
			assertSame(ReferencePartitions.EMPTY_PARTITIONS, ofTarget(false));
		}

		@Test
		@DisplayName("treats a non-type index registered under the type index key as a programming error")
		void shouldThrowWhenTypeIndexKeyHoldsAnotherIndex() {
			addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK, "DE");
			ReferencePartitionsTest.this.indexesByKey.put(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME),
				new GlobalEntityIndex(3, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL))
			);
			assertThrows(GenericEvitaInternalError.class, () -> ofTarget(true));
			assertThrows(GenericEvitaInternalError.class, () -> ofTargetOrThrow(true));
		}

		@Test
		@DisplayName("skips a listed primary key the collection no longer holds, unless strict")
		void shouldSkipDanglingPrimaryKeyOnlyWhenLenient() {
			final ReducedEntityIndex american = addPartition(2, Scope.LIVE, REFERENCE_NAME, TARGET_PK, "US");
			addTypeIndex(Scope.LIVE, TARGET_PK, 1, 2);
			assertArrayEquals(new ReferencePartition[]{american}, ofTarget(true));
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> ofTargetOrThrow(true)
			);
			assertTrue(error.getPrivateMessage().contains("primary key 1"), error.getPrivateMessage());

			// when every listed primary key dangles, the lenient answer is empty
			ReferencePartitionsTest.this.indexesByPrimaryKey.remove(american.getPrimaryKey());
			assertEquals(0, ofTarget(true).length);
			assertThrows(GenericEvitaInternalError.class, () -> ofTargetOrThrow(true));
		}

		@Test
		@DisplayName("treats a listed primary key naming another family, scope or reference as corruption")
		void shouldThrowWhenTypeIndexListsForeignIndex() {
			addGroupIndex(1, TARGET_PK);
			addTypeIndex(Scope.LIVE, TARGET_PK, 1);
			assertThrows(GenericEvitaInternalError.class, () -> ofTarget(true));
			assertThrows(GenericEvitaInternalError.class, () -> ofTargetOrThrow(true));

			ReferencePartitionsTest.this.indexesByPrimaryKey.clear();
			addPartition(1, Scope.ARCHIVED, REFERENCE_NAME, TARGET_PK, "DE");
			assertThrows(GenericEvitaInternalError.class, () -> ofTarget(true));
			assertThrows(GenericEvitaInternalError.class, () -> ofTargetOrThrow(true));

			ReferencePartitionsTest.this.indexesByPrimaryKey.clear();
			addPartition(1, Scope.LIVE, OTHER_REFERENCE_NAME, TARGET_PK, "DE");
			assertThrows(GenericEvitaInternalError.class, () -> ofTarget(true));
			assertThrows(GenericEvitaInternalError.class, () -> ofTargetOrThrow(true));
		}

		@Test
		@DisplayName("finds partitions in the requested scope only")
		void shouldResolvePartitionsOfRequestedScopeOnly() {
			final ReducedEntityIndex archived = addPartition(1, Scope.ARCHIVED, REFERENCE_NAME, TARGET_PK);
			assertSame(ReferencePartitions.EMPTY_PARTITIONS, ofTarget(false));
			assertArrayEquals(new ReferencePartition[]{archived}, ofTarget(Scope.ARCHIVED, false));
		}
	}

	@Nested
	@DisplayName("guarding the virtual partition boundary")
	class VirtualBoundary {
		/**
		 * The registered single-owner partition of the fixture's type index.
		 */
		private static final int VIRTUAL_PK = 5;
		/**
		 * The only owner of {@link #VIRTUAL_PK}.
		 */
		private static final int VIRTUAL_OWNER = 50;

		/**
		 * Registers the type index of {@link #REFERENCE_NAME} in the scope with one registered single-owner partition
		 * of {@link #TARGET_PK}, listed by the type index as the registry writer lists it.
		 *
		 * @param scope the scope
		 * @return the type index
		 */
		@Nonnull
		private ReferencedTypeEntityIndex typeIndexWithVirtualPartition(@Nonnull Scope scope) {
			final ReferencedTypeEntityIndex typeIndex = addTypeIndex(scope, TARGET_PK, VIRTUAL_PK);
			typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(VIRTUAL_PK, VIRTUAL_OWNER, TARGET_PK);
			return typeIndex;
		}

		/**
		 * Returns the virtual handle of {@link #VIRTUAL_PK}.
		 *
		 * @param typeIndex the type index holding its row
		 * @return the handle
		 */
		@Nonnull
		private static VirtualReferencePartition virtualOf(@Nonnull ReferencedTypeEntityIndex typeIndex) {
			final VirtualReferencePartition virtual = ReferencePartitions.resolveVirtualIfExists(typeIndex, VIRTUAL_PK);
			assertNotNull(virtual, "The registry row must answer a virtual handle");
			return virtual;
		}

		@Test
		@DisplayName("refuses a virtual partition on the index-bound query body channel")
		void shouldRefuseAVirtualPartitionOnTheQueryBodyChannel() {
			final VirtualReferencePartition virtual = virtualOf(typeIndexWithVirtualPartition(Scope.LIVE));

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> ReferencePartitions.toReducedEntityIndex(virtual)
			);
			assertTrue(error.getPrivateMessage().contains(String.valueOf(VIRTUAL_PK)), error.getPrivateMessage());
		}

		@Test
		@DisplayName("hands a materialized partition to a route reading index objects, and refuses a virtual one by route")
		void shouldRefuseAVirtualPartitionOnARouteReadingIndexObjects() {
			final ReducedEntityIndex materialized = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK);
			final VirtualReferencePartition virtual = virtualOf(typeIndexWithVirtualPartition(Scope.LIVE));

			assertSame(materialized, ReferencePartitions.toMaterializedEntityIndex(materialized, "sortRoute"));
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> ReferencePartitions.toMaterializedEntityIndex(virtual, "sortRoute")
			);
			assertTrue(error.getPrivateMessage().contains("`sortRoute`"), error.getPrivateMessage());
		}

		@Test
		@DisplayName("lets a write path hold nothing or a reduced entity index, and refuses a virtual one by write site")
		void shouldRefuseAVirtualPartitionOnAWritePath() {
			final ReducedEntityIndex materialized = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK);
			final VirtualReferencePartition virtual = virtualOf(typeIndexWithVirtualPartition(Scope.LIVE));
			final ReferencePartition[] allMaterialized = {materialized};
			final ReferencePartition[] withVirtual = {materialized, virtual};

			assertNull(ReferencePartitions.requireMaterialized((ReferencePartition) null, "upsert"));
			assertSame(materialized, ReferencePartitions.requireMaterialized(materialized, "upsert"));
			assertSame(allMaterialized, ReferencePartitions.requireMaterialized(allMaterialized, "upsert"));
			final GenericEvitaInternalError single = assertThrows(
				GenericEvitaInternalError.class, () -> ReferencePartitions.requireMaterialized(virtual, "upsert")
			);
			assertTrue(single.getPrivateMessage().contains("`upsert`"), single.getPrivateMessage());
			final GenericEvitaInternalError array = assertThrows(
				GenericEvitaInternalError.class, () -> ReferencePartitions.requireMaterialized(withVirtual, "remove")
			);
			assertTrue(array.getPrivateMessage().contains("`remove`"), array.getPrivateMessage());
		}

		@Test
		@DisplayName("resolves a registered partition to its handle and any other to its index object, for a query")
		void shouldResolveARegisteredPartitionToItsHandleForRead() {
			final ReducedEntityIndex materialized = addPartition(1, Scope.LIVE, REFERENCE_NAME, TARGET_PK);
			final ReferencedTypeEntityIndex typeIndex = typeIndexWithVirtualPartition(Scope.LIVE);
			final Map<Integer, EntityIndex> byPrimaryKey = ReferencePartitionsTest.this.indexesByPrimaryKey;

			final VirtualReferencePartition virtual = assertInstanceOf(
				VirtualReferencePartition.class,
				ReferencePartitions.resolveForReadIfExists(
					pk -> ReferencePartitions.asPartition(byPrimaryKey.get(pk)), typeIndex, Scope.LIVE,
					REFERENCE_NAME, VIRTUAL_PK
				)
			);
			assertEquals(VIRTUAL_OWNER, virtual.ownerPrimaryKey());
			assertSame(
				materialized,
				ReferencePartitions.resolveForReadIfExists(
					pk -> ReferencePartitions.asPartition(byPrimaryKey.get(pk)), typeIndex, Scope.LIVE,
					REFERENCE_NAME, 1
				)
			);
		}

		@Test
		@DisplayName("refuses a type index of another scope or another reference on a read resolver")
		void shouldRefuseATypeIndexOfAnotherScopeOrReference() {
			final ReferencedTypeEntityIndex archived = typeIndexWithVirtualPartition(Scope.ARCHIVED);
			final ReferencedTypeEntityIndex otherReference = new ReferencedTypeEntityIndex(
				2_000, ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, OTHER_REFERENCE_NAME)
			);

			final GenericEvitaInternalError otherScope = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferencePartitions.resolveForReadIfExists(
					pk -> null, archived, Scope.LIVE, REFERENCE_NAME, VIRTUAL_PK
				)
			);
			assertTrue(otherScope.getPrivateMessage().contains("`" + REFERENCE_NAME + "`"), otherScope.getPrivateMessage());
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferencePartitions.resolveForReadIfExists(
					pk -> null, otherReference, Scope.LIVE, REFERENCE_NAME, VIRTUAL_PK
				)
			);
		}

		@Test
		@DisplayName("refuses a primary key naming no partition, naming the collection, when one must exist")
		void shouldRefuseAPrimaryKeyNamingNoPartitionForRead() {
			final ReferencedTypeEntityIndex typeIndex = typeIndexWithVirtualPartition(Scope.LIVE);

			assertNull(
				ReferencePartitions.resolveForReadIfExists(pk -> null, typeIndex, Scope.LIVE, REFERENCE_NAME, 42)
			);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferencePartitions.resolveForRead(
					pk -> null, typeIndex, Scope.LIVE, REFERENCE_NAME, 42, ENTITY_TYPE
				)
			);
			assertTrue(error.getPrivateMessage().contains("42"), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains("`" + ENTITY_TYPE + "`"), error.getPrivateMessage());
		}

		@Test
		@DisplayName("answers the shared empty array for a target the type index lists nothing for, for a query")
		void shouldAnswerEmptyForATargetWithoutPartitionsForRead() {
			final ReferencedTypeEntityIndex typeIndex = typeIndexWithVirtualPartition(Scope.LIVE);
			final Map<EntityIndexKey, EntityIndex> byKey = ReferencePartitionsTest.this.indexesByKey;
			final Map<Integer, EntityIndex> byPrimaryKey = ReferencePartitionsTest.this.indexesByPrimaryKey;

			assertSame(
				ReferencePartitions.EMPTY_PARTITIONS,
				ReferencePartitions.resolveOfTargetForRead(
					byKey::get, byPrimaryKey::get, typeIndex, Scope.LIVE, REFERENCE_NAME, false, TARGET_PK + 1,
					ENTITY_TYPE
				)
			);
			final ReferencePartition[] ofTarget = ReferencePartitions.resolveOfTargetForRead(
				byKey::get, byPrimaryKey::get, typeIndex, Scope.LIVE, REFERENCE_NAME, false, TARGET_PK, ENTITY_TYPE
			);
			assertEquals(1, ofTarget.length);
			assertEquals(VIRTUAL_OWNER, assertInstanceOf(VirtualReferencePartition.class, ofTarget[0]).ownerPrimaryKey());
		}
	}

	@Test
	@DisplayName("builds the partition key of the REFERENCED_ENTITY family")
	void shouldCreateReferencedEntityKey() {
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, TARGET_PK), ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
		);
		assertEquals(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.ARCHIVED, rrk),
			ReferencePartitions.createKey(Scope.ARCHIVED, rrk)
		);
	}

	/**
	 * A data store reader over one collection's index maps with nothing layered on top - the lenient resolution of
	 * the partitions of one target is reached only through a reader. Only the index lookups are supported.
	 *
	 * @param indexesByKey        the indexes by key
	 * @param indexesByPrimaryKey the indexes by primary key
	 */
	private record MapDataStoreReader(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexesByKey,
		@Nonnull Map<Integer, EntityIndex> indexesByPrimaryKey
	) implements DataStoreReader {

		@Override
		public int countStorageParts(long catalogVersion, @Nonnull Class<? extends StoragePart> containerType) {
			throw new UnsupportedOperationException("Not supported in the test.");
		}

		@Nullable
		@Override
		public <T extends StoragePart> T fetch(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException("Not supported in the test.");
		}

		@Nullable
		@Override
		public <T extends StoragePart> byte[] fetchBinary(
			long catalogVersion,
			long primaryKey,
			@Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException("Not supported in the test.");
		}

		@Nullable
		@Override
		public <T extends StoragePart, U extends Comparable<U>> T fetch(
			long catalogVersion,
			@Nonnull U originalKey,
			@Nonnull Class<T> containerType,
			@Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer
		) {
			throw new UnsupportedOperationException("Not supported in the test.");
		}

		@Nullable
		@Override
		public <T extends StoragePart, U extends Comparable<U>> byte[] fetchBinary(
			long catalogVersion,
			@Nonnull U originalKey,
			@Nonnull Class<T> containerType,
			@Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer
		) {
			throw new UnsupportedOperationException("Not supported in the test.");
		}

		@Nullable
		@Override
		public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(
			@Nonnull IK entityIndexKey,
			@Nonnull Function<IK, I> accessorWhenMissing
		) {
			//noinspection unchecked,SuspiciousMethodCalls
			final I index = (I) this.indexesByKey.get(entityIndexKey);
			return index == null ? accessorWhenMissing.apply(entityIndexKey) : index;
		}

		@Nullable
		@Override
		public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(
			int entityIndexPrimaryKey,
			@Nonnull IntFunction<I> accessorWhenMissing
		) {
			//noinspection unchecked
			final I index = (I) this.indexesByPrimaryKey.get(entityIndexPrimaryKey);
			return index == null ? accessorWhenMissing.apply(entityIndexPrimaryKey) : index;
		}
	}

}
