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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.attribute.RequestedThresholdPredicate;
import io.evitadb.core.query.algebra.attribute.ThresholdComparison;
import io.evitadb.core.query.algebra.attribute.ThresholdOperator;
import io.evitadb.core.query.algebra.attribute.ThresholdRange;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies content hashing, collision families and retained weight.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Identity accumulator")
@Tag(ENGINE)
@Tag(CACHE)
class KeyAccumulatorTest {
	/** Price index shared by the component forms that need one. */
	private static final PriceIndexKey PRICE_INDEX = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);

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

	/** Builds a fresh value key over two coordinates. */
	@Nonnull
	private static FormulaKey coordinatesKey(int from, int to) {
		return FormulaKey.of(new Coordinates(from, to), 1, FormulaKey.NO_CHILDREN, new Object());
	}

	/** Returns the hash of one component description in a fresh accumulator. */
	private static long hashOf(@Nonnull Consumer<KeyAccumulator> description) {
		final KeyAccumulator accumulator = new KeyAccumulator(1);
		description.accept(accumulator);
		return accumulator.hash64();
	}

	/**
	 * Lists every component overload with a value, a different value and, for reference types, the null form.
	 * Each description builds fresh instances, so describing it twice compares equal values rather than one instance.
	 */
	@Nonnull
	static Stream<Arguments> componentForms() {
		return Stream.of(
			form("boolean", it -> it.add(true), it -> it.add(false), null),
			form("int", it -> it.add(7), it -> it.add(8), null),
			form("long", it -> it.add(8L), it -> it.add(9L), null),
			form("String", it -> it.add("component"), it -> it.add("other"), it -> it.add((String) null)),
			form(
				"Enum", it -> it.add(ThresholdOperator.LESS_THAN), it -> it.add(ThresholdOperator.GREATER_THAN),
				it -> it.add((Enum<?>) null)
			),
			form(
				"Locale", it -> it.add(Locale.forLanguageTag("en-Latn-US-u-ca-gregory")),
				it -> it.add(Locale.forLanguageTag("en-Latn-US-u-ca-buddhist")), it -> it.add((Locale) null)
			),
			form(
				"Currency", it -> it.add(Currency.getInstance("EUR")), it -> it.add(Currency.getInstance("CZK")),
				it -> it.add((Currency) null)
			),
			form(
				"OffsetDateTime with another offset of the same instant",
				it -> it.add(OffsetDateTime.parse("2026-01-01T00:00:00+01:00")),
				it -> it.add(OffsetDateTime.parse("2025-12-31T23:00:00Z")), it -> it.add((OffsetDateTime) null)
			),
			form(
				"AttributeKey", it -> it.add(new AttributeKey("name", Locale.ENGLISH)),
				it -> it.add(new AttributeKey("name", Locale.GERMAN)), it -> it.add((AttributeKey) null)
			),
			form(
				"PriceIndexKey",
				it -> it.add(new PriceIndexKey("basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE)),
				it -> it.add(new PriceIndexKey("basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.SUM)),
				it -> it.add((PriceIndexKey) null)
			),
			form(
				"PriceEvaluationContext", it -> it.add(new PriceEvaluationContext(7L, PRICE_INDEX)),
				it -> it.add(new PriceEvaluationContext(8L, PRICE_INDEX)),
				it -> it.add((PriceEvaluationContext) null)
			),
			form(
				"RequestedThresholdPredicate",
				it -> it.add(new ThresholdComparison(ThresholdOperator.LESS_THAN, new BigDecimal("2.0"))),
				it -> it.add(new ThresholdRange(BigDecimal.ONE, new BigDecimal("2"))),
				it -> it.add((RequestedThresholdPredicate) null)
			),
			form(
				"IdentityRecord", it -> it.add(new Coordinates(4, 5)), it -> it.add(new Coordinates(5, 4)),
				it -> it.add((IdentityRecord) null)
			),
			form(
				"IdentityToken", it -> it.add(IdentityToken.ofContent(new int[]{1, 2})),
				it -> it.add(IdentityToken.ofContent(new int[]{2, 1})), it -> it.add((IdentityToken) null)
			),
			form(
				"TokenList", it -> it.add(TokenList.ofProducerIds(new long[]{1, 2}, false)),
				it -> it.add(TokenList.ofProducerIds(new long[]{2, 1}, false)), it -> it.add((TokenList) null)
			),
			form(
				"ValueList", it -> it.add(new ValueList("value", 1)), it -> it.add(new ValueList("value", 2)),
				it -> it.add((ValueList) null)
			),
			form(
				"FormulaKey", it -> it.add(coordinatesKey(4, 5)), it -> it.add(coordinatesKey(5, 4)),
				it -> it.add((FormulaKey) null)
			),
			form(
				"side key list", it -> it.addKeys(List.of(coordinatesKey(4, 5))),
				it -> it.addKeys(List.of(coordinatesKey(5, 4))), null
			),
			form(
				"string list", it -> it.addStrings(List.of("one", "two")),
				it -> it.addStrings(List.of("two", "one")), it -> it.addStrings(null)
			)
		);
	}

	/** Packs one row of {@link #componentForms()}. */
	@Nonnull
	private static Arguments form(
		@Nonnull String name, @Nonnull Consumer<KeyAccumulator> value, @Nonnull Consumer<KeyAccumulator> other,
		@Nullable Consumer<KeyAccumulator> absent
	) {
		return Arguments.of(name, value, other, absent);
	}

	/** Requires every overload to hash equal values alike and to separate changed values and the null marker. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("componentForms")
	@DisplayName("Hashes each component form by value and separates its null marker")
	void shouldHashComponentFormByValue(
		@Nonnull String name, @Nonnull Consumer<KeyAccumulator> value, @Nonnull Consumer<KeyAccumulator> other,
		@Nullable Consumer<KeyAccumulator> absent
	) {
		final long hash = hashOf(value);

		assertEquals(hash, hashOf(value), "equal values must hash alike");
		assertNotEquals(hash, hashOf(other), "a changed value must move the hash");
		if (absent != null) {
			assertNotEquals(hash, hashOf(absent), "null must not hash like a value");
		}
	}

	/** Separates absent text from empty text. */
	@Test
	@DisplayName("Hashes null text differently from empty text")
	void shouldSeparateNullTextFromEmptyText() {
		assertNotEquals(hashOf(it -> it.add((String) null)), hashOf(it -> it.add("")));
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
			new BigDecimal(BigInteger.ONE.shiftLeft(640))));
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

	/** Verifies that the supported component forms keep a key exact and propagate locality and side keys. */
	@Test
	@DisplayName("Accepts all supported component forms and propagates locality")
	void shouldDescribeClosedComponentDomain() {
		final KeyAccumulator accumulator = new KeyAccumulator(3);
		accumulator.add(true);
		accumulator.add(7);
		accumulator.add(8L);
		accumulator.add("component");
		accumulator.add(ThresholdOperator.LESS_THAN);
		accumulator.add(Currency.getInstance("EUR"));
		accumulator.add(OffsetDateTime.parse("2026-01-01T00:00:00+01:00"));
		accumulator.add(new AttributeKey("name", Locale.ENGLISH));
		accumulator.add(PRICE_INDEX);
		accumulator.add(new PriceEvaluationContext(7L, PRICE_INDEX));
		accumulator.add(new Coordinates(4, 5));
		accumulator.add(IdentityToken.ofContent(new int[]{1, 2}));
		accumulator.add(TokenList.ofProducerIds(new long[]{1, 2}, false));
		accumulator.add(new ValueList("value", 1));
		accumulator.addStrings(List.of("one", "two"));
		assertFalse(accumulator.isOpaque());
		assertFalse(accumulator.isQueryLocal());
		assertFalse(accumulator.hasSideKeys());
		accumulator.addKeys(List.of(coordinatesKey(4, 5)));
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

	/** Checks the null branch of every nullable component overload and content hashing of character sequences. */
	@Test
	@DisplayName("Keeps null components exact and hashes character sequences by content")
	void shouldKeepNullComponentsExact() {
		final KeyAccumulator accumulator = new KeyAccumulator(1);
		accumulator.add((String) null);
		accumulator.add((Enum<?>) null);
		accumulator.add((Locale) null);
		accumulator.add((Currency) null);
		accumulator.add((OffsetDateTime) null);
		accumulator.add((AttributeKey) null);
		accumulator.add((PriceIndexKey) null);
		accumulator.add((PriceEvaluationContext) null);
		accumulator.add((RequestedThresholdPredicate) null);
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
