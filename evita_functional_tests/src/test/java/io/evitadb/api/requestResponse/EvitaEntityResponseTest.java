/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import org.junit.jupiter.api.Test;

import java.io.Serial;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * This test verifies contract of {@link EvitaEntityResponse} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EvitaEntityResponseTest {

	@Test
	void shouldPassExtraDataInResponse() {
		final EvitaEntityResponse<SealedEntity> response = new EvitaEntityResponse<>(
			query(collection("brand")),
			PaginatedList.emptyList()
		);

		response.addExtraResult(new MockEvitaResponseExtraResult("a"));
		assertNotNull(response.getExtraResult(MockEvitaResponseExtraResult.class));
		assertEquals("a", response.getExtraResult(MockEvitaResponseExtraResult.class).data());
		assertNull(response.getExtraResult(EvitaResponseExtraResult.class));
	}

	private record MockEvitaResponseExtraResult(String data) implements EvitaResponseExtraResult {
		@Serial private static final long serialVersionUID = 133944519712518780L;
	}
}
