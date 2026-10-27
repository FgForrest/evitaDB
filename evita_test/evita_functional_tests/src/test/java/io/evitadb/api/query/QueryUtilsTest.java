/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.Page;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link QueryUtils} - utility methods for accessing and manipulating
 * {@link Query} constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("QueryUtils")
@Tag(CONTRACT)
@Tag(QUERY)
class QueryUtilsTest {

	/**
	 * Supplies every container whose children filter a different entity, each wrapping the passed filter as its
	 * children.
	 *
	 * @return the container name and the function wrapping a filter into it
	 */
	@Nonnull
	static Stream<Arguments> differentEntityContainers() {
		return Stream.of(
			Arguments.of("entityHaving", (Function<FilterConstraint, FilterConstraint>) QueryConstraints::entityHaving),
			Arguments.of("groupHaving", (Function<FilterConstraint, FilterConstraint>) QueryConstraints::groupHaving),
			Arguments.of(
				"hierarchyWithin", (Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithin("category", it)
			),
			Arguments.of(
				"hierarchyWithinRoot",
				(Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithinRoot("category", having(it))
			)
		);
	}

	/**
	 * Covers own opinions independently from the caller's inheritance policy.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Entity-level locale resolver")
	class LocaleTest {
		/** A level without any locale constraint has no opinion of its own. */
		@Test
		@DisplayName("Returns no locale when the level names none")
		void shouldReturnNullWhenNoOpinion() {
			assertNull(QueryUtils.resolveLocale(null), "A missing filter has no opinion");
			assertNull(QueryUtils.resolveLocale(filterBy(attributeEquals("code", "a"))),
				"A filter without a locale constraint has no opinion");
		}

		/** Identical opinions of one level coalesce into a single locale. */
		@Test
		@DisplayName("Coalesces own locale opinions")
		void shouldCoalesceWhenOpinionsEqual() {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(entityLocaleEquals(Locale.ENGLISH))),
				"A single opinion is the level's locale");
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH))),
				"A repeated opinion coalesces");
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(entityLocaleEquals(Locale.ENGLISH),
				referenceHaving("brand", entityLocaleEquals(Locale.ENGLISH)))),
				"An equal opinion in a reference container coalesces with the owner's");
		}

		/** Each container whose children filter a different entity hides their opinion from the enclosing level. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#differentEntityContainers")
		@DisplayName("Stops at a container whose children filter a different entity")
		void shouldStopWhenChildrenFilterDifferentEntity(
			@Nonnull String container, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				entityLocaleEquals(Locale.ENGLISH), level.apply(entityLocaleEquals(Locale.FRENCH)))),
				"The nested French opinion must not conflict with the enclosing English one");
			assertNull(QueryUtils.resolveLocale(filterBy(level.apply(entityLocaleEquals(Locale.FRENCH)))),
				"A nested-only opinion must not become the enclosing level's locale");
		}

		/** Reference and facet containers keep their direct children at the owner's level. */
		@Test
		@DisplayName("Descends into reference and facet containers")
		void shouldDescendWhenContainerDoesNotStartLevel() {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				referenceHaving("brand", entityLocaleEquals(Locale.ENGLISH)))));
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				facetHaving("brand", entityLocaleEquals(Locale.ENGLISH)))));
		}

		/** An opinion under a reference or facet container belongs to the owner and so conflicts with it. */
		@ParameterizedTest(name = "facet: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Rejects a reference or facet opinion differing from its owner")
		void shouldRejectWhenReferenceOrFacetOpinionConflictsWithOwner(boolean facet) {
			final FilterBy filter = filterBy(entityLocaleEquals(Locale.ENGLISH), facet ?
				facetHaving("brand", entityLocaleEquals(Locale.FRENCH)) :
				referenceHaving("brand", entityLocaleEquals(Locale.FRENCH)));
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> QueryUtils.resolveLocale(filter));
			assertEquals("Conflicting locales `en` and `fr` at one entity level in constraint `" + filter + "`.",
				error.getMessage());
		}

		/** A conflicting disjunction still refers to one entity level. */
		@Test
		@DisplayName("Reports conflicting locales and the filter")
		void shouldRejectWhenOpinionsConflict() {
			final FilterBy filter = filterBy(or(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.FRENCH)));
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> QueryUtils.resolveLocale(filter));
			assertEquals("Conflicting locales `en` and `fr` at one entity level in constraint `" + filter + "`.",
				error.getMessage());
		}
	}

	/**
	 * Covers the chain resolving the locale of a referenced entity or group body: the body level's own opinion, then
	 * the reference level's, then the enclosing level's locale.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Reference body locale resolver")
	class ReferenceBodyLocaleTest {

		/** Without a reference filter the body inherits the enclosing level's locale. */
		@ParameterizedTest(name = "group: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Inherits the enclosing locale when the reference has no filter")
		void shouldInheritEnclosingLocaleWhenReferenceFilterMissing(boolean group) {
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(null, group, Locale.GERMAN));
			assertNull(QueryUtils.resolveReferenceBodyLocale(null, group, null),
				"Without any level naming a locale the body has none");
		}

		/** Each level decides only when the levels inside it have no opinion. */
		@ParameterizedTest(name = "group: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Prefers the body opinion, then the reference opinion, then the enclosing locale")
		void shouldPreferInnermostOpinionWhenLevelsDiffer(boolean group) {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveReferenceBodyLocale(
				filterBy(entityLocaleEquals(Locale.FRENCH), body(group, entityLocaleEquals(Locale.ENGLISH))),
				group, Locale.GERMAN
			), "The body's own opinion wins over the reference level and the enclosing level");
			assertEquals(Locale.FRENCH, QueryUtils.resolveReferenceBodyLocale(
				filterBy(entityLocaleEquals(Locale.FRENCH), body(group, attributeEquals("code", "a"))),
				group, Locale.GERMAN
			), "The reference level's opinion wins over the enclosing level");
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(
				filterBy(attributeEquals("order", 1), body(group, attributeEquals("code", "a"))),
				group, Locale.GERMAN
			), "A body without any opinion inherits the enclosing level's locale");
		}

		/** The referenced entity and its group are separate levels, so neither inherits the other's opinion. */
		@Test
		@DisplayName("Keeps the entity and group levels apart")
		void shouldNotShareOpinionBetweenEntityAndGroup() {
			final FilterBy both = filterBy(
				entityHaving(entityLocaleEquals(Locale.ENGLISH)), groupHaving(entityLocaleEquals(Locale.FRENCH))
			);
			final FilterBy entityOnly = filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH)));

			assertEquals(Locale.ENGLISH, QueryUtils.resolveReferenceBodyLocale(both, false, Locale.GERMAN));
			assertEquals(Locale.FRENCH, QueryUtils.resolveReferenceBodyLocale(both, true, Locale.GERMAN));
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(entityOnly, true, Locale.GERMAN),
				"The group must not inherit the referenced entity's opinion");
		}

		/** A body container below a nested reference describes the target of that other reference. */
		@Test
		@DisplayName("Ignores a body container nested in another reference")
		void shouldIgnoreBodyWhenNestedInAnotherReference() {
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(
				filterBy(referenceHaving("brand", entityHaving(entityLocaleEquals(Locale.ENGLISH)))),
				false, Locale.GERMAN
			));
		}

		/** Several body containers of one reference form a single level whose opinions must agree. */
		@Test
		@DisplayName("Treats several body containers as one level")
		void shouldCoalesceOrRejectWhenSeveralBodyContainersHaveOpinions() {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveReferenceBodyLocale(
				filterBy(
					entityHaving(entityLocaleEquals(Locale.ENGLISH)), entityHaving(entityLocaleEquals(Locale.ENGLISH))
				),
				false, Locale.GERMAN
			), "Equal opinions of one body level coalesce");
			assertThrows(EvitaInvalidUsageException.class, () -> QueryUtils.resolveReferenceBodyLocale(
				filterBy(
					entityHaving(entityLocaleEquals(Locale.ENGLISH)), entityHaving(entityLocaleEquals(Locale.FRENCH))
				),
				false, Locale.GERMAN
			), "Different opinions of one body level conflict");
		}

		/** The reference level is always resolved, so its conflict surfaces even when the body decides. */
		@Test
		@DisplayName("Rejects a reference level conflict even when the body names a locale")
		void shouldRejectWhenReferenceLevelConflictsAndBodyDecides() {
			assertThrows(EvitaInvalidUsageException.class, () -> QueryUtils.resolveReferenceBodyLocale(
				filterBy(
					or(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.FRENCH)),
					entityHaving(entityLocaleEquals(Locale.GERMAN))
				),
				false, null
			));
		}

		/**
		 * Wraps the children into the container of the body level under test.
		 *
		 * @param group    true for the group body, false for the referenced entity body
		 * @param children the filter of the body level
		 * @return `groupHaving` or `entityHaving` holding the children
		 */
		@Nonnull
		private FilterConstraint body(boolean group, @Nonnull FilterConstraint... children) {
			return group ? groupHaving(children) : entityHaving(children);
		}
	}

	@Nested
	@DisplayName("Find constraint")
	class FindConstraintTest {

		@Test
		@DisplayName("should find constraint by type")
		void shouldFindConstraintByType() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final AttributeEquals found = QueryUtils.findConstraint(filterBy, AttributeEquals.class);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}

		@Test
		@DisplayName("should find constraint by predicate")
		void shouldFindConstraintByPredicate() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"), attributeEquals("name", "xyz"))
			);
			final Constraint<?> found = QueryUtils.findConstraint(
				filterBy,
				c -> c instanceof AttributeEquals && "name".equals(c.getArguments()[0])
			);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null when constraint not found")
		void shouldReturnNullWhenNotFound() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final Page found = QueryUtils.findConstraint(filterBy, Page.class);
			assertNull(found);
		}

		@Test
		@DisplayName("should respect stop container type")
		void shouldRespectStopContainerType() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					or(
						attributeEquals("name", "xyz")
					)
				)
			);
			// Looking for AttributeEquals but stopping at Or - should find "code" not "name"
			final AttributeEquals found = QueryUtils.findConstraint(
				filterBy, AttributeEquals.class, Or.class
			);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}
	}

	@Nested
	@DisplayName("Find constraints (list)")
	class FindConstraintsTest {

		@Test
		@DisplayName("should find all constraints of type")
		void shouldFindAllOfType() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					attributeEquals("name", "xyz")
				)
			);
			final List<AttributeEquals> found = QueryUtils.findConstraints(filterBy, AttributeEquals.class);
			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("should return empty list when none found")
		void shouldReturnEmptyListWhenNoneFound() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final List<Page> found = QueryUtils.findConstraints(filterBy, Page.class);
			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("should find constraints with stop container")
		void shouldFindConstraintsWithStopContainer() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					or(
						attributeEquals("name", "xyz")
					)
				)
			);
			// Stop at Or - should find only "code"
			final List<AttributeEquals> found = QueryUtils.findConstraints(
				filterBy, AttributeEquals.class, Or.class
			);
			assertEquals(1, found.size());
			assertEquals("code", found.getFirst().getArguments()[0]);
		}

		@Test
		@DisplayName("should find constraints by predicate")
		void shouldFindConstraintsByPredicate() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					attributeEquals("name", "xyz")
				)
			);
			final List<Constraint<?>> found = QueryUtils.findConstraints(
				filterBy,
				AttributeEquals.class::isInstance
			);
			assertEquals(2, found.size());
		}
	}

	@Nested
	@DisplayName("Find filter")
	class FindFilterTest {

		@Test
		@DisplayName("should find filter constraint from query")
		void shouldFindFilterFromQuery() {
			final Query q = query(
				collection("product"),
				filterBy(attributeEquals("code", "abc"))
			);
			final AttributeEquals found = QueryUtils.findFilter(q, AttributeEquals.class);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}

		@Test
		@DisplayName("should return null for query without filter")
		void shouldReturnNullForQueryWithoutFilter() {
			final Query q = query(collection("product"));
			final AttributeEquals found = QueryUtils.findFilter(q, AttributeEquals.class);
			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Find order")
	class FindOrderTest {

		@Test
		@DisplayName("should find order constraint from query")
		void shouldFindOrderFromQuery() {
			final Query q = query(
				collection("product"),
				orderBy(attributeNatural("name"))
			);
			final AttributeNatural found = QueryUtils.findOrder(q, AttributeNatural.class);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null for query without order")
		void shouldReturnNullForQueryWithoutOrder() {
			final Query q = query(collection("product"));
			final AttributeNatural found = QueryUtils.findOrder(q, AttributeNatural.class);
			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Find require")
	class FindRequireTest {

		@Test
		@DisplayName("should find require constraint from query")
		void shouldFindRequireFromQuery() {
			final Query q = query(
				collection("product"),
				require(page(1, 5))
			);
			final Page found = QueryUtils.findRequire(q, Page.class);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null for query without require")
		void shouldReturnNullForQueryWithoutRequire() {
			final Query q = query(collection("product"));
			final Page found = QueryUtils.findRequire(q, Page.class);
			assertNull(found);
		}

		@Test
		@DisplayName("should find all requires of type")
		void shouldFindAllRequiresOfType() {
			final Query q = query(
				collection("product"),
				require(page(1, 5))
			);
			final List<Page> found = QueryUtils.findRequires(q, Page.class);
			assertEquals(1, found.size());
		}

		@Test
		@DisplayName("should return empty list for missing require")
		void shouldReturnEmptyListForMissingRequire() {
			final Query q = query(collection("product"));
			final List<Page> found = QueryUtils.findRequires(q, Page.class);
			assertTrue(found.isEmpty());
		}
	}

	@Nested
	@DisplayName("Value differs")
	class ValueDiffersTest {

		@Test
		@DisplayName("should return false for null vs null")
		void shouldReturnFalseForNullVsNull() {
			assertFalse(QueryUtils.valueDiffers(null, null));
		}

		@Test
		@DisplayName("should return true for null vs value")
		void shouldReturnTrueForNullVsValue() {
			assertTrue(QueryUtils.valueDiffers(null, "abc"));
		}

		@Test
		@DisplayName("should return true for value vs null")
		void shouldReturnTrueForValueVsNull() {
			assertTrue(QueryUtils.valueDiffers("abc", null));
		}

		@Test
		@DisplayName("should use compareTo for BigDecimal")
		void shouldUseCompareToForBigDecimal() {
			// BigDecimal: 10.0 equals 10.00 via compareTo, but not via equals
			assertFalse(QueryUtils.valueDiffers(new BigDecimal("10.0"), new BigDecimal("10.00")));
		}

		@Test
		@DisplayName("should return true for different BigDecimals")
		void shouldReturnTrueForDifferentBigDecimals() {
			assertTrue(QueryUtils.valueDiffers(new BigDecimal("10.0"), new BigDecimal("20.0")));
		}

		@Test
		@DisplayName("should use equals for non-comparable types")
		void shouldUseEqualsForNonComparableTypes() {
			assertFalse(QueryUtils.valueDiffers("abc", "abc"));
			assertTrue(QueryUtils.valueDiffers("abc", "xyz"));
		}

		@Test
		@DisplayName("should compare arrays element by element")
		void shouldCompareArraysElementByElement() {
			assertFalse(QueryUtils.valueDiffers(
				new String[]{"a", "b"},
				new String[]{"a", "b"}
			));
		}

		@Test
		@DisplayName("should detect different array lengths")
		void shouldDetectDifferentArrayLengths() {
			assertTrue(QueryUtils.valueDiffers(
				new String[]{"a"},
				new String[]{"a", "b"}
			));
		}

		@Test
		@DisplayName("should detect different array elements")
		void shouldDetectDifferentArrayElements() {
			assertTrue(QueryUtils.valueDiffers(
				new String[]{"a", "b"},
				new String[]{"a", "c"}
			));
		}

		@Test
		@DisplayName("should return true for array vs non-array")
		void shouldReturnTrueForArrayVsNonArray() {
			assertTrue(QueryUtils.valueDiffers(new String[]{"a"}, "a"));
		}
	}

	@Nested
	@DisplayName("Assert no nested scope containers")
	class AssertNoNestedScopeContainersTest {

		@Test
		@DisplayName("should reject filter inScope of the other scope nested directly")
		void shouldRejectFilterInScopeOfOtherScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals("code", "a"))))
					)
				)
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
			assertTrue(exception.getMessage().contains("`LIVE`"), exception.getMessage());
			assertTrue(exception.getMessage().contains("`ARCHIVED`"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject filter inScope of the same scope nested directly")
		void shouldRejectFilterInScopeOfSameScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(inScope(Scope.ARCHIVED, inScope(Scope.ARCHIVED, attributeEquals("code", "a"))))
					)
				)
			);

			assertTrue(exception.getMessage().contains("redundant"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject filter inScope nested deeper in logical containers")
		void shouldRejectFilterInScopeNestedDeeperInLogicalContainers() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								entityPrimaryKeyInSet(1),
								and(not(inScope(Scope.ARCHIVED, attributeEquals("code", "a"))))
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should reject filter inScope nested in the body of referenceHaving")
		void shouldRejectFilterInScopeNestedInReferenceHavingBody() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								referenceHaving("brand", inScope(Scope.ARCHIVED, attributeEquals("order", 1)))
							)
						)
					)
				)
			);
		}

		/** A facet's direct body remains in the owner's scope context just like a reference filter. */
		@Test
		@DisplayName("rejects nested inScope in the direct body of facetHaving")
		void shouldRejectNestedScopeWhenFacetBodyFiltersOwnerReferences() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(collection("product"), filterBy(inScope(Scope.LIVE,
						facetHaving("brand", inScope(Scope.ARCHIVED, attributeEquals("order", 1))))))),
				"Facet reference filtering must keep the owner's scope context"
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject nesting introduced by copying a container with new children")
		void shouldRejectNestingIntroducedByCopyingContainerWithNewChildren() {
			final FilterInScope valid = inScope(Scope.LIVE, attributeEquals("code", "a"));
			final FilterConstraint copy = valid.getCopyWithNewChildren(
				new FilterConstraint[]{inScope(Scope.ARCHIVED, attributeEquals("code", "a"))},
				new Constraint<?>[0]
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(query(collection("product"), filterBy(copy)))
			);
		}

		@Test
		@DisplayName("should reject filter nesting within the referenced entity's own context")
		void shouldRejectFilterNestingWithinReferencedEntityContext() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							referenceHaving(
								"brand",
								entityHaving(
									inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should reject filter nesting inside a requirement")
		void shouldRejectFilterNestingInsideRequirement() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						require(
							entityFetch(
								referenceContent(
									"brand",
									filterBy(
										inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals("order", 1)))
									)
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept filter inScope restricting another entity inside an inScope")
		void shouldAcceptFilterInScopeRestrictingAnotherEntityInsideInScope() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								referenceHaving(
									"brand", entityHaving(inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
								),
								hierarchyWithin("categories", inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
							)
						)
					)
				)
			);
		}

		/** The entity body of a facet filter restricts the facet entity, which has scopes of its own. */
		@Test
		@DisplayName("should accept inScope in the entity body of facetHaving inside an inScope")
		void shouldAcceptInScopeInsideEntityHavingBodyOfFacetHaving() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								facetHaving(
									"brand", entityHaving(inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept inScope containers side by side")
		void shouldAcceptInScopeContainersSideBySide() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(Scope.LIVE, attributeEquals("code", "a")),
							inScope(Scope.ARCHIVED, attributeEquals("code", "b"))
						),
						orderBy(
							inScope(Scope.LIVE, attributeNatural("code", OrderDirection.ASC)),
							inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.DESC))
						),
						require(
							inScope(Scope.LIVE, facetSummary()),
							inScope(Scope.ARCHIVED, attributeHistogram(10, "code"))
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept a query without any section")
		void shouldAcceptQueryWithoutAnySection() {
			assertDoesNotThrow(() -> QueryUtils.assertNoNestedScopeContainers(query(collection("product"))));
		}

		@Test
		@DisplayName("should reject order inScope of the other scope nested directly")
		void shouldRejectOrderInScopeOfOtherScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.ASC)))
						)
					)
				)
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject order inScope of the same scope nested directly")
		void shouldRejectOrderInScopeOfSameScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(inScope(Scope.LIVE, inScope(Scope.LIVE, attributeNatural("code", OrderDirection.ASC))))
					)
				)
			);

			assertTrue(exception.getMessage().contains("redundant"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject order inScope nested in the body of referenceProperty")
		void shouldRejectOrderInScopeNestedInReferencePropertyBody() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(
								Scope.LIVE,
								referenceProperty(
									"brand", inScope(Scope.ARCHIVED, attributeNatural("order", OrderDirection.ASC))
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept order inScope ordering by the referenced entity's own properties")
		void shouldAcceptOrderInScopeOrderingByReferencedEntityProperties() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(
								Scope.LIVE,
								referenceProperty(
									"brand",
									pickFirstByEntityProperty(
										inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.ASC))
									),
									entityProperty(
										inScope(Scope.ARCHIVED, attributeNatural("name", OrderDirection.DESC))
									)
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept order inScope ordering by the referenced group entity's own properties")
		void shouldAcceptOrderInScopeOrderingByReferencedGroupProperties() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(
								Scope.LIVE,
								referenceProperty(
									"brand",
									entityGroupProperty(
										inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.ASC))
									)
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should reject require inScope of the other scope nested directly")
		void shouldRejectRequireInScopeOfOtherScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						require(inScope(Scope.LIVE, inScope(Scope.ARCHIVED, facetSummary())))
					)
				)
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject require inScope of the same scope nested directly")
		void shouldRejectRequireInScopeOfSameScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						require(inScope(Scope.ARCHIVED, inScope(Scope.ARCHIVED, facetSummary())))
					)
				)
			);

			assertTrue(exception.getMessage().contains("redundant"), exception.getMessage());
		}
	}
}
