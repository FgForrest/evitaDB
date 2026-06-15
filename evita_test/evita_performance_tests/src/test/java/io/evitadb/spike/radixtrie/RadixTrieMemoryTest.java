/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.spike.radixtrie;

import org.junit.jupiter.api.Test;

/**
 * Thin JUnit entry point so the {@link RadixTrieMemorySpike} go/no-go measurement can be driven through the
 * whitelisted {@code mvn test} lifecycle (rather than a raw {@code java} invocation). Prints the footprint /
 * allocation comparison table to stdout. Not a behavioural assertion — it is the measurement harness.
 *
 * @author Claude (radix-trie memory spike), FG Forrest a.s. (c) 2026
 */
class RadixTrieMemoryTest {

	@Test
	void runMemorySpike() {
		// 50k distinct values keeps the measurement under a few seconds while staying representative.
		RadixTrieMemorySpike.main(new String[]{"50000"});
	}
}
