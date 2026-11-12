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
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

import io.evitadb.core.query.algebra.attribute.ThresholdComparison;
import io.evitadb.core.query.algebra.attribute.ThresholdOperator;
import io.evitadb.core.query.algebra.attribute.ThresholdRange;
import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.BigInteger;
import io.evitadb.utils.CollectionUtils;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies content hashing, collision families and retained weight.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Identity accumulator")
@Tag(ENGINE)
@Tag(CACHE)
class KeyAccumulatorTest {
	/**
	 * Feeds both coordinates without using the record's generated hash.
	 *
	 * @param from first coordinate
	 * @param to second coordinate
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record Coordinates(int from, int to) implements IdentityRecord {
		/** Returns a synthetic record identifier. */
		@Override public long classId() { return 1; }
		/** Includes both integer components. */
		@Override public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.from);
			accumulator.add(this.to);
		}
	}
	/** Verifies the complete numeric collision family. */
	@Test
	@DisplayName("Separates 10000 coordinate pairs with one linear hash")
	void shouldSeparateLinearCollisionFamily() {
		final Set<Long> hashes = CollectionUtils.createHashSet(10_000);
		for (int i = 0; i < 10_000; i++) {
			final FormulaKey key = FormulaKey.of(new Coordinates(i, -31 * i), 1,
				FormulaKey.NO_CHILDREN, new Object());
			assertTrue(hashes.add(key.getHash64()));
		}
		assertEquals(10_000, hashes.size());
	}
	/** Checks the entire magnitude rather than its Java hash and low word. */
	@Test
	@DisplayName("Separates 10000 decimal magnitudes with colliding Java hashes and low words")
	void shouldSeparateDecimalCollisionFamily() {
		final Set<Long> thresholdHashes = CollectionUtils.createHashSet(10_000);
		final Set<Long> bucketHashes = CollectionUtils.createHashSet(10_000);
		for (int i = 1; i <= 10_000; i++) {
			final BigInteger magnitude = BigInteger.valueOf(i).shiftLeft(96)
				.add(BigInteger.valueOf(310001L - 31L * i).shiftLeft(64));
			final BigDecimal value = new BigDecimal(magnitude);
			final KeyAccumulator accumulator = new KeyAccumulator(1);
			accumulator.add(new ThresholdComparison(ThresholdOperator.LESS_THAN, value));
			thresholdHashes.add(accumulator.hash64());
			bucketHashes.add(new ValueList(value).hash64());
		}
		assertEquals(10_000, thresholdHashes.size(), "threshold hashes");
		assertEquals(10_000, bucketHashes.size(), "bucket hashes");
	}

	/** Pins numeric decimal identity across normalized record equality and hashing. */
	@Test
	@DisplayName("Treats 1.0 and 1.00 as the same identity")
	void shouldNormalizeDecimalIdentity() {
		final ThresholdComparison first = new ThresholdComparison(ThresholdOperator.LESS_THAN, new BigDecimal("1.0"));
		final ThresholdComparison second = new ThresholdComparison(ThresholdOperator.LESS_THAN, new BigDecimal("1.00"));
		assertEquals(first, second);
		final KeyAccumulator firstHash = new KeyAccumulator(1);
		final KeyAccumulator secondHash = new KeyAccumulator(1);
		firstHash.add(first);
		secondHash.add(second);
		assertEquals(firstHash.hash64(), secondHash.hash64());
		final ValueList firstList = new ValueList(new BigDecimal("1.0"));
		final ValueList secondList = new ValueList(new BigDecimal("1.00"));
		assertEquals(firstList, secondList);
		assertEquals(firstList.hash64(), secondList.hash64());
	}

	/** Verifies the complete equal-String-hash collision family. */
	@Test
	@DisplayName("Separates 1024 equal Java string hashes")
	void shouldSeparateStringCollisionFamily() {
		final Set<Long> hashes = CollectionUtils.createHashSet(1_024);
		final int referenceHash = "Aa".repeat(10).hashCode();
		for (int i = 0; i < 1_024; i++) {
			final StringBuilder text = new StringBuilder(20);
			for (int bit = 0; bit < 10; bit++) { text.append((i & (1 << bit)) == 0 ? "Aa" : "BB"); }
			final String value = text.toString();
			assertEquals(referenceHash, value.hashCode());
			final KeyAccumulator accumulator = new KeyAccumulator(1);
			accumulator.add(value);
			assertTrue(hashes.add(accumulator.hash64()));
		}
		assertEquals(1_024, hashes.size());
	}
	/** Verifies nullable markers, normalized threshold values and content-sensitive weight. */
	@Test
	@DisplayName("Charges text and decimal content and distinguishes nullable lists")
	void shouldChargeContent() {
		final KeyAccumulator text = new KeyAccumulator(1);
		text.add("a".repeat(160));
		assertEquals(12, text.weight());
		final KeyAccumulator decimal = new KeyAccumulator(1);
		decimal.add(new ThresholdComparison(ThresholdOperator.LESS_THAN,
			new BigDecimal(java.math.BigInteger.ONE.shiftLeft(640))));
		assertEquals(13, decimal.weight());
		final KeyAccumulator nullList = new KeyAccumulator(1);
		nullList.addStrings(null);
		final KeyAccumulator emptyList = new KeyAccumulator(1);
		emptyList.addStrings(List.of());
		assertNotEquals(nullList.hash64(), emptyList.hash64());
		final KeyAccumulator first = new KeyAccumulator(1);
		first.add(new ThresholdRange(new BigDecimal("1.00"), new BigDecimal("2.0")));
		final KeyAccumulator second = new KeyAccumulator(1);
		second.add(new ThresholdRange(BigDecimal.ONE, new BigDecimal("2")));
		assertEquals(first.hash64(), second.hash64());
		final KeyAccumulator locale = new KeyAccumulator(1);
		locale.add(Locale.forLanguageTag("en-Latn-US-u-ca-gregory"));
		assertFalse(locale.isQueryLocal());
	}
	/** Verifies that each supported component distinguishes changed content and nullable markers. */
	@Test
	@DisplayName("Hashes all supported component forms and propagates locality")
	void shouldDescribeClosedComponentDomain() {
		final KeyAccumulator accumulator = new KeyAccumulator(3);
		accumulator.add(true);
		accumulator.add(7);
		accumulator.add(8L);
		accumulator.add("component");
		accumulator.add(ThresholdOperator.LESS_THAN);
		accumulator.add(java.util.Currency.getInstance("EUR"));
		accumulator.add(java.time.OffsetDateTime.parse("2026-01-01T00:00:00+01:00"));
		accumulator.add(new io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey(
			"name", Locale.ENGLISH));
		final io.evitadb.index.price.model.PriceIndexKey priceIndex = new io.evitadb.index.price.model.PriceIndexKey(
			"basic", java.util.Currency.getInstance("EUR"),
			io.evitadb.api.requestResponse.data.PriceInnerRecordHandling.NONE);
		accumulator.add(priceIndex);
		accumulator.add(new io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext(7L, priceIndex));
		accumulator.add(new Coordinates(4, 5));
		accumulator.add(IdentityToken.ofContent(new int[]{1, 2}));
		accumulator.add(TokenList.ofProducerIds(new long[]{1, 2}, false));
		accumulator.add(new ValueList("value", 1));
		accumulator.addStrings(List.of("one", "two"));
		assertFalse(accumulator.isOpaque());
		assertFalse(accumulator.isQueryLocal());
		assertFalse(accumulator.hasSideKeys());
		final FormulaKey sideKey = FormulaKey.of(new Coordinates(4, 5), 1, FormulaKey.NO_CHILDREN, new Object());
		accumulator.addKeys(List.of(sideKey));
		assertTrue(accumulator.hasSideKeys());
		assertFalse(accumulator.isOpaque());
		final KeyAccumulator oversized = new KeyAccumulator(1);
		oversized.add(TokenList.ofProducerIds(new long[TokenList.CAP + 1], false));
		assertTrue(oversized.isQueryLocal());
		assertFalse(oversized.isOpaque());
		final KeyAccumulator opaque = new KeyAccumulator(1);
		opaque.add(new ValueList(new Object()));
		assertTrue(opaque.isOpaque());
		assertTrue(opaque.isQueryLocal());
	}

	/** Checks both value and null branches of nullable component overloads. */
	@Test
	@DisplayName("Hashes nullable component markers without conflating empty values")
	void shouldDescribeNullComponents() {
		final KeyAccumulator accumulator = new KeyAccumulator(1);
		accumulator.add((String) null);
		accumulator.add((Enum<?>) null);
		accumulator.add((Locale) null);
		accumulator.add((java.util.Currency) null);
		accumulator.add((java.time.OffsetDateTime) null);
		accumulator.add((io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey) null);
		accumulator.add((io.evitadb.index.price.model.PriceIndexKey) null);
		accumulator.add((io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext) null);
		accumulator.add((io.evitadb.core.query.algebra.attribute.RequestedThresholdPredicate) null);
		accumulator.add((IdentityRecord) null);
		accumulator.add((IdentityToken) null);
		accumulator.add((TokenList) null);
		accumulator.add((ValueList) null);
		accumulator.add((FormulaKey) null);
		assertFalse(accumulator.isOpaque());
		assertFalse(accumulator.isQueryLocal());
		assertFalse(accumulator.hasSideKeys());
		assertEquals(KeyAccumulator.contentHash("characters"),
			KeyAccumulator.contentHash(new StringBuilder("characters")));
	}

}
