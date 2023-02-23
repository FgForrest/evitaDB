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

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.EvitaSession;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import org.mockito.Mockito;

/**
 * This class contains shared variables and logic for mutation specific tests in this package.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class AbstractMutatorTestBase {
	protected static final String ENTITY_NAME = "product";
	protected final Catalog catalog;
	protected final CatalogIndex catalogIndex;
	protected final CatalogSchema catalogSchema;
	protected final MockStorageContainerAccessor containerAccessor = new MockStorageContainerAccessor();
	protected final DataGenerator dataGenerator = new DataGenerator();
	protected final EntityIndexLocalMutationExecutor executor;
	protected final GlobalEntityIndex productIndex;
	protected final EntitySchema productSchema;
	protected final SealedCatalogSchema sealedCatalogSchema;

	{
		catalog = Mockito.mock(Catalog.class);
		final InternalCatalogSchemaBuilder catalogSchemaBuilder = new InternalCatalogSchemaBuilder(
			CatalogSchema._internalBuild(
				TestConstants.TEST_CATALOG,
				NamingConvention.generate(TestConstants.TEST_CATALOG),
				entityType -> catalog.getEntitySchema(entityType).orElse(null)
			)
		);
		alterCatalogSchema(catalogSchemaBuilder);
		catalogSchema = (CatalogSchema) catalogSchemaBuilder.toInstance();
		sealedCatalogSchema = new CatalogSchemaDecorator(catalogSchema);
		catalogIndex = new CatalogIndex(catalog);

		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(catalog.getSchema()).thenReturn(sealedCatalogSchema);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(sealedCatalogSchema);

		productSchema = unwrap(
			dataGenerator.getSampleProductSchema(
				mockSession,
				EntitySchemaEditor.EntitySchemaBuilder::toInstance,
				AbstractMutatorTestBase.this::alterProductSchema
			)
		);
		productIndex = new GlobalEntityIndex(1, new EntityIndexKey(EntityIndexType.GLOBAL), () -> productSchema);
		executor = new EntityIndexLocalMutationExecutor(
			containerAccessor, 1,
			new MockEntityIndexCreator<>(productIndex),
			new MockEntityIndexCreator<>(catalogIndex),
			() -> productSchema,
			entityType -> entityType.equals(productSchema.getName()) ? productSchema : null
		);

		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(catalog.getCollectionForEntityOrThrowException(productSchema.getName())).thenReturn(productCollection);
		Mockito.when(catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(productCollection.getEntityType()).thenReturn(productSchema.getName());
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
	}

	private EntitySchema unwrap(SealedEntitySchema entitySchema) {
		return (EntitySchema) ((EntitySchemaDecorator)entitySchema).getDelegate();
	}

	protected abstract void alterCatalogSchema(CatalogSchemaEditor.CatalogSchemaBuilder schemaBuilder);

	protected abstract void alterProductSchema(EntitySchemaEditor.EntitySchemaBuilder schemaBuilder);

}
