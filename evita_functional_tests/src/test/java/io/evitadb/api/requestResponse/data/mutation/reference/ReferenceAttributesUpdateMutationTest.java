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

package io.evitadb.api.requestResponse.data.mutation.reference;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ReferenceAttributeMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ReferenceAttributesUpdateMutationTest extends AbstractMutationTest {

	@Test
	void shouldUpdateReferenceAttributes() {
		final ReferenceAttributeMutation mutation = new ReferenceAttributeMutation(
			new ReferenceKey("category", 5),
			new UpsertAttributeMutation(new AttributeKey("categoryPriority"), 145L)
		);

		final ReferenceContract reference = mutation.mutateLocal(
			productSchema,
			new Reference(
				productSchema,
				"category",
				5,
				"category", Cardinality.ZERO_OR_MORE,
				null
			)
		);

		assertNotNull(reference);
		assertEquals(2, reference.getVersion());
		assertEquals("category", reference.getReferenceName());
		assertEquals("category", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());

		final Collection<AttributeValue> attributeValues = reference.getAttributeValues();
		assertEquals(1, attributeValues.size());

		final AttributeValue attributeValue = attributeValues.iterator().next();
		assertEquals(1, attributeValue.getVersion());
		assertEquals("categoryPriority", attributeValue.getKey().getAttributeName());
		assertEquals(145L, attributeValue.getValue());

		assertNull(reference.getGroup().orElse(null));
		assertFalse(reference.isDropped());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(catalogSchema, productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abc"), "C")
			).getSkipToken(catalogSchema, productSchema)
		);
		assertEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "B")
			).getSkipToken(catalogSchema, productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "C")
			).getSkipToken(catalogSchema, productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(catalogSchema, productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abe"), "C")
			).getSkipToken(catalogSchema, productSchema)
		);
		assertNotEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(catalogSchema, productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("product", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(catalogSchema, productSchema)
		);
		assertNotEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "B")
			).getSkipToken(catalogSchema, productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.GERMAN), "C")
			).getSkipToken(catalogSchema, productSchema)
		);
	}

}