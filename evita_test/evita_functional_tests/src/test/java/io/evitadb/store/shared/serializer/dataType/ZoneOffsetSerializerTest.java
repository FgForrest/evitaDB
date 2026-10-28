/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneOffset;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the fixed-width zone-offset encoding and reuse of deserialized offsets from the cache.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ZoneOffsetSerializer binary round trip")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(DATA_TYPE)
class ZoneOffsetSerializerTest {
	/**
	 * Covers UTC, uncommon second-based offsets, both signs and the supported bounds.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, -1, 3661, -20723, 64800, -64800})
	@DisplayName("Should preserve offset seconds and reuse the cached value")
	void shouldPreserveOffsetAndReuseCachedValue(int totalSeconds) {
		final ZoneOffset expected = ZoneOffset.ofTotalSeconds(totalSeconds);
		try (final Output output = new Output(ZoneOffsetSerializer.RECORD_SIZE)) {
			ZoneOffsetSerializer.write(output, expected);
			assertEquals(ZoneOffsetSerializer.RECORD_SIZE, output.position());

			try (final Input input = new Input(output.toBytes())) {
				final ZoneOffset first = ZoneOffsetSerializer.read(input);
				assertEquals(expected, first);
				input.setPosition(0);
				assertSame(first, ZoneOffsetSerializer.read(input));
			}
		}
	}
}
