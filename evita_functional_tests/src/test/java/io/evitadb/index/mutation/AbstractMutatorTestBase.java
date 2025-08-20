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

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
		this.catalog = Mockito.mock(Catalog.class);
		final InternalCatalogSchemaBuilder catalogSchemaBuilder = new InternalCatalogSchemaBuilder(
			CatalogSchema._internalBuild(
				TestConstants.TEST_CATALOG,
				NamingConvention.generate(TestConstants.TEST_CATALOG),
				EnumSet.allOf(CatalogEvolutionMode.class),
				new EntitySchemaProvider() {
					@Nonnull
					@Override
					public Collection<EntitySchemaContract> getEntitySchemas() {
						return AbstractMutatorTestBase.this.catalog.getEntitySchemaIndex().values();
					}

					@Nonnull
					@Override
					public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
						return AbstractMutatorTestBase.this.catalog.getEntitySchema(entityType).map(EntitySchemaContract.class::cast);
					}
				}
			)
		);
		alterCatalogSchema(catalogSchemaBuilder);
		this.catalogSchema = (CatalogSchema) catalogSchemaBuilder.toInstance();
		this.sealedCatalogSchema = new CatalogSchemaDecorator(this.catalogSchema);
		this.catalogIndex = new CatalogIndex(Scope.LIVE);
		this.catalogIndex.attachToCatalog(null, this.catalog);

		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(this.catalog.getSchema()).thenReturn(this.sealedCatalogSchema);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(this.sealedCatalogSchema);

		this.productSchema = unwrap(
			this.dataGenerator.getSampleProductSchema(
				mockSession,
				EntitySchemaEditor.EntitySchemaBuilder::toInstance,
				AbstractMutatorTestBase.this::alterProductSchema
			)
		);
		this.productIndex = new GlobalEntityIndex(1, this.productSchema.getName(), new EntityIndexKey(EntityIndexType.GLOBAL));
		final AtomicInteger sequencer = new AtomicInteger(1);
		this.executor = new EntityIndexLocalMutationExecutor(
			this.containerAccessor, 1,
			new MockEntityIndexCreator<>(this.productIndex),
			new MockEntityIndexCreator<>(this.catalogIndex),
			() -> this.productSchema,
			sequencer::getAndIncrement,
			false,
			() -> {
				throw new UnsupportedOperationException("Not supported in the test.");
			}
		);

		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(this.productSchema.getName())).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(productCollection.getEntityType()).thenReturn(this.productSchema.getName());
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
	}

	private EntitySchema unwrap(SealedEntitySchema entitySchema) {
		return (EntitySchema) ((EntitySchemaDecorator)entitySchema).getDelegate();
	}

	protected abstract void alterCatalogSchema(CatalogSchemaEditor.CatalogSchemaBuilder schemaBuilder);

	protected abstract void alterProductSchema(EntitySchemaEditor.EntitySchemaBuilder schemaBuilder);

}
