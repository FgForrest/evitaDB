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

package io.evitadb.index.fulltext;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the fulltext dictionary key encoding: round-trips, the fixed width, the (field, term) order the prefix
 * walks depend on, and the refusals at the edges of the encodable range.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Fulltext term keys")
class FulltextTermKeysTest {

	@Test
	@DisplayName("A key round-trips its field id and term")
	void shouldRoundTripFieldIdAndTerm() {
		for (int fieldId : new int[]{0, 1, 9, 10, 15, 16, 255, 4096, FulltextTermKeys.MAX_FIELD_ID}) {
			for (String term : new String[]{"", "a", "praha", "žluťoučký", "0042", "kůň-1"}) {
				final String key = FulltextTermKeys.encode(fieldId, term);
				assertEquals(fieldId, FulltextTermKeys.fieldIdOf(key), key);
				assertEquals(term, FulltextTermKeys.termOf(key), key);
				assertEquals(FulltextTermKeys.FIELD_PREFIX_WIDTH + term.length(), key.length());
			}
		}
	}

	@Test
	@DisplayName("The prefix is fixed width, lower-case hexadecimal, and starts every key of the field")
	void shouldRenderFixedWidthLowerCaseHexadecimalPrefix() {
		assertEquals("0000", FulltextTermKeys.fieldPrefix(0));
		assertEquals("000a", FulltextTermKeys.fieldPrefix(10));
		assertEquals("00ff", FulltextTermKeys.fieldPrefix(255));
		assertEquals("ffff", FulltextTermKeys.fieldPrefix(FulltextTermKeys.MAX_FIELD_ID));
		assertTrue(FulltextTermKeys.belongsTo(FulltextTermKeys.encode(10, "term"), FulltextTermKeys.fieldPrefix(10)));
		assertFalse(FulltextTermKeys.belongsTo(FulltextTermKeys.encode(11, "term"), FulltextTermKeys.fieldPrefix(10)));
	}

	@Test
	@DisplayName("Natural string order of the keys is exactly (field id, term) order")
	void shouldOrderKeysByFieldThenTerm() {
		// field ids chosen to straddle the digit/letter boundary and a carry, where a careless encoding would break
		final int[] fieldIds = {0, 9, 10, 15, 16, 159, 160, 4095, 4096};
		final String[] terms = {"", "a", "aa", "b", "z", "á"};
		final List<String> expected = new ArrayList<>(fieldIds.length * terms.length);
		for (int fieldId : fieldIds) {
			for (String term : terms) {
				expected.add(FulltextTermKeys.encode(fieldId, term));
			}
		}
		final List<String> sorted = new ArrayList<>(expected);
		Collections.shuffle(sorted, new Random(42));
		Collections.sort(sorted);
		assertEquals(expected, sorted);
	}

	@Test
	@DisplayName("A field id outside the prefix's range is refused")
	void shouldRefuseFieldIdOutsideRange() {
		assertThrows(GenericEvitaInternalError.class, () -> FulltextTermKeys.fieldPrefix(-1));
		assertThrows(
			GenericEvitaInternalError.class, () -> FulltextTermKeys.encode(FulltextTermKeys.MAX_FIELD_ID + 1, "term")
		);
	}

	@Test
	@DisplayName("A key without a well-formed prefix is refused")
	void shouldRefuseMalformedKey() {
		assertThrows(GenericEvitaInternalError.class, () -> FulltextTermKeys.fieldIdOf("00a"));
		assertThrows(GenericEvitaInternalError.class, () -> FulltextTermKeys.termOf(""));
		assertThrows(GenericEvitaInternalError.class, () -> FulltextTermKeys.fieldIdOf("00A0term"));
		assertThrows(GenericEvitaInternalError.class, () -> FulltextTermKeys.fieldIdOf("00g0term"));
	}

}
