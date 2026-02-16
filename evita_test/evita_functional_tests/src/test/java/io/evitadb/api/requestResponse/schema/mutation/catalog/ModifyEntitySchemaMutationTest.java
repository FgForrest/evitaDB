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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
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
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ModifyEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyEntitySchemaMutation")
class ModifyEntitySchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should combine with another mutation to same schema")
		void shouldCombineWithAnotherMutationToSameSchema() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
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
			assertInstanceOf(
				SetAttributeSchemaUniqueMutation.class, entitySchemaMutation.getSchemaMutations()[0]
			);
			assertInstanceOf(
				SetAttributeSchemaFilterableMutation.class, entitySchemaMutation.getSchemaMutations()[1]
			);
		}

		@Test
		@DisplayName("should not combine with mutation to different schema")
		void shouldNotCombineWithAnotherMutationToDifferentSchema() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
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
		@DisplayName("should not combine with different mutation type")
		void shouldNotCombineWithDifferentMutationType() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"entityName",
				new SetAttributeSchemaUniqueMutation("someAttribute", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
			final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
				catalogSchema,
				new CreateEntitySchemaMutation("entityName")
			);
			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should mutate entity via schema accessor")
		void shouldMutateCatalogSchema() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
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

			final MutationEntitySchemaAccessor entitySchemaAccessor =
				new MutationEntitySchemaAccessor(catalogSchema);
			final CatalogSchemaWithImpactOnEntitySchemas result =
				mutation.mutate(catalogSchema, entitySchemaAccessor);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertEquals(1, newCatalogSchema.version());

			final EntitySchemaContract updatedSchema =
				entitySchemaAccessor.getEntitySchema("entityName").orElseThrow();
			final EntityAttributeSchemaContract updatedAttribute =
				updatedSchema.getAttribute("someAttribute").orElseThrow();
			assertEquals(2, updatedSchema.version());
			assertEquals(
				AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, updatedAttribute.getUniquenessType()
			);
		}

		@Test
		@DisplayName("should throw when entity schema not found")
		void shouldThrowWhenEntitySchemaNotFound() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"nonExistent",
				new SetAttributeSchemaFilterableMutation("attr", true)
			);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
			final MutationEntitySchemaAccessor entitySchemaAccessor =
				new MutationEntitySchemaAccessor(catalogSchema);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> mutation.mutate(catalogSchema, entitySchemaAccessor)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should apply schema mutations to entity schema")
		void shouldMutateEntitySchema() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
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

			final EntitySchemaContract updatedSchema =
				mutation.mutate(Mockito.mock(CatalogSchema.class), entitySchema);
			final EntityAttributeSchemaContract updatedAttribute =
				updatedSchema.getAttribute("someAttribute").orElseThrow();
			assertEquals(2, updatedSchema.version());
			assertEquals(
				AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, updatedAttribute.getUniquenessType()
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"entityName", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return entity name as container name")
		void shouldReturnEntityNameAsContainerName() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"entityName", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			assertEquals("entityName", mutation.containerName());
		}

		@Test
		@DisplayName("should return entity name via getName")
		void shouldReturnEntityNameViaGetName() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"entityName", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			assertEquals("entityName", mutation.getName());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"product", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"product", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				"entityName", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			final String result = mutation.toString();
			assertTrue(result.contains("entityName"));
			assertTrue(result.contains("Modify"));
		}

		@Test
		@DisplayName("should be equal to mutation with same parameters")
		void shouldBeEqualToMutationWithSameParameters() {
			final ModifyEntitySchemaMutation mutation1 = new ModifyEntitySchemaMutation(
				"entityName", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			final ModifyEntitySchemaMutation mutation2 = new ModifyEntitySchemaMutation(
				"entityName", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different name")
		void shouldNotBeEqualToMutationWithDifferentName() {
			final ModifyEntitySchemaMutation mutation1 = new ModifyEntitySchemaMutation(
				"entity1", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			final ModifyEntitySchemaMutation mutation2 = new ModifyEntitySchemaMutation(
				"entity2", new SetAttributeSchemaFilterableMutation("attr", true)
			);
			assertNotEquals(mutation1, mutation2);
		}
	}
}
