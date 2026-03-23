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
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import lombok.Data;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
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

import static io.evitadb.test.Assertions.assertExactlyEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link EntitySchema} serialization and deserialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class SchemaSerializationServiceTest {

	@Test
	void shouldSerializeAndDeserializeSchema() {
		final EntitySchema productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		final Kryo kryo = KryoFactory.createKryo(SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE));
		final EntitySchemaContract createdSchema = constructSomeSchema(
				new InternalEntitySchemaBuilder(
						CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
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
			partiallyExpression
		);

		final EntitySchema deserialized = roundTripEntitySchema(kryo, createdSchema);

		assertEquals(createdSchema, deserialized);
		assertExactlyEquals(createdSchema, deserialized);

		// verify bucketed fields on the brand reference (populated bucketed with expression)
		final ReferenceSchemaContract brandRef = deserialized.getReference(Entities.BRAND).orElseThrow();
		final HistogramIndexDefinition brandDef = brandRef.getHistogramIndexDefinitions().get(Scope.LIVE);
		assertNotNull(brandDef);
		assertEquals("priceHistogram", brandDef.nameOfTheIndex());
		assertEquals(valueExpression, brandDef.valueExpression());
		assertEquals(partiallyExpression, brandRef.getBucketedPartiallyInScopes().get(Scope.LIVE));

		// verify bucketed fields on the stock reference (bucketed with null valueExpression)
		final ReferenceSchemaContract stockRef = deserialized.getReference("stock").orElseThrow();
		final HistogramIndexDefinition stockDef = stockRef.getHistogramIndexDefinitions().get(Scope.LIVE);
		assertNotNull(stockDef);
		assertEquals("stockIdx", stockDef.nameOfTheIndex());
		assertNull(stockDef.valueExpression(), "valueExpression should be null");

		// verify bucketed fields on the category reference (empty bucketed)
		final ReferenceSchemaContract categoryRef = deserialized.getReference(Entities.CATEGORY).orElseThrow();
		assertTrue(categoryRef.getHistogramIndexDefinitions().isEmpty());
		assertTrue(categoryRef.getBucketedPartiallyInScopes().isEmpty());
	}

	/**
	 * Verifies round-trip serialization of a {@link ReflectedReferenceSchema} with explicit
	 * (non-inherited) bucketed state ({@code bucketedInherited = false}).
	 */
	@Test
	void shouldSerializeAndDeserializeReflectedReferenceSchemaWithExplicitBucketed() {
		final Kryo kryo = createKryo();
		final Expression valueExpression = ExpressionFactory.parse("$price * 1.21");
		final Expression partiallyExpression = ExpressionFactory.parse("1 > 0");

		final Map<Scope, HistogramIndexDefinition> bucketedInScopes = new EnumMap<>(Scope.class);
		bucketedInScopes.put(Scope.LIVE, new HistogramIndexDefinition("refIdx", valueExpression));

		final Map<Scope, Expression> bucketedPartiallyInScopes = new EnumMap<>(Scope.class);
		bucketedPartiallyInScopes.put(Scope.LIVE, partiallyExpression);

		final ReflectedReferenceSchema base = createBaseReflectedReferenceSchema();
		final ReflectedReferenceSchema withBucketed =
			(ReflectedReferenceSchema) base.withBucketed(bucketedInScopes);
		final ReflectedReferenceSchema withBoth = withBucketed.withBucketedPartially(bucketedPartiallyInScopes);

		final ReflectedReferenceSchema deserialized = roundTripReflectedReferenceSchema(kryo, withBoth);

		assertEquals(withBoth, deserialized);
		assertFalse(deserialized.isBucketedInherited());
		assertEquals(bucketedInScopes, deserialized.getHistogramIndexDefinitions());
		assertEquals(bucketedPartiallyInScopes, deserialized.getBucketedPartiallyInScopes());
	}

	/**
	 * Verifies round-trip serialization of a {@link ReflectedReferenceSchema} with inherited
	 * bucketed state ({@code bucketedInherited = true}). This exercises the branch where
	 * the serializer writes {@code false} (no bucketed data follows).
	 */
	@Test
	void shouldSerializeAndDeserializeReflectedReferenceSchemaWithInheritedBucketed() {
		final Kryo kryo = createKryo();
		final ReflectedReferenceSchema base = createBaseReflectedReferenceSchema();

		final ReflectedReferenceSchema deserialized = roundTripReflectedReferenceSchema(kryo, base);

		assertEquals(base, deserialized);
		assertTrue(deserialized.isBucketedInherited());
		assertTrue(deserialized.getHistogramIndexDefinitions().isEmpty());
		assertTrue(deserialized.getBucketedPartiallyInScopes().isEmpty());
	}

	/**
	 * Verifies round-trip serialization of a {@link ReflectedReferenceSchema} with non-inherited
	 * but empty bucketed maps. This is a boundary case where the serializer writes {@code true}
	 * (not inherited) followed by zero-count maps. After round-trip, the serializer reconstructs
	 * the schema via {@code _internalBuild} + {@code withBucketed}, preserving the non-inherited state.
	 */
	@Test
	void shouldSerializeAndDeserializeReflectedReferenceSchemaWithNonInheritedEmptyBucketed() {
		final Kryo kryo = createKryo();
		final ReflectedReferenceSchema base = createBaseReflectedReferenceSchema();
		final ReflectedReferenceSchema withEmptyBucketed =
			(ReflectedReferenceSchema) base.withBucketed(Collections.emptyMap());

		assertFalse(withEmptyBucketed.isBucketedInherited(), "original bucketedInherited");

		final ReflectedReferenceSchema deserialized = roundTripReflectedReferenceSchema(kryo, withEmptyBucketed);

		// the bucketed maps should be empty after round-trip
		assertTrue(deserialized.getHistogramIndexDefinitions().isEmpty());
		assertTrue(deserialized.getBucketedPartiallyInScopes().isEmpty());
		// verify the non-inherited flag survives the round-trip
		assertFalse(deserialized.isBucketedInherited(), "deserialized bucketedInherited");
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
	 * The returned schema always has {@code bucketedInherited = true} (default). Callers can
	 * subsequently call {@link ReflectedReferenceSchema#withBucketed(Map)} to flip it to non-inherited.
	 *
	 * @return a new reflected reference schema with inherited bucketed state
	 */
	@Nonnull
	private static ReflectedReferenceSchema createBaseReflectedReferenceSchema() {
		final Map<Scope, ReferenceIndexType> indexedInScopes = new EnumMap<>(Scope.class);
		indexedInScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
		// the _internalBuild overload with only indexed/faceted parameters always sets bucketedInherited=true
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
	 * - brand reference: bucketed with a value expression and a partially expression
	 * - stock reference: bucketed with null value expression (no expression branch)
	 * - category reference: no bucketed configuration (empty bucketed maps)
	 *
	 * @param schemaBuilder       the entity schema builder to use
	 * @param valueExpression     the expression for the bucketed histogram value
	 * @param partiallyExpression the expression for bucketed partially filtering
	 * @return the built entity schema
	 */
	@Nonnull
	@SuppressWarnings("Convert2MethodRef")
	private static EntitySchemaContract constructSchemaWithBucketedReferences(
		@Nonnull InternalEntitySchemaBuilder schemaBuilder,
		@Nonnull Expression valueExpression,
		@Nonnull Expression partiallyExpression
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
					.bucketed("priceHistogram", valueExpression)
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
