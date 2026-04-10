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

package io.evitadb.api.functional.reference;

import io.evitadb.api.AbstractHundredProductsFunctionalTest;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DataCarrier;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract base class for reference filtering functional tests. Provides shared dataset setup
 * and utility methods for tests that verify reference-related filtering constraints
 * (entityHaving, groupHaving).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class AbstractReferenceFilterFunctionalTest extends AbstractHundredProductsFunctionalTest {
	static final String HUNDRED_PRODUCTS = "HundredProducts";

	/**
	 * Returns primary keys of original products matching the given predicate.
	 */
	@Nonnull
	static Integer[] getRequestedIdsByPredicate(
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull Predicate<SealedEntity> predicate
	) {
		final Integer[] entitiesMatchingTheRequirements = originalProducts
			.stream()
			.filter(predicate)
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "There are no entities matching the requirements!");
		return entitiesMatchingTheRequirements;
	}

	@Nonnull
	@DataSet(value = HUNDRED_PRODUCTS)
	@Override
	protected DataCarrier setUp(@Nonnull Evita evita) {
		return super.setUp(evita);
	}
}
