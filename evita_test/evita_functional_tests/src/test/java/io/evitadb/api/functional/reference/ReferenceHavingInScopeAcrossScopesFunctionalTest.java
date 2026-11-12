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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.Debug;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an `inScope` container inside the body of `referenceHaving` restricts the reference rows of its own
 * scope only, exactly as the same container restricts the entities of its own scope at the top level of a filter.
 * Every row of a reference lives in the scope of its owner, so in a query over both scopes `inScope(LIVE, x)` keeps the
 * live rows satisfying `x` and every archived row, and `not(inScope(LIVE, x))` keeps the live rows not satisfying `x`
 * and no archived row.
 *
 * ## The fixture
 *
 * `product` has two references to the non-managed entity type `tag` with the same rows - `tags`, indexed for filtering,
 * whose single-owner partitions are kept as registry rows, and `partitionedTags`, indexed for filtering and
 * partitioning, whose every partition is a reduced entity index. Both are indexed in both scopes and carry the
 * reference attributes `primary` and `rank`, filterable in both scopes:
 *
 * | product | scope | rows (tag: primary, rank) | partitions of `tags` |
 * |---|---|---|---|
 * | 1 | LIVE | 1: true, 1 | `1` shared with 2 and 5 - an index object |
 * | 2 | LIVE | 1: false, 2 | |
 * | 3 | LIVE | 2: true, 1 | single-owner - a registry row |
 * | 4 | LIVE | 3: false, 2 | single-owner - a registry row |
 * | 5 | LIVE | 1: true, 1 and 4: false, 2 | `1` shared, `4` single-owner |
 * | 6 | LIVE | none | |
 * | 101 | ARCHIVED | 11: true, 1 | `11` shared with 102 and 105 - an index object |
 * | 102 | ARCHIVED | 11: false, 2 | |
 * | 103 | ARCHIVED | 12: true, 1 | single-owner - a registry row |
 * | 104 | ARCHIVED | 13: false, 2 | single-owner - a registry row |
 * | 105 | ARCHIVED | 11: true, 1 and 14: false, 2 | `11` shared, `14` single-owner |
 * | 106 | ARCHIVED | none | |
 * | 107 | ARCHIVED | 1: false, 1 | single-owner in the archived scope, shared in the live one |
 *
 * Products 5 and 105 hold one row satisfying `primary` and another satisfying `rank = 2`, so a body combining both
 * must not match them by combining the two rows. Every expected answer of a body is computed from the bodies of the
 * products, never from another query.
 *
 * A nested pass processes fewer scopes than the query requests: the `entityHaving` of `product.categories` filters the
 * categories one scope at a time, so an `inScope` of the other scope in the body of a `referenceHaving` of the
 * categories names a scope that pass examines no row of. The non-managed `category.tags` reference, indexed for
 * filtering in both scopes with the reference attribute `primary`, serves that case:
 *
 * | category | scope | `tags` rows (tag: primary) | product referencing it |
 * |---|---|---|---|
 * | 1 | LIVE | 1: false - shared with 2 | 1 |
 * | 2 | LIVE | 1: true | |
 * | 3 | LIVE | none | 2 |
 * | 4 | LIVE | 5: false - single-owner, a registry row | 3 |
 * | 11 | ARCHIVED | 11: true - shared with 12 | 102 |
 * | 12 | ARCHIVED | 11: false | 101 |
 * | 13 | ARCHIVED | 15: false - single-owner, a registry row | 103 |
 *
 * Every query runs on both plans of {@link PlanPreference} - except an outer `not(referenceHaving(...))`, which does
 * not take the PREFETCH plan and runs on INDEX_SCAN only - and proves from its telemetry that it ran on that plan. On
 * the index-scan plan it also verifies its caching trees and the alternative index plans, where index selection
 * offers any.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("inScope inside referenceHaving restricts the rows of its own scope only")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public class ReferenceHavingInScopeAcrossScopesFunctionalTest {
	private static final String DATA_SET = "referenceHavingInScopeAcrossScopes";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_TAG = "tag";
	private static final String REFERENCE_TAGS = "tags";
	private static final String REFERENCE_PARTITIONED_TAGS = "partitionedTags";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_PRIMARY = "primary";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * Every product of the fixture - the keys the prefetch plan conjoins with the filter, neutral to every answer.
	 */
	private static final int[] ALL_PRODUCTS = {1, 2, 3, 4, 5, 6, 101, 102, 103, 104, 105, 106, 107};

	/**
	 * One reference row of the fixture.
	 *
	 * @param tag     the primary key of the referenced tag
	 * @param primary the value of the `primary` attribute
	 * @param rank    the value of the `rank` attribute
	 */
	private record Row(int tag, boolean primary, int rank) {
	}

	/**
	 * Decides from one reference row and the scope of its owner whether the body of a case matches the row.
	 */
	@FunctionalInterface
	private interface RowPredicate {
		/**
		 * Tells whether the body matches the row.
		 *
		 * @param scope   the scope of the owner, and so of the row
		 * @param primary the value of the `primary` attribute of the row
		 * @param rank    the value of the `rank` attribute of the row
		 * @return TRUE when the row satisfies the body
		 */
		boolean test(@Nonnull Scope scope, boolean primary, int rank);
	}

	/**
	 * One body of `referenceHaving` with the answer it must give for a row.
	 *
	 * @param name      the description of the body
	 * @param body      the body
	 * @param matches   the expected answer for a row
	 * @param scopeSets the scope sets the body is admitted in - a set must request every scope an `inScope` names
	 */
	private record BodyCase(
		@Nonnull String name,
		@Nonnull FilterConstraint body,
		@Nonnull RowPredicate matches,
		@Nonnull Scope[][] scopeSets
	) {
		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * Returns the bodies the parameterized test runs, each with every reference, every scope set it is admitted in and
	 * both plans.
	 *
	 * @return the arguments: the case, the reference name, the requested scopes and the plan
	 */
	@Nonnull
	static Stream<Arguments> bodies() {
		final List<BodyCase> cases = List.of(
			new BodyCase(
				"inScope(LIVE, primary)",
				inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)),
				(scope, primary, rank) -> scope != Scope.LIVE || primary,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"inScope(ARCHIVED, primary)",
				inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_PRIMARY, true)),
				(scope, primary, rank) -> scope != Scope.ARCHIVED || primary,
				new Scope[][]{ARCHIVED, BOTH}
			),
			new BodyCase(
				"not(inScope(ARCHIVED, primary))",
				not(inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_PRIMARY, true))),
				(scope, primary, rank) -> scope == Scope.ARCHIVED && !primary,
				new Scope[][]{ARCHIVED, BOTH}
			),
			new BodyCase(
				"or(inScope(LIVE, primary), inScope(ARCHIVED, primary))",
				or(
					inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)),
					inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_PRIMARY, true))
				),
				// every row satisfies the container of the other scope, and so the disjunction
				(scope, primary, rank) -> true,
				new Scope[][]{BOTH}
			),
			new BodyCase(
				"and(inScope(LIVE, primary), inScope(ARCHIVED, not primary))",
				and(
					inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)),
					inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_PRIMARY, false))
				),
				(scope, primary, rank) -> scope == Scope.LIVE ? primary : !primary,
				new Scope[][]{BOTH}
			),
			new BodyCase(
				"or(inScope(LIVE, primary), rank = 2)",
				or(
					inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)),
					attributeEquals(ATTRIBUTE_RANK, 2)
				),
				(scope, primary, rank) -> scope != Scope.LIVE || primary || rank == 2,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"and(inScope(LIVE, primary), rank = 2)",
				and(
					inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)),
					attributeEquals(ATTRIBUTE_RANK, 2)
				),
				(scope, primary, rank) -> (scope != Scope.LIVE || primary) && rank == 2,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"not(inScope(LIVE, primary))",
				not(inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true))),
				(scope, primary, rank) -> scope == Scope.LIVE && !primary,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"inScope(LIVE, not(primary))",
				inScope(Scope.LIVE, not(attributeEquals(ATTRIBUTE_PRIMARY, true))),
				(scope, primary, rank) -> scope != Scope.LIVE || !primary,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"not(inScope(LIVE, not(primary)))",
				not(inScope(Scope.LIVE, not(attributeEquals(ATTRIBUTE_PRIMARY, true)))),
				(scope, primary, rank) -> scope == Scope.LIVE && primary,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"and(inScope(LIVE, not(primary)), rank = 2)",
				and(
					inScope(Scope.LIVE, not(attributeEquals(ATTRIBUTE_PRIMARY, true))),
					attributeEquals(ATTRIBUTE_RANK, 2)
				),
				(scope, primary, rank) -> (scope != Scope.LIVE || !primary) && rank == 2,
				new Scope[][]{LIVE, BOTH}
			),
			new BodyCase(
				"not(or(inScope(LIVE, not(primary)), rank = 2))",
				not(
					or(
						inScope(Scope.LIVE, not(attributeEquals(ATTRIBUTE_PRIMARY, true))),
						attributeEquals(ATTRIBUTE_RANK, 2)
					)
				),
				(scope, primary, rank) -> scope == Scope.LIVE && primary && rank != 2,
				new Scope[][]{LIVE, BOTH}
			)
		);
		final List<Arguments> arguments = new ArrayList<>(64);
		for (final BodyCase theCase : cases) {
			for (final String referenceName : new String[]{REFERENCE_TAGS, REFERENCE_PARTITIONED_TAGS}) {
				for (final Scope[] scopes : theCase.scopeSets()) {
					for (final PlanPreference plan : PlanPreference.values()) {
						arguments.add(Arguments.of(theCase, referenceName, scopes, plan));
					}
				}
			}
		}
		return arguments.stream();
	}

	/**
	 * Builds the fixture described in the class documentation.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(DATA_SET)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withReferenceTo(
						REFERENCE_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(
								ATTRIBUTE_PRIMARY, Boolean.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							)
					)
					.updateVia(session);
				createCategory(session, 1, Scope.LIVE, 1, false);
				createCategory(session, 2, Scope.LIVE, 1, true);
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 3));
				createCategory(session, 4, Scope.LIVE, 5, false);
				createCategory(session, 11, Scope.ARCHIVED, 11, true);
				createCategory(session, 12, Scope.ARCHIVED, 11, false);
				createCategory(session, 13, Scope.ARCHIVED, 15, false);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withReferenceTo(
						REFERENCE_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(
								ATTRIBUTE_PRIMARY, Boolean.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							)
							.withAttribute(
								ATTRIBUTE_RANK, Integer.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							)
					)
					.withReferenceTo(
						REFERENCE_PARTITIONED_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(
								ATTRIBUTE_PRIMARY, Boolean.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							)
							.withAttribute(
								ATTRIBUTE_RANK, Integer.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							)
					)
					.updateVia(session);
				createProduct(session, 1, Scope.LIVE, 1, new Row(1, true, 1));
				createProduct(session, 2, Scope.LIVE, 3, new Row(1, false, 2));
				createProduct(session, 3, Scope.LIVE, 4, new Row(2, true, 1));
				createProduct(session, 4, Scope.LIVE, null, new Row(3, false, 2));
				createProduct(session, 5, Scope.LIVE, null, new Row(1, true, 1), new Row(4, false, 2));
				createProduct(session, 6, Scope.LIVE, null);
				createProduct(session, 101, Scope.ARCHIVED, 12, new Row(11, true, 1));
				createProduct(session, 102, Scope.ARCHIVED, 11, new Row(11, false, 2));
				createProduct(session, 103, Scope.ARCHIVED, 13, new Row(12, true, 1));
				createProduct(session, 104, Scope.ARCHIVED, null, new Row(13, false, 2));
				createProduct(session, 105, Scope.ARCHIVED, null, new Row(11, true, 1), new Row(14, false, 2));
				createProduct(session, 106, Scope.ARCHIVED, null);
				createProduct(session, 107, Scope.ARCHIVED, null, new Row(1, false, 1));
			}
		);
		// fixture guard: both representations must be present in both scopes, or the cases would not reach them
		assertEquals(3, countRegistryRows(evita, ENTITY_PRODUCT, REFERENCE_TAGS, Scope.LIVE));
		assertEquals(4, countRegistryRows(evita, ENTITY_PRODUCT, REFERENCE_TAGS, Scope.ARCHIVED));
		assertEquals(0, countRegistryRows(evita, ENTITY_PRODUCT, REFERENCE_PARTITIONED_TAGS, Scope.LIVE));
		assertEquals(0, countRegistryRows(evita, ENTITY_PRODUCT, REFERENCE_PARTITIONED_TAGS, Scope.ARCHIVED));
		assertEquals(1, countRegistryRows(evita, ENTITY_CATEGORY, REFERENCE_TAGS, Scope.LIVE));
		assertEquals(1, countRegistryRows(evita, ENTITY_CATEGORY, REFERENCE_TAGS, Scope.ARCHIVED));
	}

	@ParameterizedTest(name = "{0} of {1} in {2} on {3}")
	@MethodSource("bodies")
	@UseDataSet(DATA_SET)
	@DisplayName("referenceHaving with an inScope body matches the rows the body admits in every queried scope")
	void shouldMatchRowsOfEveryScopeAsTheBodyAdmitsThem(
		@Nonnull BodyCase theCase,
		@Nonnull String referenceName,
		@Nonnull Scope[] scopes,
		@Nonnull PlanPreference plan,
		@Nonnull Evita evita
	) {
		final List<Integer> expected = expectedOwners(evita, referenceName, scopes, theCase.matches());
		assertEquals(
			expected,
			queryProducts(evita, scopes, plan, referenceHaving(referenceName, theCase.body()))
		);
	}

	/**
	 * Runs on INDEX_SCAN because an outer `not(referenceHaving(...))` does not take the PREFETCH plan.
	 * The expected complement includes every requested owner without a matching row, including owners without rows.
	 */
	@ParameterizedTest(name = "{0} of {1} on {2}")
	@MethodSource("negatedBodies")
	@UseDataSet(DATA_SET)
	@DisplayName("negated referenceHaving complements the owners its body admits")
	void shouldComplementAdmittedOwnersWhenReferenceHavingIsNegated(
		@Nonnull BodyCase theCase,
		@Nonnull String referenceName,
		@Nonnull PlanPreference plan,
		@Nonnull Evita evita
	) {
		final List<Integer> matchingOwners = expectedOwners(evita, referenceName, BOTH, theCase.matches());
		final List<Integer> expected = Arrays.stream(ALL_PRODUCTS)
			.filter(primaryKey -> !matchingOwners.contains(primaryKey))
			.boxed()
			.toList();
		assertEquals(
			expected,
			queryProducts(evita, BOTH, plan, not(referenceHaving(referenceName, theCase.body())))
		);
	}

	/**
	 * Exercises candidate discovery below `or` on both plans and unions the row oracle's owners with product 6.
	 */
	@ParameterizedTest(name = "{0} on {1}")
	@MethodSource("referenceNamesAndPlans")
	@UseDataSet(DATA_SET)
	@DisplayName("a referenceHaving disjunction unions scoped matching owners with the requested primary key")
	void shouldUnionOwnersWhenReferenceBodyRestrictsLiveScopeInDisjunction(
		@Nonnull String referenceName,
		@Nonnull PlanPreference plan,
		@Nonnull Evita evita
	) {
		final List<Integer> matchingOwners = expectedOwners(
			evita, referenceName, BOTH, (scope, primary, rank) -> scope != Scope.LIVE || primary
		);
		final List<Integer> expected = Arrays.stream(ALL_PRODUCTS)
			.filter(primaryKey -> matchingOwners.contains(primaryKey) || primaryKey == 6)
			.boxed()
			.toList();
		assertEquals(
			expected,
			queryProducts(
				evita, BOTH, plan,
				or(
					referenceHaving(referenceName, inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true))),
					entityPrimaryKeyInSet(6)
				)
			)
		);
	}

	@ParameterizedTest(name = "{0} on {1}")
	@MethodSource("referenceNamesAndPlans")
	@UseDataSet(DATA_SET)
	@DisplayName("the query of the issue keeps the archived owner next to the live one")
	void shouldKeepArchivedOwnerWhenTheBodyRestrictsTheLiveScope(
		@Nonnull String referenceName,
		@Nonnull PlanPreference plan,
		@Nonnull Evita evita
	) {
		assertEquals(
			List.of(3, 103, 105),
			queryProducts(
				evita, BOTH, plan,
				entityPrimaryKeyInSet(3, 4, 103, 105, 106),
				referenceHaving(referenceName, inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)))
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@UseDataSet(DATA_SET)
	@DisplayName("a body restricting a scope its nested pass examines no row of keeps every row of that pass")
	void shouldKeepEveryRowOfNestedPassWhenBodyRestrictsAnotherScope(
		@Nonnull PlanPreference plan,
		@Nonnull Evita evita
	) {
		// the live pass of `entityHaving` keeps every live category holding a tag - 1 and 4, the latter through a
		// registry row - and the archived pass the archived ones holding a primary tag - 11; see the class
		// documentation for the products referencing them
		assertEquals(
			List.of(1, 3, 102),
			queryProducts(
				evita, BOTH, plan,
				referenceHaving(
					REFERENCE_CATEGORIES,
					entityHaving(
						referenceHaving(
							REFERENCE_TAGS, inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_PRIMARY, true))
						)
					)
				)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@UseDataSet(DATA_SET)
	@DisplayName("a body restricting the live scope keeps every row of the archived nested pass")
	void shouldKeepEveryArchivedRowOfNestedPassWhenBodyRestrictsLiveScope(
		@Nonnull PlanPreference plan,
		@Nonnull Evita evita
	) {
		// the live pass of `entityHaving` keeps the live categories holding a primary tag - 2, which no product
		// references - and the archived pass every archived category holding a tag - 11 and 12 through an index
		// object, 13 through a registry row; see the class documentation for the products referencing them
		assertEquals(
			List.of(101, 102, 103),
			queryProducts(
				evita, BOTH, plan,
				referenceHaving(
					REFERENCE_CATEGORIES,
					entityHaving(
						referenceHaving(
							REFERENCE_TAGS, inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true))
						)
					)
				)
			)
		);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("the expected answer selects owners reached through registry rows and owners of the archived scope")
	void shouldExpectOwnersOfRegistryRowsAndArchivedScopeWhenBodyRestrictsLiveScope(@Nonnull Evita evita) {
		// guards the oracle the parameterized cases compare with, not the engine: an expected answer missing the
		// archived owners, or those reached through a registry row, would agree with an engine dropping the very
		// same rows
		final List<Integer> expected = expectedOwners(
			evita, REFERENCE_TAGS, BOTH, (scope, primary, rank) -> scope != Scope.LIVE || primary
		);
		assertTrue(expected.containsAll(List.of(3, 101, 103, 104, 107)), expected.toString());
		assertFalse(expected.contains(106), expected.toString());
	}

	/**
	 * Returns both references of the fixture products, each with both plans.
	 *
	 * @return the arguments: the reference name and the plan
	 */
	@Nonnull
	static Stream<Arguments> referenceNamesAndPlans() {
		final List<Arguments> arguments = new ArrayList<>(4);
		for (final String referenceName : new String[]{REFERENCE_TAGS, REFERENCE_PARTITIONED_TAGS}) {
			for (final PlanPreference plan : PlanPreference.values()) {
				arguments.add(Arguments.of(referenceName, plan));
			}
		}
		return arguments.stream();
	}

	/**
	 * Returns the bodies the outer negation is tested with, each with every reference, in a query over both scopes and
	 * on INDEX_SCAN only, which outer negations are restricted to because they do not take the PREFETCH plan.
	 *
	 * @return the arguments: the case, the reference name and INDEX_SCAN
	 */
	@Nonnull
	static Stream<Arguments> negatedBodies() {
		final List<BodyCase> cases = List.of(
			new BodyCase(
				"inScope(LIVE, primary)",
				inScope(Scope.LIVE, attributeEquals(ATTRIBUTE_PRIMARY, true)),
				(scope, primary, rank) -> scope != Scope.LIVE || primary,
				new Scope[][]{BOTH}
			),
			new BodyCase(
				"inScope(ARCHIVED, primary)",
				inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_PRIMARY, true)),
				(scope, primary, rank) -> scope != Scope.ARCHIVED || primary,
				new Scope[][]{BOTH}
			),
			new BodyCase(
				"inScope(LIVE, not(primary))",
				inScope(Scope.LIVE, not(attributeEquals(ATTRIBUTE_PRIMARY, true))),
				(scope, primary, rank) -> scope != Scope.LIVE || !primary,
				new Scope[][]{BOTH}
			)
		);
		final List<Arguments> arguments = new ArrayList<>(cases.size() * 2);
		for (final BodyCase theCase : cases) {
			for (final String referenceName : new String[]{REFERENCE_TAGS, REFERENCE_PARTITIONED_TAGS}) {
				arguments.add(Arguments.of(theCase, referenceName, PlanPreference.INDEX_SCAN));
			}
		}
		return arguments.stream();
	}

	/**
	 * Creates a category holding one row of `tags`.
	 *
	 * @param session    the session
	 * @param primaryKey the primary key of the category
	 * @param scope      the scope of the category
	 * @param tag        the primary key of the referenced tag
	 * @param primary    the value of the `primary` attribute of the row
	 */
	private static void createCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Scope scope,
		int tag,
		boolean primary
	) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_CATEGORY, primaryKey)
				.setScope(scope)
				.setReference(REFERENCE_TAGS, tag, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRIMARY, primary))
		);
	}

	/**
	 * Creates a product with the rows set on both tag references.
	 *
	 * @param session    the session
	 * @param primaryKey the primary key of the product
	 * @param scope      the scope of the product
	 * @param category   the primary key of the category the product references, NULL for none
	 * @param rows       the rows of both tag references
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Scope scope,
		@Nullable Integer category,
		@Nonnull Row... rows
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_PRODUCT, primaryKey).setScope(scope);
		if (category != null) {
			builder.setReference(REFERENCE_CATEGORIES, category);
		}
		for (final Row row : rows) {
			for (final String referenceName : new String[]{REFERENCE_TAGS, REFERENCE_PARTITIONED_TAGS}) {
				builder.setReference(
					referenceName, row.tag(),
					whichIs -> whichIs
						.setAttribute(ATTRIBUTE_PRIMARY, row.primary())
						.setAttribute(ATTRIBUTE_RANK, row.rank())
				);
			}
		}
		session.upsertEntity(builder);
	}

	/**
	 * Returns the owners of the requested scopes holding a row of the reference the predicate accepts, computed from
	 * the bodies of the products, in primary key order.
	 *
	 * @param evita         the engine instance
	 * @param referenceName the reference
	 * @param scopes        the requested scopes
	 * @param matches       the expected answer for a row
	 * @return the primary keys of the owners
	 */
	@Nonnull
	private static List<Integer> expectedOwners(
		@Nonnull Evita evita,
		@Nonnull String referenceName,
		@Nonnull Scope[] scopes,
		@Nonnull RowPredicate matches
	) {
		final Set<Scope> requested = EnumSet.copyOf(Arrays.asList(scopes));
		final List<SealedEntity> products = evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.queryListOfSealedEntities(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
					require(page(1, 100), entityFetch(referenceContentAllWithAttributes()))
				)
			)
		);
		final List<Integer> result = new ArrayList<>(16);
		for (final SealedEntity product : products) {
			final Scope scope = product.getScope();
			if (!requested.contains(scope)) {
				continue;
			}
			for (final ReferenceContract reference : product.getReferences(referenceName)) {
				final Boolean primary = reference.getAttribute(ATTRIBUTE_PRIMARY);
				final Integer rank = reference.getAttribute(ATTRIBUTE_RANK);
				if (matches.test(scope, Boolean.TRUE.equals(primary), rank == null ? 0 : rank)) {
					result.add(product.getPrimaryKeyOrThrowException());
					break;
				}
			}
		}
		result.sort(Integer::compareTo);
		return result;
	}

	/**
	 * Returns the products matching the constraints in the requested scopes, in primary key order, and asserts from
	 * the telemetry that the query ran on the plan. On the index-scan plan the query verifies the variants of its
	 * caching trees and the alternative index plans, where index selection offers any.
	 *
	 * @param evita       the engine instance
	 * @param scopes      the requested scopes
	 * @param plan        the plan the query is steered towards
	 * @param constraints the constraints of the filter
	 * @return the primary keys of the matching products
	 */
	@Nonnull
	private static List<Integer> queryProducts(
		@Nonnull Evita evita,
		@Nonnull Scope[] scopes,
		@Nonnull PlanPreference plan,
		@Nonnull FilterConstraint... constraints
	) {
		final FilterConstraint[] scopedConstraints = new FilterConstraint[constraints.length + 1];
		scopedConstraints[0] = scope(scopes);
		System.arraycopy(constraints, 0, scopedConstraints, 1, constraints.length);
		final Debug debugRequirement = plan == PlanPreference.INDEX_SCAN ?
			debug(
				DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES,
				DebugMode.PREFER_INDEX_SCAN
			) :
			plan.debug();
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, EvitaResponse<EntityReference>>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(plan.filter(ALL_PRODUCTS, scopedConstraints)),
					require(page(1, 100), debugRequirement, queryTelemetry())
				),
				EntityReference.class
			)
		);
		plan.assertTaken(response);
		return response.getRecordData().stream().map(EntityReference::getPrimaryKey).sorted().toList();
	}

	/**
	 * Returns how many registry rows the referenced-type index of the reference holds in the scope, as the public index
	 * statistics report it.
	 *
	 * @param evita         the engine instance
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @return the number of single-owner partitions kept as registry rows
	 */
	private static int countRegistryRows(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final CollectionIndexCardinality cardinality = evita.management()
			.getEntityCollectionStatistics(
				TEST_CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY)
			)
			.indexCardinalityIfPresent()
			.orElseThrow();
		for (final IndexCardinality index : cardinality.indexes()) {
			if (index.indexType() == EntityIndexType.REFERENCED_ENTITY_TYPE &&
				index.scope() == scope && referenceName.equals(index.discriminator())) {
				return index.virtualPartitionCountIfKnown().orElse(0);
			}
		}
		throw new AssertionError("No " + scope + " referenced-type index of `" + referenceName + "`: " + cardinality);
	}

}
