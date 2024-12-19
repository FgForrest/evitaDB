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

package io.evitadb.api.query.order;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Segment} constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class SegmentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final Segment segment = new Segment(orderBy(attributeNatural("code")));
		assertNotNull(segment);

		final Segment segmentWithLimit = new Segment(orderBy(attributeNatural("code")), limit(5));
		assertNotNull(segmentWithLimit);

		final Segment segmentWithLimitAndFilter = new Segment(
			entityHaving(attributeEquals("code", "123")),
			orderBy(attributeNatural("code")),
			limit(5)
		);
		assertNotNull(segmentWithLimitAndFilter);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new Segment(null).isApplicable());

		final Segment segment = new Segment(orderBy(attributeNatural("code")));
		assertTrue(segment.isApplicable());

		final Segment segmentWithLimit = new Segment(orderBy(attributeNatural("code")), limit(5));
		assertTrue(segmentWithLimit.isApplicable());

		final Segment segmentWithLimitAndFilter = new Segment(
			entityHaving(attributeEquals("code", "123")),
			orderBy(attributeNatural("code")),
			limit(5)
		);
		assertTrue(segmentWithLimitAndFilter.isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final Segment segment = new Segment(orderBy(attributeNatural("code")));
		assertEquals("segment(orderBy(attributeNatural('code',ASC)))", segment.toString());

		final Segment segmentWithLimit = new Segment(orderBy(attributeNatural("code")), limit(5));
		assertEquals("segment(orderBy(attributeNatural('code',ASC)),limit(5))", segmentWithLimit.toString());

		final Segment segmentWithLimitAndFilter = new Segment(
			entityHaving(attributeEquals("code", "123")),
			orderBy(attributeNatural("code")),
			limit(5)
		);
		assertEquals(
			"segment(entityHaving(attributeEquals('code','123')),orderBy(attributeNatural('code',ASC)),limit(5))",
			segmentWithLimitAndFilter.toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		final Segment segment1 = new Segment(orderBy(attributeNatural("code")));
		final Segment segment2 = new Segment(orderBy(attributeNatural("code")));
		final Segment segment3 = new Segment(orderBy(attributeNatural("name")));

		final Segment segmentWithLimit1 = new Segment(orderBy(attributeNatural("code")), limit(5));
		final Segment segmentWithLimit2 = new Segment(orderBy(attributeNatural("code")), limit(5));
		final Segment segmentWithLimit3 = new Segment(orderBy(attributeNatural("code")), limit(10));

		final Segment segmentWithFilter1 = new Segment(entityHaving(attributeEquals("code", "123")), orderBy(attributeNatural("code")), limit(5));
		final Segment segmentWithFilter2 = new Segment(entityHaving(attributeEquals("code", "123")), orderBy(attributeNatural("code")), limit(5));
		final Segment segmentWithFilter3 = new Segment(entityHaving(attributeEquals("code", "456")), orderBy(attributeNatural("code")), limit(5));

		// Equality and Same Object assertions
		assertSame(segment1, segment1);
		assertNotSame(segment1, segment2);
		assertEquals(segment1, segment2);
		assertNotEquals(segment1, segment3);

		assertSame(segmentWithLimit1, segmentWithLimit1);
		assertNotSame(segmentWithLimit1, segmentWithLimit2);
		assertNotSame(segmentWithLimit1, segmentWithLimit3);
		assertEquals(segmentWithLimit1, segmentWithLimit2);
		assertNotEquals(segmentWithLimit1, segmentWithLimit3);

		assertSame(segmentWithFilter1, segmentWithFilter1);
		assertNotSame(segmentWithFilter1, segmentWithFilter2);
		assertNotSame(segmentWithFilter1, segmentWithFilter3);
		assertEquals(segmentWithFilter1, segmentWithFilter2);
		assertNotEquals(segmentWithFilter1, segmentWithFilter3);

		assertEquals(segment1.hashCode(), segment2.hashCode());
		assertEquals(segmentWithLimit1.hashCode(), segmentWithLimit2.hashCode());
		assertNotEquals(segmentWithLimit1.hashCode(), segmentWithLimit3.hashCode());
		assertEquals(segmentWithFilter1.hashCode(), segmentWithFilter2.hashCode());
		assertNotEquals(segmentWithFilter1.hashCode(), segmentWithFilter3.hashCode());
	}

}