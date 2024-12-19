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
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * This test verifies {@link ModifyEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyEntitySchemaMutationTest {

	@Test
	void shouldCombineWithAnotherMutationToSameSchema() {
		ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
			"entityName",
			new SetAttributeSchemaUniqueMutation("someAttribute", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
		);

		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			catalogSchema,
			new ModifyEntitySchemaMutation(
				"entityName",
				new SetAttributeSchemaFilterableMutation("someAttribute", true)
			)
		);

		assertNull(result.origin());
		assertNotNull(result.current());
		assertEquals(1, result.current().length);

		final ModifyEntitySchemaMutation entitySchemaMutation = (ModifyEntitySchemaMutation) result.current()[0];
		assertEquals(2, entitySchemaMutation.getSchemaMutations().length);
		assertInstanceOf(SetAttributeSchemaUniqueMutation.class, entitySchemaMutation.getSchemaMutations()[0]);
		assertInstanceOf(SetAttributeSchemaFilterableMutation.class, entitySchemaMutation.getSchemaMutations()[1]);
	}

	@Test
	void shouldNotCombineWithAnotherMutationToDifferentSchema() {
		ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
			"entityName",
			new SetAttributeSchemaUniqueMutation("someAttribute", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
		);

		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			catalogSchema,
			new ModifyEntitySchemaMutation(
				"differentEntityName",
				new SetAttributeSchemaFilterableMutation("someAttribute", true)
			)
		);

		assertNull(result);
	}

	@Test
	void shouldMutateCatalogSchema() {
		ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
			"entityName",
			new SetAttributeSchemaUniqueMutation("someAttribute", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);

		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
		final EntityAttributeSchema attribute = Mockito.mock(EntityAttributeSchema.class);
		Mockito.doReturn(Integer.class).when(attribute).getType();
		Mockito.doReturn(
			AttributeSchema.toUniquenessEnumMap(
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
				}
			)
		).when(attribute).getUniquenessTypeInScopes();

		Mockito.when(entitySchema.getAttribute("someAttribute")).thenReturn(of(attribute));
		Mockito.when(entitySchema.getName()).thenReturn("entityName");
		Mockito.when(entitySchema.version()).thenReturn(1);
		Mockito.when(catalogSchema.getEntitySchema("entityName")).thenReturn(of(entitySchema));

		final MutationEntitySchemaAccessor entitySchemaAccessor = new MutationEntitySchemaAccessor(catalogSchema);
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, entitySchemaAccessor);
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertEquals(1, newCatalogSchema.version());

		final EntitySchemaContract updatedSchema = entitySchemaAccessor.getEntitySchema("entityName").orElseThrow();
		final EntityAttributeSchemaContract updatedAttribute = updatedSchema.getAttribute("someAttribute").orElseThrow();
		assertEquals(2, updatedSchema.version());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, updatedAttribute.getUniquenessType());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
			"entityName",
			new SetAttributeSchemaUniqueMutation("someAttribute", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
		);

		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
		final EntityAttributeSchema attribute = Mockito.mock(EntityAttributeSchema.class);
		Mockito.doReturn(Integer.class).when(attribute).getType();
		Mockito.doReturn(
			AttributeSchema.toUniquenessEnumMap(
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
				}
			)
		).when(attribute).getUniquenessTypeInScopes();

		Mockito.when(entitySchema.getAttribute("someAttribute")).thenReturn(of(attribute));
		Mockito.when(entitySchema.getName()).thenReturn("entityName");
		Mockito.when(entitySchema.version()).thenReturn(1);

		final EntitySchemaContract updatedSchema = mutation.mutate(Mockito.mock(CatalogSchema.class), entitySchema);
		final EntityAttributeSchemaContract updatedAttribute = updatedSchema.getAttribute("someAttribute").orElseThrow();
		assertEquals(2, updatedSchema.version());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, updatedAttribute.getUniquenessType());
	}

}
