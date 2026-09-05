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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REQUIRE;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * This tests verifies basic properties of {@link ReferenceContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReferenceContent constraint")
@Tag(CONTRACT)
@Tag(REQUIRE)
@Tag(REFERENCE)
class ReferenceContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ReferenceContent referenceContent1 = referenceContentAll();
		assertArrayEquals(new String[0], referenceContent1.getReferenceNames());
		assertTrue(referenceContent1.getFilterBy().isEmpty());
		assertTrue(referenceContent1.getOrderBy().isEmpty());
		assertTrue(referenceContent1.getEntityRequirement().isEmpty());
		assertTrue(referenceContent1.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent1.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent2 = referenceContent("a");
		assertArrayEquals(new String[] {"a"}, referenceContent2.getReferenceNames());
		assertTrue(referenceContent2.getFilterBy().isEmpty());
		assertTrue(referenceContent2.getOrderBy().isEmpty());
		assertTrue(referenceContent2.getEntityRequirement().isEmpty());
		assertTrue(referenceContent2.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent2.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent3 = referenceContent(
				"a",
				filterBy(attributeEquals("code", "a"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent3.getReferenceNames());
		assertTrue(referenceContent3.getFilterBy().isPresent());
		assertTrue(referenceContent3.getOrderBy().isEmpty());
		assertTrue(referenceContent3.getEntityRequirement().isEmpty());
		assertTrue(referenceContent3.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent3.getManagedReferencesBehaviour());

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
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent4.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent5 = referenceContent("a", "b");
		assertArrayEquals(new String[] {"a", "b"}, referenceContent5.getReferenceNames());
		assertTrue(referenceContent5.getFilterBy().isEmpty());
		assertTrue(referenceContent5.getOrderBy().isEmpty());
		assertTrue(referenceContent5.getEntityRequirement().isEmpty());
		assertTrue(referenceContent5.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent5.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent6 = QueryConstraints.referenceContentAll(entityFetch());
		assertArrayEquals(new String[0], referenceContent6.getReferenceNames());
		assertTrue(referenceContent6.getFilterBy().isEmpty());
		assertTrue(referenceContent6.getOrderBy().isEmpty());
		assertEquals(entityFetch(), referenceContent6.getEntityRequirement().orElse(null));
		assertTrue(referenceContent6.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent6.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent7 = referenceContent(new String[] {"a", "b"}, entityFetch(attributeContentAll()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent7.getReferenceNames());
		assertTrue(referenceContent7.getFilterBy().isEmpty());
		assertTrue(referenceContent7.getOrderBy().isEmpty());
		assertEquals(entityFetch(attributeContentAll()), referenceContent7.getEntityRequirement().orElse(null));
		assertTrue(referenceContent7.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent7.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent8 = referenceContent("a");
		assertArrayEquals(new String[] {"a"}, referenceContent8.getReferenceNames());
		assertTrue(referenceContent8.getFilterBy().isEmpty());
		assertTrue(referenceContent8.getOrderBy().isEmpty());
		assertTrue(referenceContent8.getEntityRequirement().isEmpty());
		assertTrue(referenceContent8.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent8.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent9 = referenceContent(new String[] {"a", "b"}, entityGroupFetch(attributeContentAll()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent9.getReferenceNames());
		assertTrue(referenceContent9.getFilterBy().isEmpty());
		assertTrue(referenceContent9.getOrderBy().isEmpty());
		assertTrue(referenceContent9.getEntityRequirement().isEmpty());
		assertEquals(entityGroupFetch(attributeContentAll()), referenceContent9.getGroupEntityRequirement().orElse(null));
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent9.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent10 = referenceContent(new String[] {"a", "b"}, entityFetch(associatedDataContentAll()), entityGroupFetch(attributeContentAll()));
		assertArrayEquals(new String[] {"a", "b"}, referenceContent10.getReferenceNames());
		assertTrue(referenceContent10.getFilterBy().isEmpty());
		assertTrue(referenceContent10.getOrderBy().isEmpty());
		assertEquals(entityFetch(associatedDataContentAll()), referenceContent10.getEntityRequirement().orElse(null));
		assertEquals(entityGroupFetch(attributeContentAll()), referenceContent10.getGroupEntityRequirement().orElse(null));
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent10.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent11 = referenceContent(
			"a",
			orderBy(attributeNatural("code"))
		);
		assertArrayEquals(new String[] {"a"}, referenceContent11.getReferenceNames());
		assertTrue(referenceContent11.getFilterBy().isEmpty());
		assertTrue(referenceContent11.getOrderBy().isPresent());
		assertTrue(referenceContent11.getEntityRequirement().isEmpty());
		assertTrue(referenceContent11.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent11.getManagedReferencesBehaviour());

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
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent12.getManagedReferencesBehaviour());

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
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent13.getManagedReferencesBehaviour());

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
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent14.getManagedReferencesBehaviour());

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
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent15.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent16 = referenceContentWithAttributes("a", entityFetch());
		assertArrayEquals(new String[] {"a"}, referenceContent16.getReferenceNames());
		assertTrue(referenceContent16.getFilterBy().isEmpty());
		assertTrue(referenceContent16.getOrderBy().isEmpty());
		assertEquals(attributeContentAll(), referenceContent16.getAttributeContent().orElse(null));
		assertEquals(entityFetch(), referenceContent16.getEntityRequirement().orElse(null));
		assertTrue(referenceContent16.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent16.getManagedReferencesBehaviour());

		final ReferenceContent referenceContent17 = referenceContent("a", entityFetch(), page(2, 40));
		assertArrayEquals(new String[] {"a"}, referenceContent17.getReferenceNames());
		assertTrue(referenceContent17.getFilterBy().isEmpty());
		assertTrue(referenceContent17.getOrderBy().isEmpty());
		assertNull(referenceContent17.getAttributeContent().orElse(null));
		assertEquals(entityFetch(), referenceContent17.getEntityRequirement().orElse(null));
		assertTrue(referenceContent17.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent17.getManagedReferencesBehaviour());
		assertEquals(page(2, 40), referenceContent17.getPage().orElse(null));
		assertNull(referenceContent17.getStrip().orElse(null));

		final ReferenceContent referenceContent18 = referenceContentWithAttributes("a", entityFetch(), page(2, 40));
		assertArrayEquals(new String[] {"a"}, referenceContent18.getReferenceNames());
		assertTrue(referenceContent18.getFilterBy().isEmpty());
		assertTrue(referenceContent18.getOrderBy().isEmpty());
		assertEquals(attributeContentAll(), referenceContent18.getAttributeContent().orElse(null));
		assertEquals(entityFetch(), referenceContent18.getEntityRequirement().orElse(null));
		assertTrue(referenceContent18.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent18.getManagedReferencesBehaviour());
		assertEquals(page(2, 40), referenceContent18.getPage().orElse(null));
		assertNull(referenceContent18.getStrip().orElse(null));

		final ReferenceContent referenceContent19 = referenceContentAll(entityFetch(), page(2, 40));
		assertTrue(ArrayUtils.isEmpty(referenceContent19.getReferenceNames()));
		assertTrue(referenceContent19.getFilterBy().isEmpty());
		assertTrue(referenceContent19.getOrderBy().isEmpty());
		assertNull(referenceContent19.getAttributeContent().orElse(null));
		assertEquals(entityFetch(), referenceContent19.getEntityRequirement().orElse(null));
		assertTrue(referenceContent19.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent19.getManagedReferencesBehaviour());
		assertEquals(page(2, 40), referenceContent19.getPage().orElse(null));
		assertNull(referenceContent19.getStrip().orElse(null));

		final ReferenceContent referenceContent20 = referenceContentAllWithAttributes(entityFetch(), page(2, 40));
		assertTrue(ArrayUtils.isEmpty(referenceContent19.getReferenceNames()));
		assertTrue(referenceContent20.getFilterBy().isEmpty());
		assertTrue(referenceContent20.getOrderBy().isEmpty());
		assertEquals(attributeContentAll(), referenceContent20.getAttributeContent().orElse(null));
		assertEquals(entityFetch(), referenceContent20.getEntityRequirement().orElse(null));
		assertTrue(referenceContent20.getGroupEntityRequirement().isEmpty());
		assertEquals(ManagedReferencesBehaviour.ANY, referenceContent20.getManagedReferencesBehaviour());
		assertEquals(page(2, 40), referenceContent20.getPage().orElse(null));
		assertNull(referenceContent20.getStrip().orElse(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(referenceContentAll().isApplicable());
		assertTrue(referenceContentAll(page(2, 20)).isApplicable());
		assertTrue(referenceContentAll(strip(2, 20)).isApplicable());
		assertTrue(referenceContent("a").isApplicable());
		assertTrue(referenceContent("a", "c").isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))).isApplicable());
		assertTrue(referenceContent("a", orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContent("a", entityFetch(attributeContentAll())).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch()).isApplicable());
		assertTrue(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch(), page(2, 20)).isApplicable());
		assertTrue(referenceContent("a", page(2, 20)).isApplicable());
		assertTrue(referenceContent("a", strip(2, 20)).isApplicable());
		assertTrue(referenceContent("a", entityFetchAll(), page(2, 20)).isApplicable());
		assertTrue(referenceContent("a", entityFetchAll(), entityGroupFetchAll(), page(2, 20)).isApplicable());
		assertTrue(referenceContentWithAttributes("a").isApplicable());
		assertTrue(referenceContentWithAttributes("a", "c").isApplicable());
		assertTrue(referenceContentWithAttributes("a", filterBy(entityPrimaryKeyInSet(1))).isApplicable());
		assertTrue(referenceContentWithAttributes("a", orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContentWithAttributes("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code"))).isApplicable());
		assertTrue(referenceContentWithAttributes("a", entityFetch(attributeContentAll())).isApplicable());
		assertTrue(referenceContentWithAttributes("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())).isApplicable());
		assertTrue(referenceContentWithAttributes("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch()).isApplicable());
		assertTrue(referenceContentWithAttributes("a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch(), page(2, 20)).isApplicable());
		assertTrue(referenceContentWithAttributes("a", page(2, 20)).isApplicable());
		assertTrue(referenceContentWithAttributes("a", strip(2, 20)).isApplicable());
		assertTrue(referenceContentWithAttributes("a", entityFetchAll(), page(2, 20)).isApplicable());
		assertTrue(referenceContentWithAttributes("a", entityFetchAll(), entityGroupFetchAll(), page(2, 20)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ReferenceContent referenceContent1 = referenceContent("a", "b");
		assertEquals("referenceContent('a','b')", referenceContent1.toString());

		final ReferenceContent referenceContent2 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)))", referenceContent2.toString());

		final ReferenceContent referenceContent3 = referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()));
		assertEquals("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContentAll()))", referenceContent3.toString());

		final ReferenceContent referenceContent4 = referenceContentAll(entityFetch(attributeContentAll()));
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

		final ReferenceContent referenceContentWithAttributes1 = referenceContentWithAttributes("a", entityFetch(attributeContentAll()));
		assertEquals("referenceContentWithAttributes('a',entityFetch(attributeContentAll()))", referenceContentWithAttributes1.toString());

		final ReferenceContent referenceContentWithAttributes2 = referenceContentAllWithAttributes();
		assertEquals("referenceContentAllWithAttributes()", referenceContentWithAttributes2.toString());

		final ReferenceContent referenceContentWithAttributes3 = referenceContentAllWithAttributes(attributeContent("a"));
		assertEquals("referenceContentAllWithAttributes(attributeContent('a'))", referenceContentWithAttributes3.toString());

		final ReferenceContent referenceContentWithAttributes4 = referenceContentAllWithAttributes(attributeContent("a"), page(2, 20));
		assertEquals("referenceContentAllWithAttributes(attributeContent('a'),page(2,20))", referenceContentWithAttributes4.toString());
	}

	@Test
	void shouldToStringReturnExpectedFormatWithExistingReferencesOnly() {
		final ReferenceContent referenceContent1 = referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b");
		assertEquals("referenceContent(EXISTING,'a','b')", referenceContent1.toString());

		final ReferenceContent referenceContent2 = referenceContent(ManagedReferencesBehaviour.EXISTING,"a", filterBy(entityPrimaryKeyInSet(1)));
		assertEquals("referenceContent(EXISTING,'a',filterBy(entityPrimaryKeyInSet(1)))", referenceContent2.toString());

		final ReferenceContent referenceContent3 = referenceContent(ManagedReferencesBehaviour.EXISTING,"a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()));
		assertEquals("referenceContent(EXISTING,'a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContentAll()))", referenceContent3.toString());

		final ReferenceContent referenceContent4 = referenceContentAll(ManagedReferencesBehaviour.EXISTING,entityFetch(attributeContentAll()));
		assertEquals("referenceContentAll(EXISTING,entityFetch(attributeContentAll()))", referenceContent4.toString());

		final ReferenceContent referenceContent5 = referenceContent(ManagedReferencesBehaviour.EXISTING,new String[]{"a", "b"}, entityFetch(attributeContentAll()));
		assertEquals("referenceContent(EXISTING,'a','b',entityFetch(attributeContentAll()))", referenceContent5.toString());

		final ReferenceContent referenceContent6 = referenceContent(ManagedReferencesBehaviour.EXISTING,new String[]{"a", "b"}, entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
		assertEquals("referenceContent(EXISTING,'a','b',entityFetch(attributeContentAll()),entityGroupFetch(associatedDataContentAll()))", referenceContent6.toString());

		final ReferenceContent referenceContent7 = referenceContent(ManagedReferencesBehaviour.EXISTING,"a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
		assertEquals("referenceContent(EXISTING,'a',filterBy(entityPrimaryKeyInSet(1)),entityFetch(attributeContentAll()),entityGroupFetch(associatedDataContentAll()))", referenceContent7.toString());

		final ReferenceContent referenceContent8 = referenceContent(ManagedReferencesBehaviour.EXISTING,"a", filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code")), entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
		assertEquals("referenceContent(EXISTING,'a',filterBy(entityPrimaryKeyInSet(1)),orderBy(attributeNatural('code',ASC)),entityFetch(attributeContentAll()),entityGroupFetch(associatedDataContentAll()))", referenceContent8.toString());

		final ReferenceContent referenceContent9 = referenceContent(ManagedReferencesBehaviour.EXISTING,"a", orderBy(attributeNatural("code")));
		assertEquals("referenceContent(EXISTING,'a',orderBy(attributeNatural('code',ASC)))", referenceContent9.toString());

		final ReferenceContent referenceContentWithAttributes1 = referenceContentWithAttributes(ManagedReferencesBehaviour.EXISTING,"a", entityFetch(attributeContentAll()));
		assertEquals("referenceContentWithAttributes(EXISTING,'a',entityFetch(attributeContentAll()))", referenceContentWithAttributes1.toString());

		final ReferenceContent referenceContentWithAttributes2 = referenceContentAllWithAttributes(ManagedReferencesBehaviour.EXISTING);
		assertEquals("referenceContentAllWithAttributes(EXISTING)", referenceContentWithAttributes2.toString());

		final ReferenceContent referenceContentWithAttributes3 = referenceContentAllWithAttributes(ManagedReferencesBehaviour.EXISTING, attributeContent("a"));
		assertEquals("referenceContentAllWithAttributes(EXISTING,attributeContent('a'))", referenceContentWithAttributes3.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(referenceContent("a", "b"), referenceContent("a", "b"));
		assertEquals(referenceContent("a", "b"), referenceContent("a", "b"));
		assertEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())), referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll())));
		assertNotEquals(referenceContent("a", "b"), referenceContent("a", "e"));
		assertNotEquals(referenceContent("a", "b"), referenceContent(ManagedReferencesBehaviour.EXISTING,"a", "b"));
		assertNotEquals(referenceContent("a", "b"), referenceContent("a"));
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))), referenceContent("a", entityFetch()));
		assertEquals(referenceContent("a", "b").hashCode(), referenceContent("a", "b").hashCode());
		assertNotEquals(referenceContent("a", "b").hashCode(), referenceContent("a", "e").hashCode());
		assertNotEquals(referenceContent("a", "b").hashCode(), referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b").hashCode());
		assertEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch()).hashCode(), referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), entityFetch()).hashCode());
		assertEquals(referenceContent("a", orderBy(attributeNatural("code")), entityFetch()).hashCode(), referenceContent("a", orderBy(attributeNatural("code")), entityFetch()).hashCode());
		assertNotEquals(referenceContent("a", "b").hashCode(), referenceContent("a").hashCode());
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))).hashCode(), referenceContent("a", entityFetch()).hashCode());
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))), referenceContent("a", entityFetch(), page(2, 40)));
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1))).hashCode(), referenceContent("a", entityFetch(), page(2, 40)).hashCode());
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), strip(2, 40)), referenceContent("a", entityFetch(), page(2, 40)));
		assertNotEquals(referenceContent("a", filterBy(entityPrimaryKeyInSet(1)), strip(2, 40)).hashCode(), referenceContent("a", entityFetch(), page(2, 40)).hashCode());
	}

	@Test
	@DisplayName("getCopyWithNewChildren() should reject additionalChildren where first is FilterBy but second is not OrderConstraint")
	void shouldRejectAdditionalChildrenWithWrongSecondType() {
		final ReferenceContent original = referenceContent("a");
		// Pass FilterBy as first additional child and another FilterBy as second -- second is wrong type
		assertThrows(EvitaInvalidUsageException.class, () ->
			original.getCopyWithNewChildren(
				new RequireConstraint[0],
				new Constraint<?>[]{
					filterBy(entityPrimaryKeyInSet(1)),
					filterBy(entityPrimaryKeyInSet(2))
				}
			)
		);
	}

	/**
	 * Creates a named (aliased) reference content instance - there is no factory method for it in
	 * {@link QueryConstraints}, so the internal constructor has to be used directly.
	 *
	 * @param instanceName name of the reference content instance (alias)
	 * @param referenceNames names of the references the instance addresses
	 * @param requirements requirements nested in the instance
	 * @return named reference content instance
	 */
	@Nonnull
	private static ReferenceContent namedReferenceContent(
		@Nonnull String instanceName,
		@Nonnull String[] referenceNames,
		@Nonnull RequireConstraint... requirements
	) {
		return new ReferenceContent(
			instanceName,
			ManagedReferencesBehaviour.ANY,
			referenceNames,
			requirements,
			new Constraint<?>[0]
		);
	}

	@Nested
	@DisplayName("Combining")
	class CombiningTest {

		@Test
		@DisplayName("two requirements for all references share the DEFAULT key")
		void shouldBeCombinableWhenBothRequestAllReferences() {
			assertTrue(referenceContentAll().isCombinableWith(referenceContentAll()));
			assertTrue(referenceContentAll().isCombinableWith(referenceContentAllWithAttributes()));
			assertTrue(referenceContentAll(entityFetchAll()).isCombinableWith(referenceContentAll()));
		}

		@Test
		@DisplayName("two requirements for the same single reference are combinable")
		void shouldBeCombinableWhenSingleReferenceNameIsSame() {
			assertTrue(referenceContent("a").isCombinableWith(referenceContent("a")));
			assertTrue(referenceContent("a", entityFetchAll()).isCombinableWith(referenceContent("a")));
		}

		@Test
		@DisplayName("identical name sets in different order are combinable")
		void shouldBeCombinableWhenNameSetsMatchInDifferentOrder() {
			assertTrue(referenceContent("a", "b").isCombinableWith(referenceContent("b", "a")));
		}

		@Test
		@DisplayName("two instances sharing an alias and a reference name are combinable")
		void shouldBeCombinableWhenInstanceNameAndReferenceNameMatch() {
			assertTrue(
				namedReferenceContent("alias", new String[]{"a"})
					.isCombinableWith(namedReferenceContent("alias", new String[]{"a"}, entityFetchAll()))
			);
		}

		@Test
		@DisplayName("a requirement for all references is not combinable with a name specific one")
		void shouldNotBeCombinableWhenDefaultMeetsSpecificReference() {
			assertFalse(referenceContentAll().isCombinableWith(referenceContent("a")));
			assertFalse(referenceContent("a").isCombinableWith(referenceContentAll()));
		}

		@Test
		@DisplayName("requirements for different references are not combinable")
		void shouldNotBeCombinableWhenReferenceNamesDiffer() {
			assertFalse(referenceContent("a").isCombinableWith(referenceContent("b")));
		}

		@Test
		@DisplayName("overlapping but different name sets are not combinable")
		void shouldNotBeCombinableWhenNameSetsOverlapButDiffer() {
			assertFalse(referenceContent("a", "b").isCombinableWith(referenceContent("b", "c")));
			assertFalse(referenceContent("a", "b").isCombinableWith(referenceContent("a")));
		}

		@Test
		@DisplayName("the same reference under two different aliases is not combinable")
		void shouldNotBeCombinableWhenInstanceNamesDiffer() {
			assertFalse(
				namedReferenceContent("first", new String[]{"a"})
					.isCombinableWith(namedReferenceContent("second", new String[]{"a"}))
			);
		}

		@Test
		@DisplayName("a named instance is not combinable with an unnamed requirement")
		void shouldNotBeCombinableWhenOnlyOneSideIsNamedInstance() {
			assertFalse(namedReferenceContent("alias", new String[]{"a"}).isCombinableWith(referenceContent("a")));
			assertFalse(referenceContent("a").isCombinableWith(namedReferenceContent("alias", new String[]{"a"})));
		}

		@Test
		@DisplayName("a requirement of another kind is never combinable")
		void shouldNotBeCombinableWithDifferentRequirementType() {
			assertFalse(referenceContent("a").isCombinableWith(attributeContentAll()));
			assertFalse(referenceContentAll().isCombinableWith(hierarchyContent()));
		}

		@Test
		@DisplayName("reference attributes are united")
		void shouldCombineAttributeContentsIntoUnion() {
			assertEquals(
				referenceContentWithAttributes("a", attributeContent("code", "name")),
				referenceContentWithAttributes("a", attributeContent("code"))
					.combineWith(referenceContentWithAttributes("a", attributeContent("name")))
			);
		}

		@Test
		@DisplayName("a request for all reference attributes absorbs a named one")
		void shouldLetAllAttributesAbsorbNamedAttributes() {
			assertEquals(
				referenceContentAllWithAttributes(),
				referenceContentAllWithAttributes(attributeContent("code"))
					.combineWith(referenceContentAllWithAttributes())
			);
		}

		@Test
		@DisplayName("nested entity bodies are united recursively")
		void shouldCombineNestedEntityFetchBodies() {
			assertEquals(
				referenceContent("a", entityFetch(attributeContent("code", "name"))),
				referenceContent("a", entityFetch(attributeContent("code")))
					.combineWith(referenceContent("a", entityFetch(attributeContent("name"))))
			);
		}

		@Test
		@DisplayName("nested group entity bodies are united recursively")
		void shouldCombineNestedEntityGroupFetchBodies() {
			assertEquals(
				referenceContent("a", entityGroupFetch(attributeContent("code", "name"))),
				referenceContent("a", entityGroupFetch(attributeContent("code")))
					.combineWith(referenceContent("a", entityGroupFetch(attributeContent("name"))))
			);
		}

		@Test
		@DisplayName("differing managed references behaviour narrows to EXISTING")
		void shouldNarrowManagedReferencesBehaviourToExistingWhenBehavioursDiffer() {
			final ReferenceContent combined = referenceContent(ManagedReferencesBehaviour.ANY, "a")
				.combineWith(referenceContent(ManagedReferencesBehaviour.EXISTING, "a"));

			assertEquals(ManagedReferencesBehaviour.EXISTING, combined.getManagedReferencesBehaviour());
		}

		@Test
		@DisplayName("matching managed references behaviour is kept")
		void shouldKeepManagedReferencesBehaviourWhenBehavioursMatch() {
			final ReferenceContent combined = referenceContent(ManagedReferencesBehaviour.EXISTING, "a")
				.combineWith(referenceContent(ManagedReferencesBehaviour.EXISTING, "a"));

			assertEquals(ManagedReferencesBehaviour.EXISTING, combined.getManagedReferencesBehaviour());
		}

		@Test
		@DisplayName("chunking equal on both sides is retained")
		void shouldRetainChunkingWhenEqualOnBothSides() {
			final ReferenceContent combined = referenceContent("a", entityFetch(attributeContent("code")), page(1, 20))
				.combineWith(referenceContent("a", entityFetch(attributeContent("name")), page(1, 20)));

			assertEquals(page(1, 20), combined.getChunking().orElse(null));
			assertEquals(
				referenceContent("a", entityFetch(attributeContent("code", "name")), page(1, 20)),
				combined
			);
		}

		@Test
		@DisplayName("filter and order equal on both sides are retained")
		void shouldRetainFilterAndOrderWhenEqualOnBothSides() {
			final ReferenceContent combined = referenceContent(
				"a", filterBy(attributeEquals("code", "x")), orderBy(attributeNatural("code")),
				entityFetch(attributeContent("code"))
			).combineWith(
				referenceContent(
					"a", filterBy(attributeEquals("code", "x")), orderBy(attributeNatural("code")),
					entityFetch(attributeContent("name"))
				)
			);

			assertEquals(filterBy(attributeEquals("code", "x")), combined.getFilterBy().orElse(null));
			assertEquals(orderBy(attributeNatural("code")), combined.getOrderBy().orElse(null));
			assertEquals(
				referenceContent(
					"a", filterBy(attributeEquals("code", "x")), orderBy(attributeNatural("code")),
					entityFetch(attributeContent("code", "name"))
				),
				combined
			);
		}

		@Test
		@DisplayName("the instance name survives combining")
		void shouldRetainInstanceNameOfCombinedRequirements() {
			final ReferenceContent combined = namedReferenceContent(
				"alias", new String[]{"a"}, entityFetch(attributeContent("code"))
			).combineWith(
				namedReferenceContent("alias", new String[]{"a"}, entityFetch(attributeContent("name")))
			);

			assertEquals("alias", combined.getInstanceName());
			assertEquals(
				namedReferenceContent("alias", new String[]{"a"}, entityFetch(attributeContent("code", "name"))),
				combined
			);
		}

		@Test
		@DisplayName("two bare requirements for all references collapse into one")
		void shouldCombineTwoDefaultRequirementsIntoDefaultOne() {
			assertEquals(referenceContentAll(), referenceContentAll().combineWith(referenceContentAll()));
		}

		@Test
		@DisplayName("a bare requirement for all references does not swallow the other side's body")
		void shouldCombineDefaultRequirementWithBodyCarryingOne() {
			assertEquals(
				referenceContentAll(entityFetch(attributeContent("code"))),
				referenceContentAll().combineWith(referenceContentAll(entityFetch(attributeContent("code"))))
			);
		}

		@Test
		@DisplayName("differing filter constraints are refused")
		void shouldThrowExceptionWhenFilterConstraintsDiffer() {
			final ReferenceContent first = referenceContent("a", filterBy(attributeEquals("code", "x")));
			final ReferenceContent second = referenceContent("a", filterBy(attributeEquals("code", "y")));

			assertThrows(EvitaInvalidUsageException.class, () -> first.combineWith(second));
		}

		@Test
		@DisplayName("a filter present on a single side only is dropped")
		void shouldDropFilterPresentOnSingleSideOnly() {
			final ReferenceContent first = referenceContent("a", filterBy(attributeEquals("code", "x")));
			final ReferenceContent second = referenceContent("a");

			assertEquals(referenceContent("a"), first.combineWith(second));
			assertEquals(referenceContent("a"), second.combineWith(first));
		}

		@Test
		@DisplayName("differing order constraints are refused")
		void shouldThrowExceptionWhenOrderConstraintsDiffer() {
			final ReferenceContent first = referenceContent("a", orderBy(attributeNatural("code")));
			final ReferenceContent second = referenceContent("a", orderBy(attributeNatural("name")));

			assertThrows(EvitaInvalidUsageException.class, () -> first.combineWith(second));
		}

		@Test
		@DisplayName("an order present on a single side only is retained")
		void shouldKeepOrderPresentOnSingleSideOnly() {
			final ReferenceContent first = referenceContent("a", orderBy(attributeNatural("code")));
			final ReferenceContent second = referenceContent("a");

			assertEquals(referenceContent("a", orderBy(attributeNatural("code"))), first.combineWith(second));
			assertEquals(referenceContent("a", orderBy(attributeNatural("code"))), second.combineWith(first));
		}

		@Test
		@DisplayName("differing chunking constraints are refused")
		void shouldThrowExceptionWhenChunkingConstraintsDiffer() {
			final ReferenceContent first = referenceContent("a", page(1, 20));
			final ReferenceContent second = referenceContent("a", page(2, 20));

			assertThrows(EvitaInvalidUsageException.class, () -> first.combineWith(second));
		}

		@Test
		@DisplayName("chunking present on a single side only is dropped")
		void shouldDropChunkingPresentOnSingleSideOnly() {
			final ReferenceContent first = referenceContent("a", page(1, 20));
			final ReferenceContent second = referenceContent("a");

			assertEquals(referenceContent("a"), first.combineWith(second));
			assertEquals(referenceContent("a"), second.combineWith(first));
		}

		@Test
		@DisplayName("combining requirements with different keys is a programming error")
		void shouldThrowExceptionWhenCombiningDifferentKeys() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> referenceContent("a").combineWith(referenceContent("b"))
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> referenceContentAll().combineWith(referenceContent("a"))
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> namedReferenceContent("alias", new String[]{"a"}).combineWith(referenceContent("a"))
			);
		}

		@Test
		@DisplayName("combining with a requirement of another kind is a programming error")
		void shouldThrowExceptionWhenCombiningWithDifferentRequirementType() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> referenceContent("a").combineWith(attributeContentAll())
			);
		}

	}

	@Nested
	@DisplayName("Containment")
	class ContainmentTest {

		@Test
		@DisplayName("a name specific requirement is contained within one for all references")
		void shouldBeFullyContainedWithinRequirementForAllReferences() {
			assertTrue(referenceContent("a").isFullyContainedWithin(referenceContentAll()));
		}

		@Test
		@DisplayName("a bare requirement for all references is contained within an identical one")
		void shouldBeFullyContainedWithinAnIdenticalRequirementForAllReferences() {
			// this is what lets the prefetch union collapse two bare requirements for all references into one
			assertTrue(referenceContentAll().isFullyContainedWithin(referenceContentAll()));
		}

		@Test
		@DisplayName("names, attributes and bodies forming a subset are contained")
		void shouldBeFullyContainedWhenNamesAndBodiesAreSubset() {
			final ReferenceContent narrower = new ReferenceContent(
				null,
				ManagedReferencesBehaviour.ANY,
				new String[]{"a"},
				new RequireConstraint[]{entityFetch(attributeContent("code")), page(1, 20)},
				new Constraint<?>[]{filterBy(attributeEquals("code", "x")), orderBy(attributeNatural("code"))}
			);
			final ReferenceContent wider = new ReferenceContent(
				null,
				ManagedReferencesBehaviour.ANY,
				new String[]{"a", "b"},
				new RequireConstraint[]{entityFetch(attributeContent("code", "name")), page(1, 20)},
				new Constraint<?>[]{filterBy(attributeEquals("code", "x")), orderBy(attributeNatural("code"))}
			);

			assertTrue(narrower.isFullyContainedWithin(wider));
		}

		@Test
		@DisplayName("differing filter constraints break containment")
		void shouldNotBeFullyContainedWhenFilterConstraintsDiffer() {
			assertFalse(
				referenceContent("a", filterBy(attributeEquals("code", "x")))
					.isFullyContainedWithin(referenceContent("a", filterBy(attributeEquals("code", "y"))))
			);
		}

		@Test
		@DisplayName("a filtered requirement is contained within an unfiltered one")
		void shouldBeFullyContainedWithinRequirementCarryingNoFilter() {
			assertTrue(
				referenceContent("a", filterBy(attributeEquals("code", "x")))
					.isFullyContainedWithin(referenceContentAll())
			);
			assertFalse(
				referenceContent("a")
					.isFullyContainedWithin(referenceContent("a", filterBy(attributeEquals("code", "x"))))
			);
		}

		@Test
		@DisplayName("differing chunking constraints break containment")
		void shouldNotBeFullyContainedWhenChunkingConstraintsDiffer() {
			assertFalse(
				referenceContent("a", page(1, 20)).isFullyContainedWithin(referenceContent("a", page(2, 20)))
			);
		}

		@Test
		@DisplayName("a paged requirement is contained within an unchunked one")
		void shouldBeFullyContainedWithinRequirementCarryingNoChunking() {
			assertTrue(referenceContent("a", page(1, 20)).isFullyContainedWithin(referenceContentAll()));
			assertFalse(referenceContent("a").isFullyContainedWithin(referenceContent("a", page(1, 20))));
		}

		@Test
		@DisplayName("differing order constraints break containment")
		void shouldNotBeFullyContainedWhenOrderConstraintsDiffer() {
			assertFalse(
				referenceContent("a", orderBy(attributeNatural("code")))
					.isFullyContainedWithin(referenceContent("a", orderBy(attributeNatural("name"))))
			);
			assertFalse(
				referenceContent("a", orderBy(attributeNatural("code")))
					.isFullyContainedWithin(referenceContentAll())
			);
		}

		@Test
		@DisplayName("reference attributes outside the other side break containment")
		void shouldNotBeFullyContainedWhenAttributeContentIsNotContained() {
			assertFalse(
				referenceContentWithAttributes("a", attributeContent("code"))
					.isFullyContainedWithin(referenceContent("a"))
			);
			assertFalse(
				referenceContentWithAttributes("a", attributeContent("code"))
					.isFullyContainedWithin(referenceContentWithAttributes("a", attributeContent("name")))
			);
		}

		@Test
		@DisplayName("a richer group body breaks containment")
		void shouldNotBeFullyContainedWhenGroupBodyIsNotContained() {
			assertFalse(
				referenceContent("a", entityGroupFetch(attributeContent("code")))
					.isFullyContainedWithin(referenceContent("a"))
			);
			assertFalse(
				referenceContent("a", entityGroupFetch(attributeContent("code")))
					.isFullyContainedWithin(referenceContent("a", entityGroupFetch(attributeContent("name"))))
			);
		}

		@Test
		@DisplayName("a named instance is never contained")
		void shouldNotBeFullyContainedWhenThisCarriesInstanceName() {
			assertFalse(
				namedReferenceContent("alias", new String[]{"a"}).isFullyContainedWithin(referenceContentAll())
			);
		}

		@Test
		@DisplayName("nothing is ever contained within a named instance")
		void shouldNotBeFullyContainedWhenOtherCarriesInstanceName() {
			assertFalse(
				referenceContent("a").isFullyContainedWithin(namedReferenceContent("alias", new String[]{"a"}))
			);
		}

		@Test
		@DisplayName("a richer body breaks containment")
		void shouldNotBeFullyContainedWhenBodyIsNotContained() {
			assertFalse(
				referenceContent("a", entityFetch(attributeContent("code")))
					.isFullyContainedWithin(referenceContent("a", entityFetch(attributeContent("name"))))
			);
			assertFalse(
				referenceContent("a", entityFetch(attributeContent("code")))
					.isFullyContainedWithin(referenceContent("a"))
			);
		}

		@Test
		@DisplayName("a requirement for all references is not contained within a name specific one")
		void shouldNotBeFullyContainedWhenAllReferencesMeetSpecificOnes() {
			assertFalse(referenceContentAll().isFullyContainedWithin(referenceContent("a")));
		}

		@Test
		@DisplayName("differing managed references behaviour breaks containment")
		void shouldNotBeFullyContainedWhenManagedReferencesBehaviourDiffers() {
			assertFalse(
				referenceContent(ManagedReferencesBehaviour.EXISTING, "a")
					.isFullyContainedWithin(referenceContentAll())
			);
		}

		@Test
		@DisplayName("a requirement of another kind never contains a reference content")
		void shouldNotBeFullyContainedWithinDifferentRequirementType() {
			assertFalse(referenceContent("a").isFullyContainedWithin(attributeContentAll()));
		}

	}

}
