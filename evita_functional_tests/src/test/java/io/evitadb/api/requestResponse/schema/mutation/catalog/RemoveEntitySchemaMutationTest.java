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
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This test verifies {@link RemoveEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RemoveEntitySchemaMutationTest {

	@Test
	void shouldMutateCatalogSchema() {
		RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);

		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
		final EntityAttributeSchema attribute = Mockito.mock(EntityAttributeSchema.class);
		Mockito.doReturn(Integer.class).when(attribute).getType();
		Mockito.when(entitySchema.getName()).thenReturn("entityName");
		Mockito.when(entitySchema.version()).thenReturn(1);
		Mockito.when(catalogSchema.getEntitySchema("entityName")).thenReturn(of(entitySchema));

		final MutationEntitySchemaAccessor entitySchemaAccessor = new MutationEntitySchemaAccessor(catalogSchema);
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, entitySchemaAccessor);
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertEquals(1, newCatalogSchema.version());

		assertFalse(entitySchemaAccessor.getEntitySchema("newEntityName").isPresent());
	}

}
