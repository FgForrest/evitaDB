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
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Segments} constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class SegmentsTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertNull(segments());

		final Segments segments = segments(
			segment(
				orderBy(attributeNatural("orderedQuantity", DESC)),
				limit(3)
			),
			segment(
				entityHaving(attributeEquals("new", true)),
				orderBy(random()),
				limit(2)
			),
			segment(
				orderBy(attributeNatural("code"), attributeNatural("create"))
			)
		);

		assertNotNull(segments);
		assertEquals(3, segments.getChildrenCount());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(
			new Segments(
				segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3))
			).isApplicable()
		);

		assertFalse(new Segments().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final Segments segments = segments(
			segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
			segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(2)),
			segment(orderBy(attributeNatural("code"), attributeNatural("create")))
		);

		assertEquals(
			"segments(segment(orderBy(attributeNatural('orderedQuantity',DESC)),limit(3)),segment(entityHaving(attributeEquals('new',true)),orderBy(random()),limit(2)),segment(orderBy(attributeNatural('code',ASC),attributeNatural('create',ASC))))",
			segments.toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(createSegmentsConstraint(), createSegmentsConstraint());
		assertEquals(createSegmentsConstraint(), createSegmentsConstraint());
		assertNotEquals(createSegmentsConstraint(), createDifferentSegmentsConstraint());
		assertEquals(createSegmentsConstraint().hashCode(), createSegmentsConstraint().hashCode());
		assertNotEquals(createSegmentsConstraint().hashCode(), createDifferentSegmentsConstraint().hashCode());
	}

	private static Segments createSegmentsConstraint() {
		return segments(
			segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
			segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(2)),
			segment(orderBy(attributeNatural("code"), attributeNatural("create")))
		);
	}

	private static Segments createDifferentSegmentsConstraint() {
		return segments(
			segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
			segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(1)),
			segment(orderBy(attributeNatural("code"), attributeNatural("create")))
		);
	}

}