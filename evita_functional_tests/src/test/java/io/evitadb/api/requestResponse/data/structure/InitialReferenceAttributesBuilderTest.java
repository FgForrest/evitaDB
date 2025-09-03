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

import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for InitialReferenceAttributesBuilder built on top of shared InitialAttributesBuilderTest.
 */
class InitialReferenceAttributesBuilderTest extends InitialAttributesBuilderTest {
	@Override
	protected InitialAttributesBuilder<?, ?> builder() {
		return new InitialReferenceAttributesBuilder(
			EntitySchema._internalBuild("whatever"),
			ReferencesBuilder.createImplicitSchema(PRODUCT_SCHEMA, "brand", "brand", Cardinality.ZERO_OR_MORE, null)
		);
	}

	@Override
	protected Attributes<?> build(InitialAttributesBuilder<?, ?> builder) {
		return ((InitialReferenceAttributesBuilder) builder).build();
	}

	@Test
	void shouldDefineAttributeTypesAlongTheWay() {
		final ReferenceAttributes attributes = ((InitialReferenceAttributesBuilder) builder()
			.setAttribute("abc", 1)
			.setAttribute("def", io.evitadb.dataType.IntegerNumberRange.between(4, 8))
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
			AttributeSchema._internalBuild("abc", Integer.class, false),
			attributes.getAttributeSchema("abc").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("def", io.evitadb.dataType.IntegerNumberRange.class, false),
			attributes.getAttributeSchema("def").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("dd", BigDecimal.class, false),
			attributes.getAttributeSchema("dd").orElse(null)
		);
		assertEquals(
			AttributeSchema._internalBuild("greetings", String.class, true),
			attributes.getAttributeSchema("greetings").orElse(null)
		);
	}
}