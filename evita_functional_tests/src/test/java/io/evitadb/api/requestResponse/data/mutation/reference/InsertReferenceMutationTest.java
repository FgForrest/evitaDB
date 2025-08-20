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
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.schema.Cardinality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link InsertReferenceMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class InsertReferenceMutationTest extends AbstractMutationTest {

	@Test
	void shouldInsertNewReference() {
		final InsertReferenceMutation mutation = new InsertReferenceMutation(
			new ReferenceKey("brand", 5),
			Cardinality.ZERO_OR_ONE,
			"brand"
		);

		final ReferenceContract reference = mutation.mutateLocal(this.productSchema, null);
		assertNotNull(reference);
		assertEquals(1, reference.version());
		assertEquals("brand", reference.getReferenceName());
		assertEquals(5, reference.getReferencedPrimaryKey());
		assertNull(reference.getGroup().orElse(null));
		assertFalse(reference.dropped());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new InsertReferenceMutation(
				new ReferenceKey("brand", 5),
				Cardinality.ZERO_OR_ONE,
				"brand"
			).getSkipToken(this.catalogSchema, this.productSchema),
			new InsertReferenceMutation(
				new ReferenceKey("brand", 10),
				Cardinality.ZERO_OR_ONE,
				"brand"
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new InsertReferenceMutation(
				new ReferenceKey("brand", 5),
				Cardinality.ZERO_OR_ONE,
				"brand"
			).getSkipToken(this.catalogSchema, this.productSchema),
			new InsertReferenceMutation(
				new ReferenceKey("category", 5),
				Cardinality.ZERO_OR_ONE,
				"brand"
			).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
