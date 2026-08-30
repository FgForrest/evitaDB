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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NamingConvention;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Tests for {@link AttributeSchema} and its sealed subtypes.
 */
@DisplayName("AttributeSchema")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class AttributeSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal schema with name, type and localized flag")
		void shouldBuildMinimalSchema() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);

			assertEquals("code", schema.getName());
			assertSame(String.class, schema.getType());
			assertSame(String.class, schema.getPlainType());
			assertFalse(schema.isLocalized());
			assertFalse(schema.isNullable());
			assertFalse(schema.isRepresentative());
			assertNull(schema.getDescription());
			assertNull(schema.getDeprecationNotice());
			assertNull(schema.getDefaultValue());
			assertEquals(0, schema.getIndexedDecimalPlaces());
		}

		@Test
		@DisplayName("should build full schema with all parameters")
		void shouldBuildFullSchema() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"priority",
				"Priority of the entity",
				"Use 'weight' instead",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				new Scope[]{Scope.LIVE},
				null,
				new Scope[]{Scope.LIVE},
				false, true, true,
				Integer.class, 0,
				0,
				ConflictResolutionOverride.INHERITED
			);

			assertEquals("priority", schema.getName());
			assertEquals("Priority of the entity", schema.getDescription());
			assertEquals("Use 'weight' instead", schema.getDeprecationNotice());
			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isFilterableInScope(Scope.LIVE));
			assertTrue(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.isNullable());
			assertTrue(schema.isRepresentative());
			assertSame(Integer.class, schema.getType());
			assertEquals(0, schema.getDefaultValue());
		}

		@Test
		@DisplayName("should generate name variants for naming conventions")
		void shouldGenerateNameVariants() {
			final AttributeSchema schema = AttributeSchema._internalBuild("productCode", String.class, false, ConflictResolutionOverride.INHERITED);

			final String camelCase = schema.getNameVariant(NamingConvention.CAMEL_CASE);
			assertNotNull(camelCase);
			assertEquals("productCode", schema.getNameVariant(NamingConvention.CAMEL_CASE));
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> customVariants = NamingConvention.generate("myAttr");
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"myAttr", customVariants,
				null, null,
				(Map<Scope, AttributeUniquenessType>) null,
				null, null, null,
				false, false, false,
				String.class, null, 0,
				ConflictResolutionOverride.INHERITED
			);

			assertEquals(
				customVariants.get(NamingConvention.CAMEL_CASE),
				schema.getNameVariant(NamingConvention.CAMEL_CASE)
			);
		}

		@Test
		@DisplayName("should wrap primitive types to wrapper types")
		void shouldWrapPrimitiveTypes() {
			final AttributeSchema schema = AttributeSchema._internalBuild("count", int.class, false, ConflictResolutionOverride.INHERITED);

			assertSame(Integer.class, schema.getType());
			assertSame(Integer.class, schema.getPlainType());
		}

		@Test
		@DisplayName("should resolve plain type from array type")
		void shouldResolvePlainTypeFromArrayType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("tags", String[].class, false, ConflictResolutionOverride.INHERITED);

			assertSame(String[].class, schema.getType());
			assertSame(String.class, schema.getPlainType());
		}

		@Test
		@DisplayName("should default uniqueness to NOT_UNIQUE when null")
		void shouldDefaultUniquenessToNotUnique() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);

			assertEquals(AttributeUniquenessType.NOT_UNIQUE, schema.getUniquenessType(Scope.DEFAULT_SCOPE));
			assertFalse(schema.isUnique());
			assertFalse(schema.isUniqueWithinLocale());
		}
	}

	@Nested
	@DisplayName("Uniqueness queries")
	class UniquenessQueries {

		@Test
		@DisplayName("should report unique when scope has UNIQUE_WITHIN_COLLECTION")
		void shouldReportUniqueForCollection() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				null, null, null,
				false, false, false,
				String.class, null,
				ConflictResolutionOverride.INHERITED
			);

			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isUnique());
			assertFalse(schema.isUniqueWithinLocale());
			assertFalse(schema.isUniqueWithinLocaleInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should report unique within locale for matching scope")
		void shouldReportUniqueWithinLocale() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"slug",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(
						Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
					)
				},
				null, null, null,
				true, false, false,
				String.class, null,
				ConflictResolutionOverride.INHERITED
			);

			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isUniqueWithinLocaleInScope(Scope.LIVE));
			assertTrue(schema.isUniqueWithinLocale());
		}

		@Test
		@DisplayName("should return NOT_UNIQUE for unregistered scope")
		void shouldReturnNotUniqueForUnregisteredScope() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				null, null, null,
				false, false, false,
				String.class, null,
				ConflictResolutionOverride.INHERITED
			);

			assertEquals(AttributeUniquenessType.NOT_UNIQUE, schema.getUniquenessType(Scope.ARCHIVED));
			assertFalse(schema.isUniqueInScope(Scope.ARCHIVED));
		}
	}

	@Nested
	@DisplayName("Filterable and Sortable")
	class FilterableAndSortable {

		@Test
		@DisplayName("should report filterable in specified scope")
		void shouldReportFilterable() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"name",
				null,
				new Scope[]{Scope.LIVE},
				null,
				null,
				false, false, false,
				String.class, null,
				ConflictResolutionOverride.INHERITED
			);

			assertTrue(schema.isFilterableInScope(Scope.LIVE));
			assertFalse(schema.isFilterableInScope(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should report sortable in specified scope")
		void shouldReportSortable() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"name",
				null, null, null,
				new Scope[]{Scope.LIVE, Scope.ARCHIVED},
				false, false, false,
				String.class, null,
				ConflictResolutionOverride.INHERITED
			);

			assertTrue(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.isSortableInScope(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should report empty scopes when none specified")
		void shouldReportEmptyScopes() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);

			assertFalse(schema.isFilterableInScope(Scope.LIVE));
			assertFalse(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.getFilterableInScopes().isEmpty());
			assertTrue(schema.getSortableInScopes().isEmpty());
		}
	}

	@Nested
	@DisplayName("Type inversion")
	class TypeInversion {

		@Test
		@DisplayName("should invert Predecessor to ReferencedEntityPredecessor")
		void shouldInvertPredecessorType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("order", Predecessor.class, false, ConflictResolutionOverride.INHERITED);

			final AttributeSchemaContract inverted = schema.withInvertedType();

			assertSame(ReferencedEntityPredecessor.class, inverted.getType());
		}

		@Test
		@DisplayName("should invert ReferencedEntityPredecessor to Predecessor")
		void shouldInvertReferencedEntityPredecessorType() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"order", ReferencedEntityPredecessor.class, false,
				ConflictResolutionOverride.INHERITED
			);

			final AttributeSchemaContract inverted = schema.withInvertedType();

			assertSame(Predecessor.class, inverted.getType());
		}

		@Test
		@DisplayName("should throw when inverting non-predecessor type")
		void shouldThrowWhenInvertingNonPredecessorType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("name", String.class, false, ConflictResolutionOverride.INHERITED);

			assertThrows(GenericEvitaInternalError.class, schema::withInvertedType);
		}
	}

	@Nested
	@DisplayName("Filter index capabilities")
	class FilterCapabilities {

		@Test
		@DisplayName("should drop a scope mapped to an empty capability set")
		void shouldDropScopeMappedToEmptyCapabilitySet() {
			// this is what keeps an accelerated-then-cleared schema comparing equal to a plainly filterable one, and
			// every mutation-combination path rests on that equality to decide whether anything actually changed
			final Map<Scope, Set<AttributeFilterAccelerator>> emptyForLive = new EnumMap<>(Scope.class);
			emptyForLive.put(Scope.LIVE, EnumSet.noneOf(AttributeFilterAccelerator.class));

			final AttributeSchema declaringEmptySet = buildFilterableSchema(emptyForLive);
			final AttributeSchema declaringNothing = buildFilterableSchema(null);

			assertTrue(declaringEmptySet.getAcceleratorsInScopes().isEmpty());
			assertEquals(declaringNothing, declaringEmptySet);
			assertEquals(declaringNothing.hashCode(), declaringEmptySet.hashCode());
		}

		@Test
		@DisplayName("should keep an accelerator declared in a scope the attribute is not filterable in")
		void shouldKeepAcceleratorInNonFilterableScope() {
			// the DTO deliberately does NOT police this cross-field rule. It is the type the builder materializes
			// after each individual mutation, so it routinely holds intermediate states whose filterability has not
			// caught up yet; refusing here made the order of the builder calls significant. Every sibling cross-field
			// invariant - sortable-versus-array, unique-versus-filterable, the Comparable requirement - lives in
			// AbstractAttributeSchemaBuilder#validate for the same reason, and this rule now sits with them. The
			// refusal is pinned by AttributeSchemaBuilderTest#shouldRefuseAcceleratorLeftInScopeWithoutFilterIndex.
			final Map<Scope, Set<AttributeFilterAccelerator>> substringInArchived = new EnumMap<>(Scope.class);
			substringInArchived.put(Scope.ARCHIVED, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));

			final AttributeSchema schema = buildFilterableSchema(substringInArchived);

			assertFalse(schema.hasFilterIndexInScope(Scope.ARCHIVED));
			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
				schema.getAcceleratorsInScope(Scope.ARCHIVED)
			);
		}

		@Test
		@DisplayName("should refuse the substring capability on a non-String attribute built directly")
		void shouldRefuseSubstringCapabilityOnNonStringAttributeBuiltDirectly() {
			// the same rule the mutation route refuses with the same exception type, so that one `catch` covers the
			// invariant however it was reached - the DTO backstop is what external-API converters and Kryo readers
			// run into, since they build schemas straight through `_internalBuild`
			final Map<Scope, Set<AttributeFilterAccelerator>> substringInLive = new EnumMap<>(Scope.class);
			substringInLive.put(Scope.LIVE, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeSchema._internalBuild(
					"quantity", null, null,
					(Map<Scope, AttributeUniquenessType>) null,
					Set.of(Scope.LIVE),
					substringInLive,
					null,
					false, false, false,
					Integer.class, null, 0,
					ConflictResolutionOverride.INHERITED
				)
			);
			assertSame(InvalidSchemaMutationException.class, exception.getClass());
			assertTrue(exception.getMessage().contains("String"));
		}

		@Test
		@DisplayName("should expose the capability map and its value sets as unmodifiable")
		void shouldExposeCapabilitiesAsUnmodifiable() {
			final Map<Scope, Set<AttributeFilterAccelerator>> substringInLive = new EnumMap<>(Scope.class);
			substringInLive.put(Scope.LIVE, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));
			final AttributeSchema schema = buildFilterableSchema(substringInLive);

			final Map<Scope, Set<AttributeFilterAccelerator>> capabilities = schema.getAcceleratorsInScopes();

			assertThrows(UnsupportedOperationException.class, () -> capabilities.remove(Scope.LIVE));
			assertThrows(
				UnsupportedOperationException.class,
				() -> schema.getAcceleratorsInScope(Scope.LIVE).clear()
			);
		}

		/**
		 * Builds a `String` attribute filterable in the live scope, carrying the given capabilities.
		 *
		 * @param acceleratorsInScopes the capabilities to declare, may be null
		 * @return the attribute schema
		 */
		@Nonnull
		private AttributeSchema buildFilterableSchema(
			@Nullable Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes
		) {
			return AttributeSchema._internalBuild(
				"code", null, null,
				(Map<Scope, AttributeUniquenessType>) null,
				Set.of(Scope.LIVE),
				acceleratorsInScopes,
				null,
				false, false, false,
				String.class, null, 0,
				ConflictResolutionOverride.INHERITED
			);
		}
	}

	@Nested
	@DisplayName("The map-to-array capability hinge")
	class FilterCapabilityHinge {

		@Test
		@DisplayName("should turn carriers into a per-scope map")
		void shouldConvertCarriersToEnumMap() {
			final EnumMap<Scope, Set<AttributeFilterAccelerator>> converted = AttributeSchema.toAcceleratorsEnumMap(
				new ScopedAttributeFilterAccelerators[]{
					new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
				}
			);

			assertEquals(Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), converted.get(Scope.LIVE));
			assertNull(converted.get(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should turn an empty carrier into no entry at all")
		void shouldConvertEmptyCarrierToNoEntry() {
			// the documented asymmetry of the pair: an empty carrier means "filterable here, no acceleration", which
			// is the *absence* of an entry rather than an entry holding an empty set - anything else would stop a
			// plainly filterable attribute comparing equal to one whose capabilities were cleared
			final EnumMap<Scope, Set<AttributeFilterAccelerator>> converted = AttributeSchema.toAcceleratorsEnumMap(
				new ScopedAttributeFilterAccelerators[]{new ScopedAttributeFilterAccelerators(Scope.LIVE)}
			);

			assertTrue(converted.isEmpty());
		}

		@Test
		@DisplayName("should collapse two carriers naming the same scope into one entry")
		void shouldCollapseDuplicateScopeCarriers() {
			// the conversion merges rather than overwrites, so neither carrier's capabilities are lost. With a single
			// capability declared today a merge and an overwrite yield the same set, so what this pins is that the
			// second carrier neither throws nor erases the entry - it gains its teeth when a second capability exists
			final EnumMap<Scope, Set<AttributeFilterAccelerator>> converted = AttributeSchema.toAcceleratorsEnumMap(
				new ScopedAttributeFilterAccelerators[]{
					new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH),
					new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
				}
			);

			assertEquals(1, converted.size());
			assertEquals(Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), converted.get(Scope.LIVE));
		}

		@Test
		@DisplayName("should treat a null carrier array as no capability anywhere")
		void shouldTreatNullCarrierArrayAsNoCapability() {
			assertTrue(AttributeSchema.toAcceleratorsEnumMap(null).isEmpty());
			assertTrue(AttributeSchema.toAcceleratorsEnumMap(ScopedAttributeFilterAccelerators.EMPTY).isEmpty());
		}

		@Test
		@DisplayName("should emit one carrier per scope, in Scope declaration order")
		void shouldEmitCarriersInScopeDeclarationOrder() {
			// the emitted order is what a schema-diffing mutation and every external-API conversion put on the wire,
			// so it has to come from `Scope` rather than from the iteration order of whatever map was handed in
			final Map<Scope, Set<AttributeFilterAccelerator>> reversed = new LinkedHashMap<>();
			reversed.put(Scope.ARCHIVED, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));
			reversed.put(Scope.LIVE, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));

			final ScopedAttributeFilterAccelerators[] emitted = AttributeSchema.toAcceleratorsArray(reversed);

			assertArrayEquals(
				new ScopedAttributeFilterAccelerators[]{
					new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH),
					new ScopedAttributeFilterAccelerators(Scope.ARCHIVED, AttributeFilterAccelerator.SUBSTRING_SEARCH)
				},
				emitted
			);
		}

		@Test
		@DisplayName("should emit no carrier for a scope holding an empty capability set")
		void shouldEmitNoCarrierForEmptyCapabilitySet() {
			final Map<Scope, Set<AttributeFilterAccelerator>> withEmptyLive = new EnumMap<>(Scope.class);
			withEmptyLive.put(Scope.LIVE, EnumSet.noneOf(AttributeFilterAccelerator.class));
			withEmptyLive.put(Scope.ARCHIVED, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));

			final ScopedAttributeFilterAccelerators[] emitted = AttributeSchema.toAcceleratorsArray(withEmptyLive);

			assertArrayEquals(
				new ScopedAttributeFilterAccelerators[]{
					new ScopedAttributeFilterAccelerators(Scope.ARCHIVED, AttributeFilterAccelerator.SUBSTRING_SEARCH)
				},
				emitted
			);
		}

		@Test
		@DisplayName("should emit the shared empty array for a null or empty map")
		void shouldEmitSharedEmptyArrayForNullOrEmptyMap() {
			assertSame(ScopedAttributeFilterAccelerators.EMPTY, AttributeSchema.toAcceleratorsArray(null));
			assertSame(ScopedAttributeFilterAccelerators.EMPTY, AttributeSchema.toAcceleratorsArray(Map.of()));
		}

		@Test
		@DisplayName("should return to the same map after a round trip through the carrier array")
		void shouldRoundTripThroughTheCarrierArray() {
			final Map<Scope, Set<AttributeFilterAccelerator>> original = new EnumMap<>(Scope.class);
			original.put(Scope.LIVE, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));
			original.put(Scope.ARCHIVED, EnumSet.of(AttributeFilterAccelerator.SUBSTRING_SEARCH));

			assertEquals(
				original,
				AttributeSchema.toAcceleratorsEnumMap(AttributeSchema.toAcceleratorsArray(original))
			);
		}
	}

	@Nested
	@DisplayName("Static helpers")
	class StaticHelpers {

		@Test
		@DisplayName("should convert scoped uniqueness array to enum map")
		void shouldConvertToUniquenessEnumMap() {
			final ScopedAttributeUniquenessType[] scoped = new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
				new ScopedAttributeUniquenessType(
					Scope.ARCHIVED, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
				)
			};

			final EnumMap<Scope, AttributeUniquenessType> result = AttributeSchema.toUniquenessEnumMap(scoped);

			assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, result.get(Scope.LIVE));
			assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE, result.get(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should return default NOT_UNIQUE map when input null")
		void shouldReturnDefaultMapWhenNull() {
			final EnumMap<Scope, AttributeUniquenessType> result = AttributeSchema.toUniquenessEnumMap(null);

			assertEquals(AttributeUniquenessType.NOT_UNIQUE, result.get(Scope.DEFAULT_SCOPE));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same construction parameters")
		void shouldBeEqualForSameParams() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);
			final AttributeSchema b = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);
			final AttributeSchema b = AttributeSchema._internalBuild("name", String.class, false, ConflictResolutionOverride.INHERITED);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when types differ")
		void shouldNotBeEqualWhenTypesDiffer() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);
			final AttributeSchema b = AttributeSchema._internalBuild("code", Integer.class, false, ConflictResolutionOverride.INHERITED);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when conflict resolution override differs")
		void shouldNotBeEqualWhenConflictResolutionOverrideDiffers() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);
			final AttributeSchema b = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.GRANULAR);

			assertNotEquals(a, b);
			assertNotEquals(a.hashCode(), b.hashCode());
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTests {

		@Test
		@DisplayName("should contain schema name and type in output")
		void shouldContainNameAndType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.INHERITED);

			final String result = schema.toString();

			assertTrue(result.contains("code"), "toString should contain attribute name");
			assertTrue(result.contains("String"), "toString should contain type name");
		}

		@Test
		@DisplayName("should format uniqueness entries, not Stream reference")
		void shouldFormatUniquenessEntries() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				null, null, null,
				false, false, false,
				String.class, null,
				ConflictResolutionOverride.INHERITED
			);

			final String result = schema.toString();

			// The key assertion: toString must NOT contain
			// Stream reference like "java.util.stream.ReferencePipeline"
			assertFalse(result.contains("ReferencePipeline"), "toString should not contain Stream object reference");
			// It should contain the actual formatted entry
			assertTrue(result.contains("LIVE"), "toString should contain scope name");
			assertTrue(result.contains("UNIQUE_WITHIN_COLLECTION"), "toString should contain uniqueness type");
		}
	}
}
