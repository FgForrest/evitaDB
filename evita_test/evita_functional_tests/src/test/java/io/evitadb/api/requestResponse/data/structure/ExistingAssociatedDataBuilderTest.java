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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingAssociatedDataBuilder} class. Ie. tries modification on already existing
 * {@link AssociatedData} container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ExistingAssociatedDataBuilderTest extends AbstractBuilderTest {
	private AssociatedData initialAssociatedData;

	@BeforeEach
	void setUp() {
		this.initialAssociatedData = new InitialAssociatedDataBuilder(EntitySchema._internalBuild("whatever"))
			.setAssociatedData("int", 1)
			.setAssociatedData("range", IntegerNumberRange.between(4, 8))
			.setAssociatedData("bigDecimal", new BigDecimal("1.123"))
			.setAssociatedData("greetings", Locale.ENGLISH, "Hello")
			.setAssociatedData("greetings", Locale.GERMAN, "Tschüss")
			.build();
	}

	@Test
	void shouldOverrideExistingAssociatedDataWithNewValue() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.setAssociatedData("int", 10);

		assertEquals(Integer.valueOf(10), builder.getAssociatedData("int"));
		assertEquals(Integer.valueOf(1), this.initialAssociatedData.getAssociatedData("int"));

		final AssociatedData newVersion = builder.build();

		assertEquals(Integer.valueOf(10), newVersion.getAssociatedData("int"));
		assertEquals(2L, newVersion.getAssociatedDataValue(new AssociatedDataKey("int")).orElseThrow().version());
		assertEquals(Integer.valueOf(1), this.initialAssociatedData.getAssociatedData("int"));
	}

	@Test
	void shouldSkipMutationsThatMeansNoChange() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.setAssociatedData("int", 1)
			.setAssociatedData("range", IntegerNumberRange.between(5, 11))
			.setAssociatedData("range", IntegerNumberRange.between(4, 8));

		assertEquals(0, builder.buildChangeSet().count());
	}

	@Test
	void shouldIgnoreMutationsThatProduceTheEquivalentAssociatedData() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.setAssociatedData("int", 1);

		assertEquals(Integer.valueOf(1), builder.getAssociatedData("int"));
		assertSame(builder.getAssociatedData("int"), this.initialAssociatedData.getAssociatedData("int"));

		final AssociatedData newVersion = builder.build();
		assertSame(newVersion.getAssociatedData("int"), this.initialAssociatedData.getAssociatedData("int"));
	}

	@Test
	void shouldRemoveExistingAssociatedData() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.removeAssociatedData("int");

		assertNull(builder.getAssociatedData("int"));
		assertEquals(Integer.valueOf(1), this.initialAssociatedData.getAssociatedData("int"));

		final AssociatedData newVersion = builder.build();

		assertEquals(Integer.valueOf(1), newVersion.getAssociatedData("int"));

		final AssociatedDataValue associatedDataValue = newVersion.getAssociatedDataValue(new AssociatedDataKey("int")).orElseThrow();
		assertEquals(2L, associatedDataValue.version());
		assertTrue(associatedDataValue.dropped());
		assertEquals(Integer.valueOf(1), this.initialAssociatedData.getAssociatedData("int"));
	}

	@Test
	void shouldRemoveExistingLocalizedAssociatedData() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.removeAssociatedData("greetings", Locale.GERMAN);

		assertNull(builder.getAssociatedData("greetings", Locale.GERMAN));
		assertEquals("Hello", builder.getAssociatedData("greetings", Locale.ENGLISH));
		assertEquals(1L, this.initialAssociatedData.getAssociatedDataValue(new AssociatedDataKey("greetings", Locale.GERMAN)).orElseThrow().version());
		assertEquals(1L, this.initialAssociatedData.getAssociatedDataValue(new AssociatedDataKey("greetings", Locale.ENGLISH)).orElseThrow().version());

		final AssociatedData newVersion = builder.build();

		assertEquals("Tschüss", newVersion.getAssociatedData("greetings", Locale.GERMAN));
		final AssociatedDataValue germanGreeting = newVersion.getAssociatedDataValue(new AssociatedDataKey("greetings", Locale.GERMAN)).orElseThrow();
		assertEquals(2L, germanGreeting.version());
		assertTrue(germanGreeting.dropped());

		final AssociatedDataValue englishGreeting = newVersion.getAssociatedDataValue(new AssociatedDataKey("greetings", Locale.ENGLISH)).orElseThrow();
		assertEquals("Hello", newVersion.getAssociatedData("greetings", Locale.ENGLISH));
		assertEquals(1L, englishGreeting.version());
		assertFalse(englishGreeting.dropped());
	}

	@Test
	void shouldSucceedToAddNewAssociatedData() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.setAssociatedData("short", (short) 10);

		assertEquals(Short.valueOf((short) 10), builder.getAssociatedData("short"));

		final AssociatedData newVersion = builder.build();

		assertEquals(Short.valueOf((short) 10), newVersion.getAssociatedData("short"));
	}

	@Test
	void shouldReturnAccumulatedMutations() {
		final AssociatedDataBuilder builder = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.setAssociatedData("int", 10)
			.removeAssociatedData("int")
			.setAssociatedData("short", (short) 5)
			.setAssociatedData("greetings", Locale.FRENCH, "Bonjour")
			.removeAssociatedData("greetings", Locale.ENGLISH);

		assertEquals(4, (int) builder.buildChangeSet().count());
	}

	@Test
	void shouldRespectExistingSchemaEarlyOnWhenSettingAssociatedDatas() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAssociatedData("string", String.class)
			.withAssociatedData("int", Integer.class, AssociatedDataSchemaEditor::localized)
			.verifySchemaStrictly()
			.withLocale(Locale.ENGLISH)
			.toInstance();

		final AssociatedData associatedData = new InitialAssociatedDataBuilder(updatedSchema)
			.setAssociatedData("string", "Hi")
			.setAssociatedData("int", Locale.ENGLISH, 5)
			.build();

		final ExistingAssociatedDataBuilder attrBuilder = new ExistingAssociatedDataBuilder(updatedSchema, associatedData);
		// try to add unknown associatedData
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("new", "A"));
		// try to add associatedData with bad type
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("string", 1));
		// try to add associatedData as localized, when it is not
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("string", Locale.ENGLISH, "string"));
		// try to add associatedData as localized with non supported locale
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("string", Locale.GERMAN, "string"));
		// try to add associatedData as non-localized, when it is
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("int", 1));
		// try to set associatedData correctly
		final AssociatedData newAttrs = attrBuilder
			.setAssociatedData("string", "Hello")
			.setAssociatedData("int", Locale.ENGLISH, 7)
			.build();

		assertEquals(2, newAttrs.getAssociatedDataKeys().size());
		assertEquals("Hello", newAttrs.getAssociatedData("string"));
		assertEquals(2, newAttrs.getAssociatedDataValue(new AssociatedDataKey("string")).orElseThrow().version());
		assertEquals(Integer.valueOf(7), newAttrs.getAssociatedData("int", Locale.ENGLISH));
		assertEquals(2, newAttrs.getAssociatedDataValue(new AssociatedDataKey("int", Locale.ENGLISH)).orElseThrow().version());
		assertNull(newAttrs.getAssociatedData("int", Locale.GERMAN));
	}

	@Test
	void shouldAllowToAddNewAssociatedDatasIfVerificationIsNotStrict() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAssociatedData("string", String.class)
			.withAssociatedData("int", Integer.class, AssociatedDataSchemaEditor::localized)
			.withLocale(Locale.ENGLISH)
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA)
			.toInstance();

		final AssociatedData associatedData = new InitialAssociatedDataBuilder(updatedSchema)
			.setAssociatedData("string", "Hi")
			.setAssociatedData("int", Locale.ENGLISH, 5)
			.build();

		final ExistingAssociatedDataBuilder attrBuilder = new ExistingAssociatedDataBuilder(updatedSchema, associatedData);
		// try to add unknown associatedData
		attrBuilder.setAssociatedData("new", "A");
		// try to add unknown localized associatedData
		attrBuilder.setAssociatedData("newLocalized", Locale.ENGLISH, "B");
		// try to add new unknown localized associatedData with unsupported locale
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("newLocalized", Locale.GERMAN, "string"));
		// try to add associatedData with bad type
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("string", 1));
		// try to add associatedData as localized, when it is not
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("string", Locale.ENGLISH, "string"));
		// try to add associatedData as localized with non supported locale
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("string", Locale.GERMAN, "string"));
		// try to add associatedData as non-localized, when it is
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAssociatedData("int", 1));
		// try to set associatedData correctly
		final AssociatedData updatedAttrs = attrBuilder
			.setAssociatedData("string", "Hi")
			.setAssociatedData("int", Locale.ENGLISH, 5)
			.build();

		assertEquals(4, updatedAttrs.getAssociatedDataKeys().size());
		assertEquals("A", updatedAttrs.getAssociatedData("new"));
		assertEquals("B", updatedAttrs.getAssociatedData("newLocalized", Locale.ENGLISH));
		assertEquals("Hi", updatedAttrs.getAssociatedData("string"));
		assertEquals(Integer.valueOf(5), updatedAttrs.getAssociatedData("int", Locale.ENGLISH));
		assertNull(updatedAttrs.getAssociatedData("int", Locale.GERMAN));
	}

	@Test
	void shouldRespectExistingSchemaEarlyOnWhenSettingAssociatedDatasButAllowAddingLocales() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAssociatedData("string", String.class)
			.withAssociatedData("int", Integer.class, AssociatedDataSchemaEditor::localized)
			.withLocale(Locale.ENGLISH)
			.verifySchemaButAllow(EvolutionMode.ADDING_LOCALES)
			.toInstance();

		final AssociatedData associatedData = new InitialAssociatedDataBuilder(updatedSchema)
			.setAssociatedData("string", "Hi")
			.setAssociatedData("int", Locale.ENGLISH, 5)
			.build();

		final ExistingAssociatedDataBuilder attrBuilder = new ExistingAssociatedDataBuilder(updatedSchema, associatedData);
		final AssociatedData updatedAttrs = attrBuilder
			.setAssociatedData("string", "Hello")
			.setAssociatedData("int", Locale.ENGLISH, 9)
			.setAssociatedData("int", Locale.GERMAN, 10)
			.build();

		assertEquals(3, updatedAttrs.getAssociatedDataKeys().size());
		assertEquals("Hello", updatedAttrs.getAssociatedData("string"));
		assertEquals(Integer.valueOf(9), updatedAttrs.getAssociatedData("int", Locale.ENGLISH));
		assertEquals(Integer.valueOf(10), updatedAttrs.getAssociatedData("int", Locale.GERMAN));
	}

	@Test
	void shouldReturnOriginalAssociatedDataInstanceWhenNothingHasChanged() {
		final AssociatedData newAssociatedData = new ExistingAssociatedDataBuilder(PRODUCT_SCHEMA, this.initialAssociatedData)
			.setAssociatedData("int", 1)
			.setAssociatedData("range", IntegerNumberRange.between(4, 8))
			.setAssociatedData("bigDecimal", new BigDecimal("1.123"))
			.setAssociatedData("greetings", Locale.ENGLISH, "Hello")
			.setAssociatedData("greetings", Locale.GERMAN, "Tschüss")
			.build();

		assertSame(this.initialAssociatedData, newAssociatedData);
	}

}
