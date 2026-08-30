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
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.SCHEMA;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link SetAttributeSchemaAcceleratedMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SetAttributeSchemaAcceleratedMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class SetAttributeSchemaAcceleratedMutationTest {

	/**
	 * The substring accelerator declared in the live scope - the payload most of these tests apply.
	 *
	 * @return a carrier array declaring `SUBSTRING_SEARCH` in {@link Scope#LIVE}
	 */
	@Nonnull
	private static ScopedAttributeFilterAccelerators[] liveSubstring() {
		return new ScopedAttributeFilterAccelerators[]{
			new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
		};
	}

	/**
	 * A `String` attribute made filterable in the requested scopes, i.e. one that has the filter index an accelerator
	 * needs.
	 *
	 * @param scopes the scopes the attribute should be filterable in
	 * @return the filterable `String` attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract filterableString(@Nonnull Scope... scopes) {
		return new SetAttributeSchemaFilterableMutation(ATTRIBUTE_NAME, scopes)
			.mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);
	}

	@Test
	@DisplayName("Should default the accelerators to none when the field is absent on the wire")
	void shouldDefaultAcceleratorsToNoneWhenAbsentOnTheWire() {
		// this is the shape a client that never sends the field - or an older WAL record - produces: it must
		// deserialize as "no acceleration", never as an error and never as a spurious accelerator
		final SetAttributeSchemaAcceleratedMutation mutation = new SetAttributeSchemaAcceleratedMutation(
			ATTRIBUTE_NAME, (ScopedAttributeFilterAccelerators[]) null
		);
		assertNotNull(mutation.getAcceleratorsInScopes());
		assertEquals(0, mutation.getAcceleratorsInScopes().length);
		assertFalse(mutation.isAccelerated());
	}

	@Test
	@DisplayName("Should override the accelerators of a previous mutation when names match")
	void shouldOverrideAcceleratorsOfPreviousMutationIfNamesMatch() {
		// combining collapses the pair into `this` and discards the earlier mutation entirely - the mutation is a
		// full statement of the axis, so last one wins is the whole merge rule
		final SetAttributeSchemaAcceleratedMutation accelerated =
			new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME, liveSubstring());
		final SetAttributeSchemaAcceleratedMutation earlier =
			new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME);

		final MutationCombinationResult<LocalEntitySchemaMutation> entityResult = accelerated.combineWith(
			Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), earlier
		);
		assertNotNull(entityResult);
		assertNull(entityResult.origin());
		assertEquals(1, entityResult.current().length);
		assertArrayEquals(
			liveSubstring(),
			((SetAttributeSchemaAcceleratedMutation) entityResult.current()[0]).getAcceleratorsInScopes()
		);

		final MutationCombinationResult<LocalCatalogSchemaMutation> catalogResult = accelerated.combineWith(
			Mockito.mock(CatalogSchemaContract.class), earlier
		);
		assertNotNull(catalogResult);
		assertEquals(1, catalogResult.current().length);
		assertArrayEquals(
			liveSubstring(),
			((SetAttributeSchemaAcceleratedMutation) catalogResult.current()[0]).getAcceleratorsInScopes()
		);
	}

	@Test
	@DisplayName("Should leave both mutations when the names don't match")
	void shouldLeaveBothMutationsWhenNamesDontMatch() {
		final SetAttributeSchemaAcceleratedMutation mutation =
			new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME, liveSubstring());
		final SetAttributeSchemaAcceleratedMutation existing =
			new SetAttributeSchemaAcceleratedMutation("differentName", liveSubstring());
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existing));
	}

	@Nested
	@DisplayName("the filter index the accelerator needs")
	class FilterIndexRequirement {

		@Test
		@DisplayName("Should accept an accelerator on a filterable attribute")
		void shouldAcceptAcceleratorOnFilterableAttribute() {
			final AttributeSchemaContract mutated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, filterableString(Scope.LIVE), AttributeSchemaContract.class);

			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), mutated.getAcceleratorsInScope(Scope.LIVE)
			);
			assertEquals(Set.of(), mutated.getAcceleratorsInScope(Scope.ARCHIVED));
			assertEquals(Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), mutated.getAccelerators());
		}

		@Test
		@DisplayName("Should accept an accelerator on a unique-only attribute")
		void shouldAcceptAcceleratorOnUniqueOnlyAttribute() {
			// `unique()` provides a filter index implicitly - this is exactly the case the old, filterability-bound
			// validation rejected, and the reason the rule now asks for an index rather than for a flag
			final AttributeSchemaContract unique = new SetAttributeSchemaUniqueMutation(
				ATTRIBUTE_NAME,
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(
						Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				}
			).mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);
			assertFalse(unique.isFilterableInScope(Scope.LIVE));
			assertTrue(unique.hasFilterIndexInScope(Scope.LIVE));

			final AttributeSchemaContract mutated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, unique, AttributeSchemaContract.class);

			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), mutated.getAcceleratorsInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("Should tolerate a scope with no filter index, because the state may still be intermediate")
		void shouldTolerateScopeWithoutFilterIndex() {
			// this mutation is applied incrementally and cannot tell an intermediate builder state from a final one,
			// so refusing here would make declaration order significant - and mutation combination can reorder
			// same-name mutations, so even a correctly written chain could be applied the wrong way round. The rule
			// is enforced once on the assembled schema instead, by AbstractAttributeSchemaBuilder#validate, which
			// AttributeSchemaBuilderTest#shouldRefuseAcceleratorLeftInScopeWithoutFilterIndex pins.
			final AttributeSchemaContract mutated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);

			assertFalse(mutated.hasFilterIndexInScope(Scope.LIVE));
			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), mutated.getAcceleratorsInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("Should accept an empty carrier for a scope with no filter index")
		void shouldAcceptEmptyCarrierForScopeWithoutFilterIndex() {
			// an empty carrier declares no acceleration at all, so there is nothing to orphan
			final AttributeSchemaContract mutated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, new ScopedAttributeFilterAccelerators(Scope.ARCHIVED)
			).mutate(null, filterableString(Scope.LIVE), AttributeSchemaContract.class);
			assertTrue(mutated.getAcceleratorsInScopes().isEmpty());
		}
	}

	@Nested
	@DisplayName("accelerators against the attribute type")
	class TypeApplicability {

		@Test
		@DisplayName("Should refuse the substring accelerator on an attribute that is not a String")
		void shouldRefuseSubstringAcceleratorOnNonStringAttribute() {
			final AttributeSchemaContract filterableInteger = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}
			).mutate(null, createExistingAttributeSchema(), AttributeSchemaContract.class);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME, liveSubstring())
					.mutate(null, filterableInteger, AttributeSchemaContract.class)
			);
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			assertTrue(exception.getMessage().contains("String"));
		}

		@Test
		@DisplayName("Should accept the substring accelerator on a String array attribute")
		void shouldAcceptSubstringAcceleratorOnStringArrayAttribute() {
			final AttributeSchemaContract filterableArray = new SetAttributeSchemaFilterableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}
			).mutate(null, createAttributeSchemaOfType(String[].class), AttributeSchemaContract.class);

			final AttributeSchemaContract mutated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, filterableArray, AttributeSchemaContract.class);

			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), mutated.getAcceleratorsInScope(Scope.LIVE)
			);
		}
	}

	@Nested
	@DisplayName("accelerators and schema identity")
	class SchemaIdentity {

		@Test
		@DisplayName("Should keep the schema untouched when the accelerators do not change")
		void shouldKeepSchemaUntouchedWhenNothingChanges() {
			final AttributeSchemaContract accelerated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, filterableString(Scope.LIVE), AttributeSchemaContract.class);

			assertSame(
				accelerated,
				new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME, liveSubstring())
					.mutate(null, accelerated, AttributeSchemaContract.class)
			);
		}

		@Test
		@DisplayName("Should replace the schema when only the accelerators differ")
		void shouldReplaceSchemaWhenOnlyAcceleratorsDiffer() {
			final AttributeSchemaContract plain = filterableString(Scope.LIVE);
			final AttributeSchemaContract accelerated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, plain, AttributeSchemaContract.class);

			assertNotSame(plain, accelerated);
			assertEquals(plain.getFilterableInScopes(), accelerated.getFilterableInScopes());
			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), accelerated.getAcceleratorsInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("Should withdraw every accelerator when the mutation names none")
		void shouldWithdrawEveryAcceleratorWhenMutationNamesNone() {
			final AttributeSchemaContract accelerated = new SetAttributeSchemaAcceleratedMutation(
				ATTRIBUTE_NAME, liveSubstring()
			).mutate(null, filterableString(Scope.LIVE), AttributeSchemaContract.class);

			final AttributeSchemaContract withdrawn = new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME)
				.mutate(null, accelerated, AttributeSchemaContract.class);

			assertTrue(withdrawn.getAcceleratorsInScopes().isEmpty());
			assertTrue(withdrawn.isFilterableInScope(Scope.LIVE));
		}
	}

	@Nested
	@DisplayName("accelerators on a reference attribute")
	class OnReferenceAttribute {

		@Test
		@DisplayName("Should refuse the substring accelerator on a reference attribute")
		void shouldRefuseSubstringAcceleratorOnReferenceAttribute() {
			// the index serving the accelerator is maintained on the entity's global index and never sees reference
			// attribute values, so a declaration here would buy the user nothing but memory and ceremony
			final SetAttributeSchemaAcceleratedMutation mutation =
				new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME, liveSubstring());
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
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			assertTrue(exception.getMessage().contains("referenceName"));
		}

		@Test
		@DisplayName("Should refuse the substring accelerator on a reference attribute even when checks are skipped")
		void shouldRefuseSubstringAcceleratorOnReferenceAttributeEvenWhenChecksSkipped() {
			// the refusal sits outside the consistency-check guard on purpose: it is not a question about the
			// reference's current state, so a SKIP caller must not be able to write a declaration nothing will
			// ever honour
			final SetAttributeSchemaAcceleratedMutation mutation =
				new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME, liveSubstring());
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getName()).thenReturn("product");
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(entitySchema, referenceSchema, ConsistencyChecks.SKIP)
			);
		}
	}

}
