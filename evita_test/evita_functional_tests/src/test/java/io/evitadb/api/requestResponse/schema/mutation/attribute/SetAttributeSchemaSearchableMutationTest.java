/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link SetAttributeSchemaSearchableMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SetAttributeSchemaSearchableMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FULLTEXT)
class SetAttributeSchemaSearchableMutationTest {

	/**
	 * A `String` attribute that is filterable and substring-accelerated in the live scope and localized - the axes a
	 * searchability change must carry through untouched.
	 *
	 * @return the filterable, accelerated, localized `String` attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract filterableAcceleratedLocalizedString() {
		final AttributeSchemaContract filterable = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}
		).mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);
		final AttributeSchemaContract accelerated = new SetAttributeSchemaAcceleratedMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
		).mutate(null, filterable, AttributeSchemaContract.class);
		return new SetAttributeSchemaLocalizedMutation(ATTRIBUTE_NAME, true)
			.mutate(null, accelerated, AttributeSchemaContract.class);
	}

	/**
	 * A real (not mocked) reference indexed for filtering in the live scope only, with one `String` attribute,
	 * created through the create mutation rather than a builder - the path that skips the builder's own validation.
	 *
	 * @param localized          whether the attribute is localized
	 * @param searchableInScopes the scopes the attribute is searchable in
	 * @return the reference schema holding the attribute
	 */
	@Nonnull
	private static ReferenceSchemaContract createLiveIndexedReferenceAttribute(
		boolean localized, @Nonnull Scope... searchableInScopes
	) {
		final ReferenceSchema brand = ReferenceSchema._internalBuild(
			"brand", "brand", false, Cardinality.ZERO_OR_MORE, null, false,
			new ScopedReferenceIndexType[]{new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)},
			null
		);
		final CreateAttributeSchemaMutation create = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, null, null, null,
			Scope.NO_SCOPE, null, searchableInScopes, Scope.NO_SCOPE,
			localized, false, false, String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);
		final ReferenceSchemaContract result = create.mutate(EntitySchema._internalBuild("product"), brand);
		assertNotNull(result);
		return result;
	}

	@Test
	@DisplayName("Should default the scopes to none when the field is absent on the wire")
	void shouldDefaultScopesToNoneWhenAbsentOnTheWire() {
		final SetAttributeSchemaSearchableMutation mutation =
			new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, (Scope[]) null);
		assertArrayEquals(Scope.NO_SCOPE, mutation.getSearchableInScopes());
		assertFalse(mutation.isSearchable());
	}

	@Test
	@DisplayName("Should translate the boolean form to the default scope or to no scope")
	void shouldTranslateBooleanFormToDefaultScopeOrNoScope() {
		final SetAttributeSchemaSearchableMutation searchable =
			new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true);
		assertArrayEquals(Scope.DEFAULT_SCOPES, searchable.getSearchableInScopes());
		assertTrue(searchable.isSearchable());

		final SetAttributeSchemaSearchableMutation notSearchable =
			new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, false);
		assertArrayEquals(Scope.NO_SCOPE, notSearchable.getSearchableInScopes());
		assertFalse(notSearchable.isSearchable());
	}

	@Nested
	@DisplayName("combination")
	class Combination {

		@Test
		@DisplayName("Should override the previous mutation of the same attribute")
		void shouldOverridePreviousMutationOfSameAttribute() {
			// the mutation is a full statement of the axis, so last one wins is the whole merge rule
			final SetAttributeSchemaSearchableMutation mutation =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.values());
			final SetAttributeSchemaSearchableMutation earlier =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, false);

			final MutationCombinationResult<LocalEntitySchemaMutation> entityResult = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), earlier
			);
			assertNotNull(entityResult);
			assertNull(entityResult.origin());
			assertEquals(1, entityResult.current().length);
			assertSame(mutation, entityResult.current()[0]);

			final MutationCombinationResult<LocalCatalogSchemaMutation> catalogResult = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), earlier
			);
			assertNotNull(catalogResult);
			assertNull(catalogResult.origin());
			assertSame(mutation, catalogResult.current()[0]);
		}

		@Test
		@DisplayName("Should keep the analyzer and the length pivot of the earlier mutation unless restated")
		void shouldKeepEarlierAnalyzerAndPivotUnlessRestated() {
			final SetAttributeSchemaSearchableMutation earlier =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.NO_SCOPE, "html-text", 180, null);
			final SetAttributeSchemaSearchableMutation mutation =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.values(), null, 40, null);

			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), earlier
			);

			assertNotNull(result);
			assertNull(result.origin());
			assertEquals(
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.values(), "html-text", 40, null),
				result.current()[0]
			);
		}

		@Test
		@DisplayName("Should leave both mutations when the names don't match")
		void shouldLeaveBothMutationsWhenNamesDontMatch() {
			final SetAttributeSchemaSearchableMutation mutation =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true);
			final SetAttributeSchemaSearchableMutation existing =
				new SetAttributeSchemaSearchableMutation("differentName", true);
			assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existing));
			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existing
				)
			);
		}

		@Test
		@DisplayName("Should leave a different mutation of the same attribute alone")
		void shouldLeaveDifferentMutationOfSameAttributeAlone() {
			// searchability and filterability are independent axes - one must never swallow the other
			final SetAttributeSchemaSearchableMutation mutation =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true);
			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					new SetAttributeSchemaFilterableMutation(ATTRIBUTE_NAME, true)
				)
			);
		}

		@Test
		@DisplayName("Should let a type change be swapped ahead of it, as with the other index flags")
		void shouldLetTypeChangeBeSwappedAheadOfIt() {
			final SetAttributeSchemaSearchableMutation searchable =
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true);
			final ModifyAttributeSchemaTypeMutation typeChange =
				new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, String[].class, 0);

			final MutationCombinationResult<LocalEntitySchemaMutation> entityResult = typeChange.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), searchable
			);
			assertNotNull(entityResult);
			assertSame(typeChange, entityResult.origin());
			assertSame(searchable, entityResult.current()[0]);

			final MutationCombinationResult<LocalCatalogSchemaMutation> catalogResult = typeChange.combineWith(
				Mockito.mock(CatalogSchemaContract.class), searchable
			);
			assertNotNull(catalogResult);
			assertSame(typeChange, catalogResult.origin());
			assertSame(searchable, catalogResult.current()[0]);
		}
	}

	@Nested
	@DisplayName("the attribute schema")
	class AttributeSchema {

		@Test
		@DisplayName("Should make the attribute searchable and carry every other axis through")
		void shouldMakeAttributeSearchableAndCarryOtherAxesThrough() {
			final AttributeSchemaContract original = filterableAcceleratedLocalizedString();
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}
			).mutate(null, original, AttributeSchemaContract.class);

			assertTrue(mutated.isSearchable());
			assertTrue(mutated.isSearchableInScope(Scope.LIVE));
			assertFalse(mutated.isSearchableInScope(Scope.ARCHIVED));
			assertEquals(EnumSet.of(Scope.LIVE), mutated.getSearchableInScopes());
			assertEquals(original.getFilterableInScopes(), mutated.getFilterableInScopes());
			assertEquals(original.getAcceleratorsInScopes(), mutated.getAcceleratorsInScopes());
			assertTrue(mutated.isLocalized());
		}

		@Test
		@DisplayName("Should be searchable in any scope when searchable in the archived scope only")
		void shouldBeSearchableInAnyScopeWhenSearchableInArchivedOnly() {
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.ARCHIVED}
			).mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class);

			assertFalse(mutated.isSearchable());
			assertTrue(mutated.isSearchableInAnyScope());
		}

		@Test
		@DisplayName("Should keep the very same schema when the scopes do not change")
		void shouldKeepSameSchemaWhenScopesDoNotChange() {
			final AttributeSchemaContract searchable = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class);
			assertSame(
				searchable,
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
					.mutate(null, searchable, AttributeSchemaContract.class)
			);
		}

		@Test
		@DisplayName("Should withdraw searchability from every scope when the mutation names none")
		void shouldWithdrawSearchabilityWhenMutationNamesNone() {
			final AttributeSchemaContract searchable = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, Scope.values()
			).mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class);
			final AttributeSchemaContract withdrawn = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, false)
				.mutate(null, searchable, AttributeSchemaContract.class);

			assertTrue(withdrawn.getSearchableInScopes().isEmpty());
			assertFalse(withdrawn.isSearchableInAnyScope());
			assertTrue(withdrawn.isFilterableInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("Should set the analyzer and the length pivot, and keep each when the mutation leaves it null")
		void shouldSetAnalyzerAndPivotAndKeepThemWhenNull() {
			final AttributeSchemaContract original = filterableAcceleratedLocalizedString();
			assertEquals(FulltextAnalyzerSchemaContract.TEXT, original.getAnalyzer());
			assertEquals(AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, original.getLengthPivot());

			final AttributeSchemaContract configured = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}, "html-text", 180, null
			).mutate(null, original, AttributeSchemaContract.class);
			assertEquals("html-text", configured.getAnalyzer());
			assertEquals(180, configured.getLengthPivot());

			final AttributeSchemaContract rescoped = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, Scope.values()
			).mutate(null, configured, AttributeSchemaContract.class);
			assertEquals(EnumSet.allOf(Scope.class), rescoped.getSearchableInScopes());
			assertEquals("html-text", rescoped.getAnalyzer());
			assertEquals(180, rescoped.getLengthPivot());
			assertSame(
				rescoped,
				new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.values(), "html-text", null, null)
					.mutate(null, rescoped, AttributeSchemaContract.class)
			);
		}

		@Test
		@DisplayName("Should keep an entity attribute an entity attribute")
		void shouldKeepEntityAttributeAnEntityAttribute() {
			final EntityAttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, true
			).mutate(null, createStringEntityAttributeSchema(), EntityAttributeSchemaContract.class);

			assertInstanceOf(EntityAttributeSchema.class, mutated);
			assertTrue(mutated.isSearchable());
		}

		@Test
		@DisplayName("Should keep a global attribute a global attribute")
		void shouldKeepGlobalAttributeAGlobalAttribute() {
			final GlobalAttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, true
			).mutate(null, createStringGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);

			assertInstanceOf(GlobalAttributeSchema.class, mutated);
			assertTrue(mutated.isSearchable());
		}

		@Test
		@DisplayName("Should mention the searchable scopes in the schema description")
		void shouldMentionSearchableScopesInSchemaDescription() {
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class);
			assertTrue(mutated.toString().contains("searchable=(in scopes: LIVE)"), mutated.toString());
		}
	}

	/**
	 * Every other attribute mutation that rebuilds the attribute schema, each changing something so that the rebuild
	 * actually happens. Each of them reconstructs the schema field by field, and one that forgot the searchable
	 * scopes would compile and silently drop them.
	 *
	 * @return pairs of a readable label and the mutation
	 */
	@Nonnull
	static Stream<Arguments> otherAttributeMutations() {
		return Stream.of(
			Arguments.of("filterable", new SetAttributeSchemaFilterableMutation(ATTRIBUTE_NAME, true)),
			Arguments.of("sortable", new SetAttributeSchemaSortableMutation(ATTRIBUTE_NAME, true)),
			Arguments.of(
				"unique",
				new SetAttributeSchemaUniqueMutation(ATTRIBUTE_NAME, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			),
			Arguments.of("nullable", new SetAttributeSchemaNullableMutation(ATTRIBUTE_NAME, true)),
			Arguments.of("localized", new SetAttributeSchemaLocalizedMutation(ATTRIBUTE_NAME, false)),
			Arguments.of("representative", new SetAttributeSchemaRepresentativeMutation(ATTRIBUTE_NAME, true)),
			Arguments.of("description", new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_NAME, "changed")),
			Arguments.of(
				"deprecation notice", new ModifyAttributeSchemaDeprecationNoticeMutation(ATTRIBUTE_NAME, "changed")
			),
			Arguments.of("default value", new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_NAME, "changed")),
			Arguments.of("name", new ModifyAttributeSchemaNameMutation(ATTRIBUTE_NAME, "renamed")),
			Arguments.of("type", new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, String[].class, 0)),
			Arguments.of(
				"conflict resolution override",
				new SetAttributeSchemaConflictResolutionOverrideMutation(
					ATTRIBUTE_NAME, ConflictResolutionOverride.ENTITY
				)
			),
			Arguments.of(
				"accelerators",
				new SetAttributeSchemaAcceleratedMutation(
					ATTRIBUTE_NAME,
					new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
				)
			),
			Arguments.of("normalization", new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase()))
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("otherAttributeMutations")
	@DisplayName("Should keep the searchable scopes of an attribute through every other attribute mutation")
	void shouldKeepSearchableScopesThroughOtherAttributeMutation(
		@Nonnull String label, @Nonnull AttributeSchemaMutation mutation
	) {
		final SetAttributeSchemaSearchableMutation everywhere =
			new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.values());
		final Set<Scope> allScopes = EnumSet.allOf(Scope.class);

		final AttributeSchemaContract plain = mutation.mutate(
			null,
			everywhere.mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class),
			AttributeSchemaContract.class
		);
		assertEquals(allScopes, plain.getSearchableInScopes(), label + " dropped it from a reference attribute");

		final EntityAttributeSchemaContract entity = mutation.mutate(
			null,
			everywhere.mutate(null, createStringEntityAttributeSchema(), EntityAttributeSchemaContract.class),
			EntityAttributeSchemaContract.class
		);
		assertEquals(allScopes, entity.getSearchableInScopes(), label + " dropped it from an entity attribute");

		final GlobalAttributeSchemaContract global = mutation.mutate(
			null,
			everywhere.mutate(null, createStringGlobalAttributeSchema(), GlobalAttributeSchemaContract.class),
			GlobalAttributeSchemaContract.class
		);
		assertEquals(allScopes, global.getSearchableInScopes(), label + " dropped it from a global attribute");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("otherAttributeMutations")
	@DisplayName("Should keep the analyzer and the length pivot of an attribute through every other attribute mutation")
	void shouldKeepAnalyzerAndPivotThroughOtherAttributeMutation(
		@Nonnull String label, @Nonnull AttributeSchemaMutation mutation
	) {
		final SetAttributeSchemaSearchableMutation configured =
			new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, Scope.values(), "html-text", 180, null);

		final AttributeSchemaContract plain = mutation.mutate(
			null,
			configured.mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class),
			AttributeSchemaContract.class
		);
		assertEquals("html-text", plain.getAnalyzer(), label + " dropped it from a reference attribute");
		assertEquals(180, plain.getLengthPivot(), label + " dropped it from a reference attribute");

		final EntityAttributeSchemaContract entity = mutation.mutate(
			null,
			configured.mutate(null, createStringEntityAttributeSchema(), EntityAttributeSchemaContract.class),
			EntityAttributeSchemaContract.class
		);
		assertEquals("html-text", entity.getAnalyzer(), label + " dropped it from an entity attribute");
		assertEquals(180, entity.getLengthPivot(), label + " dropped it from an entity attribute");

		final GlobalAttributeSchemaContract global = mutation.mutate(
			null,
			configured.mutate(null, createStringGlobalAttributeSchema(), GlobalAttributeSchemaContract.class),
			GlobalAttributeSchemaContract.class
		);
		assertEquals("html-text", global.getAnalyzer(), label + " dropped it from a global attribute");
		assertEquals(180, global.getLengthPivot(), label + " dropped it from a global attribute");
	}

	@Test
	@DisplayName("Should keep the searchable scopes of a global attribute when its global uniqueness changes")
	void shouldKeepSearchableScopesThroughGlobalUniquenessChange() {
		final GlobalAttributeSchemaContract searchable = new SetAttributeSchemaSearchableMutation(
			ATTRIBUTE_NAME, Scope.values()
		).mutate(null, createStringGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);

		final GlobalAttributeSchemaContract mutated = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		).mutate(null, searchable, GlobalAttributeSchemaContract.class);

		assertTrue(mutated.isUniqueGlobally());
		assertEquals(EnumSet.allOf(Scope.class), mutated.getSearchableInScopes());
	}

	@Nested
	@DisplayName("validation")
	class Validation {

		@Test
		@DisplayName("Should accept a localized String attribute")
		void shouldAcceptLocalizedStringAttribute() {
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, filterableAcceleratedLocalizedString(), AttributeSchemaContract.class);
			assertEquals(List.of(), mutated.validate().toList());
		}

		@Test
		@DisplayName("Should accept a localized String array attribute")
		void shouldAcceptLocalizedStringArrayAttribute() {
			final AttributeSchemaContract localizedArray = new SetAttributeSchemaLocalizedMutation(
				ATTRIBUTE_NAME, true
			).mutate(null, createAttributeSchemaOfType(String[].class), AttributeSchemaContract.class);
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, localizedArray, AttributeSchemaContract.class);
			assertEquals(List.of(), mutated.validate().toList());
		}

		@Test
		@DisplayName("Should apply to an attribute of another type and leave the refusal to the assembled schema")
		void shouldLeaveTypeRefusalToAssembledSchema() {
			// the mutation refuses nothing - a type change may still follow in the same batch, so only the assembled
			// attribute can tell whether the combination makes sense
			final AttributeSchemaContract localizedInteger = new SetAttributeSchemaLocalizedMutation(
				ATTRIBUTE_NAME, true
			).mutate(null, createExistingAttributeSchema(), AttributeSchemaContract.class);
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, localizedInteger, AttributeSchemaContract.class);

			assertTrue(mutated.isSearchable());
			final List<String> errors = mutated.validate().toList();
			assertEquals(1, errors.size(), errors.toString());
			assertTrue(errors.get(0).contains("String"), errors.get(0));
			assertTrue(errors.get(0).contains(ATTRIBUTE_NAME), errors.get(0));
		}

		@Test
		@DisplayName("Should report a searchable attribute that is not localized")
		void shouldReportSearchableAttributeThatIsNotLocalized() {
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);

			final List<String> errors = mutated.validate().toList();
			assertEquals(1, errors.size(), errors.toString());
			assertTrue(errors.get(0).contains("localized"), errors.get(0));
		}

		@Test
		@DisplayName("Should report both problems of a non-localized attribute of another type")
		void shouldReportBothProblems() {
			final AttributeSchemaContract mutated = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(null, createExistingAttributeSchema(), AttributeSchemaContract.class);
			assertEquals(2, mutated.validate().count());
		}

		@Test
		@DisplayName("Should report nothing for a non-searchable attribute of any type")
		void shouldReportNothingForNonSearchableAttribute() {
			assertEquals(0, createExistingAttributeSchema().validate().count());
			assertEquals(0, createStringAttributeSchema().validate().count());
		}
	}

	@Nested
	@DisplayName("schemas holding the attribute")
	class HoldingSchemas {

		@Test
		@DisplayName("Should mutate the entity schema")
		void shouldMutateEntitySchema() {
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createStringEntityAttributeSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true)
				.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);

			assertEquals(2, newEntitySchema.version());
			assertTrue(newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow().isSearchable());
		}

		@Test
		@DisplayName("Should mutate the catalog schema")
		void shouldMutateCatalogSchema() {
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME))
				.thenReturn(of(createStringGlobalAttributeSchema()));
			Mockito.when(catalogSchema.version()).thenReturn(1);

			final CatalogSchemaContract newCatalogSchema = new SetAttributeSchemaSearchableMutation(
					ATTRIBUTE_NAME, true
				)
				.mutate(catalogSchema)
				.updatedCatalogSchema();

			assertEquals(2, newCatalogSchema.version());
			assertTrue(newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow().isSearchable());
		}

		@Test
		@DisplayName("Should make the reference report a searchable attribute that is not localized")
		void shouldMakeReferenceReportSearchableAttributeThatIsNotLocalized() {
			// a mutation arriving without a builder - over the wire or from the WAL - is validated only once the
			// whole schema is assembled, so the reference must run the attribute rules itself
			final ReferenceSchemaContract notLocalized = createLiveIndexedReferenceAttribute(false, Scope.LIVE);
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> ((ReferenceSchema) notLocalized).validate(
					Mockito.mock(CatalogSchemaContract.class), EntitySchema._internalBuild("product")
				)
			);
			assertTrue(exception.getMessage().contains("localized"), exception.getMessage());
			assertTrue(exception.getMessage().contains(ATTRIBUTE_NAME), exception.getMessage());

			// the positive control - the same reference with a localized attribute validates
			final ReferenceSchemaContract localized = createLiveIndexedReferenceAttribute(true, Scope.LIVE);
			assertDoesNotThrow(
				() -> ((ReferenceSchema) localized).validate(
					Mockito.mock(CatalogSchemaContract.class), EntitySchema._internalBuild("product")
				)
			);
		}

		@Test
		@DisplayName("Should refuse the attribute of a reference that is not indexed in a requested scope")
		void shouldRefuseAttributeOfReferenceNotIndexedInRequestedScope() {
			// like filterability, searchability needs the reference indexed in the scope, and a mutation applied on
			// its own is refused right here rather than only when the whole schema is validated
			final ReferenceSchemaContract liveIndexed = createLiveIndexedReferenceAttribute(true);
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, new Scope[]{Scope.LIVE, Scope.ARCHIVED})
					.mutate(EntitySchema._internalBuild("product"), liveIndexed)
			);
			assertTrue(exception.getMessage().contains("required scopes: ARCHIVED!"), exception.getMessage());
			assertTrue(
				exception.getMessage().contains("searchable attribute `" + ATTRIBUTE_NAME + "`"), exception.getMessage()
			);

			// the positive control - the scope the reference is indexed in is accepted
			final ReferenceSchemaContract mutated = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE}
			).mutate(EntitySchema._internalBuild("product"), liveIndexed);
			assertNotNull(mutated);
			assertEquals(
				EnumSet.of(Scope.LIVE), mutated.getAttribute(ATTRIBUTE_NAME).orElseThrow().getSearchableInScopes()
			);
		}

		@Test
		@DisplayName("Should leave the reference index to the schema validation when consistency checks are skipped")
		void shouldNotRefuseAttributeOfReferenceNotIndexedWhenChecksAreSkipped() {
			// a builder applies its mutations one by one with the checks skipped, so it may declare searchability
			// before indexing - the assembled schema is validated once all of them are applied
			final ReferenceSchemaContract liveIndexed = createLiveIndexedReferenceAttribute(true);
			final ReferenceSchemaContract mutated = new SetAttributeSchemaSearchableMutation(
				ATTRIBUTE_NAME, new Scope[]{Scope.LIVE, Scope.ARCHIVED}
			).mutate(EntitySchema._internalBuild("product"), liveIndexed, ConsistencyChecks.SKIP);
			assertNotNull(mutated);
			assertEquals(
				EnumSet.of(Scope.LIVE, Scope.ARCHIVED),
				mutated.getAttribute(ATTRIBUTE_NAME).orElseThrow().getSearchableInScopes()
			);
		}
	}

	/** Nullable settings retain earlier weights, while explicitly restated zero and positive weights win. */
	@Test
	@DisplayName("should retain and combine search weights in mutation order")
	void shouldRetainAndCombineSearchWeights() {
		final CatalogSchema catalog = CatalogSchema._internalBuild(
			"test", Map.of(), null, Set.of(),
			EmptyEntitySchemaAccessor.INSTANCE
		);
		final AttributeSchemaContract original = filterableAcceleratedLocalizedString();
		assertEquals(1.0f, original.getSearchWeight());
		final SetAttributeSchemaSearchableMutation earlier = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME,
			Scope.DEFAULT_SCOPES, null, null, 3.5f);
		final AttributeSchemaContract weighted = earlier.mutate(catalog, original, AttributeSchemaContract.class);
		assertEquals(3.5f, weighted.getSearchWeight());
		for (final Float weight : new Float[]{null, 0.0f, 2.5f}) {
			final SetAttributeSchemaSearchableMutation later = new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME,
				Scope.DEFAULT_SCOPES, null, null, weight);
			final float expected = weight == null ? 3.5f : weight;
			assertEquals(expected, later.mutate(catalog, weighted, AttributeSchemaContract.class).getSearchWeight());
			final MutationCombinationResult<LocalEntitySchemaMutation> combined = later.combineWith(
				catalog, EntitySchema._internalBuild("product"), earlier
			);
			assertNotNull(combined);
			final SetAttributeSchemaSearchableMutation merged =
				(SetAttributeSchemaSearchableMutation) combined.current()[0];
			assertEquals(expected, merged.getSearchWeight());
			assertEquals(expected, merged.mutate(catalog, original, AttributeSchemaContract.class).getSearchWeight());
		}
	}

	/** Both signs of zero produce identical statements and preserve an unchanged schema instance. */
	@Test
	@DisplayName("should treat both signs of a zero weight as one value and keep an unchanged schema")
	void shouldCanonicalizeZeroAndKeepUnchangedSchema() {
		final CatalogSchema catalog = CatalogSchema._internalBuild(
			"test", Map.of(), null, Set.of(), EmptyEntitySchemaAccessor.INSTANCE
		);
		final AttributeSchemaContract original = filterableAcceleratedLocalizedString();
		final SetAttributeSchemaSearchableMutation positiveZero = new SetAttributeSchemaSearchableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES, null, null, 0.0f
		);
		final SetAttributeSchemaSearchableMutation negativeZero = new SetAttributeSchemaSearchableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES, null, null, -0.0f
		);
		final AttributeSchemaContract zero = positiveZero.mutate(catalog, original, AttributeSchemaContract.class);
		assertSame(zero, negativeZero.mutate(catalog, zero, AttributeSchemaContract.class));
		assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(zero.getSearchWeight()));
		assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(negativeZero.getSearchWeight()));
		assertEquals(positiveZero, negativeZero);
		assertEquals(positiveZero.hashCode(), negativeZero.hashCode());
		final SetAttributeSchemaSearchableMutation weighted = new SetAttributeSchemaSearchableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES, null, null, 2.5f
		);
		final AttributeSchemaContract changed = weighted.mutate(catalog, original, AttributeSchemaContract.class);
		assertSame(changed, weighted.mutate(catalog, changed, AttributeSchemaContract.class));
		assertNotEquals(positiveZero, weighted);
		assertNotEquals(positiveZero.hashCode(), weighted.hashCode());
		assertTrue(weighted.toString().contains("searchWeight=2.5"));
	}
}
