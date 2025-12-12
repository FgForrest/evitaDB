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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This abstract test verifies shared contract of {@link InitialAttributesBuilder} implementations.
 * Concrete subclasses must provide an appropriate builder via {@link #builder()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class InitialAttributesBuilderTest extends AbstractBuilderTest {
	/**
	 * Provides fresh builder instance for each test.
	 */
	protected abstract InitialAttributesBuilder<?, ?> builder();

	/**
	 * Builds the Attributes instance from given builder (implemented by subclass to invoke correct build()).
	 */
	protected abstract Attributes<?> build(InitialAttributesBuilder<?, ?> builder);

	@Test
	void shouldStoreNewValueAndRetrieveIt() {
		final InitialAttributesBuilder<?, ?> b = builder();
		final Attributes<?> attributes = build(b.setAttribute("abc", "DEF"));
		assertEquals("DEF", attributes.getAttribute("abc"));
	}

	@Test
	void shouldOverrideOneOperationWithAnother() {
		InitialAttributesBuilder<?, ?> b = builder();
		b = b.setAttribute("abc", "DEF");
		b = b.setAttribute("abc", "RTE");
		final Attributes<?> attributes = build(b);
		assertEquals("RTE", attributes.getAttribute("abc"));
	}

	@Test
	void shouldFailToAddMutationToNewAttributeContainer() {
		final InitialAttributesBuilder<?, ?> builder = builder();
		builder.mutateAttribute(new UpsertAttributeMutation("abc", 1));
		assertEquals(1, builder.getAttribute("abc", Integer.class).intValue());
	}

	@Test
	void shouldFailWithClassCastIfMappingToDifferentType() {
		final Attributes<?> attributes = build(builder().setAttribute("abc", "DEF"));
		assertThrows(ClassCastException.class, () -> {
			final Integer someInt = attributes.getAttribute("abc");
			fail("Should not be executed at all!");
		});
	}

	@Test
	void shouldStoreNewValueArrayAndRetrieveIt() {
		final Attributes<?> attributes = build(builder().setAttribute("abc", new String[]{"DEF", "XYZ"}));
		assertArrayEquals(new String[]{"DEF", "XYZ"}, attributes.getAttributeArray("abc"));
	}

	@Test
	void shouldRemoveValue() {
		final Attributes<?> attributes = build(
			builder().setAttribute("abc", "DEF").removeAttribute("abc")
		);
		assertFalse(attributes.attributeValues.containsKey(new AttributeKey("abc")));
	}

	@Test
	void shouldRemovePreviouslySetValue() {
		final Attributes<?> attributes = build(
			builder().setAttribute("abc", "DEF").setAttribute("abc", "DEF").removeAttribute("abc")
		);
		assertFalse(attributes.attributeValues.containsKey(new AttributeKey("abc")));
	}

	@Test
	void shouldReturnAttributeNames() {
		final Attributes<?> attributes = build(
			builder().setAttribute("abc", 1).setAttribute("def", IntegerNumberRange.between(4, 8))
		);

		final Set<String> names = attributes.getAttributeNames();
		assertEquals(2, names.size());
		assertTrue(names.contains("abc"));
		assertTrue(names.contains("def"));
	}

	@Test
	void shouldSupportLocalizedAttributes() {
		final InitialAttributesBuilder<?, ?> b = builder()
			.setAttribute("abc", 1)
			.setAttribute("def", IntegerNumberRange.between(4, 8))
			.setAttribute("dd", new BigDecimal("1.123"))
			.setAttribute("greetings", Locale.ENGLISH, "Hello")
			.setAttribute("greetings", Locale.GERMAN, "Tschüss");

		assertEquals(Integer.valueOf(1), b.getAttribute("abc"));
		assertEquals(IntegerNumberRange.between(4, 8), b.getAttribute("def"));
		assertEquals(new BigDecimal("1.123"), b.getAttribute("dd"));
		assertEquals("Hello", b.getAttribute("greetings", Locale.ENGLISH));
		assertEquals("Tschüss", b.getAttribute("greetings", Locale.GERMAN));
		assertNull(b.getAttribute("greetings", Locale.FRENCH));

		final Attributes<?> attributes = build(b);
		final Set<String> names = attributes.getAttributeNames();
		assertEquals(4, names.size());
		assertTrue(names.contains("abc"));
		assertTrue(names.contains("def"));
		assertTrue(names.contains("dd"));
		assertTrue(names.contains("greetings"));

		assertEquals(Integer.valueOf(1), attributes.getAttribute("abc"));
		assertEquals(IntegerNumberRange.between(4, 8), attributes.getAttribute("def"));
		assertEquals(new BigDecimal("1.123"), attributes.getAttribute("dd"));
		assertEquals("Hello", attributes.getAttribute("greetings", Locale.ENGLISH));
		assertEquals("Tschüss", attributes.getAttribute("greetings", Locale.GERMAN));
		assertNull(attributes.getAttribute("greetings", Locale.FRENCH));
	}

	@Test
	void shouldReportErrorOnAmbiguousAttributeDefinition() {
		assertThrows(
			IllegalArgumentException.class,
			() -> build(
				builder()
					.setAttribute("greetings", Locale.ENGLISH, "Hello")
					.setAttribute("greetings", Locale.GERMAN, 1)
			)
		);
	}

}
