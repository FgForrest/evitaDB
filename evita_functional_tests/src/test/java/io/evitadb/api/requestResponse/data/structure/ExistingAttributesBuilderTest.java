/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.InvalidDataTypeMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingAttributesBuilder} class. Ie. tries modification on already existing
 * {@link Attributes} container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ExistingAttributesBuilderTest extends AbstractBuilderTest {
	private Attributes initialAttributes;

	@BeforeEach
	void setUp() {
		initialAttributes = new InitialAttributesBuilder(
			new InternalEntitySchemaBuilder(CATALOG_SCHEMA, PRODUCT_SCHEMA)
				.verifySchemaButCreateOnTheFly()
				.withAttribute("int", Integer.class)
				.withAttribute("range", IntegerNumberRange.class)
				.withAttribute("bigDecimal", BigDecimal.class)
				.withAttribute("greetings", String.class, whichIs -> whichIs.localized())
				.toInstance()
		)
			.setAttribute("int", 1)
			.setAttribute("range", IntegerNumberRange.between(4, 8))
			.setAttribute("bigDecimal", new BigDecimal("1.123"))
			.setAttribute("greetings", Locale.ENGLISH, "Hello")
			.setAttribute("greetings", Locale.GERMAN, "Tschüss")
			.build();
	}

	@Test
	void shouldOverrideExistingAttributeWithNewValue() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("int", 10);

		assertEquals(Integer.valueOf(10), builder.getAttribute("int"));
		assertEquals(Integer.valueOf(1), initialAttributes.getAttribute("int"));

		final Attributes newVersion = builder.build();

		assertEquals(Integer.valueOf(10), newVersion.getAttribute("int"));
		assertEquals(2L, newVersion.getAttributeValue(new AttributeKey("int")).getVersion());
		assertEquals(Integer.valueOf(1), initialAttributes.getAttribute("int"));
	}

	@Test
	void shouldSkipMutationsThatMeansNoChange() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("int", 1)
			.setAttribute("range", IntegerNumberRange.between(5, 11))
			.setAttribute("range", IntegerNumberRange.between(4, 8));

		assertEquals(0, builder.buildChangeSet().count());
	}

	@Test
	void shouldAddLocalizedArray() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("localizedArray", Locale.ENGLISH, new Integer[]{1, 5})
			.setAttribute("localizedArray", new Locale("cs"), new Integer[]{6, 7});

		assertArrayEquals(new Integer[]{1, 5}, builder.getAttributeArray("localizedArray", Locale.ENGLISH));
		assertArrayEquals(new Integer[]{6, 7}, builder.getAttributeArray("localizedArray", new Locale("cs")));
	}

	@Test
	void shouldIgnoreMutationsThatProduceTheEquivalentAttribute() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("int", 1);

		assertEquals(Integer.valueOf(1), builder.getAttribute("int"));
		assertSame(builder.getAttribute("int"), initialAttributes.getAttribute("int"));

		final Attributes newVersion = builder.build();
		assertSame(newVersion.getAttribute("int"), initialAttributes.getAttribute("int"));
	}

	@Test
	void shouldRemoveExistingAttribute() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.removeAttribute("int");

		assertNull(builder.getAttribute("int"));
		assertEquals(Integer.valueOf(1), initialAttributes.getAttribute("int"));

		final Attributes newVersion = builder.build();

		assertEquals(Integer.valueOf(1), newVersion.getAttribute("int"));
		final AttributeValue attributeValue = newVersion.getAttributeValue(new AttributeKey("int"));
		assertEquals(2L, attributeValue.getVersion());
		assertTrue(attributeValue.isDropped());

		assertEquals(Integer.valueOf(1), initialAttributes.getAttribute("int"));
	}

	@Test
	void shouldRemoveExistingLocalizedAttribute() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.removeAttribute("greetings", Locale.GERMAN);

		assertNull(builder.getAttribute("greetings", Locale.GERMAN));
		assertEquals("Hello", builder.getAttribute("greetings", Locale.ENGLISH));
		assertEquals(1L, initialAttributes.getAttributeValue(new AttributeKey("greetings", Locale.GERMAN)).getVersion());
		assertEquals(1L, initialAttributes.getAttributeValue(new AttributeKey("greetings", Locale.ENGLISH)).getVersion());

		final Attributes newVersion = builder.build();

		assertEquals("Tschüss", newVersion.getAttribute("greetings", Locale.GERMAN));
		final AttributeValue germanGreeting = newVersion.getAttributeValue(new AttributeKey("greetings", Locale.GERMAN));
		assertEquals(2L, germanGreeting.getVersion());
		assertTrue(germanGreeting.isDropped());

		final AttributeValue englishGreeting = newVersion.getAttributeValue(new AttributeKey("greetings", Locale.ENGLISH));
		assertEquals("Hello", newVersion.getAttribute("greetings", Locale.ENGLISH));
		assertEquals(1L, englishGreeting.getVersion());
		assertFalse(englishGreeting.isDropped());
	}

	@Test
	void shouldSucceedToAddNewAttribute() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("short", (short) 10);

		assertEquals(Short.valueOf((short) 10), builder.getAttribute("short"));

		final Attributes newVersion = builder.build();

		assertEquals(Short.valueOf((short) 10), newVersion.getAttribute("short"));
	}

	@Test
	void shouldUpdateSchemaAlongTheWay() {
		final ExistingAttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes);
		builder.setAttribute("short", (short) 4);
		final Attributes attributes = builder.build();

		assertEquals(
			AttributeSchema._internalBuild("int", false, false, false, false, false, Integer.class, null),
			attributes.getAttributeSchema("int").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("short", false, false, false, false, false, Short.class, null),
			attributes.getAttributeSchema("short").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("range", false, false, false, false, false, IntegerNumberRange.class, null),
			attributes.getAttributeSchema("range").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("bigDecimal", false, false, false, false, false, BigDecimal.class, null),
			attributes.getAttributeSchema("bigDecimal").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("greetings", false, false, false, true, false, String.class, null),
			attributes.getAttributeSchema("greetings").orElse(null)
		);
	}

	@Test
	void shouldAllowAddMutation() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.mutateAttribute(new ApplyDeltaAttributeMutation<>("int", 2));

		assertEquals(Integer.valueOf(3), builder.getAttribute("int"));

		final Attributes newVersion = builder.build();

		assertEquals(Integer.valueOf(3), newVersion.getAttribute("int"));
	}

	@Test
	void shouldReturnAccumulatedMutations() {
		final AttributesBuilder builder = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("int", 10)
			.removeAttribute("int")
			.setAttribute("short", (short) 5)
			.setAttribute("greetings", Locale.FRENCH, "Bonjour")
			.removeAttribute("greetings", Locale.ENGLISH);

		assertEquals(4, (int) builder.buildChangeSet().count());
	}

	@Test
	void shouldRespectExistingSchemaEarlyOnWhenSettingAttributes() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("string", String.class)
			.withAttribute("int", Integer.class, AttributeSchemaEditor::localized)
			.verifySchemaStrictly()
			.withLocale(Locale.ENGLISH)
			.toInstance();

		final Attributes attrs = new InitialAttributesBuilder(updatedSchema)
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.build();

		final ExistingAttributesBuilder attrBuilder = new ExistingAttributesBuilder(updatedSchema, attrs);
		// try to add unknown attribute
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("new", "A"));
		// try to add attribute with bad type
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("string", 1));
		// try to add attribute as localized, when it is not
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("string", Locale.ENGLISH, "string"));
		// try to add attribute as localized with non supported locale
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("string", Locale.GERMAN, "string"));
		// try to add attribute as non-localized, when it is
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("int", 1));
		// try to set attributes correctly
		final Attributes newAttrs = attrBuilder
			.setAttribute("string", "Hello")
			.setAttribute("int", Locale.ENGLISH, 7)
			.build();

		assertEquals(2, newAttrs.getAttributeKeys().size());
		assertEquals("Hello", newAttrs.getAttribute("string"));
		assertEquals(2, newAttrs.getAttributeValue(new AttributeKey("string")).getVersion());
		assertEquals(Integer.valueOf(7), newAttrs.getAttribute("int", Locale.ENGLISH));
		assertEquals(2, newAttrs.getAttributeValue(new AttributeKey("int", Locale.ENGLISH)).getVersion());
		assertNull(newAttrs.getAttribute("int", Locale.GERMAN));
	}

	@Test
	void shouldAllowToAddNewAttributesIfVerificationIsNotStrict() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("string", String.class)
			.withAttribute("int", Integer.class, AttributeSchemaEditor::localized)
			.withLocale(Locale.ENGLISH)
			.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES)
			.toInstance();

		final Attributes attrs = new InitialAttributesBuilder(updatedSchema)
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.build();

		final ExistingAttributesBuilder attrBuilder = new ExistingAttributesBuilder(updatedSchema, attrs);
		// try to add unknown attribute
		attrBuilder.setAttribute("new", "A");
		// try to add unknown localized attribute
		attrBuilder.setAttribute("newLocalized", Locale.ENGLISH, "B");
		// try to add new unknown localized attribute with unsupported locale
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("newLocalized", Locale.GERMAN, "string"));
		// try to add attribute with bad type
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("string", 1));
		// try to add attribute as localized, when it is not
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("string", Locale.ENGLISH, "string"));
		// try to add attribute as localized with non supported locale
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("string", Locale.GERMAN, "string"));
		// try to add attribute as non-localized, when it is
		assertThrows(InvalidMutationException.class, () -> attrBuilder.setAttribute("int", 1));
		// try to set attributes correctly
		final Attributes updatedAttrs = attrBuilder
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.build();

		assertEquals(4, updatedAttrs.getAttributeKeys().size());
		assertEquals("A", updatedAttrs.getAttribute("new"));
		assertEquals("B", updatedAttrs.getAttribute("newLocalized", Locale.ENGLISH));
		assertEquals("Hi", updatedAttrs.getAttribute("string"));
		assertEquals(Integer.valueOf(5), updatedAttrs.getAttribute("int", Locale.ENGLISH));
		assertNull(updatedAttrs.getAttribute("int", Locale.GERMAN));
	}

	@Test
	void shouldRespectExistingSchemaEarlyOnWhenSettingAttributesButAllowAddingLocales() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("string", String.class)
			.withAttribute("int", Integer.class, AttributeSchemaEditor::localized)
			.withLocale(Locale.ENGLISH)
			.verifySchemaButAllow(EvolutionMode.ADDING_LOCALES)
			.toInstance();

		final Attributes attrs = new InitialAttributesBuilder(updatedSchema)
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.build();

		final ExistingAttributesBuilder attrBuilder = new ExistingAttributesBuilder(updatedSchema, attrs);
		final Attributes updatedAttrs = attrBuilder
			.setAttribute("string", "Hello")
			.setAttribute("int", Locale.ENGLISH, 9)
			.setAttribute("int", Locale.GERMAN, 10)
			.build();

		assertEquals(3, updatedAttrs.getAttributeKeys().size());
		assertEquals("Hello", updatedAttrs.getAttribute("string"));
		assertEquals(Integer.valueOf(9), updatedAttrs.getAttribute("int", Locale.ENGLISH));
		assertEquals(Integer.valueOf(10), updatedAttrs.getAttribute("int", Locale.GERMAN));
	}

	@Test
	void shouldReturnOriginalAttributesInstanceWhenNothingHasChanged() {
		final Attributes newAttributes = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.setAttribute("int", 1)
			.setAttribute("range", IntegerNumberRange.between(4, 8))
			.setAttribute("bigDecimal", new BigDecimal("1.123"))
			.setAttribute("greetings", Locale.ENGLISH, "Hello")
			.setAttribute("greetings", Locale.GERMAN, "Tschüss")
			.build();

		assertSame(initialAttributes, newAttributes);
	}

	@Test
	void shouldFailToAddIncompatibleMutation() {
		assertThrows(
			InvalidDataTypeMutationException.class,
			() -> new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
				.addMutation(new UpsertAttributeMutation("int", "a"))
		);
	}

	@Test
	void shouldFailRemovingNonExistingAttribute() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
				.addMutation(new RemoveAttributeMutation("abc"))
		);
	}

	@Test
	void shouldFailToApplyDeltaOnNonExistingAttribute() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
				.addMutation(new ApplyDeltaAttributeMutation<>("newInt", 10))
		);
	}

	@Test
	void shouldAllowCombiningMultipleMutationsAtOnce() {
		final Attributes newAttributes = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.addMutation(new UpsertAttributeMutation("newInt", 10))
			.addMutation(new ApplyDeltaAttributeMutation<>("newInt", -1))
			.addMutation(new ApplyDeltaAttributeMutation<>("newInt", -4))
			.build();

		assertEquals(Integer.valueOf(5), newAttributes.getAttribute("newInt"));
	}

	@Test
	void shouldAllowCombiningCreateAndRemoveMutationAtOnce() {
		final Attributes newAttributes = new ExistingAttributesBuilder(PRODUCT_SCHEMA, initialAttributes)
			.addMutation(new UpsertAttributeMutation("newInt", 10))
			.addMutation(new RemoveAttributeMutation("newInt"))
			.build();

		assertNull(newAttributes.getAttribute("newInt"));
	}

}