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

package io.evitadb.api.requestResponse.data.mutation.parent;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link RemoveParentMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RemoveParentMutationTest extends AbstractMutationTest {

	@Test
	void shouldRemoveExistingParent() {
		final RemoveParentMutation mutation = new RemoveParentMutation();
		final OptionalInt hierarchyPlacement = mutation.mutateLocal(
			this.productSchema,
			OptionalInt.of(10)
		);
		assertNotNull(hierarchyPlacement);
		assertTrue(hierarchyPlacement.isEmpty());
	}

	@Test
	void shouldFailToRemoveNonexistingHierarchicalPlacement() {
		final RemoveParentMutation mutation = new RemoveParentMutation();
		assertThrows(InvalidMutationException.class, () -> mutation.mutateLocal(this.productSchema, null));
	}

	@Test
	void shouldFailToRemoveNonexistingPriceWhenAcceptingEmptyObject() {
		final RemoveParentMutation mutation = new RemoveParentMutation();
		assertThrows(
			InvalidMutationException.class,
			() -> mutation.mutateLocal(
				this.productSchema,
				OptionalInt.empty()
			)
		);
	}
}
