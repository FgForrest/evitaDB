/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.utils;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies the functionality of the {@link RandomUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class RandomUtilsTest {

	@Test
	void shouldGenerateSameRowOfRandomResults() {
		final byte[] frozenRandom = RandomUtils.getFrozenRandom();
		final int[] randomArray1 = Stream.generate(() -> RandomUtils.getRandom(frozenRandom)).limit(1000).mapToInt(Random::nextInt).toArray();
		final int[] randomArray2 = Stream.generate(() -> RandomUtils.getRandom(frozenRandom)).limit(1000).mapToInt(Random::nextInt).toArray();
		assertArrayEquals(randomArray1, randomArray2);
	}

}
