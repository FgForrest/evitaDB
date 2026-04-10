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

import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
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
 * Tests for {@link AssociatedData} verifying construction,
 * retrieval of localized and non-localized values, key/name
 * resolution, and schema violation handling.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AssociatedData")
class AssociatedDataTest extends AbstractBuilderTest {
	private static final String LABELS = "labels";
	private static final String DESCRIPTION = "description";

	/**
	 * Builds an entity schema that declares given associated
	 * data names together with their localization settings.
	 *
	 * @param localized whether `DESCRIPTION` should be localized
	 * @return entity schema with associated data definitions
	 */
	@Nonnull
	private static EntitySchemaContract schemaWith(
		boolean localized
	) {
		final InternalEntitySchemaBuilder builder =
			new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			);
		builder.withAssociatedData(LABELS, String.class);
		if (localized) {
			builder.withAssociatedData(
				DESCRIPTION, String.class,
				AssociatedDataSchemaEditor::localized
			);
		} else {
			builder.withAssociatedData(
				DESCRIPTION, String.class
			);
		}
		return builder.toInstance();
	}

	/**
	 * Creates an {@link AssociatedData} instance populated with
	 * non-localized `labels` and localized `description` values.
	 *
	 * @return pre-populated associated data container
	 */
	@Nonnull
	private static AssociatedData createPopulated() {
		final EntitySchemaContract schema = schemaWith(true);
		final Map<String, AssociatedDataSchemaContract> types =
			schema.getAssociatedData();
		final AssociatedDataValue labelsVal =
			new AssociatedDataValue(
				new AssociatedDataKey(LABELS), "LBL"
			);
		final AssociatedDataValue descEn =
			new AssociatedDataValue(
				new AssociatedDataKey(
					DESCRIPTION, Locale.ENGLISH
				),
				"English desc"
			);
		final AssociatedDataValue descDe =
			new AssociatedDataValue(
				new AssociatedDataKey(
					DESCRIPTION, Locale.GERMAN
				),
				"German desc"
			);
		return new AssociatedData(
			schema,
			List.of(labelsVal, descEn, descDe),
			types
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create empty state from schema-only constructor"
		)
		void shouldCreateEmptyFromSchema() {
			final EntitySchemaContract schema =
				schemaWith(false);

			final AssociatedData data =
				new AssociatedData(schema);

			assertTrue(data.isEmpty());
			assertTrue(data.getAssociatedDataNames().isEmpty());
			assertTrue(data.getAssociatedDataKeys().isEmpty());
			assertTrue(
				data.getAssociatedDataValues().isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should create empty state from collection constructor"
		)
		void shouldCreateEmptyFromCollection() {
			final EntitySchemaContract schema =
				schemaWith(false);
			final Map<String, AssociatedDataSchemaContract>
				types = schema.getAssociatedData();

			final AssociatedData data = new AssociatedData(
				schema, Collections.emptyList(), types
			);

			assertTrue(data.isEmpty());
		}

		@Test
		@DisplayName(
			"should store values from collection constructor"
		)
		void shouldStoreValuesFromCollection() {
			final AssociatedData data = createPopulated();

			assertFalse(data.isEmpty());
			assertEquals(
				2, data.getAssociatedDataNames().size()
			);
		}
	}

	@Nested
	@DisplayName("Non-localized retrieval")
	class NonLocalizedRetrievalTest {

		@Test
		@DisplayName(
			"should return non-localized value by name"
		)
		void shouldReturnNonLocalizedValue() {
			final AssociatedData data = createPopulated();

			final String result =
				data.getAssociatedData(LABELS);

			assertEquals("LBL", result);
		}

		@Test
		@DisplayName(
			"should return value via AssociatedDataKey"
		)
		void shouldReturnValueByKey() {
			final AssociatedData data = createPopulated();

			final Optional<AssociatedDataValue> val =
				data.getAssociatedDataValue(
					new AssociatedDataKey(LABELS)
				);

			assertTrue(val.isPresent());
			assertEquals("LBL", val.get().value());
		}
	}

	@Nested
	@DisplayName("Localized retrieval")
	class LocalizedRetrievalTest {

		@Test
		@DisplayName(
			"should return localized value by name and locale"
		)
		void shouldReturnLocalizedValue() {
			final AssociatedData data = createPopulated();

			final String english =
				data.getAssociatedData(
					DESCRIPTION, Locale.ENGLISH
				);
			final String german =
				data.getAssociatedData(
					DESCRIPTION, Locale.GERMAN
				);

			assertEquals("English desc", english);
			assertEquals("German desc", german);
		}

		@Test
		@DisplayName(
			"should return null for missing locale"
		)
		void shouldReturnNullForMissingLocale() {
			final AssociatedData data = createPopulated();

			final String result =
				data.getAssociatedData(
					DESCRIPTION, Locale.FRENCH
				);

			assertNull(result);
		}

		@Test
		@DisplayName(
			"should return value via localized key"
		)
		void shouldReturnValueByLocalizedKey() {
			final AssociatedData data = createPopulated();

			final Optional<AssociatedDataValue> val =
				data.getAssociatedDataValue(
					new AssociatedDataKey(
						DESCRIPTION, Locale.ENGLISH
					)
				);

			assertTrue(val.isPresent());
			assertEquals("English desc", val.get().value());
		}
	}

	@Nested
	@DisplayName("Collection queries")
	class CollectionQueriesTest {

		@Test
		@DisplayName("should return all values")
		void shouldReturnAllValues() {
			final AssociatedData data = createPopulated();

			final Collection<AssociatedDataValue> values =
				data.getAssociatedDataValues();

			assertEquals(3, values.size());
		}

		@Test
		@DisplayName(
			"should filter values by name"
		)
		void shouldFilterValuesByName() {
			final AssociatedData data = createPopulated();

			final Collection<AssociatedDataValue> descs =
				data.getAssociatedDataValues(DESCRIPTION);

			assertEquals(2, descs.size());
		}

		@Test
		@DisplayName("should return all associated data names")
		void shouldReturnNames() {
			final AssociatedData data = createPopulated();

			final Set<String> names =
				data.getAssociatedDataNames();

			assertTrue(names.contains(LABELS));
			assertTrue(names.contains(DESCRIPTION));
			assertEquals(2, names.size());
		}

		@Test
		@DisplayName(
			"should return unmodifiable set from getAssociatedDataNames"
		)
		void shouldReturnUnmodifiableNames() {
			final AssociatedData data = createPopulated();

			final Set<String> names =
				data.getAssociatedDataNames();

			assertThrows(
				UnsupportedOperationException.class,
				() -> names.add("illegal")
			);
		}

		@Test
		@DisplayName(
			"should return unmodifiable list from getAssociatedDataValues by name"
		)
		void shouldReturnUnmodifiableValuesByName() {
			final AssociatedData data = createPopulated();

			final Collection<AssociatedDataValue> descs =
				data.getAssociatedDataValues(DESCRIPTION);

			assertThrows(
				UnsupportedOperationException.class,
				() -> descs.add(
					new AssociatedDataValue(
						new AssociatedDataKey(LABELS),
						"illegal"
					)
				)
			);
		}

		@Test
		@DisplayName("should return all associated data keys")
		void shouldReturnKeys() {
			final AssociatedData data = createPopulated();

			final Set<AssociatedDataKey> keys =
				data.getAssociatedDataKeys();

			assertEquals(3, keys.size());
		}

		@Test
		@DisplayName(
			"should return locales from localized data"
		)
		void shouldReturnLocales() {
			final AssociatedData data = createPopulated();

			final Set<Locale> locales =
				data.getAssociatedDataLocales();

			assertEquals(2, locales.size());
			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.GERMAN));
		}
	}

	@Nested
	@DisplayName("Availability and state")
	class AvailabilityTest {

		@Test
		@DisplayName("should report associated data available")
		void shouldReportAvailable() {
			final AssociatedData data = createPopulated();

			assertTrue(data.associatedDataAvailable());
			assertTrue(
				data.associatedDataAvailable(Locale.ENGLISH)
			);
			assertTrue(
				data.associatedDataAvailable(LABELS)
			);
			assertTrue(
				data.associatedDataAvailable(
					DESCRIPTION, Locale.ENGLISH
				)
			);
		}

		@Test
		@DisplayName(
			"should return true for isEmpty on empty container"
		)
		void shouldReturnTrueForIsEmpty() {
			final AssociatedData data =
				new AssociatedData(schemaWith(false));

			assertTrue(data.isEmpty());
		}

		@Test
		@DisplayName(
			"should return false for isEmpty on populated"
		)
		void shouldReturnFalseForIsEmpty() {
			final AssociatedData data = createPopulated();

			assertFalse(data.isEmpty());
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName(
			"should throw for non-existent associated data name"
		)
		void shouldThrowForUnknownName() {
			final AssociatedData data = createPopulated();

			assertThrows(
				AssociatedDataNotFoundException.class,
				() -> data.getAssociatedData("nonExistent")
			);
		}

		@Test
		@DisplayName(
			"should throw for unknown name in getValues(name)"
		)
		void shouldThrowForUnknownNameInValues() {
			final AssociatedData data = createPopulated();

			assertThrows(
				AssociatedDataNotFoundException.class,
				() -> data.getAssociatedDataValues(
					"nonExistent"
				)
			);
		}

		@Test
		@DisplayName(
			"should throw for unknown key in getDataValue"
		)
		void shouldThrowForUnknownKey() {
			final AssociatedData data = createPopulated();

			assertThrows(
				AssociatedDataNotFoundException.class,
				() -> data.getAssociatedDataValue(
					new AssociatedDataKey("nonExistent")
				)
			);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTest {

		@Test
		@DisplayName(
			"should return readable representation"
		)
		void shouldReturnReadableString() {
			final AssociatedData data = createPopulated();

			final String result = data.toString();

			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

		@Test
		@DisplayName(
			"should return empty string for empty container"
		)
		void shouldReturnEmptyStringForEmptyData() {
			final AssociatedData data =
				new AssociatedData(schemaWith(false));

			final String result = data.toString();

			assertEquals("", result);
		}
	}
}
