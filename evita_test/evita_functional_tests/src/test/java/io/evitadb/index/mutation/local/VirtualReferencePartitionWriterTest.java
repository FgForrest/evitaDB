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


package io.evitadb.index.mutation.local;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VirtualReferencePartitionWriter} against a real {@link VirtualReferencePartitionIndex}: which
 * references it writes virtually, what a partition entering the registry records, and that a partition sharing or
 * vacating leaves nothing of itself behind - including the declared keys once no partition has a row.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition writer")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionWriterTest {
	private static final String REFERENCE_NAME = "gallery";
	/**
	 * A sort key the drop tests declare.
	 */
	private static final AttributeIndexKey ORDER = new AttributeIndexKey(REFERENCE_NAME, "order", null);
	/**
	 * A chain key the drop tests declare.
	 */
	private static final AttributeIndexKey CHAIN = new AttributeIndexKey(REFERENCE_NAME, "chain", null);

	/**
	 * Builds a reference indexed the given way in the live scope and not at all in the archive.
	 *
	 * @param indexType  how the reference is indexed in the live scope
	 * @param components the components it indexes there
	 * @return the reference
	 */
	@Nonnull
	private static ReferenceSchema reference(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull Set<ReferenceIndexedComponents> components
	) {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME, Map.of(), null, null, Cardinality.ZERO_OR_MORE,
			"media", Map.of(), false,
			"mediaGroup", Map.of(), false,
			Map.of(Scope.LIVE, indexType),
			Map.of(Scope.LIVE, components),
			Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Registers partition 1 of owner 10 and partition 2 of owner 20 and declares a sort and a chain key holding an
	 * entry of each.
	 *
	 * @return the registry
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex twoRegisteredPartitions() {
		final VirtualReferencePartitionIndex registry = new VirtualReferencePartitionIndex();
		VirtualReferencePartitionWriter.partitionEntered(registry, 1, 10, key(100, "detail"));
		VirtualReferencePartitionWriter.partitionEntered(registry, 2, 20, key(200, "detail"));
		registry.declareSortKey(
			ORDER,
			new ComparatorSource[]{
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			},
			0,
			() -> SortIndex.createTypeLevelNormalizerFor(new ComparatorSource[]{
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			}, 0)
		);
		registry.declareChainKey(CHAIN);
		for (int partition = 1; partition <= 2; partition++) {
			registry.putSortValue(ORDER, partition, partition);
			registry.putChainPredecessor(CHAIN, partition, -1);
		}
		return registry;
	}

	/**
	 * Creates the key of a gallery partition.
	 *
	 * @param target the referenced media
	 * @param role   the representative role, none when `null`
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey key(int target, @Nullable String role) {
		return role == null ?
			new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, target)) :
			new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, target), new Serializable[]{role});
	}

	@Nested
	@DisplayName("References written virtually")
	class ReferencesWrittenVirtually {

		@Test
		@DisplayName("a reference indexed for filtering with the referenced entity component is written virtually")
		void shouldWriteAFilteringReferenceOfReferencedEntitiesVirtually() {
			assertTrue(
				VirtualReferencePartitionWriter.isWrittenVirtually(
					reference(ReferenceIndexType.FOR_FILTERING, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)),
					Scope.LIVE
				)
			);
			assertTrue(
				VirtualReferencePartitionWriter.isWrittenVirtually(
					reference(
						ReferenceIndexType.FOR_FILTERING,
						Set.of(
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						)
					),
					Scope.LIVE
				)
			);
		}

		@Test
		@DisplayName("a reference indexed for partitioning, without the entity component or not at all is not")
		void shouldNotWriteAnyOtherReferenceVirtually() {
			assertFalse(
				VirtualReferencePartitionWriter.isWrittenVirtually(
					reference(
						ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
						Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)
					),
					Scope.LIVE
				)
			);
			assertFalse(
				VirtualReferencePartitionWriter.isWrittenVirtually(
					reference(
						ReferenceIndexType.FOR_FILTERING, Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
					),
					Scope.LIVE
				)
			);
			assertFalse(
				VirtualReferencePartitionWriter.isWrittenVirtually(
					reference(ReferenceIndexType.FOR_FILTERING, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)),
					Scope.ARCHIVED
				),
				"the reference is not indexed in the archive"
			);
		}
	}

	@Nested
	@DisplayName("Entering")
	class Entering {

		@Test
		@DisplayName("an entering partition records its owner, its referenced entity and its tuple")
		void shouldRecordOwnerTargetAndTuple() {
			final VirtualReferencePartitionIndex registry = new VirtualReferencePartitionIndex();

			VirtualReferencePartitionWriter.partitionEntered(registry, 7, -3, key(Integer.MAX_VALUE, "detail"));

			final long row = registry.getPartitionOwner(7).orElseThrow();
			assertEquals(-3, NumberUtils.unpackHigh(row));
			assertEquals(Integer.MAX_VALUE, NumberUtils.unpackLow(row));
			assertArrayEquals(
				new Serializable[]{"detail"}, registry.getTuple(registry.getRepresentativeTupleId(7))
			);
			assertEquals(2, VirtualReferencePartitionIndexEntries.count(registry));
		}

		@Test
		@DisplayName("a partition without representative attributes records no tuple")
		void shouldRecordNoTupleForTheEmptyOne() {
			final VirtualReferencePartitionIndex registry = new VirtualReferencePartitionIndex();

			VirtualReferencePartitionWriter.partitionEntered(registry, 7, 3, key(5, null));

			assertTrue(registry.getPartitionOwner(7).isPresent());
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, registry.getRepresentativeTupleId(7));
			assertEquals(1, VirtualReferencePartitionIndexEntries.count(registry));
		}
	}

	@Nested
	@DisplayName("Dropping")
	class Dropping {

		@Test
		@DisplayName("a shared partition leaves nothing of itself, and the keys stay while another row remains")
		void shouldDropASharedPartitionAndKeepTheKeys() {
			final VirtualReferencePartitionIndex registry = twoRegisteredPartitions();

			VirtualReferencePartitionWriter.partitionShared(registry, 1);

			assertTrue(registry.getPartitionOwner(1).isEmpty());
			assertEquals(Set.of(ORDER), registry.getDeclaredSortKeys());
			assertEquals(Set.of(CHAIN), registry.getDeclaredChainKeys());
			assertEquals(
				4, VirtualReferencePartitionIndexEntries.count(registry),
				"row, tuple, sort value and predecessor of partition 2"
			);
		}

		@Test
		@DisplayName("the last row shared away retires every declared key")
		void shouldRetireTheKeysWhenTheLastRowIsShared() {
			final VirtualReferencePartitionIndex registry = twoRegisteredPartitions();

			VirtualReferencePartitionWriter.partitionShared(registry, 1);
			VirtualReferencePartitionWriter.partitionShared(registry, 2);

			assertEquals(Set.of(), registry.getDeclaredSortKeys());
			assertEquals(Set.of(), registry.getDeclaredChainKeys());
			assertTrue(registry.isEmpty());
		}

		@Test
		@DisplayName("the last row vacated retires every declared key")
		void shouldRetireTheKeysWhenTheLastRowIsVacated() {
			final VirtualReferencePartitionIndex registry = twoRegisteredPartitions();

			VirtualReferencePartitionWriter.partitionVacated(registry, 2);
			assertEquals(Set.of(ORDER), registry.getDeclaredSortKeys());
			VirtualReferencePartitionWriter.partitionVacated(registry, 1);

			assertTrue(registry.isEmpty());
			final int[] owners = {0};
			VirtualReferencePartitionIndexEntries.forEachOwnerRow(registry, (partition, payload) -> owners[0]++);
			assertEquals(0, owners[0]);
		}
	}
}
