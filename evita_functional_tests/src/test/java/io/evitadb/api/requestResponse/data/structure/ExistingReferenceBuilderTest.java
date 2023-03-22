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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link ExistingReferenceBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ExistingReferenceBuilderTest extends AbstractBuilderTest {
	private ReferenceContract initialReference;

	@BeforeEach
	void setUp() {
		initialReference = new InitialReferenceBuilder(
			PRODUCT_SCHEMA,
			"brand",
			5,
			Cardinality.ZERO_OR_ONE,
			"brand",
			(entityType, attributeName) -> false
		)
			.setAttribute("brandPriority", 154L)
			.setAttribute("country", Locale.ENGLISH, "Great Britain")
			.setAttribute("country", Locale.CANADA, "Canada")
			.setGroup("group", 78)
			.build();
	}

	@Test
	void shouldModifyAttributes() {
		final ReferenceBuilder builder = new ExistingReferenceBuilder(initialReference, PRODUCT_SCHEMA, (entityType, attributeName) -> false)
			.setAttribute("brandPriority", 155L)
			.removeAttribute("country", Locale.ENGLISH)
			.setAttribute("newAttribute", "Hi");

		assertEquals(155L, (Long) builder.getAttribute("brandPriority"));
		assertEquals("Canada", builder.getAttribute("country", Locale.CANADA));
		assertNull(builder.getAttribute("country", Locale.ENGLISH));
		assertEquals("Hi", builder.getAttribute("newAttribute"));

		final ReferenceContract reference = builder.build();

		assertEquals(155L, (Long) reference.getAttribute("brandPriority"));
		assertEquals("Canada", reference.getAttribute("country", Locale.CANADA));
		assertEquals("Great Britain", reference.getAttribute("country", Locale.ENGLISH));
		assertEquals("Hi", reference.getAttribute("newAttribute"));

		final AttributeValue gbCountry = reference.getAttributeValue("country", Locale.ENGLISH).orElseThrow();
		assertTrue(gbCountry.isDropped());
	}

	@Test
	void shouldSkipMutationsThatMeansNoChange() {
		final ReferenceBuilder builder = new ExistingReferenceBuilder(initialReference, PRODUCT_SCHEMA, (entityType, attributeName) -> false)
			.setAttribute("brandPriority", 154L)
			.setAttribute("country", Locale.ENGLISH, "Changed name")
			.setAttribute("country", Locale.ENGLISH, "Great Britain")
			.setGroup("group", 78);

		assertEquals(0, builder.buildChangeSet().count());
	}

	@Test
	void shouldModifyReferenceGroup() {
		final ReferenceBuilder builder = new ExistingReferenceBuilder(initialReference, PRODUCT_SCHEMA, (entityType, attributeName) -> false)
			.setGroup("newGroup", 77);

		assertEquals(
			new GroupEntityReference("newGroup", 77, 2, false),
			builder.getGroup().orElse(null)
		);

		final ReferenceContract reference = builder.build();

		assertEquals(
			new GroupEntityReference("newGroup", 77, 2, false),
			reference.getGroup().orElse(null)
		);
	}

	@Test
	void shouldRemoveReferenceGroup() {
		final ReferenceBuilder builder = new ExistingReferenceBuilder(initialReference, PRODUCT_SCHEMA, (entityType, attributeName) -> false)
			.removeGroup();

		assertTrue(builder.getGroup().isEmpty());

		final ReferenceContract reference = builder.build();

		assertTrue(reference.getGroup().isEmpty());
	}

	@Test
	void shouldReturnOriginalReferenceInstanceWhenNothingHasChanged() {
		final ReferenceContract reference = new ExistingReferenceBuilder(initialReference, PRODUCT_SCHEMA, (entityType, attributeName) -> false)
			.setAttribute("brandPriority", 154L)
			.setAttribute("country", Locale.ENGLISH, "Great Britain")
			.setAttribute("country", Locale.CANADA, "Canada")
			.setGroup("group", 78)
			.build();

		assertSame(initialReference, reference);
	}
}
