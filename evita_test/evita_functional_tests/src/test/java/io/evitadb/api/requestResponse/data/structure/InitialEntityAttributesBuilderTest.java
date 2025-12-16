/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for InitialEntityAttributesBuilder built on top of shared InitialAttributesBuilderTest.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class InitialEntityAttributesBuilderTest extends InitialAttributesBuilderTest {
	@Override
	protected InitialAttributesBuilder<?, ?> builder() {
		return new InitialEntityAttributesBuilder(EntitySchema._internalBuild("whatever"));
	}

	@Override
	protected Attributes<?> build(InitialAttributesBuilder<?, ?> builder) {
		return ((InitialEntityAttributesBuilder) builder).build();
	}

	@Test
	void shouldDefineAttributeTypesAlongTheWay() {
		final EntityAttributes attributes = ((InitialEntityAttributesBuilder) builder()
			.setAttribute("abc", 1)
			.setAttribute("def", IntegerNumberRange.between(4, 8))
			.setAttribute("dd", new BigDecimal("1.123"))
			.setAttribute("greetings", Locale.ENGLISH, "Hello")
			.setAttribute("greetings", Locale.FRENCH, "Tschüss")
		).build();

		final Set<String> names = attributes.getAttributeNames();
		assertEquals(4, names.size());
		assertTrue(names.contains("abc"));
		assertTrue(names.contains("def"));
		assertTrue(names.contains("dd"));
		assertTrue(names.contains("greetings"));

		assertEquals(
			EntityAttributeSchema._internalBuild("abc", null, Scope.NO_SCOPE, Scope.NO_SCOPE, false, false, false, Integer.class, null),
			attributes.getAttributeSchema("abc").orElse(null)
		);
		assertEquals(
			EntityAttributeSchema._internalBuild("def", null, Scope.NO_SCOPE, Scope.NO_SCOPE, false, false, false, IntegerNumberRange.class, null),
			attributes.getAttributeSchema("def").orElse(null)
		);
		assertEquals(
			EntityAttributeSchema._internalBuild("dd", null, Scope.NO_SCOPE, Scope.NO_SCOPE, false, false, false, BigDecimal.class, null),
			attributes.getAttributeSchema("dd").orElse(null)
		);
		assertEquals(
			EntityAttributeSchema._internalBuild("greetings", null, Scope.NO_SCOPE, Scope.NO_SCOPE, true, false, false, String.class, null),
			attributes.getAttributeSchema("greetings").orElse(null)
		);
	}

	@Test
	void shouldRespectExistingSchemaEarlyOnWhenSettingAttributes() {
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("string", String.class)
			.withAttribute("int", Integer.class, AttributeSchemaEditor::localized)
			.withLocale(Locale.ENGLISH)
			.verifySchemaStrictly()
			.toInstance();

		final InitialEntityAttributesBuilder attrBuilder = new InitialEntityAttributesBuilder(updatedSchema);
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
		final EntityAttributes attrs = attrBuilder
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.build();

		assertEquals(2, attrs.getAttributeKeys().size());
		assertEquals("Hi", attrs.getAttribute("string"));
		assertEquals(Integer.valueOf(5), attrs.getAttribute("int", Locale.ENGLISH));
		assertNull(attrs.getAttribute("int", Locale.GERMAN));
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

		final InitialEntityAttributesBuilder attrBuilder = new InitialEntityAttributesBuilder(updatedSchema);
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
		final EntityAttributes attrs = attrBuilder
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.build();

		assertEquals(4, attrs.getAttributeKeys().size());
		assertEquals("A", attrs.getAttribute("new"));
		assertEquals("B", attrs.getAttribute("newLocalized", Locale.ENGLISH));
		assertEquals("Hi", attrs.getAttribute("string"));
		assertEquals(Integer.valueOf(5), attrs.getAttribute("int", Locale.ENGLISH));
		assertNull(attrs.getAttribute("int", Locale.GERMAN));
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

		final InitialEntityAttributesBuilder attrBuilder = new InitialEntityAttributesBuilder(updatedSchema);
		final EntityAttributes attrs = attrBuilder
			.setAttribute("string", "Hi")
			.setAttribute("int", Locale.ENGLISH, 5)
			.setAttribute("int", Locale.GERMAN, 7)
			.build();

		assertEquals(3, attrs.getAttributeKeys().size());
		assertEquals("Hi", attrs.getAttribute("string"));
		assertEquals(Integer.valueOf(5), attrs.getAttribute("int", Locale.ENGLISH));
		assertEquals(Integer.valueOf(7), attrs.getAttribute("int", Locale.GERMAN));
	}
}