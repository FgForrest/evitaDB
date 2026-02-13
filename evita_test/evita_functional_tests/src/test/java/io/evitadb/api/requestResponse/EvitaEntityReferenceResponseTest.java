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

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvitaEntityReferenceResponse} verifying
 * subclass-specific behavior including construction and
 * primary key storage.
 *
 * @author evitaDB
 */
@DisplayName("EvitaEntityReferenceResponse")
class EvitaEntityReferenceResponseTest {

	/**
	 * Verifies construction stores primary keys and data
	 * correctly.
	 */
	@Test
	@DisplayName("stores primary keys on construction")
	void shouldStorePrimaryKeysOnConstruction() {
		final int[] pks = {1, 2, 3};
		final EntityReference ref1 =
			new EntityReference("brand", 1);
		final EntityReference ref2 =
			new EntityReference("brand", 2);
		final EntityReference ref3 =
			new EntityReference("brand", 3);

		final PaginatedList<EntityReference> page =
			new PaginatedList<>(
				1, 1, 20, 3,
				List.of(ref1, ref2, ref3)
			);

		final EvitaEntityReferenceResponse response =
			new EvitaEntityReferenceResponse(
				query(collection("brand")),
				page,
				pks
			);

		assertArrayEquals(pks, response.getPrimaryKeys());
		assertEquals(3, response.getRecordData().size());
		assertEquals(
			"brand",
			response.getRecordData().get(0).type()
		);
	}

	/**
	 * Verifies construction with extra results.
	 */
	@Test
	@DisplayName(
		"stores primary keys and extras via constructor"
	)
	void shouldStorePrimaryKeysAndExtras() {
		final int[] pks = {10};
		final MockRefExtra extra = new MockRefExtra("v");

		final EvitaEntityReferenceResponse response =
			new EvitaEntityReferenceResponse(
				query(collection("brand")),
				PaginatedList.emptyList(),
				pks,
				extra
			);

		assertArrayEquals(pks, response.getPrimaryKeys());
		assertNotNull(
			response.getExtraResult(MockRefExtra.class)
		);
		assertEquals(
			"v",
			response.getExtraResult(
				MockRefExtra.class
			).data()
		);
	}

	/**
	 * Verifies empty primary keys array.
	 */
	@Test
	@DisplayName("handles empty primary keys")
	void shouldHandleEmptyPrimaryKeys() {
		final EvitaEntityReferenceResponse response =
			new EvitaEntityReferenceResponse(
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
	private record MockRefExtra(
		String data
	) implements EvitaResponseExtraResult {
		@Serial
		private static final long serialVersionUID =
			433944519712518783L;
	}
}
