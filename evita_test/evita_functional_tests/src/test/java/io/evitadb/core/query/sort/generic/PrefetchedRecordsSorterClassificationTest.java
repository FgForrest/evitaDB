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

package io.evitadb.core.query.sort.generic;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.comparator.AttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.TraverseReferenceAttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.TraverseReferenceCompoundAttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.TraverseReferencePredecessorAttributeComparator;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.SortResult;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_CHAIN;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_ORDER;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_TAIL;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_1;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_2;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_3;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.COMPOUND;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.OWNERS;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.blockKey;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.sort;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the classification lifecycle of {@link PrefetchedRecordsSorter}: the comparator is reset, learns the
 * selection, is bound to the key of the sorted block, classifies every entity exactly once, sorts only the entities
 * it classified as sortable and is unbound again - so an entity without the sorted data is handed to the next sorter
 * even when it is the only entity of its block, which a detection inside the comparison alone never notices.
 *
 * The owners, their references and the prefetched execution context are those of {@link PrefetchedOwnersFixture}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Prefetched records sorter classification lifecycle")
@Tag(ENGINE)
@Tag(ORDER)
class PrefetchedRecordsSorterClassificationTest {
	/**
	 * Creates the traversal comparator of the `order` attribute in the direction.
	 */
	@Nonnull
	private static TraverseReferenceAttributeComparator orderComparator(@Nonnull OrderDirection direction) {
		return new TraverseReferenceAttributeComparator(
			ATTRIBUTE_ORDER, Integer.class, REFERENCE_SCHEMA, null, direction
		);
	}

	/**
	 * Creates the traversal comparator of the chains: category 1 has a live chain 15, 11, 13 and an archived chain
	 * holding 18 alone - two providers sharing one block key, the live one first - and category 2 a chain holding 14;
	 * category 3 has none.
	 */
	@Nonnull
	private static TraverseReferencePredecessorAttributeComparator chainComparator() {
		final SortedRecordsProvider[] providers = {
			new ReferenceSortedRecordsProvider(
				1L, new int[]{15, 11, 13}, new int[]{1, 2, 0}, new BaseBitmap(11, 13, 15),
				position -> null, BLOCK_1
			),
			new ReferenceSortedRecordsProvider(
				2L, new int[]{18}, new int[]{0}, new BaseBitmap(18), position -> null, BLOCK_1
			),
			new ReferenceSortedRecordsProvider(
				3L, new int[]{14}, new int[]{0}, new BaseBitmap(14), position -> null, BLOCK_2
			)
		};
		return new TraverseReferencePredecessorAttributeComparator(
			ATTRIBUTE_CHAIN, Predecessor.class, REFERENCE_SCHEMA, null, OrderDirection.ASC, () -> providers
		);
	}

	@Test
	@DisplayName("should pass on a lone owner whose row of the block lacks the value")
	void shouldPassOnLoneOwnerWithoutValue() {
		// owner 12 is alone in the block of category 1 and its row there has no `order` - sorting one element
		// compares nothing, so only the classification can pass it on to the block of category 2
		final SortResult sorted = sort(orderComparator(OrderDirection.ASC), BLOCK_1, 0, 10, 12);

		assertAll(
			() -> assertArrayEquals(new int[0], sorted.written(), "no owner may be claimed by the block"),
			() -> assertArrayEquals(new int[]{12}, sorted.passedOn(), "the owner moves on to the next block")
		);
	}

	@Test
	@DisplayName("should claim a lone owner whose row of the block carries the value")
	void shouldClaimLoneOwnerWithValue() {
		final SortResult sorted = sort(orderComparator(OrderDirection.ASC), BLOCK_1, 0, 10, 13);

		assertAll(
			() -> assertArrayEquals(new int[]{13}, sorted.written()),
			() -> assertArrayEquals(new int[0], sorted.passedOn())
		);
	}

	@Test
	@DisplayName("should sort only the owners carrying the value of the block and pass on the rest")
	void shouldSortOnlyOwnersCarryingValueOfBlock() {
		// 12 has a value-less row of the block, 14 no row of the block at all; 13 and 15 tie on 10
		final SortResult ascending = sort(orderComparator(OrderDirection.ASC), BLOCK_1, 0, 10, 11, 12, 13, 14, 15);
		final SortResult descending = sort(orderComparator(OrderDirection.DESC), BLOCK_1, 0, 10, 11, 12, 13, 14, 15);

		assertAll(
			() -> assertArrayEquals(new int[]{13, 15, 11}, ascending.written(), "ascending, ties by owner"),
			() -> assertArrayEquals(new int[]{12, 14}, ascending.passedOn(), "ascending passes on"),
			() -> assertArrayEquals(new int[]{11, 15, 13}, descending.written(), "descending, ties by owner"),
			() -> assertArrayEquals(new int[]{12, 14}, descending.passedOn(), "descending passes on")
		);
	}

	@Test
	@DisplayName("should skip and slice only the sortable owners")
	void shouldSkipAndSliceOnlySortableOwners() {
		final SortResult sorted = sort(orderComparator(OrderDirection.ASC), BLOCK_1, 1, 2, 11, 12, 13, 14, 15);

		assertAll(
			() -> assertArrayEquals(new int[]{15}, sorted.written()),
			() -> assertArrayEquals(new int[]{13}, sorted.skipped()),
			() -> assertArrayEquals(new int[]{12, 14}, sorted.passedOn())
		);
	}

	@Test
	@DisplayName("should reset, prepare, bind, classify each entity once, sort the sortable ones and unbind")
	void shouldDriveComparatorThroughLifecycle() {
		final RecordingComparator comparator = new RecordingComparator(orderComparator(OrderDirection.ASC));

		final SortResult sorted = sort(comparator, BLOCK_1, 0, 10, 11, 12, 13, 14, 15);

		final List<String> calls = comparator.calls;
		assertAll(
			() -> assertArrayEquals(new int[]{13, 15, 11}, sorted.written()),
			() -> assertEquals(
				List.of(
					"prepareFor", "prepareForSelection [11, 12, 13, 14, 15]", "bind",
					"canSort 11", "canSort 12", "canSort 13", "canSort 14", "canSort 15"
				),
				calls.subList(0, 8),
				"the comparator is reset and prepared before the block key is bound, then classifies in order"
			),
			() -> assertEquals("unbind", calls.get(calls.size() - 1), "the block key is released at the end"),
			() -> assertTrue(
				calls.subList(8, calls.size() - 1).stream().allMatch(it -> it.startsWith("compare")),
				"only comparisons happen between the classification and the unbinding: " + calls
			),
			() -> assertFalse(
				calls.stream().anyMatch(it -> it.startsWith("compare") && (it.contains(" 12") || it.contains(" 14"))),
				"owners classified as not sortable are never compared: " + calls
			)
		);
	}

	@Test
	@DisplayName("should release the block key when the classification fails")
	void shouldUnbindWhenClassificationFails() {
		final TraverseReferenceAttributeComparator delegate = orderComparator(OrderDirection.ASC);
		final RecordingComparator failing = new RecordingComparator(delegate) {
			@Override
			public boolean canSort(@Nonnull EntityContract entity) {
				super.canSort(entity);
				throw new IllegalStateException("classification failed");
			}
		};

		assertThrows(IllegalStateException.class, () -> sort(failing, BLOCK_1, 0, 10, 11, 13));
		// the very comparator that failed binds again - a key left bound refuses the second binding
		assertArrayEquals(new int[]{13}, sort(delegate, BLOCK_1, 0, 10, 13).written());
	}

	@Test
	@DisplayName("should hand on the entities a comparator without classification reports while comparing")
	void shouldHandOnEntitiesReportedByComparison() {
		// the entity-level attribute comparator keeps the default classification and detects owner 12 lacking
		// `tail` only while comparing - that channel must keep working
		final SortResult sorted = sort(
			new AttributeComparator(ATTRIBUTE_TAIL, Integer.class, null, OrderDirection.ASC), null, 0, 10,
			11, 12, 13, 14
		);

		assertAll(
			() -> assertArrayEquals(new int[]{13, 11, 14}, sorted.written()),
			() -> assertArrayEquals(new int[]{12}, sorted.passedOn())
		);
	}

	@Test
	@DisplayName("should count an entity both classified out and reported while comparing only once")
	void shouldNotDoubleCountEntityClassifiedOutAndReported() {
		// a comparator that rejects owner 12 up front and still reports it, next to owner 14 detected while comparing
		final CompositeObjectArray<EntityContract> reported = new CompositeObjectArray<>(EntityContract.class);
		final EntityComparator comparator = new EntityComparator() {
			@Override
			public boolean canSort(@Nonnull EntityContract entity) {
				if (entity.getPrimaryKeyOrThrowException() == 12) {
					reported.add(entity);
					return false;
				}
				return true;
			}

			@Nonnull
			@Override
			public Iterable<EntityContract> getNonSortedEntities() {
				return reported;
			}

			@Override
			public int compare(EntityContract o1, EntityContract o2) {
				for (EntityContract entity : new EntityContract[]{o1, o2}) {
					if (entity.getPrimaryKeyOrThrowException() == 14 && !contains(reported, entity)) {
						reported.add(entity);
					}
				}
				return Integer.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException());
			}
		};

		final SortResult sorted = sort(comparator, null, 0, 10, 11, 12, 13, 14);

		assertAll(
			() -> assertArrayEquals(new int[]{11, 13}, sorted.written()),
			() -> assertArrayEquals(new int[]{12, 14}, sorted.passedOn())
		);
	}

	@Test
	@DisplayName("should refuse a comparator that keeps a reported entity inside the sortable slice")
	void shouldRefuseReportedEntityInsideSortableSlice() {
		// owner 11 is reported as not sortable but ordered first - downstream sorters would emit it twice
		final EntityComparator comparator = new EntityComparator() {
			@Nonnull
			@Override
			public Iterable<EntityContract> getNonSortedEntities() {
				return List.of(OWNERS.get(11));
			}

			@Override
			public int compare(EntityContract o1, EntityContract o2) {
				return Integer.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException());
			}
		};

		assertThrows(EvitaInternalError.class, () -> sort(comparator, null, 0, 10, 11, 13));
	}

	@Test
	@DisplayName("should refuse a traversal comparison of an entity that was not classified as sortable")
	void shouldRefuseComparisonOfUnclassifiedEntity() {
		final TraverseReferenceAttributeComparator comparator = orderComparator(OrderDirection.ASC);
		comparator.prepareFor(2);

		comparator.withReferencedEntityId(
			BLOCK_1,
			() -> assertThrows(EvitaInternalError.class, () -> comparator.compare(OWNERS.get(11), OWNERS.get(12)))
		);
	}

	@Test
	@DisplayName("should pass on owners without the compound value of the block, a lone one included")
	void shouldClassifyByCompound() {
		final SortableAttributeCompoundSchemaContract compound =
			REFERENCE_SCHEMA.getSortableAttributeCompound(COMPOUND).orElseThrow();
		final TraverseReferenceCompoundAttributeComparator comparator =
			new TraverseReferenceCompoundAttributeComparator(
				compound, REFERENCE_SCHEMA, null,
				attributeName -> REFERENCE_SCHEMA.getAttribute(attributeName).map(AttributeSchemaContract.class::cast)
					.orElseThrow(),
				OrderDirection.ASC
			);

		final SortResult lone = sort(comparator, BLOCK_1, 0, 10, 16);
		final SortResult block = sort(comparator, BLOCK_1, 0, 10, 11, 12, 13, 14, 15, 16);

		assertAll(
			() -> assertArrayEquals(new int[0], lone.written(), "the lone owner has no compound value"),
			() -> assertArrayEquals(new int[]{16}, lone.passedOn()),
			() -> assertArrayEquals(new int[]{15, 13, 11}, block.written(), "(10,2) (10,3) (50,1)"),
			() -> assertArrayEquals(new int[]{12, 14, 16}, block.passedOn())
		);
	}

	@Test
	@DisplayName("should pass on owners the chain of the block cannot place, a lone one included")
	void shouldClassifyByChainPosition() {
		final SortResult block = sort(chainComparator(), BLOCK_1, 0, 10, 11, 12, 13, 15, 16);
		final SortResult lone = sort(chainComparator(), BLOCK_1, 0, 10, 16);
		final SortResult noChain = sort(chainComparator(), BLOCK_3, 0, 10, 17);

		assertAll(
			() -> assertArrayEquals(new int[]{15, 11, 13}, block.written(), "chain order of category 1"),
			() -> assertArrayEquals(new int[]{12, 16}, block.passedOn(), "value-less 12, 16 absent in the chain"),
			() -> assertArrayEquals(new int[0], lone.written(), "16 carries a value but the chain lacks it"),
			() -> assertArrayEquals(new int[]{16}, lone.passedOn()),
			() -> assertArrayEquals(new int[0], noChain.written(), "category 3 has no chain provider"),
			() -> assertArrayEquals(new int[]{17}, noChain.passedOn())
		);
	}

	@Test
	@DisplayName("should admit an owner held by a later provider sharing the block key, across blocks")
	void shouldClassifyByEveryProviderOfBlock() {
		// category 1 is backed by two providers - the live chain 15, 11, 13 and the archived chain holding 18 - so 18
		// belongs to the block although the first provider lacks it, and follows the live chain; one comparator
		// instance then sorts the block of category 2 and the block of category 1 again
		final TraverseReferencePredecessorAttributeComparator comparator = chainComparator();
		final SortResult first = sort(comparator, BLOCK_1, 0, 10, 11, 13, 14, 15, 18);
		final SortResult second = sort(comparator, BLOCK_2, 0, 10, 14, 18);
		final SortResult loneArchived = sort(comparator, BLOCK_1, 0, 10, 18);

		assertAll(
			() -> assertArrayEquals(new int[]{15, 11, 13, 18}, first.written(), "live chain, then archived chain"),
			() -> assertArrayEquals(new int[]{14}, first.passedOn(), "14 has no row of category 1"),
			() -> assertArrayEquals(new int[]{14}, second.written(), "the block of category 2"),
			() -> assertArrayEquals(new int[]{18}, second.passedOn(), "18 has no row of category 2"),
			() -> assertArrayEquals(new int[]{18}, loneArchived.written(), "the second provider places 18 alone")
		);
	}

	@Test
	@DisplayName("should admit every owner to the chain comparison when no block is bound")
	void shouldAdmitEveryOwnerToChainWithoutBlock() {
		final TraverseReferencePredecessorAttributeComparator comparator = chainComparator();
		comparator.prepareFor(2);

		assertAll(
			() -> assertTrue(comparator.canSort(OWNERS.get(12)), "value-less owner is left to the comparison"),
			() -> assertTrue(comparator.canSort(OWNERS.get(16)), "unplaced owner is left to the comparison")
		);
	}

	@Test
	@DisplayName("should classify every block afresh with one comparator instance")
	void shouldClassifyEachBlockAfresh() {
		// owner 12 lacks the value in block 1 but carries it in block 2 - the reset between blocks must not let the
		// classification of one block leak into the next
		final TraverseReferenceAttributeComparator comparator = orderComparator(OrderDirection.ASC);
		final SortResult first = sort(comparator, BLOCK_1, 0, 10, 12);
		final SortResult second = sort(comparator, blockKey(2), 0, 10, 12);

		assertAll(
			() -> assertArrayEquals(new int[]{12}, first.passedOn(), "block 1 passes the owner on"),
			() -> assertArrayEquals(new int[]{12}, second.written(), "block 2 claims it")
		);
	}

	/**
	 * Tells whether the array holds the very entity.
	 */
	private static boolean contains(
		@Nonnull CompositeObjectArray<EntityContract> array,
		@Nonnull EntityContract entity
	) {
		for (EntityContract item : array) {
			if (item == entity) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A reference sensitive comparator recording every lifecycle call before delegating to a real comparator.
	 */
	private static class RecordingComparator implements EntityReferenceSensitiveComparator {
		/**
		 * The comparator the calls are delegated to.
		 */
		@Nonnull private final TraverseReferenceAttributeComparator delegate;
		/**
		 * The calls in the order they arrived.
		 */
		@Nonnull final List<String> calls = new ArrayList<>(32);

		RecordingComparator(@Nonnull TraverseReferenceAttributeComparator delegate) {
			this.delegate = delegate;
		}

		@Override
		public void prepareFor(int entityCount) {
			this.calls.add("prepareFor");
			this.delegate.prepareFor(entityCount);
		}

		@Override
		public void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {
			this.calls.add("prepareForSelection " + Arrays.toString(entityPrimaryKeys.getArray()));
			this.delegate.prepareForSelection(entityPrimaryKeys);
		}

		@Override
		public void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda) {
			this.calls.add("bind");
			try {
				this.delegate.withReferencedEntityId(referenceKey, lambda);
			} finally {
				this.calls.add("unbind");
			}
		}

		@Override
		public boolean canSort(@Nonnull EntityContract entity) {
			this.calls.add("canSort " + entity.getPrimaryKeyOrThrowException());
			return this.delegate.canSort(entity);
		}

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return this.delegate.getNonSortedEntities();
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			this.calls.add("compare " + o1.getPrimaryKeyOrThrowException() + " " + o2.getPrimaryKeyOrThrowException());
			return this.delegate.compare(o1, o2);
		}
	}

}
