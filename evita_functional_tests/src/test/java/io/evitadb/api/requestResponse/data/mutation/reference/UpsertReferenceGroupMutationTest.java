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

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * This test verifies contract of {@link SetReferenceGroupMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class UpsertReferenceGroupMutationTest extends AbstractMutationTest {

	@Test
	void shouldSetReferenceGroup() {
		final SetReferenceGroupMutation mutation = new SetReferenceGroupMutation(
			"brand", 5,
			"europe", 2
		);
		final EntitySchemaBuilder productSchemaBuilder = new EntitySchemaDecorator(() -> this.catalogSchema, this.productSchema).openForWrite();
		mutation.verifyOrEvolveSchema(this.catalogSchema.openForWrite(), productSchemaBuilder);
		assertEquals("europe", productSchemaBuilder.getReference("brand").orElseThrow().getReferencedGroupType());
		final ReferenceContract reference = mutation.mutateLocal(
			this.productSchema,
			new Reference(
				this.productSchema,
				ReferencesBuilder.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, null),
				new ReferenceKey("brand", 5),
				null
			)
		);

		assertNotNull(reference);
		assertEquals(2, reference.version());
		assertEquals("brand", reference.getReferenceName());
		assertEquals("brand", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());
		assertFalse(reference.dropped());

		final GroupEntityReference groupEntityReference = reference.getGroup().orElseThrow();
		assertEquals("europe", groupEntityReference.getType());
		assertEquals(2, groupEntityReference.getPrimaryKey());
		assertEquals(1, groupEntityReference.version());
		assertFalse(groupEntityReference.dropped());
	}

	@Test
	void shouldOverwriteExistingReferenceGroup() {
		final SetReferenceGroupMutation mutation = new SetReferenceGroupMutation(
			"brand", 5,
			"europe", 2
		);
		final GroupEntityReference group = new GroupEntityReference("brand", 78, 1, false);
		final ReferenceContract reference = mutation.mutateLocal(
			this.productSchema,
			new Reference(
				this.productSchema,
				ReferencesBuilder.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, group),
				new ReferenceKey("brand", 5),
				group
			)
		);

		assertNotNull(reference);
		assertEquals(2, reference.version());
		assertEquals("brand", reference.getReferenceName());
		assertEquals("brand", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());
		assertFalse(reference.dropped());

		final GroupEntityReference theGroup = reference.getGroup().orElseThrow();
		assertEquals("europe", theGroup.getType());
		assertEquals(2, theGroup.getPrimaryKey());
		assertEquals(2, theGroup.version());
		assertFalse(theGroup.dropped());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new SetReferenceGroupMutation(
				"brand", 5,
				"europe", 2
			).getSkipToken(this.catalogSchema, this.productSchema),
			new SetReferenceGroupMutation(
				"brand", 10,
				"europe", 8
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new SetReferenceGroupMutation(
				"brand", 5,
				"europe", 2
			).getSkipToken(this.catalogSchema, this.productSchema),
			new SetReferenceGroupMutation(
				"brand", 10,
				"asia", 2
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
