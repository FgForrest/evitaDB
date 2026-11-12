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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.test.identity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilterIdentityFixture.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached, type-faithful identities of attribute prefetch matchers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute prefetch matcher identity")
@Tag(ENGINE)
@Tag(CACHE)
class AttributeBitmapFilterIdentityTest {
	/** Runs every construction mode and every field perturbation through the shared verifier. */
	@Test
	@DisplayName("Verifies controlled and opaque construction modes")
	void shouldVerifyIdentityContract() {
		IdentityContractVerifier.verify(AttributeBitmapFilter.class, new AttributeBitmapFilterIdentityFixture());
	}

	/** Equal EvitaQL literals must not erase their runtime argument types. */
	@Test
	@DisplayName("Separates type-ambiguous EvitaQL literals and decimal range precision")
	void shouldPreserveTypesOfEvitaQlLiterals() {
		final AttributeEquals integer = attributeEquals("a", 1);
		final AttributeEquals longInteger = attributeEquals("a", 1L);
		assertEquals(integer.toString(), longInteger.toString());
		assertNotEquals(valueKey(integer), valueKey(longInteger));
		assertEquals(attributeEquals("a", 'x').toString(), attributeEquals("a", "x").toString());
		assertNotEquals(valueKey(attributeEquals("a", 'x')), valueKey(attributeEquals("a", "x")));
		assertNotEquals(valueKey(attributeEquals("a", new BigDecimal("1.0"))),
			valueKey(attributeEquals("a", new BigDecimal("1.00"))));
		final AttributeEquals coarse = attributeEquals("a", BigDecimalNumberRange.between(
			new BigDecimal("1.11"), new BigDecimal("2.22"), 1));
		final AttributeEquals precise = attributeEquals("a", BigDecimalNumberRange.between(
			new BigDecimal("1.11"), new BigDecimal("2.22"), 2));
		assertEquals(coarse.toString(), precise.toString());
		assertNotEquals(valueKey(coarse), valueKey(precise));
		assertNotEquals(valueKey(attributeEquals("a", OffsetDateTime.parse("2026-01-01T00:00:00Z"))),
			valueKey(attributeEquals("a", OffsetDateTime.parse("2026-01-01T01:00:00+01:00"))));
	}

	/** A live constraint or caller-owned argument array cannot rewrite a captured key. */
	@Test
	@DisplayName("Captures immutable arguments when the matcher is constructed")
	void shouldDetachConstraintArguments() {
		final AttributeEquals constraint = attributeEquals("a", 1);
		final AttributeBitmapFilter filter = create(constraint, attributeContent("a"), null, false, null);
		final FormulaKey captured = key(filter);
		constraint.getArguments()[1] = 2L;
		assertEquals(valueKey(attributeEquals("a", 1)), key(filter));
		assertEquals(captured, key(filter));
		assertNotEquals(captured, valueKey(constraint));
	}

	/** Arrays have no canonical scalar formatting and unknown mutable values have no value identity. */
	@Test
	@DisplayName("Keeps arrays, nested arrays, unknown values and raw functions opaque")
	void shouldKeepUnsupportedEncodingsOpaque() {
		for (final Serializable unsupported : new Serializable[]{
			new int[]{1, 2}, new Integer[]{1, 2}, new Integer[][]{{1}}, new StringBuilder("value"), java.time.DayOfWeek.MONDAY
		}) {
			final AttributeEquals constraint = attributeEquals("a", 1);
			constraint.getArguments()[1] = unsupported;
			final AttributeBitmapFilter first = create(constraint, attributeContent("a"), null, false, null);
			assertTrue(key(first).isOpaque());
			assertTrue(key(first).isQueryLocal());
			assertEquals(key(first), key(first));
			assertNotEquals(key(first), key(create(constraint, attributeContent("a"), null, false, null)));
		}
		final AttributeBitmapFilter raw = raw();
		assertTrue(key(raw).isOpaque());
		assertNotEquals(key(raw), key(raw()));
	}

	/** Retained literal characters contribute to the admission weight. */
	@Test
	@DisplayName("Charges long argument text to key weight")
	void shouldChargeSnapshotContent() {
		assertTrue(valueKey(attributeEquals("a", "x".repeat(8192))).getWeight() >
			valueKey(attributeEquals("a", "x")).getWeight());
	}

	/** Builds the controlled alternative key for one equality constraint. */
	@Nonnull
	private static FormulaKey valueKey(@Nonnull AttributeEquals constraint) {
		return key(create(constraint, attributeContent("a"), null, false, null));
	}

	/** Open bounds are explicit argument values, preserving their position without making the matcher opaque. */
	@Test
	@DisplayName("Captures null range bounds as exact typed arguments")
	void shouldCaptureOpenRangeBounds() {
		final FilterConstraint lowerOpen = attributeBetween("a", null, 10);
		final FilterConstraint upperOpen = attributeBetween("a", 10, null);
		final FormulaKey first = key(create(lowerOpen, attributeContent("a"), null, false, null));
		assertFalse(first.isOpaque());
		assertEquals(first, key(create(attributeBetween("a", null, 10), attributeContent("a"), null, false, null)));
		assertNotEquals(first, key(create(upperOpen, attributeContent("a"), null, false, null)));
	}

	/** Only an in-range constraint with no explicit probe reads the query's implicit moment. */
	@Test
	@DisplayName("Identifies the attribute constraints that read the implicit query moment")
	void shouldIdentifyImplicitQueryMoment() {
		final io.evitadb.core.query.filter.translator.attribute.AttributeInRangeTranslator translator =
			new io.evitadb.core.query.filter.translator.attribute.AttributeInRangeTranslator();
		assertTrue(translator.readsQueryMoment(attributeInRangeNow("a")));
		assertFalse(translator.readsQueryMoment(attributeInRange("a", 1)));
		assertFalse(translator.readsQueryMoment(attributeInRange("a", OffsetDateTime.parse("2026-01-01T00:00:00Z"))));
		assertFalse(new io.evitadb.core.query.filter.translator.attribute.AttributeEqualsTranslator()
			.readsQueryMoment(attributeEquals("a", 1)));
	}
}
