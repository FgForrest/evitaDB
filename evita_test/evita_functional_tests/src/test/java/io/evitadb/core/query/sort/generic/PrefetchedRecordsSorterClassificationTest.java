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
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.comparator.AttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.PredecessorAttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.TraverseReferenceAttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.TraverseReferenceCompoundAttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.TraverseReferencePredecessorAttributeComparator;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.SortResult;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_CHAIN;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_ORDER;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_TAIL;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_1;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_2;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_3;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.COMPOUND;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.DECIMAL_VARIANTS_REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.OWNERS;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.VARIANTS_REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.blockKey;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.decimalVariantKey;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.decimalVariantsBlockKey;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.sort;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.variantKey;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.variantsBlockKey;
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
			ATTRIBUTE_ORDER, Integer.class, 0, REFERENCE_SCHEMA, null, direction
		);
	}

	/**
	 * Creates the traversal comparator of the `orderSecond` compound in the ascending direction.
	 */
	@Nonnull
	private static TraverseReferenceCompoundAttributeComparator compoundComparator() {
		return compoundComparator(OrderDirection.ASC);
	}

	/**
	 * Creates the traversal comparator of the `orderSecond` compound in the direction.
	 */
	@Nonnull
	private static TraverseReferenceCompoundAttributeComparator compoundComparator(@Nonnull OrderDirection direction) {
		final SortableAttributeCompoundSchemaContract compound =
			REFERENCE_SCHEMA.getSortableAttributeCompound(COMPOUND).orElseThrow();
		return new TraverseReferenceCompoundAttributeComparator(
			compound, REFERENCE_SCHEMA, null,
			attributeName -> REFERENCE_SCHEMA.getAttribute(attributeName).map(AttributeSchemaContract.class::cast)
				.orElseThrow(),
			direction
		);
	}

	/**
	 * Creates the chain providers of the categories with the factory: category 1 has a live chain 15, 11, 13 and an
	 * archived chain holding 18 alone - two providers sharing one block key, the live one first - and category 2 a
	 * chain holding 14; category 3 has none.
	 */
	@Nonnull
	private static ReferenceSortedRecordsProvider[] chainProviders(@Nonnull ChainProviderFactory factory) {
		return new ReferenceSortedRecordsProvider[]{
			factory.create(1L, new int[]{15, 11, 13}, new int[]{1, 2, 0}, new BaseBitmap(11, 13, 15), BLOCK_1),
			factory.create(2L, new int[]{18}, new int[]{0}, new BaseBitmap(18), BLOCK_1),
			factory.create(3L, new int[]{14}, new int[]{0}, new BaseBitmap(14), BLOCK_2)
		};
	}

	/**
	 * Creates the traversal comparator of the chains of {@link #chainProviders(ChainProviderFactory)}.
	 */
	@Nonnull
	private static TraverseReferencePredecessorAttributeComparator chainComparator() {
		final SortedRecordsProvider[] providers = chainProviders(
			(transactionalId, sortedRecordIds, recordPositions, allRecords, referenceKey) ->
				new ReferenceSortedRecordsProvider(
					transactionalId, sortedRecordIds, recordPositions, allRecords, position -> null, referenceKey
				)
		);
		return chainComparator(() -> providers);
	}

	/**
	 * Creates the traversal comparator of the chains the supplier resolves.
	 */
	@Nonnull
	private static TraverseReferencePredecessorAttributeComparator chainComparator(
		@Nonnull Supplier<SortedRecordsProvider[]> providers
	) {
		return new TraverseReferencePredecessorAttributeComparator(
			ATTRIBUTE_CHAIN, Predecessor.class, REFERENCE_SCHEMA, null, OrderDirection.ASC, providers
		);
	}

	/**
	 * Asserts that the comparator reports no entity as not sortable.
	 */
	private static void assertNothingReported(@Nonnull EntityComparator comparator, @Nonnull String message) {
		assertFalse(comparator.getNonSortedEntities().iterator().hasNext(), message);
	}

	@Nested
	@DisplayName("Sorter lifecycle")
	class SorterLifecycle {

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
					calls.stream()
						.anyMatch(it -> it.startsWith("compare") && (it.contains(" 12") || it.contains(" 14"))),
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
		@DisplayName("should skip and slice only the sortable owners")
		void shouldSkipAndSliceOnlySortableOwners() {
			final SortResult sorted = sort(orderComparator(OrderDirection.ASC), BLOCK_1, 1, 2, 11, 12, 13, 14, 15);

			assertAll(
				() -> assertArrayEquals(new int[]{15}, sorted.written()),
				() -> assertArrayEquals(new int[]{13}, sorted.skipped()),
				() -> assertArrayEquals(new int[]{12, 14}, sorted.passedOn())
			);
		}
	}

	@Nested
	@DisplayName("Entities reported while comparing")
	class CompareTimeReporting {

		@Test
		@DisplayName("should hand on the entities a comparator without classification reports while comparing")
		void shouldHandOnEntitiesReportedByComparison() {
			// the entity-level attribute comparator keeps the default classification and detects owner 12 lacking
			// `tail` only while comparing - that channel must keep working
			final SortResult sorted = sort(
				new AttributeComparator(ATTRIBUTE_TAIL, Integer.class, 0, null, OrderDirection.ASC), null, 0, 10,
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
			// a comparator that rejects owner 12 up front and still reports it, next to owner 14 detected while
			// comparing
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
		@DisplayName("should refuse a comparator that reports more non-sortable entities than it was given")
		void shouldRefuseComparatorReportingMoreEntitiesThanItSorted() {
			// owners 13 and 14 are reported although only owner 11 was sorted
			final EntityComparator comparator = new EntityComparator() {
				@Nonnull
				@Override
				public Iterable<EntityContract> getNonSortedEntities() {
					return List.of(OWNERS.get(13), OWNERS.get(14));
				}

				@Override
				public int compare(EntityContract o1, EntityContract o2) {
					return Integer.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException());
				}
			};

			final EvitaInternalError error = assertThrows(
				EvitaInternalError.class, () -> sort(comparator, null, 0, 10, 11)
			);
			assertTrue(
				error.getMessage().contains("reported more non-sortable entities"),
				"the sorter names the broken contract: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should write the chained entities and pass on the ones no chain provider holds")
		void shouldSortEntitiesMissingFromEveryChainProvider() {
			// the entity-level chain holds 13 and then 11, owner 12 is in no provider and is reported while comparing
			final SortedRecordsProvider provider = new SortedRecordsSupplier(
				1L, new int[]{13, 11}, new int[]{1, 0}, new BaseBitmap(11, 13), SortedComparableForwardSeeker.EMPTY
			);
			final PredecessorAttributeComparator comparator = new PredecessorAttributeComparator(
				() -> new SortedRecordsProvider[]{provider}
			);

			final SortResult sorted = sort(comparator, null, 0, 10, 11, 12, 13);

			assertAll(
				() -> assertArrayEquals(new int[]{13, 11}, sorted.written(), "the chain order of the held entities"),
				() -> assertArrayEquals(new int[]{12}, sorted.passedOn(), "the entity no provider holds moves on")
			);
		}
	}

	@Nested
	@DisplayName("Reference attribute classification")
	class AttributeClassification {

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
			final TraverseReferenceAttributeComparator ascendingComparator = orderComparator(OrderDirection.ASC);
			final SortResult ascending = sort(ascendingComparator, BLOCK_1, 0, 10, 11, 12, 13, 14, 15);
			final SortResult descending =
				sort(orderComparator(OrderDirection.DESC), BLOCK_1, 0, 10, 11, 12, 13, 14, 15);

			assertAll(
				() -> assertArrayEquals(new int[]{13, 15, 11}, ascending.written(), "ascending, ties by owner"),
				() -> assertArrayEquals(new int[]{12, 14}, ascending.passedOn(), "ascending passes on"),
				() -> assertArrayEquals(new int[]{11, 15, 13}, descending.written(), "descending, ties by owner"),
				() -> assertArrayEquals(new int[]{12, 14}, descending.passedOn(), "descending passes on"),
				() -> assertNothingReported(ascendingComparator, "owners classified out are not reported as well")
			);
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
	}

	@Nested
	@DisplayName("Reference compound classification")
	class CompoundClassification {

		@Test
		@DisplayName("should pass on owners without the compound value of the block, a lone one included")
		void shouldClassifyByCompound() {
			final TraverseReferenceCompoundAttributeComparator comparator = compoundComparator();

			final SortResult lone = sort(comparator, BLOCK_1, 0, 10, 16);
			final SortResult block = sort(comparator, BLOCK_1, 0, 10, 11, 12, 13, 14, 15, 16);

			assertAll(
				() -> assertArrayEquals(new int[0], lone.written(), "the lone owner has no compound value"),
				() -> assertArrayEquals(new int[]{16}, lone.passedOn()),
				() -> assertArrayEquals(new int[]{15, 13, 11}, block.written(), "(10,2) (10,3) (50,1)"),
				() -> assertArrayEquals(new int[]{12, 14, 16}, block.passedOn()),
				() -> assertNothingReported(comparator, "owners classified out are not reported as well")
			);
		}

		@Test
		@DisplayName("should order owners with equal compound values by their primary key in the direction of the ordering")
		void shouldOrderCompoundTiesByPrimaryKeyInDirection() {
			// owners 15 and 19 tie on (10,2) in category 1, owner 13 follows with (10,3)
			final SortResult ascending = sort(compoundComparator(OrderDirection.ASC), BLOCK_1, 0, 10, 13, 15, 19);
			final SortResult descending = sort(compoundComparator(OrderDirection.DESC), BLOCK_1, 0, 10, 13, 15, 19);

			assertAll(
				() -> assertArrayEquals(new int[]{15, 19, 13}, ascending.written(), "ascending"),
				() -> assertArrayEquals(new int[]{13, 19, 15}, descending.written(), "descending")
			);
		}

		@Test
		@DisplayName("should classify every block afresh with one compound comparator instance")
		void shouldClassifyEachCompoundBlockAfresh() {
			// owner 12's row of category 1 carries no element of the compound, its row of category 2 carries `order`
			final TraverseReferenceCompoundAttributeComparator comparator = compoundComparator();
			final SortResult first = sort(comparator, BLOCK_1, 0, 10, 12);
			final SortResult second = sort(comparator, BLOCK_2, 0, 10, 12);

			assertAll(
				() -> assertArrayEquals(new int[]{12}, first.passedOn(), "block 1 passes the owner on"),
				() -> assertArrayEquals(new int[]{12}, second.written(), "block 2 claims it")
			);
		}

		@Test
		@DisplayName("should answer a repeated compound classification from the memo without reporting")
		void shouldAnswerRepeatedClassificationFromMemo() {
			final TraverseReferenceCompoundAttributeComparator comparator = compoundComparator();
			comparator.prepareFor(4);

			comparator.withReferencedEntityId(
				BLOCK_1,
				() -> assertAll(
					() -> assertFalse(comparator.canSort(OWNERS.get(12)), "12 lacks the compound"),
					() -> assertFalse(comparator.canSort(OWNERS.get(12)), "12 lacks it again, from the memo"),
					() -> assertTrue(comparator.canSort(OWNERS.get(13)), "13 carries the compound"),
					() -> assertTrue(comparator.canSort(OWNERS.get(13)), "13 carries it again, from the memo")
				)
			);
			assertNothingReported(comparator, "the classification reports nothing");
		}

		@Test
		@DisplayName("should refuse a compound comparison of an entity that was not classified as sortable")
		void shouldRefuseCompoundComparisonOfUnclassifiedEntity() {
			// owner 16's row of category 1 carries no element of the compound
			final TraverseReferenceCompoundAttributeComparator comparator = compoundComparator();
			comparator.prepareFor(2);

			comparator.withReferencedEntityId(
				BLOCK_1,
				() -> assertThrows(EvitaInternalError.class, () -> comparator.compare(OWNERS.get(13), OWNERS.get(16)))
			);
		}
	}

	@Nested
	@DisplayName("Duplicate rows of one target")
	class DuplicateRowsClassification {

		/**
		 * Creates the traversal comparator of the `order` attribute of the `variants` reference in the direction.
		 */
		@Nonnull
		private static TraverseReferenceAttributeComparator variantsOrderComparator(@Nonnull OrderDirection direction) {
			return new TraverseReferenceAttributeComparator(
				ATTRIBUTE_ORDER, Integer.class, 0, VARIANTS_REFERENCE_SCHEMA, null, direction
			);
		}

		/**
		 * Creates the traversal comparator of the `orderSecond` compound of the `variants` reference in the direction.
		 */
		@Nonnull
		private static TraverseReferenceCompoundAttributeComparator variantsCompoundComparator(
			@Nonnull OrderDirection direction
		) {
			final SortableAttributeCompoundSchemaContract compound =
				VARIANTS_REFERENCE_SCHEMA.getSortableAttributeCompound(COMPOUND).orElseThrow();
			return new TraverseReferenceCompoundAttributeComparator(
				compound, VARIANTS_REFERENCE_SCHEMA, null,
				attributeName -> VARIANTS_REFERENCE_SCHEMA.getAttribute(attributeName)
					.map(AttributeSchemaContract.class::cast)
					.orElseThrow(),
				direction
			);
		}

		/**
		 * Creates the traversal comparator of the `chain` attribute of the `variants` reference over the providers.
		 */
		@Nonnull
		private static TraverseReferencePredecessorAttributeComparator variantsChainComparator(
			@Nonnull SortedRecordsProvider[] providers
		) {
			return new TraverseReferencePredecessorAttributeComparator(
				ATTRIBUTE_CHAIN, Predecessor.class, VARIANTS_REFERENCE_SCHEMA, null, OrderDirection.ASC, () -> providers
			);
		}

		/**
		 * Creates the traversal comparator of the `chain` attribute of the `decimalVariants` reference over the
		 * providers.
		 */
		@Nonnull
		private static TraverseReferencePredecessorAttributeComparator decimalVariantsChainComparator(
			@Nonnull SortedRecordsProvider... providers
		) {
			return new TraverseReferencePredecessorAttributeComparator(
				ATTRIBUTE_CHAIN, Predecessor.class, DECIMAL_VARIANTS_REFERENCE_SCHEMA, null, OrderDirection.ASC,
				() -> providers
			);
		}

		/**
		 * Creates the chain provider of one representative key.
		 *
		 * @param transactionalId the id of the provider
		 * @param sortedOwners    the owners in the order of the chain
		 * @param ownerPositions  the position of each owner, in ascending owner order, within `sortedOwners`
		 * @param key             the representative key of the provider
		 * @return the provider
		 */
		@Nonnull
		private static ReferenceSortedRecordsProvider chainProvider(
			long transactionalId,
			@Nonnull int[] sortedOwners,
			@Nonnull int[] ownerPositions,
			@Nonnull RepresentativeReferenceKey key
		) {
			return new ReferenceSortedRecordsProvider(
				transactionalId, sortedOwners, ownerPositions, new BaseBitmap(sortedOwners), position -> null, key
			);
		}

		@Test
		@DisplayName("should represent each owner by its first valued row in representative order and order by value")
		void shouldRepresentOwnerByFirstValuedRow() {
			// block of brand 2: 70 by z (1), 72 by b (50) - its a row has no value, 73 by b (60) although its c row
			// carries 5, 75 by a (60), 71 by a (100); the representative values pick the row, never the position of
			// the owner - 73 and 75 tie on 60 and follow their primary keys, not their rows' a < b
			final SortResult ascending = sort(
				variantsOrderComparator(OrderDirection.ASC), variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74, 75
			);
			final SortResult descending = sort(
				variantsOrderComparator(OrderDirection.DESC), variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74, 75
			);

			assertAll(
				() -> assertArrayEquals(new int[]{70, 72, 73, 75, 71}, ascending.written(), "ascending"),
				() -> assertArrayEquals(new int[]{74}, ascending.passedOn(), "74 has a row of brand 1 only"),
				() -> assertArrayEquals(new int[]{71, 75, 73, 72, 70}, descending.written(), "descending"),
				() -> assertArrayEquals(new int[]{74}, descending.passedOn())
			);
		}

		@Test
		@DisplayName("should claim a lone owner by a later row when its first row carries no value")
		void shouldClaimLoneOwnerByLaterValuedRow() {
			final SortResult lone = sort(
				variantsOrderComparator(OrderDirection.ASC), variantsBlockKey(2), 0, 10, 72
			);

			assertAll(
				() -> assertArrayEquals(new int[]{72}, lone.written(), "row b carries the value"),
				() -> assertArrayEquals(new int[0], lone.passedOn())
			);
		}

		@Test
		@DisplayName("should represent each owner by its first row carrying any element of the compound")
		void shouldRepresentOwnerByFirstRowCarryingAnyCompoundElement() {
			// block of brand 2: 70 by z (1,-), 72 by b (50,-) - its a row carries no element, 73 by b (60,-), 75 by
			// a (60,-), 71 by a (100,-) and 77 by a (-,7), which carries `second` only and so follows every owner
			// with an `order`; ordered by `order` alone, 77 is represented by its b row (40) instead
			final SortResult compound = sort(
				variantsCompoundComparator(OrderDirection.ASC), variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74, 75, 77
			);
			final SortResult order = sort(
				variantsOrderComparator(OrderDirection.ASC), variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74, 75, 77
			);

			assertAll(
				() -> assertArrayEquals(new int[]{70, 72, 73, 75, 71, 77}, compound.written(), "compound"),
				() -> assertArrayEquals(new int[]{74}, compound.passedOn(), "74 has a row of brand 1 only"),
				() -> assertArrayEquals(new int[]{70, 77, 72, 73, 75, 71}, order.written(), "order"),
				() -> assertArrayEquals(new int[]{74}, order.passedOn())
			);
		}

		@Test
		@DisplayName("should place the owners of a chain by the provider of their first placeable row, in key order")
		void shouldPlaceChainOwnersByProviderOfFirstPlaceableRow() {
			// chains of brand 2: a holds 71, b holds 73 then 72, c holds 73, z holds 70; 72's a row has no chain, 73
			// is placed by b before c - the owners follow the keys, then their positions in the chain of one key
			final SortedRecordsProvider[] providers = {
				new ReferenceSortedRecordsProvider(
					1L, new int[]{74}, new int[]{0}, new BaseBitmap(74), position -> null, variantKey(1, "m")
				),
				new ReferenceSortedRecordsProvider(
					2L, new int[]{71}, new int[]{0}, new BaseBitmap(71), position -> null, variantKey(2, "a")
				),
				new ReferenceSortedRecordsProvider(
					3L, new int[]{73, 72}, new int[]{1, 0}, new BaseBitmap(72, 73), position -> null,
					variantKey(2, "b")
				),
				new ReferenceSortedRecordsProvider(
					4L, new int[]{73}, new int[]{0}, new BaseBitmap(73), position -> null, variantKey(2, "c")
				),
				new ReferenceSortedRecordsProvider(
					5L, new int[]{70}, new int[]{0}, new BaseBitmap(70), position -> null, variantKey(2, "z")
				)
			};
			final TraverseReferencePredecessorAttributeComparator comparator =
				new TraverseReferencePredecessorAttributeComparator(
					ATTRIBUTE_CHAIN, Predecessor.class, VARIANTS_REFERENCE_SCHEMA, null, OrderDirection.ASC,
					() -> providers
				);
			final SortResult sorted = sort(comparator, variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74);

			assertAll(
				() -> assertArrayEquals(new int[]{71, 73, 72, 70}, sorted.written()),
				() -> assertArrayEquals(new int[]{74}, sorted.passedOn())
			);
		}

		@Test
		@DisplayName("should pass a chained row its key's providers do not hold on to the next row of the owner")
		void shouldPassRowItsKeysProviderCannotPlaceToTheNextRow() {
			// the chain of b holds 72 only: 73's b row carries a chain, yet no provider of b places 73, so its c row
			// represents it - a | b | c | z
			final SortedRecordsProvider[] providers = {
				chainProvider(1L, new int[]{74}, new int[]{0}, variantKey(1, "m")),
				chainProvider(2L, new int[]{71}, new int[]{0}, variantKey(2, "a")),
				chainProvider(3L, new int[]{72}, new int[]{0}, variantKey(2, "b")),
				chainProvider(4L, new int[]{73}, new int[]{0}, variantKey(2, "c")),
				chainProvider(5L, new int[]{70}, new int[]{0}, variantKey(2, "z"))
			};
			final SortResult sorted = sort(
				variantsChainComparator(providers), variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74
			);

			assertAll(
				() -> assertArrayEquals(new int[]{71, 72, 73, 70}, sorted.written()),
				() -> assertArrayEquals(new int[]{74}, sorted.passedOn())
			);
		}

		@Test
		@DisplayName("should order the chains of two keys whose representative values compare equal but differ")
		void shouldOrderChainsOfEqualComparingKeys() {
			// the providers line up the key 1.00 (84) before the key 1.0 (82, then 83), so the index route appends
			// 84, 82, 83
			final SortedRecordsProvider[] providers = {
				new ReferenceSortedRecordsProvider(
					1L, new int[]{84}, new int[]{0}, new BaseBitmap(84), position -> null,
					decimalVariantKey(2, new BigDecimal("1.00"))
				),
				new ReferenceSortedRecordsProvider(
					2L, new int[]{82, 83}, new int[]{0, 1}, new BaseBitmap(82, 83), position -> null,
					decimalVariantKey(2, new BigDecimal("1.0"))
				)
			};
			final TraverseReferencePredecessorAttributeComparator comparator =
				new TraverseReferencePredecessorAttributeComparator(
					ATTRIBUTE_CHAIN, Predecessor.class, DECIMAL_VARIANTS_REFERENCE_SCHEMA, null, OrderDirection.ASC,
					() -> providers
				);
			final SortResult sorted = sort(comparator, decimalVariantsBlockKey(2), 0, 10, 82, 83, 84);
			final int[] comparisons = new int[3];
			comparator.prepareFor(3);
			comparator.withReferencedEntityId(
				decimalVariantsBlockKey(2),
				() -> {
					comparisons[0] = Integer.signum(comparator.compare(OWNERS.get(82), OWNERS.get(83)));
					comparisons[1] = Integer.signum(comparator.compare(OWNERS.get(82), OWNERS.get(84)));
					comparisons[2] = Integer.signum(comparator.compare(OWNERS.get(83), OWNERS.get(84)));
				}
			);

			// owners placed by two keys follow the order the providers line the keys up in, even when the keys compare
			// equal, and the owners of one key follow its chain - no pair compares as equal, so the comparator keeps
			// its contract
			assertAll(
				() -> assertArrayEquals(new int[]{84, 82, 83}, sorted.written()),
				() -> assertArrayEquals(new int[]{-1, 1, 1}, comparisons, "82 vs 83, 82 vs 84, 83 vs 84")
			);
		}

		@Test
		@DisplayName("should represent an owner by the row of the key lined up first among keys comparing equal")
		void shouldPickRowOfFirstLinedUpKeyAmongEqualComparingKeys() {
			// 85 sits in the chains of both 1.0 (after 83) and 1.00 (after 84): the providers of the key lined up
			// first claim it on the index route, whichever of its rows the owner stores first
			final ReferenceSortedRecordsProvider scale1 = chainProvider(
				1L, new int[]{82, 83, 85}, new int[]{0, 1, 2}, decimalVariantKey(2, new BigDecimal("1.0"))
			);
			final ReferenceSortedRecordsProvider scale2 = chainProvider(
				2L, new int[]{84, 85}, new int[]{0, 1}, decimalVariantKey(2, new BigDecimal("1.00"))
			);
			final SortResult scale1First = sort(
				decimalVariantsChainComparator(scale1, scale2), decimalVariantsBlockKey(2), 0, 10, 82, 83, 84, 85
			);
			final SortResult scale2First = sort(
				decimalVariantsChainComparator(scale2, scale1), decimalVariantsBlockKey(2), 0, 10, 82, 83, 84, 85
			);

			assertAll(
				() -> assertArrayEquals(new int[]{82, 83, 85, 84}, scale1First.written(), "1.0 lined up first"),
				() -> assertArrayEquals(new int[]{84, 85, 82, 83}, scale2First.written(), "1.00 lined up first")
			);
		}
	}

	@Nested
	@DisplayName("Reference chain classification")
	class ChainClassification {

		@Test
		@DisplayName("should pass on owners the chain of the block cannot place, a lone one included")
		void shouldClassifyByChainPosition() {
			final TraverseReferencePredecessorAttributeComparator blockComparator = chainComparator();
			final SortResult block = sort(blockComparator, BLOCK_1, 0, 10, 11, 12, 13, 15, 16);
			final SortResult lone = sort(chainComparator(), BLOCK_1, 0, 10, 16);
			final SortResult noChain = sort(chainComparator(), BLOCK_3, 0, 10, 17);

			assertAll(
				() -> assertArrayEquals(new int[]{15, 11, 13}, block.written(), "chain order of category 1"),
				() -> assertArrayEquals(new int[]{12, 16}, block.passedOn(), "value-less 12, 16 absent in the chain"),
				() -> assertNothingReported(blockComparator, "owners classified out are not reported as well"),
				() -> assertArrayEquals(new int[0], lone.written(), "16 carries a value but the chain lacks it"),
				() -> assertArrayEquals(new int[]{16}, lone.passedOn()),
				() -> assertArrayEquals(new int[0], noChain.written(), "category 3 has no chain provider"),
				() -> assertArrayEquals(new int[]{17}, noChain.passedOn())
			);
		}

		@Test
		@DisplayName("should admit an owner held by a later provider sharing the block key, across blocks")
		void shouldClassifyByEveryProviderOfBlock() {
			// category 1 is backed by two providers - the live chain 15, 11, 13 and the archived chain holding 18 - so
			// 18 belongs to the block although the first provider lacks it, and follows the live chain; one comparator
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
		@DisplayName("should hand on a value-less owner reported while comparing when no block is bound")
		void shouldReportValueLessOwnerWhileComparingWithoutBlock() {
			// owner 12's row of category 1 lacks `chain`, so the unbound comparison reports it and sorts it last
			final SortResult sorted = sort(chainComparator(), null, 0, 10, 11, 12, 13, 15);

			assertAll(
				() -> assertArrayEquals(new int[]{15, 11, 13}, sorted.written(), "chain order of category 1"),
				() -> assertArrayEquals(new int[]{12}, sorted.passedOn(), "the value-less owner is handed on")
			);
		}

		@Test
		@DisplayName(
			"should pass on every owner when the reference has no chain provider at all, resolving the family once"
		)
		void shouldPassOnEveryOwnerWhenFamilyHasNoChainProvider() {
			final AtomicInteger resolutions = new AtomicInteger();
			final TraverseReferencePredecessorAttributeComparator comparator = chainComparator(
				() -> {
					resolutions.incrementAndGet();
					return new SortedRecordsProvider[0];
				}
			);

			final SortResult first = sort(comparator, BLOCK_1, 0, 10, 11, 13, 15);
			final SortResult second = sort(comparator, BLOCK_2, 0, 10, 14);

			assertAll(
				() -> assertArrayEquals(new int[0], first.written(), "no chain can place an owner of category 1"),
				() -> assertArrayEquals(new int[]{11, 13, 15}, first.passedOn()),
				() -> assertArrayEquals(new int[0], second.written(), "no chain can place an owner of category 2"),
				() -> assertArrayEquals(new int[]{14}, second.passedOn()),
				() -> assertEquals(1, resolutions.get(), "the family is resolved once for the whole query")
			);
		}

		@Test
		@DisplayName("should resolve each chain position once across classification, comparison and repeated blocks")
		void shouldResolveEveryChainPositionOnce() {
			final ReferenceSortedRecordsProvider[] providers =
				chainProviders(CountingReferenceSortedRecordsProvider::new);
			final IntUnaryOperator lookups =
				index -> ((CountingReferenceSortedRecordsProvider) providers[index]).positionLookups;
			final AtomicInteger resolutions = new AtomicInteger();
			final TraverseReferencePredecessorAttributeComparator comparator = chainComparator(
				() -> {
					resolutions.incrementAndGet();
					return providers;
				}
			);

			// each owner's classification probes the live chain, 18 misses there and is found in the archived one;
			// the comparisons then find every position in the cache
			final SortResult first = sort(comparator, BLOCK_1, 0, 10, 11, 13, 15, 18);
			final int liveAfterFirst = lookups.applyAsInt(0);
			final int archivedAfterFirst = lookups.applyAsInt(1);
			// the positions stay valid for the whole query, so sorting the block again resolves nothing
			final SortResult second = sort(comparator, BLOCK_1, 0, 10, 11, 13, 15, 18);

			assertAll(
				() -> assertArrayEquals(new int[]{15, 11, 13, 18}, first.written(), "live chain, then archived chain"),
				() -> assertArrayEquals(new int[]{15, 11, 13, 18}, second.written(), "the same order again"),
				() -> assertEquals(4, liveAfterFirst, "one lookup per owner in the live chain"),
				() -> assertEquals(1, archivedAfterFirst, "one lookup of 18 in the archived chain"),
				() -> assertEquals(4, lookups.applyAsInt(0), "no new lookup in the live chain"),
				() -> assertEquals(1, lookups.applyAsInt(1), "no new lookup in the archived chain"),
				() -> assertEquals(0, lookups.applyAsInt(2), "the chain of category 2 is never probed"),
				() -> assertEquals(1, resolutions.get(), "the family is resolved once for the whole query")
			);
		}
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
	 * Creates one chain provider of a block from its sorted record ids, the positions of its records in ascending
	 * record id order, the records themselves and the key of the block.
	 */
	@FunctionalInterface
	private interface ChainProviderFactory {

		/**
		 * Creates the provider.
		 */
		@Nonnull
		ReferenceSortedRecordsProvider create(
			long transactionalId,
			@Nonnull int[] sortedRecordIds,
			@Nonnull int[] recordPositions,
			@Nonnull Bitmap allRecords,
			@Nonnull RepresentativeReferenceKey referenceKey
		);
	}

	/**
	 * A chain provider counting how many times the position of a record was looked up in it.
	 */
	private static final class CountingReferenceSortedRecordsProvider extends ReferenceSortedRecordsProvider {
		/**
		 * The number of position lookups so far.
		 */
		private int positionLookups;

		CountingReferenceSortedRecordsProvider(
			long transactionalId,
			@Nonnull int[] sortedRecordIds,
			@Nonnull int[] recordPositions,
			@Nonnull Bitmap allRecords,
			@Nonnull RepresentativeReferenceKey referenceKey
		) {
			super(transactionalId, sortedRecordIds, recordPositions, allRecords, position -> null, referenceKey);
		}

		@Override
		public int positionOf(int recordId) {
			this.positionLookups++;
			return super.positionOf(recordId);
		}
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
