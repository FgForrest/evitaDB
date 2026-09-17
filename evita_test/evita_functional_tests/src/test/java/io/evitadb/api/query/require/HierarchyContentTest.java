/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REQUIRE;
import static io.evitadb.test.TestTags.HIERARCHY;

/**
 * This tests verifies basic properties of {@link HierarchyContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Tag(CONTRACT)
@Tag(REQUIRE)
@Tag(HIERARCHY)
class HierarchyContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyContent hierarchyContent1 = hierarchyContent();
		assertTrue(hierarchyContent1.getStopAt().isEmpty());
		assertTrue(hierarchyContent1.getEntityFetch().isEmpty());

		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(1)));
		assertEquals(stopAt(distance(1)), hierarchyContent2.getStopAt().orElse(null));
		assertTrue(hierarchyContent2.getEntityFetch().isEmpty());

		final HierarchyContent hierarchyContent3 = hierarchyContent(entityFetch());
		assertTrue(hierarchyContent3.getStopAt().isEmpty());
		assertEquals(entityFetch(), hierarchyContent3.getEntityFetch().orElse(null));

		final HierarchyContent hierarchyContent4 = hierarchyContent(
			stopAt(distance(1)), entityFetch()
		);
		assertEquals(stopAt(distance(1)), hierarchyContent4.getStopAt().orElse(null));
		assertEquals(entityFetch(), hierarchyContent4.getEntityFetch().orElse(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(hierarchyContent().isApplicable());
		assertTrue(hierarchyContent(stopAt(distance(1))).isApplicable());
		assertTrue(hierarchyContent(entityFetch(attributeContentAll())).isApplicable());
		assertTrue(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyContent hierarchyContent1 = hierarchyContent();
		assertEquals("hierarchyContent()", hierarchyContent1.toString());

		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(1)));
		assertEquals("hierarchyContent(stopAt(distance(1)))", hierarchyContent2.toString());

		final HierarchyContent hierarchyContent3 = hierarchyContent(entityFetch(attributeContentAll()));
		assertEquals("hierarchyContent(entityFetch(attributeContentAll()))", hierarchyContent3.toString());

		final HierarchyContent hierarchyContent4 = hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll()));
		assertEquals("hierarchyContent(stopAt(distance(1)),entityFetch(attributeContentAll()))", hierarchyContent4.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyContent(), hierarchyContent());
		assertEquals(hierarchyContent(), hierarchyContent());
		assertEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(stopAt(distance(1))));
		assertEquals(hierarchyContent(entityFetch(attributeContentAll())), hierarchyContent(entityFetch(attributeContentAll())));
		assertEquals(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())));
		assertNotEquals(hierarchyContent(), hierarchyContent(stopAt(distance(1))));
		assertNotEquals(hierarchyContent(), hierarchyContent(entityFetch(attributeContentAll())));
		assertNotEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(entityFetch(attributeContentAll())));
		assertNotEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(stopAt(distance(2))));
		assertEquals(hierarchyContent().hashCode(), hierarchyContent().hashCode());
		assertEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(stopAt(distance(1))).hashCode());
		assertEquals(hierarchyContent(entityFetch(attributeContentAll())).hashCode(), hierarchyContent(entityFetch(attributeContentAll())).hashCode());
		assertEquals(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())).hashCode(), hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())).hashCode());
		assertNotEquals(hierarchyContent().hashCode(), hierarchyContent(stopAt(distance(1))).hashCode());
		assertNotEquals(hierarchyContent().hashCode(), hierarchyContent(entityFetch(attributeContentAll())).hashCode());
		assertNotEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(entityFetch(attributeContentAll())).hashCode());
		assertNotEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(stopAt(distance(2))).hashCode());
	}

	@Test
	void shouldFullyContainWhenHierarchyContentIsEmpty() {
		assertTrue(hierarchyContent().isFullyContainedWithin(hierarchyContent()));
	}

	@Test
	void shouldNotFullyContainWhenStopAtIsPresentInHierarchyContent() {
		assertFalse(hierarchyContent(stopAt(distance(1))).isFullyContainedWithin(hierarchyContent()));
	}

	@Test
	void shouldFullyContainWhenEntityFetchIsPresentInBothHierarchyContents() {
		assertTrue(hierarchyContent(entityFetch(attributeContent())).isFullyContainedWithin(hierarchyContent(entityFetchAll())));
	}

	@Test
	void shouldNotFullyContainWhenEntityFetchIsOnlyInOneHierarchyContent() {
		assertFalse(hierarchyContent(entityFetchAll()).isFullyContainedWithin(hierarchyContent()));
	}

	@Test
	void shouldReturnEmptyStopAtWhenNotPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent();
		assertFalse(hierarchyContent.getStopAt().isPresent());
	}

	@Test
	void shouldReturnEmptyEntityFetchWhenNotPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent();
		assertFalse(hierarchyContent.getEntityFetch().isPresent());
	}

	@Test
	void shouldReturnStopAtWhenPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent(stopAt(distance(1)));
		assertTrue(hierarchyContent.getStopAt().isPresent());
		assertEquals(stopAt(distance(1)), hierarchyContent.getStopAt().orElse(null));
	}

	@Test
	void shouldReturnEntityFetchWhenPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent(entityFetch(attributeContentAll()));
		assertTrue(hierarchyContent.getEntityFetch().isPresent());
		assertEquals(entityFetch(attributeContentAll()), hierarchyContent.getEntityFetch().orElse(null));
	}

	@Test
	void shouldCombineWithAnotherHierarchyContent() {
		final HierarchyContent hierarchyContent1 = hierarchyContent(entityFetch(attributeContentAll()));
		final HierarchyContent hierarchyContent2 = hierarchyContent(entityFetch(associatedDataContentAll()));
		final HierarchyContent combined = hierarchyContent1.combineWith(hierarchyContent2);
		assertTrue(combined.getEntityFetch().isPresent());
		assertEquals(entityFetch(attributeContentAll(), associatedDataContentAll()), combined.getEntityFetch().orElse(null));
	}

	@Test
	void shouldNotCombineWithDifferentRequirement() {
		final HierarchyContent hierarchyContent = hierarchyContent();
		assertThrows(GenericEvitaInternalError.class, () -> hierarchyContent.combineWith(attributeContent()));
	}

	@Test
	void shouldThrowWhenCombiningWithStopAt() {
		final HierarchyContent hierarchyContent1 = hierarchyContent(stopAt(distance(1)));
		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(2)));
		assertThrows(EvitaInvalidUsageException.class, () -> hierarchyContent1.combineWith(hierarchyContent2));
	}

	@Test
	@DisplayName("a stop constraint present on a single side only is dropped")
	void shouldDropStopAtPresentOnSingleSideOnly() {
		final HierarchyContent bounded = hierarchyContent(stopAt(distance(1)));
		final HierarchyContent unbounded = hierarchyContent();

		assertEquals(hierarchyContent(), bounded.combineWith(unbounded));
		assertEquals(hierarchyContent(), unbounded.combineWith(bounded));
	}

	@Test
	@DisplayName("an equal stop constraint present on both sides is kept")
	void shouldKeepStopAtWhenEqualOnBothSides() {
		final HierarchyContent first = hierarchyContent(stopAt(distance(1)));
		final HierarchyContent second = hierarchyContent(stopAt(distance(1)));

		assertEquals(hierarchyContent(stopAt(distance(1))), first.combineWith(second));
	}

	@Test
	@DisplayName("the stop constraint is dropped for the prefetch, the parent bodies are not")
	void shouldDropStopAtForPrefetch() {
		final HierarchyContent bounded = hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent("code")));

		final HierarchyContent prefetched = bounded.forPrefetch();

		assertTrue(prefetched.getStopAt().isEmpty());
		assertEquals(hierarchyContent(entityFetch(attributeContent("code"))), prefetched);
	}

	@Test
	@DisplayName("a requirement carrying no stop constraint is handed back unchanged")
	void shouldReturnSameInstanceWhenNoStopAtIsCarried() {
		final HierarchyContent unbounded = hierarchyContent(entityFetch(attributeContent("code")));

		assertSame(unbounded, unbounded.forPrefetch());
	}

	@Test
	@DisplayName("cloneWithArguments() should return new instance, not this")
	void shouldReturnNewInstanceFromCloneWithArguments() {
		final HierarchyContent original = hierarchyContent(stopAt(distance(1)), entityFetch());
		final HierarchyContent cloned = (HierarchyContent) original.cloneWithArguments(new Serializable[0]);
		assertNotSame(original, cloned);
		assertEquals(original, cloned);
	}

	/**
	 * Consolidates the rows that pin the parents-behaviour argument the constraint gained for #1365 -
	 * how every constructor carries it, how it is printed, how two constraints combine, and what the
	 * descriptor-driven contract it newly implements reports about it.
	 *
	 * The flat rows above are the constraint's pre-existing surface and stay where they are.
	 */
	@Nested
	@DisplayName("parents behaviour")
	class ParentsBehaviourTest {

		@Test
		@DisplayName("parents behaviour defaults to MATCHING on every constructor that omits it")
		void shouldDefaultParentsBehaviourToMatching() {
			assertEquals(HierarchyParentsBehaviour.MATCHING, hierarchyContent().getParentsBehaviour());
			assertEquals(
				HierarchyParentsBehaviour.MATCHING,
				hierarchyContent(stopAt(distance(1))).getParentsBehaviour()
			);
			assertEquals(HierarchyParentsBehaviour.MATCHING, hierarchyContent(entityFetchAll()).getParentsBehaviour());
			assertEquals(
				HierarchyParentsBehaviour.MATCHING,
				hierarchyContent(stopAt(distance(1)), entityFetchAll()).getParentsBehaviour()
			);
			assertEquals(
				HierarchyParentsBehaviour.MATCHING,
				new HierarchyContent((HierarchyParentsBehaviour) null).getParentsBehaviour()
			);
			assertEquals(HierarchyParentsBehaviour.MATCHING, HierarchyContent.DEFAULT_PARENTS_BEHAVIOUR);
		}

		@Test
		@DisplayName("every constructor taking a parents behaviour carries it through")
		void shouldCreateWithParentsBehaviour() {
			final HierarchyContent hierarchyContent1 = hierarchyContent(HierarchyParentsBehaviour.COMPLETE);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, hierarchyContent1.getParentsBehaviour());
			assertTrue(hierarchyContent1.getStopAt().isEmpty());
			assertTrue(hierarchyContent1.getEntityFetch().isEmpty());

			final HierarchyContent hierarchyContent2 = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1))
			);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, hierarchyContent2.getParentsBehaviour());
			assertEquals(stopAt(distance(1)), hierarchyContent2.getStopAt().orElse(null));
			assertTrue(hierarchyContent2.getEntityFetch().isEmpty());

			final HierarchyContent hierarchyContent3 = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, entityFetchAll()
			);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, hierarchyContent3.getParentsBehaviour());
			assertTrue(hierarchyContent3.getStopAt().isEmpty());
			assertEquals(entityFetchAll(), hierarchyContent3.getEntityFetch().orElse(null));

			final HierarchyContent hierarchyContent4 = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1)), entityFetchAll()
			);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, hierarchyContent4.getParentsBehaviour());
			assertEquals(stopAt(distance(1)), hierarchyContent4.getStopAt().orElse(null));
			assertEquals(entityFetchAll(), hierarchyContent4.getEntityFetch().orElse(null));
		}

		@Test
		@DisplayName("toString() hides the default behaviour and prints an explicit non-default one")
		void shouldToStringCarryParentsBehaviour() {
			assertEquals("hierarchyContent()", hierarchyContent(HierarchyParentsBehaviour.MATCHING).toString());
			assertEquals("hierarchyContent(COMPLETE)", hierarchyContent(HierarchyParentsBehaviour.COMPLETE).toString());
			assertEquals(
				"hierarchyContent(COMPLETE,stopAt(distance(1)))",
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1))).toString()
			);
			assertEquals(
				"hierarchyContent(COMPLETE,stopAt(distance(1)),entityFetch(attributeContentAll()))",
				hierarchyContent(
					HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1)), entityFetch(attributeContentAll())
				).toString()
			);
		}

		@Test
		@DisplayName("equals/hashCode take the parents behaviour into account")
		void shouldConformToEqualsAndHashContractForParentsBehaviour() {
			assertEquals(hierarchyContent(HierarchyParentsBehaviour.MATCHING), hierarchyContent());
			assertEquals(
				hierarchyContent(HierarchyParentsBehaviour.MATCHING).hashCode(), hierarchyContent().hashCode()
			);
			assertEquals(
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetchAll()),
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetchAll())
			);
			assertNotEquals(hierarchyContent(HierarchyParentsBehaviour.COMPLETE), hierarchyContent());
			assertNotEquals(
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE).hashCode(), hierarchyContent().hashCode()
			);
		}

		@Test
		@DisplayName("cloneWithArguments() carries an explicit behaviour and defaults on an empty array")
		void shouldCloneWithParentsBehaviourArgument() {
			final HierarchyContent original = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1)), entityFetchAll()
			);

			final HierarchyContent clonedWithoutArguments =
				(HierarchyContent) original.cloneWithArguments(new Serializable[0]);
			assertEquals(HierarchyParentsBehaviour.MATCHING, clonedWithoutArguments.getParentsBehaviour());
			assertEquals(stopAt(distance(1)), clonedWithoutArguments.getStopAt().orElse(null));
			assertEquals(entityFetchAll(), clonedWithoutArguments.getEntityFetch().orElse(null));

			final HierarchyContent clonedWithBehaviour = (HierarchyContent) original.cloneWithArguments(
				new Serializable[]{HierarchyParentsBehaviour.COMPLETE}
			);
			assertEquals(original, clonedWithBehaviour);
			assertNotSame(original, clonedWithBehaviour);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{"COMPLETE"})
			);
		}

		@Test
		@DisplayName("getCopyWithNewChildren() keeps the parents behaviour")
		void shouldCopyWithNewChildrenKeepParentsBehaviour() {
			final HierarchyContent original = hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetchAll());
			final HierarchyContent copy = (HierarchyContent) original.getCopyWithNewChildren(
				new RequireConstraint[]{stopAt(distance(2))}, new Constraint<?>[0]
			);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, copy.getParentsBehaviour());
			assertEquals(stopAt(distance(2)), copy.getStopAt().orElse(null));
			assertTrue(copy.getEntityFetch().isEmpty());
		}

		@Test
		@DisplayName("combineWith() throws when both sides request ancestor bodies under different behaviours")
		void shouldThrowWhenCombiningConflictingParentsBehaviours() {
			final HierarchyContent complete = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContentAll())
			);
			final HierarchyContent matching = hierarchyContent(
				HierarchyParentsBehaviour.MATCHING, entityFetch(associatedDataContentAll())
			);
			assertThrows(EvitaInvalidUsageException.class, () -> complete.combineWith(matching));
			assertThrows(EvitaInvalidUsageException.class, () -> matching.combineWith(complete));
		}

		@Test
		@DisplayName("combineWith() ignores a side that requests no ancestor bodies, in either order")
		void shouldCombineWhenOneSideRequestsNoAncestorBodies() {
			// this is what `entityFetchAll()` produces - a bare hierarchyContent() carrying the default behaviour
			final HierarchyContent bare = hierarchyContent();
			final HierarchyContent complete = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContentAll())
			);

			final HierarchyContent combined1 = bare.combineWith(complete);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, combined1.getParentsBehaviour());
			assertEquals(entityFetch(attributeContentAll()), combined1.getEntityFetch().orElse(null));

			final HierarchyContent combined2 = complete.combineWith(bare);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, combined2.getParentsBehaviour());
			assertEquals(entityFetch(attributeContentAll()), combined2.getEntityFetch().orElse(null));

			// a body-less side carrying an explicit COMPLETE is inert too and never overrides the side that has bodies
			final HierarchyContent bareComplete = hierarchyContent(HierarchyParentsBehaviour.COMPLETE);
			final HierarchyContent matching = hierarchyContent(
				HierarchyParentsBehaviour.MATCHING, entityFetch(attributeContentAll())
			);
			assertEquals(HierarchyParentsBehaviour.MATCHING, bareComplete.combineWith(matching).getParentsBehaviour());
			assertEquals(HierarchyParentsBehaviour.MATCHING, matching.combineWith(bareComplete).getParentsBehaviour());
		}

		@Test
		@DisplayName("combineWith() keeps a matching behaviour when both sides request ancestor bodies")
		void shouldCombineEqualParentsBehaviours() {
			final HierarchyContent hierarchyContent1 = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContentAll())
			);
			final HierarchyContent hierarchyContent2 = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, entityFetch(associatedDataContentAll())
			);
			final HierarchyContent combined = hierarchyContent1.combineWith(hierarchyContent2);
			assertEquals(HierarchyParentsBehaviour.COMPLETE, combined.getParentsBehaviour());
			assertEquals(
				entityFetch(attributeContentAll(), associatedDataContentAll()),
				combined.getEntityFetch().orElse(null)
			);
		}

		@Test
		@DisplayName("isFullyContainedWithin() is false when both sides request bodies under different behaviours")
		void shouldNotFullyContainWhenParentsBehavioursDiffer() {
			final HierarchyContent complete = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContent())
			);
			final HierarchyContent matching = hierarchyContent(HierarchyParentsBehaviour.MATCHING, entityFetchAll());
			assertFalse(complete.isFullyContainedWithin(matching));
			assertFalse(matching.isFullyContainedWithin(complete));

			// the same shapes under one behaviour stay contained
			assertTrue(
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContent()))
					.isFullyContainedWithin(hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetchAll()))
			);
			// a body-less side expresses no behaviour, so it is contained regardless
			assertTrue(hierarchyContent(HierarchyParentsBehaviour.COMPLETE).isFullyContainedWithin(hierarchyContent()));
		}

		@Test
		@DisplayName("combineWith() converges on one behaviour when neither side requests ancestor bodies")
		void shouldCombineTwoBodyLessConstraintsIndependentlyOfOperandOrder() {
			final HierarchyContent matching = hierarchyContent(HierarchyParentsBehaviour.MATCHING);
			final HierarchyContent complete = hierarchyContent(HierarchyParentsBehaviour.COMPLETE);

			// neither side requests ancestor bodies, so neither expresses a preference and the combination has to
			// land on one value regardless of which operand it started from
			assertEquals(
				HierarchyContent.DEFAULT_PARENTS_BEHAVIOUR,
				matching.combineWith(complete).getParentsBehaviour()
			);
			assertEquals(
				HierarchyContent.DEFAULT_PARENTS_BEHAVIOUR,
				complete.combineWith(matching).getParentsBehaviour()
			);
			assertEquals(matching.combineWith(complete), complete.combineWith(matching));
		}

		@Test
		@DisplayName("combineWith() drops a stopAt bound that only one side carries")
		void shouldDropTheBoundWhenOnlyOneSideCarriesIt() {
			// the unbounded caller asked for the whole chain, and a bound the other caller asked for would return
			// less than that - an absent bound is the superset, so it is the one that satisfies both
			final HierarchyContent unbounded = hierarchyContent(HierarchyParentsBehaviour.COMPLETE);
			final HierarchyContent bounded = hierarchyContent(
				HierarchyParentsBehaviour.MATCHING, stopAt(distance(1)), entityFetch(attributeContentAll())
			);

			assertTrue(unbounded.combineWith(bounded).getStopAt().isEmpty());
			assertTrue(bounded.combineWith(unbounded).getStopAt().isEmpty());
			assertEquals(unbounded.combineWith(bounded), bounded.combineWith(unbounded));

			// an identical bound on both sides is what both callers asked for and survives
			final HierarchyContent boundedTwin = hierarchyContent(
				HierarchyParentsBehaviour.MATCHING, stopAt(distance(1)), entityFetch(associatedDataContentAll())
			);
			assertEquals(stopAt(distance(1)), bounded.combineWith(boundedTwin).getStopAt().orElse(null));
			assertEquals(stopAt(distance(1)), boundedTwin.combineWith(bounded).getStopAt().orElse(null));
		}

		@Test
		@DisplayName("isFullyContainedWithin() refuses a MATCHING container that may cut the chain")
		void shouldNotContainTheBareFormInAMatchingContainer() {
			// the bare form reports every parent primary key; a MATCHING container cuts the chain below an ancestor
			// whose body does not materialize, so it returns fewer keys and cannot stand in for the bare form
			final HierarchyContent bare = hierarchyContent();
			assertFalse(
				bare.isFullyContainedWithin(
					hierarchyContent(HierarchyParentsBehaviour.MATCHING, entityFetch(attributeContentAll()))
				)
			);
			// COMPLETE keeps every key and only adds bodies to them, so it does contain the bare form
			assertTrue(
				bare.isFullyContainedWithin(
					hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContentAll()))
				)
			);
			// the opposite direction is unchanged - a container asking for no bodies cannot satisfy a body request
			assertFalse(
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContentAll()))
					.isFullyContainedWithin(bare)
			);
		}

		@Test
		@DisplayName("cloneWithArguments() refuses an argument array of the wrong arity")
		void shouldRefuseWrongArityInCloneWithArguments() {
			final HierarchyContent original = hierarchyContent(
				HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1)), entityFetchAll()
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(
					new Serializable[]{HierarchyParentsBehaviour.COMPLETE, HierarchyParentsBehaviour.MATCHING}
				)
			);
		}

		@Test
		@DisplayName("the default behaviour is reported as an implicit argument and omitted from the argument array")
		void shouldReportTheDefaultBehaviourAsImplicit() {
			// this is what every descriptor-driven external API layer reads, and today only `toString()`
			// reaches it - so the contract is asserted directly rather than through its one consumer
			assertArrayEquals(new Serializable[0], hierarchyContent().getArgumentsExcludingDefaults());
			assertArrayEquals(
				new Serializable[]{HierarchyParentsBehaviour.COMPLETE},
				hierarchyContent(HierarchyParentsBehaviour.COMPLETE).getArgumentsExcludingDefaults()
			);
			assertTrue(hierarchyContent().isArgumentImplicit(HierarchyParentsBehaviour.MATCHING));
			assertFalse(hierarchyContent().isArgumentImplicit(HierarchyParentsBehaviour.COMPLETE));
		}
	}

}
