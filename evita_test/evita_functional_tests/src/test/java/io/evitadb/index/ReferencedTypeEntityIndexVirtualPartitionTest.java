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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionRootRemoval;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link ReferencedTypeEntityIndex} carries its {@link VirtualReferencePartitionIndex}: only the
 * referenced entity family hands it out, its entries keep the type index from being empty, its writes reach the
 * flush, its committed state reaches the next version of the type index, and dropping the type index reclaims its
 * root.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Referenced type entity index carrying virtual reference partitions")
@Tag(INDEXING)
@Tag(REFERENCE)
class ReferencedTypeEntityIndexVirtualPartitionTest {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "media";
	private static final int INDEX_PK = 11;

	/**
	 * Creates a fresh type index of the given family.
	 *
	 * @param type the family
	 * @return the type index
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex typeIndex(@Nonnull EntityIndexType type) {
		return new ReferencedTypeEntityIndex(
			INDEX_PK, ENTITY_TYPE, new EntityIndexKey(type, Scope.LIVE, REFERENCE_NAME)
		);
	}

	/**
	 * Drains the parts a sink collected.
	 *
	 * @param trappedChanges the sink
	 * @return the collected parts
	 */
	@Nonnull
	private static List<StoragePart> drain(@Nonnull TrappedChanges trappedChanges) {
		final List<StoragePart> parts = new ArrayList<>();
		trappedChanges.getTrappedChangesIterator().forEachRemaining(parts::add);
		return parts;
	}

	@Test
	@DisplayName("only the referenced entity family hands the index out")
	void shouldHandOutTheIndexOnlyForTheReferencedEntityFamily() {
		assertTrue(typeIndex(EntityIndexType.REFERENCED_ENTITY_TYPE).getVirtualReferencePartitionIndex().isEmpty());
		final ReferencedTypeEntityIndex groupIndex = typeIndex(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE);
		assertThrows(GenericEvitaInternalError.class, groupIndex::getVirtualReferencePartitionIndex);
	}

	@Test
	@DisplayName("a type index holding only virtual partitions is not empty")
	void shouldNotBeEmptyWhileItHoldsVirtualPartitions() {
		final ReferencedTypeEntityIndex index = typeIndex(EntityIndexType.REFERENCED_ENTITY_TYPE);
		assertTrue(index.isEmpty());
		index.getVirtualReferencePartitionIndex().putPartitionOwner(5, 50, 500);
		assertFalse(index.isEmpty());
		index.getVirtualReferencePartitionIndex().removePartitionOwner(5);
		assertTrue(index.isEmpty());
	}

	@Test
	@DisplayName("a write to the index reaches the flush of the type index")
	void shouldFlushTheIndexWithTheTypeIndex() {
		final ReferencedTypeEntityIndex index = typeIndex(EntityIndexType.REFERENCED_ENTITY_TYPE);
		final TrappedChanges before = new TrappedChanges();
		index.getModifiedStorageParts(before);
		assertTrue(
			drain(before).stream().noneMatch(VirtualReferencePartitionIndexStoragePart.class::isInstance),
			"An unused index must not be flushed"
		);

		index.getVirtualReferencePartitionIndex().putPartitionOwner(5, 50, 500);
		final TrappedChanges after = new TrappedChanges();
		index.getModifiedStorageParts(after);
		final List<VirtualReferencePartitionIndexStoragePart> roots = drain(after).stream()
			.filter(VirtualReferencePartitionIndexStoragePart.class::isInstance)
			.map(VirtualReferencePartitionIndexStoragePart.class::cast)
			.toList();
		assertEquals(1, roots.size());
		assertEquals(INDEX_PK, roots.get(0).getEntityIndexPrimaryKey());
		assertEquals(REFERENCE_NAME, roots.get(0).getReferenceName());
	}

	@Test
	@DisplayName("the committed type index carries the committed partitions")
	void shouldCarryThePartitionsIntoTheCommittedTypeIndex() {
		final ReferencedTypeEntityIndex index = typeIndex(EntityIndexType.REFERENCED_ENTITY_TYPE);
		final Serializable[] gallery = {"gallery"};
		assertStateAfterCommit(
			index,
			original -> {
				original.getVirtualReferencePartitionIndex().putPartitionOwner(5, 50, 500);
				original.getVirtualReferencePartitionIndex().putRepresentativeTuple(5, gallery);
			},
			(original, committed) -> {
				assertNotSame(original, committed);
				assertTrue(original.getVirtualReferencePartitionIndex().isEmpty());
				final VirtualReferencePartitionIndex committedPartitions =
					committed.getVirtualReferencePartitionIndex();
				assertEquals(OptionalLong.of(NumberUtils.pack(50, 500)), committedPartitions.getPartitionOwner(5));
				assertArrayEquals(
					gallery, committedPartitions.getTuple(committedPartitions.getRepresentativeTupleId(5))
				);
			}
		);
	}

	@Test
	@DisplayName("dropping the type index reclaims the root of the index")
	void shouldReclaimTheRootWhenTheTypeIndexIsDropped() {
		final ReferencedTypeEntityIndex index = typeIndex(EntityIndexType.REFERENCED_ENTITY_TYPE);
		final TrappedChanges removals = new TrappedChanges();
		index.emitFootprintRemovals(removals);
		assertEquals(
			1, drain(removals).stream().filter(VirtualReferencePartitionRootRemoval.class::isInstance).count()
		);
	}
}
