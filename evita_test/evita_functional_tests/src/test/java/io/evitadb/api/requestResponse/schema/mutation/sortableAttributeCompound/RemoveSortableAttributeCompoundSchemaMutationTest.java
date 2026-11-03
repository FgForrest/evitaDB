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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.ATTRIBUTE_COMPOUND_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createExistingAttributeCompoundSchema;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createMockedReferenceSchema;
import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Test verifies {@link RemoveSortableAttributeCompoundSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("RemoveSortableAttributeCompoundSchemaMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class RemoveSortableAttributeCompoundSchemaMutationTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("Should remove previous create mutation with same name")
		void shouldRemovePreviousCreateMutationWithSameName() {
			final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
			final CreateSortableAttributeCompoundSchemaMutation previousMutation = new CreateSortableAttributeCompoundSchemaMutation(
				ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[]{Scope.LIVE},
				new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
				new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
			);
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
			);
			assertNotNull(result);
			assertTrue(result.discarded());
			assertNull(result.origin());
			assertNotNull(result.current());
		}

		@Test
		@DisplayName("Should leave mutation intact when removal targets different compound")
		void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
			final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
			final CreateAttributeSchemaMutation previousMutation = new CreateAttributeSchemaMutation(
				"differentName", "description", "deprecationNotice",
				AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
				String.class, null, 0
			);
			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					previousMutation
				)
			);
		}
	}

	@Nested
	@DisplayName("Mutate compound schema")
	class MutateSchema {

		@Test
		@DisplayName("Should return null for removed compound")
		void shouldRemoveEntityAttribute() {
			final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
			final SortableAttributeCompoundSchemaContract attributeSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				Mockito.mock(ReferenceSchemaContract.class),
				createExistingAttributeCompoundSchema()
			);
			assertNull(attributeSchema);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("Should remove compound from entity schema")
		void shouldRemoveAttributeInEntity() {
			final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
				.thenReturn(of(createExistingAttributeCompoundSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			assertNotNull(newEntitySchema);
			assertEquals(2, newEntitySchema.version());
			assertFalse(newEntitySchema.getAttribute(ATTRIBUTE_COMPOUND_NAME).isPresent());
		}

		@Test
		@DisplayName("Should return unchanged entity schema when compound doesn't exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
			final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(entitySchema.getAttribute(ATTRIBUTE_COMPOUND_NAME))
				.thenReturn(empty());
			final EntitySchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			assertEquals(1, mutatedSchema.version());
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("Should remove compound from reference schema")
		void shouldRemoveAttributeInReference() {
			final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
				.thenReturn(of(createExistingAttributeCompoundSchema()));
			final ReferenceSchemaContract newReferenceSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), referenceSchema);
			assertNotNull(newReferenceSchema);
			assertFalse(newReferenceSchema.getAttribute(ATTRIBUTE_COMPOUND_NAME).isPresent());
		}

		/**
		 * Verifies removal of a sortable attribute compound preserves managed type metadata. An unmanaged entity type
		 * also exercises the managed group lookup independently of the entity lookup.
		 */
		@Tag(ENGINE)
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		@DisplayName("Should remove sortable attribute compound from reference with managed entity or group types")
		void shouldRemoveCompoundFromReferenceWithManagedTypes(boolean managedEntityType) {
			final TestPaths paths = createTestPaths("RemoveSortableAttributeCompoundSchemaMutationTest");
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog("testCatalog");
				evita.updateCatalog("testCatalog", session -> {
					if (managedEntityType) {
						session.defineEntitySchema("BrandType").updateVia(session);
					}
					session.defineEntitySchema("GroupType").updateVia(session);
					final EntitySchemaBuilder builder = session.defineEntitySchema("Product");
					final Consumer<ReferenceSchemaBuilder> configureReference = reference -> reference
						.withGroupTypeRelatedToEntity("GroupType")
						.withAttribute("priority", Long.class)
						.withAttribute("label", String.class)
						.withSortableAttributeCompound(
							ATTRIBUTE_COMPOUND_NAME, attributeElement("priority"), attributeElement("label")
						);
					if (managedEntityType) {
						builder.withReferenceToEntity(
							"brand", "BrandType", Cardinality.ZERO_OR_MORE, configureReference
						);
					} else {
						builder.withReferenceTo(
							"brand", "BrandType", Cardinality.ZERO_OR_MORE, configureReference
						);
					}
					builder.updateVia(session);
					final EntitySchemaContract schema = session.getEntitySchemaOrThrowException("Product");
					final ReferenceSchemaContract reference = schema.getReference("brand").orElseThrow();
					assertTrue(reference.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).isPresent());

					final EntitySchemaBuilder updatedBuilder = session.getEntitySchemaOrThrowException("Product")
						.openForWrite();
					if (managedEntityType) {
						updatedBuilder.withReferenceToEntity(
							"brand", "BrandType", Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs.withoutSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)
						);
					} else {
						updatedBuilder.withReferenceTo(
							"brand", "BrandType", Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs.withoutSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)
						);
					}
					updatedBuilder.updateVia(session);
					final ReferenceSchemaContract updatedReference = session.getEntitySchemaOrThrowException("Product")
						.getReference("brand")
						.orElseThrow();

					assertNotNull(updatedReference);
					assertTrue(updatedReference.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).isEmpty());
					assertEquals("BrandType", updatedReference.getReferencedEntityType());
					assertEquals(managedEntityType, updatedReference.isReferencedEntityTypeManaged());
					assertEquals("GroupType", updatedReference.getReferencedGroupType());
					assertTrue(updatedReference.isReferencedGroupTypeManaged());
					assertEquals(
						NamingConvention.generate("BrandType"),
						updatedReference.getEntityTypeNameVariants(session::getEntitySchemaOrThrowException)
					);
					assertEquals(
						NamingConvention.generate("GroupType"),
						updatedReference.getGroupTypeNameVariants(session::getEntitySchemaOrThrowException)
					);
				});
			} finally {
				cleanupTestPaths(paths);
			}
		}

	}

	@Test
	@DisplayName("Should return REMOVE operation")
	void shouldReturnRemoveOperation() {
		final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		assertEquals(Operation.REMOVE, mutation.operation());
	}

	@Test
	@DisplayName("Should provide human-readable toString")
	void shouldHaveToString() {
		final RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		final String result = mutation.toString();
		assertTrue(result.contains(ATTRIBUTE_COMPOUND_NAME));
	}

}
