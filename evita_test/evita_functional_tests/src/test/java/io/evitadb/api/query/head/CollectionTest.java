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

package io.evitadb.api.query.head;

import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link Collection} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class CollectionTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final Collection collection = QueryConstraints.collection("brand");
		assertEquals("brand", collection.getEntityType());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new Collection(null).isApplicable());
		assertTrue(QueryConstraints.collection("brand").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final Collection collection = QueryConstraints.collection("brand");
		assertEquals("collection('brand')", collection.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(QueryConstraints.collection("brand"), QueryConstraints.collection("brand"));
		assertEquals(QueryConstraints.collection("brand"), QueryConstraints.collection("brand"));
		assertNotEquals(QueryConstraints.collection("brand"), QueryConstraints.collection("product"));
	}

	@Test
	void shouldCloneWithArguments() {
		final Collection original = QueryConstraints.collection("brand");
		final HeadConstraint cloned = original.cloneWithArguments(new Serializable[]{"product"});

		assertInstanceOf(Collection.class, cloned);
		assertEquals("product", ((Collection) cloned).getEntityType());
	}

	@Test
	void shouldRecognizeApplicabilityWithMultipleArgs() {
		final Collection original = QueryConstraints.collection("brand");
		// cloneWithArguments uses private varargs constructor, so we can create a Collection with wrong arity
		final HeadConstraint cloned = original.cloneWithArguments(new Serializable[]{"a", "b"});

		assertInstanceOf(Collection.class, cloned);
		assertFalse(cloned.isApplicable());
	}

}
