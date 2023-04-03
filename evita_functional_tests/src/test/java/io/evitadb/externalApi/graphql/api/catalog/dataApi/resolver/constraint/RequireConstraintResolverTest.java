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

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.facetGroupsConjunction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RequireConstraintResolver}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class RequireConstraintResolverTest extends AbstractConstraintResolverTest {

	private RequireConstraintResolver resolver;

	@BeforeEach
	void init() {
		super.init();
		resolver = new RequireConstraintResolver(catalogSchema, "PRODUCT");
	}

	@Test
	void shouldResolveValueRequireConstraint() {
		assertEquals(
			facetGroupsConjunction("BRAND", 1, 2),
			resolver.resolve(
				"facetBrandGroupsConjunction",
				List.of(1, 2)
			)
		);
	}

	@Test
	void shouldNotResolveValueRequireConstraint() {
		assertThrows(EvitaInvalidUsageException.class, () -> resolver.resolve("facetBrandGroupsConjunction", null));
		assertThrows(EvitaInternalError.class, () -> resolver.resolve("facetBrandGroupsConjunction", Map.of()));
	}
}