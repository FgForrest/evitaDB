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

import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityProxyingFunctionalTest extends AbstractFiftyProductsFunctionalTest {

	public static final Locale CZECH_LOCALE = new Locale("cs", "CZ");

	@DisplayName("Should downgrade from SealedEntity to EntityReference")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToEntityReference(EvitaSessionContract evitaSession) {
		final Optional<EntityReference> theReference = evitaSession.getEntity(
			Entities.PRODUCT, EntityReference.class, 1, entityFetchAllContent()
		);
		assertTrue(theReference.isPresent());
		assertEquals(1, theReference.get().getPrimaryKey());
		assertEquals(Entities.PRODUCT, theReference.get().getType());
	}

	@DisplayName("Should wrap an interface and load data in single localization")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToInterfaceWithEnglishLocalization(EvitaSessionContract evitaSession) {
		final Optional<ProductInterface> product = evitaSession.queryOne(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					and(
						entityPrimaryKeyInSet(1),
						entityLocaleEquals(CZECH_LOCALE)
					)
				),
				require(
					entityFetch(
						attributeContent(), associatedDataContent(), priceContentAll(), referenceContentAll()
					)
				)
			),
			ProductInterface.class
		);
		assertTrue(product.isPresent());
		assertEquals(1, product.get().getPrimaryKey());
		assertEquals(Entities.PRODUCT, product.get().getType());
		assertEquals("Ergonomic-Plastic-Table-1", product.get().getCode());
		assertEquals("Incredible Linen Clock", product.get().getName());
		assertEquals(new BigDecimal("310.37"), product.get().getQuantity());
		assertTrue(product.get().isAlias());
	}

	@DisplayName("Should wrap an interface and load all data")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToInterface(EvitaSessionContract evitaSession) {
		final Optional<ProductInterface> productRef = evitaSession.getEntity(
			Entities.PRODUCT, ProductInterface.class, 1, entityFetchAllContent()
		);
		assertTrue(productRef.isPresent());
		final ProductInterface product = productRef.get();
		assertEquals(1, product.getPrimaryKey());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals("Ergonomic-Plastic-Table-1", product.getCode());
		assertEquals("Incredible Linen Clock", product.getName(CZECH_LOCALE));
		assertEquals("Incredible Linen Clock_2", product.getName(Locale.ENGLISH));
		assertEquals(new BigDecimal("310.37"), product.getQuantity());
		assertTrue(product.isAlias());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSet());
	}

}
