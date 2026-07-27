/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.ATTRIBUTE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingAttributeSchema;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingEntityAttributeSchema;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingGlobalAttributeSchema;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Test for {@link SetAttributeSchemaConflictResolutionOverrideMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("SetAttributeSchemaConflictResolutionOverrideMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class SetAttributeSchemaConflictResolutionOverrideMutationTest {

	@Test
	@DisplayName("Should carry the new override into the mutated global attribute schema")
	void shouldMutateGlobalAttributeSchema() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);

		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(),
			GlobalAttributeSchemaContract.class
		);

		assertNotNull(mutatedSchema);
		assertEquals(ConflictResolutionOverride.GRANULAR, mutatedSchema.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("Should carry the new override into the mutated entity attribute schema")
	void shouldMutateEntityAttributeSchema() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);

		final AttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);

		assertNotNull(mutatedSchema);
		assertEquals(ConflictResolutionOverride.GRANULAR, mutatedSchema.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("Should carry the new override into the mutated plain attribute schema")
	void shouldMutatePlainAttributeSchema() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);

		final AttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingAttributeSchema(),
			AttributeSchemaContract.class
		);

		assertNotNull(mutatedSchema);
		assertEquals(ConflictResolutionOverride.GRANULAR, mutatedSchema.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("Should return the same schema instance when the override is unchanged")
	void shouldReturnSameSchemaInstanceWhenOverrideUnchanged() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.INHERITED
			);
		// the existing schema already carries the INHERITED override
		final AttributeSchemaContract existingSchema = createExistingAttributeSchema();

		final AttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), existingSchema, AttributeSchemaContract.class
		);

		assertSame(existingSchema, mutatedSchema);
	}

	@Test
	@DisplayName("Should override previous catalog mutation with the newer one when names match")
	void shouldOverridePreviousCatalogMutationWhenNamesMatch() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaConflictResolutionOverrideMutation existingMutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.ENTITY
			);

		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), existingMutation
		);

		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaConflictResolutionOverrideMutation.class, result.current()[0]);
		assertEquals(
			ConflictResolutionOverride.GRANULAR,
			((SetAttributeSchemaConflictResolutionOverrideMutation) result.current()[0]).getConflictResolutionOverride()
		);
	}

	@Test
	@DisplayName("Should leave both catalog mutations when names do not match")
	void shouldLeaveBothCatalogMutationsWhenNamesDoNotMatch() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaConflictResolutionOverrideMutation existingMutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				"differentName", ConflictResolutionOverride.ENTITY
			);

		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should leave both catalog mutations when the existing mutation is of an unrelated type")
	void shouldLeaveBothCatalogMutationsWhenExistingMutationIsUnrelatedType() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaNullableMutation existingMutation =
			new SetAttributeSchemaNullableMutation(ATTRIBUTE_NAME, true);

		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should override previous entity mutation with the newer one when names match")
	void shouldOverridePreviousEntityMutationWhenNamesMatch() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaConflictResolutionOverrideMutation existingMutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.ENTITY
			);

		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
		);

		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaConflictResolutionOverrideMutation.class, result.current()[0]);
		assertEquals(
			ConflictResolutionOverride.GRANULAR,
			((SetAttributeSchemaConflictResolutionOverrideMutation) result.current()[0]).getConflictResolutionOverride()
		);
	}

	@Test
	@DisplayName("Should leave both entity mutations when names do not match")
	void shouldLeaveBothEntityMutationsWhenNamesDoNotMatch() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaConflictResolutionOverrideMutation existingMutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				"differentName", ConflictResolutionOverride.ENTITY
			);

		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
			)
		);
	}

	@Test
	@DisplayName("Should leave both entity mutations when the existing mutation is of an unrelated type")
	void shouldLeaveBothEntityMutationsWhenExistingMutationIsUnrelatedType() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaNullableMutation existingMutation =
			new SetAttributeSchemaNullableMutation(ATTRIBUTE_NAME, true);

		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
			)
		);
	}

	@Test
	@DisplayName("Should mutate catalog schema updating the global attribute override")
	void shouldMutateCatalogSchema() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		Mockito.when(catalogSchema.version()).thenReturn(1);

		final CatalogSchemaWithImpactOnEntitySchemas mutationResult = mutation.mutate(catalogSchema);

		assertEquals(0, mutationResult.entitySchemaMutations().length);
		final CatalogSchemaContract newCatalogSchema = mutationResult.updatedCatalogSchema();
		assertEquals(2, newCatalogSchema.version());
		final GlobalAttributeSchemaContract newSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(ConflictResolutionOverride.GRANULAR, newSchema.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("Should not be equal to a mutation with a different override value")
	void shouldNotBeEqualToMutationWithDifferentOverride() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation1 =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation2 =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.ENTITY
			);

		assertNotEquals(mutation1, mutation2);
	}

	@Test
	@DisplayName("Should not be equal to a mutation with a different attribute name")
	void shouldNotBeEqualToMutationWithDifferentName() {
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation1 =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.GRANULAR
			);
		final SetAttributeSchemaConflictResolutionOverrideMutation mutation2 =
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				"differentName", ConflictResolutionOverride.GRANULAR
			);

		assertNotEquals(mutation1, mutation2);
	}

}
