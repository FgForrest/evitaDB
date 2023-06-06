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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link OrderConstraintResolver}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class OrderConstraintResolverTest extends AbstractConstraintResolverTest {

	private OrderConstraintResolver resolver;

	@BeforeEach
	void init() {
		super.init();
		resolver = new OrderConstraintResolver(catalogSchema);
	}

	@Test
	void shouldResolveValueOrderConstraint() {
		assertEquals(
			attributeNatural(OrderDirection.ASC, "CODE"),
			resolver.resolve(
				Entities.PRODUCT,
				"attributeCodeNatural",
				OrderDirection.ASC
			)
		);
	}

	@Test
	void shouldNotResolveValueOrderConstraint() {
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeNatural", null));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeNatural", List.of()));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeNatural", Map.of()));
	}

	@Test
	void shouldResolveChildOrderConstraint() {
		assertEquals(
			referenceProperty(
				"CATEGORY",
				attributeNatural(OrderDirection.ASC, "CODE"),
				random()
			),
			resolver.resolve(
				Entities.PRODUCT,
				"referenceCategoryProperty",
				mapOf(
					"attributeCodeNatural", OrderDirection.ASC,
					"random", true
				)
			)
		);
	}

	@Test
	void shouldNotResolveChildOrderConstraint() {
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve(Entities.PRODUCT, "referenceCategoryProperty", null));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "referenceCategoryProperty", "abc"));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "referenceCategoryProperty", List.of()));
	}

	@Test
	void shouldResolveComplexOrderConstraintTree() {
		//noinspection ConstantConditions
		assertEquals(
			orderBy(
				attributeNatural(OrderDirection.ASC, "CODE"),
				priceNatural(OrderDirection.DESC),
				referenceProperty(
					"CATEGORY",
					attributeNatural(OrderDirection.DESC, "CODE"),
					random()
				)
			),
			QueryPurifierVisitor.purify(
				resolver.resolve(
					Entities.PRODUCT,
					"orderBy",
					mapOf(
						"attributeCodeNatural", OrderDirection.ASC,
						"priceNatural", OrderDirection.DESC,
						"referenceCategoryProperty", mapOf(
							"attributeCodeNatural", OrderDirection.DESC,
							"random", true
						)
					)
				)
			)
		);
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