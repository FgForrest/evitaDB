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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
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
		resolver = new FilterConstraintResolver(catalogSchema, "PRODUCT");
	}

	@Test
	void shouldResolveValueFilterConstraint() {
		assertEquals(
			attributeEquals("CODE", "123"),
			resolver.resolve(
				"attribute_code_equals",
				"123"
			)
		);
	}

	@Test
	void shouldNotResolveValueFilterConstraint() {
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve("attribute_code_equals", null));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve("attribute_code_equals", List.of()));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve("attribute_code_equals", Map.of()));
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
				"and",
				List.of(
					Map.of("attribute_code_equals", "123"),
					Map.of("attribute_age_is", AttributeSpecialValue.NULL)
				)
			)
		);
	}

	@Test
	void shouldNotResolveChildFilterConstraint() {
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve("and", null));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve("and", "abc"));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve("and", Map.of()));
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
				"hierarchy_category_within",
				Map.of(
					"ofParent", 1,
					"with", Map.of(
						"hierarchy_directRelation", true
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
				"hierarchy_category_within",
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
				"attribute_age_between",
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
				"attribute_age_between",
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
				"attribute_age_between",
				Arrays.asList(1, null)
			)
		);
	}

	@Test
	void shouldNotResolveFilterConstraintWithArgumentsResultingInRange() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> resolver.resolve(
				"attribute_age_between",
				null
			)
		);

		assertThrows(
			EvitaInvalidUsageException.class,
			() -> resolver.resolve(
				"attribute_age_between",
				List.of(1)
			)
		);

		assertThrows(
			EvitaInternalError.class,
			() -> resolver.resolve(
				"attribute_age_between",
				mapOf(
					"from", 1,
					"to", 2
				)
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
							facetInSet("BRAND", 10, 20, 30)
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
					"filterBy",
					List.of(
						mapOf(
							"attribute_code_equals", "123",
							"or", List.of(
								mapOf(
									"attribute_age_is", AttributeSpecialValue.NULL
								),
								mapOf(
									"price_between", List.of(BigDecimal.valueOf(10L), BigDecimal.valueOf(20L)),
									"facet_brand_inSet", List.of(10, 20, 30)
								)
							),
							"reference_category_having", List.of(
								mapOf(
									"attribute_code_startsWith", "ab",
									"entity_primaryKey_inSet", List.of(2),
									"entity_having", mapOf(
										"attribute_name_equals", "cd",
										"reference_relatedProducts_having", List.of(
											mapOf(
												"attribute_order_equals", 1
											)
										)
									)
								)
							)
						)
					)
				)
			)
		);
	}

	private <K, V> Map<K, V> mapOf(K k1, V v1) {
		final LinkedHashMap<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		return map;
	}

	private <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
		final LinkedHashMap<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		map.put(k2, v2);
		return map;
	}

	private <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
		final LinkedHashMap<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		map.put(k2, v2);
		map.put(k3, v3);
		return map;
	}
}