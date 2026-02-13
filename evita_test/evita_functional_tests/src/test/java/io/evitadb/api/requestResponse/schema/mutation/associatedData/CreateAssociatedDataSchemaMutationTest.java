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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateAssociatedDataSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("CreateAssociatedDataSchemaMutation")
class CreateAssociatedDataSchemaMutationTest {
	static final String ASSOCIATED_DATA_NAME = "name";

	@Nonnull
	static AssociatedDataSchemaContract createExistingAssociatedDataSchema() {
		return AssociatedDataSchema._internalBuild(
			ASSOCIATED_DATA_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			Integer.class,
			false,
			false
		);
	}

	@SuppressWarnings("ResultOfObjectAllocationIgnored")
	@Nested
	@DisplayName("Create associated data schema")
	class Creation {

		@Test
		@DisplayName("should create associated data with all properties")
		void shouldCreateAssociatedData() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final AssociatedDataSchemaContract associatedSchema = mutation.mutate(null);
			assertNotNull(associatedSchema);
			assertEquals(ASSOCIATED_DATA_NAME, associatedSchema.getName());
			assertEquals("description", associatedSchema.getDescription());
			assertEquals("deprecationNotice", associatedSchema.getDeprecationNotice());
			assertSame(String.class, associatedSchema.getType());
			assertTrue(associatedSchema.isLocalized());
			assertTrue(associatedSchema.isNullable());
		}

		@Test
		@DisplayName("should throw exception when unsupported type is provided")
		void shouldThrowExceptionWhenInvalidTypeIsProvided() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new CreateAssociatedDataSchemaMutation(
					ASSOCIATED_DATA_NAME, "description", "deprecationNotice", Serializable.class, true, true
				)
			);
		}

		@Test
		@DisplayName("should reject Predecessor type")
		void shouldRejectPredecessorType() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new CreateAssociatedDataSchemaMutation(
					ASSOCIATED_DATA_NAME, "description", null, Predecessor.class, false, false
				)
			);
		}

		@Test
		@DisplayName("should reject ReferencedEntityPredecessor type")
		void shouldRejectReferencedEntityPredecessorType() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new CreateAssociatedDataSchemaMutation(
					ASSOCIATED_DATA_NAME, "description", null, ReferencedEntityPredecessor.class, false, false
				)
			);
		}

		@Test
		@DisplayName("should accept ComplexDataObject type")
		void shouldAcceptComplexDataObjectType() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, ComplexDataObject.class, false, false
			);
			final AssociatedDataSchemaContract schema = mutation.mutate(null);
			assertSame(ComplexDataObject.class, schema.getType());
		}

		@Test
		@DisplayName("should accept array types")
		void shouldAcceptArrayTypes() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, String[].class, false, false
			);
			final AssociatedDataSchemaContract schema = mutation.mutate(null);
			assertSame(String[].class, schema.getType());
		}
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should be replaced with individual mutations when combined with remove mutation")
		void shouldBeReplacedWithIndividualMutationsWhenAssociatedDataWasRemovedAndCreatedWithDifferentSettings() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
				.thenReturn(of(createExistingAssociatedDataSchema()));
			final RemoveAssociatedDataSchemaMutation removeMutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
			);
			assertNotNull(result);
			assertFalse(result.discarded());
			assertEquals(5, result.current().length);
			assertTrue(Arrays.stream(result.current())
				           .anyMatch(ModifyAssociatedDataSchemaDescriptionMutation.class::isInstance));
			assertTrue(Arrays.stream(result.current())
				           .anyMatch(ModifyAssociatedDataSchemaDeprecationNoticeMutation.class::isInstance));
			assertTrue(
				Arrays.stream(result.current()).anyMatch(ModifyAssociatedDataSchemaTypeMutation.class::isInstance));
			assertTrue(
				Arrays.stream(result.current()).anyMatch(SetAssociatedDataSchemaLocalizedMutation.class::isInstance));
			assertTrue(
				Arrays.stream(result.current()).anyMatch(SetAssociatedDataSchemaNullableMutation.class::isInstance));
		}

		@Test
		@DisplayName("should leave mutation intact when removal targets different associated data")
		void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAssociateData() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final RemoveAssociatedDataSchemaMutation removeMutation = new RemoveAssociatedDataSchemaMutation(
				"differentName");
			assertNull(mutation.combineWith(null, null, removeMutation));
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should create associated data in entity schema")
		void shouldCreateAssociatedDataInEntity() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);
			assertNotNull(newEntitySchema);
			assertEquals(2, newEntitySchema.version());
			final AssociatedDataSchemaContract associatedSchema = newEntitySchema
				.getAssociatedData(ASSOCIATED_DATA_NAME).orElseThrow();
			assertNotNull(associatedSchema);
			assertEquals(ASSOCIATED_DATA_NAME, associatedSchema.getName());
			assertEquals("description", associatedSchema.getDescription());
			assertEquals("deprecationNotice", associatedSchema.getDeprecationNotice());
			assertSame(String.class, associatedSchema.getType());
			assertTrue(associatedSchema.isLocalized());
			assertTrue(associatedSchema.isNullable());
		}

		@Test
		@DisplayName("should throw exception when associated data already exists with different definition")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingAssociatedData() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
					Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
						.thenReturn(of(createExistingAssociatedDataSchema()));
					mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
				}
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnCorrectOperation() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, String.class, false, false
			);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return associated data name as container name")
		void shouldReturnAssociatedDataNameAsContainerName() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, String.class, false, false
			);
			assertEquals(ASSOCIATED_DATA_NAME, mutation.containerName());
		}

		@Test
		@DisplayName("should return associated data name via getName")
		void shouldReturnAssociatedDataNameViaGetName() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, String.class, false, false
			);
			assertEquals(ASSOCIATED_DATA_NAME, mutation.getName());
		}

		@Test
		@DisplayName("should return collection conflict key with entity type")
		void shouldReturnCollectionConflictKeyWithEntityType() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, String.class, false, false
			);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"product", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
			assertEquals("product", ((CollectionConflictKey) keys.get(0)).entityType());
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToStringOutput() {
			final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final String result = mutation.toString();
			assertTrue(result.contains(ASSOCIATED_DATA_NAME));
			assertTrue(result.contains("description"));
			assertTrue(result.contains("deprecationNotice"));
			assertTrue(result.contains("localized=true"));
			assertTrue(result.contains("nullable=true"));
		}

		@Test
		@DisplayName("should be equal to mutation with same parameters")
		void shouldBeEqualToMutationWithSameParameters() {
			final CreateAssociatedDataSchemaMutation mutation1 = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final CreateAssociatedDataSchemaMutation mutation2 = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different parameters")
		void shouldNotBeEqualToMutationWithDifferentParameters() {
			final CreateAssociatedDataSchemaMutation mutation1 = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", null, String.class, true, true
			);
			final CreateAssociatedDataSchemaMutation mutation2 = new CreateAssociatedDataSchemaMutation(
				"differentName", "description", null, String.class, true, true
			);
			assertNotEquals(mutation1, mutation2);
		}
	}
}
