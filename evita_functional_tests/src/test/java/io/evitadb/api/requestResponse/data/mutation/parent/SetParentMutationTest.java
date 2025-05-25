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

import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link SetParentMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class SetParentMutationTest extends AbstractMutationTest {

	@Test
	void shouldCreateNewHierarchicalPlacement() {
		final SetParentMutation mutation = new SetParentMutation(2);
		final OptionalInt newParent = mutation.mutateLocal(this.productSchema, OptionalInt.empty());

		assertNotNull(newParent);
		assertTrue(newParent.isPresent());
		assertEquals(2, newParent.getAsInt());
	}

	@Test
	void shouldUpdateExistingHierarchicalPlacement() {
		final SetParentMutation mutation = new SetParentMutation(6);
		final OptionalInt newParent = mutation.mutateLocal(
			this.productSchema,
			OptionalInt.of(2)
		);

		assertNotNull(newParent);
		assertTrue(newParent.isPresent());
		assertEquals(6, newParent.getAsInt());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new SetParentMutation(7).getSkipToken(this.catalogSchema, this.productSchema),
			new SetParentMutation(4).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
