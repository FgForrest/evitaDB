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
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InitialAssociatedDataBuilder} verifying
 * setting, removing, querying, and schema validation of
 * associated data on a freshly created entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("InitialAssociatedDataBuilder")
class InitialAssociatedDataBuilderTest extends AbstractBuilderTest {
	private final InitialAssociatedDataBuilder associatedData =
		new InitialAssociatedDataBuilder(
			EntitySchema._internalBuild("whatever")
		);

	@Nested
	@DisplayName("Setting and getting")
	class SettingAndGettingTest {

		@Test
		@DisplayName("should store new value and retrieve it")
		void shouldStoreNewValueAndRetrieveIt() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", "DEF")
					.build();

			assertEquals("DEF", associatedData.getAssociatedData("abc"));
		}

		@Test
		@DisplayName("should override one operation with another")
		void shouldOverrideOneOperationWithAnother() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", "DEF")
					.setAssociatedData("abc", "RTE")
					.build();

			assertEquals(
				"RTE",
				associatedData.getAssociatedData("abc")
			);
		}

		@Test
		@DisplayName(
			"should fail with ClassCast if mapping to different type"
		)
		void shouldFailWithClassCastIfMappingToDifferentType() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", "DEF")
					.build();

			assertThrows(ClassCastException.class, () -> {
				final Integer someInt =
					associatedData.getAssociatedData("abc");
				fail("Should not be executed at all!");
			});
		}

		@Test
		@DisplayName(
			"should store new value array and retrieve it"
		)
		void shouldStoreNewValueArrayAndRetrieveIt() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData(
						"abc",
						new String[]{"DEF", "XYZ"}
					)
					.build();

			assertArrayEquals(
				new String[]{"DEF", "XYZ"},
				associatedData.getAssociatedData("abc")
			);
		}
	}

	@Nested
	@DisplayName("Removing")
	class RemovingTest {

		@Test
		@DisplayName("should remove value")
		void shouldRemoveValue() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", "DEF")
					.removeAssociatedData("abc")
					.build();

			assertThrows(
				AssociatedDataNotFoundException.class,
				() -> associatedData.getAssociatedData("abc")
			);
		}

		@Test
		@DisplayName("should remove previously set value")
		void shouldRemovePreviouslySetValue() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", "DEF")
					.setAssociatedData("abc", "DEF")
					.removeAssociatedData("abc")
					.build();

			assertThrows(
				AssociatedDataNotFoundException.class,
				() -> associatedData.getAssociatedData("abc")
			);
		}
	}

	@Nested
	@DisplayName("Names and locales")
	class NamesAndLocalesTest {

		@Test
		@DisplayName("should return associated data names")
		void shouldReturnAssociatedDataNames() {
			final AssociatedData associatedData =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", 1)
					.setAssociatedData(
						"def",
						IntegerNumberRange.between(4, 8)
					)
					.build();

			final Set<String> names =
				associatedData.getAssociatedDataNames();

			assertEquals(2, names.size());
			assertTrue(names.contains("abc"));
			assertTrue(names.contains("def"));
		}

		@Test
		@DisplayName("should support localized associated data")
		void shouldSupportLocalizedAssociatedData() {
			final AssociatedDataBuilder builder =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData("abc", 1)
					.setAssociatedData(
						"def",
						IntegerNumberRange.between(4, 8)
					)
					.setAssociatedData(
						"dd", new BigDecimal("1.123")
					)
					.setAssociatedData(
						"greetings", Locale.ENGLISH, "Hello"
					)
					.setAssociatedData(
						"greetings", Locale.GERMAN, "Tschüss"
					);

			assertEquals(
				Integer.valueOf(1),
				builder.getAssociatedData("abc")
			);
			assertEquals(
				IntegerNumberRange.between(4, 8),
				builder.getAssociatedData("def")
			);
			assertEquals(
				new BigDecimal("1.123"),
				builder.getAssociatedData("dd")
			);
			assertEquals(
				"Hello",
				builder.getAssociatedData(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				"Tschüss",
				builder.getAssociatedData(
					"greetings", Locale.GERMAN
				)
			);
			assertNull(
				builder.getAssociatedData(
					"greetings", Locale.FRENCH
				)
			);

			final AssociatedData associatedData = builder.build();
			final Set<String> names =
				associatedData.getAssociatedDataNames();

			assertEquals(4, names.size());
			assertTrue(names.contains("abc"));
			assertTrue(names.contains("def"));
			assertTrue(names.contains("dd"));
			assertTrue(names.contains("greetings"));

			assertEquals(
				Integer.valueOf(1),
				associatedData.getAssociatedData("abc")
			);
			assertEquals(
				IntegerNumberRange.between(4, 8),
				associatedData.getAssociatedData("def")
			);
			assertEquals(
				new BigDecimal("1.123"),
				associatedData.getAssociatedData("dd")
			);
			assertEquals(
				"Hello",
				associatedData.getAssociatedData(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				"Tschüss",
				associatedData.getAssociatedData(
					"greetings", Locale.GERMAN
				)
			);
			assertNull(
				associatedData.getAssociatedData(
					"greetings", Locale.FRENCH
				)
			);
		}

		@Test
		@DisplayName(
			"should report error on ambiguous definition"
		)
		void shouldReportErrorOnAmbiguousAssociatedDataDefinition() {
			assertThrows(
				IllegalArgumentException.class,
				() -> InitialAssociatedDataBuilderTest.this
					.associatedData
					.setAssociatedData(
						"greetings", Locale.ENGLISH, "Hello"
					)
					.setAssociatedData(
						"greetings", Locale.GERMAN, 1
					)
					.build()
			);
		}
	}

	@Nested
	@DisplayName("Schema validation")
	class SchemaValidationTest {

		@Test
		@DisplayName(
			"should respect existing schema early on"
		)
		void shouldRespectExistingSchemaEarlyOnWhenSettingAssociatedData() {
			final EntitySchemaContract updatedSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA,
					PRODUCT_SCHEMA
				)
					.withAssociatedData("string", String.class)
					.withAssociatedData(
						"int",
						Integer.class,
						AssociatedDataSchemaEditor::localized
					)
					.withLocale(Locale.ENGLISH)
					.verifySchemaStrictly()
					.toInstance();

			final InitialAssociatedDataBuilder attrBuilder =
				new InitialAssociatedDataBuilder(updatedSchema);

			// try to add unknown associatedData
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData("new", "A")
			);
			// try to add associatedData with bad type
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData("string", 1)
			);
			// try to add associatedData as localized, when it is not
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.ENGLISH, "string"
				)
			);
			// try to add associatedData as localized with non
			// supported locale
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.GERMAN, "string"
				)
			);
			// try to add associatedData as non-localized, when it is
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData("int", 1)
			);
			// try to set associatedData correctly
			final AssociatedData attrs = attrBuilder
				.setAssociatedData("string", "Hi")
				.setAssociatedData("int", Locale.ENGLISH, 5)
				.build();

			assertEquals(2, attrs.getAssociatedDataKeys().size());
			assertEquals("Hi", attrs.getAssociatedData("string"));
			assertEquals(
				Integer.valueOf(5),
				attrs.getAssociatedData("int", Locale.ENGLISH)
			);
			assertNull(
				attrs.getAssociatedData("int", Locale.GERMAN)
			);
		}

		@Test
		@DisplayName(
			"should allow adding new data when not strict"
		)
		void shouldAllowToAddNewAssociatedDataIfVerificationIsNotStrict() {
			final EntitySchemaContract updatedSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA,
					PRODUCT_SCHEMA
				)
					.withAssociatedData("string", String.class)
					.withAssociatedData(
						"int",
						Integer.class,
						AssociatedDataSchemaEditor::localized
					)
					.withLocale(Locale.ENGLISH)
					.verifySchemaButAllow(
						EvolutionMode.ADDING_ASSOCIATED_DATA
					)
					.toInstance();

			final InitialAssociatedDataBuilder attrBuilder =
				new InitialAssociatedDataBuilder(updatedSchema);

			// try to add unknown associatedData
			attrBuilder.setAssociatedData("new", "A");
			// try to add unknown localized associatedData
			attrBuilder.setAssociatedData(
				"newLocalized", Locale.ENGLISH, "B"
			);
			// try to add new unknown localized associatedData
			// with unsupported locale
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"newLocalized", Locale.GERMAN, "string"
				)
			);
			// try to add associatedData with bad type
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData("string", 1)
			);
			// try to add associatedData as localized, when it is not
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.ENGLISH, "string"
				)
			);
			// try to add associatedData as localized with non
			// supported locale
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.GERMAN, "string"
				)
			);
			// try to add associatedData as non-localized, when it is
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData("int", 1)
			);
			// try to set associatedData correctly
			final AssociatedData attrs = attrBuilder
				.setAssociatedData("string", "Hi")
				.setAssociatedData("int", Locale.ENGLISH, 5)
				.build();

			assertEquals(
				4, attrs.getAssociatedDataKeys().size()
			);
			assertEquals("A", attrs.getAssociatedData("new"));
			assertEquals(
				"B",
				attrs.getAssociatedData(
					"newLocalized", Locale.ENGLISH
				)
			);
			assertEquals(
				"Hi", attrs.getAssociatedData("string")
			);
			assertEquals(
				Integer.valueOf(5),
				attrs.getAssociatedData("int", Locale.ENGLISH)
			);
			assertNull(
				attrs.getAssociatedData("int", Locale.GERMAN)
			);
		}

		@Test
		@DisplayName(
			"should respect schema but allow adding locales"
		)
		void shouldRespectExistingSchemaEarlyOnWhenSettingAssociatedDataButAllowAddingLocales() {
			final EntitySchemaContract updatedSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA,
					PRODUCT_SCHEMA
				)
					.withAssociatedData("string", String.class)
					.withAssociatedData(
						"int",
						Integer.class,
						AssociatedDataSchemaEditor::localized
					)
					.withLocale(Locale.ENGLISH)
					.verifySchemaButAllow(
						EvolutionMode.ADDING_LOCALES
					)
					.toInstance();

			final InitialAssociatedDataBuilder attrBuilder =
				new InitialAssociatedDataBuilder(updatedSchema);
			final AssociatedData attrs = attrBuilder
				.setAssociatedData("string", "Hi")
				.setAssociatedData("int", Locale.ENGLISH, 5)
				.setAssociatedData("int", Locale.GERMAN, 7)
				.build();

			assertEquals(
				3, attrs.getAssociatedDataKeys().size()
			);
			assertEquals(
				"Hi", attrs.getAssociatedData("string")
			);
			assertEquals(
				Integer.valueOf(5),
				attrs.getAssociatedData("int", Locale.ENGLISH)
			);
			assertEquals(
				Integer.valueOf(7),
				attrs.getAssociatedData("int", Locale.GERMAN)
			);
		}
	}

	@Nested
	@DisplayName("Accessor methods and change set")
	class AccessorMethodsAndChangeSetTest {

		@Test
		@DisplayName("should return associated data locales")
		void shouldReturnAssociatedDataLocales() {
			InitialAssociatedDataBuilderTest.this
				.associatedData
				.setAssociatedData(
					"greetings", Locale.ENGLISH, "Hello"
				)
				.setAssociatedData(
					"greetings", Locale.GERMAN, "Hallo"
				);

			final Set<Locale> locales =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.getAssociatedDataLocales();

			assertEquals(2, locales.size());
			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.GERMAN));
		}

		@Test
		@DisplayName(
			"should return associated data value by key"
		)
		void shouldReturnAssociatedDataValueByKey() {
			InitialAssociatedDataBuilderTest.this
				.associatedData
				.setAssociatedData("abc", "DEF");

			final Optional<AssociatedDataValue> value =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.getAssociatedDataValue(
						new AssociatedDataKey("abc")
					);

			assertTrue(value.isPresent());
			assertEquals("DEF", value.get().value());
		}

		@Test
		@DisplayName(
			"should return associated data value as Optional"
		)
		void shouldReturnAssociatedDataValueOptional() {
			InitialAssociatedDataBuilderTest.this
				.associatedData
				.setAssociatedData("abc", "DEF");

			final Optional<AssociatedDataValue> present =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.getAssociatedDataValue("abc");
			final Optional<AssociatedDataValue> absent =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.getAssociatedDataValue("nonexistent");

			assertTrue(present.isPresent());
			assertEquals("DEF", present.get().value());
			assertTrue(absent.isEmpty());
		}

		@Test
		@DisplayName(
			"should report associated data available"
		)
		void shouldReportAssociatedDataAvailable() {
			final boolean available =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.associatedDataAvailable();

			assertTrue(available);
		}

		@Test
		@DisplayName("should build change set with mutations")
		void shouldBuildChangeSet() {
			InitialAssociatedDataBuilderTest.this
				.associatedData
				.setAssociatedData("abc", "DEF")
				.setAssociatedData("xyz", 42);

			final List<UpsertAssociatedDataMutation> mutations =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.buildChangeSet()
					.map(UpsertAssociatedDataMutation.class::cast)
					.toList();

			assertEquals(2, mutations.size());
		}

		@Test
		@DisplayName("should build empty change set")
		void shouldBuildEmptyChangeSet() {
			final long count =
				InitialAssociatedDataBuilderTest.this
					.associatedData
					.buildChangeSet()
					.count();

			assertEquals(0, count);
		}
	}
}
