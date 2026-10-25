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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VirtualPartitionSortedRecords}: the singleton sorted records of one virtual partition read out of
 * its registry row - the sort value of a key, the exact-value test of `attributeSetExact`, the chain of a chained
 * attribute keyed by the owner or by the referenced entity, and the referenced primary key. Each provider holds the
 * partition's owner (or target) as its only record at position `0` and carries the partition's representative
 * reference key, as the singleton sort or chain index of the partition's reduced entity index would.
 *
 * The type index and its registry are real, filled the way the registry writer fills them; the handles come from
 * {@link ReferencePartitions#resolveVirtualIfExists(ReferencedTypeEntityIndex, int)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sorted records of one virtual partition")
@Tag(INDEXING)
@Tag(ORDER)
@Tag(REFERENCE)
class VirtualPartitionSortedRecordsTest {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "media";
	/**
	 * A sort key of a `BigDecimal` attribute indexed with two decimal places - its normalized value is a scaled
	 * `Integer`, so the stored value and the attribute value differ.
	 */
	private static final AttributeIndexKey PRICE = new AttributeIndexKey(REFERENCE_NAME, "price", null);
	/**
	 * A sort key the fixture never declares.
	 */
	private static final AttributeIndexKey UNDECLARED = new AttributeIndexKey(REFERENCE_NAME, "undeclared", null);
	/**
	 * A chain key of the fixture.
	 */
	private static final AttributeIndexKey POSITION = new AttributeIndexKey(REFERENCE_NAME, "position", null);
	/**
	 * The partition of the fixture.
	 */
	private static final int PARTITION = 7;
	/**
	 * The only owner of {@link #PARTITION}.
	 */
	private static final int OWNER = 70;
	/**
	 * The target of {@link #PARTITION}.
	 */
	private static final int TARGET = 700;
	/**
	 * The representative value of {@link #PARTITION}.
	 */
	private static final String ROLE = "detail";

	/**
	 * The type index holding the row of {@link #PARTITION}.
	 */
	private final ReferencedTypeEntityIndex typeIndex = new ReferencedTypeEntityIndex(
		1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
	);
	/**
	 * The registry of {@link #typeIndex}.
	 */
	private final VirtualReferencePartitionIndex registry = this.typeIndex.getVirtualReferencePartitionIndex();

	VirtualPartitionSortedRecordsTest() {
		register(PARTITION, OWNER, TARGET);
		this.registry.declareSortKey(
			PRICE,
			new ComparatorSource[]{new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)},
			2,
			() -> SortIndex.createLegacyNormalizerFor(new ComparatorSource[]{new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)}, 2)
		);
		this.registry.declareChainKey(POSITION);
	}

	/**
	 * Lists a partition in the type index and registers its row with the fixture's representative value, as the
	 * registry writer does.
	 *
	 * @param partition the partition primary key
	 * @param owner     the only owner
	 * @param target    the target
	 */
	private void register(int partition, int owner, int target) {
		this.typeIndex.insertPrimaryKeyIfMissing(partition, target);
		this.registry.putPartitionOwner(partition, owner, target);
		this.registry.putRepresentativeTuple(partition, new Serializable[]{ROLE});
	}

	/**
	 * Returns the virtual handle of a registered partition.
	 *
	 * @param partition the partition primary key
	 * @return the handle
	 */
	@Nonnull
	private VirtualReferencePartition handleOf(int partition) {
		return Objects.requireNonNull(ReferencePartitions.resolveVirtualIfExists(this.typeIndex, partition));
	}

	/**
	 * Asserts a provider is the singleton of one record carrying the fixture's representative reference key.
	 *
	 * @param provider the provider
	 * @param recordId the expected only record
	 * @param value    the expected value of the record
	 */
	private static void assertSingleton(@Nonnull SortedRecordsProvider provider, int recordId, @Nonnull Serializable value) {
		final ReferenceSortedRecordsProvider singleton = assertInstanceOf(ReferenceSortedRecordsProvider.class, provider);
		assertEquals(PARTITION, singleton.getTransactionalId(), "The provider is identified by its partition");
		assertEquals(1, singleton.getRecordCount());
		assertArrayEquals(new int[]{recordId}, singleton.getSortedRecordIds());
		assertArrayEquals(new int[]{0}, singleton.getRecordPositions());
		assertArrayEquals(new int[]{recordId}, singleton.getAllRecords().getArray());
		assertEquals(value, singleton.getSortedComparableForwardSeeker().getValueToCompareOn(0));
		assertEquals(
			new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, TARGET), new Serializable[]{ROLE}),
			singleton.getReferenceKey()
		);
	}

	@Nested
	@DisplayName("sort value")
	class SortValue {

		@Test
		@DisplayName("a partition without a value for the key has no provider")
		void shouldHaveNoProviderWithoutAValue() {
			assertNull(VirtualPartitionSortedRecords.ofSortValue(handleOf(PARTITION), PRICE));
			assertNull(VirtualPartitionSortedRecords.ofSortValue(handleOf(PARTITION), UNDECLARED));
		}

		@Test
		@DisplayName("the provider holds the owner at position 0, its value the normalized value of the registry")
		void shouldHoldTheOwnerWithTheNormalizedValue() {
			VirtualPartitionSortedRecordsTest.this.registry.putSortValue(PRICE, PARTITION, new BigDecimal("1.5"));
			final Serializable normalized = VirtualPartitionSortedRecordsTest.this.registry.getNormalizedSortValue(
				PRICE, VirtualPartitionSortedRecordsTest.this.registry.getSortValueId(PRICE, PARTITION)
			);

			final SortedRecordsProvider provider = VirtualPartitionSortedRecords.ofSortValue(handleOf(PARTITION), PRICE);

			assertNotNull(provider);
			assertSingleton(provider, OWNER, normalized);
			assertEquals(150, normalized, "The value is the registry's scaled form, not the attribute value");
		}

	}

	@Nested
	@DisplayName("exact value")
	class ExactValue {

		@Test
		@DisplayName("an equal value answers the owner, an unequal one nothing, normalized as the registry normalizes")
		void shouldAnswerTheOwnerForAnEqualValueOnly() {
			VirtualPartitionSortedRecordsTest.this.registry.putSortValue(PRICE, PARTITION, new BigDecimal("1.5"));
			final VirtualReferencePartition partition = handleOf(PARTITION);

			assertArrayEquals(
				new int[]{OWNER},
				VirtualPartitionSortedRecords.getRecordsEqualTo(partition, PRICE, new BigDecimal("1.50")).getArray()
			);
			assertTrue(
				VirtualPartitionSortedRecords.getRecordsEqualTo(partition, PRICE, new BigDecimal("2.5")).isEmpty()
			);
		}

		@Test
		@DisplayName("a partition without a value, or a key not declared, answers nothing")
		void shouldAnswerNothingWithoutAValueOrKey() {
			final VirtualReferencePartition partition = handleOf(PARTITION);

			assertTrue(
				VirtualPartitionSortedRecords.getRecordsEqualTo(partition, PRICE, new BigDecimal("1.5")).isEmpty()
			);
			assertTrue(
				VirtualPartitionSortedRecords.getRecordsEqualTo(partition, UNDECLARED, new BigDecimal("1.5")).isEmpty()
			);
		}

		@Test
		@DisplayName("one partition of a family sharing its value answers its own owner only")
		void shouldAnswerTheOwnPartitionOfALargeFamilySharingTheValue() {
			final int familySize = 10_000;
			for (int i = 1; i < familySize; i++) {
				register(PARTITION + i, OWNER + i, TARGET + i);
				VirtualPartitionSortedRecordsTest.this.registry.putSortValue(PRICE, PARTITION + i, new BigDecimal("1.5"));
			}
			VirtualPartitionSortedRecordsTest.this.registry.putSortValue(PRICE, PARTITION, new BigDecimal("1.5"));
			final VirtualReferencePartition partition = handleOf(PARTITION);
			final VirtualReferencePartitionIndex registry = VirtualPartitionSortedRecordsTest.this.registry;
			final int[] holders = {0};
			VirtualReferencePartitionIndexEntries.forEachSortValueBlock(
				registry, PRICE, (value, partitions) -> holders[0] += partitions.length
			);
			assertEquals(1, registry.getDistinctSortValueCount(PRICE), "The premise of the case: one value");
			assertEquals(
				familySize, holders[0],
				"The premise of the case: the value bucket of the registry's sort index holds the whole family"
			);

			assertArrayEquals(
				new int[]{OWNER},
				VirtualPartitionSortedRecords.getRecordsEqualTo(partition, PRICE, new BigDecimal("1.5")).getArray()
			);
			assertTrue(
				VirtualPartitionSortedRecords.getRecordsEqualTo(partition, PRICE, new BigDecimal("3")).isEmpty()
			);
			assertTrue(
				VirtualPartitionSortedRecords.getRecordsEqualTo(handleOf(PARTITION + 1), PRICE, new BigDecimal("1.50"))
					.contains(OWNER + 1),
				"Another partition of the family answers its own owner"
			);
		}

	}

	@Nested
	@DisplayName("chain")
	class Chain {

		@Test
		@DisplayName("a key not declared as a chain, or a partition not chained under it, has no provider")
		void shouldHaveNoProviderWhenNotChained() {
			final VirtualReferencePartition partition = handleOf(PARTITION);

			assertNull(VirtualPartitionSortedRecords.ofChain(partition, UNDECLARED, false));
			assertNull(VirtualPartitionSortedRecords.ofChain(partition, POSITION, false));
			assertNull(VirtualPartitionSortedRecords.ofChainFactory(partition, POSITION, true));
		}

		@Test
		@DisplayName("a chained partition holds the owner, or the target when keyed by the referenced entity")
		void shouldHoldTheRecordTheChainIsKeyedBy() {
			VirtualPartitionSortedRecordsTest.this.registry.putChainPredecessor(POSITION, PARTITION, 3);
			final VirtualReferencePartition partition = handleOf(PARTITION);

			final SortedRecordsProvider byOwner = VirtualPartitionSortedRecords.ofChain(partition, POSITION, false);
			final SortedRecordsProvider byTarget = VirtualPartitionSortedRecords.ofChain(partition, POSITION, true);

			assertNotNull(byOwner);
			assertSingleton(byOwner, OWNER, OWNER);
			assertNotNull(byTarget);
			assertSingleton(byTarget, TARGET, TARGET);
		}

		@Test
		@DisplayName("the factory of a chained partition answers one provider in both directions")
		void shouldAnswerOneProviderInBothDirections() {
			VirtualPartitionSortedRecordsTest.this.registry.putChainPredecessor(POSITION, PARTITION, 3);

			final SortedRecordsSupplierFactory factory = VirtualPartitionSortedRecords.ofChainFactory(
				handleOf(PARTITION), POSITION, false
			);

			assertNotNull(factory);
			assertSame(factory.getAscendingOrderRecordsSupplier(), factory.getDescendingOrderRecordsSupplier());
			assertSingleton(factory.getAscendingOrderRecordsSupplier(), OWNER, OWNER);
		}

	}

	@Test
	@DisplayName("the referenced primary key provider holds the owner, its value the target")
	void shouldHoldTheOwnerValuedByTheTarget() {
		assertSingleton(VirtualPartitionSortedRecords.ofReferencedPrimaryKey(handleOf(PARTITION)), OWNER, TARGET);
	}

}
