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
import io.evitadb.api.exception.InvalidSchemaMutationException;
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
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.store.schema.serializer.ReferenceSchemaSerializer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.test.Assertions.assertExactlyEquals;
import static io.evitadb.test.TestTags.FULLTEXT;
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

	/**
	 * Name of the reference built by the stored-shape tests, on whichever side it is declared.
	 */
	private static final String STORED_SHAPE_REFERENCE = "storedShapeReference";
	/**
	 * Name of the category-side reference the stored-shape reflected references reflect.
	 */
	private static final String STORED_SHAPE_REFLECTED_NAME = "productsInCategory";
	/**
	 * Group entity type of every stored-shape reference - the group component is only declared on grouped references.
	 */
	private static final String STORED_SHAPE_GROUP_TYPE = "BrandGroup";
	/**
	 * Serial version UID under which the latest released format stores a {@link ReferenceSchema}, routed to
	 * `ReferenceSchemaSerializer_2026_2` by the version-routing serializer.
	 */
	private static final long REFERENCE_SCHEMA_RELEASED_FORMAT_UID = 5443565766311111159L;

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
	@DisplayName("should round-trip the searchable scopes through all three attribute schema serializers")
	@Tag(FULLTEXT)
	void shouldRoundTripSearchableScopes() {
		// three distinct serializers carry the field - entity attribute, reference attribute and global attribute -
		// and each appends it after the accelerators, so an accelerator is declared alongside to prove the two
		// trailing sections do not overlap
		final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs.localized()
					.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
					.searchableInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.withAttribute("description", String.class, whichIs -> whichIs.localized().searchable())
			.withReferenceToEntity(
				Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.withAttribute(
					"brandName", String[].class, thatIs -> thatIs.localized().searchable()
				)
			)
			.toInstance();

		final EntitySchema deserialized = roundTripEntitySchema(createKryo(), createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);
		// read explicitly - a writer and a reader that both dropped the field would still compare equal
		final AttributeSchemaContract name = deserialized.getAttribute("name").orElseThrow();
		assertEquals(EnumSet.of(Scope.LIVE, Scope.ARCHIVED), name.getSearchableInScopes());
		assertEquals(Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH), name.getAcceleratorsInScope(Scope.LIVE));
		assertEquals(
			EnumSet.of(Scope.LIVE), deserialized.getAttribute("description").orElseThrow().getSearchableInScopes()
		);
		assertEquals(
			EnumSet.of(Scope.LIVE),
			deserialized.getReference(Entities.BRAND).orElseThrow()
				.getAttribute("brandName").orElseThrow().getSearchableInScopes()
		);

		final GlobalAttributeSchema globalAttribute = GlobalAttributeSchema._internalBuild(
			"title", null, null,
			// the untyped nulls would leave the inherited AttributeSchema overload equally applicable
			(ScopedAttributeUniquenessType[]) null, (ScopedGlobalAttributeUniquenessType[]) null,
			Scope.NO_SCOPE, null, new Scope[]{Scope.ARCHIVED}, Scope.NO_SCOPE,
			true, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);
		final CatalogSchema createdCatalogSchema = CatalogSchema._internalBuild(
			1,
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			null,
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			Map.of("title", globalAttribute),
			EmptyEntitySchemaAccessor.INSTANCE
		);

		final CatalogSchema deserializedCatalogSchema = roundTripCatalogSchema(createKryo(), createdCatalogSchema);

		assertEquals(
			EnumSet.of(Scope.ARCHIVED),
			deserializedCatalogSchema.getAttribute("title").orElseThrow().getSearchableInScopes()
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
	 * Covers the read path for the reference shapes the entity-component schema rule refuses: an indexed scope whose
	 * components lack `REFERENCED_ENTITY` - either group-only, or empty. Catalogs written before the rule existed store
	 * these shapes, and they must keep loading; the rule therefore lives in `validate()` and nowhere on the read path.
	 *
	 * Most tests write the shape with the current serializer and read it back with the current reader, then, for
	 * reflected references, re-bind them to the reference they reflect
	 * ({@link ReflectedReferenceSchema#withReferencedSchema}), which runs construction-time scope validation of its own.
	 * That proves the readers and the re-binding accept the shape, not that bytes written by a released version decode.
	 * The released format of a plain reference is read by a backward-compatible reader of its own, so one test routes
	 * the shape through it; a reflected reference stored by the latest release is read by the current reader.
	 */
	@Nested
	@DisplayName("Loading reference shapes that lack REFERENCED_ENTITY in an indexed scope")
	class StoredShapesWithoutEntityComponent {

		@Test
		@DisplayName("should deserialize an entity schema whose reference is indexed only for the group component")
		void shouldDeserializeEntitySchemaWithGroupOnlyReference() {
			final EntitySchemaContract createdSchema = createEntitySchemaBuilder()
				.withReferenceToEntity(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
						.withGroupTypeRelatedToEntity(STORED_SHAPE_GROUP_TYPE)
						.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
						.indexedWithComponentsInScope(
							Scope.ARCHIVED,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						)
				)
				.toInstance();

			final EntitySchema deserialized = roundTripEntitySchema(createKryo(), createdSchema);

			assertEquals(createdSchema, deserialized, "The group-only reference must survive the round trip intact");
			final ReferenceSchemaContract brand = deserialized.getReference(Entities.BRAND).orElseThrow();
			assertEquals(
				Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
				brand.getIndexedComponents(Scope.LIVE),
				"The stored group-only LIVE scope must load exactly as stored - no default filled in, no refusal"
			);
			assertTrue(brand.isIndexedInScope(Scope.LIVE), "The group-only LIVE scope must stay indexed");
		}

		@Test
		@DisplayName("should deserialize a reference schema indexed with an empty component set")
		void shouldDeserializeReferenceSchemaWithEmptyComponentsInIndexedScope() {
			final ReferenceSchema created = buildStoredReferenceSchema(
				STORED_SHAPE_REFERENCE, Entities.BRAND, Map.of(Scope.LIVE, Collections.emptySet())
			);
			assertEquals(
				Set.of(), created.getIndexedComponents(Scope.LIVE),
				"The premise is an indexed LIVE scope with no component"
			);

			final ReferenceSchema deserialized = roundTripReferenceSchema(createKryo(), created);

			assertEquals(created, deserialized, "The empty-component reference must survive the round trip intact");
			assertTrue(deserialized.isIndexedInScope(Scope.LIVE), "The empty-component LIVE scope must stay indexed");
			assertEquals(
				Set.of(), deserialized.getIndexedComponents(Scope.LIVE),
				"The stored empty component set must load exactly as stored - no default filled in, no refusal"
			);
		}

		@Test
		@DisplayName("should read group-only and empty scopes of a reference stored in the released format")
		void shouldReadGroupOnlyAndEmptyScopesOfAReferenceStoredInTheReleasedFormat() {
			final Map<Scope, Set<ReferenceIndexedComponents>> components = new EnumMap<>(Scope.class);
			components.put(Scope.LIVE, EnumSet.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
			components.put(Scope.ARCHIVED, EnumSet.noneOf(ReferenceIndexedComponents.class));
			final ReferenceSchema created = buildStoredReferenceSchema(
				STORED_SHAPE_REFERENCE, Entities.BRAND, components, Scope.LIVE, Scope.ARCHIVED
			);
			final Kryo kryo = createKryo();

			// the released format is the current payload without the trailing conflict resolution override, stored
			// under the serial version UID the version-routing serializer hands to ReferenceSchemaSerializer_2026_2
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
			try (final Output output = new Output(baos)) {
				output.writeLong(REFERENCE_SCHEMA_RELEASED_FORMAT_UID);
				new ReferenceSchemaSerializer().write(kryo, output, created);
			}
			final ReferenceSchema deserialized;
			try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
				deserialized = kryo.readObject(input, ReferenceSchema.class);
			}

			assertEquals(
				"LIVE=[REFERENCED_GROUP_ENTITY] ARCHIVED=[]",
				"LIVE=" + deserialized.getIndexedComponents(Scope.LIVE) +
					" ARCHIVED=" + deserialized.getIndexedComponents(Scope.ARCHIVED),
				"Both stored scopes must load exactly as stored - no default filled in, no refusal"
			);
			assertTrue(
				deserialized.isIndexedInScope(Scope.LIVE) && deserialized.isIndexedInScope(Scope.ARCHIVED),
				"Both stored scopes must stay indexed"
			);
		}

		@Test
		@DisplayName("should deserialize and rebind a reflected reference declaring group-only components")
		void shouldDeserializeAndRebindReflectedReferenceWithGroupOnlyComponents() {
			final Map<Scope, Set<ReferenceIndexedComponents>> groupOnly =
				Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
			final ReflectedReferenceSchema created = buildStoredReflectedReferenceSchema(groupOnly);

			final ReflectedReferenceSchema rebound = roundTripReflectedReferenceSchema(createKryo(), created)
				.withReferencedSchema(
					buildStoredReferenceSchema(STORED_SHAPE_REFLECTED_NAME, Entities.PRODUCT, groupOnly)
				);

			assertFalse(rebound.isIndexedComponentsInherited(), "The premise is an explicit component declaration");
			assertEquals(
				Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
				rebound.getIndexedComponents(Scope.LIVE),
				"The stored group-only reflected reference must load and rebind exactly as stored"
			);
		}

		/**
		 * The most likely stored form: a reflected reference that inherits its components from a source reference
		 * that is itself group-only. The components reach it only through the rebinding, so this is the path that
		 * would break first.
		 */
		@Test
		@DisplayName("should deserialize and rebind a reflected reference inheriting group-only components")
		void shouldDeserializeAndRebindReflectedReferenceInheritingGroupOnlyComponents() {
			final ReflectedReferenceSchema created = buildStoredReflectedReferenceSchema(null);

			final ReflectedReferenceSchema rebound = roundTripReflectedReferenceSchema(createKryo(), created)
				.withReferencedSchema(
					buildStoredReferenceSchema(
						STORED_SHAPE_REFLECTED_NAME, Entities.PRODUCT,
						Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY))
					)
				);

			assertTrue(rebound.isIndexedComponentsInherited(), "The premise is inherited components");
			assertEquals(
				Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
				rebound.getIndexedComponents(Scope.LIVE),
				"The group-only components inherited from the stored source must load without a refusal"
			);
		}

		@Test
		@DisplayName("should deserialize and rebind a reflected reference declaring an empty component set")
		void shouldDeserializeAndRebindReflectedReferenceWithEmptyComponents() {
			final ReflectedReferenceSchema created = buildStoredReflectedReferenceSchema(
				Map.of(Scope.LIVE, Collections.emptySet())
			);

			final ReflectedReferenceSchema rebound = roundTripReflectedReferenceSchema(createKryo(), created)
				.withReferencedSchema(
					buildStoredReferenceSchema(
						STORED_SHAPE_REFLECTED_NAME, Entities.PRODUCT,
						Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY))
					)
				);

			assertTrue(rebound.isIndexedInScope(Scope.LIVE), "The empty-component LIVE scope must stay indexed");
			assertEquals(
				Set.of(), rebound.getIndexedComponents(Scope.LIVE),
				"The stored empty component set must load and rebind exactly as stored"
			);
		}

		/**
		 * The stored form of the route that left reflected references on archived owners unindexed: explicit
		 * components naming {@link Scope#LIVE} alone, scopes inherited from a reference indexed in both. Schema-change
		 * paths now complete the uncovered scope with the default component, but a catalog stored before that has
		 * never indexed anything there - so loading must leave the scope empty, and the schema rule must keep refusing
		 * it. Filling it on load would make the scope claim `REFERENCED_ENTITY` over indexes that were never built,
		 * and silence exactly the checks that exist to say so.
		 *
		 * The second half pins the same for a later schema change: a scope the reference was already indexed in is
		 * not completed by re-binding either, so an unrelated change to the reference it reflects cannot quietly turn
		 * the stored empty scope into one that looks healthy.
		 */
		@Test
		@DisplayName("should keep a stored uncovered scope empty on load so that validation still refuses it")
		void shouldKeepAStoredUncoveredScopeEmptyOnLoadSoThatValidationStillRefusesIt() {
			final ReferenceSchema originalReference = buildStoredReferenceSchema(
				STORED_SHAPE_REFLECTED_NAME, Entities.PRODUCT,
				Map.of(
					Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
					Scope.ARCHIVED, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)
				),
				Scope.LIVE, Scope.ARCHIVED
			);
			final ReflectedReferenceSchema created = buildStoredReflectedReferenceSchemaInheritingScopes(
				Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY))
			);

			// the binding catalog load performs
			final ReflectedReferenceSchema loaded = roundTripReflectedReferenceSchema(createKryo(), created)
				.withReferencedSchema(originalReference);

			assertTrue(loaded.isIndexedInherited(), "The premise is scopes inherited from the reflected reference");
			assertFalse(loaded.isIndexedComponentsInherited(), "The premise is an explicit component declaration");
			assertTrue(loaded.isIndexedInScope(Scope.ARCHIVED), "ARCHIVED is inherited from the reflected reference");
			assertEquals(
				Set.of(), loaded.getIndexedComponents(Scope.ARCHIVED),
				"The stored uncovered ARCHIVED scope must load exactly as stored - never filled with a default"
			);
			assertEquals(
				Set.of(),
				loaded.withReferencedSchemaAfterSchemaChange(originalReference).getIndexedComponents(Scope.ARCHIVED),
				"A schema change re-binding a reference already indexed in ARCHIVED must not fill the stored empty " +
					"scope"
			);

			// validation needs no more of the catalog than the reference the reflected one points at
			final EntitySchema ownerSchema = EntitySchema._internalBuild(Entities.PRODUCT);
			final EntitySchemaContract reflectedSchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(reflectedSchema.getReference(STORED_SHAPE_REFLECTED_NAME))
				.thenReturn(Optional.of(originalReference));
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getName()).thenReturn(TestConstants.TEST_CATALOG);
			Mockito.when(catalogSchema.getEntitySchema(Entities.CATEGORY))
				.thenReturn(Optional.of(reflectedSchema));
			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> loaded.validate(catalogSchema, ownerSchema),
				"The loaded schema must still be refused by the schema rule"
			);
			for (String expected : List.of("`" + STORED_SHAPE_REFERENCE + "`", "ARCHIVED", "REFERENCED_ENTITY")) {
				assertTrue(
					refusal.getMessage().contains(expected),
					"The refusal must name `" + expected + "`, was: " + refusal.getMessage()
				);
			}
		}

		/**
		 * An indexing mutation that changes another scope of a plain reference completes every indexed scope it leaves
		 * without components with the default - except one the reference is already stored with and no component. That
		 * scope never indexed anything, and filling it as a side effect would make it look healthy over indexes that
		 * were never built. Asking for the component in that scope explicitly is still honoured - it is the repair.
		 */
		@Test
		@DisplayName("should not fill a stored empty scope of a plain reference when a mutation changes another scope")
		void shouldNotFillAStoredEmptyScopeOfAPlainReferenceWhenAMutationChangesAnotherScope() {
			final ReferenceSchema stored = buildStoredReferenceSchema(
				STORED_SHAPE_REFERENCE, Entities.BRAND,
				Map.of(
					Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
					Scope.ARCHIVED, Collections.emptySet()
				),
				Scope.LIVE, Scope.ARCHIVED
			);
			final ScopedReferenceIndexType[] bothScopes = {
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING),
				new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
			};
			final EntitySchemaContract ownerSchema = Mockito.mock(EntitySchemaContract.class);

			final ReferenceSchemaContract liveComponentsChanged = new SetReferenceSchemaIndexedMutation(
				STORED_SHAPE_REFERENCE, bothScopes,
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				}
			).mutate(ownerSchema, stored);
			assertEquals(
				Set.of(), liveComponentsChanged.getIndexedComponents(Scope.ARCHIVED),
				"Changing the LIVE components must leave the stored empty ARCHIVED scope empty"
			);

			final ReferenceSchemaContract componentsUnspecified =
				new SetReferenceSchemaIndexedMutation(STORED_SHAPE_REFERENCE, bothScopes).mutate(ownerSchema, stored);
			assertEquals(
				Set.of(), componentsUnspecified.getIndexedComponents(Scope.ARCHIVED),
				"A mutation that names no components must leave the stored empty ARCHIVED scope empty too"
			);

			final ReferenceSchemaContract repaired = new SetReferenceSchemaIndexedMutation(
				STORED_SHAPE_REFERENCE, bothScopes,
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.ARCHIVED, new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
					)
				}
			).mutate(ownerSchema, stored);
			assertEquals(
				Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY), repaired.getIndexedComponents(Scope.ARCHIVED),
				"Asking for the component in the stored empty scope explicitly must repair it"
			);
			assertEquals(
				Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY), repaired.getIndexedComponents(Scope.LIVE),
				"The LIVE scope the repair leaves uncovered was indexed with components, so it gets the default"
			);
		}

		/**
		 * The reflected counterpart of the test above: the indexing mutation of a reflected reference completes the
		 * scopes it leaves without components, and must skip one the reference is already stored with and no
		 * component - the shape the reflected-reference builder used to leave behind in every scope its explicit
		 * components did not name.
		 */
		@Test
		@DisplayName("should not fill a stored empty scope of a reflected reference when a mutation changes another scope")
		void shouldNotFillAStoredEmptyScopeOfAReflectedReferenceWhenAMutationChangesAnotherScope() {
			final ReferenceSchema originalReference = buildStoredReferenceSchema(
				STORED_SHAPE_REFLECTED_NAME, Entities.PRODUCT,
				Map.of(
					Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
					Scope.ARCHIVED, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)
				),
				Scope.LIVE, Scope.ARCHIVED
			);
			final Map<Scope, ReferenceIndexType> indexedInScopes = new EnumMap<>(Scope.class);
			indexedInScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			indexedInScopes.put(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING);
			final ReflectedReferenceSchema stored = buildStoredReflectedReferenceSchema(
				indexedInScopes,
				Map.of(
					Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
					Scope.ARCHIVED, Collections.emptySet()
				)
			).withReferencedSchema(originalReference);
			assertEquals(Set.of(), stored.getIndexedComponents(Scope.ARCHIVED), "The premise is a stored empty scope");

			// explicit components naming LIVE alone, as the builder sends them - a mutation with no components at all
			// would switch the reference to inherited components instead, which never reaches the default fill
			final ReferenceSchemaContract mutated = new SetReferenceSchemaIndexedMutation(
				STORED_SHAPE_REFERENCE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING),
					new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				}
			).mutate(Mockito.mock(EntitySchemaContract.class), stored);

			assertFalse(
				((ReflectedReferenceSchemaContract) mutated).isIndexedComponentsInherited(),
				"The premise is a reflected reference keeping its explicit components"
			);
			assertEquals(
				Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
				mutated.getIndexedComponents(Scope.LIVE),
				"The mutation must have been applied at all, or the assertion below proves nothing"
			);
			assertEquals(
				Set.of(), mutated.getIndexedComponents(Scope.ARCHIVED),
				"Changing the LIVE components must leave the stored empty ARCHIVED scope empty"
			);
		}

		/**
		 * A reflected reference inheriting its components has none of its own to add the missing one to, so the
		 * refusal must point at the inheritance - the reference it reflects - rather than tell the user to add a
		 * component to a reference that declares none.
		 */
		@Test
		@DisplayName("should point a reflected reference inheriting its components at the reference it reflects")
		void shouldPointAReflectedReferenceInheritingItsComponentsAtTheReferenceItReflects() {
			final ReferenceSchema originalReference = buildStoredReferenceSchema(
				STORED_SHAPE_REFLECTED_NAME, Entities.PRODUCT,
				Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY))
			);
			final ReflectedReferenceSchema loaded = buildStoredReflectedReferenceSchema(null)
				.withReferencedSchema(originalReference);
			assertTrue(loaded.isIndexedComponentsInherited(), "The premise is inherited components");

			final EntitySchemaContract reflectedSchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(reflectedSchema.getReference(STORED_SHAPE_REFLECTED_NAME))
				.thenReturn(Optional.of(originalReference));
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getName()).thenReturn(TestConstants.TEST_CATALOG);
			Mockito.when(catalogSchema.getEntitySchema(Entities.CATEGORY))
				.thenReturn(Optional.of(reflectedSchema));
			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> loaded.validate(catalogSchema, EntitySchema._internalBuild(Entities.PRODUCT)),
				"The inherited group-only components must be refused"
			);
			assertTrue(
				refusal.getMessage().contains("inherited from reference `" + STORED_SHAPE_REFLECTED_NAME + "`"),
				"The refusal must point at the reference the components are inherited from, was: " +
					refusal.getMessage()
			);
		}

	}

	/**
	 * Builds a {@link ReferenceSchema} indexed in {@link Scope#LIVE} with exactly the given components, through the
	 * map overload of `_internalBuild` - the one the Kryo reader uses, which neither validates nor fills defaults, so
	 * shapes the builder API can no longer produce can still be constructed.
	 *
	 * @param name       name of the reference
	 * @param entityType referenced entity type
	 * @param components indexed components per scope, taken verbatim
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchema buildStoredReferenceSchema(
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull Map<Scope, Set<ReferenceIndexedComponents>> components
	) {
		return buildStoredReferenceSchema(name, entityType, components, Scope.LIVE);
	}

	/**
	 * Builds a {@link ReferenceSchema} indexed for filtering in the given scopes with exactly the given components,
	 * through the map overload of `_internalBuild` - see {@link #buildStoredReferenceSchema(String, String, Map)}.
	 *
	 * @param name          name of the reference
	 * @param entityType    referenced entity type
	 * @param components    indexed components per scope, taken verbatim
	 * @param indexedScopes scopes the reference is indexed in
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchema buildStoredReferenceSchema(
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull Map<Scope, Set<ReferenceIndexedComponents>> components,
		@Nonnull Scope... indexedScopes
	) {
		final Map<Scope, ReferenceIndexType> indexedInScopes = new EnumMap<>(Scope.class);
		for (Scope scope : indexedScopes) {
			indexedInScopes.put(scope, ReferenceIndexType.FOR_FILTERING);
		}
		return ReferenceSchema._internalBuild(
			name, NamingConvention.generate(name),
			null, null,
			Cardinality.ZERO_OR_MORE,
			entityType, Collections.emptyMap(), true,
			STORED_SHAPE_GROUP_TYPE, Collections.emptyMap(), true,
			indexedInScopes,
			components,
			Collections.emptySet(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds a {@link ReflectedReferenceSchema} on the product side, indexed in {@link Scope#LIVE}, reflecting the
	 * reference {@link #STORED_SHAPE_REFLECTED_NAME} of the category collection.
	 *
	 * @param components explicit indexed components per scope, or `null` to inherit them from the reflected reference
	 * @return the reflected reference schema, not yet bound to the reference it reflects
	 */
	@Nonnull
	private static ReflectedReferenceSchema buildStoredReflectedReferenceSchema(
		@Nullable Map<Scope, Set<ReferenceIndexedComponents>> components
	) {
		final Map<Scope, ReferenceIndexType> indexedInScopes = new EnumMap<>(Scope.class);
		indexedInScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
		return buildStoredReflectedReferenceSchema(indexedInScopes, components);
	}

	/**
	 * Builds the same reflected reference as {@link #buildStoredReflectedReferenceSchema(Map)}, but inheriting its
	 * indexed scopes from the reference it reflects.
	 *
	 * @param components explicit indexed components per scope
	 * @return the reflected reference schema, not yet bound to the reference it reflects
	 */
	@Nonnull
	private static ReflectedReferenceSchema buildStoredReflectedReferenceSchemaInheritingScopes(
		@Nonnull Map<Scope, Set<ReferenceIndexedComponents>> components
	) {
		return buildStoredReflectedReferenceSchema(null, components);
	}

	/**
	 * Builds a {@link ReflectedReferenceSchema} on the product side through the map overload of `_internalBuild`.
	 *
	 * @param indexedInScopes explicit indexed scopes, or `null` to inherit them from the reflected reference
	 * @param components      explicit indexed components per scope, or `null` to inherit them
	 * @return the reflected reference schema, not yet bound to the reference it reflects
	 */
	@Nonnull
	private static ReflectedReferenceSchema buildStoredReflectedReferenceSchema(
		@Nullable Map<Scope, ReferenceIndexType> indexedInScopes,
		@Nullable Map<Scope, Set<ReferenceIndexedComponents>> components
	) {
		return ReflectedReferenceSchema._internalBuild(
			STORED_SHAPE_REFERENCE,
			NamingConvention.generate(STORED_SHAPE_REFERENCE),
			null, null,
			Entities.CATEGORY,
			STORED_SHAPE_REFLECTED_NAME,
			null,
			indexedInScopes, components, null, null, null, null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			null
		);
	}

	/**
	 * Serializes and deserializes a {@link ReferenceSchema} via Kryo, returning the deserialized result.
	 *
	 * @param kryo   the Kryo instance to use
	 * @param schema the reference schema to round-trip
	 * @return the deserialized reference schema
	 */
	@Nonnull
	private static ReferenceSchema roundTripReferenceSchema(@Nonnull Kryo kryo, @Nonnull ReferenceSchema schema) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, schema);
		}
		final byte[] bytes = baos.toByteArray();
		assertTrue(bytes.length > 0);
		try (final Input input = new Input(new ByteArrayInputStream(bytes))) {
			return kryo.readObject(input, ReferenceSchema.class);
		}
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
