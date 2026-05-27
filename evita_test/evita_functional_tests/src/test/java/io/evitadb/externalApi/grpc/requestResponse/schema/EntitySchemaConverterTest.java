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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.*;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchema;
import io.evitadb.externalApi.grpc.generated.GrpcNameVariant;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceSchema;
import io.evitadb.externalApi.grpc.generated.GrpcScopedHistogramIndexDefinition;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.*;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies functionalities of methods in {@link EntitySchemaConverter} class.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
class EntitySchemaConverterTest {

	@Nonnull
	private static CatalogSchema createCatalogSchemaWithSingleEntitySchema(EntitySchema entitySchema) {
		return CatalogSchema._internalBuild("test", Collections.emptyMap(), EnumSet.allOf(CatalogEvolutionMode.class), new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of(entitySchema);
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return entityType.equals(entitySchema.getName()) ? Optional.of(entitySchema) : Optional.empty();
			}
		});
	}

	@Nonnull
	private static EntitySchema createComplexEntitySchema() {
		return EntitySchema._internalBuild(
			1,
			Entities.PRODUCT,
			"Lorem ipsum dolor sit amet.",
			"Alert! Deprecated!",
			true,
			false,
			Scope.NO_SCOPE,
			true,
			new Scope[]{Scope.LIVE},
			2,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(Currency.getInstance("EUR"), Currency.getInstance("USD")),
			Map.of(
				"test1", EntityAttributeSchema._internalBuild("test1", LocalDateTime.class, true),
				"test2", GlobalAttributeSchema._internalBuild(
					"test2",
					"description",
					"depr",
					new ScopedAttributeUniquenessType[]{
						new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
					},
					new ScopedGlobalAttributeUniquenessType[]{
						new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
					},
					new Scope[]{Scope.LIVE},
					new Scope[]{Scope.LIVE},
					true,
					true,
					false,
					String.class,
					null,
					0
				)
			),
			Map.of(
				"test1", AssociatedDataSchema._internalBuild("test1", "Lorem ipsum", "Alert", Integer.class, false, true),
				"test2", AssociatedDataSchema._internalBuild("test2", "Lorem ipsum", "Alert", String[].class, true, true)
			),
			Map.of(
				"test1", ReferenceSchema._internalBuild(
					"test1",
					Entities.PARAMETER,
					true,
					Cardinality.ZERO_OR_MORE,
					Entities.PARAMETER_GROUP,
					false,
					new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) },
					new Scope[]{Scope.LIVE}
				),
				"test2", ReferenceSchema._internalBuild(
					"test2",
					NamingConvention.generate("test2"),
					"desc",
					"depr",
					Entities.CATEGORY,
					NamingConvention.generate(Entities.CATEGORY),
					false,
					Cardinality.ONE_OR_MORE,
					null,
					Collections.emptyMap(),
					false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.DEFAULT_SCOPE,
							ReferenceIndexType.FOR_FILTERING
						)
					},
					null,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(
							Scope.LIVE,
							ExpressionFactory.parse("1 > 0")
						)
					},
					null, null,
					Map.of(
						"code", EntityAttributeSchema._internalBuild(
							"code",
							"description",
							"depr",
							new ScopedAttributeUniquenessType[]{
								new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
							},
							new Scope[]{Scope.LIVE},
							new Scope[]{Scope.LIVE},
							true,
							true,
							true,
							String.class,
							null,
							0
						),
						"priority", EntityAttributeSchema._internalBuild(
							"code",
							Long[].class,
							false
						)
					),
					Map.of(
						"compound1",
						SortableAttributeCompoundSchema._internalBuild(
							"compound1", "This is compound 1", null, new Scope[]{Scope.LIVE},
							Arrays.asList(
								new AttributeElement("code", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
								new AttributeElement("name", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
							)
						),
						"compound2",
						SortableAttributeCompoundSchema._internalBuild(
							"compound2", "This is compound 2", null, new Scope[]{Scope.LIVE},
							Arrays.asList(
								new AttributeElement("name", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								new AttributeElement("age", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST)
							)
						)
					)
				)
			),
			Set.of(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_ATTRIBUTES),
			Map.of(
				"compound1",
				EntitySortableAttributeCompoundSchema._internalBuild(
					"compound1", "This is compound 1", null, new Scope[]{Scope.LIVE},
					Arrays.asList(
						new AttributeElement("code", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("name", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
				)
			)
		);
	}

	private static void assertEntitySchema(@Nonnull EntitySchemaContract expected, @Nonnull EntitySchemaContract actual) {
		assertEquals(expected.version(), actual.version());
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.isBlank(), actual.isBlank());
		assertEquals(expected.isWithGeneratedPrimaryKey(), actual.isWithGeneratedPrimaryKey());
		assertEquals(expected.isWithHierarchy(), actual.isWithHierarchy());
		assertEquals(expected.isWithPrice(), actual.isWithPrice());
		assertEquals(expected.getIndexedPricePlaces(), actual.getIndexedPricePlaces());
		assertEquals(expected.getLocales(), actual.getLocales());
		assertEquals(expected.getCurrencies(), actual.getCurrencies());
		assertEquals(expected.getEvolutionMode(), actual.getEvolutionMode());
		assertEquals(expected.getSortableAttributeCompounds(), actual.getSortableAttributeCompounds());

		assertEquals(expected.getAttributes().size(), actual.getAttributes().size());
		expected.getAttributes().forEach((attributeName, attribute) ->
			assertAttributeSchema(attribute, actual.getAttribute(attributeName).orElseThrow()));

		assertEquals(expected.getSortableAttributeCompounds().size(), actual.getSortableAttributeCompounds().size());
		expected.getSortableAttributeCompounds().forEach((compoundName, compound) ->
			assertSortableAttributeCompoundSchema(compound, actual.getSortableAttributeCompound(compoundName).orElseThrow()));

		assertEquals(expected.getAssociatedData().size(), actual.getAssociatedData().size());
		expected.getAssociatedData().forEach((associatedDataName, associatedData) ->
			assertAssociatedDataSchema(associatedData, actual.getAssociatedData(associatedDataName).orElseThrow()));

		assertEquals(expected.getReferences().size(), actual.getReferences().size());
		expected.getReferences().forEach((referenceName, reference) ->
			assertReferenceSchema(reference, actual.getReference(referenceName).orElseThrow()));
	}

	private static void assertAttributeSchema(@Nonnull AttributeSchemaContract expected, @Nonnull AttributeSchemaContract actual) {
		assertSame(expected.getClass(), actual.getClass());
		if (expected instanceof GlobalAttributeSchemaContract expectedGlobal) {
			assertEquals(expectedGlobal.isUniqueGlobally(), ((GlobalAttributeSchemaContract) actual).isUniqueGlobally());
		}

		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription(), "Attribute `" + expected.getName() + "` is expected to have description `" + expected.getDescription() + "`!");
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice(), "Attribute `" + expected.getName() + "` is expected to have deprecation notice `" + expected.getDeprecationNotice() + "`!");
		assertEquals(expected.isLocalized(), actual.isLocalized(), "Attribute `" + expected.getName() + "` is expected " + (expected.isLocalized() ? "localized" : "not localized") + "!");
		assertEquals(expected.isUnique(), actual.isUnique(), "Attribute `" + expected.getName() + "` is expected " + (expected.isUnique() ? "unique" : "not unique") + "!");
		assertEquals(expected.isFilterable(), actual.isFilterable(), "Attribute `" + expected.getName() + "` is expected " + (expected.isFilterable() ? "filterable" : "not filterable") + "!");
		assertEquals(expected.isSortable(), actual.isSortable(), "Attribute `" + expected.getName() + "` is expected " + (expected.isSortable() ? "sortable" : "not sortable") + "!");
		assertEquals(expected.isNullable(), actual.isNullable(), "Attribute `" + expected.getName() + "` is expected " + (expected.isNullable() ? "nullable" : "not nullable") + "!");
		assertSame(
			expected.getType(), actual.getType(),
			"Attribute `" + expected.getName() + "` is expected to be of type `" + expected.getType() + "`!"
		);
		assertSame(
			expected.getPlainType(), actual.getPlainType(),
			"Attribute `" + expected.getName() + "` is expected to be of plain type `" + expected.getPlainType() + "`!"
		);
		assertEquals(expected.getDefaultValue(), actual.getDefaultValue(), "Attribute `" + expected.getName() + "` is expected to have default value `" + expected.getDefaultValue() + "`!");
		assertEquals(expected.getIndexedDecimalPlaces(), actual.getIndexedDecimalPlaces(), "Attribute `" + expected.getName() + "` is expected to have indexed decimal places `" + expected.getIndexedDecimalPlaces() + "`!");
	}

	private static void assertSortableAttributeCompoundSchema(@Nonnull SortableAttributeCompoundSchemaContract expected, @Nonnull SortableAttributeCompoundSchemaContract actual) {
		assertSame(expected.getClass(), actual.getClass());

		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertArrayEquals(expected.getAttributeElements().toArray(), actual.getAttributeElements().toArray());
	}

	private static void assertAssociatedDataSchema(@Nonnull AssociatedDataSchemaContract expected, @Nonnull AssociatedDataSchemaContract actual) {
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.isLocalized(), actual.isLocalized());
		assertEquals(expected.isNullable(), actual.isNullable());
		assertSame(expected.getType(), actual.getType());
	}

	private static void assertReferenceSchema(@Nonnull ReferenceSchemaContract expected, @Nonnull ReferenceSchemaContract actual) {
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.getCardinality(), actual.getCardinality());
		assertEquals(expected.getReferencedEntityType(), actual.getReferencedEntityType());
		assertEquals(expected.isReferencedEntityTypeManaged(), actual.isReferencedEntityTypeManaged());
		assertEquals(expected.getReferencedGroupType(), actual.getReferencedGroupType());
		assertEquals(expected.isReferencedGroupTypeManaged(), actual.isReferencedGroupTypeManaged());
		assertEquals(expected.isIndexed(), actual.isIndexed());
		assertEquals(expected.getReferenceIndexTypeInScopes(), actual.getReferenceIndexTypeInScopes());
		assertEquals(expected.getIndexedComponentsInScopes(), actual.getIndexedComponentsInScopes());
		assertEquals(expected.isFaceted(), actual.isFaceted());
		assertEquals(expected.getFacetedPartiallyInScopes(), actual.getFacetedPartiallyInScopes());
		assertEquals(expected.getSortableAttributeCompounds(), actual.getSortableAttributeCompounds());

		assertEquals(expected.getAttributes().size(), actual.getAttributes().size());
		expected.getAttributes().forEach((attributeName, attribute) ->
			assertAttributeSchema(attribute, actual.getAttribute(attributeName).orElseThrow()));
	}

	@Test
	void shouldConvertSimpleEntitySchema() {
		final EntitySchema entitySchema = EntitySchema._internalBuild("product");
		final CatalogSchema catalogSchema = createCatalogSchemaWithSingleEntitySchema(entitySchema);
		final GrpcEntitySchema grpcEntitySchema = EntitySchemaConverter.convert(catalogSchema, entitySchema, true);
		assertEquals(catalogSchema.version(), grpcEntitySchema.getCatalogSchemaVersion());
		assertEntitySchema(
			entitySchema,
			EntitySchemaConverter.convert(grpcEntitySchema)
		);
	}

	@Test
	void shouldConvertComplexEntitySchema() {
		final EntitySchema entitySchema = createComplexEntitySchema();
		final CatalogSchema catalogSchema = createCatalogSchemaWithSingleEntitySchema(entitySchema);
		final GrpcEntitySchema grpcEntitySchema = EntitySchemaConverter.convert(catalogSchema, entitySchema, true);
		assertEquals(catalogSchema.version(), grpcEntitySchema.getCatalogSchemaVersion());
		assertEntitySchema(
			entitySchema,
			EntitySchemaConverter.convert(grpcEntitySchema)
		);
	}

	/**
	 * Verifies that {@link HistogramIndexDefinition#nameVariants()} is populated on the
	 * {@code GrpcScopedHistogramIndexDefinition} produced by {@link EntitySchemaConverter#convert}
	 * and that, after a round-trip through the reverse converter, the reconstructed definition
	 * carries the same per-convention variants as the originating {@link NamingConvention#generate}
	 * output.
	 *
	 * The gRPC proto marks {@code nameVariants} as output-only (ignored on mutation input). Clients
	 * re-derive the variants from the canonical name during reverse conversion — this test pins
	 * both ends: the wire carries the server-side variants, and the client-side reconstruction
	 * agrees with them byte-for-byte.
	 */
	@Test
	@DisplayName("should populate histogram nameVariants on gRPC output and match after round-trip")
	void shouldPopulateHistogramNameVariantsOnGrpcOutputAndMatchAfterRoundTrip() {
		final Expression valueExpr = ExpressionFactory.parse("$price * 1.21");
		final String canonicalName = "priceHistogram";
		final EntitySchema entitySchema = createEntitySchemaWithBucketedBrandRef(canonicalName, valueExpr);
		final CatalogSchema catalogSchema = createCatalogSchemaWithSingleEntitySchema(entitySchema);

		final GrpcEntitySchema grpcEntitySchema = EntitySchemaConverter.convert(catalogSchema, entitySchema, true);

		// --- forward direction: the gRPC message carries the expected nameVariants ---
		final GrpcReferenceSchema grpcBrandRef = grpcEntitySchema.getReferencesOrDefault(Entities.BRAND, null);
		assertNotNull(grpcBrandRef, "gRPC brand reference must be present");
		assertEquals(1, grpcBrandRef.getBucketedCount(), "brand reference must carry exactly one bucketed entry");

		final GrpcScopedHistogramIndexDefinition grpcHist = grpcBrandRef.getBucketed(0);
		assertEquals(canonicalName, grpcHist.getNameOfTheIndex());
		assertEquals(
			NamingConvention.values().length, grpcHist.getNameVariantsCount(),
			"Every NamingConvention must produce one GrpcNameVariant entry"
		);

		final Map<NamingConvention, String> expectedVariants = NamingConvention.generate(canonicalName);
		final Map<NamingConvention, String> grpcVariants = new EnumMap<>(NamingConvention.class);
		for (final GrpcNameVariant nv : grpcHist.getNameVariantsList()) {
			grpcVariants.put(EvitaEnumConverter.toNamingConvention(nv.getNamingConvention()), nv.getName());
		}
		assertEquals(
			expectedVariants, grpcVariants,
			"gRPC nameVariants must match the NamingConvention.generate output"
		);

		// --- reverse direction: the reconstructed definition carries the same variants ---
		final EntitySchemaContract roundTripped = EntitySchemaConverter.convert(grpcEntitySchema);
		final ReferenceSchemaContract brandRef = roundTripped.getReference(Entities.BRAND).orElseThrow();
		final HistogramIndexDefinition reconstructed = brandRef.getHistogramIndexDefinition(Scope.LIVE, canonicalName);

		assertNotNull(reconstructed, "Reconstructed histogram must be present after gRPC round-trip");
		assertEquals(canonicalName, reconstructed.nameOfTheIndex());
		assertEquals(
			expectedVariants, reconstructed.nameVariants(),
			"Reconstructed nameVariants must match the canonical generator output"
		);
		for (final NamingConvention convention : NamingConvention.values()) {
			assertEquals(
				expectedVariants.get(convention),
				reconstructed.getNameVariant(convention),
				"Reconstructed variant for " + convention + " must match the canonical output"
			);
		}

		// --- end-to-end: the reconstructed reference resolves by every variant ---
		for (final NamingConvention convention : NamingConvention.values()) {
			final String variant = expectedVariants.get(convention);
			assertTrue(
				brandRef.getHistogramIndexDefinitionByName(Scope.LIVE, variant, convention).isPresent(),
				"Reconstructed reference must resolve histogram by variant `" + variant + "` under " + convention
			);
		}
	}

	/**
	 * Builds a minimal {@link EntitySchema} whose `brand` reference is bucketed on the LIVE scope
	 * using the supplied canonical histogram name and expression. This is the gRPC test's fixture
	 * and intentionally keeps unrelated schema surface empty to isolate the bucketed payload.
	 *
	 * @param histogramName the canonical histogram index name
	 * @param valueExpr     the bucketed value expression
	 * @return a newly-built entity schema
	 */
	@Nonnull
	private static EntitySchema createEntitySchemaWithBucketedBrandRef(
		@Nonnull String histogramName,
		@Nonnull Expression valueExpr
	) {
		return createEntitySchemaWithBucketedBrandRef(histogramName, valueExpr, null);
	}

	/**
	 * Builds a minimal {@link EntitySchema} whose `brand` reference is bucketed on the LIVE scope
	 * using the supplied canonical histogram name, value expression, and optional per-histogram
	 * {@code assignedWhen} expression.
	 *
	 * @param histogramName  the canonical histogram index name
	 * @param valueExpr      the bucketed value expression
	 * @param assignedWhen   the optional per-histogram partition selector, or null when none
	 * @return a newly-built entity schema
	 */
	@Nonnull
	private static EntitySchema createEntitySchemaWithBucketedBrandRef(
		@Nonnull String histogramName,
		@Nonnull Expression valueExpr,
		@Nullable Expression assignedWhen
	) {
		return EntitySchema._internalBuild(
			1,
			Entities.PRODUCT,
			null, null,
			true,
			false,
			Scope.NO_SCOPE,
			true,
			new Scope[]{Scope.LIVE},
			2,
			Set.of(Locale.ENGLISH),
			Set.of(Currency.getInstance("EUR")),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Map.of(
				Entities.BRAND, ReferenceSchema._internalBuild(
					Entities.BRAND,
					NamingConvention.generate(Entities.BRAND),
					null, null,
					Entities.BRAND,
					NamingConvention.generate(Entities.BRAND),
					false,
					Cardinality.ZERO_OR_ONE,
					null,
					Collections.emptyMap(),
					false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(
							Scope.LIVE, histogramName, valueExpr, assignedWhen
						)
					},
					ScopedBucketedPartially.EMPTY,
					Collections.emptyMap(),
					Collections.emptyMap()
				)
			),
			Collections.emptySet(),
			Collections.emptyMap()
		);
	}

	/**
	 * Verifies that a per-histogram {@code assignedWhen} expression survives the gRPC
	 * round-trip — the canonical expression string serialized into
	 * {@link GrpcScopedHistogramIndexDefinition#getAssignedWhen()} on the outbound side
	 * and is parsed back into the reconstructed {@link HistogramIndexDefinition} on the
	 * inbound side. Pins both ends of the wire contract for the field.
	 */
	@Test
	@DisplayName("should round-trip per-histogram assignedWhen expression through gRPC")
	void shouldRoundTripAssignedWhenExpressionThroughGrpc() {
		final Expression valueExpr = ExpressionFactory.parse("$price * 1.21");
		final Expression assignedWhen = ExpressionFactory.parse("$stock > 0");
		final String canonicalName = "priceHistogram";

		final EntitySchema entitySchema = createEntitySchemaWithBucketedBrandRef(
			canonicalName, valueExpr, assignedWhen
		);
		final CatalogSchema catalogSchema = createCatalogSchemaWithSingleEntitySchema(entitySchema);

		final GrpcEntitySchema grpcEntitySchema =
			EntitySchemaConverter.convert(catalogSchema, entitySchema, true);

		// --- forward direction: the gRPC message carries the assignedWhen expression ---
		final GrpcReferenceSchema grpcBrandRef =
			grpcEntitySchema.getReferencesOrDefault(Entities.BRAND, null);
		assertNotNull(grpcBrandRef, "gRPC brand reference must be present");
		assertEquals(1, grpcBrandRef.getBucketedCount(), "brand must carry exactly one bucketed entry");

		final GrpcScopedHistogramIndexDefinition grpcHist = grpcBrandRef.getBucketed(0);
		assertTrue(
			grpcHist.hasAssignedWhen(),
			"gRPC message must carry the per-histogram assignedWhen expression"
		);
		assertEquals(
			assignedWhen.toExpressionString(),
			grpcHist.getAssignedWhen().getValue(),
			"Serialized assignedWhen string must match the canonical expression form"
		);

		// --- reverse direction: the reconstructed definition carries the same expression ---
		final EntitySchemaContract roundTripped = EntitySchemaConverter.convert(grpcEntitySchema);
		final ReferenceSchemaContract brandRef =
			roundTripped.getReference(Entities.BRAND).orElseThrow();
		final HistogramIndexDefinition reconstructed =
			brandRef.getHistogramIndexDefinition(Scope.LIVE, canonicalName);

		assertNotNull(reconstructed, "Reconstructed histogram must be present after gRPC round-trip");
		final Expression reconstructedExpr = reconstructed.assignedWhen();
		assertNotNull(
			reconstructedExpr,
			"Reconstructed assignedWhen must not be null when wire carried a value"
		);
		assertEquals(
			assignedWhen.toExpressionString(),
			reconstructedExpr.toExpressionString(),
			"Reconstructed assignedWhen must match the originating expression"
		);
	}
}
