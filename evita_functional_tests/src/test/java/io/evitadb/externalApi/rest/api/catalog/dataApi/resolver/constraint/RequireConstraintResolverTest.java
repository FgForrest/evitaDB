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

import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class RequireConstraintResolverTest extends AbstractConstraintResolverTest {

	private io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver resolver;

	@BeforeEach
	void init() {
		super.init();
		final AtomicReference<FilterConstraintResolver> filterConstraintResolver = new AtomicReference<>(new FilterConstraintResolver(this.catalogSchema));
		this.resolver = new RequireConstraintResolver(
			this.catalogSchema,
			filterConstraintResolver,
			new AtomicReference<>(new OrderConstraintResolver(this.catalogSchema, filterConstraintResolver))
		);
	}

	@Test
	void shouldResolveValueRequireConstraint() {
		assertEquals(
			facetGroupsConjunction(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1, 2))),
			this.resolver.resolve(
				Entities.PRODUCT,
				"facetBrandGroupsConjunction",
				map()
					.e("filterBy", map()
						.e("entityPrimaryKeyInSet", List.of(1, 2)))
					.build()
			)
		);
		assertEquals(
			facetGroupsConjunction(Entities.BRAND),
			this.resolver.resolve(
				Entities.PRODUCT,
				"facetBrandGroupsConjunction",
				map().build()
			)
		);
	}

	@Test
	void shouldResolveRequireConstraintWithAdditionalChildConstraint() {
		assertEquals(
			stopAt(
				node(
					filterBy(
						entityPrimaryKeyInSet(1)
					)
				)
			),
			this.resolver.resolve(
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				"stopAt",
				map()
					.e("node", map()
						.e("filterBy", map()
							.e("entityPrimaryKeyInSet", List.of(1))))
					.build()
			)
		);
	}

	@Test
	void shouldResolveRequireConstraintWithSuffixedConstraintCreator() {
		assertEquals(
			require(
				entityFetch(
					referenceContentAllWithAttributes(
						attributeContent("NAME")
					)
				)
			),
			this.resolver.resolve(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				"require",
				map()
					.e("entityFetch", map()
						.e("referenceContentAllWithAttributes", map()
							.e("attributeContent", List.of("NAME"))))
					.build()
			)
		);

		assertEquals(
			require(
				entityFetch(
					referenceContentWithAttributes(
						"CATEGORY",
						attributeContentAll()
					)
				)
			),
			this.resolver.resolve(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				"require",
				map()
					.e("entityFetch", map()
						.e("referenceCategoryContentWithAttributes", map()
							.e("attributeContentAll", true)))
					.build()
			)
		);
	}

	@Test
	void shouldResolveComplexRequireAndFilterOutUndefinedConstraints() {
		assertEquals(
			require(
				facetGroupsDisjunction(
					"CATEGORY",
					filterBy(
						entityPrimaryKeyInSet(123)
					)
				)
			),
			QueryPurifierVisitor.purify(
				Objects.requireNonNull(
					this.resolver.resolve(
						new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
						"require",
						map()
							.e("priceType", null)
							.e("facetCategoryGroupsDisjunction", map()
								.e("filterBy", map()
									.e("entityPrimaryKeyInSet", list()
										.i(123))))
							.build()
					)
				)
			)
		);
	}

	@Test
	void shouldNotResolveValueRequireConstraint() {
		assertThrows(EvitaInternalError.class, () -> this.resolver.resolve(Entities.PRODUCT, "facetBrandGroupsConjunction", List.of()));
	}
}
