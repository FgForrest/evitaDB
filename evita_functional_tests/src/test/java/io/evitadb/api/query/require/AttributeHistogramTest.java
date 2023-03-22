/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeHistogram} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 202"a"
 */
class AttributeHistogramTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeHistogram attributeHistogram = attributeHistogram(20, "a", "b");
		assertEquals(20, attributeHistogram.getRequestedBucketCount());
		assertArrayEquals(new String[]{"a", "b"}, attributeHistogram.getAttributeNames());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertNull(attributeHistogram(20));
		assertFalse(new AttributeHistogram(20).isApplicable());
		assertTrue(attributeHistogram(20, "a").isApplicable());
		assertTrue(attributeHistogram(20, "a", "c").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeHistogram attributeHistogram = attributeHistogram(20, "a", "b");
		assertEquals("attributeHistogram(20,'a','b')", attributeHistogram.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a", "b"));
		assertEquals(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a", "b"));
		assertNotEquals(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a", "e"));
		assertNotEquals(attributeHistogram(20, "a", "b"), attributeHistogram(21, "a", "b"));
		assertNotEquals(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a"));
		assertEquals(attributeHistogram(20, "a", "b").hashCode(), attributeHistogram(20, "a", "b").hashCode());
		assertNotEquals(attributeHistogram(20, "a", "b").hashCode(), attributeHistogram(20, "a", "e").hashCode());
		assertNotEquals(attributeHistogram(20, "a", "b").hashCode(), attributeHistogram(21, "a", "b").hashCode());
		assertNotEquals(attributeHistogram(20, "a", "b").hashCode(), attributeHistogram(20, "a").hashCode());
	}

}