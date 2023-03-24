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
		final ReferenceContent referenceContent1 = referenceContent();
		assertArrayEquals(new String[0], referenceContent1.getReferencedEntityTypes());
		assertNull(referenceContent1.getFilterBy());
		assertNull(referenceContent1.getOrderBy());
		assertNull(referenceContent1.getEntityRequirement());
		assertNull(referenceContent1.getGroupEntityRequirement());

		final ReferenceContent referenceContent2 = referenceContent("a");
		assertArrayEquals(new String[] {"a"}, referenceContent2.getReferencedEntityTypes());
		assertNull(referenceContent2.getFilterBy());
		assertNull(referenceContent2.getOrderBy());
		assertNull(referenceContent2.getEntityRequirement());
		assertNull(referenceContent2.getGroupEntityRequirement());

		final ReferenceContent referenceContent3 = referenceContent(
				"a",
				filterBy(attributeEquals("code", "a"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent3.getReferencedEntityTypes());
		assertNotNull(referenceContent3.getFilterBy());
		assertNull(referenceContent3.getOrderBy());
		assertNull(referenceContent3.getEntityRequirement());
		assertNull(referenceContent3.getGroupEntityRequirement());

		final ReferenceContent referenceContent4 = referenceContent(
				"a",
				filterBy(attributeEquals("code", "a")),
				entityFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent4.getReferencedEntityTypes());
		assertNotNull(referenceContent4.getFilterBy());
		assertNull(referenceContent4.getOrderBy());
		assertEquals(entityFetch(), referenceContent4.getEntityRequirement());
		assertNull(referenceContent4.getGroupEntityRequirement());

		final ReferenceContent referenceContent5 = referenceContent("a", "b");
		assertArrayEquals(new String[] {"a", "b"}, referenceContent5.getReferencedEntityTypes());
		assertNull(referenceContent5.getFilterBy());
		assertNull(referenceContent5.getOrderBy());
		assertNull(referenceContent5.getEntityRequirement());
		assertNull(referenceContent5.getGroupEntityRequirement());

		final ReferenceContent referenceContent6 = referenceContent(entityFetch());
		assertArrayEquals(new String[0], referenceContent6.getReferencedEntityTypes());
		assertNull(referenceContent6.getFilterBy());
		assertNull(referenceContent6.getOrderBy());
		assertEquals(entityFetch(), referenceContent6.getEntityRequirement());
		assertNull(referenceContent6.getGroupEntityRequirement());

		final ReferenceContent referenceContent7 = referenceContent(new String[] {"a", "b"}, entityFetch(attributeContent()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent7.getReferencedEntityTypes());
		assertNull(referenceContent7.getFilterBy());
		assertNull(referenceContent7.getOrderBy());
		assertEquals(entityFetch(attributeContent()), referenceContent7.getEntityRequirement());
		assertNull(referenceContent7.getGroupEntityRequirement());

		final ReferenceContent referenceContent8 = referenceContent("a", null, null, null, null);
		assertArrayEquals(new String[] {"a"}, referenceContent8.getReferencedEntityTypes());
		assertNull(referenceContent8.getFilterBy());
		assertNull(referenceContent8.getOrderBy());
		assertNull(referenceContent8.getEntityRequirement());
		assertNull(referenceContent8.getGroupEntityRequirement());

		final ReferenceContent referenceContent9 = referenceContent(new String[] {"a", "b"}, entityGroupFetch(attributeContent()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent9.getReferencedEntityTypes());
		assertNull(referenceContent9.getFilterBy());
		assertNull(referenceContent9.getOrderBy());
		assertNull(referenceContent9.getEntityRequirement());
		assertEquals(entityGroupFetch(attributeContent()), referenceContent9.getGroupEntityRequirement());

		final ReferenceContent referenceContent10 = referenceContent(new String[] {"a", "b"}, entityFetch(associatedDataContent()), entityGroupFetch(attributeContent()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent10.getReferencedEntityTypes());
		assertNull(referenceContent10.getFilterBy());
		assertNull(referenceContent10.getOrderBy());
		assertEquals(entityFetch(associatedDataContent()), referenceContent10.getEntityRequirement());
		assertEquals(entityGroupFetch(attributeContent()), referenceContent10.getGroupEntityRequirement());

		final ReferenceContent referenceContent11 = referenceContent(
			"a",
			orderBy(attributeNatural("code"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent11.getReferencedEntityTypes());
		assertNull(referenceContent11.getFilterBy());
		assertNotNull(referenceContent11.getOrderBy());
		assertNull(referenceContent11.getEntityRequirement());
		assertNull(referenceContent11.getGroupEntityRequirement());

		final ReferenceContent referenceContent12 = referenceContent(
			"a",
			orderBy(attributeNatural("code")),
			entityFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent12.getReferencedEntityTypes());
		assertNull(referenceContent12.getFilterBy());
		assertNotNull(referenceContent12.getOrderBy());
		assertEquals(entityFetch(), referenceContent12.getEntityRequirement());
		assertNull(referenceContent12.getGroupEntityRequirement());

		final ReferenceContent referenceContent13 = referenceContent(
			"a",
			filterBy(attributeEquals("code", "a")),
			orderBy(attributeNatural("code")),
			entityFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent13.getReferencedEntityTypes());
		assertNotNull(referenceContent13.getFilterBy());
		assertNotNull(referenceContent13.getOrderBy());
		assertEquals(entityFetch(), referenceContent13.getEntityRequirement());
		assertNull(referenceContent13.getGroupEntityRequirement());

		final ReferenceContent referenceContent14 = referenceContent(
			"a",
			filterBy(attributeEquals("code", "a")),
			orderBy(attributeNatural("code")),
			entityFetch(),
			entityGroupFetch()
		);
		assertArrayEquals(new String[] {"a"}, referenceContent14.getReferencedEntityTypes());
		assertNotNull(referenceContent14.getFilterBy());
		assertNotNull(referenceContent14.getOrderBy());
		assertEquals(entityFetch(), referenceContent14.getEntityRequirement());
		assertEquals(entityGroupFetch(), referenceContent14.getGroupEntityRequirement());

		final ReferenceContent referenceContent15 = referenceContent(
			"a",
			filterBy(attributeEquals("code", "a")),
			orderBy(attributeNatural("code"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent15.getReferencedEntityTypes());
		assertNotNull(referenceContent15.getFilterBy());
		assertNotNull(referenceContent15.getOrderBy());
		assertNull(referenceContent15.getEntityRequirement());
		assertNull(referenceContent15.getGroupEntityRequirement());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(referenceContent().isApplicable());
		assertTrue(referenceContent("a").isApplicable());
		assertTrue(referenceContent("a", "c").isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))).isApplicable());
		assertTrue(referenceContent("a", orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContent("a", entityFetch(attributeContent())).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent())).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContent()), entityGroupFetch()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ReferenceContent referenceContent1 = referenceContent("a", "b");
		assertEquals("referenceContent('a','b')", referenceContent1.toString());

		final ReferenceContent referenceContent2 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)))", referenceContent2.toString());

		final ReferenceContent referenceContent3 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContent()))", referenceContent3.toString());

		final ReferenceContent referenceContent4 = referenceContent(entityFetch(attributeContent()));
		assertEquals("referenceContent(entityFetch(attributeContent()))", referenceContent4.toString());

		final ReferenceContent referenceContent5 = referenceContent(new String[]{"a", "b"}, entityFetch(attributeContent()));
		assertEquals("referenceContent('a','b',entityFetch(attributeContent()))", referenceContent5.toString());

		final ReferenceContent referenceContent6 = referenceContent(new String[]{"a", "b"}, entityFetch(attributeContent()), entityGroupFetch(associatedDataContent()));
		assertEquals("referenceContent('a','b',entityFetch(attributeContent()),entityGroupFetch(associatedDataContent()))", referenceContent6.toString());

		final ReferenceContent referenceContent7 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent()), entityGroupFetch(associatedDataContent()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContent()),entityGroupFetch(associatedDataContent()))", referenceContent7.toString());

		final ReferenceContent referenceContent8 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContent()), entityGroupFetch(associatedDataContent()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),orderBy(attributeNatural('code',ASC)),entityFetch(attributeContent()),entityGroupFetch(associatedDataContent()))", referenceContent8.toString());

		final ReferenceContent referenceContent9 = referenceContent("a", orderBy(attributeNatural("code")));
		assertEquals("referenceContent('a',orderBy(attributeNatural('code',ASC)))", referenceContent9.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(referenceContent("a", "b"), referenceContent("a", "b"));
		assertEquals(referenceContent("a", "b"), referenceContent("a", "b"));
		assertEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent())), referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent())));
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