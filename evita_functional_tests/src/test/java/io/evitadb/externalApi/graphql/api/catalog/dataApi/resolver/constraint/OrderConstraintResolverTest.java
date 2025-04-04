/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
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
		resolver = new OrderConstraintResolver(catalogSchema, new AtomicReference<>(new FilterConstraintResolver(catalogSchema)));
	}

	@Test
	void shouldResolveValueOrderConstraint() {
		assertEquals(
			attributeNatural("CODE", OrderDirection.ASC),
			resolver.resolve(
				Entities.PRODUCT,
				"attributeCodeNatural",
				OrderDirection.ASC
			)
		);
	}

	@Test
	void shouldNotResolveValueOrderConstraint() {
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeNatural", List.of()));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "attributeCodeNatural", Map.of()));
	}

	@Test
	void shouldResolveChildOrderConstraint() {
		assertEquals(
			referenceProperty(
				"CATEGORY",
				attributeNatural("CODE", OrderDirection.ASC),
				random()
			),
			resolver.resolve(
				Entities.PRODUCT,
				"referenceCategoryProperty",
				List.of(
					map()
						.e("attributeCodeNatural", OrderDirection.ASC)
						.e("random", true)
						.build()
				)
			)
		);
	}

	@Test
	void shouldNotResolveChildOrderConstraint() {
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "referenceCategoryProperty", "abc"));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve(Entities.PRODUCT, "referenceCategoryProperty", Map.of()));
	}

	@Test
	void shouldResolveComplexOrderConstraintTree() {
		//noinspection ConstantConditions
		assertEquals(
			orderBy(
				attributeNatural("CODE", OrderDirection.ASC),
				priceNatural(OrderDirection.DESC),
				referenceProperty(
					"CATEGORY",
					attributeNatural("CODE", OrderDirection.DESC),
					random()
				)
			),
			QueryPurifierVisitor.purify(
				resolver.resolve(
					Entities.PRODUCT,
					"orderBy",
					List.of(
						map()
							.e("attributeCodeNatural", OrderDirection.ASC)
							.e("priceNatural", OrderDirection.DESC)
							.e("referenceCategoryProperty", list()
								.i(map()
									.e("attributeCodeNatural", OrderDirection.DESC)
									.e("random", true)))
							.build()
					)
				)
			)
		);
	}

	@Test
	void shouldResolveComplexOrderAndFilterOutUndefinedConstraints() {
		//noinspection ConstantConditions
		assertEquals(
			orderBy(
				attributeNatural("CODE", OrderDirection.ASC),
				priceNatural(OrderDirection.DESC),
				referenceProperty(
					"CATEGORY",
					attributeNatural("CODE", OrderDirection.DESC),
					random()
				)
			),
			QueryPurifierVisitor.purify(
				resolver.resolve(
					Entities.PRODUCT,
					"orderBy",
					List.of(
						map()
							.e("attributeCodeNatural", OrderDirection.ASC)
							.e("priceNatural", OrderDirection.DESC)
							.e("referenceBrandProperty", null)
							.e("referenceCategoryProperty", list()
								.i(map()
									.e("attributeCodeNatural", OrderDirection.DESC)
									.e("priceNatural", null)
									.e("random", true)))
							.build()
					)
				)
			)
		);
	}
}
