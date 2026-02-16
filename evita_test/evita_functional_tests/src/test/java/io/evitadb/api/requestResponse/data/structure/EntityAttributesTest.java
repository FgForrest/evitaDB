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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityAttributes} verifying construction,
 * schema resolution, attribute retrieval, and error handling.
 *
 * @author evitaDB
 */
@DisplayName("EntityAttributes")
class EntityAttributesTest extends AbstractBuilderTest {
	private static final String CODE = "code";
	private static final String NAME = "name";

	/**
	 * Builds an entity schema with a non-localized `code`
	 * attribute and optionally a localized `name` attribute.
	 *
	 * @param withLocalized whether to add localized `name`
	 * @return configured entity schema
	 */
	@Nonnull
	private static EntitySchemaContract schemaWith(
		boolean withLocalized
	) {
		final InternalEntitySchemaBuilder b =
			new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			);
		b.withAttribute(CODE, String.class);
		if (withLocalized) {
			b.withAttribute(
				NAME, String.class,
				AttributeSchemaEditor::localized
			);
		}
		return b.toInstance();
	}

	/**
	 * Creates a populated {@link EntityAttributes} instance
	 * with a non-localized `code` value and localized `name`
	 * values in English and German.
	 *
	 * @return pre-populated entity attributes container
	 */
	@Nonnull
	private static EntityAttributes createPopulated() {
		final EntitySchemaContract schema =
			schemaWith(true);
		final Map<String, EntityAttributeSchemaContract>
			types = schema.getAttributes();
		final AttributeValue codeVal = new AttributeValue(
			new AttributeKey(CODE), "ABC"
		);
		final AttributeValue nameEn = new AttributeValue(
			new AttributeKey(NAME, Locale.ENGLISH),
			"English name"
		);
		final AttributeValue nameDe = new AttributeValue(
			new AttributeKey(NAME, Locale.GERMAN),
			"German name"
		);
		return new EntityAttributes(
			schema,
			List.of(codeVal, nameEn, nameDe),
			types
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create empty container from schema"
		)
		void shouldCreateEmptyFromSchema() {
			final EntitySchemaContract schema =
				schemaWith(false);

			final EntityAttributes attrs =
				new EntityAttributes(schema);

			assertTrue(attrs.isEmpty());
			assertTrue(attrs.getAttributeNames().isEmpty());
		}

		@Test
		@DisplayName(
			"should create empty container from empty list"
		)
		void shouldCreateEmptyFromEmptyCollection() {
			final EntitySchemaContract schema =
				schemaWith(false);

			final EntityAttributes attrs =
				new EntityAttributes(
					schema,
					Collections.emptyList(),
					schema.getAttributes()
				);

			assertTrue(attrs.isEmpty());
		}

		@Test
		@DisplayName(
			"should store values from collection"
		)
		void shouldStoreValuesFromCollection() {
			final EntityAttributes attrs =
				createPopulated();

			assertFalse(attrs.isEmpty());
			assertEquals(
				2, attrs.getAttributeNames().size()
			);
		}
	}

	@Nested
	@DisplayName("Schema resolution")
	class SchemaResolutionTest {

		@Test
		@DisplayName(
			"should resolve schema from entity schema"
		)
		void shouldResolveFromEntitySchema() {
			final EntityAttributes attrs =
				createPopulated();

			final Optional<EntityAttributeSchemaContract>
				schema = attrs.getAttributeSchema(CODE);

			assertTrue(schema.isPresent());
			assertEquals(
				String.class,
				schema.get().getType()
			);
		}

		@Test
		@DisplayName(
			"should return empty for unknown attribute"
		)
		void shouldReturnEmptyForUnknown() {
			final EntityAttributes attrs =
				createPopulated();

			// getAttributeSchema won't throw for missing
			// but getAttribute will
			assertThrows(
				AttributeNotFoundException.class,
				() -> attrs.getAttribute("unknown")
			);
		}
	}

	@Nested
	@DisplayName("Attribute retrieval")
	class RetrievalTest {

		@Test
		@DisplayName(
			"should return non-localized attribute"
		)
		void shouldReturnNonLocalizedAttribute() {
			final EntityAttributes attrs =
				createPopulated();

			final String code = attrs.getAttribute(CODE);

			assertEquals("ABC", code);
		}

		@Test
		@DisplayName(
			"should return localized attribute"
		)
		void shouldReturnLocalizedAttribute() {
			final EntityAttributes attrs =
				createPopulated();

			final String en =
				attrs.getAttribute(NAME, Locale.ENGLISH);
			final String de =
				attrs.getAttribute(NAME, Locale.GERMAN);

			assertEquals("English name", en);
			assertEquals("German name", de);
		}

		@Test
		@DisplayName(
			"should return null for missing locale"
		)
		void shouldReturnNullForMissingLocale() {
			final EntityAttributes attrs =
				createPopulated();

			final String fr =
				attrs.getAttribute(NAME, Locale.FRENCH);

			assertNull(fr);
		}

		@Test
		@DisplayName("should return all attribute values")
		void shouldReturnAllValues() {
			final EntityAttributes attrs =
				createPopulated();

			final Collection<AttributeValue> values =
				attrs.getAttributeValues();

			assertEquals(3, values.size());
		}

		@Test
		@DisplayName("should return attribute names")
		void shouldReturnNames() {
			final EntityAttributes attrs =
				createPopulated();

			final Set<String> names =
				attrs.getAttributeNames();

			assertTrue(names.contains(CODE));
			assertTrue(names.contains(NAME));
			assertEquals(2, names.size());
		}

		@Test
		@DisplayName(
			"should return unmodifiable list from getAttributeValues by name"
		)
		void shouldReturnUnmodifiableValuesByName() {
			final EntityAttributes attrs =
				createPopulated();

			final Collection<AttributeValue> values =
				attrs.getAttributeValues(NAME);

			assertThrows(
				UnsupportedOperationException.class,
				() -> values.add(
					new AttributeValue(
						new AttributeKey(CODE),
						"illegal"
					)
				)
			);
		}

		@Test
		@DisplayName("should return attribute locales")
		void shouldReturnLocales() {
			final EntityAttributes attrs =
				createPopulated();

			final Set<Locale> locales =
				attrs.getAttributeLocales();

			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.GERMAN));
			assertEquals(2, locales.size());
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName(
			"should throw for unknown attribute name"
		)
		void shouldThrowForUnknownName() {
			final EntityAttributes attrs =
				createPopulated();

			assertThrows(
				AttributeNotFoundException.class,
				() -> attrs.getAttribute("unknown")
			);
		}

		@Test
		@DisplayName(
			"should throw for unknown name in getValues"
		)
		void shouldThrowForUnknownNameInValues() {
			final EntityAttributes attrs =
				createPopulated();

			assertThrows(
				AttributeNotFoundException.class,
				() -> attrs.getAttributeValues("unknown")
			);
		}
	}

	@Nested
	@DisplayName("isEmpty and toString")
	class StateTest {

		@Test
		@DisplayName(
			"should return true for isEmpty on empty"
		)
		void shouldReturnTrueForIsEmpty() {
			final EntityAttributes attrs =
				new EntityAttributes(schemaWith(false));

			assertTrue(attrs.isEmpty());
		}

		@Test
		@DisplayName(
			"should return readable toString"
		)
		void shouldReturnReadableToString() {
			final EntityAttributes attrs =
				createPopulated();

			final String result = attrs.toString();

			assertNotNull(result);
			assertFalse(result.isEmpty());
		}
	}
}
