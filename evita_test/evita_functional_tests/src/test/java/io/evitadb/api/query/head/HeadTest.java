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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link Head} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class HeadTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final Head head = head(collection("brand"));
		assertArrayEquals(new HeadConstraint[] {collection("brand")}, head.getChildren());

		assertNull(head(null));
		assertNull(head());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new Head().isApplicable());
		assertFalse(new Head((HeadConstraint) null).isApplicable());
		assertTrue(head(collection("brand")).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final Head head = head(collection("brand"));
		assertEquals("head(collection('brand'))", head.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(head(collection("brand")), head(collection("brand")));
		assertEquals(head(collection("brand")), head(collection("brand")));
		assertNotEquals(head(collection("brand")), head(collection("product")));
	}

	@Test
	void shouldReturnCopyWithNewChildren() {
		final Head head = head(collection("brand"));
		final HeadConstraint copy = head.getCopyWithNewChildren(
			new HeadConstraint[]{collection("product")}, new Constraint[0]
		);

		assertInstanceOf(Head.class, copy);
		assertArrayEquals(new HeadConstraint[]{collection("product")}, ((Head) copy).getChildren());
	}

	@Test
	void shouldThrowWhenCopyWithAdditionalChildren() {
		final Head head = head(collection("brand"));

		assertThrows(
			GenericEvitaInternalError.class,
			() -> head.getCopyWithNewChildren(
				new HeadConstraint[]{collection("product")},
				new Constraint[]{collection("category")}
			)
		);
	}

	@Test
	void shouldThrowWhenCloneWithArguments() {
		final Head head = head(collection("brand"));

		assertThrows(
			UnsupportedOperationException.class,
			() -> head.cloneWithArguments(new Serializable[]{"test"})
		);
	}

	@Test
	void shouldRecognizeNecessity() {
		final Head singleChild = head(collection("brand"));
		assertFalse(singleChild.isNecessary());

		final Head multipleChildren = new Head(collection("brand"), label("a", "b"));
		assertTrue(multipleChildren.isNecessary());
	}

}