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
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the resolution rules of {@link ReferencePartitions} over one collection's indexes held in plain maps: which
 * objects are reference partitions at all, which belong to a scope and a reference, the lenient and strict answers
 * to a primary key that names nothing, and the two ways the partitions of one target are found.
 *
 * The maps stand in for the lookups the resolver surfaces pass in (a collection's buffer and maps, a query snapshot,
 * a data store reader); those surfaces and their layering are covered by
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
	private final Map<EntityIndexKey, EntityIndex> indexesByKey = new HashMap<>(16);
	/**
	 * Every index of the fixture collection by primary key.
	 */
	private final Map<Integer, EntityIndex> indexesByPrimaryKey = new HashMap<>(16);

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
	 * Resolves the partitions of {@link #TARGET_PK} leniently over the fixture maps.
	 *
	 * @param allowsDuplicates whether the reference allows duplicates
	 * @return the partitions
	 */
	@Nonnull
	private ReferencePartition[] ofTarget(boolean allowsDuplicates) {
		return ReferencePartitions.resolveOfTarget(
			this.indexesByKey::get, this.indexesByPrimaryKey::get,
			Scope.LIVE, REFERENCE_NAME, allowsDuplicates, TARGET_PK
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
			assertSame(index, ReferencePartitions.asPartition(index, Scope.LIVE, REFERENCE_NAME));
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
			assertNull(ReferencePartitions.asPartition(groupIndex, Scope.LIVE, REFERENCE_NAME));
		}

		@Test
		@DisplayName("answers null for a partition of another scope or another reference")
		void shouldReturnNullWhenPartitionBelongsToAnotherScopeOrReference() {
			final ReducedEntityIndex archived = addPartition(1, Scope.ARCHIVED, REFERENCE_NAME, TARGET_PK);
			final ReducedEntityIndex otherReference = addPartition(2, Scope.LIVE, OTHER_REFERENCE_NAME, TARGET_PK);
			assertNull(ReferencePartitions.asPartition(archived, Scope.LIVE, REFERENCE_NAME));
			assertNull(ReferencePartitions.asPartition(otherReference, Scope.LIVE, REFERENCE_NAME));
			assertSame(archived, ReferencePartitions.asPartition(archived, Scope.ARCHIVED, REFERENCE_NAME));
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
	@DisplayName("resolving by key and by primary keys")
	class ByKeyAndPrimaryKeys {

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

		@Test
		@DisplayName("resolves primary keys strictly, in ascending primary key order")
		void shouldResolveAllPrimaryKeysInAscendingOrder() {
			final ReducedEntityIndex third = addPartition(30, Scope.LIVE, REFERENCE_NAME, 3);
			final ReducedEntityIndex first = addPartition(10, Scope.LIVE, REFERENCE_NAME, 1);
			final ReducedEntityIndex second = addPartition(20, Scope.ARCHIVED, OTHER_REFERENCE_NAME, 2);
			assertArrayEquals(
				new ReferencePartition[]{first, second, third},
				ReferencePartitions.resolveAll(
					ReferencePartitionsTest.this.indexesByPrimaryKey::get, new BaseBitmap(30, 10, 20), ENTITY_TYPE
				)
			);
			assertSame(
				ReferencePartitions.EMPTY_PARTITIONS,
				ReferencePartitions.resolveAll(
					ReferencePartitionsTest.this.indexesByPrimaryKey::get, EmptyBitmap.INSTANCE, ENTITY_TYPE
				)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferencePartitions.resolveAll(
					ReferencePartitionsTest.this.indexesByPrimaryKey::get, new BaseBitmap(10, 11), ENTITY_TYPE
				)
			);
			addGroupIndex(40, TARGET_PK);
			final GenericEvitaInternalError otherFamily = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferencePartitions.resolveAll(
					ReferencePartitionsTest.this.indexesByPrimaryKey::get, new BaseBitmap(10, 40), ENTITY_TYPE
				)
			);
			assertTrue(otherFamily.getPrivateMessage().contains("primary key 40"), otherFamily.getPrivateMessage());
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
			assertArrayEquals(
				new ReferencePartition[]{archived},
				ReferencePartitions.resolveOfTarget(
					ReferencePartitionsTest.this.indexesByKey::get,
					ReferencePartitionsTest.this.indexesByPrimaryKey::get,
					Scope.ARCHIVED, REFERENCE_NAME, false, TARGET_PK
				)
			);
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

}
