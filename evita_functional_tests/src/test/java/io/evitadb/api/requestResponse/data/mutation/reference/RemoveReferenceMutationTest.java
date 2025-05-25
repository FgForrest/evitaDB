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

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link InsertReferenceMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RemoveReferenceMutationTest extends AbstractMutationTest {

	@Test
	void shouldRemoveReference() {
		final RemoveReferenceMutation mutation = new RemoveReferenceMutation(
			new ReferenceKey("brand", 5)
		);

		final ReferenceContract reference = mutation.mutateLocal(
			this.productSchema,
			new Reference(
				this.productSchema,
				"brand",
				5,
				"brand", Cardinality.ZERO_OR_ONE,
				new GroupEntityReference("europe", 2, 1, false)
			)
		);
		assertNotNull(reference);
		assertEquals(2, reference.version());
		assertEquals("brand", reference.getReferenceName());
		assertEquals("brand", reference.getReferencedEntityType());
		assertEquals(5, reference.getReferencedPrimaryKey());
		assertTrue(reference.dropped());

		final GroupEntityReference theGroup = reference.getGroup().orElseThrow();
		assertEquals("europe", theGroup.getType());
		assertEquals(2, theGroup.getPrimaryKey());
	}

	@Test
	void shouldFailToRemoveNonexistingReference() {
		final RemoveReferenceMutation mutation = new RemoveReferenceMutation(
			new ReferenceKey("brand", 5)
		);
		assertThrows(InvalidMutationException.class, () -> mutation.mutateLocal(this.productSchema, null));
	}

	@Test
	void shouldFailToRemoveNonexistingReferenceWhenAcceptingDroppedObject() {
		final RemoveReferenceMutation mutation = new RemoveReferenceMutation(
			new ReferenceKey("brand", 5)
		);
		assertThrows(
			InvalidMutationException.class,
			() -> mutation.mutateLocal(
				this.productSchema,
				new Reference(
					this.productSchema,
					2,
					"brand",
					5,
					"brand", Cardinality.ZERO_OR_ONE,
					new GroupEntityReference("europe", 2, 1, false),
					true
				)
			)
		);
	}

}
