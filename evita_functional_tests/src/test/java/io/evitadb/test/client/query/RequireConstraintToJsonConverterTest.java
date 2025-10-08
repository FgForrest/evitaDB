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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.query.require.FacetGroupsDisjunction;
import io.evitadb.api.query.require.FacetGroupsNegation;
import io.evitadb.api.query.require.PriceType;
import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link RequireConstraintToJsonConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class RequireConstraintToJsonConverterTest extends ConstraintToJsonConverterTest {

	@Nonnull private static final Set<Class<? extends Constraint<?>>> allowedRequireConstraints = Set.of(
		Require.class,
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		PriceType.class
	);

	private RequireConstraintToJsonConverter baseConverter;
	private RequireConstraintToJsonConverter limitedConverter;

	@BeforeEach
	void init() {
		super.init();
		final FilterConstraintToJsonConverter filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(this.catalogSchema);
		final OrderConstraintToJsonConverter orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(this.catalogSchema);
		this.baseConverter = new RequireConstraintToJsonConverter(
			this.catalogSchema,
			new AtomicReference<>(filterConstraintToJsonConverter),
			new AtomicReference<>(orderConstraintToJsonConverter)
		);
		this.limitedConverter = new RequireConstraintToJsonConverter(
			this.catalogSchema,
			allowedRequireConstraints::contains,
			new AtomicReference<>(filterConstraintToJsonConverter),
			new AtomicReference<>(orderConstraintToJsonConverter)
		);
	}

	@Test
	void shouldConvertRequireConstraintWithLimitedConverter() {
		final ObjectNode facetBrandGroupsConjunction = jsonNodeFactory.objectNode();

		final ObjectNode filterBy = jsonNodeFactory.objectNode();

		final ArrayNode entityPrimaryKeyInSet = jsonNodeFactory.arrayNode();
		entityPrimaryKeyInSet.add(1);
		entityPrimaryKeyInSet.add(2);
		filterBy.putIfAbsent("entityPrimaryKeyInSet", entityPrimaryKeyInSet);

		facetBrandGroupsConjunction.putIfAbsent("filterBy", filterBy);

		assertEquals(
			new JsonConstraint("facetBrandGroupsConjunction", facetBrandGroupsConjunction),
			this.limitedConverter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				facetGroupsConjunction(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1, 2)))
			).get()
		);
	}

	@Test
	void shouldConvertRequireConstraintWithAdditionalChildConstraint() {
		final ObjectNode stopAt = jsonNodeFactory.objectNode();
		final ObjectNode node = jsonNodeFactory.objectNode();
		final ObjectNode filterBy = jsonNodeFactory.objectNode();
		final ArrayNode and = jsonNodeFactory.arrayNode();
		final ObjectNode andWrapperContainer = jsonNodeFactory.objectNode();
		final ArrayNode entityPrimaryKeyInSet = jsonNodeFactory.arrayNode();
		entityPrimaryKeyInSet.add(1);
		andWrapperContainer.putIfAbsent("entityPrimaryKeyInSet", entityPrimaryKeyInSet);
		and.add(andWrapperContainer);
		filterBy.putIfAbsent("and", and);
		node.putIfAbsent("filterBy", filterBy);
		stopAt.putIfAbsent("node", node);

		assertEquals(
			new JsonConstraint("stopAt", stopAt),
			this.baseConverter.convert(
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				stopAt(
					node(
						filterBy(
							and(
								entityPrimaryKeyInSet(1)
							)
						)
					)
				)
			).get()
		);
	}
}
