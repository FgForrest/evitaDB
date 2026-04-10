/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.query.head;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.api.query.QueryConstraints.label;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link Label} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class LabelTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final Label label = label("a", "b");
		assertEquals("a", label.getLabelName());
		assertEquals("b", label.getLabelValue());

		assertNull(label(null, null));
		assertNull(label(null, "B"));
		assertNull(label(null, null));
		assertNull(label("", null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new Label().isApplicable());
		assertFalse(new Label(null, null).isApplicable());
		assertFalse(new Label(null, "b").isApplicable());
		assertFalse(new Label("a", null).isApplicable());
		assertTrue(label("a", "c").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final Label label = label("a", "b");
		assertEquals("label('a','b')", label.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(label("a", "b"), label("a", "b"));
		assertEquals(label("a", "b"), label("a", "b"));
		assertNotEquals(label("a", "b"), label("a", "c"));
		assertNotEquals(label("a", "b"), label("b", "b"));
	}

	@Test
	void shouldCloneWithArguments() {
		final Label original = label("a", "b");
		final Label cloned = (Label) original.cloneWithArguments(new Serializable[]{"x", "y"});

		assertEquals("x", cloned.getLabelName());
		assertEquals("y", cloned.getLabelValue());
	}

	@Test
	void shouldRejectUnsupportedValueType() {
		// Double is Comparable & Serializable but NOT in EvitaDataTypes.SUPPORTED_QUERY_DATA_TYPES
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new Label("key", 3.14)
		);
	}

	@Test
	void shouldAcceptSupportedValueTypes() {
		final Label withInt = label("key", 42);
		assertEquals(42, withInt.getLabelValue());

		final Label withBigDecimal = label("key", new BigDecimal("1.5"));
		assertEquals(new BigDecimal("1.5"), withBigDecimal.getLabelValue());

		final Label withString = label("key", "value");
		assertEquals("value", withString.getLabelValue());

		final OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final Label withDateTime = label("key", dateTime);
		assertEquals(dateTime, withDateTime.getLabelValue());
	}

	@Test
	void shouldThrowWhenAccessingMissingName() {
		final Label empty = new Label();

		assertThrows(EvitaInvalidUsageException.class, empty::getLabelName);
	}

	@Test
	void shouldThrowWhenAccessingMissingValue() {
		final Label nameOnly = new Label(new Serializable[]{"a"});

		assertThrows(EvitaInvalidUsageException.class, nameOnly::getLabelValue);
	}

}