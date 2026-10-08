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

package io.evitadb.store.catalog;

import io.evitadb.store.catalog.Migration_2026_3.UniqueKeyRekeying;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure transform the v6→v7 migration re-keys a persisted standalone unique index with
 * ({@link Migration_2026_3#rekeyUniqueKeys}): every value is mapped onto the canonical form the engine now persists,
 * a part already in that form is left alone, and two values naming one key are folded only when they provably are
 * the same entry - otherwise they are reported as a collision.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — standalone unique index re-key (v6→v7)")
@Tag(STORAGE)
@Tag(INDEXING)
@SuppressWarnings("removal") // the migration interface is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_UniqueRekeyTest {
	private static final String NFC = Normalizer.normalize("čaj", Normalizer.Form.NFC);
	private static final String NFD = Normalizer.normalize("čaj", Normalizer.Form.NFD);
	private static final OffsetDateTime NOON_UTC = OffsetDateTime.of(2026, 5, 20, 12, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime NOON_AT_PLUS_TWO = NOON_UTC.withOffsetSameInstant(ZoneOffset.ofHours(2));

	@Test
	@DisplayName("Should leave a part whose values are all canonical alone")
	void shouldLeaveCanonicalPartAlone() {
		assertNull(rekey(String.class, 0, new Serializable[]{NFD, "tea"}, new long[]{1, 2}, false));
		assertNull(rekey(OffsetDateTime.class, 0, new Serializable[]{NOON_UTC}, new long[]{1}, false));
		assertNull(rekey(BigDecimal.class, 2, new Serializable[]{new BigDecimal("1.24")}, new long[]{1}, false));
		assertNull(rekey(LocalTime.class, 0, new Serializable[]{LocalTime.of(10, 0)}, new long[]{1}, false));
		assertNull(rekey(Currency.class, 0, new Serializable[]{Currency.getInstance("CZK")}, new long[]{1}, false));
		assertNull(rekey(Locale.class, 0, new Serializable[]{Locale.forLanguageTag("cs")}, new long[]{1}, false));
	}

	@Test
	@DisplayName("Should rewrite each type into the declared value of its key")
	void shouldRewriteIntoDeclaredValueOfKey() {
		assertArrayEquals(
			new Serializable[]{NFD}, rekeyed(String.class, 0, new Serializable[]{NFC}).values()
		);
		assertArrayEquals(
			new Serializable[]{NOON_UTC}, rekeyed(OffsetDateTime.class, 0, new Serializable[]{NOON_AT_PLUS_TWO}).values()
		);
		assertArrayEquals(
			new Serializable[]{LocalDateTime.of(2026, 5, 20, 12, 0, 0, 123_000_000)},
			rekeyed(LocalDateTime.class, 0, new Serializable[]{LocalDateTime.of(2026, 5, 20, 12, 0, 0, 123_456_789)})
				.values()
		);
		assertArrayEquals(
			new Serializable[]{new BigDecimal("1.24")},
			rekeyed(BigDecimal.class, 2, new Serializable[]{new BigDecimal("1.235")}).values()
		);
		final Serializable rescaled = rekeyed(BigDecimal.class, 2, new Serializable[]{new BigDecimal("1.0")}).values()[0];
		assertEquals(2, ((BigDecimal) rescaled).scale(), "the canonical value carries the indexed scale");
	}

	@Test
	@DisplayName("Should report two owners of one key as a collision, whatever the type")
	void shouldReportTwoOwnersOfOneKey() {
		assertCollision(String.class, 0, NFC, NFD);
		assertCollision(OffsetDateTime.class, 0, NOON_UTC, NOON_AT_PLUS_TWO);
		assertCollision(BigDecimal.class, 2, new BigDecimal("1.235"), new BigDecimal("1.244"));
		assertCollision(BigDecimal.class, 2, new BigDecimal("1.0"), new BigDecimal("1.00"));
	}

	@Test
	@DisplayName("Should fold two spellings of one global entry, but report them for an owner index")
	void shouldFoldOnlyWhenTheOwnerIdentifiesTheEntry() {
		// a global payload packs entity type, primary key and locale: an equal payload is provably the same entry
		final UniqueKeyRekeying global = rekeyed(
			String.class, 0, new Serializable[]{NFC, NFD}, new long[]{7, 7}, true
		);
		assertArrayEquals(new Serializable[]{NFD}, global.values());
		assertTrue(global.collisions().isEmpty());
		// an owner index stores the record id alone - the same record may hold the value under two locales
		final UniqueKeyRekeying owner = rekeyed(
			String.class, 0, new Serializable[]{NFC, NFD}, new long[]{7, 7}, false
		);
		assertEquals(1, owner.collisions().size());
	}

	/**
	 * Asserts that `first` and `second`, held by two different owners, collide.
	 */
	private static void assertCollision(
		Class<?> type, int indexedDecimalPlaces, Serializable first, Serializable second
	) {
		final UniqueKeyRekeying rekeying = rekeyed(
			type, indexedDecimalPlaces, new Serializable[]{first, second}, new long[]{1, 2}, true
		);
		assertEquals(1, rekeying.collisions().size(), type.getSimpleName() + ": " + first + " vs " + second);
		assertArrayEquals(new int[]{0, 1}, rekeying.collisions().get(0));
	}

	private static UniqueKeyRekeying rekeyed(Class<?> type, int indexedDecimalPlaces, Serializable[] values) {
		final long[] owners = new long[values.length];
		for (int i = 0; i < owners.length; i++) {
			owners[i] = i + 1;
		}
		return rekeyed(type, indexedDecimalPlaces, values, owners, false);
	}

	private static UniqueKeyRekeying rekeyed(
		Class<?> type, int indexedDecimalPlaces, Serializable[] values, long[] owners, boolean ownerIdentifiesEntry
	) {
		final UniqueKeyRekeying rekeying = rekey(type, indexedDecimalPlaces, values, owners, ownerIdentifiesEntry);
		assertNotNull(rekeying, "the part must be rewritten");
		return rekeying;
	}

	private static UniqueKeyRekeying rekey(
		Class<?> type, int indexedDecimalPlaces, Serializable[] values, long[] owners, boolean ownerIdentifiesEntry
	) {
		return Migration_2026_3.rekeyUniqueKeys(
			values, owners, ownerIdentifiesEntry, Migration_2026_3.canonicalizerFor(type, indexedDecimalPlaces)
		);
	}

}
