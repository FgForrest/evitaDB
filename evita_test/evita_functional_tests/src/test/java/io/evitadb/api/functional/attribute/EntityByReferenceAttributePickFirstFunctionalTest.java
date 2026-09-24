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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Pins the semantics of `pickFirstByEntityProperty` ordering by a reference attribute on a small hand-built dataset
 * where every expected order can be derived by reading the fixture:
 *
 * - an owner sorts on the value of its **first row, in the query's target order, that carries the attribute**,
 * - every row of the owner takes part, whatever the filter says about the same reference,
 * - rows sharing one target are ordered by their representative attribute values,
 * - owners with equal values are ordered by primary key in the direction of the ordering, as in a plain attribute
 *   sort, and owners with no value follow in ascending primary key order.
 *
 * Each scenario runs on two routes the engine can take to the same answer: the index route, where the sorted-records
 * providers of the reduced indexes holding a row of the selection are merged, and the prefetch route, where the
 * fetched references are compared by `PickFirstReferenceAttributeComparator`. Both routes must agree with each other and with the rule above, in both
 * reference indexing levels.
 *
 * The fixture (target order is the referenced primary key ascending, the default for a non-hierarchical target):
 *
 * | owner | rows (target, order, kind)  | first row with a value |
 * |-------|-----------------------------|------------------------|
 * | 1     | (2, -, x), (4, 30, x)       | 30 - earliest target lacks the value |
 * | 2     | (3, 20, x)                  | 20                     |
 * | 3     | (2, 50, x), (3, 10, x)      | 50 - the first wins over a lower later value |
 * | 4     | (5, -, x)                   | none                   |
 * | 5     | -                           | none                   |
 * | 6     | (6, 20, x)                  | 20 - ties owner 2 across reduced indexes |
 * | 7     | (1, 1, y), (4, 40, x)       | 1                      |
 * | 8     | (1, 60, y)                  | 60                     |
 * | 9     | (3, 20, x)                  | 20 - ties owner 2 inside one reduced index |
 * | 10    | (3, 70, x), (7, 2, y)       | 70                     |
 *
 * Targets 1 and 7 carry only rows of kind `y`, so a `referenceHaving` on kind `x` leaves their reduced indexes out
 * of the candidate set. Owners 11-14 use the duplicate-allowing reference only.
 *
 * Owners 10 and 3 carry the entity attribute `pin` with values 1 and 2, no other owner does. Reference
 * `unusedTargets` has no row at all. Reference `groupOnly` is indexed for its group family only, with rows
 * (target, group) (5, 1) on owner 2, (1, 1) on owner 3 and (3, 2) on owner 6.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering by a reference attribute picks the first row in target order that carries the value")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
@Tag(ORDER)
public class EntityByReferenceAttributePickFirstFunctionalTest {
	private static final String PICK_FIRST_REFERENCES = "pickFirstReferences";
	private static final String ENTITY_TARGET = "target";
	private static final String ENTITY_OWNER = "owner";
	private static final String REFERENCE_TARGETS_FILTERING = "targetsFiltering";
	private static final String REFERENCE_TARGETS_PARTITIONING = "targetsPartitioning";
	private static final String REFERENCE_VARIANTS_FILTERING = "variantsFiltering";
	private static final String REFERENCE_VARIANTS_PARTITIONING = "variantsPartitioning";
	private static final String REFERENCE_UNUSED_TARGETS = "unusedTargets";
	private static final String REFERENCE_GROUP_ONLY = "groupOnly";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_KIND = "kind";
	private static final String ATTRIBUTE_VARIANT = "variant";
	private static final String ATTRIBUTE_SCORE = "score";
	private static final String ATTRIBUTE_PIN = "pin";
	private static final int TARGET_COUNT = 7;
	private static final Integer[] PLAIN_OWNERS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
	private static final Integer[] DUPLICATE_OWNERS = {11, 12, 13, 14};

	/**
	 * One row of an owner's plain reference.
	 *
	 * @param target the referenced primary key
	 * @param order  the sortable value, `null` when the row carries none
	 * @param kind   the filterable discriminator used to narrow the candidate reduced indexes
	 */
	private record Row(int target, @Nullable Integer order, @Nonnull String kind) {
	}

	/**
	 * The route the engine takes to order the owners.
	 */
	private enum SortRoute {
		/**
		 * Sorted-records providers of the reduced indexes, merged by the index sorter.
		 */
		INDEX,
		/**
		 * Entities are prefetched and ordered by the entity comparator.
		 */
		PREFETCH
	}

	/**
	 * Both references of one shape, each at a different indexing level, crossed with both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> plainReferenceRoutes() {
		return Stream.of(REFERENCE_TARGETS_FILTERING, REFERENCE_TARGETS_PARTITIONING)
			.flatMap(reference -> Arrays.stream(SortRoute.values()).map(route -> Arguments.of(reference, route)));
	}

	/**
	 * Both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> sortRoutes() {
		return Arrays.stream(SortRoute.values()).map(Arguments::of);
	}

	/**
	 * Both duplicate-allowing references, each at a different indexing level.
	 */
	@Nonnull
	static Stream<Arguments> duplicateReferences() {
		return Stream.of(Arguments.of(REFERENCE_VARIANTS_FILTERING), Arguments.of(REFERENCE_VARIANTS_PARTITIONING));
	}

	/**
	 * Upserts one owner with the passed rows written identically into both plain references.
	 */
	private static void upsertOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer score,
		@Nonnull Row... rows
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		if (score != null) {
			builder.setAttribute(ATTRIBUTE_SCORE, score);
		}
		for (String referenceName : new String[]{REFERENCE_TARGETS_FILTERING, REFERENCE_TARGETS_PARTITIONING}) {
			for (Row row : rows) {
				builder.setReference(
					referenceName, row.target(),
					whichIs -> {
						whichIs.setAttribute(ATTRIBUTE_KIND, row.kind());
						if (row.order() != null) {
							whichIs.setAttribute(ATTRIBUTE_ORDER, row.order());
						}
					}
				);
			}
		}
		builder.upsertVia(session);
	}

	/**
	 * Upserts one owner holding duplicate rows of the same target, told apart by the representative `variant`.
	 */
	private static void upsertDuplicateOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Object[]... variants
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		for (String referenceName : new String[]{REFERENCE_VARIANTS_FILTERING, REFERENCE_VARIANTS_PARTITIONING}) {
			for (Object[] variant : variants) {
				builder.setOrUpdateReference(
					referenceName, (Integer) variant[0], Functions.alwaysFalse(),
					whichIs -> whichIs
						.setAttribute(ATTRIBUTE_VARIANT, (String) variant[1])
						.setAttribute(ATTRIBUTE_ORDER, (Integer) variant[2])
				);
			}
		}
		builder.upsertVia(session);
	}

	/**
	 * Runs the ordering query on the requested route and returns the owner primary keys in result order.
	 */
	@Nonnull
	private static int[] queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull String referenceName,
		@Nonnull SortRoute route,
		@Nonnull OrderDirection direction,
		@Nonnull Integer[] owners,
		@Nullable FilterConstraint narrowing
	) {
		return queryOrder(
			session, route, owners, narrowing,
			referenceProperty(referenceName, attributeNatural(ATTRIBUTE_ORDER, direction))
		);
	}

	/**
	 * Runs a query with the passed ordering on the requested route and returns the owner primary keys in result
	 * order.
	 */
	@Nonnull
	private static int[] queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nullable FilterConstraint narrowing,
		@Nonnull OrderConstraint ordering
	) {
		return queryPage(session, route, owners, narrowing, 1, 100, ordering);
	}

	/**
	 * Runs a query with the passed orderings on the requested route and returns the owner primary keys of one page
	 * in result order.
	 */
	@Nonnull
	private static int[] queryPage(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nullable FilterConstraint narrowing,
		int pageNumber,
		int pageSize,
		@Nonnull OrderConstraint... orderings
	) {
		// each route is forced, so that a small dataset cannot answer an index-route assertion from prefetched bodies
		final RequireConstraint[] require = route == SortRoute.PREFETCH ?
			new RequireConstraint[]{page(pageNumber, pageSize), debug(DebugMode.PREFER_PREFETCHING)} :
			new RequireConstraint[]{page(pageNumber, pageSize), debug(DebugMode.PREFER_INDEX_SCAN)};
		return session.query(
				query(
					collection(ENTITY_OWNER),
					filterBy(and(entityPrimaryKeyInSet(owners), narrowing)),
					orderBy(orderings),
					require(require)
				),
				EntityReference.class
			)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
	}

	/**
	 * Reads every page of the passed size on the requested route and returns the concatenated owner primary keys.
	 */
	@Nonnull
	private static int[] queryAllPages(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		int pageSize,
		@Nonnull OrderConstraint... orderings
	) {
		final List<Integer> result = new ArrayList<>(owners.length);
		for (int pageNumber = 1; (pageNumber - 1) * pageSize < owners.length; pageNumber++) {
			for (int owner : queryPage(session, route, owners, null, pageNumber, pageSize, orderings)) {
				result.add(owner);
			}
		}
		return result.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Adds a row of the group-only reference to an existing owner.
	 */
	private static void addGroupOnlyRow(@Nonnull EvitaSessionContract session, int owner, int target, int group) {
		session.getEntity(ENTITY_OWNER, owner, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REFERENCE_GROUP_ONLY, target, whichIs -> whichIs.setGroup(group))
			.upsertVia(session);
	}

	/**
	 * Sets the entity attribute `pin` of an existing owner.
	 */
	private static void pinOwner(@Nonnull EvitaSessionContract session, int owner, int pin) {
		session.getEntity(ENTITY_OWNER, owner, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_PIN, pin)
			.upsertVia(session);
	}

	@DataSet(value = PICK_FIRST_REFERENCES, destroyAfterClass = true)
	void setUp(EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TARGET)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);
		session.defineEntitySchema(ENTITY_OWNER)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_SCORE, Integer.class, thatIs -> thatIs.sortable().nullable())
			.withAttribute(ATTRIBUTE_PIN, Integer.class, thatIs -> thatIs.sortable().nullable())
			.withReferenceToEntity(
				REFERENCE_TARGETS_FILTERING, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFiltering()
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterable())
			)
			.withReferenceToEntity(
				REFERENCE_TARGETS_PARTITIONING, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterable())
			)
			.withReferenceToEntity(
				REFERENCE_VARIANTS_FILTERING, ENTITY_TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs.indexedForFiltering()
					.withAttribute(ATTRIBUTE_VARIANT, String.class, thatIs -> thatIs.filterable().representative())
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.sortable())
			)
			.withReferenceToEntity(
				REFERENCE_VARIANTS_PARTITIONING, ENTITY_TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.withAttribute(ATTRIBUTE_VARIANT, String.class, thatIs -> thatIs.filterable().representative())
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.sortable())
			)
			// no owner ever sets this reference, so it has no reduced index at all
			.withReferenceToEntity(
				REFERENCE_UNUSED_TARGETS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFiltering()
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterable().sortable().nullable())
			)
			// indexed for its group family only, so it has no reduced index of the referenced entity family
			.withReferenceToEntity(
				REFERENCE_GROUP_ONLY, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.withGroupTypeRelatedToEntity(ENTITY_TARGET)
					.indexedForFiltering()
					.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
			)
			.updateVia(session);

		for (int i = 1; i <= TARGET_COUNT; i++) {
			session.createNewEntity(ENTITY_TARGET, i).upsertVia(session);
		}

		upsertOwner(session, 1, 30, new Row(2, null, "x"), new Row(4, 30, "x"));
		upsertOwner(session, 2, 20, new Row(3, 20, "x"));
		upsertOwner(session, 3, 50, new Row(2, 50, "x"), new Row(3, 10, "x"));
		upsertOwner(session, 4, null, new Row(5, null, "x"));
		upsertOwner(session, 5, null);
		upsertOwner(session, 6, 20, new Row(6, 20, "x"));
		upsertOwner(session, 7, 1, new Row(1, 1, "y"), new Row(4, 40, "x"));
		upsertOwner(session, 8, 60, new Row(1, 60, "y"));
		upsertOwner(session, 9, 20, new Row(3, 20, "x"));
		upsertOwner(session, 10, 70, new Row(3, 70, "x"), new Row(7, 2, "y"));
		addGroupOnlyRow(session, 2, 5, 1);
		addGroupOnlyRow(session, 3, 1, 1);
		addGroupOnlyRow(session, 6, 3, 2);
		pinOwner(session, 10, 1);
		pinOwner(session, 3, 2);

		// owner 11 is upserted first, so the reduced index of the (2, "b") row exists before the one of (2, "a")
		upsertDuplicateOwner(session, 11, new Object[]{2, "b", 70});
		upsertDuplicateOwner(session, 12, new Object[]{2, "a", 5}, new Object[]{2, "b", 80});
		upsertDuplicateOwner(session, 13, new Object[]{3, "a", 50});
		// owner 14 adds the same two rows as owner 12, but in the opposite order within its own upsert
		upsertDuplicateOwner(session, 14, new Object[]{2, "b", 6}, new Object[]{2, "a", 90});
	}

	/**
	 * Asserts the owner order and reports both full arrays on a mismatch, so one run shows the complete answer of
	 * every route rather than the first differing position only.
	 */
	private static void assertOrder(@Nonnull int[] expected, @Nonnull int[] actual, @Nonnull String label) {
		assertArrayEquals(
			expected, actual,
			() -> label + ": expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual)
		);
	}

	@DisplayName("Should sort each owner on its first row in target order that carries the value")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldSortOnFirstRowCarryingValueWhenAscending(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// values 1 (7), 20 (2, 6, 9), 30 (1), 50 (3), 60 (8), 70 (10), then owners without a value by primary key
		assertOrder(
			new int[]{7, 2, 6, 9, 1, 3, 8, 10, 4, 5},
			queryOrder(session, referenceName, route, OrderDirection.ASC, PLAIN_OWNERS, null),
			"ascending"
		);
	}

	@DisplayName("Should break value ties by descending owner primary key when the order is descending")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldBreakTiesByDescendingPrimaryKeyWhenDescending(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// owners 2 and 9 tie inside the reduced index of target 3, owner 6 ties with both from the one of target 6;
		// a descending plain attribute sort orders ties by descending primary key, so the reference sort must too
		assertOrder(
			new int[]{10, 8, 3, 1, 9, 6, 2, 7, 4, 5},
			queryOrder(session, referenceName, route, OrderDirection.DESC, PLAIN_OWNERS, null),
			"descending"
		);
	}

	@DisplayName("Should break value ties the same way as a plain entity attribute sort")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldBreakTiesLikePlainAttributeSort(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// every owner's entity-level `score` equals the value its reference sort picks, so a plain attribute sort
		// over it is the reference for how ties are broken outside reference ordering
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] plainAttribute = queryOrder(
				session, route, PLAIN_OWNERS, null, attributeNatural(ATTRIBUTE_SCORE, direction)
			);
			assertOrder(
				plainAttribute,
				queryOrder(session, referenceName, route, direction, PLAIN_OWNERS, null),
				direction + " reference sort vs plain attribute sort " + Arrays.toString(plainAttribute)
			);
		}
	}

	@DisplayName("Should order the same owners identically whether or not the filter narrows the same reference")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldNotDependOnSameReferenceNarrowing(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// `kind == x` selects owners 1, 2, 3, 4, 6, 7, 9 and 10 and leaves the reduced indexes of targets 1 and 7
		// (rows of kind `y` only) out of the candidate set; the plain query selects the very same owners
		final Integer[] owners = {1, 2, 3, 4, 6, 7, 9, 10};
		final int[] expected = {7, 2, 6, 9, 1, 3, 10, 4};
		final int[] plain = queryOrder(session, referenceName, route, OrderDirection.ASC, owners, null);
		final int[] narrowed = queryOrder(
			session, referenceName, route, OrderDirection.ASC, owners,
			referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, "x"))
		);
		assertAll(
			() -> assertOrder(expected, plain, "plain"),
			() -> assertOrder(expected, narrowed, "narrowed")
		);
	}

	@DisplayName("Should order rows sharing one target by their representative values on both routes")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("duplicateReferences")
	void shouldOrderDuplicateRowsByRepresentativeValues(String referenceName, EvitaSessionContract session) {
		// owners 12 and 14 reference target 2 twice, adding the two rows in opposite orders, and the `b` row's reduced
		// index was created first (by owner 11); neither the insertion order nor the index creation order may decide -
		// the `a` row precedes the `b` row by its representative value: 12 (5), 13 (50), 11 (70), 14 (90)
		final int[] expected = {12, 13, 11, 14};
		final int[] index = queryOrder(session, referenceName, SortRoute.INDEX, OrderDirection.ASC, DUPLICATE_OWNERS, null);
		final int[] prefetch = queryOrder(session, referenceName, SortRoute.PREFETCH, OrderDirection.ASC, DUPLICATE_OWNERS, null);
		assertAll(
			() -> assertOrder(expected, index, "index route"),
			() -> assertOrder(expected, prefetch, "prefetch route")
		);
	}

	@DisplayName("Should return consistent pages whatever the page size")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldReturnConsistentPagesOnBothRoutes(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// size 3 yields a page straddling the claimed and unclaimed owners (8, 10, 4) and a page of the unclaimed
		// owner 5 alone; the other sizes move the page boundaries across the whole claimed block
		final List<Executable> assertions = new ArrayList<>(10);
		for (int pageSize : new int[]{1, 2, 3, 4, 7}) {
			for (OrderDirection direction : OrderDirection.values()) {
				final int[] expected = direction == OrderDirection.ASC ?
					new int[]{7, 2, 6, 9, 1, 3, 8, 10, 4, 5} : new int[]{10, 8, 3, 1, 9, 6, 2, 7, 4, 5};
				final int[] actual = queryAllPages(
					session, route, PLAIN_OWNERS, pageSize,
					referenceProperty(referenceName, attributeNatural(ATTRIBUTE_ORDER, direction))
				);
				assertions.add(() -> assertOrder(expected, actual, direction + " by pages of " + pageSize));
			}
		}
		assertAll(assertions);
	}

	@DisplayName("Should hand the owners without a value to the next sorter")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldHandOwnersWithoutValueToNextSorter(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// owners 4 and 5 carry no value, so the descending primary key order of the next sorter places them
		assertOrder(
			new int[]{7, 2, 6, 9, 1, 3, 8, 10, 5, 4},
			queryPage(
				session, route, PLAIN_OWNERS, null, 1, 100,
				referenceProperty(referenceName, attributeNatural(ATTRIBUTE_ORDER)),
				entityPrimaryKeyNatural(OrderDirection.DESC)
			),
			"followed by descending primary key"
		);
	}

	@DisplayName("Should sort the remainder a preceding sorter left over")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("plainReferenceRoutes")
	void shouldSortRemainderLeftByPrecedingSorter(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// owners 10 and 3 are placed by the preceding sorter, the rest is sorted on a selection without them
		final int[] expected = {10, 3, 7, 2, 6, 9, 1, 8, 4, 5};
		final OrderConstraint pickFirst = referenceProperty(referenceName, attributeNatural(ATTRIBUTE_ORDER));
		final OrderConstraint[] byPin = {attributeNatural(ATTRIBUTE_PIN), pickFirst};
		assertAll(
			() -> assertOrder(
				expected, queryPage(session, route, PLAIN_OWNERS, null, 1, 100, byPin), "after pin"
			),
			() -> assertOrder(
				expected, queryAllPages(session, route, PLAIN_OWNERS, 3, byPin), "after pin, pages of 3"
			),
			() -> assertOrder(
				expected,
				queryPage(session, route, PLAIN_OWNERS, null, 1, 100, entityPrimaryKeyExact(10, 3), pickFirst),
				"after exact primary keys"
			)
		);
	}

	@DisplayName("Should order by the referenced primary key over rows sharing one target on both routes")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("duplicateReferences")
	void shouldOrderByReferencedPrimaryKeyOnDuplicateReference(String referenceName, EvitaSessionContract session) {
		// owners 11, 12 and 14 reference target 2 (12 and 14 twice), owner 13 references target 3; the owners of
		// target 2 tie and follow their own primary key in the direction of the ordering
		final List<Executable> assertions = new ArrayList<>(4);
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] expected = direction == OrderDirection.ASC ?
				new int[]{11, 12, 14, 13} : new int[]{13, 14, 12, 11};
			for (SortRoute route : SortRoute.values()) {
				final int[] actual = queryOrder(
					session, route, DUPLICATE_OWNERS, null,
					referenceProperty(referenceName, entityPrimaryKeyNatural(direction))
				);
				assertions.add(() -> assertOrder(expected, actual, direction + " via " + route));
			}
		}
		assertAll(assertions);
	}

	@DisplayName("Should not fail a single-index ordering by a reference no owner has")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldNotFailSingleIndexOrderingWhenReferenceHasNoRow(SortRoute route, EvitaSessionContract session) {
		final Integer[] owners = IntStream.rangeClosed(1, 14).boxed().toArray(Integer[]::new);
		assertOrder(
			IntStream.rangeClosed(1, 14).toArray(),
			queryOrder(
				session, route, owners, null,
				referenceProperty(REFERENCE_UNUSED_TARGETS, attributeSetExact(ATTRIBUTE_KIND, "x"))
			),
			"unused reference"
		);
	}

	@DisplayName("Should order by a reference indexed for its group family only identically on both routes")
	@UseDataSet(PICK_FIRST_REFERENCES)
	@Test
	void shouldOrderGroupOnlyReferenceIdenticallyOnBothRoutes(EvitaSessionContract session) {
		final OrderConstraint ordering = referenceProperty(
			REFERENCE_GROUP_ONLY, entityPrimaryKeyNatural(OrderDirection.ASC)
		);
		// the reference has no reduced index of the referenced entity family, so neither route sorts by it and both
		// leave the owners in primary key order
		final int[] expected = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
		assertAll(
			() -> assertOrder(
				expected, queryOrder(session, SortRoute.INDEX, PLAIN_OWNERS, null, ordering), "index route"
			),
			() -> assertOrder(
				expected, queryOrder(session, SortRoute.PREFETCH, PLAIN_OWNERS, null, ordering), "prefetch route"
			)
		);
	}

}
