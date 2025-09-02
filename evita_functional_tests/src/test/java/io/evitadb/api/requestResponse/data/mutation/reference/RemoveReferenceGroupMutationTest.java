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
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link RemoveReferenceGroupMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RemoveReferenceGroupMutationTest extends AbstractMutationTest {

	@Test
	void shouldRemoveExistingReferenceGroup() {
		final RemoveReferenceGroupMutation mutation = new RemoveReferenceGroupMutation(
			new ReferenceKey("brand", 5)
		);
		final GroupEntityReference group = new GroupEntityReference("europe", 2, 1, false);
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
		assertTrue(theGroup.dropped());
	}

	@Test
	void shouldFailToRemoveNonexistingReferenceGroup() {
		final RemoveReferenceGroupMutation mutation = new RemoveReferenceGroupMutation(
			new ReferenceKey("brand", 5)
		);
		assertThrows(InvalidMutationException.class, () -> mutation.mutateLocal(this.productSchema, null));
	}

	@Test
	void shouldFailToRemoveNonexistingReferenceGroupWhenAcceptingDroppedObject() {
		final RemoveReferenceGroupMutation mutation = new RemoveReferenceGroupMutation(
			new ReferenceKey("brand", 5)
		);
		assertThrows(
			InvalidMutationException.class,
			() -> {
				final GroupEntityReference group = new GroupEntityReference("europe", 2, 2, true);
				mutation.mutateLocal(
					this.productSchema,
					new Reference(
						this.productSchema,
						ReferencesBuilder.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, group),
						2,
						new ReferenceKey("brand", 5),
						group,
						false
					)
				);
			}
		);
	}

}
