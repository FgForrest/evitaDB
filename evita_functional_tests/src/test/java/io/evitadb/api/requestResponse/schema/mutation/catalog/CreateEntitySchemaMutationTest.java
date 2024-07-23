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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * This test verifies {@link CreateEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CreateEntitySchemaMutationTest {

	@Test
	void shouldMutateCatalogSchema() {
		CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("newEntityCollection");
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final MutationEntitySchemaAccessor schemaProvider = new MutationEntitySchemaAccessor(Mockito.mock(EntitySchemaProvider.class));
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, schemaProvider);
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertNull(result.entitySchemaMutations());
		assertNotNull(newCatalogSchema);
		assertEquals(1, newCatalogSchema.version());

		final Collection<EntitySchemaContract> entitySchemas = schemaProvider.getEntitySchemas();
		assertEquals(1, entitySchemas.size());
		assertNotNull(schemaProvider.getEntitySchema("newEntityCollection"));
	}

}
