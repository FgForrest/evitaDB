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

package io.evitadb.core;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.test.TestFileSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies behaviour of {@link Catalog}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class CatalogTest implements TestFileSupport {

	@Test
	void shouldDefineCatalogSchemaUpfront() throws IOException {
		cleanTestDirectory();
		try (final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory())
						.build()
				).build()
		)) {
			evita.defineCatalog(TestConstants.TEST_CATALOG);
			evita.updateCatalog(
				TestConstants.TEST_CATALOG,
				evitaSession -> {
					evitaSession.getCatalogSchema().openForWrite()
						.withAttribute("code", String.class, whichIs -> whichIs.uniqueGlobally().sortable())
						.withAttribute("name", String.class, whichIs -> whichIs.localized().filterable().sortable())
						.updateVia(evitaSession);

					evitaSession.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute("code")
						.withGlobalAttribute("name")
						.withAttribute("catalogNumber", String.class, whichIs -> whichIs.filterable())
						.withPrice()
						.updateVia(evitaSession);
				});

			evita.queryCatalog(
				TestConstants.TEST_CATALOG,
				evitaSession -> {
					final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();

					assertEquals(9, catalogSchema.getVersion());
					final AttributeSchemaContract code = catalogSchema.getAttribute("code").orElseThrow();
					final AttributeSchemaContract name = catalogSchema.getAttribute("name").orElseThrow();

					final SealedEntitySchema entitySchema = evitaSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
					assertEquals(6, entitySchema.getVersion());
					assertSame(entitySchema.getAttribute("code").orElse(null), code);
					assertSame(entitySchema.getAttribute("name").orElse(null), name);
					return null;
				}
			);
		}
	}

}