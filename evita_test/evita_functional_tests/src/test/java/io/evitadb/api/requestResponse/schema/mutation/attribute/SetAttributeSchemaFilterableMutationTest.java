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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.Tag;

import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;

/**
 * Test for {@link SetAttributeSchemaFilterableMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("SetAttributeSchemaFilterableMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class SetAttributeSchemaFilterableMutationTest {

	@Test
	@DisplayName("Should override filterable of previous global attribute mutation when names match")
	void shouldOverrideFilterableOfPreviousGlobalAttributeMutationIfNamesMatch() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.values()
		);
		final SetAttributeSchemaFilterableMutation existingMutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.NO_SCOPE);
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaFilterableMutation.class, result.current()[0]);
		assertTrue(((SetAttributeSchemaFilterableMutation) result.current()[0]).isFilterable());
		assertArrayEquals(
			Scope.values(), ((SetAttributeSchemaFilterableMutation) result.current()[0]).getFilterableInScopes());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new global attribute mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final SetAttributeSchemaFilterableMutation existingMutation = new SetAttributeSchemaFilterableMutation(
			"differentName", Scope.NO_SCOPE);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should override filterable of previous mutation when names match")
	void shouldOverrideFilterableOfPreviousMutationIfNamesMatch() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final SetAttributeSchemaFilterableMutation existingMutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.NO_SCOPE);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaFilterableMutation.class, result.current()[0]);
		assertTrue(((SetAttributeSchemaFilterableMutation) result.current()[0]).isFilterable());
		assertArrayEquals(
			Scope.DEFAULT_SCOPES, ((SetAttributeSchemaFilterableMutation) result.current()[0]).getFilterableInScopes());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final SetAttributeSchemaFilterableMutation existingMutation = new SetAttributeSchemaFilterableMutation(
			"differentName", Scope.NO_SCOPE);
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
				existingMutation
			));
	}

	@Test
	@DisplayName("Should mutate global attribute schema")
	void shouldMutateGlobalAttributeSchema() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(),
			GlobalAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isFilterable());
		assertArrayEquals(Scope.DEFAULT_SCOPES, mutatedSchema.getFilterableInScopes().toArray(Scope[]::new));
	}

	@Test
	@DisplayName("Should mutate entity attribute schema")
	void shouldMutateEntityAttributeSchema() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isFilterable());
		assertArrayEquals(Scope.DEFAULT_SCOPES, mutatedSchema.getFilterableInScopes().toArray(Scope[]::new));
	}

	@Test
	@DisplayName("Should mutate catalog schema")
	void shouldMutateCatalogSchema() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas mutationResult = mutation.mutate(
			catalogSchema
		);
		assertEquals(0, mutationResult.entitySchemaMutations().length);
		final CatalogSchemaContract newCatalogSchema = mutationResult.updatedCatalogSchema();
		assertEquals(2, newCatalogSchema.version());
		final GlobalAttributeSchemaContract newSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newSchema.isFilterable());
		assertArrayEquals(Scope.DEFAULT_SCOPES, newSchema.getFilterableInScopes().toArray(Scope[]::new));
	}

	@Test
	@DisplayName("Should mutate entity schema")
	void shouldMutateEntitySchema() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract newAttributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newAttributeSchema.isFilterable());
		assertArrayEquals(Scope.DEFAULT_SCOPES, newAttributeSchema.getFilterableInScopes().toArray(Scope[]::new));
	}

	@Test
	@DisplayName("Should mutate reference schema")
	void shouldMutateReferenceSchema() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.isIndexed()).thenReturn(true);
		Mockito.when(referenceSchema.isIndexedInScope(Scope.LIVE)).thenReturn(true);
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			referenceSchema
		);
		assertNotNull(mutatedSchema);
		final AttributeSchemaContract newAttributeSchema = mutatedSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newAttributeSchema.isFilterable());
		assertArrayEquals(Scope.DEFAULT_SCOPES, newAttributeSchema.getFilterableInScopes().toArray(Scope[]::new));
	}

	@Test
	@DisplayName("Should fail to mutate reference schema when not indexed")
	void shouldFailMutateReferenceSchemaIfNotIndexed() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingAttributeSchema()));
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					referenceSchema
				);
			}
		);
	}

	@Test
	@DisplayName("Should throw exception when mutating entity schema with non-existing attribute")
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

	@Test
	@DisplayName("Should keep the later mutation's capabilities when combining with a plain filterable one")
	void shouldKeepLaterCapabilitiesWhenCombiningWithPlainFilterableMutation() {
		// combining collapses the pair into `this` and discards the earlier mutation entirely, so a capability lost
		// here would strip a declared acceleration from the resulting schema with nothing anywhere failing. The
		// builder reaches both overrides with capability-carrying mutations, so both are asserted.
		final SetAttributeSchemaFilterableMutation accelerated = SetAttributeSchemaFilterableMutation.fromCapabilities(
			ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
		);
		final SetAttributeSchemaFilterableMutation plainEarlier = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);
		final ScopedFilterCapabilities[] expected = {
			new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
		};

		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createStringEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> entityResult = accelerated.combineWith(
			Mockito.mock(CatalogSchemaContract.class), entitySchema, plainEarlier
		);
		assertNotNull(entityResult);
		assertNull(entityResult.origin());
		assertEquals(1, entityResult.current().length);
		assertArrayEquals(
			expected,
			((SetAttributeSchemaFilterableMutation) entityResult.current()[0]).getFilterCapabilitiesInScopes()
		);

		final MutationCombinationResult<LocalCatalogSchemaMutation> catalogResult = accelerated.combineWith(
			Mockito.mock(CatalogSchemaContract.class), plainEarlier
		);
		assertNotNull(catalogResult);
		assertEquals(1, catalogResult.current().length);
		assertArrayEquals(
			expected,
			((SetAttributeSchemaFilterableMutation) catalogResult.current()[0]).getFilterCapabilitiesInScopes()
		);
	}

	@Nested
	@DisplayName("filter index capabilities on the wire")
	class WireShape {

		@Test
		@DisplayName("Should default the filter capabilities to none when the two-argument constructor is used")
		void shouldDefaultFilterCapabilitiesToNoneForTwoArgumentConstructor() {
			final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
			);
			assertNotNull(mutation.getFilterCapabilitiesInScopes());
			assertEquals(0, mutation.getFilterCapabilitiesInScopes().length);
		}

		@Test
		@DisplayName("Should default the filter capabilities to none when the field is absent on the wire")
		void shouldDefaultFilterCapabilitiesToNoneWhenAbsentOnTheWire() {
			// this is the shape an older client - or an older WAL record - produces: the capability field simply is
			// not there, and must deserialize as "plain filterable", never as an error and never as a spurious
			// capability
			final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES, null
			);
			assertNotNull(mutation.getFilterCapabilitiesInScopes());
			assertEquals(0, mutation.getFilterCapabilitiesInScopes().length);
			assertEquals(new SetAttributeSchemaFilterableMutation(ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES), mutation);
		}

		@Test
		@DisplayName("Should derive the filterable scopes from the carriers in the capability factory")
		void shouldDeriveFilterableScopesFromCarriers() {
			final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME,
				new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING),
				new ScopedFilterCapabilities(Scope.ARCHIVED)
			);
			assertTrue(mutation.isFilterable());
			assertArrayEquals(new Scope[]{Scope.LIVE, Scope.ARCHIVED}, mutation.getFilterableInScopes());
		}

		@Test
		@DisplayName("Should refuse a capability declared in a scope the mutation does not make filterable")
		void shouldRefuseCapabilityInScopeThatIsNotMadeFilterable() {
			// the builder cannot express this - it folds the capabilities into the `filterable()` call - but a
			// mutation assembled field by field over gRPC, REST or GraphQL can, and silently dropping it would let
			// the client believe it had enabled an acceleration it will never get
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> new SetAttributeSchemaFilterableMutation(
					ATTRIBUTE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFilterCapabilities[]{
						new ScopedFilterCapabilities(Scope.ARCHIVED, FilterIndexCapability.SUBSTRING)
					}
				)
			);
			assertTrue(exception.getMessage().contains(Scope.ARCHIVED.name()));
		}

		@Test
		@DisplayName("Should allow an empty carrier for a scope the mutation does not make filterable")
		void shouldAllowEmptyCarrierForScopeThatIsNotMadeFilterable() {
			// an empty carrier declares no acceleration at all, so there is nothing to orphan - refusing it would
			// break `nonFilterableInScope`, which legitimately emits carriers with zero capabilities
			final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME,
				new Scope[]{Scope.LIVE},
				new ScopedFilterCapabilities[]{new ScopedFilterCapabilities(Scope.ARCHIVED)}
			);
			assertTrue(mutation.isFilterable());
		}
	}

	@Nested
	@DisplayName("filter index capabilities against the attribute type")
	class TypeApplicability {

		@Test
		@DisplayName("Should apply the substring capability to a String attribute")
		void shouldApplySubstringCapabilityToStringAttribute() {
			final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
			final AttributeSchemaContract mutated = mutation.mutate(
				null, createStringAttributeSchema(), AttributeSchemaContract.class
			);
			assertTrue(mutated.isFilterableInScope(Scope.LIVE));
			assertEquals(Set.of(FilterIndexCapability.SUBSTRING), mutated.getFilterCapabilitiesInScope(Scope.LIVE));
			assertEquals(Set.of(), mutated.getFilterCapabilitiesInScope(Scope.ARCHIVED));
			assertEquals(Set.of(FilterIndexCapability.SUBSTRING), mutated.getFilterCapabilities());
		}

		@Test
		@DisplayName("Should leave the filter capabilities empty for a plain filterable mutation")
		void shouldLeaveFilterCapabilitiesEmptyForPlainFilterableMutation() {
			final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
			);
			final AttributeSchemaContract mutated = mutation.mutate(
				null, createStringAttributeSchema(), AttributeSchemaContract.class
			);
			assertTrue(mutated.isFilterable());
			assertTrue(mutated.getFilterCapabilities().isEmpty());
			assertTrue(mutated.getFilterCapabilitiesInScopes().isEmpty());
		}

		@Test
		@DisplayName("Should refuse the substring capability on an attribute that is not a String")
		void shouldRefuseSubstringCapabilityOnNonStringAttribute() {
			final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
			// createExistingAttributeSchema() declares the attribute as Integer
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(null, createExistingAttributeSchema(), AttributeSchemaContract.class)
			);
			assertTrue(exception.getMessage().contains("SUBSTRING"));
			assertTrue(exception.getMessage().contains("String"));
		}

		@Test
		@DisplayName("Should accept the substring capability on a String array attribute")
		void shouldAcceptSubstringCapabilityOnStringArrayAttribute() {
			final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
			final AttributeSchemaContract mutated = mutation.mutate(
				null, createAttributeSchemaOfType(String[].class), AttributeSchemaContract.class
			);
			assertEquals(Set.of(FilterIndexCapability.SUBSTRING), mutated.getFilterCapabilitiesInScope(Scope.LIVE));
		}
	}

	@Nested
	@DisplayName("filter index capabilities and schema identity")
	class SchemaIdentity {

		@Test
		@DisplayName("Should keep the schema untouched when the mutation changes neither scopes nor capabilities")
		void shouldKeepSchemaUntouchedWhenNothingChanges() {
			final AttributeSchemaContract alreadyAccelerated = SetAttributeSchemaFilterableMutation
				.fromCapabilities(
					ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				)
				.mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);
			final SetAttributeSchemaFilterableMutation sameAgain = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
			assertSame(alreadyAccelerated, sameAgain.mutate(null, alreadyAccelerated, AttributeSchemaContract.class));
		}

		@Test
		@DisplayName("Should replace the schema when only the capabilities differ")
		void shouldReplaceSchemaWhenOnlyCapabilitiesDiffer() {
			final AttributeSchemaContract plain = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}
			).mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);
			final AttributeSchemaContract accelerated = SetAttributeSchemaFilterableMutation
				.fromCapabilities(
					ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				)
				.mutate(null, plain, AttributeSchemaContract.class);
			assertNotSame(plain, accelerated);
			assertEquals(plain.getFilterableInScopes(), accelerated.getFilterableInScopes());
			assertEquals(Set.of(FilterIndexCapability.SUBSTRING), accelerated.getFilterCapabilitiesInScope(Scope.LIVE));
		}
	}

	@Nested
	@DisplayName("filter index capabilities on a reference attribute")
	class OnReferenceAttribute {

		@Test
		@DisplayName("Should refuse the substring capability on a reference attribute")
		void shouldRefuseSubstringCapabilityOnReferenceAttribute() {
			// the index serving the capability is maintained on the entity's global index and never sees reference
			// attribute values, so a declaration here would buy the user nothing but memory and ceremony
			final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			Mockito.when(referenceSchema.isIndexedInScope(Scope.LIVE)).thenReturn(true);
			Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME))
				.thenReturn(of(createExistingAttributeSchema()));
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getName()).thenReturn("product");
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(entitySchema, referenceSchema)
			);
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
			assertTrue(exception.getMessage().contains("referenceName"));
		}

		@Test
		@DisplayName("Should refuse the substring capability on a reference attribute even when checks are skipped")
		void shouldRefuseSubstringCapabilityOnReferenceAttributeEvenWhenChecksSkipped() {
			// the refusal sits outside the consistency-check guard on purpose: it is not a question about the
			// reference's current state, so a SKIP caller must not be able to write a declaration nothing will
			// ever honour
			final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
				ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getName()).thenReturn("product");
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(entitySchema, referenceSchema, ConsistencyChecks.SKIP)
			);
		}

		@Test
		@DisplayName("Should still allow a plainly filterable reference attribute")
		void shouldStillAllowPlainlyFilterableReferenceAttribute() {
			// the refusal must be about capabilities alone - plain filterability on a reference attribute is untouched
			final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
			);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			Mockito.when(referenceSchema.isIndexedInScope(Scope.LIVE)).thenReturn(true);
			Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME))
				.thenReturn(of(createExistingAttributeSchema()));
			final ReferenceSchemaContract mutated = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class), referenceSchema
			);
			assertNotNull(mutated);
			assertTrue(mutated.getAttribute(ATTRIBUTE_NAME).orElseThrow().isFilterable());
		}
	}

}
