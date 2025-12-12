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

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * This test verifies contract of {@link UpsertAssociatedDataMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class UpsertAssociatedDataMutationTest extends AbstractMutationTest {

	@Test
	void shouldCreateNewAssociatedData() {
		final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(new AssociatedDataKey("a"), (byte) 5);
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, null);
		assertEquals((byte) 5, newValue.value());
		assertEquals(1L, newValue.version());
	}

	@Test
	void shouldIncrementVersionByUpdatingAssociatedData() {
		final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(new AssociatedDataKey("a"), (byte) 5);
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, new AssociatedDataValue(new AssociatedDataKey("a"), (byte) 3));
		assertEquals((byte) 5, newValue.value());
		assertEquals(2L, newValue.version());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abe"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.GERMAN), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
