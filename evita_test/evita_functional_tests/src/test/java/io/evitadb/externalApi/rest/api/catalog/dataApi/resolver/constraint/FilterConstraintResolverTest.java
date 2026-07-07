/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.FILTER;

/**
 * Tests for {@link FilterConstraintResolver}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(FILTER)
class FilterConstraintResolverTest extends AbstractConstraintResolverTest {

	private FilterConstraintResolver resolver;

	@BeforeEach
	void init() {
		super.init();
		this.resolver = new FilterConstraintResolver(this.catalogSchema);
	}

	@Test
	void shouldResolveValueFilterConstraint() {
		assertEquals(
			attributeEquals("CODE", "123"),
			this.resolver.resolve(
				Entities.PRODUCT,
				"attributeCodeEquals",
				"123"
			)
		);
	}

	@Test
	void shouldNotResolveValueFilterConstraint() {
		assertThrows(UnsupportedDataTypeException.class, () -> this.resolver.resolve(Entities.PRODUCT, "attributeCodeEquals", List.of()));
		assertThrows(UnsupportedDataTypeException.class, () -> this.resolver.resolve(Entities.PRODUCT, "attributeCodeEquals", Map.of()));
	}

	@Test
	void shouldResolveChildFilterConstraint() {
		assertEquals(
			and(
				and(
					attributeEquals("CODE", "123")
				),
				and(
					attributeIs("AGE", AttributeSpecialValue.NULL)
				)
			),
			this.resolver.resolve(
				Entities.PRODUCT,
				"and",
				List.of(
					Map.of("attributeCodeEquals", "123"),
					Map.of("attributeAgeIs", AttributeSpecialValue.NULL)
				)
			)
		);
	}

	@Test
	void shouldNotResolveChildFilterConstraint() {
		assertThrows(EvitaInternalError.class, () -> this.resolver.resolve(Entities.PRODUCT, "and", "abc"));
		assertThrows(EvitaInternalError.class, () -> this.resolver.resolve(Entities.PRODUCT, "and", Map.of()));
	}

	@Test
	void shouldResolveFilterConstraintWithMultipleArguments() {
		assertEquals(
			hierarchyWithin(
				"CATEGORY",
				and(
					entityPrimaryKeyInSet(1)
				),
				directRelation()
			),
			this.resolver.resolve(
				Entities.PRODUCT,
				"hierarchyCategoryWithin",
				map()
					.e("ofParent", map()
						.e("entityPrimaryKeyInSet", List.of(1)))
					.e("with", map()
						.e("directRelation", true))
					.build()
			)
		);

		assertEquals(
			hierarchyWithin(
				"CATEGORY",
				and(
					entityPrimaryKeyInSet(1)
				)
			),
			this.resolver.resolve(
				Entities.PRODUCT,
				"hierarchyCategoryWithin",
				map()
					.e("ofParent", map()
						.e("entityPrimaryKeyInSet", List.of(1)))
					.build()
			)
		);
	}

	@Test
	void shouldResolveFilterConstraintWithArgumentsResultingInRange() {
		assertEquals(
			attributeBetween(
				"AGE",
				1,
				2
			),
			this.resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				List.of(1, 2)
			)
		);

		assertEquals(
			attributeBetween(
				"AGE",
				null,
				2
			),
			this.resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				Arrays.asList(null, 2)
			)
		);

		assertEquals(
			attributeBetween(
				"AGE",
				1,
				null
			),
			this.resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				Arrays.asList(1, null)
			)
		);
	}

	@Test
	void shouldNotResolveFilterConstraintWithArgumentsResultingInRange() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				List.of(1)
			)
		);

		assertThrows(
			EvitaInternalError.class,
			() -> this.resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				map()
					.e("from", 1)
					.e("to", 2)
					.build()
			)
		);
	}

	@Test
	void shouldResolveHistogramHavingWithBoundsOnly() {
		// classifier-only histogramHaving — only `from` / `to` are provided in the wrapper object;
		// the resolver must reconstruct a HistogramHaving with matching referenceName and bounds
		assertEquals(
			histogramHaving("CATEGORY", 10, 20),
			this.resolver.resolve(
				Entities.PRODUCT,
				"referenceCategoryHistogramHaving",
				map()
					.e("from", 10)
					.e("to", 20)
					.build()
			)
		);
	}

	@Test
	void shouldResolveHistogramHavingWithHistogramNameAndBounds() {
		// classifier + histogramName + bounds — the wrapper also carries a `histogramName` field
		// to select one of several histograms hosted by the same reference
		assertEquals(
			histogramHaving("CATEGORY", "basicUnitValue", 50, 120),
			this.resolver.resolve(
				Entities.PRODUCT,
				"referenceCategoryHistogramHaving",
				map()
					.e("histogramName", "basicUnitValue")
					.e("from", 50)
					.e("to", 120)
					.build()
			)
		);
	}

	@Test
	void shouldResolveHistogramHavingFullArityWithGroupSelector() {
		// full-arity histogramHaving with a groupHaving child — the `@Child GroupHaving groupHaving`
		// parameter is single-variant, so the GraphQL/REST schema flattens the field name to the
		// parameter name `groupHaving`, and the inner constraint key is `groupHaving` (the `group`
		// property-type prefix combined with GroupHaving's fullName `having`, mirroring EntityHaving).
		assertEquals(
			histogramHaving(
				"CATEGORY",
				"basicUnitValue",
				50,
				120,
				groupHaving(attributeEquals("NAME", "height"))
			),
			QueryPurifierVisitor.purify(
				this.resolver.resolve(
					Entities.PRODUCT,
					"referenceCategoryHistogramHaving",
					map()
						.e("histogramName", "basicUnitValue")
						.e("from", 50)
						.e("to", 120)
						.e("groupHaving", map()
							.e("attributeNameEquals", "height"))
						.build()
				)
			)
		);
	}

	@Test
	void shouldResolveGroupHavingNestedAttributeOnGroupSchema() {
		// inside groupHaving, the data locator switches to the `categoryGroup` group entity — the
		// `NAME` attribute exists there (defined in AbstractConstraintResolverTest), so the nested
		// attributeEquals must resolve and produce the corresponding constraint
		assertEquals(
			histogramHaving(
				"CATEGORY",
				50,
				120,
				groupHaving(attributeEquals("NAME", "height"))
			),
			QueryPurifierVisitor.purify(
				this.resolver.resolve(
					Entities.PRODUCT,
					"referenceCategoryHistogramHaving",
					map()
						.e("from", 50)
						.e("to", 120)
						.e("groupHaving", map()
							.e("attributeNameEquals", "height"))
						.build()
				)
			)
		);
	}

	@Test
	void shouldRejectGroupHavingNestedAttributeNotOnGroupSchema() {
		// `CODE` exists on Product and on the Category reference attributes but NOT on the
		// `categoryGroup` entity — once the locator switches to GROUP_ENTITY, attribute lookup
		// must fail; the resolver throws an internal error naming the missing classifier
		assertThrows(
			EvitaInternalError.class,
			() -> this.resolver.resolve(
				Entities.PRODUCT,
				"referenceCategoryHistogramHaving",
				map()
					.e("from", 50)
					.e("to", 120)
					.e("groupHaving", map()
						.e("attributeCodeEquals", "x"))
					.build()
			)
		);
	}

	@Test
	void shouldResolveComplexFilterConstraintTree() {
		//noinspection ConstantConditions
		assertEquals(
			filterBy(
				attributeEquals("CODE", "123"),
				or(
					attributeIs("AGE", AttributeSpecialValue.NULL),
					and(
						priceBetween(BigDecimal.valueOf(10L), BigDecimal.valueOf(20L)),
						facetHaving("BRAND", entityPrimaryKeyInSet(10, 20, 30))
					)
				),
				referenceHaving(
					"CATEGORY",
					and(
						attributeStartsWith("CODE", "ab"),
						entityPrimaryKeyInSet(2),
						entityHaving(
							and(
								attributeEquals("NAME", "cd"),
								referenceHaving(
									"RELATED_PRODUCTS",
									attributeEquals("ORDER", 1)
								)
							)
						)
					)
				)
			),
			QueryPurifierVisitor.purify(
				this.resolver.resolve(
					Entities.PRODUCT,
					"filterBy",
					map()
						.e("attributeCodeEquals", "123")
						.e("or", List.of(
							map()
								.e("attributeAgeIs", AttributeSpecialValue.NULL)
								.build(),
							map()
								.e("priceBetween", List.of(BigDecimal.valueOf(10L), BigDecimal.valueOf(20L)))
								.e(
									"facetBrandHaving",
									map()
										.e("entityPrimaryKeyInSet",  List.of(10, 20, 30))
										.build()
								)
								.build()
						))
						.e("referenceCategoryHaving", List.of(
							map()
								.e("attributeCodeStartsWith", "ab")
								.e("entityPrimaryKeyInSet", List.of(2))
								.e("entityHaving", map()
									.e("attributeNameEquals", "cd")
									.e("referenceRelatedProductsHaving", List.of(
										map()
											.e("attributeOrderEquals", 1)
											.build()
									)))
								.build()
						))
						.build()
				)
			)
		);
	}

	@Test
	void shouldResolveComplexFilterAndFilterOutUndefinedConstraints() {
		//noinspection ConstantConditions
		assertEquals(
			filterBy(
				attributeEquals("CODE", "123"),
				facetHaving("BRAND", entityPrimaryKeyInSet(10, 20, 30)),
				referenceHaving(
					"CATEGORY",
					and(
						attributeStartsWith("CODE", "ab"),
						entityPrimaryKeyInSet(2),
						entityHaving(
							and(
								attributeEquals("NAME", "cd"),
								referenceHaving(
									"RELATED_PRODUCTS",
									attributeEquals("ORDER", 1)
								)
							)
						)
					)
				)
			),
			QueryPurifierVisitor.purify(
				this.resolver.resolve(
					Entities.PRODUCT,
					"filterBy",
					map()
						.e("attributeCodeEquals", "123")
						.e("or", List.of(
							map()
								.e("attributeAgeIs", null)
								.build(),
							map()
								.e("priceBetween", null)
								.e(
									"facetBrandHaving",
									map()
										.e("entityPrimaryKeyInSet",  List.of(10, 20, 30))
										.build()
								)
								.build()
						))
						.e("referenceCategoryHaving", List.of(
							map()
								.e("attributeCodeStartsWith", "ab")
								.e("entityPrimaryKeyInSet", List.of(2))
								.e("entityHaving", map()
									.e("attributeNameEquals", "cd")
									.e("referenceRelatedProductsHaving", List.of(
										map()
											.e("attributeOrderEquals", 1)
											.e("and", null)
											.build()
									)))
								.build()
						))
						.build()
				)
			)
		);
	}
}
