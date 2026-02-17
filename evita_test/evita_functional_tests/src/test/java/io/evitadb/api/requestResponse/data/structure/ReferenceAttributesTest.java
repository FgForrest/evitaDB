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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceAttributes} verifying construction,
 * schema resolution with reference-schema-first lookup,
 * and exception creation with reference context.
 *
 * @author evitaDB
 */
@DisplayName("ReferenceAttributes")
class ReferenceAttributesTest extends AbstractBuilderTest {
	private static final String BRAND_REF = "brand";
	private static final String BRAND_ENTITY = "brand";
	private static final String PRIORITY = "priority";
	private static final String COUNTRY = "country";

	/**
	 * Builds an entity schema containing a `brand` reference
	 * with `priority` (Long) and localized `country` (String)
	 * attributes.
	 *
	 * @return configured entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildSchema() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				BRAND_REF, BRAND_ENTITY,
				Cardinality.ZERO_OR_ONE,
				ref -> {
					ref.withAttribute(
						PRIORITY, Long.class
					);
					ref.withAttribute(
						COUNTRY, String.class,
						AttributeSchemaEditor::localized
					);
				}
			)
			.toInstance();
	}

	/**
	 * Extracts the `brand` {@link ReferenceSchemaContract}
	 * from the given entity schema.
	 *
	 * @param schema entity schema containing the brand ref
	 * @return reference schema for brand
	 */
	@Nonnull
	private static ReferenceSchemaContract brandRefSchema(
		@Nonnull EntitySchemaContract schema
	) {
		return schema.getReference(BRAND_REF).orElseThrow();
	}

	/**
	 * Creates a {@link ReferenceAttributes} instance using
	 * the collection constructor, populated with a `priority`
	 * value and localized `country` values.
	 *
	 * @return pre-populated reference attributes
	 */
	@Nonnull
	private static ReferenceAttributes createPopulated() {
		final EntitySchemaContract schema = buildSchema();
		final ReferenceSchemaContract refSchema =
			brandRefSchema(schema);
		final Map<String, AttributeSchemaContract> types =
			refSchema.getAttributes();

		final AttributeValue priorityVal =
			new AttributeValue(
				new AttributeKey(PRIORITY), 42L
			);
		final AttributeValue countryEn =
			new AttributeValue(
				new AttributeKey(COUNTRY, Locale.ENGLISH),
				"US"
			);
		final AttributeValue countryDe =
			new AttributeValue(
				new AttributeKey(COUNTRY, Locale.GERMAN),
				"DE"
			);

		return new ReferenceAttributes(
			schema, refSchema,
			List.of(priorityVal, countryEn, countryDe),
			types
		);
	}

	/**
	 * Creates a {@link ReferenceAttributes} instance using
	 * the map constructor.
	 *
	 * @return pre-populated reference attributes (map ctor)
	 */
	@Nonnull
	private static ReferenceAttributes createFromMap() {
		final EntitySchemaContract schema = buildSchema();
		final ReferenceSchemaContract refSchema =
			brandRefSchema(schema);
		final Map<String, AttributeSchemaContract> types =
			refSchema.getAttributes();

		final AttributeKey priorityKey =
			new AttributeKey(PRIORITY);
		final AttributeValue priorityVal =
			new AttributeValue(priorityKey, 99L);

		final Map<AttributeKey, AttributeValue> map =
			new LinkedHashMap<>(4);
		map.put(priorityKey, priorityVal);

		return new ReferenceAttributes(
			schema, refSchema, map, types
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		/**
		 * Verifies that the collection constructor stores
		 * all provided attribute values and exposes them.
		 */
		@Test
		@DisplayName(
			"should store values via collection constructor"
		)
		void shouldCreateFromCollectionConstructor() {
			final ReferenceAttributes attrs =
				createPopulated();

			assertFalse(attrs.isEmpty());
			assertEquals(
				2, attrs.getAttributeNames().size()
			);
			assertEquals(
				3, attrs.getAttributeValues().size()
			);
		}

		/**
		 * Verifies that the map constructor stores
		 * all provided attribute values and exposes them.
		 */
		@Test
		@DisplayName(
			"should store values via map constructor"
		)
		void shouldCreateFromMapConstructor() {
			final ReferenceAttributes attrs =
				createFromMap();

			assertFalse(attrs.isEmpty());
			assertEquals(
				1, attrs.getAttributeNames().size()
			);
			final Long priority =
				attrs.getAttribute(PRIORITY);
			assertEquals(99L, priority);
		}
	}

	@Nested
	@DisplayName("Schema resolution")
	class SchemaResolutionTest {

		/**
		 * Verifies that `getAttributeSchema` resolves
		 * a known attribute from the reference schema first.
		 */
		@Test
		@DisplayName(
			"should resolve schema from reference schema"
		)
		void shouldResolveSchemaFromReferenceSchemaFirst() {
			final ReferenceAttributes attrs =
				createPopulated();

			final Optional<AttributeSchemaContract> schema =
				attrs.getAttributeSchema(PRIORITY);

			assertTrue(schema.isPresent());
			assertEquals(
				Long.class, schema.get().getType()
			);
		}

		/**
		 * Verifies fallback to `attributeTypes` map when
		 * the attribute is not found in reference schema.
		 */
		@Test
		@DisplayName(
			"should fallback to attribute types"
		)
		void shouldFallbackToAttributeTypesWhenNotInReferenceSchema() {
			final EntitySchemaContract schema =
				buildSchema();
			final ReferenceSchemaContract refSchema =
				brandRefSchema(schema);

			// build types map with an extra attribute
			// not present in refSchema
			final Map<String, AttributeSchemaContract>
				types = new LinkedHashMap<>(
				refSchema.getAttributes()
			);
			// grab one schema to re-register under alias
			final AttributeSchemaContract prioSchema =
				types.get(PRIORITY);
			types.put("fallbackAttr", prioSchema);

			final ReferenceAttributes attrs =
				new ReferenceAttributes(
					schema, refSchema,
					List.of(), types
				);

			final Optional<AttributeSchemaContract> result =
				attrs.getAttributeSchema("fallbackAttr");

			assertTrue(result.isPresent());
		}

		/**
		 * Verifies that an unknown attribute name
		 * produces an empty Optional from schema lookup.
		 */
		@Test
		@DisplayName(
			"should return empty for unknown attribute"
		)
		void shouldReturnEmptyWhenAttributeNotInEitherSchema() {
			final ReferenceAttributes attrs =
				createPopulated();

			final Optional<AttributeSchemaContract> result =
				attrs.getAttributeSchema("nonexistent");

			assertTrue(result.isEmpty());
		}

		/**
		 * Verifies that when the same attribute name exists
		 * in both the reference schema and the attribute
		 * types map, the reference schema wins.
		 */
		@Test
		@DisplayName(
			"should prefer reference schema over types map"
		)
		void shouldPreferReferenceSchemaOverAttributeTypes() {
			final ReferenceAttributes attrs =
				createPopulated();

			// PRIORITY exists in ref schema as Long
			final Optional<AttributeSchemaContract>
				fromRef =
				attrs.getAttributeSchema(PRIORITY);

			assertTrue(fromRef.isPresent());
			assertEquals(
				Long.class, fromRef.get().getType()
			);
		}
	}

	@Nested
	@DisplayName("Exception creation")
	class ExceptionCreationTest {

		/**
		 * Verifies that an exception thrown for an unknown
		 * attribute contains both the reference and entity
		 * schema context in its message.
		 */
		@Test
		@DisplayName(
			"should create exception with reference context"
		)
		void shouldCreateExceptionWithReferenceContext() {
			final ReferenceAttributes attrs =
				createPopulated();

			final AttributeNotFoundException ex =
				assertThrows(
					AttributeNotFoundException.class,
					() -> attrs.getAttribute("unknown")
				);

			final String msg = ex.getMessage();
			assertTrue(
				msg.contains(BRAND_REF),
				"Message should contain reference name"
			);
			assertTrue(
				msg.contains("product"),
				"Message should contain entity name"
			);
		}

		/**
		 * Verifies that requesting an unknown attribute
		 * by name throws {@link AttributeNotFoundException}.
		 */
		@Test
		@DisplayName(
			"should throw for unknown attribute"
		)
		void shouldThrowAttributeNotFoundForUnknownAttribute() {
			final ReferenceAttributes attrs =
				createPopulated();

			assertThrows(
				AttributeNotFoundException.class,
				() -> attrs.getAttribute("nonexistent")
			);
		}
	}

	@Nested
	@DisplayName("Mutability of inherited collections")
	class MutabilityTest {

		/**
		 * Verifies that `getAttributeNames()` returns an
		 * unmodifiable set, enforcing the `@Immutable`
		 * contract.
		 */
		@Test
		@DisplayName(
			"should return unmodifiable attribute names"
		)
		void shouldReturnUnmodifiableAttributeNames() {
			final ReferenceAttributes attrs =
				createPopulated();

			final Set<String> names =
				attrs.getAttributeNames();

			assertThrows(
				UnsupportedOperationException.class,
				() -> names.add("FAKE")
			);
		}

		/**
		 * Verifies that `getAttributeValues()` returns an
		 * unmodifiable collection, enforcing the
		 * `@Immutable` contract.
		 */
		@Test
		@DisplayName(
			"should return unmodifiable attribute values"
		)
		void shouldReturnUnmodifiableAttributeValues() {
			final ReferenceAttributes attrs =
				createPopulated();

			final Collection<AttributeValue> values =
				attrs.getAttributeValues();

			assertThrows(
				UnsupportedOperationException.class,
				values::clear
			);
		}

		/**
		 * Verifies that `getAttributeLocales()` returns an
		 * unmodifiable set, enforcing the `@Immutable`
		 * contract.
		 */
		@Test
		@DisplayName(
			"should return unmodifiable attribute locales"
		)
		void shouldReturnUnmodifiableAttributeLocales() {
			final ReferenceAttributes attrs =
				createPopulated();

			final Set<Locale> locales =
				attrs.getAttributeLocales();

			assertThrows(
				UnsupportedOperationException.class,
				() -> locales.add(Locale.CHINESE)
			);
		}
	}
}
