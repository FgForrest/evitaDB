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

package io.evitadb.dataType;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link StripList} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class StripListTest {

	@Test
	void shouldRecognizeFirstPage() {
		assertTrue(new StripList<>(0, 20, 19).isFirst());
		assertTrue(new StripList<>(0, 20, 19).isSinglePage());

		assertFalse(new StripList<>(2, 20, 45).isFirst());
		assertFalse(new StripList<>(0, 20, 45).isSinglePage());
	}

	@Test
	void shouldRecognizeLastPage() {
		assertTrue(new StripList<>(0, 20, 19).isLast());
		assertTrue(new StripList<>(20, 20, 35).isLast());
		assertFalse(new StripList<>(20, 20, 45).isLast());
	}

	@Test
	void shouldInitializeWithDataAndIterateOver() {
		final DataChunk<Integer> page = new StripList<>(0, 5, 34, Arrays.asList(1, 2, 3, 4, 5));
		assertFalse(page.isEmpty());
		int i = 0;
		for (Integer recId : page) {
			assertEquals(++i, recId);
		}
	}

}
