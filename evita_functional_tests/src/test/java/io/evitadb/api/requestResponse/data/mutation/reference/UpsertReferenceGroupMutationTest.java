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

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
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
		final EntitySchemaBuilder productSchemaBuilder = new EntitySchemaDecorator(() -> catalogSchema, productSchema).openForWrite();
		mutation.verifyOrEvolveSchema(catalogSchema.openForWrite(), productSchemaBuilder);
		assertEquals("europe", productSchemaBuilder.getReference("brand").orElseThrow().getReferencedGroupType());
		final ReferenceContract reference = mutation.mutateLocal(
			productSchema,
			new Reference(
				productSchema,
				"brand",
				5,
				"brand", Cardinality.ZERO_OR_ONE,
				null
			)
		);

		assertNotNull(reference);
		assertEquals(2, reference.getVersion());
		assertEquals("brand", reference.getReferenceName());
		assertEquals("brand", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());
		assertFalse(reference.isDropped());

		final GroupEntityReference groupEntityReference = reference.getGroup().orElseThrow();
		assertEquals("europe", groupEntityReference.getType());
		assertEquals(2, groupEntityReference.getPrimaryKey());
		assertEquals(1, groupEntityReference.getVersion());
		assertFalse(groupEntityReference.isDropped());
	}

	@Test
	void shouldOverwriteExistingReferenceGroup() {
		final SetReferenceGroupMutation mutation = new SetReferenceGroupMutation(
			"brand", 5,
			"europe", 2
		);
		final ReferenceContract reference = mutation.mutateLocal(
			productSchema,
			new Reference(
				productSchema,
				"brand",
				5,
				"brand", Cardinality.ZERO_OR_ONE,
				new GroupEntityReference("brand", 78, 1, false)
			)
		);

		assertNotNull(reference);
		assertEquals(2, reference.getVersion());
		assertEquals("brand", reference.getReferenceName());
		assertEquals("brand", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());
		assertFalse(reference.isDropped());

		final GroupEntityReference theGroup = reference.getGroup().orElseThrow();
		assertEquals("europe", theGroup.getType());
		assertEquals(2, theGroup.getPrimaryKey());
		assertEquals(2, theGroup.getVersion());
		assertFalse(theGroup.isDropped());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new SetReferenceGroupMutation(
				"brand", 5,
				"europe", 2
			).getSkipToken(catalogSchema, productSchema),
			new SetReferenceGroupMutation(
				"brand", 10,
				"europe", 8
			).getSkipToken(catalogSchema, productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new SetReferenceGroupMutation(
				"brand", 5,
				"europe", 2
			).getSkipToken(catalogSchema, productSchema),
			new SetReferenceGroupMutation(
				"brand", 10,
				"asia", 2
			).getSkipToken(catalogSchema, productSchema)
		);
	}

}