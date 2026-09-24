/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.schema;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.Assertions.assertExactlyEquals;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link EntitySchema} serialization and deserialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(STORAGE)
@Tag(SCHEMA)
class SchemaSerializationServiceTest {

	@Test
	void shouldSerializeAndDeserializeSchema() {
		final EntitySchema productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		final Kryo kryo = KryoFactory.createKryo(SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE));
		final EntitySchemaContract createdSchema = constructSomeSchema(
				new InternalEntitySchemaBuilder(
						CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), null, EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
						productSchema
				)
		);

		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, createdSchema);
		}
		final byte[] serializedSchema = baos.toByteArray();
		assertNotNull(serializedSchema);
		assertTrue(serializedSchema.length > 0);

		final EntitySchema deserializedSchema;
		try (final Input input = new Input(new ByteArrayInputStream(serializedSchema))) {
			deserializedSchema = kryo.readObject(input, EntitySchema.class);
		}
		assertEquals(createdSchema, deserializedSchema);
		assertExactlyEquals(createdSchema, deserializedSchema);
	}

	@Test
	@DisplayName("should round-trip non-default conflict resolution settings through the schema serializers")
	void shouldRoundTripNonDefaultConflictResolutionSettings() {
		// non-default values on every axis: catalog/entity nullable ConflictResolution and per-item override enums
		final ConflictResolution entityResolution = new ConflictResolution(
			ConflictPolicy.ENTITY,
			EnumSet.of(GranularConflictPolicy.PRICE, GranularConflictPolicy.REFERENCE)
		);
		final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
			.withConflictResolution(entityResolution)
			.withAttribute(
				"code", String.class,
				whichIs -> whichIs.withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR)
			)
			.withAssociatedData(
				"labels", String.class,
				whichIs -> whichIs.withConflictResolutionOverride(ConflictResolutionOverride.ENTITY)
			)
			.withReferenceToEntity(
				Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withConflictResolutionOverride(ConflictResolutionOverride.ENTITY)
					.withAttribute(
						"brandCode", String.class,
						thatIs -> thatIs.withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR)
					)
			)
			.toInstance();

		final EntitySchema deserialized = roundTripEntitySchema(createKryo(), createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);

		// explicit non-default assertions — a silent drop would default these back to empty / INHERITED and,
		// because the field would still be readable at its default, the equals checks alone might not surface it
		assertEquals(
			entityResolution,
			deserialized.getConflictResolution().orElseThrow()
		);
		// entity-level attribute (EntityAttributeSchema → EntityAttributeSchemaSerializer)
		assertEquals(
			ConflictResolutionOverride.GRANULAR,
			deserialized.getAttribute("code").orElseThrow().getConflictResolutionOverride()
		);
		// per-reference override on the reference itself (ReferenceSchema → ReferenceSchemaSerializer)
		assertEquals(
			ConflictResolutionOverride.ENTITY,
			deserialized.getReference(Entities.BRAND).orElseThrow().getConflictResolutionOverride()
		);
		// reference-level (plain) attribute (AttributeSchema → AttributeSchemaSerializer) — distinct serializer from
		// the entity-level `code` attribute above, so it must be asserted independently
		assertEquals(
			ConflictResolutionOverride.GRANULAR,
			deserialized.getReference(Entities.BRAND).orElseThrow()
				.getAttribute("brandCode").orElseThrow().getConflictResolutionOverride()
		);
		// associated data (AssociatedDataSchema → AssociatedDataSchemaSerializer)
		assertEquals(
			ConflictResolutionOverride.ENTITY,
			deserialized.getAssociatedData("labels").orElseThrow().getConflictResolutionOverride()
		);
	}

	@Test
	@DisplayName("should round-trip per-scope filter accelerators through the schema serializers")
	void shouldRoundTripFilterAccelerators() {
		// every attribute of the shared fixture below is either plainly filterable or not filterable at all, so the
		// accelerator section is only ever written empty there - these attributes are what puts a non-empty map
		// through the writer and the reader
		final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
			)
			.withAttribute(
				"tags", String[].class,
				whichIs -> whichIs
					.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
					.acceleratedForInScope(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			)
			.withAttribute("ean", String.class, AttributeSchemaEditor::filterable)
			// the case the filterability-bound validation used to reject: `unique()` provides the filter index the
			// accelerator needs, so the attribute never has to be declared filterable to carry one
			.withAttribute(
				"code", String.class,
				whichIs -> whichIs.unique().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
			)
			.toInstance();

		final EntitySchema deserialized = roundTripEntitySchema(createKryo(), createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);

		// read explicitly rather than relying on the equality above: a writer and a reader that both dropped the
		// field would still compare equal, since the absent map is a perfectly valid state
		assertEquals(
			Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
			deserialized.getAttribute("name").orElseThrow().getAcceleratorsInScope(Scope.LIVE)
		);
		// a String[] attribute carries the capability just as a String one does …
		assertEquals(
			Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
			deserialized.getAttribute("tags").orElseThrow().getAcceleratorsInScope(Scope.LIVE)
		);
		// … while its archived scope stays filterable with no acceleration declared, which is a different state from
		// "not filterable" and must survive as such
		assertTrue(deserialized.getAttribute("tags").orElseThrow().isFilterableInScope(Scope.ARCHIVED));
		assertEquals(
			Set.of(),
			deserialized.getAttribute("tags").orElseThrow().getAcceleratorsInScope(Scope.ARCHIVED)
		);
		// the size-prefixed empty section a plain `filterable()` writes must not read back as a spurious entry
		assertTrue(
			deserialized.getAttribute("ean").orElseThrow().getAcceleratorsInScopes().isEmpty(),
			"a plainly filterable attribute came back carrying an accelerator entry"
		);
		// the unique-only attribute keeps both its uniqueness and the accelerator riding on the uniqueness index
		final AttributeSchemaContract uniqueOnly = deserialized.getAttribute("code").orElseThrow();
		assertFalse(uniqueOnly.isFilterableInScope(Scope.LIVE));
		assertTrue(uniqueOnly.isUniqueInScope(Scope.LIVE));
		assertEquals(
			Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
			uniqueOnly.getAcceleratorsInScope(Scope.LIVE)
		);
	}

	@Test
	@DisplayName("should round-trip a non-default catalog-level conflict resolution through the schema serializers")
	void shouldRoundTripNonDefaultCatalogLevelConflictResolution() {
		// a non-default catalog-level resolution on every axis: coarse policy plus a granularity subset
		final ConflictResolution catalogResolution = new ConflictResolution(
			ConflictPolicy.ENTITY,
			EnumSet.of(GranularConflictPolicy.PRICE, GranularConflictPolicy.REFERENCE)
		);
		final CatalogSchema createdSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			catalogResolution,
			EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);

		final CatalogSchema deserialized = roundTripCatalogSchema(createKryo(), createdSchema);

		// a silent drop would default this back to empty (inherited); read it explicitly so the loss surfaces
		assertEquals(
			catalogResolution,
			deserialized.getConflictResolution().orElseThrow()
		);
	}

	@Test
	@DisplayName("should round-trip a non-default global attribute conflict resolution override through the schema serializers")
	void shouldRoundTripGlobalAttributeConflictResolutionOverride() {
		// a global attribute embedded in the catalog schema carrying a non-default override — a serializer that silently
		// dropped the override on GlobalAttributeSchema would still pass every catalog-level ConflictResolution test
		final GlobalAttributeSchema globalAttribute = GlobalAttributeSchema._internalBuild(
			"url", String.class, false, ConflictResolutionOverride.GRANULAR
		);
		final Map<String, GlobalAttributeSchemaContract> attributes = Map.of("url", globalAttribute);
		final CatalogSchema createdSchema = CatalogSchema._internalBuild(
			1,
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			null,
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			attributes,
			EmptyEntitySchemaAccessor.INSTANCE
		);

		final CatalogSchema deserialized = roundTripCatalogSchema(createKryo(), createdSchema);

		// a silent drop would default this back to INHERITED; read it explicitly so the loss surfaces
		assertEquals(
			ConflictResolutionOverride.GRANULAR,
			deserialized.getAttribute("url").orElseThrow().getConflictResolutionOverride()
		);
	}

	/**
	 * Verifies round-trip serialization of a {@link ReferenceSchema} with populated bucketed fields
	 * (non-empty `bucketedInScopes` with {@link HistogramIndexDefinition} and non-empty
	 * `bucketedPartiallyInScopes`), as well as a reference with empty bucketed fields.
	 * Also covers the case where {@link HistogramIndexDefinition} has a null `valueExpression`.
	 */
	@Test
	void shouldSerializeAndDeserializeReferenceSchemaWithBucketedHistogram() {
		final Kryo kryo = createKryo();
		final Expression valueExpression = ExpressionFactory.parse("$price * 1.21");
		final Expression partiallyExpression = ExpressionFactory.parse("1 > 0");

		final EntitySchemaContract createdSchema = constructSchemaWithBucketedReferences(
			createEntitySchemaBuilder(),
			valueExpression,
			partiallyExpression,
			null
		);

		final EntitySchema deserialized = roundTripEntitySchema(kryo, createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);

		// verify bucketed fields on the brand reference (populated bucketed with expression)
		final ReferenceSchemaContract brandRef = deserialized.getReference(Entities.BRAND).orElseThrow();
		final HistogramIndexDefinition brandDef = brandRef.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram");
		assertNotNull(brandDef);
		assertEquals("priceHistogram", brandDef.nameOfTheIndex());
		assertEquals(valueExpression, brandDef.valueExpression());
		assertEquals(partiallyExpression, brandRef.getBucketedPartiallyInScopes().get(Scope.LIVE));

		// verify bucketed fields on the stock reference (bucketed with null valueExpression)
		final ReferenceSchemaContract stockRef = deserialized.getReference("stock").orElseThrow();
		final HistogramIndexDefinition stockDef = stockRef.getHistogramIndexDefinition(Scope.LIVE, "stockIdx");
		assertNotNull(stockDef);
		assertEquals("stockIdx", stockDef.nameOfTheIndex());
		assertNull(stockDef.valueExpression(), "valueExpression should be null");

		// verify bucketed fields on the category reference (empty bucketed)
		final ReferenceSchemaContract categoryRef = deserialized.getReference(Entities.CATEGORY).orElseThrow();
		assertTrue(categoryRef.getAllHistogramIndexDefinitions().isEmpty());
		assertTrue(categoryRef.getBucketedPartiallyInScopes().isEmpty());
	}

	/**
	 * Verifies that the per-histogram `assignedWhen` partition selector — the fourth-positional
	 * component of {@link HistogramIndexDefinition} — survives a full Kryo round-trip
	 * through the entity-schema serializer. Pins the `writeBucketedHistogramMap` /
	 * `readBucketedHistogramMap` branch that codecs the optional expression behind a
	 * boolean prefix: one histogram on the brand reference carries the partition selector,
	 * the second histogram on the same reference carries `null` — both arms of
	 * the codec branch are exercised in a single round-trip.
	 */
	@Test
	@Tag(HISTOGRAM)
	@DisplayName("should round-trip assignedWhen partition selector through entity schema serializer")
	void shouldRoundTripAssignedWhenThroughEntitySchemaSerializer() {
		final Kryo kryo = createKryo();
		final Expression filteredValueExpr = ExpressionFactory.parse("$price * 1.21");
		final Expression assignedWhen = ExpressionFactory.parse("$active == 1");
		final Expression plainValueExpr = ExpressionFactory.parse("$quantity + 1");

		final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.faceted()
					.bucketedInScope(
						Scope.DEFAULT_SCOPE, "filteredHistogram",
						filteredValueExpr, assignedWhen
					)
					.bucketedInScope(
						Scope.DEFAULT_SCOPE, "plainHistogram", plainValueExpr, null
					)
			)
			.toInstance();

		final EntitySchema deserialized = roundTripEntitySchema(kryo, createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);

		final ReferenceSchemaContract brandRef =
			deserialized.getReference(Entities.BRAND).orElseThrow();

		final HistogramIndexDefinition filteredDef =
			brandRef.getHistogramIndexDefinition(Scope.DEFAULT_SCOPE, "filteredHistogram");
		assertNotNull(filteredDef, "filteredHistogram must survive round-trip");
		assertEquals(filteredValueExpr, filteredDef.valueExpression());
		assertNotNull(
			filteredDef.assignedWhen(),
			"Per-histogram assignedWhen must be preserved through entity-schema serialization"
		);
		assertEquals(
			assignedWhen.toExpressionString(),
			filteredDef.assignedWhen().toExpressionString(),
			"Per-histogram assignedWhen expression must round-trip unchanged"
		);

		final HistogramIndexDefinition plainDef =
			brandRef.getHistogramIndexDefinition(Scope.DEFAULT_SCOPE, "plainHistogram");
		assertNotNull(plainDef, "plainHistogram must survive round-trip");
		assertEquals(plainValueExpr, plainDef.valueExpression());
		assertNull(
			plainDef.assignedWhen(),
			"Histogram declared without a per-histogram partition selector must round-trip with null"
		);
	}

	/**
	 * Verifies round-trip serialization of a reference schema containing two distinct
	 * histogram definitions in the same scope, ensuring both survive the round-trip.
	 */
	@Test
	void shouldSerializeAndDeserializeMultipleHistogramsPerScope() {
		final Kryo kryo = createKryo();
		final Expression priceExpr = ExpressionFactory.parse("$price * 1.21");
		final Expression quantityExpr = ExpressionFactory.parse("$quantity + 1");

		final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.faceted()
					.bucketed("priceHistogram", priceExpr)
					.bucketed("quantityHistogram", quantityExpr)
			)
			.toInstance();

		final EntitySchema deserialized = roundTripEntitySchema(kryo, createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);

		final ReferenceSchemaContract brandRef =
			deserialized.getReference(Entities.BRAND).orElseThrow();

		final Map<Scope, Map<String, HistogramIndexDefinition>> allDefs =
			brandRef.getAllHistogramIndexDefinitions();
		assertEquals(
			1, allDefs.size(),
			"Should have exactly 1 scope entry"
		);
		assertEquals(
			2, allDefs.get(Scope.LIVE).size(),
			"LIVE scope should contain 2 histograms"
		);

		final HistogramIndexDefinition priceDef =
			brandRef.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram");
		assertNotNull(priceDef, "priceHistogram should survive round-trip");
		assertEquals("priceHistogram", priceDef.nameOfTheIndex());
		assertEquals(priceExpr, priceDef.valueExpression());

		final HistogramIndexDefinition quantityDef =
			brandRef.getHistogramIndexDefinition(Scope.LIVE, "quantityHistogram");
		assertNotNull(quantityDef, "quantityHistogram should survive round-trip");
		assertEquals("quantityHistogram", quantityDef.nameOfTheIndex());
		assertEquals(quantityExpr, quantityDef.valueExpression());
	}

	/**
	 * Verifies that the {@link HistogramIndexDefinition#nameVariants()} map survives a full Kryo
	 * round-trip of the enclosing entity schema — not just the canonical name. This guards against
	 * regressions in {@code EntitySchemaSerializer.writeBucketedHistogramMap} /
	 * {@code readBucketedHistogramMap} where an accidental omission of the variants block would
	 * leave the deserialized definition with stale or empty variants and break name-variant lookup
	 * (e.g. {@code getHistogramIndexDefinitionByName}).
	 *
	 * The assertion compares the deserialized variant map against the authoritative
	 * {@link NamingConvention#generate(String)} output as well as variant-by-variant, so that a
	 * future addition of a naming convention surfaces in this test instead of silently passing.
	 */
	@Test
	void shouldPreserveHistogramNameVariantsOnRoundTrip() {
		final Kryo kryo = createKryo();
		final Expression priceExpr = ExpressionFactory.parse("$price * 1.21");
		final String canonicalName = "priceHistogram";

		final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.faceted()
					.bucketed(canonicalName, priceExpr)
			)
			.toInstance();

		final EntitySchema deserialized = roundTripEntitySchema(kryo, createdSchema);

		final ReferenceSchemaContract brandRef =
			deserialized.getReference(Entities.BRAND).orElseThrow();
		final HistogramIndexDefinition priceDef =
			brandRef.getHistogramIndexDefinition(Scope.LIVE, canonicalName);
		assertNotNull(priceDef, "priceHistogram should survive round-trip");

		// full map equality against the canonical generator — catches any silently-dropped entry
		final Map<NamingConvention, String> expectedVariants = NamingConvention.generate(canonicalName);
		assertEquals(
			expectedVariants, priceDef.nameVariants(),
			"nameVariants must match NamingConvention.generate output after Kryo round-trip"
		);

		// per-convention assertion: ensures the accessor contract works, not just map equality
		for (final NamingConvention convention : NamingConvention.values()) {
			assertEquals(
				expectedVariants.get(convention),
				priceDef.getNameVariant(convention),
				"Variant for " + convention + " must survive round-trip unchanged"
			);
		}

		// end-to-end lookup proof: the deserialized reference must resolve the histogram by every
		// convention variant — this is what downstream external APIs actually rely on.
		for (final NamingConvention convention : NamingConvention.values()) {
			final String variant = expectedVariants.get(convention);
			assertEquals(
				canonicalName,
				brandRef.getHistogramIndexDefinitionByName(Scope.LIVE, variant, convention)
					.orElseThrow(() -> new AssertionError(
						"Expected to resolve variant `" + variant + "` under " + convention
					))
					.nameOfTheIndex(),
				"Lookup by variant `" + variant + "` under " + convention + " must resolve to the canonical histogram"
			);
		}
	}

	/**
	 * Verifies round-trip serialization of a {@link ReflectedReferenceSchema} with explicit
	 * bucketed state.
	 */
	@Test
	void shouldSerializeAndDeserializeReflectedReferenceSchemaWithExplicitBucketed() {
		final Kryo kryo = createKryo();
		final Expression valueExpression = ExpressionFactory.parse("$price * 1.21");
		final Expression partiallyExpression = ExpressionFactory.parse("1 > 0");

		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedInScopes = new EnumMap<>(Scope.class);
		bucketedInScopes.put(Scope.LIVE, Map.of("refIdx", HistogramIndexDefinition.of("refIdx", valueExpression)));

		final Map<Scope, Expression> bucketedPartiallyInScopes = new EnumMap<>(Scope.class);
		bucketedPartiallyInScopes.put(Scope.LIVE, partiallyExpression);

		final ReflectedReferenceSchema base = createBaseReflectedReferenceSchema();
		final ReflectedReferenceSchema withBucketed =
			(ReflectedReferenceSchema) base.withBucketed(bucketedInScopes);
		final ReflectedReferenceSchema withBoth = withBucketed.withBucketedPartially(bucketedPartiallyInScopes);

		final ReflectedReferenceSchema deserialized = roundTripReflectedReferenceSchema(kryo, withBoth);

		assertEquals(withBoth, deserialized);
		assertEquals(bucketedInScopes, deserialized.getAllHistogramIndexDefinitions());
		assertEquals(bucketedPartiallyInScopes, deserialized.getBucketedPartiallyInScopes());
	}

	/**
	 * Verifies round-trip serialization of a {@link ReflectedReferenceSchema} with no
	 * bucketed configuration. This exercises the branch where the serializer writes
	 * {@code false} (no bucketed data follows).
	 */
	@Test
	void shouldSerializeAndDeserializeReflectedReferenceSchemaWithInheritedBucketed() {
		final Kryo kryo = createKryo();
		final ReflectedReferenceSchema base = createBaseReflectedReferenceSchema();

		final ReflectedReferenceSchema deserialized = roundTripReflectedReferenceSchema(kryo, base);

		assertEquals(base, deserialized);
		assertTrue(deserialized.getAllHistogramIndexDefinitions().isEmpty());
		assertTrue(deserialized.getBucketedPartiallyInScopes().isEmpty());
	}

	/**
	 * Verifies round-trip serialization of a {@link ReflectedReferenceSchema} with explicit but
	 * empty bucketed maps. After round-trip, the serializer reconstructs the schema via
	 * {@code _internalBuild} + {@code withBucketed}, preserving the explicit empty state.
	 */
	@Test
	void shouldSerializeAndDeserializeReflectedReferenceSchemaWithNonInheritedEmptyBucketed() {
		final Kryo kryo = createKryo();
		final ReflectedReferenceSchema base = createBaseReflectedReferenceSchema();
		final ReflectedReferenceSchema withEmptyBucketed =
			(ReflectedReferenceSchema) base.withBucketed(Collections.emptyMap());

		final ReflectedReferenceSchema deserialized = roundTripReflectedReferenceSchema(kryo, withEmptyBucketed);

		// the bucketed maps should be empty after round-trip
		assertTrue(deserialized.getAllHistogramIndexDefinitions().isEmpty());
		assertTrue(deserialized.getBucketedPartiallyInScopes().isEmpty());
	}

	/**
	 * Creates a pre-configured {@link Kryo} instance with schema and shared serializers registered.
	 *
	 * @return a new Kryo instance
	 */
	@Nonnull
	private static Kryo createKryo() {
		return KryoFactory.createKryo(SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE));
	}

	/**
	 * Creates a new {@link InternalEntitySchemaBuilder} for the product entity type.
	 *
	 * @return a new entity schema builder
	 */
	@Nonnull
	private static InternalEntitySchemaBuilder createEntitySchemaBuilder() {
		return new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(
				TestConstants.TEST_CATALOG,
				NamingConvention.generate(TestConstants.TEST_CATALOG),
				null,
				EnumSet.allOf(CatalogEvolutionMode.class),
				EmptyEntitySchemaAccessor.INSTANCE
			),
			EntitySchema._internalBuild(Entities.PRODUCT)
		);
	}

	/**
	 * Serializes and deserializes an {@link EntitySchemaContract} via Kryo, returning the deserialized result.
	 *
	 * @param kryo   the Kryo instance to use
	 * @param schema the entity schema to round-trip
	 * @return the deserialized entity schema
	 */
	@Nonnull
	private static EntitySchema roundTripEntitySchema(@Nonnull Kryo kryo, @Nonnull EntitySchemaContract schema) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, schema);
		}
		final byte[] bytes = baos.toByteArray();
		assertNotNull(bytes);
		assertTrue(bytes.length > 0);
		try (final Input input = new Input(new ByteArrayInputStream(bytes))) {
			return kryo.readObject(input, EntitySchema.class);
		}
	}

	/**
	 * Serializes and deserializes a {@link CatalogSchema} via Kryo, returning the deserialized result. The read is
	 * wrapped in a deserialization context supplying a mock catalog, mirroring the real catalog-storage read path
	 * that {@code CatalogSchemaSerializer} relies on to resolve nested entity schemas.
	 *
	 * @param kryo   the Kryo instance to use
	 * @param schema the catalog schema to round-trip
	 * @return the deserialized catalog schema
	 */
	@Nonnull
	private static CatalogSchema roundTripCatalogSchema(@Nonnull Kryo kryo, @Nonnull CatalogSchema schema) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, schema);
		}
		final byte[] bytes = baos.toByteArray();
		assertNotNull(bytes);
		assertTrue(bytes.length > 0);
		try (final Input input = new Input(new ByteArrayInputStream(bytes))) {
			return CatalogSchemaStoragePart.deserializeWithCatalog(
				Mockito.mock(CatalogContract.class),
				() -> kryo.readObject(input, CatalogSchema.class)
			);
		}
	}

	/**
	 * Serializes and deserializes a {@link ReflectedReferenceSchema} via Kryo, returning the deserialized result.
	 *
	 * @param kryo   the Kryo instance to use
	 * @param schema the reflected reference schema to round-trip
	 * @return the deserialized reflected reference schema
	 */
	@Nonnull
	private static ReflectedReferenceSchema roundTripReflectedReferenceSchema(
		@Nonnull Kryo kryo,
		@Nonnull ReflectedReferenceSchema schema
	) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, schema);
		}
		final byte[] bytes = baos.toByteArray();
		assertNotNull(bytes);
		assertTrue(bytes.length > 0);
		try (final Input input = new Input(new ByteArrayInputStream(bytes))) {
			return kryo.readObject(input, ReflectedReferenceSchema.class);
		}
	}

	/**
	 * Creates a base {@link ReflectedReferenceSchema} suitable for bucketed serialization tests.
	 * The returned schema has no bucketed configuration. Callers can subsequently call
	 * {@link ReflectedReferenceSchema#withBucketed(Map)} to add explicit bucketed settings.
	 *
	 * @return a new reflected reference schema without bucketed configuration
	 */
	@Nonnull
	private static ReflectedReferenceSchema createBaseReflectedReferenceSchema() {
		final Map<Scope, ReferenceIndexType> indexedInScopes = new EnumMap<>(Scope.class);
		indexedInScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
		return ReflectedReferenceSchema._internalBuild(
			"referencedInCategories",
			NamingConvention.generate("referencedInCategories"),
			null, null,
			Entities.CATEGORY,
			"productsInCategory",
			null,
			indexedInScopes, null, null, null, null, null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			null
		);
	}

	/**
	 * Builds an entity schema with references that exercise various bucketed histogram configurations:
	 * - brand reference: bucketed with a value expression, a reference-level `bucketedPartially`
	 *   eligibility gate, and an optional per-histogram `assignedWhen` partition selector
	 * - stock reference: bucketed with null value expression (no expression branch)
	 * - category reference: no bucketed configuration (empty bucketed maps)
	 *
	 * @param schemaBuilder       the entity schema builder to use
	 * @param valueExpression     the expression for the bucketed histogram value
	 * @param partiallyExpression the reference-level eligibility gate expression
	 * @param assignedWhen        the optional per-histogram partition selector applied to the brand
	 *                            reference's `priceHistogram` only; `null` means no per-histogram
	 *                            restriction
	 * @return the built entity schema
	 */
	@Nonnull
	@SuppressWarnings("Convert2MethodRef")
	private static EntitySchemaContract constructSchemaWithBucketedReferences(
		@Nonnull InternalEntitySchemaBuilder schemaBuilder,
		@Nonnull Expression valueExpression,
		@Nonnull Expression partiallyExpression,
		@Nullable Expression assignedWhen
	) {
		return schemaBuilder
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.faceted()
					.bucketedInScope(
						Scope.DEFAULT_SCOPE, "priceHistogram", valueExpression, assignedWhen
					)
					.bucketedPartially(partiallyExpression)
			)
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.faceted()
					.bucketed("stockIdx", null)
			)
			.toInstance();
	}

	@Nonnull
	@SuppressWarnings("Convert2MethodRef")
	private static EntitySchemaContract constructSomeSchema(@Nonnull InternalEntitySchemaBuilder schemaBuilder) {
		return schemaBuilder
			/* all is strictly verified but associated data and facets can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define facets that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexedForFilteringAndPartitioning()
						.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for indexed facets we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted()
			)
			/* facets may be also represented be entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* we can create reflected references to other entities */
			.withReflectedReferenceToEntity(
				"referencedInCategories",
				Entities.CATEGORY,
				"productsInCategory",
				whichIs -> {
					whichIs.withAttributesInheritedExcept("categoryPriority");
				}
			)
			/* finally apply schema changes */
			.toInstance();
	}

	@Data
	public static class ReferencedFileSet implements Serializable {
		@Serial private static final long serialVersionUID = -1355676966187183143L;
		private String someField = "someValue";

	}

	@Data
	public static class Labels implements Serializable {
		@Serial private static final long serialVersionUID = 1121150156843379388L;
		private String someField = "someValue";

	}

}
