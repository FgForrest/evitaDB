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

package io.evitadb.core.query.algebra.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies immutable bucket values, type fidelity and content-sensitive admission weight.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Bucket value identities")
@Tag(ENGINE)
@Tag(CACHE)
class ValueListTest {
	/** Checks the complete supported immutable value domain and normalized decimals. */
	@Test
	@DisplayName("Preserves immutable value types and normalizes decimals")
	void shouldPreserveSupportedValues() {
		final Object[] source = {"text", (byte) 1, (short) 2, 3, 4L, true, 'x', OffsetDateTime.MIN,
			LocalDateTime.MIN, LocalDate.MIN, LocalTime.MIN, Locale.ENGLISH, Currency.getInstance("EUR"),
			new UUID(1, 2), new BigDecimal("1.00")};
		final ValueList values = new ValueList(source);
		source[0] = "changed";
		final Object[] copy = source.clone();
		copy[0] = "text";
		copy[14] = BigDecimal.ONE;
		final ValueList equal = new ValueList(copy);
		assertEquals(values, equal);
		assertEquals(values.hashCode(), equal.hashCode());
		assertEquals(values.hash64(), equal.hash64());
		assertEquals(15, values.size());
		assertFalse(values.isOpaque());
		assertFalse(values.isQueryLocal());
		assertNotEquals(new ValueList(1), new ValueList(1L));
		assertNotEquals(new ValueList(1, 2), new ValueList(2, 1));
		assertNotEquals(new ValueList(1).hash64(), new ValueList(1L).hash64());
	}

	/** Checks foreign values, count limits and retained text size. */
	@Test
	@DisplayName("Rejects foreign values and charges retained text")
	void shouldClassifyAdmissionByContent() {
		assertTrue(new ValueList(new Object()).isOpaque());
		assertTrue(new ValueList((Object) null).isQueryLocal());
		assertEquals(11, new ValueList("a".repeat(160)).weight());
		final Object[] many = new Object[TokenList.CAP + 1];
		Arrays.fill(many, "bucket");
		final ValueList oversized = new ValueList(many);
		assertTrue(oversized.isQueryLocal());
		assertFalse(oversized.isOpaque());
		assertEquals(oversized, new ValueList(many));
		assertTrue(oversized.weight() > new ValueList("bucket").weight());
	}
}
