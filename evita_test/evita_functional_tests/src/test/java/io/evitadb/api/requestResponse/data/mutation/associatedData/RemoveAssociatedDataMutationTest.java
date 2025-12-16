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

package io.evitadb.api.requestResponse.data.mutation.associatedData;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link RemoveAssociatedDataMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RemoveAssociatedDataMutationTest extends AbstractMutationTest {

	@Test
	void shouldRemoveAssociatedData() {
		final RemoveAssociatedDataMutation mutation = new RemoveAssociatedDataMutation(new AssociatedDataKey("a"));
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, new AssociatedDataValue(new AssociatedDataKey("a"), (byte) 3));
		assertTrue(newValue.dropped());
		assertFalse(newValue.exists());
		assertEquals((byte) 3, newValue.value());
		assertEquals(2L, newValue.version());
	}

	@Test
	void shouldFailToRemoveNonexistingAssociatedData() {
		final RemoveAssociatedDataMutation mutation = new RemoveAssociatedDataMutation(new AssociatedDataKey("a"));
		assertThrows(InvalidMutationException.class, () -> mutation.mutateLocal(this.productSchema, null));
	}

	@Test
	void shouldFailToRemoveNonexistingAssociatedDataWhenAcceptingDroppedObject() {
		final RemoveAssociatedDataMutation mutation = new RemoveAssociatedDataMutation(new AssociatedDataKey("a"));
		assertThrows(
			InvalidMutationException.class,
			() -> mutation.mutateLocal(
				this.productSchema,
				new AssociatedDataValue(2, new AssociatedDataKey("a"), 3, true)
			)
		);
	}

}
