/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.query.filter.HierarchySpecificationFilterConstraint;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.require.*;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the null-argument rules through Java factories and safe EvitaQL with both parameter binding styles.
 */
@DisplayName("Java and EvitaQL null argument parity")
@Tag(CONTRACT)
@Tag(QUERY)
class NullArgumentsParityTest {
	private static final DefaultQueryParser PARSER = DefaultQueryParser.getInstance();

	/**
	 * Compares undefined scalar values, mandatory names and default enum settings.
	 */
	@Nested
	@DisplayName("Scalar arguments")
	class Scalars {
		/**
		 * Covers undefined values, mandatory names and optional or mandatory enums.
		 *
		 * @return one test per scalar example and binding style
		 */
		@TestFactory
		@DisplayName("Scalar nulls have equal Java and EvitaQL outcomes")
		Stream<DynamicTest> shouldMatchFactoriesWhenScalarParametersAreNull() {
			return Stream.of(
				row("attributeEquals(?, ?)", FilterConstraint.class, () -> attributeEquals("code", null), "code", null),
				row("entityLocaleEquals(?)", FilterConstraint.class, () -> entityLocaleEquals(null), (Object) null),
				row("attributeBetween(?, ?, ?)", FilterConstraint.class,
					() -> attributeBetween("price", null, 100), "price", null, 100),
				row("attributeEquals(?, ?)", FilterConstraint.class, () -> attributeEquals(null, "A"), null, "A"),
				row("attributeNatural(?)", OrderConstraint.class, () -> attributeNatural(null), (Object) null),
				row("referenceHaving(?, entityPrimaryKeyInSet(?))", FilterConstraint.class,
					() -> referenceHaving(null, entityPrimaryKeyInSet(1)), null, List.of(1)),
				row("referenceContent(?, filterBy(entityPrimaryKeyInSet(?)))", RequireConstraint.class,
					() -> referenceContent((String) null, filterBy(entityPrimaryKeyInSet(1))), null, List.of(1)),
				row("hierarchyWithinRoot(?)", FilterConstraint.class, () -> hierarchyWithinRoot(null), (Object) null),
				row("histogramHaving(?, ?, ?, ?)", FilterConstraint.class,
					() -> histogramHaving("ref", null, 1, 5), "ref", null, 1, 5),
				row("collection(?)", HeadConstraint.class, () -> collection(null), (Object) null),
				row("facetSummary(?)", RequireConstraint.class,
					() -> facetSummary((FacetStatisticsDepth) null), (Object) null),
				row("attributeNatural(?, ?)", OrderConstraint.class,
					() -> attributeNatural("code", null), "code", null),
				row("statistics(?, ?)", RequireConstraint.class,
					() -> statistics((StatisticsBase) null, StatisticsType.CHILDREN_COUNT), null,
						StatisticsType.CHILDREN_COUNT),
				row("inScope(?, entityPrimaryKeyInSet(?))", FilterConstraint.class,
					() -> inScope(null, entityPrimaryKeyInSet(1)), null, List.of(1)),
				row("inScope(?, attributeNatural(?))", OrderConstraint.class,
					() -> inScope(null, attributeNatural("code")), null, "code"),
				row("inScope(?, entityFetch())", RequireConstraint.class,
					() -> inScope(null, entityFetch()), (Object) null),
				row("attributeIs(?, ?)", FilterConstraint.class,
					() -> attributeIs("code", null), "code", null),
				row("priceContent(?)", RequireConstraint.class,
					() -> priceContent((PriceContentMode) null), (Object) null),
				row("priceDiscount(?, ?)", OrderConstraint.class,
					() -> priceDiscount(null, new String[]{"basic"}), null, List.of("basic")),
				row("attributeHistogram(?, ?, ?)", RequireConstraint.class,
					() -> attributeHistogram(20, null, new String[]{"price"}), 20, null, List.of("price")),
				row("attributeHistogram(?, ?, ?)", RequireConstraint.class,
					() -> attributeHistogram(20, null, new String[]{"price"}), 20, null, "price"),
				row("histogramStatistics(?, ?, ?)", RequireConstraint.class,
					() -> histogramStatistics(20, (HistogramBehavior) null, "priceIndex"), 20, null, "priceIndex"),
				row("page(?, ?)", RequireConstraint.class, () -> page(null, null), null, null),
				row("strip(?, ?)", RequireConstraint.class, () -> strip(null, null), null, null),
				row("priceBetween(?, ?)", FilterConstraint.class, () -> priceBetween(null, java.math.BigDecimal.TEN),
					null, 10),
				row("entityPrimaryKeyGreaterThan(?)", FilterConstraint.class,
					() -> entityPrimaryKeyGreaterThan(null), (Object) null),
				row("attributeInRange(?, ?)", FilterConstraint.class,
					() -> attributeInRange("range", (Number) null), "range", null)
			).flatMap(example -> Stream.of(false, true).map(named -> DynamicTest.dynamicTest(
				example.expression() + describe(example.arguments()) + (named ? " named" : " positional"),
				() -> assertParity(example, named)
			)));
		}
	}

	/**
	 * Regressions for omitted children, blank settings and bound array types.
	 */
	@Nested
	@DisplayName("Omitted children, blank names and bound array types")
	class OmittedChildrenAndBlankNames {
		/**
		 * Checks child omission and blank inputs with both binding styles.
		 *
		 * @return paired Java and EvitaQL examples
		 */
		@TestFactory
		@DisplayName("Omitted children and blank inputs follow the factory rules")
		Stream<DynamicTest> shouldMatchFactoriesWhenChildrenAreOmittedOrNamesAreBlank() {
			return Stream.of(
				row("segment(orderBy(entityPrimaryKeyExact(?)))", OrderConstraint.class,
					() -> segment(orderBy(entityPrimaryKeyExact(new Integer[0]))), List.of()),
				row("segments(segment(orderBy(entityPrimaryKeyExact(?))))", OrderConstraint.class,
					() -> segments(segment(orderBy(entityPrimaryKeyExact(new Integer[0])))), List.of()),
				row("fromRoot(@output, stopAt(distance(?)), entityFetch())", RequireConstraint.class,
					() -> fromRoot("tree", entityFetch(), stopAt(distance(null))), (Object) null),
				row("facetSummary(filterBy(attributeEquals(?, ?)), filterGroupBy(entityPrimaryKeyInSet(?)))",
					RequireConstraint.class, () -> facetSummary(filterBy(attributeEquals("code", null)),
						filterGroupBy(entityPrimaryKeyInSet(1))), "code", null, List.of(1)),
				row("referenceSummary(filterBy(attributeEquals(?, ?)), filterGroupBy(entityPrimaryKeyInSet(?)))",
					RequireConstraint.class, () -> referenceSummary(filterBy(attributeEquals("code", null)),
						filterGroupBy(entityPrimaryKeyInSet(1))), "code", null, List.of(1)),
				row("userFilter(attributeEquals(?, ?), entityPrimaryKeyInSet(?))", FilterConstraint.class,
					() -> userFilter(attributeEquals("code", null), entityPrimaryKeyInSet(1)),
					"code", null, List.of(1)),
				row("hierarchyContent(stopAt(distance(?)))", RequireConstraint.class,
					() -> hierarchyContent(stopAt(distance(null))), (Object) null),
				row("hierarchyContent(?, stopAt(distance(?)))", RequireConstraint.class,
					() -> hierarchyContent(HierarchyParentsBehaviour.COMPLETE),
					HierarchyParentsBehaviour.COMPLETE, null),
				row("hierarchyContent(stopAt(level(?)))", RequireConstraint.class,
					() -> hierarchyContent(stopAt(level(null))), (Object) null),
				row("page(?, ?, spacing(gap(?, ?)))", RequireConstraint.class,
					() -> page(1, 20, spacing(gap(2, (String) null))), 1, 20, 2, null),
				row("hierarchyWithinRoot(?)", FilterConstraint.class,
					() -> hierarchyWithinRoot("category", (HierarchySpecificationFilterConstraint[]) null), "category"),
				row("hierarchyOfReference(?, fromRoot(@output))", RequireConstraint.class,
					() -> hierarchyOfReference((String) null, fromRoot("tree")), (Object) null),
				row("label(?, ?)", HeadConstraint.class, () -> label(" ", 1), " ", 1),
				row("attributeSetExact(?, ?)", OrderConstraint.class,
					() -> attributeSetExact(" ", "A"), " ", List.of("A")),
				row("attributeSetInFilter(?)", OrderConstraint.class, () -> attributeSetInFilter(" "), " "),
				row("accompanyingPriceContent(?, ?)", RequireConstraint.class,
					() -> accompanyingPriceContent(" ", "basic"), " ", List.of("basic")),
				row("priceInPriceLists(?)", FilterConstraint.class,
					() -> priceInPriceLists(" ", null), Arrays.asList(" ", null)),
				row("priceInPriceLists(?)", FilterConstraint.class,
					() -> priceInPriceLists(" ", "basic"), List.of(" ", "basic")),
				row("attributeContent(?)", RequireConstraint.class,
					() -> attributeContent("code", " "), List.of("code", " ")),
				row("attributeContent(?)", RequireConstraint.class,
					() -> attributeContent("code"), (Object) new Object[]{"code"}),
				row("attributeContent(?)", RequireConstraint.class,
					() -> attributeContent("code"), (Object) new Serializable[]{"code"})
			).flatMap(example -> Stream.of(false, true).map(named -> DynamicTest.dynamicTest(
				example.expression() + describe(example.arguments()) + " " + named, () -> assertParity(example, named)
			)));
		}

		/**
		 * Ensures omitted invalid children and wrong array components still produce syntax errors.
		 *
		 * @return syntax validation cases
		 */
		@TestFactory
		@DisplayName("Omission preserves child and array type validation")
		Stream<DynamicTest> shouldRejectInvalidTypesWhenTheirValuesAreEmpty() {
			return Stream.of(
				row("page(?, ?, defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> null, 1, 20, List.of()),
				row("referenceContentAll(defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> null, List.of()),
				row("referenceContentAllWithAttributes(defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> null, List.of()),
				row("referenceSummary(defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> null, List.of()),
				row("fromRoot(@output, defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> null, List.of()),
				row("page(?, ?, inScope(?, defaultAccompanyingPriceLists(?)))", RequireConstraint.class,
					() -> null, 1, 20, Scope.LIVE, List.of()),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) new Object[]{new String[]{"code"}}),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) new Serializable[]{new String[]{"code"}}),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) new Object[]{new java.util.ArrayList<>(List.of("code"))}),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) new Object[]{null, new String[]{"code"}}),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) List.of(new String[]{"code"}, new String[]{"name"})),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) List.of(List.of("code"))),
				row("attributeContent(?)", RequireConstraint.class, () -> null, (Object) new Integer[0]),
				row("attributeContent(?)", RequireConstraint.class, () -> null, (Object) new Thread[0]),
				row("entityPrimaryKeyInSet(?)", FilterConstraint.class, () -> null, (Object) new String[0]),
				row("dataInLocales(?)", RequireConstraint.class, () -> null, (Object) new Integer[0]),
				row("attributeContent(?)", RequireConstraint.class, () -> null, (Object) new Object[]{"code", 1}),
				row("attributeContent(?)", RequireConstraint.class, () -> null,
					(Object) new Serializable[]{"code", 1}),
				row("attributeContent(?)", RequireConstraint.class, () -> null, List.of("code", 1)),
				row("hierarchyOfReference(?, fromRoot(@output))", RequireConstraint.class,
					() -> null, List.of("category"))
			).flatMap(example -> Stream.of(false, true).map(named -> DynamicTest.dynamicTest(
				example.expression() + describe(example.arguments()) + " " + named, () -> {
					final EvitaSyntaxException exception = assertThrows(EvitaSyntaxException.class,
						() -> parse(example.expression(), example.category(), example.arguments(), named));
					if (example.expression().startsWith("page(")) {
						assertTrue(exception.getMessage().contains("spacing"));
					}
				}
			)));
		}
	}

	/**
	 * Compares list normalization and ambiguous lone bindings across both parameter styles.
	 */
	@Nested
	@DisplayName("Variadic arguments")
	class Lists {
		/**
		 * Checks every list row with arrays, iterables and separate parameters, including null in the first position.
		 *
		 * @return list regression cases
		 */
		@TestFactory
		@DisplayName("Bound arrays, lists and separate items follow the factory rules")
		Stream<DynamicTest> shouldMatchFactoriesWhenListsContainNulls() {
			return listRows().flatMap(list -> Stream.of(false, true).flatMap(named ->
				Stream.concat(Stream.of("empty", "value then null", "null then value", "only nulls", "missing"),
					list.value() instanceof String && (list.expression().startsWith("price")
						|| list.expression().startsWith("defaultAccompanying")
						|| list.expression().startsWith("accompanyingPrice")) ?
						Stream.of("value then blank", "blank then value", "only blanks") : Stream.empty())
					.flatMap(shape -> Stream.of("array", "iterable", "separate")
						.map(binding ->
						DynamicTest.dynamicTest(list.expression() + " " + shape + " " + binding + " " + named, () -> {
							final Object[] items = switch (shape) {
								case "empty" -> new Object[0];
								case "value then null" -> new Object[]{list.value(), null};
								case "null then value" -> new Object[]{null, list.value()};
								case "only nulls" -> new Object[]{null, null};
								case "value then blank" -> new Object[]{list.value(), " "};
								case "blank then value" -> new Object[]{" ", list.value()};
								case "only blanks" -> new Object[]{"", " "};
								case "missing" -> null;
								default -> throw new IllegalStateException("Unknown list shape");
							};
							final Object array = items == null ? null : typedArray(list.value().getClass(), items);
							final List<Object> arguments = new ArrayList<>(Arrays.asList(list.prefix()));
							String expression = list.expression();
							if (binding.equals("separate") && items != null && items.length > 1) {
								expression = expression.substring(0, expression.lastIndexOf('?')) +
									"?, ?" + expression.substring(expression.lastIndexOf('?') + 1);
								arguments.addAll(Arrays.asList(items));
							} else {
								arguments.add(binding.equals("iterable") && items != null ? Arrays.asList(items)
									: array);
							}
							if (items == null) {
								final String query = expression;
								final EvitaSyntaxException exception = assertThrows(EvitaSyntaxException.class,
									() -> parse(query, list.category(), arguments.toArray(), named));
								final Matcher owner = Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\([^()]*$")
									.matcher(expression.substring(0, expression.lastIndexOf('?')));
								assertTrue(owner.find());
								assertTrue(exception.getMessage().contains(owner.group(1)));
								assertTrue(exception.getMessage().contains("empty list"));
								assertTrue(exception.getMessage().contains("leave the constraint out"));
							} else {
								assertParity(row(expression, list.category(), () -> list.factory().apply(array),
									arguments.toArray()), named);
							}
						})))));
		}

		/**
		 * Ensures omission preserves sibling constraints during query normalization.
		 */
		@Test
		@DisplayName("Omitted children leave the other query constraints intact")
		void shouldPreserveSiblingsWhenAnUndefinedValueOmitsAChild() {
			assertEquals(
				Query.query(collection("Product"), filterBy(entityPrimaryKeyInSet(1))).normalizeQuery(),
				PARSER.parseQuery("query(collection(?), filterBy(attributeEquals(?, ?), entityPrimaryKeyInSet(?)))",
					"Product", "code", null, List.of(1))
			);
		}
	}

	/**
	 * Checks all container families with nullable children through the parser.
	 */
	@Nested
	@DisplayName("Empty container parity")
	class Containers {
		/**
		 * Compares empty containers and applicable defaults without losing siblings during normalization.
		 *
		 * @return one case per container form and binding style
		 */
		@TestFactory
		@DisplayName("Omitted children leave no non-applicable container")
		Stream<DynamicTest> shouldPreserveSiblingsWhenAllContainerChildrenAreOmitted() {
			return Stream.of(
				row("head(label(?, ?))", HeadConstraint.class,
					() -> head(label("code", null)), "code", null),
				row("includingChildrenHaving(attributeEquals(?, ?))", FilterConstraint.class,
					() -> includingChildrenHaving(attributeEquals("code", null)), "code", null),
				row("includingChildrenExcept(attributeEquals(?, ?))", FilterConstraint.class,
					() -> includingChildrenExcept(attributeEquals("code", null)), "code", null),
				row("includingChildren()", FilterConstraint.class, QueryConstraints::includingChildren),
				row("facetHaving(@ref, entityHaving(attributeEquals(?, ?)))", FilterConstraint.class,
					() -> facetHaving("ref", entityHaving(attributeEquals("code", null))), "code", null),
				row("histogramHaving(@ref, ?, ?, groupHaving(attributeEquals(?, ?)))", FilterConstraint.class,
					() -> histogramHaving("ref", 1, 5, groupHaving(attributeEquals("code", null))), 1, 5, "code", null),
				row("referenceSummary(histogramStatistics(?, ?))", RequireConstraint.class,
					() -> referenceSummaryWithHistograms(null, null, null,
						histogramStatistics(20, new String[0])), 20, List.of()),
				row("referenceSummaryOfReference(@ref, histogramStatistics(?, ?))", RequireConstraint.class,
					() -> referenceSummaryOfReferenceWithHistograms("ref", null, null, null,
						histogramStatistics(20, new String[0])), 20, List.of()),
				row("entityFetch()", RequireConstraint.class, QueryConstraints::entityFetch),
				row("entityGroupFetch()", RequireConstraint.class, QueryConstraints::entityGroupFetch),
				row("referenceContentAll()", RequireConstraint.class, QueryConstraints::referenceContentAll),
				row("referenceContentAllWithAttributes()", RequireConstraint.class,
					QueryConstraints::referenceContentAllWithAttributes),
				row("referenceContent(@ref)", RequireConstraint.class, () -> referenceContent("ref")),
				row("referenceContentWithAttributes(@ref)", RequireConstraint.class,
					() -> referenceContentWithAttributes("ref")),
				row("facetSummary()", RequireConstraint.class, () -> facetSummary(new EntityFetchRequire[0])),
				row("facetSummaryOfReference(@ref)", RequireConstraint.class,
					() -> facetSummaryOfReference("ref", new EntityFetchRequire[0])),
				row("referenceSummary()", RequireConstraint.class, () -> referenceSummary(new EntityFetchRequire[0])),
				row("referenceSummaryOfReference(@ref)", RequireConstraint.class,
					() -> referenceSummaryOfReference("ref", new EntityFetchRequire[0])),
				row("facetGroupsConjunction(@ref, filterBy(attributeEquals(?, ?)))", RequireConstraint.class,
					() -> facetGroupsConjunction("ref", filterBy(attributeEquals("code", null))), "code", null),
				row("facetGroupsDisjunction(@ref, filterBy(attributeEquals(?, ?)))", RequireConstraint.class,
					() -> facetGroupsDisjunction("ref", filterBy(attributeEquals("code", null))), "code", null),
				row("facetGroupsExclusivity(@ref, filterBy(attributeEquals(?, ?)))", RequireConstraint.class,
					() -> facetGroupsExclusivity("ref", filterBy(attributeEquals("code", null))), "code", null),
				row("facetGroupsNegation(@ref, filterBy(attributeEquals(?, ?)))", RequireConstraint.class,
					() -> facetGroupsNegation("ref", filterBy(attributeEquals("code", null))), "code", null),
				row("filterBy(attributeEquals(?, ?))", FilterConstraint.class,
					() -> filterBy(attributeEquals("code", null)), "code", null),
				row("filterGroupBy(attributeEquals(?, ?))", FilterConstraint.class,
					() -> filterGroupBy(attributeEquals("code", null)), "code", null),
				row("and(attributeEquals(?, ?))", FilterConstraint.class,
					() -> and(attributeEquals("code", null)), "code", null),
				row("or(attributeEquals(?, ?))", FilterConstraint.class,
					() -> or(attributeEquals("code", null)), "code", null),
				row("not(attributeEquals(?, ?))", FilterConstraint.class,
					() -> not(attributeEquals("code", null)), "code", null),
				row("userFilter(attributeEquals(?, ?))", FilterConstraint.class,
					() -> userFilter(attributeEquals("code", null)), "code", null),
				row("entityHaving(attributeEquals(?, ?))", FilterConstraint.class,
					() -> entityHaving(attributeEquals("code", null)), "code", null),
				row("groupHaving(attributeEquals(?, ?))", FilterConstraint.class,
					() -> groupHaving(attributeEquals("code", null)), "code", null),
				row("having(attributeEquals(?, ?))", FilterConstraint.class,
					() -> having(attributeEquals("code", null)), "code", null),
				row("anyHaving(attributeEquals(?, ?))", FilterConstraint.class,
					() -> anyHaving(attributeEquals("code", null)), "code", null),
				row("excluding(attributeEquals(?, ?))", FilterConstraint.class,
					() -> excluding(attributeEquals("code", null)), "code", null),
				row("referenceHaving(@ref, attributeEquals(?, ?))", FilterConstraint.class,
					() -> referenceHaving("ref", attributeEquals("code", null)), "code", null),
				row("hierarchyWithin(@ref, attributeEquals(?, ?))", FilterConstraint.class,
					() -> hierarchyWithin("ref", attributeEquals("code", null)), "code", null),
				row("hierarchyWithinSelf(attributeEquals(?, ?))", FilterConstraint.class,
					() -> hierarchyWithinSelf(attributeEquals("code", null)), "code", null),
				row("hierarchyWithinRoot(@ref, having(attributeEquals(?, ?)))", FilterConstraint.class,
					() -> hierarchyWithinRoot("ref", having(attributeEquals("code", null))), "code", null),
				row("hierarchyWithinRootSelf(having(attributeEquals(?, ?)))", FilterConstraint.class,
					() -> hierarchyWithinRootSelf(having(attributeEquals("code", null))), "code", null),
				row("orderBy(entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> orderBy(entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("orderGroupBy(entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> orderGroupBy(entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("entityProperty(entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> entityProperty(entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("entityGroupProperty(entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> entityGroupProperty(entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("traverseByEntityProperty(entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> traverseByEntityProperty(entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("pickFirstByEntityProperty(entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> pickFirstByEntityProperty(entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("referenceProperty(@ref, entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> referenceProperty("ref", entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("require(defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> require(defaultAccompanyingPriceLists(new String[0])), List.of()),
				row("inScope(@scope, attributeEquals(?, ?))", FilterConstraint.class,
					() -> inScope(Scope.LIVE, attributeEquals("code", null)), "code", null),
				row("inScope(@scope)", FilterConstraint.class,
					() -> inScope(Scope.LIVE, new FilterConstraint[0])),
				row("inScope(@scope, entityPrimaryKeyExact(?))", OrderConstraint.class,
					() -> inScope(Scope.LIVE, entityPrimaryKeyExact(new Integer[0])), List.of()),
				row("inScope(@scope)", OrderConstraint.class,
					() -> inScope(Scope.LIVE, new OrderConstraint[0])),
				row("inScope(@scope, defaultAccompanyingPriceLists(?))", RequireConstraint.class,
					() -> inScope(Scope.LIVE, defaultAccompanyingPriceLists(new String[0])), List.of()),
				row("inScope(@scope)", RequireConstraint.class,
					() -> inScope(Scope.LIVE, new RequireConstraint[0])),
				row("fromRoot(@output, stopAt(distance(?)))", RequireConstraint.class,
					() -> fromRoot("tree", stopAt(distance(null))), (Object) null),
				row("children(@output, stopAt(distance(?)))", RequireConstraint.class,
					() -> children("tree", stopAt(distance(null))), (Object) null),
				row("siblings(@output, stopAt(distance(?)))", RequireConstraint.class,
					() -> siblings("tree", stopAt(distance(null))), (Object) null),
				row("parents(@output, stopAt(distance(?)))", RequireConstraint.class,
					() -> parents("tree", stopAt(distance(null))), (Object) null),
				row("fromNode(@output, node(filterBy(attributeEquals(?, ?))))", RequireConstraint.class,
					() -> fromNode("tree", node(filterBy(attributeEquals("code", null)))), "code", null),
				row("hierarchyOfSelf(fromNode(@output, node(filterBy(attributeEquals(?, ?)))))",
					RequireConstraint.class, () -> hierarchyOfSelf(fromNode("tree",
						node(filterBy(attributeEquals("code", null))))), "code", null),
				row("hierarchyOfReference(@ref, fromNode(@output, node(filterBy(attributeEquals(?, ?)))))",
					RequireConstraint.class, () -> hierarchyOfReference("ref", fromNode("tree",
						node(filterBy(attributeEquals("code", null))))), "code", null)
			).flatMap(example -> Stream.of(false, true).map(named -> DynamicTest.dynamicTest(
				example.category().getSimpleName() + " " + example.expression() + " " + named, () -> {
					assertParity(example, named);
					final Constraint<?> result = example.expected().get();
					assertTrue(result == null || result.isApplicable());
					if (example.category() == FilterConstraint.class) {
						assertNotNull(Query.query(collection("Product"), filterBy((FilterConstraint) result,
							entityPrimaryKeyInSet(1))).normalizeQuery().getFilterBy());
					} else if (example.category() == OrderConstraint.class) {
						assertNotNull(Query.query(collection("Product"), orderBy((OrderConstraint) result,
							entityPrimaryKeyNatural(OrderDirection.ASC))).normalizeQuery().getOrderBy());
					} else if (example.category() == RequireConstraint.class) {
						assertNotNull(Query.query(collection("Product"), require((RequireConstraint) result,
							page(1, 20))).normalizeQuery().getRequire());
					}
				}
			)));
		}
	}

	/**
	 * Inventories the list positions the rules cover, including both name-list reference content forms.
	 *
	 * @return table rows
	 */
	@Nonnull
	private static Stream<ListRow> listRows() {
		return Stream.of(
			list("attributeInSet(?, ?)", FilterConstraint.class, "A", a -> attributeInSet("code", (String[]) a),
				"code"),
			list("entityPrimaryKeyInSet(?)", FilterConstraint.class, 1, a -> entityPrimaryKeyInSet((Integer[]) a)),
			list("priceInPriceLists(?)", FilterConstraint.class, "basic", a -> priceInPriceLists((String[]) a)),
			list("scope(?)", FilterConstraint.class, Scope.LIVE, a -> scope((Scope[]) a)),
			list("attributeSetExact(?, ?)", OrderConstraint.class, "A", a -> attributeSetExact("code", (String[]) a),
				"code"),
			list("entityPrimaryKeyExact(?)", OrderConstraint.class, 1, a -> entityPrimaryKeyExact((Integer[]) a)),
			list("priceDiscount(?)", OrderConstraint.class, "basic", a -> priceDiscount((String[]) a)),
			list("priceDiscount(@direction, ?)", OrderConstraint.class, "basic",
				a -> priceDiscount(OrderDirection.DESC, (String[]) a)),
			list("defaultAccompanyingPriceLists(?)", RequireConstraint.class, "basic",
				a -> defaultAccompanyingPriceLists((String[]) a)),
			list("accompanyingPriceContent(?, ?)", RequireConstraint.class, "basic",
				a -> accompanyingPriceContent("previous", (String[]) a), "previous"),
			list("priceContent(?, ?)", RequireConstraint.class, "basic",
				a -> priceContent(PriceContentMode.RESPECTING_FILTER, (String[]) a),
					PriceContentMode.RESPECTING_FILTER),
			list("priceContentRespectingFilter(?)", RequireConstraint.class, "basic",
				a -> priceContentRespectingFilter((String[]) a)),
			list("dataInLocales(?)", RequireConstraint.class, Locale.ENGLISH, a -> dataInLocales((Locale[]) a)),
			list("attributeContent(?)", RequireConstraint.class, "code", a -> attributeContent((String[]) a)),
			list("associatedDataContent(?)", RequireConstraint.class, "description",
				a -> associatedDataContent((String[]) a)),
			list("referenceContent(@managed, ?)", RequireConstraint.class, "brand",
				a -> referenceContent((String[]) a)),
			list("referenceContent(@managed, ?, entityFetch())", RequireConstraint.class, "brand",
				a -> referenceContent((String[]) a, entityFetch())),
			list("referenceContentWithAttributes(?, attributeContent(?))", RequireConstraint.class, "code",
				a -> referenceContentWithAttributes("brand", (String[]) a), "brand"),
			list("attributeHistogram(?, @behavior, ?)", RequireConstraint.class, "price",
				a -> attributeHistogram(20, (String[]) a), 20),
			list("histogramStatistics(?, @behavior, ?)", RequireConstraint.class, "priceIndex",
				a -> histogramStatistics(20, (String[]) a), 20),
			list("statistics(@base, ?)", RequireConstraint.class, StatisticsType.CHILDREN_COUNT,
				a -> statistics((StatisticsType[]) a))
		);
	}

	/**
	 * Constructs one list table entry.
	 *
	 * @param expression EvitaQL expression
	 * @param category constraint category
	 * @param value representative list value
	 * @param factory Java factory taking a typed array
	 * @param prefix scalar arguments before the list
	 * @return the table entry
	 */
	@Nonnull
	private static ListRow list(@Nonnull String expression, @Nonnull Class<?> category, @Nonnull Object value,
		@Nonnull Function<Object, Constraint<?>> factory, @Nonnull Object... prefix) {
		return new ListRow(expression, category, value, factory, prefix);
	}

	/**
	 * Constructs a scalar example.
	 *
	 * @param expression EvitaQL expression
	 * @param category constraint category
	 * @param expected matching Java call
	 * @param arguments bound arguments
	 * @return the example
	 */
	@Nonnull
	private static Row row(@Nonnull String expression, @Nonnull Class<?> category,
		@Nonnull Supplier<Constraint<?>> expected, @Nullable Object... arguments) {
		return new Row(expression, category, expected, arguments);
	}

	/**
	 * Compares factory outcomes including omission and the surface-specific client exceptions.
	 *
	 * @param row example under test
	 * @param named whether named parameters should be used
	 */
	private static void assertParity(@Nonnull Row row, boolean named) {
		final Constraint<?> expected;
		try {
			expected = row.expected().get();
		} catch (EvitaInvalidUsageException exception) {
			assertThrows(EvitaSyntaxException.class,
				() -> parse(row.expression(), row.category(), row.arguments(), named));
			return;
		}
		assertEquals(Collections.singletonList(expected),
			parse(row.expression(), row.category(), row.arguments(), named));
	}

	/**
	 * Runs the safe parser with positional or named bindings.
	 *
	 * @param expression constraint expression
	 * @param category constraint category
	 * @param arguments bound arguments, including present nulls
	 * @param named whether to rewrite placeholders to named parameters
	 * @return the parsed constraint list, including an omitted entry when applicable
	 */
	@Nonnull
	private static List<?> parse(@Nonnull String expression, @Nonnull Class<?> category,
		@Nonnull Object[] arguments, boolean named) {
		final Map<String, Object> bindings = createHashMap(arguments.length + 5);
		bindings.put("output", "tree");
		bindings.put("ref", "ref");
		bindings.put("scope", Scope.LIVE);
		bindings.put("base", StatisticsBase.WITHOUT_USER_FILTER);
		bindings.put("direction", OrderDirection.DESC);
		bindings.put("behavior", HistogramBehavior.STANDARD);
		bindings.put("managed", ManagedReferencesBehaviour.ANY);
		String query = expression;
		if (named) {
			for (int i = 0; i < arguments.length; i++) {
				query = query.replaceFirst("\\?", "@p" + i);
				bindings.put("p" + i, arguments[i]);
			}
		}
		if (category == FilterConstraint.class) {
			return named ? PARSER.parseFilterConstraintList(query, bindings) :
				PARSER.parseFilterConstraintList(query, bindings, arguments);
		} else if (category == OrderConstraint.class) {
			return named ? PARSER.parseOrderConstraintList(query, bindings) :
				PARSER.parseOrderConstraintList(query, bindings, arguments);
		} else if (category == RequireConstraint.class) {
			return named ? PARSER.parseRequireConstraintList(query, bindings) :
				PARSER.parseRequireConstraintList(query, bindings, arguments);
		} else if (category == HeadConstraint.class) {
			return named ? PARSER.parseHeadConstraintList(query, bindings) :
				PARSER.parseHeadConstraintList(query, bindings, arguments);
		}
		throw new IllegalStateException("Unknown constraint category " + category);
	}

	/**
	 * Describes bindings for a dynamic test name.
	 *
	 * @param arguments bound arguments
	 * @return readable bindings
	 */
	@Nonnull
	private static String describe(@Nonnull Object[] arguments) {
		final StringJoiner description = new StringJoiner(", ", "[", "]");
		for (final Object argument : arguments) {
			description.add(describeArgument(argument));
		}
		return description.toString();
	}

	/**
	 * Describes one binding, naming the array component type and marking lists, because some cases differ only in
	 * those (`Object[]` versus `Serializable[]`, an array versus a list of the same items).
	 *
	 * @param argument bound argument
	 * @return readable binding
	 */
	@Nonnull
	private static String describeArgument(@Nullable Object argument) {
		if (argument instanceof Object[] array) {
			return array.getClass().getComponentType().getSimpleName() + describe(array);
		} else if (argument instanceof Iterable<?> iterable) {
			final StringJoiner items = new StringJoiner(", ", "List[", "]");
			for (final Object item : iterable) {
				items.add(describeArgument(item));
			}
			return items.toString();
		}
		return String.valueOf(argument);
	}

	/**
	 * Creates a typed array so factory overloads receive the same shape as bound parser inputs.
	 *
	 * @param component array component type
	 * @param items array items
	 * @return typed array
	 */
	@Nonnull
	private static Object typedArray(@Nonnull Class<?> component, @Nonnull Object[] items) {
		final Object result = Array.newInstance(component, items.length);
		for (int i = 0; i < items.length; i++) Array.set(result, i, items[i]);
		return result;
	}

	/**
	 * A scalar example and its Java equivalent.
	 *
	 * @param expression constraint expression
	 * @param category constraint category
	 * @param expected Java factory
	 * @param arguments bindings
	 */
	private record Row(String expression, Class<?> category, Supplier<Constraint<?>> expected, Object[] arguments) {}

	/**
	 * A list position covered by the list rules.
	 *
	 * @param expression constraint expression
	 * @param category constraint category
	 * @param value representative value
	 * @param factory Java factory
	 * @param prefix preceding scalar bindings
	 */
	private record ListRow(String expression, Class<?> category, Object value,
		Function<Object, Constraint<?>> factory, Object[] prefix) {}
}
