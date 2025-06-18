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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies {@link ModifyCatalogSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyCatalogSchemaMutationTest {

	@Test
	void shouldMutateCatalogSchema() {
		ModifyCatalogSchemaMutation mutation = new ModifyCatalogSchemaMutation(
			"catalogName",
			null,
			new SetAttributeSchemaGloballyUniqueMutation("someAttribute", GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final GlobalAttributeSchema globalAttribute = Mockito.mock(GlobalAttributeSchema.class);
		Mockito.doReturn(Integer.class).when(globalAttribute).getType();
		Mockito.when(catalogSchema.getAttribute("someAttribute")).thenReturn(of(globalAttribute));
		Mockito.when(catalogSchema.getEntitySchema("product")).thenReturn(of(Mockito.mock(EntitySchema.class)));
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertEquals(2, newCatalogSchema.version());
	}

}
