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

package io.evitadb.api.requestResponse.data.mutation.reference;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
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
			this.productSchema,
			new Reference(
				this.productSchema,
				ReferencesBuilder.createImplicitSchema("category", "category", Cardinality.ZERO_OR_MORE, null),
				new ReferenceKey("category", 5),
				null
			)
		);

		assertNotNull(reference);
		assertEquals(2, reference.version());
		assertEquals("category", reference.getReferenceName());
		assertEquals("category", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());

		final Collection<AttributeValue> attributeValues = reference.getAttributeValues();
		assertEquals(1, attributeValues.size());

		final AttributeValue attributeValue = attributeValues.iterator().next();
		assertEquals(1, attributeValue.version());
		assertEquals("categoryPriority", attributeValue.key().attributeName());
		assertEquals(145L, attributeValue.value());

		assertNull(reference.getGroup().orElse(null));
		assertFalse(reference.dropped());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(this.catalogSchema, this.productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abc"), "C")
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "B")
			).getSkipToken(this.catalogSchema, this.productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "C")
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(this.catalogSchema, this.productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abe"), "C")
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(this.catalogSchema, this.productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("product", 5),
				new UpsertAttributeMutation(new AttributeKey("abc"), "B")
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 5),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "B")
			).getSkipToken(this.catalogSchema, this.productSchema),
			new ReferenceAttributeMutation(
				new ReferenceKey("category", 10),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.GERMAN), "C")
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
