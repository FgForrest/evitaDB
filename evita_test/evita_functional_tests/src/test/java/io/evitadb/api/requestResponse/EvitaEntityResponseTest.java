/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serial;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvitaEntityResponse} verifying subclass-specific
 * behavior including construction and primary key storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EvitaEntityResponse")
class EvitaEntityResponseTest {

	/**
	 * Verifies construction without extra results stores
	 * primary keys correctly.
	 */
	@Test
	@DisplayName("stores primary keys on construction")
	void shouldStorePrimaryKeysOnConstruction() {
		final int[] pks = {1, 2, 3};
		final EvitaEntityResponse<SealedEntity> response =
			new EvitaEntityResponse<>(
				query(collection("brand")),
				PaginatedList.emptyList(),
				pks
			);

		assertArrayEquals(pks, response.getPrimaryKeys());
	}

	/**
	 * Verifies construction with extra results stores
	 * both primary keys and extras correctly.
	 */
	@Test
	@DisplayName(
		"stores primary keys and extras via constructor"
	)
	void shouldStorePrimaryKeysAndExtras() {
		final int[] pks = {10, 20};
		final MockExtra extra = new MockExtra("val");

		final EvitaEntityResponse<SealedEntity> response =
			new EvitaEntityResponse<>(
				query(collection("brand")),
				PaginatedList.emptyList(),
				pks,
				extra
			);

		assertArrayEquals(pks, response.getPrimaryKeys());
		assertNotNull(
			response.getExtraResult(MockExtra.class)
		);
		assertEquals(
			"val",
			response.getExtraResult(MockExtra.class).data()
		);
	}

	/**
	 * Verifies empty primary keys array.
	 */
	@Test
	@DisplayName("handles empty primary keys")
	void shouldHandleEmptyPrimaryKeys() {
		final EvitaEntityResponse<SealedEntity> response =
			new EvitaEntityResponse<>(
				query(collection("brand")),
				PaginatedList.emptyList(),
				ArrayUtils.EMPTY_INT_ARRAY
			);

		assertEquals(
			0, response.getPrimaryKeys().length
		);
	}

	/**
	 * Mock extra result for testing.
	 */
	private record MockExtra(
		String data
	) implements EvitaResponseExtraResult {
		@Serial
		private static final long serialVersionUID =
			133944519712518780L;
	}
}
