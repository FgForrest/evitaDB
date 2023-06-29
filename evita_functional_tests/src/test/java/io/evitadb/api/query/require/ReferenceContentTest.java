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

import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link ReferenceContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 202"a"
 */
class ReferenceContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ReferenceContent referenceContent1 = referenceContentAll();
		assertArrayEquals(new String[0], referenceContent1.getReferenceNames());
		assertTrue(referenceContent1.getFilterBy().isEmpty());
		assertTrue(referenceContent1.getOrderBy().isEmpty());
		assertTrue(referenceContent1.getEntityRequirement().isEmpty());
		assertTrue(referenceContent1.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent2 = referenceContent("a");
		assertArrayEquals(new String[] {"a"}, referenceContent2.getReferenceNames());
		assertTrue(referenceContent2.getFilterBy().isEmpty());
		assertTrue(referenceContent2.getOrderBy().isEmpty());
		assertTrue(referenceContent2.getEntityRequirement().isEmpty());
		assertTrue(referenceContent2.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent3 = referenceContent(
				"a",
				filterBy(attributeEquals("code", "a"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent3.getReferenceNames());
		assertTrue(referenceContent3.getFilterBy().isPresent());
		assertTrue(referenceContent3.getOrderBy().isEmpty());
		assertTrue(referenceContent3.getEntityRequirement().isEmpty());
		assertTrue(referenceContent3.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent4 = referenceContent(
				"a",
				filterBy(attributeEquals("code", "a")),
				entityFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent4.getReferenceNames());
		assertTrue(referenceContent4.getFilterBy().isPresent());
		assertTrue(referenceContent4.getOrderBy().isEmpty());
		assertEquals(entityFetch(), referenceContent4.getEntityRequirement().orElse(null));
		assertTrue(referenceContent4.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent5 = referenceContent("a", "b");
		assertArrayEquals(new String[] {"a", "b"}, referenceContent5.getReferenceNames());
		assertTrue(referenceContent5.getFilterBy().isEmpty());
		assertTrue(referenceContent5.getOrderBy().isEmpty());
		assertTrue(referenceContent5.getEntityRequirement().isEmpty());
		assertTrue(referenceContent5.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent6 = QueryConstraints.referenceContentAll(entityFetch());
		assertArrayEquals(new String[0], referenceContent6.getReferenceNames());
		assertTrue(referenceContent6.getFilterBy().isEmpty());
		assertTrue(referenceContent6.getOrderBy().isEmpty());
		assertEquals(entityFetch(), referenceContent6.getEntityRequirement().orElse(null));
		assertTrue(referenceContent6.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent7 = referenceContent(new String[] {"a", "b"}, entityFetch(attributeContentAll()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent7.getReferenceNames());
		assertTrue(referenceContent7.getFilterBy().isEmpty());
		assertTrue(referenceContent7.getOrderBy().isEmpty());
		assertEquals(entityFetch(attributeContentAll()), referenceContent7.getEntityRequirement().orElse(null));
		assertTrue(referenceContent7.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent8 = referenceContent("a", null, null, null, null, null);
		assertArrayEquals(new String[] {"a"}, referenceContent8.getReferenceNames());
		assertTrue(referenceContent8.getFilterBy().isEmpty());
		assertTrue(referenceContent8.getOrderBy().isEmpty());
		assertTrue(referenceContent8.getEntityRequirement().isEmpty());
		assertTrue(referenceContent8.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent9 = referenceContent(new String[] {"a", "b"}, entityGroupFetch(attributeContentAll()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent9.getReferenceNames());
		assertTrue(referenceContent9.getFilterBy().isEmpty());
		assertTrue(referenceContent9.getOrderBy().isEmpty());
		assertTrue(referenceContent9.getEntityRequirement().isEmpty());
		assertEquals(entityGroupFetch(attributeContentAll()), referenceContent9.getGroupEntityRequirement().orElse(null));

		final ReferenceContent referenceContent10 = referenceContent(new String[] {"a", "b"}, entityFetch(associatedDataContentAll()), entityGroupFetch(attributeContentAll()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent10.getReferenceNames());
		assertTrue(referenceContent10.getFilterBy().isEmpty());
		assertTrue(referenceContent10.getOrderBy().isEmpty());
		assertEquals(entityFetch(associatedDataContentAll()), referenceContent10.getEntityRequirement().orElse(null));
		assertEquals(entityGroupFetch(attributeContentAll()), referenceContent10.getGroupEntityRequirement().orElse(null));

		final ReferenceContent referenceContent11 = referenceContent(
			"a",
			orderBy(attributeNatural("code"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent11.getReferenceNames());
		assertTrue(referenceContent11.getFilterBy().isEmpty());
		assertTrue(referenceContent11.getOrderBy().isPresent());
		assertTrue(referenceContent11.getEntityRequirement().isEmpty());
		assertTrue(referenceContent11.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent12 = referenceContent(
			"a",
			orderBy(attributeNatural("code")),
			entityFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent12.getReferenceNames());
		assertTrue(referenceContent12.getFilterBy().isEmpty());
		assertTrue(referenceContent12.getOrderBy().isPresent());
		assertEquals(entityFetch(), referenceContent12.getEntityRequirement().orElse(null));
		assertTrue(referenceContent12.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent13 = referenceContent(
			"a",
			filterBy(attributeEquals("code", "a")),
			orderBy(attributeNatural("code")),
			entityFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent13.getReferenceNames());
		assertTrue(referenceContent13.getFilterBy().isPresent());
		assertTrue(referenceContent13.getOrderBy().isPresent());
		assertEquals(entityFetch(), referenceContent13.getEntityRequirement().orElse(null));
		assertTrue(referenceContent13.getGroupEntityRequirement().isEmpty());

		final ReferenceContent referenceContent14 = referenceContent(
			"a",
			filterBy(attributeEquals("code", "a")),
			orderBy(attributeNatural("code")),
			entityFetch(),
			entityGroupFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent14.getReferenceNames());
		assertTrue(referenceContent14.getFilterBy().isPresent());
		assertTrue(referenceContent14.getOrderBy().isPresent());
		assertEquals(entityFetch(), referenceContent14.getEntityRequirement().orElse(null));
		assertEquals(entityGroupFetch(), referenceContent14.getGroupEntityRequirement().orElse(null));

		final ReferenceContent referenceContent15 = referenceContent(
			"a",
			filterBy(attributeEquals("code", "a")),
			orderBy(attributeNatural("code"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent15.getReferenceNames());
		assertTrue(referenceContent15.getFilterBy().isPresent());
		assertTrue(referenceContent15.getOrderBy().isPresent());
		assertTrue(referenceContent15.getEntityRequirement().isEmpty());
		assertTrue(referenceContent15.getGroupEntityRequirement().isEmpty());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(referenceContentAll().isApplicable());
		assertTrue(referenceContent("a").isApplicable());
		assertTrue(referenceContent("a", "c").isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))).isApplicable());
		assertTrue(referenceContent("a", orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContent("a", entityFetch(attributeContentAll())).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ReferenceContent referenceContent1 = referenceContent("a", "b");
		assertEquals("referenceContent('a','b')", referenceContent1.toString());

		final ReferenceContent referenceContent2 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)))", referenceContent2.toString());

		final ReferenceContent referenceContent3 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContentAll()))", referenceContent3.toString());

		final ReferenceContent referenceContent4 = QueryConstraints.referenceContentAll(entityFetch(attributeContentAll()));
		assertEquals("referenceContentAll(entityFetch(attributeContentAll()))", referenceContent4.toString());

		final ReferenceContent referenceContent5 = referenceContent(new String[]{"a", "b"}, entityFetch(attributeContentAll()));
		assertEquals("referenceContent('a','b',entityFetch(attributeContentAll()))", referenceContent5.toString());

		final ReferenceContent referenceContent6 = referenceContent(new String[]{"a", "b"}, entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
		assertEquals("referenceContent('a','b',entityFetch(attributeContentAll()),entityGroupFetch(associatedDataContentAll()))", referenceContent6.toString());

		final ReferenceContent referenceContent7 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContentAll()),entityGroupFetch(associatedDataContentAll()))", referenceContent7.toString());

		final ReferenceContent referenceContent8 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),orderBy(attributeNatural('code',ASC)),entityFetch(attributeContentAll()),entityGroupFetch(associatedDataContentAll()))", referenceContent8.toString());

		final ReferenceContent referenceContent9 = referenceContent("a", orderBy(attributeNatural("code")));
		assertEquals("referenceContent('a',orderBy(attributeNatural('code',ASC)))", referenceContent9.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(referenceContent("a", "b"), referenceContent("a", "b"));
		assertEquals(referenceContent("a", "b"), referenceContent("a", "b"));
		assertEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())), referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())));
		assertNotEquals(referenceContent("a", "b"), referenceContent("a", "e"));
		assertNotEquals(referenceContent("a", "b"), referenceContent("a"));
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))), referenceContent("a", entityFetch()));
		assertEquals(referenceContent("a", "b").hashCode(), referenceContent("a", "b").hashCode());
		assertNotEquals(referenceContent("a", "b").hashCode(), referenceContent("a", "e").hashCode());
		assertEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch()).hashCode(), referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch()).hashCode());
		assertEquals(referenceContent("a", orderBy(attributeNatural("code")), entityFetch()).hashCode(), referenceContent("a", orderBy(attributeNatural("code")), entityFetch()).hashCode());
		assertNotEquals(referenceContent("a", "b").hashCode(), referenceContent("a").hashCode());
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))).hashCode(), referenceContent("a", entityFetch()).hashCode());
	}

}