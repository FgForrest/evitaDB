/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.visitor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.visitor.FinderVisitor.MoreThanSingleResultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies expected behaviour of {@link FinderVisitor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FinderVisitorTest {
	private FilterConstraint filterConstraint;
	private RequireConstraint requireConstraint;

	@BeforeEach
	void setUp() {
		this.filterConstraint = and(
			attributeEquals("a", "b"),
			or(
				attributeIsNotNull("def"),
				attributeEqualsTrue("xev"),
				attributeBetween("c", 1, 78),
				not(
					attributeEqualsTrue("utr")
				)
			)
		);
		this.requireConstraint = require(
			page(1, 20),
			referenceContent(
				"a",
				filterBy(
					attributeEqualsTrue("xev")
				),
				entityFetch(attributeContentAll())
			)
		);
	}

	@Test
	void shouldNotFindMissingConstraint() {
		assertNull(FinderVisitor.findConstraint(this.filterConstraint, fc -> fc instanceof AttributeStartsWith));
	}

	@Test
	void shouldFindExistingConstraint() {
		assertEquals(attributeBetween("c", 1, 78), FinderVisitor.findConstraint(this.filterConstraint, fc -> fc instanceof AttributeBetween));
	}

	@Test
	void shouldFindExistingConstraintByName() {
		assertEquals(
			attributeBetween("c", 1, 78),
			FinderVisitor.findConstraint(this.filterConstraint, fc -> {
				final Serializable[] args = fc.getArguments();
				return args.length >= 1 && "c".equals(args[0]);
			})
		);
	}

	@Test
	void shouldFindExistingConstraintInAdditionalChildrenByName() {
		assertEquals(
			attributeEqualsTrue("xev"),
			FinderVisitor.findConstraint(this.requireConstraint, fc -> {
				final Serializable[] args = fc.getArguments();
				return args.length >= 1 && "xev".equals(args[0]);
			})
		);
	}

	@Test
	void shouldFindMultipleConstraints() {
		assertEquals(
			2,
			FinderVisitor.findConstraints(
				this.filterConstraint,
				fc -> fc instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)
			).size()
		);
	}

	@Test
	void shouldReportExceptionWhenExpectingSingleResultButMultipleFound() {
		assertThrows(
			MoreThanSingleResultException.class,
			() -> FinderVisitor.findConstraint(
				this.filterConstraint,
				fc -> fc instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)
			)
		);
	}

}
