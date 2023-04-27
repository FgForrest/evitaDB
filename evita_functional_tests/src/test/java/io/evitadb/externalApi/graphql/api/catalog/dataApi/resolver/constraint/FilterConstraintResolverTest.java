/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link FilterConstraintResolver}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class FilterConstraintResolverTest extends AbstractConstraintResolverTest {

	private FilterConstraintResolver resolver;

	@BeforeEach
	void init() {
		super.init();
		resolver = new FilterConstraintResolver(catalogSchema);
	}

	@Test
	void shouldResolveValueFilterConstraint() {
		assertEquals(
			attributeEquals("CODE", "123"),
			resolver.resolve(
				Entities.PRODUCT,
				"attributeCodeEquals",
				"123"
			)
		);
	}

	@Test
	void shouldNotResolveValueFilterConstraint() {
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeEquals", null));
		assertThrows(UnsupportedDataTypeException.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeEquals", List.of()));
		assertThrows(UnsupportedDataTypeException.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeEquals", Map.of()));
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
			resolver.resolve(
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
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve(Entities.PRODUCT, "and", null));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "and", "abc"));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "and", Map.of()));
	}

	@Test
	void shouldResolveFilterConstraintWithMultipleArguments() {
		assertEquals(
			hierarchyWithin(
				"CATEGORY",
				1,
				directRelation()
			),
			resolver.resolve(
				Entities.PRODUCT,
				"hierarchyCategoryWithin",
				Map.of(
					"ofParent", 1,
					"with", Map.of(
						"hierarchyDirectRelation", true
					)
				)
			)
		);

		assertEquals(
			hierarchyWithin(
				"CATEGORY",
				1
			),
			resolver.resolve(
				Entities.PRODUCT,
				"hierarchyCategoryWithin",
				Map.of(
					"ofParent", 1
				)
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
			resolver.resolve(
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
			resolver.resolve(
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
			resolver.resolve(
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
			() -> resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				null
			)
		);

		assertThrows(
			EvitaInvalidUsageException.class,
			() -> resolver.resolve(
				Entities.PRODUCT,
				"attributeAgeBetween",
				List.of(1)
			)
		);

		assertThrows(
			EvitaInternalError.class,
			() -> resolver.resolve(
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
	void shouldResolveComplexFilterConstraintTree() {
		//noinspection ConstantConditions
		assertEquals(
			filterBy(
				and(
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
				)
			),
			QueryPurifierVisitor.purify(
				resolver.resolve(
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
								.e("facetBrandHaving", List.of(
									map()
										.e("entityPrimaryKeyInSet",  List.of(10, 20, 30))
										.build()
								))
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
}