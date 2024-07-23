/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.attributeInRangeNow;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeInRange} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeInRangeTest {

	@Test
	void shouldCreateNowMomentViaFactoryClassWorkAsExpected() {
		final AttributeInRange attributeInRange = attributeInRangeNow("validity");
		assertEquals("validity", attributeInRange.getAttributeName());
		assertNull(attributeInRange.getTheMoment());
		assertNull(attributeInRange.getTheValue());
	}

	@Test
	void shouldCreateMomentViaFactoryClassWorkAsExpected() {
		final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		final AttributeInRange attributeInRange = attributeInRange("validity", now);
		assertEquals("validity", attributeInRange.getAttributeName());
		assertEquals(now, attributeInRange.getTheMoment());
		assertNull(attributeInRange.getTheValue());
	}

	@Test
	void shouldCreateNumberViaFactoryClassWorkAsExpected() {
		final AttributeInRange attributeInRange = attributeInRange("age", 19);
		assertEquals("age", attributeInRange.getAttributeName());
		assertEquals(19, attributeInRange.getTheValue());
		assertNull(attributeInRange.getTheMoment());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeInRange(null, (Number) null).isApplicable());
		assertTrue(new AttributeInRange("validity").isApplicable());
		assertTrue(QueryConstraints.attributeInRange("validity", OffsetDateTime.now(ZoneOffset.UTC)).isApplicable());
		assertTrue(attributeInRange("age", 19).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeInRange inDateRange = QueryConstraints.attributeInRange("validity", OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
		assertEquals("attributeInRange('validity',2021-01-01T00:00:00Z)", inDateRange.toString());

		final AttributeInRange inNumberRange = attributeInRange("age", 19);
		assertEquals("attributeInRange('age',19)", inNumberRange.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeInRange("age", 19), attributeInRange("age", 19));
		assertEquals(attributeInRange("age", 19), attributeInRange("age", 19));
		assertNotEquals(attributeInRange("age", 19), attributeInRange("age", 16));
		assertNotEquals(attributeInRange("age", 19), QueryConstraints.attributeInRange("validity", OffsetDateTime.now(ZoneOffset.UTC)));
		assertEquals(attributeInRange("age", 19).hashCode(), attributeInRange("age", 19).hashCode());
		assertNotEquals(attributeInRange("age", 19).hashCode(), attributeInRange("age", 6).hashCode());
		assertNotEquals(attributeInRange("age", 19).hashCode(), attributeInRange("whatever", 19).hashCode());
	}

}
