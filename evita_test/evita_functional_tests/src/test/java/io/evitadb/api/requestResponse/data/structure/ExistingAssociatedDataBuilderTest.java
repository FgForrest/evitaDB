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

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingAssociatedDataBuilder}
 * class. Ie. tries modification on already existing
 * {@link AssociatedData} container.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ExistingAssociatedDataBuilder")
class ExistingAssociatedDataBuilderTest extends AbstractBuilderTest {
	private AssociatedData initialAssociatedData;

	@BeforeEach
	void setUp() {
		this.initialAssociatedData = new InitialAssociatedDataBuilder(
			EntitySchema._internalBuild("whatever")
		)
			.setAssociatedData("int", 1)
			.setAssociatedData(
				"range", IntegerNumberRange.between(4, 8)
			)
			.setAssociatedData(
				"bigDecimal", new BigDecimal("1.123")
			)
			.setAssociatedData(
				"greetings", Locale.ENGLISH, "Hello"
			)
			.setAssociatedData(
				"greetings", Locale.GERMAN, "Tschüss"
			)
			.build();
	}

	@Nested
	@DisplayName("Overriding and setting associated data")
	class OverridingAndSettingTest {

		@Test
		@DisplayName(
			"should override existing with new value"
		)
		void shouldOverrideExistingAssociatedDataWithNewValue() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 10);

			assertEquals(
				Integer.valueOf(10),
				builder.getAssociatedData("int")
			);
			assertEquals(
				Integer.valueOf(1),
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedData("int")
			);

			final AssociatedData newVersion = builder.build();

			assertEquals(
				Integer.valueOf(10),
				newVersion.getAssociatedData("int")
			);
			assertEquals(
				2L,
				newVersion.getAssociatedDataValue(
					new AssociatedDataKey("int")
				).orElseThrow().version()
			);
			assertEquals(
				Integer.valueOf(1),
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedData("int")
			);
		}

		@Test
		@DisplayName(
			"should ignore mutations producing equivalent data"
		)
		void shouldIgnoreMutationsThatProduceTheEquivalentAssociatedData() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 1);

			assertEquals(
				Integer.valueOf(1),
				builder.getAssociatedData("int")
			);
			assertSame(
				builder.getAssociatedData("int"),
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedData("int")
			);

			final AssociatedData newVersion = builder.build();
			assertSame(
				newVersion.getAssociatedData("int"),
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedData("int")
			);
		}

		@Test
		@DisplayName("should succeed to add new associated data")
		void shouldSucceedToAddNewAssociatedData() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("short", (short) 10);

			assertEquals(
				Short.valueOf((short) 10),
				builder.getAssociatedData("short")
			);

			final AssociatedData newVersion = builder.build();

			assertEquals(
				Short.valueOf((short) 10),
				newVersion.getAssociatedData("short")
			);
		}

		@Test
		@DisplayName(
			"should set localized associated data"
		)
		void shouldSetLocalizedAssociatedData() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData(
						"greetings",
						Locale.FRENCH,
						"Bonjour"
					);

			assertEquals(
				"Bonjour",
				builder.getAssociatedData(
					"greetings", Locale.FRENCH
				)
			);
			// original locales remain untouched
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

			final AssociatedData newVersion = builder.build();
			assertEquals(
				"Bonjour",
				newVersion.getAssociatedData(
					"greetings", Locale.FRENCH
				)
			);
		}

		@Test
		@DisplayName(
			"should override localized associated data"
		)
		void shouldOverrideLocalizedAssociatedData() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData(
						"greetings",
						Locale.ENGLISH,
						"Hi"
					);

			assertEquals(
				"Hi",
				builder.getAssociatedData(
					"greetings", Locale.ENGLISH
				)
			);

			final AssociatedData newVersion = builder.build();
			assertEquals(
				"Hi",
				newVersion.getAssociatedData(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				2L,
				newVersion.getAssociatedDataValue(
					new AssociatedDataKey(
						"greetings", Locale.ENGLISH
					)
				).orElseThrow().version()
			);
		}

		@Test
		@DisplayName(
			"should set associated data array"
		)
		void shouldSetAssociatedDataArray() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData(
						"intArray",
						new Integer[]{1, 2, 3}
					);

			assertArrayEquals(
				new Integer[]{1, 2, 3},
				builder.getAssociatedDataArray("intArray")
			);

			final AssociatedData newVersion = builder.build();
			assertArrayEquals(
				new Integer[]{1, 2, 3},
				newVersion.getAssociatedDataArray("intArray")
			);
		}
	}

	@Nested
	@DisplayName("No-op and deduplication")
	class NoOpAndDeduplicationTest {

		@Test
		@DisplayName(
			"should skip mutations that mean no change"
		)
		void shouldSkipMutationsThatMeansNoChange() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 1)
					.setAssociatedData(
						"range",
						IntegerNumberRange.between(5, 11)
					)
					.setAssociatedData(
						"range",
						IntegerNumberRange.between(4, 8)
					);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"should skip mutations after set-reset to original"
		)
		void shouldSkipMutationsAfterSettingAndResettingToOriginal() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 99)
					.setAssociatedData("int", 1);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Removing associated data")
	class RemovingTest {

		@Test
		@DisplayName("should remove existing associated data")
		void shouldRemoveExistingAssociatedData() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.removeAssociatedData("int");

			assertNull(builder.getAssociatedData("int"));
			assertEquals(
				Integer.valueOf(1),
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedData("int")
			);

			final AssociatedData newVersion = builder.build();

			assertEquals(
				Integer.valueOf(1),
				newVersion.getAssociatedData("int")
			);

			final AssociatedDataValue associatedDataValue =
				newVersion.getAssociatedDataValue(
					new AssociatedDataKey("int")
				).orElseThrow();
			assertEquals(2L, associatedDataValue.version());
			assertTrue(associatedDataValue.dropped());
			assertEquals(
				Integer.valueOf(1),
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedData("int")
			);
		}

		@Test
		@DisplayName(
			"should remove existing localized associated data"
		)
		void shouldRemoveExistingLocalizedAssociatedData() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.removeAssociatedData(
						"greetings", Locale.GERMAN
					);

			assertNull(
				builder.getAssociatedData(
					"greetings", Locale.GERMAN
				)
			);
			assertEquals(
				"Hello",
				builder.getAssociatedData(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				1L,
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedDataValue(
						new AssociatedDataKey(
							"greetings", Locale.GERMAN
						)
					).orElseThrow().version()
			);
			assertEquals(
				1L,
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData
					.getAssociatedDataValue(
						new AssociatedDataKey(
							"greetings", Locale.ENGLISH
						)
					).orElseThrow().version()
			);

			final AssociatedData newVersion = builder.build();

			assertEquals(
				"Tschüss",
				newVersion.getAssociatedData(
					"greetings", Locale.GERMAN
				)
			);
			final AssociatedDataValue germanGreeting =
				newVersion.getAssociatedDataValue(
					new AssociatedDataKey(
						"greetings", Locale.GERMAN
					)
				).orElseThrow();
			assertEquals(2L, germanGreeting.version());
			assertTrue(germanGreeting.dropped());

			final AssociatedDataValue englishGreeting =
				newVersion.getAssociatedDataValue(
					new AssociatedDataKey(
						"greetings", Locale.ENGLISH
					)
				).orElseThrow();
			assertEquals(
				"Hello",
				newVersion.getAssociatedData(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				1L, englishGreeting.version()
			);
			assertFalse(englishGreeting.dropped());
		}
	}

	@Nested
	@DisplayName("Names and locales")
	class NamesAndLocalesTest {

		@Test
		@DisplayName(
			"should return associated data locales"
		)
		void shouldReturnAssociatedDataLocales() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				);

			final Set<Locale> locales =
				builder.getAssociatedDataLocales();
			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.GERMAN));
			assertEquals(2, locales.size());
		}

		@Test
		@DisplayName(
			"should return associated data keys"
		)
		void shouldReturnAssociatedDataKeys() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				);

			final Set<AssociatedDataKey> keys =
				builder.getAssociatedDataKeys();
			assertTrue(
				keys.contains(new AssociatedDataKey("int"))
			);
			assertTrue(
				keys.contains(new AssociatedDataKey("range"))
			);
			assertTrue(
				keys.contains(
					new AssociatedDataKey("bigDecimal")
				)
			);
			assertTrue(
				keys.contains(
					new AssociatedDataKey(
						"greetings", Locale.ENGLISH
					)
				)
			);
			assertTrue(
				keys.contains(
					new AssociatedDataKey(
						"greetings", Locale.GERMAN
					)
				)
			);
			assertEquals(5, keys.size());
		}
	}

	@Nested
	@DisplayName("Schema validation")
	class SchemaValidationTest {

		@Test
		@DisplayName(
			"should respect existing schema early on"
		)
		void shouldRespectExistingSchemaEarlyOnWhenSettingAssociatedDatas() {
			final EntitySchemaContract updatedSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA,
					PRODUCT_SCHEMA
				)
					.withAssociatedData(
						"string", String.class
					)
					.withAssociatedData(
						"int",
						Integer.class,
						AssociatedDataSchemaEditor::localized
					)
					.verifySchemaStrictly()
					.withLocale(Locale.ENGLISH)
					.toInstance();

			final AssociatedData associatedData =
				new InitialAssociatedDataBuilder(updatedSchema)
					.setAssociatedData("string", "Hi")
					.setAssociatedData(
						"int", Locale.ENGLISH, 5
					)
					.build();

			final ExistingAssociatedDataBuilder attrBuilder =
				new ExistingAssociatedDataBuilder(
					updatedSchema, associatedData
				);
			// try to add unknown associatedData
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"new", "A"
				)
			);
			// try to add associatedData with bad type
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", 1
				)
			);
			// try to add as localized, when it is not
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.ENGLISH, "string"
				)
			);
			// try to add localized with non supported locale
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.GERMAN, "string"
				)
			);
			// try to add as non-localized, when it is
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"int", 1
				)
			);
			// try to set associatedData correctly
			final AssociatedData newAttrs = attrBuilder
				.setAssociatedData("string", "Hello")
				.setAssociatedData(
					"int", Locale.ENGLISH, 7
				)
				.build();

			assertEquals(
				2,
				newAttrs.getAssociatedDataKeys().size()
			);
			assertEquals(
				"Hello",
				newAttrs.getAssociatedData("string")
			);
			assertEquals(
				2,
				newAttrs.getAssociatedDataValue(
					new AssociatedDataKey("string")
				).orElseThrow().version()
			);
			assertEquals(
				Integer.valueOf(7),
				newAttrs.getAssociatedData(
					"int", Locale.ENGLISH
				)
			);
			assertEquals(
				2,
				newAttrs.getAssociatedDataValue(
					new AssociatedDataKey(
						"int", Locale.ENGLISH
					)
				).orElseThrow().version()
			);
			assertNull(
				newAttrs.getAssociatedData(
					"int", Locale.GERMAN
				)
			);
		}

		@Test
		@DisplayName(
			"should allow adding if verification not strict"
		)
		void shouldAllowToAddNewAssociatedDatasIfVerificationIsNotStrict() {
			final EntitySchemaContract updatedSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA,
					PRODUCT_SCHEMA
				)
					.withAssociatedData(
						"string", String.class
					)
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

			final AssociatedData associatedData =
				new InitialAssociatedDataBuilder(updatedSchema)
					.setAssociatedData("string", "Hi")
					.setAssociatedData(
						"int", Locale.ENGLISH, 5
					)
					.build();

			final ExistingAssociatedDataBuilder attrBuilder =
				new ExistingAssociatedDataBuilder(
					updatedSchema, associatedData
				);
			// try to add unknown associatedData
			attrBuilder.setAssociatedData("new", "A");
			// try to add unknown localized associatedData
			attrBuilder.setAssociatedData(
				"newLocalized", Locale.ENGLISH, "B"
			);
			// try to add new localized with unsupported locale
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"newLocalized",
					Locale.GERMAN,
					"string"
				)
			);
			// try to add associatedData with bad type
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", 1
				)
			);
			// try to add as localized, when it is not
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.ENGLISH, "string"
				)
			);
			// try to add localized with non supported locale
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"string", Locale.GERMAN, "string"
				)
			);
			// try to add as non-localized, when it is
			assertThrows(
				InvalidMutationException.class,
				() -> attrBuilder.setAssociatedData(
					"int", 1
				)
			);
			// try to set associatedData correctly
			final AssociatedData updatedAttrs = attrBuilder
				.setAssociatedData("string", "Hi")
				.setAssociatedData(
					"int", Locale.ENGLISH, 5
				)
				.build();

			assertEquals(
				4,
				updatedAttrs.getAssociatedDataKeys().size()
			);
			assertEquals(
				"A",
				updatedAttrs.getAssociatedData("new")
			);
			assertEquals(
				"B",
				updatedAttrs.getAssociatedData(
					"newLocalized", Locale.ENGLISH
				)
			);
			assertEquals(
				"Hi",
				updatedAttrs.getAssociatedData("string")
			);
			assertEquals(
				Integer.valueOf(5),
				updatedAttrs.getAssociatedData(
					"int", Locale.ENGLISH
				)
			);
			assertNull(
				updatedAttrs.getAssociatedData(
					"int", Locale.GERMAN
				)
			);
		}

		@Test
		@DisplayName(
			"should respect schema but allow adding locales"
		)
		void shouldRespectExistingSchemaEarlyOnWhenSettingAssociatedDatasButAllowAddingLocales() {
			final EntitySchemaContract updatedSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA,
					PRODUCT_SCHEMA
				)
					.withAssociatedData(
						"string", String.class
					)
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

			final AssociatedData associatedData =
				new InitialAssociatedDataBuilder(updatedSchema)
					.setAssociatedData("string", "Hi")
					.setAssociatedData(
						"int", Locale.ENGLISH, 5
					)
					.build();

			final ExistingAssociatedDataBuilder attrBuilder =
				new ExistingAssociatedDataBuilder(
					updatedSchema, associatedData
				);
			final AssociatedData updatedAttrs = attrBuilder
				.setAssociatedData("string", "Hello")
				.setAssociatedData(
					"int", Locale.ENGLISH, 9
				)
				.setAssociatedData(
					"int", Locale.GERMAN, 10
				)
				.build();

			assertEquals(
				3,
				updatedAttrs.getAssociatedDataKeys().size()
			);
			assertEquals(
				"Hello",
				updatedAttrs.getAssociatedData("string")
			);
			assertEquals(
				Integer.valueOf(9),
				updatedAttrs.getAssociatedData(
					"int", Locale.ENGLISH
				)
			);
			assertEquals(
				Integer.valueOf(10),
				updatedAttrs.getAssociatedData(
					"int", Locale.GERMAN
				)
			);
		}
	}

	@Nested
	@DisplayName("Change set and identity")
	class ChangeSetAndIdentityTest {

		@Test
		@DisplayName(
			"should return accumulated mutations"
		)
		void shouldReturnAccumulatedMutations() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 10)
					.removeAssociatedData("int")
					.setAssociatedData("short", (short) 5)
					.setAssociatedData(
						"greetings",
						Locale.FRENCH,
						"Bonjour"
					)
					.removeAssociatedData(
						"greetings", Locale.ENGLISH
					);

			assertEquals(
				4,
				(int) builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"should return original when nothing changed"
		)
		void shouldReturnOriginalAssociatedDataInstanceWhenNothingHasChanged() {
			final AssociatedData newAssociatedData =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 1)
					.setAssociatedData(
						"range",
						IntegerNumberRange.between(4, 8)
					)
					.setAssociatedData(
						"bigDecimal",
						new BigDecimal("1.123")
					)
					.setAssociatedData(
						"greetings",
						Locale.ENGLISH,
						"Hello"
					)
					.setAssociatedData(
						"greetings",
						Locale.GERMAN,
						"Tschüss"
					)
					.build();

			assertSame(
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData,
				newAssociatedData
			);
		}

		@Test
		@DisplayName(
			"should build empty change set when no modifications"
		)
		void shouldBuildEmptyChangeSetWhenNoModifications() {
			final AssociatedDataBuilder builder =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"should return new instance when changes exist"
		)
		void shouldReturnNewInstanceWhenChangesExist() {
			final AssociatedData newAssociatedData =
				new ExistingAssociatedDataBuilder(
					PRODUCT_SCHEMA,
					ExistingAssociatedDataBuilderTest.this
						.initialAssociatedData
				)
					.setAssociatedData("int", 99)
					.build();

			assertNotSame(
				ExistingAssociatedDataBuilderTest.this
					.initialAssociatedData,
				newAssociatedData
			);
			assertEquals(
				Integer.valueOf(99),
				newAssociatedData.getAssociatedData("int")
			);
		}
	}

}
