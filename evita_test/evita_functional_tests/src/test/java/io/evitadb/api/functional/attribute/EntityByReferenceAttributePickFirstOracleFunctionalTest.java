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

import io.evitadb.core.Evita;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks `pickFirstByEntityProperty` ordering by a reference attribute against an oracle computed from the rows the
 * test itself wrote, on a randomized dataset large enough to reach every way the engine finds the reduced indexes of
 * a selection. The rule being checked is the one pinned on a hand-built fixture by
 * {@link EntityByReferenceAttributePickFirstFunctionalTest}: an owner sorts on the value of its first row, in target
 * order, that carries the value; every row takes part whatever the filter says; ties follow the owner primary key in
 * the direction of the ordering; owners without a value follow in ascending primary key order.
 *
 * What the dataset is shaped to reach:
 *
 * - targets 1 and 2 are referenced by 150 and 80 owners, more than a reduced index membership covers, so their
 *   indexes are found through the membership's residual set;
 * - a selection of a handful of owners is resolved by gathering the membership of those owners, while the large
 *   selections hold more covered owners than the reference has reduced indexes and walk the whole family;
 * - every seventh owner is archived, and one selection spans both scopes, each with its own membership;
 * - targets are ordered three ways: primary key ascending (the default), primary key descending, and by an attribute
 *   of the target entity through a nested sorter, which appends the targets lacking the attribute (every ninth one)
 *   after the others in ascending primary key order;
 * - the sorted value is a plain attribute, a localized attribute, the referenced primary key, and a compound.
 *
 * Every combination runs on the index route and on the prefetch route, forced by `PREFER_INDEX_SCAN` and
 * `PREFER_PREFETCHING`, for both reference indexing levels.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering by a reference attribute agrees with the pick-first rule on a randomized dataset")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
@Tag(ORDER)
public class EntityByReferenceAttributePickFirstOracleFunctionalTest {
	private static final String PICK_FIRST_ORACLE = "pickFirstOracle";
	private static final String ENTITY_TARGET = "oracleTarget";
	private static final String ENTITY_OWNER = "oracleOwner";
	private static final String REFERENCE_ITEMS_PARTITIONING = "itemsPartitioning";
	private static final String REFERENCE_ITEMS_FILTERING = "itemsFiltering";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_KIND = "kind";
	private static final String COMPOUND_ORDER_LABEL = "orderLabel";
	private static final String COMPOUND_ORDER_KIND = "orderKind";
	private static final int TARGET_COUNT = 40;
	/**
	 * Targets whose primary key is a multiple of this value have no `rank`.
	 */
	private static final int UNRANKED_TARGET_MODULO = 9;
	private static final int OWNER_COUNT = 300;
	private static final long SEED = 42;
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * A handful of live owners, few enough to be resolved by gathering their membership.
	 */
	private static final int[] SMALL_SELECTION = {3, 11, 150, 151, 152, 290};

	/**
	 * One row of an owner's reference, written identically into both references.
	 *
	 * @param target the referenced primary key
	 * @param order  the sortable integer, `null` when the row carries none
	 * @param label  the localized sortable string, `null` when the row carries none
	 * @param kind   the filterable discriminator used to narrow the selection
	 */
	private record Row(int target, @Nullable Integer order, @Nullable String label, @Nonnull String kind) {
	}

	/**
	 * What the test wrote, the oracle's only input.
	 *
	 * @param rowsByOwner rows of every owner, including owners with none
	 * @param targetRank  value of the target attribute `rank`, indexed by the target primary key, `null` for a target
	 *                    without one
	 * @param archived    whether the owner, indexed by its primary key, was archived
	 */
	private record OracleFixture(
		@Nonnull Map<Integer, List<Row>> rowsByOwner,
		@Nonnull Integer[] targetRank,
		@Nonnull boolean[] archived
	) {
	}

	/**
	 * The route the engine takes to order the owners.
	 */
	private enum SortRoute {
		INDEX, PREFETCH
	}

	/**
	 * Which owners are selected and how.
	 */
	private enum Selection {
		/**
		 * Every live owner.
		 */
		ALL_LIVE,
		/**
		 * {@link #SMALL_SELECTION}.
		 */
		SMALL,
		/**
		 * Every live owner with a row of kind `x`, selected by a `referenceHaving` on the ordered reference itself.
		 */
		NARROWED,
		/**
		 * Every owner, live and archived.
		 */
		BOTH_SCOPES
	}

	/**
	 * The order of the targets, the `pickFirstByEntityProperty` specification.
	 */
	private enum TargetOrder {
		/**
		 * The default - no specification, primary key ascending.
		 */
		DEFAULT,
		/**
		 * `entityPrimaryKeyNatural(DESC)`.
		 */
		PRIMARY_KEY_DESCENDING,
		/**
		 * `attributeNatural(rank)` of the target entity, resolved by a nested sorter.
		 */
		TARGET_ATTRIBUTE
	}

	/**
	 * The value the owners are sorted on.
	 */
	private enum SortedValue {
		/**
		 * The integer reference attribute.
		 */
		ORDER,
		/**
		 * The localized string reference attribute.
		 */
		LABEL,
		/**
		 * The referenced primary key - every row carries it.
		 */
		REFERENCED_PRIMARY_KEY
	}

	@Nonnull
	static Stream<Arguments> oracleCombinations() {
		return Stream.of(REFERENCE_ITEMS_PARTITIONING, REFERENCE_ITEMS_FILTERING)
			.flatMap(
				reference -> Arrays.stream(SortRoute.values())
					.flatMap(
						route -> Arrays.stream(TargetOrder.values())
							.flatMap(
								targetOrder -> Arrays.stream(SortedValue.values())
									.map(value -> Arguments.of(reference, route, targetOrder, value))
							)
					)
			);
	}

	/**
	 * Both references crossed with every target order and every sorted value - the routes are compared within one
	 * invocation.
	 */
	@Nonnull
	static Stream<Arguments> scopeCombinations() {
		return Stream.of(REFERENCE_ITEMS_PARTITIONING, REFERENCE_ITEMS_FILTERING)
			.flatMap(
				reference -> Arrays.stream(TargetOrder.values())
					.flatMap(
						targetOrder -> Arrays.stream(SortedValue.values())
							.map(value -> Arguments.of(reference, targetOrder, value))
					)
			);
	}

	/**
	 * Both references crossed with both routes and every target order.
	 */
	@Nonnull
	static Stream<Arguments> chainCombinations() {
		return Stream.of(REFERENCE_ITEMS_PARTITIONING, REFERENCE_ITEMS_FILTERING)
			.flatMap(
				reference -> Arrays.stream(SortRoute.values())
					.flatMap(
						route -> Arrays.stream(TargetOrder.values())
							.map(targetOrder -> Arguments.of(reference, route, targetOrder))
					)
			);
	}

	/**
	 * Compounds each reference can be ordered by on the index route. A compound with a localized element is declared
	 * on the partitioning reference only: a reduced index of a reference indexed for filtering alone receives no
	 * per-locale compound entries (`EntityIndexLocalMutationExecutor#insertInitialSuiteOfSortableAttributeCompounds`
	 * skips it), so its index route cannot order by such a compound at all - an indexing gap outside the ordering
	 * this test checks.
	 */
	@Nonnull
	static Stream<Arguments> compounds() {
		return Stream.of(
			Arguments.of(REFERENCE_ITEMS_PARTITIONING, COMPOUND_ORDER_LABEL),
			Arguments.of(REFERENCE_ITEMS_PARTITIONING, COMPOUND_ORDER_KIND),
			Arguments.of(REFERENCE_ITEMS_FILTERING, COMPOUND_ORDER_KIND)
		);
	}

	@DataSet(value = PICK_FIRST_ORACLE, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
					)
					.updateVia(session);
				final AttributeElement[] localizedCompoundElements = {
					new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
				};
				final AttributeElement[] compoundElements = {
					new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
					new AttributeElement(ATTRIBUTE_KIND, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				};
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized())
					.withReferenceToEntity(
						REFERENCE_ITEMS_PARTITIONING, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
							.withAttribute(
								ATTRIBUTE_ORDER, Integer.class,
								thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES).nullable()
							)
							.withAttribute(
								ATTRIBUTE_LABEL, String.class,
								thatIs -> thatIs.localized().sortableInScope(BOTH_SCOPES).nullable()
							)
							.withAttribute(
								ATTRIBUTE_KIND, String.class,
								thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES)
							)
							.withSortableAttributeCompound(
								COMPOUND_ORDER_LABEL, localizedCompoundElements,
								thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
							)
							.withSortableAttributeCompound(
								COMPOUND_ORDER_KIND, compoundElements, thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
							)
					)
					.withReferenceToEntity(
						REFERENCE_ITEMS_FILTERING, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
							.withAttribute(
								ATTRIBUTE_ORDER, Integer.class,
								thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES).nullable()
							)
							.withAttribute(
								ATTRIBUTE_LABEL, String.class,
								thatIs -> thatIs.localized().sortableInScope(BOTH_SCOPES).nullable()
							)
							.withAttribute(
								ATTRIBUTE_KIND, String.class,
								thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES)
							)
							.withSortableAttributeCompound(
								COMPOUND_ORDER_KIND, compoundElements, thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
							)
					)
					.updateVia(session);

				final Random random = new Random(SEED);
				final Integer[] targetRank = new Integer[TARGET_COUNT + 1];
				final List<Integer> ranks = new ArrayList<>(IntStream.rangeClosed(1, TARGET_COUNT).boxed().toList());
				java.util.Collections.shuffle(ranks, random);
				for (int target = 1; target <= TARGET_COUNT; target++) {
					final EntityBuilder builder = session.createNewEntity(ENTITY_TARGET, target);
					// every ninth target has no rank, so the nested sorter appends it after the ranked ones
					if (target % UNRANKED_TARGET_MODULO != 0) {
						targetRank[target] = ranks.get(target - 1);
						builder.setAttribute(ATTRIBUTE_RANK, targetRank[target]);
					}
					builder.upsertVia(session);
				}

				final Map<Integer, List<Row>> rowsByOwner = new HashMap<>(OWNER_COUNT);
				final boolean[] archived = new boolean[OWNER_COUNT + 1];
				for (int owner = 1; owner <= OWNER_COUNT; owner++) {
					final List<Row> rows = new ArrayList<>(6);
					if (owner % 10 != 0) {
						// targets 1 and 2 hold more owners than the membership covers
						if (owner <= 150) {
							rows.add(randomRow(random, 1));
						}
						if (owner <= 80) {
							rows.add(randomRow(random, 2));
						}
						final int extra = random.nextInt(4);
						for (int i = 0; i < extra; i++) {
							final int target = 3 + random.nextInt(TARGET_COUNT - 2);
							if (rows.stream().noneMatch(it -> it.target() == target)) {
								rows.add(randomRow(random, target));
							}
						}
					}
					rowsByOwner.put(owner, rows);
					final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, owner)
						.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner " + owner);
					for (String referenceName : new String[]{REFERENCE_ITEMS_PARTITIONING, REFERENCE_ITEMS_FILTERING}) {
						for (Row row : rows) {
							builder.setReference(
								referenceName, row.target(),
								whichIs -> {
									whichIs.setAttribute(ATTRIBUTE_KIND, row.kind());
									if (row.order() != null) {
										whichIs.setAttribute(ATTRIBUTE_ORDER, row.order());
									}
									if (row.label() != null) {
										whichIs.setAttribute(ATTRIBUTE_LABEL, LOCALE, row.label());
									}
								}
							);
						}
					}
					builder.upsertVia(session);
				}
				for (int owner = 7; owner <= OWNER_COUNT; owner += 7) {
					session.archiveEntity(ENTITY_OWNER, owner);
					archived[owner] = true;
				}
				return new DataCarrier("oracleFixture", new OracleFixture(rowsByOwner, targetRank, archived));
			}
		);
	}

	/**
	 * Creates a row with random values; small value ranges make ties frequent.
	 */
	@Nonnull
	private static Row randomRow(@Nonnull Random random, int target) {
		return new Row(
			target,
			random.nextInt(5) == 0 ? null : random.nextInt(12),
			random.nextInt(4) == 0 ? null : String.valueOf((char) ('a' + random.nextInt(10))),
			random.nextBoolean() ? "x" : "y"
		);
	}

	@DisplayName("Should order owners as the oracle does")
	@UseDataSet(PICK_FIRST_ORACLE)
	@ParameterizedTest(name = "{0} via {1}, targets {2}, value {3}")
	@MethodSource("oracleCombinations")
	void shouldOrderOwnersAsTheOracleDoes(
		String referenceName,
		SortRoute route,
		TargetOrder targetOrder,
		SortedValue sortedValue,
		EvitaSessionContract session,
		OracleFixture oracleFixture
	) {
		final List<Executable> assertions = new ArrayList<>(Selection.values().length * 2);
		for (Selection selection : Selection.values()) {
			for (OrderDirection direction : OrderDirection.values()) {
				final int[] expected = oracle(oracleFixture, selection, targetOrder, sortedValue, direction);
				final int[] actual = queryOrder(
					session, route, selection, referenceName,
					createOrdering(referenceName, targetOrder, valueOrdering(sortedValue, direction))
				);
				assertions.add(
					() -> assertArrayEquals(
						expected, actual,
						() -> selection + " " + direction + ": expected " + Arrays.toString(expected) +
							" but was " + Arrays.toString(actual)
					)
				);
			}
		}
		assertAll(assertions);
	}

	@DisplayName("Should order owners by a compound identically on both routes")
	@UseDataSet(PICK_FIRST_ORACLE)
	@ParameterizedTest(name = "{0} by {1}")
	@MethodSource("compounds")
	void shouldOrderByCompoundIdenticallyOnBothRoutes(
		String referenceName, String compoundName, EvitaSessionContract session
	) {
		final List<Executable> assertions = new ArrayList<>(Selection.values().length * 2);
		for (Selection selection : Selection.values()) {
			for (OrderDirection direction : OrderDirection.values()) {
				final OrderConstraint ordering = referenceProperty(
					referenceName, attributeNatural(compoundName, direction)
				);
				final int[] index = queryOrder(session, SortRoute.INDEX, selection, referenceName, ordering);
				final int[] prefetch = queryOrder(session, SortRoute.PREFETCH, selection, referenceName, ordering);
				assertions.add(
					() -> assertArrayEquals(
						index, prefetch,
						() -> selection + " " + direction + ": index route " + Arrays.toString(index) +
							" but prefetch route " + Arrays.toString(prefetch)
					)
				);
			}
		}
		assertAll(assertions);
	}

	@DisplayName("Should order owners of the ordering scope only, identically on both routes")
	@UseDataSet(PICK_FIRST_ORACLE)
	@ParameterizedTest(name = "{0}, targets {1}, value {2}")
	@MethodSource("scopeCombinations")
	void shouldOrderOwnersOfOrderingScopeOnlyIdenticallyOnBothRoutes(
		String referenceName,
		TargetOrder targetOrder,
		SortedValue sortedValue,
		EvitaSessionContract session,
		OracleFixture oracleFixture
	) {
		final List<Executable> assertions = new ArrayList<>(OrderDirection.values().length * SortRoute.values().length);
		for (OrderDirection direction : OrderDirection.values()) {
			// the filter selects both scopes, the ordering applies to the live owners only - the archived owners must
			// fall through to the next sorter on both routes, never be sorted among the live ones on one of them
			final OrderConstraint ordering = inScope(
				Scope.LIVE, createOrdering(referenceName, targetOrder, valueOrdering(sortedValue, direction))
			);
			final int[] expected = oracle(
				oracleFixture, Selection.BOTH_SCOPES, targetOrder, List.of(sortedValue), direction, false
			);
			for (SortRoute measuredRoute : SortRoute.values()) {
				final int[] actual = queryOrder(session, measuredRoute, Selection.BOTH_SCOPES, referenceName, ordering);
				assertions.add(
					() -> assertArrayEquals(
						expected, actual,
						() -> direction + " via " + measuredRoute + ": expected " + Arrays.toString(expected) +
							" but was " + Arrays.toString(actual)
					)
				);
			}
		}
		assertAll(assertions);
	}

	@DisplayName("Should order owners by two values of one reference as the oracle does")
	@UseDataSet(PICK_FIRST_ORACLE)
	@ParameterizedTest(name = "{0} via {1}, targets {2}")
	@MethodSource("chainCombinations")
	void shouldOrderByTwoValuesOfOneReferenceAsTheOracleDoes(
		String referenceName,
		SortRoute route,
		TargetOrder targetOrder,
		EvitaSessionContract session,
		OracleFixture oracleFixture
	) {
		// the second value sorts only the owners the first one leaves unsorted, on a selection without the others
		final List<Executable> assertions = new ArrayList<>(Selection.values().length * 2);
		for (Selection selection : Selection.values()) {
			for (OrderDirection direction : OrderDirection.values()) {
				final int[] expected = oracle(
					oracleFixture, selection, targetOrder, List.of(SortedValue.ORDER, SortedValue.LABEL), direction
				);
				final int[] actual = queryOrder(
					session, route, selection, referenceName,
					createOrdering(
						referenceName, targetOrder,
						valueOrdering(SortedValue.ORDER, direction), valueOrdering(SortedValue.LABEL, direction)
					)
				);
				assertions.add(
					() -> assertArrayEquals(
						expected, actual,
						() -> selection + " " + direction + ": expected " + Arrays.toString(expected) +
							" but was " + Arrays.toString(actual)
					)
				);
			}
		}
		assertAll(assertions);
	}

	/**
	 * Creates the constraint ordering by the value.
	 */
	@Nonnull
	private static OrderConstraint valueOrdering(@Nonnull SortedValue sortedValue, @Nonnull OrderDirection direction) {
		return switch (sortedValue) {
			case ORDER -> attributeNatural(ATTRIBUTE_ORDER, direction);
			case LABEL -> attributeNatural(ATTRIBUTE_LABEL, direction);
			case REFERENCED_PRIMARY_KEY -> entityPrimaryKeyNatural(direction);
		};
	}

	/**
	 * Wraps the value orderings in `referenceProperty` with the target order.
	 */
	@Nonnull
	private static OrderConstraint createOrdering(
		@Nonnull String referenceName,
		@Nonnull TargetOrder targetOrder,
		@Nonnull OrderConstraint... valueOrderings
	) {
		final OrderConstraint targetOrdering = switch (targetOrder) {
			case DEFAULT -> null;
			case PRIMARY_KEY_DESCENDING -> pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC));
			case TARGET_ATTRIBUTE -> pickFirstByEntityProperty(attributeNatural(ATTRIBUTE_RANK));
		};
		return referenceProperty(
			referenceName,
			Stream.concat(Stream.ofNullable(targetOrdering), Arrays.stream(valueOrderings))
				.toArray(OrderConstraint[]::new)
		);
	}

	/**
	 * Runs the ordering on the route and returns the owner primary keys in result order.
	 */
	@Nonnull
	private static int[] queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Selection selection,
		@Nonnull String referenceName,
		@Nonnull OrderConstraint ordering
	) {
		final Integer[] owners = switch (selection) {
			case SMALL -> Arrays.stream(SMALL_SELECTION).boxed().toArray(Integer[]::new);
			default -> IntStream.rangeClosed(1, OWNER_COUNT).boxed().toArray(Integer[]::new);
		};
		final FilterConstraint narrowing = selection == Selection.NARROWED ?
			referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, "x")) : null;
		final FilterConstraint scope = selection == Selection.BOTH_SCOPES ? scope(BOTH_SCOPES) : null;
		return session.query(
				query(
					collection(ENTITY_OWNER),
					filterBy(scope, entityLocaleEquals(LOCALE), entityPrimaryKeyInSet(owners), narrowing),
					orderBy(ordering),
					require(
						page(1, OWNER_COUNT),
						debug(route == SortRoute.INDEX ? DebugMode.PREFER_INDEX_SCAN : DebugMode.PREFER_PREFETCHING)
					)
				),
				EntityReference.class
			)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
	}

	/**
	 * Computes the expected order from the rows the test wrote.
	 */
	@Nonnull
	private static int[] oracle(
		@Nonnull OracleFixture fixture,
		@Nonnull Selection selection,
		@Nonnull TargetOrder targetOrder,
		@Nonnull SortedValue sortedValue,
		@Nonnull OrderDirection direction
	) {
		return oracle(fixture, selection, targetOrder, List.of(sortedValue), direction);
	}

	/**
	 * Computes the expected order of a chain of values of one reference from the rows the test wrote: the owners
	 * the first value sorts come first, the owners without it but with the second value follow sorted by the second
	 * one, and so on; each value is picked from the owner's first row in target order that carries it.
	 */
	@Nonnull
	private static int[] oracle(
		@Nonnull OracleFixture fixture,
		@Nonnull Selection selection,
		@Nonnull TargetOrder targetOrder,
		@Nonnull List<SortedValue> sortedValues,
		@Nonnull OrderDirection direction
	) {
		return oracle(fixture, selection, targetOrder, sortedValues, direction, true);
	}

	/**
	 * Computes the expected order like {@link #oracle(OracleFixture, Selection, TargetOrder, List, OrderDirection)},
	 * optionally ordering the live owners only - as `inScope(LIVE, ...)` does - so that selected archived owners
	 * join the unsorted ones.
	 */
	@Nonnull
	private static int[] oracle(
		@Nonnull OracleFixture fixture,
		@Nonnull Selection selection,
		@Nonnull TargetOrder targetOrder,
		@Nonnull List<SortedValue> sortedValues,
		@Nonnull OrderDirection direction,
		boolean orderArchived
	) {
		final IntUnaryOperator rank = switch (targetOrder) {
			case DEFAULT -> target -> target;
			case PRIMARY_KEY_DESCENDING -> target -> -target;
			// the nested sorter appends the targets without a rank in ascending primary key order
			case TARGET_ATTRIBUTE -> target -> fixture.targetRank()[target] == null ?
				TARGET_COUNT + target : fixture.targetRank()[target];
		};
		final int[] candidates = selection == Selection.SMALL ?
			SMALL_SELECTION : IntStream.rangeClosed(1, OWNER_COUNT).toArray();
		final List<List<int[]>> sorted = new ArrayList<>(sortedValues.size());
		for (int i = 0; i < sortedValues.size(); i++) {
			sorted.add(new ArrayList<>(candidates.length));
		}
		final List<Integer> unsorted = new ArrayList<>();
		final Map<Integer, Comparable<?>> values = new HashMap<>(candidates.length);
		for (int owner : candidates) {
			final List<Row> rows = fixture.rowsByOwner().get(owner);
			final boolean selected = (selection == Selection.BOTH_SCOPES || !fixture.archived()[owner]) &&
				(selection != Selection.NARROWED || rows.stream().anyMatch(it -> "x".equals(it.kind())));
			if (selected) {
				boolean placed = false;
				final boolean ordered = orderArchived || !fixture.archived()[owner];
				for (int i = 0; i < sortedValues.size() && ordered && !placed; i++) {
					final Function<Row, Comparable<?>> valueOf = valueExtractor(sortedValues.get(i));
					final Comparable<?> value = rows.stream()
						.filter(it -> valueOf.apply(it) != null)
						.min(Comparator.comparingInt(it -> rank.applyAsInt(it.target())))
						.map(valueOf)
						.orElse(null);
					if (value != null) {
						values.put(owner, value);
						sorted.get(i).add(new int[]{owner});
						placed = true;
					}
				}
				if (!placed) {
					unsorted.add(owner);
				}
			}
		}
		@SuppressWarnings({"unchecked", "rawtypes"})
		final Comparator<int[]> byValue = (a, b) -> ((Comparable) values.get(a[0])).compareTo(values.get(b[0]));
		final Comparator<int[]> byPrimaryKey = Comparator.comparingInt(a -> a[0]);
		final IntStream.Builder result = IntStream.builder();
		for (List<int[]> group : sorted) {
			group.sort(
				direction == OrderDirection.ASC ?
					byValue.thenComparing(byPrimaryKey) :
					byValue.reversed().thenComparing(byPrimaryKey.reversed())
			);
			group.forEach(it -> result.add(it[0]));
		}
		unsorted.stream().mapToInt(Integer::intValue).sorted().forEach(result::add);
		return result.build().toArray();
	}

	/**
	 * Returns the function reading the value from a row.
	 */
	@Nonnull
	private static Function<Row, Comparable<?>> valueExtractor(@Nonnull SortedValue sortedValue) {
		return switch (sortedValue) {
			case ORDER -> Row::order;
			case LABEL -> Row::label;
			case REFERENCED_PRIMARY_KEY -> Row::target;
		};
	}

}
