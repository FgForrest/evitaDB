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
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PartitionKeyMemo}, the query-local memo of the by-key read resolution: it answers a key the registry
 * of single-owner partitions holds with the virtual partition of exactly that key, falls through to the index object
 * for any other key, and - the reason it exists - builds the registered partitions of a target once per memo, so
 * resolving the keys of `N` owners sharing one target visits the target's family once rather than once per key. A
 * family whose registry rows contradict the type index is rejected rather than answered.
 *
 * The type index and its registry are real, filled the way the registry writer fills them; no index object exists for
 * a registered partition, so a resolution that reached for one would find nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Query-local memo of the by-key partition resolution")
@Tag(INDEXING)
@Tag(QUERY)
@Tag(REFERENCE)
class PartitionKeyMemoTest {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "media";
	private static final int TARGET = 7;

	/**
	 * The owners × tuples grid of the operation-count gate. A cell holds `owners · tuples` single-owner partitions of
	 * one target, each with representative values of its own. The 10,000 × 16 cell is left out: the registry's tuple
	 * dictionary allocates an id by scanning every id in use, so a 160,000-tuple fixture costs ~10¹⁰ map visits to
	 * build - a property of the registry writer, not of the memo, whose linearity the smaller cells already pin.
	 *
	 * @return the grid cells
	 */
	@Nonnull
	static Stream<Arguments> grid() {
		return Stream.of(
			Arguments.of(1, 1), Arguments.of(1, 2), Arguments.of(1, 16),
			Arguments.of(100, 1), Arguments.of(100, 2), Arguments.of(100, 16),
			Arguments.of(10_000, 1), Arguments.of(10_000, 2), Arguments.of(1_000, 16)
		);
	}

	@ParameterizedTest(name = "{0} owners x {1} tuples")
	@MethodSource("grid")
	@DisplayName("resolving every key of N owners on one target builds the target's family once and visits it once")
	void shouldBuildTheFamilyOfATargetOncePerMemo(int owners, int tuples) {
		final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.LIVE);
		final int partitions = owners * tuples;
		for (int partition = 1; partition <= partitions; partition++) {
			register(typeIndex, partition, ownerOf(partition, tuples), TARGET, tupleOf(partition));
		}

		final PartitionKeyMemo memo = new PartitionKeyMemo();
		for (int partition = 1; partition <= partitions; partition++) {
			final VirtualReferencePartition resolved = assertInstanceOf(
				VirtualReferencePartition.class,
				memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, tupleOf(partition)))
			);
			assertEquals(partition, resolved.getPrimaryKey());
			assertEquals(ownerOf(partition, tuples), resolved.ownerPrimaryKey());
		}
		assertEquals(1, memo.getFamilyBuildCount(), "The family of the target must be built once");
		assertEquals(partitions, memo.getVisitedEntryCount(), "Each member of the family must be visited once");

		// another memo - another query - builds the family anew
		final PartitionKeyMemo next = new PartitionKeyMemo();
		next.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, tupleOf(1)));
		assertEquals(1, next.getFamilyBuildCount());
		assertEquals(partitions, next.getVisitedEntryCount());
	}

	@Nested
	@DisplayName("resolution")
	class Resolution {

		@Test
		@DisplayName("duplicates of one target resolve each to its own partition")
		void shouldTellDuplicatesOfOneTargetApart() {
			final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.LIVE);
			register(typeIndex, 3, 30, TARGET, "gallery");
			register(typeIndex, 4, 40, TARGET, "detail");
			register(typeIndex, 5, 50, TARGET + 1, "detail");
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			assertPartition(
				3, 30, "gallery", memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, "gallery"))
			);
			assertPartition(
				4, 40, "detail", memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, "detail"))
			);
			assertPartition(
				5, 50, "detail", memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET + 1, "detail"))
			);
			assertEquals(2, memo.getFamilyBuildCount(), "One family per target");
		}

		@Test
		@DisplayName("a key without representative values resolves to the only partition of its target")
		void shouldResolveAKeyWithoutRepresentativeValues() {
			final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.LIVE);
			typeIndex.insertPrimaryKeyIfMissing(8, TARGET);
			typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(8, 80, TARGET);
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			final VirtualReferencePartition resolved = assertInstanceOf(
				VirtualReferencePartition.class,
				memo.resolveIfExists(
					NO_OBJECTS, typeIndex, Scope.LIVE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, TARGET))
				)
			);
			assertEquals(8, resolved.getPrimaryKey());
			assertEquals(80, resolved.ownerPrimaryKey());
			assertEquals(0, resolved.getRepresentativeReferenceKey().representativeAttributeValues().length);
		}

		@Test
		@DisplayName("a key the registry does not hold falls through to the index object")
		void shouldFallThroughToTheIndexObject() {
			final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.LIVE);
			register(typeIndex, 3, 30, TARGET, "gallery");
			// a partition with two owners under a tuple the registry knows - listed by the type index, no row
			typeIndex.insertPrimaryKeyIfMissing(6, TARGET + 1);
			final ReducedEntityIndex shared = newObject(6, TARGET + 1, "gallery");
			// a partition under a tuple the registry does not know at all
			typeIndex.insertPrimaryKeyIfMissing(9, TARGET);
			final ReducedEntityIndex unknown = newObject(9, TARGET, "thumbnail");
			final Map<EntityIndexKey, EntityIndex> objects = CollectionUtils.createHashMap(4);
			objects.put(shared.getIndexKey(), shared);
			objects.put(unknown.getIndexKey(), unknown);
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			assertSame(shared, memo.resolveIfExists(objects::get, typeIndex, Scope.LIVE, keyOf(TARGET + 1, "gallery")));
			assertSame(unknown, memo.resolveIfExists(objects::get, typeIndex, Scope.LIVE, keyOf(TARGET, "thumbnail")));
			assertNull(memo.resolveIfExists(objects::get, typeIndex, Scope.LIVE, keyOf(TARGET, "detail")));
		}

		@Test
		@DisplayName("without a type index to read virtually the memo answers the index objects alone")
		void shouldAnswerTheIndexObjectsWithoutTypeIndex() {
			final ReducedEntityIndex object = newObject(3, TARGET, "gallery");
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			assertSame(
				object,
				memo.resolveIfExists(
					key -> object.getIndexKey().equals(key) ? object : null, null, Scope.LIVE, keyOf(TARGET, "gallery")
				)
			);
			assertEquals(0, memo.getFamilyBuildCount());
		}

		@Test
		@DisplayName("a type index of another scope is rejected")
		void shouldRejectTypeIndexOfAnotherScope() {
			final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.ARCHIVED);
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, "gallery"))
			);
		}

		@Test
		@DisplayName("a registry row naming another target than the type index lists is rejected")
		void shouldRejectARowNamingAnotherTarget() {
			final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.LIVE);
			// the type index lists partition 3 for TARGET, but its registry row names TARGET + 1
			typeIndex.insertPrimaryKeyIfMissing(3, TARGET);
			typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(3, 30, TARGET + 1);
			typeIndex.getVirtualReferencePartitionIndex().putRepresentativeTuple(3, new Serializable[]{"gallery"});
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, "gallery"))
			);
			assertTrue(error.getPrivateMessage().contains("partition 3"), error.getPrivateMessage());
		}

		@Test
		@DisplayName("two registered partitions of one target sharing representative values are rejected")
		void shouldRejectTwoPartitionsOfOneTargetSharingTheirTuple() {
			final ReferencedTypeEntityIndex typeIndex = newTypeIndex(Scope.LIVE);
			register(typeIndex, 3, 30, TARGET, "gallery");
			register(typeIndex, 4, 40, TARGET, "gallery");
			final PartitionKeyMemo memo = new PartitionKeyMemo();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> memo.resolveIfExists(NO_OBJECTS, typeIndex, Scope.LIVE, keyOf(TARGET, "gallery"))
			);
			assertTrue(error.getPrivateMessage().contains("Partitions 3 and 4"), error.getPrivateMessage());
		}

		/**
		 * Asserts a resolution is the virtual partition of the expected row.
		 *
		 * @param partition the expected partition primary key
		 * @param owner     the expected owner
		 * @param role      the expected representative value
		 * @param resolved  the resolution
		 */
		private static void assertPartition(
			int partition,
			int owner,
			@Nonnull String role,
			@Nullable ReferencePartition resolved
		) {
			final VirtualReferencePartition virtual = assertInstanceOf(VirtualReferencePartition.class, resolved);
			assertEquals(partition, virtual.getPrimaryKey());
			assertEquals(owner, virtual.ownerPrimaryKey());
			assertEquals(role, virtual.getRepresentativeReferenceKey().representativeAttributeValues()[0]);
		}

	}

	/**
	 * Finds no index object for any key - the stand-in of a collection whose registered partitions have none.
	 */
	private static final Function<EntityIndexKey, EntityIndex> NO_OBJECTS = key -> null;

	/**
	 * Creates an empty referenced-type entity index of the fixture's reference.
	 *
	 * @param scope the scope of the index
	 * @return the type index
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex newTypeIndex(@Nonnull Scope scope) {
		return new ReferencedTypeEntityIndex(
			1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, REFERENCE_NAME)
		);
	}

	/**
	 * Lists a partition of a target in the type index and registers its row, as the registry writer does.
	 *
	 * @param typeIndex the type index
	 * @param partition the partition primary key
	 * @param owner     the only owner
	 * @param target    the target
	 * @param role      the representative value
	 */
	private static void register(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		int partition,
		int owner,
		int target,
		@Nonnull String role
	) {
		typeIndex.insertPrimaryKeyIfMissing(partition, target);
		typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(partition, owner, target);
		typeIndex.getVirtualReferencePartitionIndex().putRepresentativeTuple(partition, new Serializable[]{role});
	}

	/**
	 * Creates the index object of a partition.
	 *
	 * @param partition the partition primary key
	 * @param target    the target
	 * @param role      the representative value
	 * @return the index object
	 */
	@Nonnull
	private static ReducedEntityIndex newObject(int partition, int target, @Nonnull String role) {
		return new ReducedEntityIndex(
			partition, ENTITY_TYPE, ReferencePartitions.createKey(Scope.LIVE, keyOf(target, role))
		);
	}

	/**
	 * Creates the key of a partition.
	 *
	 * @param target the target
	 * @param role   the representative value
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey keyOf(int target, @Nonnull String role) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, target), new Serializable[]{role});
	}

	/**
	 * Returns the only owner of a partition of the grid - consecutive partitions share an owner `tuples` at a time.
	 *
	 * @param partition the partition primary key, from `1`
	 * @param tuples    how many partitions each owner has
	 * @return the owner
	 */
	private static int ownerOf(int partition, int tuples) {
		return (partition - 1) / tuples + 1;
	}

	/**
	 * Returns the representative value of a partition of the grid - one of its own.
	 *
	 * @param partition the partition primary key
	 * @return the representative value
	 */
	@Nonnull
	private static String tupleOf(int partition) {
		return "role-" + partition;
	}

}
