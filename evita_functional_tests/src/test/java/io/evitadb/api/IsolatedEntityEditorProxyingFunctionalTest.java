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

package io.evitadb.api;

import io.evitadb.api.mock.CategoryEditorInterface;
import io.evitadb.api.mock.CategoryInterface;
import io.evitadb.api.mock.SealedCategoryInterface;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.OffsetDateTime;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_VALIDITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface which is isolated from the original
 * immutable entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita isolated entity editor interface proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@TestMethodOrder(OrderAnnotation.class)
@Slf4j
public class IsolatedEntityEditorProxyingFunctionalTest extends AbstractEntityProxyingFunctionalTest implements EvitaTestSupport {
	private static final DateTimeRange VALIDITY = DateTimeRange.between(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

	private static void assertCategory(SealedEntity category, String code, String name, long priority, DateTimeRange validity) {
		assertEquals(code, category.getAttribute(ATTRIBUTE_CODE));
		assertEquals(name, category.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE));
		assertEquals(priority, category.getAttribute(ATTRIBUTE_PRIORITY, Long.class));
		if (validity == null) {
			assertNull(category.getAttribute(ATTRIBUTE_VALIDITY));
		} else {
			assertEquals(validity, category.getAttribute(ATTRIBUTE_VALIDITY));
		}
	}

	@DisplayName("Should create new entity of custom type")
	@Order(1)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomType(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				/*
					This is somehow weird scenario - created instances are always mutable - so the `openForWrite` is
					technically not necessary, but the create new entity should be correctly called with CategoryEditorInterface
					here and not the SealedCategoryInterface
				 */
				final CategoryEditorInterface newCategory = evitaSession.createNewEntity(SealedCategoryInterface.class, 1000)
					.openForWrite()
					.setCode("root-category")
					.setName(CZECH_LOCALE, "Kořenová kategorie")
					.setPriority(78L)
					.setValidity(VALIDITY);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = newCategory.toInstance();
				assertEquals("root-category", modifiedInstance.getCode());
				assertEquals("Kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(78L, modifiedInstance.getPriority());
				assertEquals(VALIDITY, modifiedInstance.getValidity());

				newCategory.upsertVia(evitaSession);

				assertEquals(1000, newCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"root-category", "Kořenová kategorie", 78L, VALIDITY
				);
			}
		);
	}

	@DisplayName("Should update existing entity of custom type")
	@Order(2)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldUpdateExistingEntityOfCustomType(EvitaContract evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isEmpty()) {
					shouldCreateNewEntityOfCustomType(evita);
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final SealedCategoryInterface sealedCategory = evitaSession.queryOne(
					Query.query(
						filterBy(
							entityPrimaryKeyInSet(1000)
						),
						require(entityFetchAll())
					),
					SealedCategoryInterface.class
				).orElseThrow();

				final CategoryEditorInterface updatedCategory = sealedCategory
					.openForWrite()
					.setCode("updated-root-category")
					.setName(CZECH_LOCALE, "Aktualizovaná kořenová kategorie")
					.setPriority(178L)
					.setValidity(null);

				final Optional<EntityMutation> mutation = updatedCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(4, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = updatedCategory.toInstance();
				assertEquals("updated-root-category", modifiedInstance.getCode());
				assertEquals("Aktualizovaná kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(178L, modifiedInstance.getPriority());
				assertNull(modifiedInstance.getValidity());

				assertEquals("root-category", sealedCategory.getCode());
				assertEquals("Kořenová kategorie", sealedCategory.getName(CZECH_LOCALE));
				assertEquals(78L, sealedCategory.getPriority());
				assertEquals(VALIDITY, sealedCategory.getValidity());

				updatedCategory.upsertVia(evitaSession);

				assertEquals(1000, updatedCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, updatedCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"updated-root-category", "Aktualizovaná kořenová kategorie", 178L, null
				);
			}
		);
	}

}
