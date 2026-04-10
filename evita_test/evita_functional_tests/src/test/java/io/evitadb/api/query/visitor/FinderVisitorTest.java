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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.visitor.FinderVisitor.MoreThanSingleResultException;
import io.evitadb.api.query.visitor.FinderVisitor.PredicateWithDescription;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FinderVisitor} verifying constraint tree search with matcher and stopper predicates,
 * including traversal of both primary and additional children.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FinderVisitor functionality")
class FinderVisitorTest {
	/**
	 * Shared filter tree: `and(attributeEquals("a","b"), or(isNotNull("def"), equalsTrue("xev"),
	 * between("c",1,78), not(equalsTrue("utr"))))`.
	 */
	private FilterConstraint filterConstraint;
	/**
	 * Shared require tree: `require(page(1,20), referenceContent("a", filterBy(equalsTrue("xev")),
	 * entityFetch(attributeContentAll())))` — `filterBy` is an additional child of `referenceContent`.
	 */
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

	@Nested
	@DisplayName("Single constraint search — findConstraint()")
	class SingleConstraintSearchTest {

		@Test
		@DisplayName("Should return null when no constraint matches predicate")
		void shouldReturnNullWhenNoConstraintMatches() {
			final FilterConstraint result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.filterConstraint,
				AttributeStartsWith.class::isInstance
			);

			assertNull(result);
		}

		@Test
		@DisplayName("Should find deeply nested constraint by type")
		void shouldFindDeeplyNestedConstraintByType() {
			final AttributeBetween result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.filterConstraint,
				AttributeBetween.class::isInstance
			);

			assertEquals(attributeBetween("c", 1, 78), result);
		}

		@Test
		@DisplayName("Should find constraint by inspecting its arguments")
		void shouldFindConstraintByArguments() {
			final Constraint<?> result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.filterConstraint,
				fc -> {
					final Serializable[] args = fc.getArguments();
					return args.length >= 1 && "c".equals(args[0]);
				}
			);

			assertEquals(attributeBetween("c", 1, 78), result);
		}

		@Test
		@DisplayName("Should find constraint located inside additional children subtree")
		void shouldFindConstraintInsideAdditionalChildren() {
			// attributeEqualsTrue("xev") sits inside filterBy which is an additional child of referenceContent
			final Constraint<?> result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.requireConstraint,
				fc -> {
					final Serializable[] args = fc.getArguments();
					return args.length >= 1 && "xev".equals(args[0]);
				}
			);

			assertEquals(attributeEqualsTrue("xev"), result);
		}

		@Test
		@DisplayName("Should match root constraint when it satisfies the predicate")
		void shouldMatchRootConstraintItself() {
			final Constraint<?> result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.filterConstraint,
				And.class::isInstance
			);

			assertNotNull(result);
			assertInstanceOf(And.class, result);
		}

		@Test
		@DisplayName("Should return null for single leaf constraint that does not match")
		void shouldReturnNullForNonMatchingLeaf() {
			final FilterConstraint leaf = attributeEquals("name", "value");

			final Constraint<?> result = FinderVisitor.findConstraint(
				leaf,
				Or.class::isInstance
			);

			assertNull(result);
		}

		@Test
		@DisplayName("Should match leaf constraint that satisfies the predicate")
		void shouldMatchLeafConstraintDirectly() {
			final FilterConstraint leaf = attributeEquals("name", "value");

			final AttributeEquals result = FinderVisitor.findConstraint(
				leaf,
				AttributeEquals.class::isInstance
			);

			assertEquals(attributeEquals("name", "value"), result);
		}

		@Test
		@DisplayName("Should return null when stopper blocks root preventing descent")
		void shouldReturnNullWhenStopperBlocksEverything() {
			final FilterConstraint result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.filterConstraint,
				AttributeBetween.class::isInstance,
				And.class::isInstance
			);

			// And is the root — stopper prevents descent, and And itself is not an AttributeBetween
			assertNull(result);
		}

		@Test
		@DisplayName("Should find single result with stopper that allows partial traversal")
		void shouldFindSingleResultWithPartialStopper() {
			final FilterConstraint constraint = and(
				attributeEquals("outside", "value"),
				or(
					attributeEquals("inside", "value")
				)
			);

			// Stopper stops at Or, so only "outside" AttributeEquals is reachable
			final AttributeEquals result = FinderVisitor.findConstraint(
				constraint,
				AttributeEquals.class::isInstance,
				Or.class::isInstance
			);

			assertNotNull(result);
			assertEquals("outside", result.getAttributeName());
		}

		@Test
		@DisplayName("Should find single result from additional children when stopper blocks primary")
		void shouldFindSingleResultFromAdditionalChildrenWhenStopperBlocksPrimary() {
			// requireConstraint: require(page(1,20), referenceContent("a", filterBy(...), entityFetch(...)))
			// Stopper blocks EntityFetch (a primary child of ReferenceContent), but FilterBy (additional child) is
			// unblocked — the stopper only prevents descent into the stopped node's children, not siblings
			final FilterBy result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.requireConstraint,
				FilterBy.class::isInstance,
				EntityFetch.class::isInstance
			);

			assertNotNull(result);
			assertInstanceOf(FilterBy.class, result);
		}
	}

	@Nested
	@DisplayName("Multiple constraints search — findConstraints()")
	class MultipleConstraintsSearchTest {

		@Test
		@DisplayName("Should find all constraints matching type predicate")
		void shouldFindAllMatchingConstraints() {
			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.filterConstraint,
				fc -> fc instanceof final AttributeEquals eq && eq.getAttributeValue().equals(true)
			);

			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("Should return empty list when nothing matches")
		void shouldReturnEmptyListWhenNothingMatches() {
			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.filterConstraint,
				AttributeStartsWith.class::isInstance
			);

			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("Should find constraints at different tree depths")
		void shouldFindConstraintsAtDifferentDepths() {
			// Build tree with AttributeEquals at depth 1, 2, and 3
			final FilterConstraint constraint = and(
				attributeEquals("depth1", "v"),
				or(
					attributeEquals("depth2", "v"),
					not(
						attributeEquals("depth3", "v")
					)
				)
			);

			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance
			);

			assertEquals(3, found.size());
		}

		@Test
		@DisplayName("Should include all container types in results when matching by container")
		void shouldFindContainerConstraints() {
			// filterConstraint: and(attributeEquals, or(isNotNull, equalsTrue, between, not(equalsTrue)))
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.filterConstraint,
				fc -> fc instanceof And || fc instanceof Or || fc instanceof Not
			);

			// And at root, Or as child, Not inside Or
			assertEquals(3, found.size());
			assertInstanceOf(And.class, found.get(0));
			assertInstanceOf(Or.class, found.get(1));
			assertInstanceOf(Not.class, found.get(2));
		}

		@Test
		@DisplayName("Should find single leaf when searched in a leaf constraint")
		void shouldFindSingleLeafWhenSearchingLeaf() {
			final FilterConstraint leaf = attributeEquals("x", 42);

			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				leaf,
				AttributeEquals.class::isInstance
			);

			assertEquals(1, found.size());
			assertEquals(attributeEquals("x", 42), found.get(0));
		}

		@Test
		@DisplayName("Should collect all nodes when using a universal matcher")
		void shouldCollectAllNodesWithUniversalMatcher() {
			// Simple tree: and(eq("a","v"), eq("b","v"))
			// Nodes: And, AttributeEquals("a"), AttributeEquals("b")
			final FilterConstraint constraint = and(
				attributeEquals("a", "v"),
				attributeEquals("b", "v")
			);

			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				constraint,
				c -> true
			);

			assertEquals(3, found.size());
			assertInstanceOf(And.class, found.get(0));
			assertInstanceOf(AttributeEquals.class, found.get(1));
			assertInstanceOf(AttributeEquals.class, found.get(2));
		}
	}

	@Nested
	@DisplayName("Stopper predicate behavior")
	class StopperPredicateTest {

		@Test
		@DisplayName("Should prevent searching inside stopped container")
		void shouldPreventSearchingInsideStoppedContainer() {
			final FilterConstraint constraint = and(
				attributeEquals("outside", "value"),
				or(
					attributeEquals("inside1", "value"),
					attributeEquals("inside2", "value")
				)
			);

			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance,
				Or.class::isInstance
			);

			// Only "outside" should be found — Or and its children are blocked
			assertEquals(1, found.size());
			final AttributeEquals foundConstraint = (AttributeEquals) found.get(0);
			assertEquals("outside", foundConstraint.getAttributeName());
		}

		@Test
		@DisplayName("Should return empty list when root itself triggers stopper")
		void shouldReturnEmptyListWhenRootTriggersStopper() {
			final FilterConstraint constraint = or(
				attributeEquals("inside1", "value"),
				attributeEquals("inside2", "value")
			);

			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance,
				Or.class::isInstance
			);

			// Or is root — stopper prevents descent, and Or does not match the matcher
			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("Should include node that matches BOTH matcher and stopper but skip its children")
		void shouldIncludeNodeMatchingBothMatcherAndStopperButSkipChildren() {
			final FilterConstraint constraint = and(
				attributeEquals("sibling", "v"),
				or(
					attributeEquals("child-of-or", "v")
				)
			);

			// Or matches both the matcher (looking for container types) and the stopper
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				constraint,
				fc -> fc instanceof Or || fc instanceof AttributeEquals,
				Or.class::isInstance
			);

			// Should find: attributeEquals("sibling") and Or itself
			// Should NOT find: attributeEquals("child-of-or") because stopper blocks descent into Or
			assertEquals(2, found.size());
			assertInstanceOf(AttributeEquals.class, found.get(0));
			assertInstanceOf(Or.class, found.get(1));
			assertEquals("sibling", ((AttributeEquals) found.get(0)).getAttributeName());
		}

		@Test
		@DisplayName("Should stop at multiple independent stopper nodes")
		void shouldStopAtMultipleIndependentStopperNodes() {
			final FilterConstraint constraint = and(
				or(
					attributeEquals("in-or", "v")
				),
				not(
					attributeEquals("in-not", "v")
				)
			);

			// Stopper on both Or and Not
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance,
				fc -> fc instanceof Or || fc instanceof Not
			);

			// Both Or and Not are stopped — no AttributeEquals should be found
			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("Should not stop when stopper does not match any traversed container")
		void shouldNotStopWhenStopperMatchesNothing() {
			final FilterConstraint constraint = and(
				attributeEquals("a", "v"),
				or(
					attributeEquals("b", "v")
				)
			);

			// Stopper targets Not (which does not appear in the tree)
			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance,
				Not.class::isInstance
			);

			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("Should have no effect when stopper matches leaf constraints")
		void shouldHaveNoEffectWhenStopperMatchesLeaf() {
			// Leaf constraints have no children to descend into, so stopper is a no-op
			final FilterConstraint constraint = and(
				attributeEquals("a", "v"),
				attributeEquals("b", "v")
			);

			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance,
				AttributeEquals.class::isInstance
			);

			// Both leaves are matched by the matcher (which runs first), stopper only prevents
			// child traversal — but leaves have no children, so both are still found
			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("Should stop based on argument-inspecting predicate, not only type")
		void shouldStopBasedOnArgumentInspectingPredicate() {
			final FilterConstraint constraint = and(
				or(
					attributeEquals("blocked-child", "v")
				),
				not(
					attributeEquals("allowed-child", "v")
				)
			);

			// Stopper inspects arguments: stop only at Or but not at Not
			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance,
				Or.class::isInstance
			);

			// "blocked-child" inside Or is unreachable, but "allowed-child" inside Not is found
			assertEquals(1, found.size());
			assertEquals("allowed-child", found.get(0).getAttributeName());
		}
	}

	@Nested
	@DisplayName("Additional children traversal")
	class AdditionalChildrenTest {

		@Test
		@DisplayName("Should find FilterBy container that is an additional child of ReferenceContent")
		void shouldFindFilterByAsAdditionalChild() {
			final FilterBy result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.requireConstraint,
				FilterBy.class::isInstance
			);

			assertNotNull(result);
			assertInstanceOf(FilterBy.class, result);
		}

		@Test
		@DisplayName("Should traverse into additional child subtrees to find deeply nested constraints")
		void shouldTraverseIntoAdditionalChildSubtrees() {
			// requireConstraint = require(page(1,20), referenceContent("a", filterBy(equalsTrue("xev")),
			//                     entityFetch(...)))
			// equalsTrue("xev") is inside filterBy, which is an additional child of referenceContent
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.requireConstraint,
				AttributeEquals.class::isInstance
			);

			// The attributeEqualsTrue("xev") inside the additional-child filterBy
			assertEquals(1, found.size());
			final AttributeEquals foundEquals = (AttributeEquals) found.get(0);
			final Serializable[] args = foundEquals.getArguments();
			assertEquals("xev", args[0]);
		}

		@Test
		@DisplayName("Should find constraints from both primary and additional children")
		void shouldFindConstraintsFromBothPrimaryAndAdditionalChildren() {
			// referenceContent has primary children (EntityFetch, etc.) and additional children (FilterBy)
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.requireConstraint,
				fc -> fc instanceof EntityFetch || fc instanceof FilterBy
			);

			// Should find the FilterBy (additional child) and EntityFetch (primary child) of referenceContent
			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("Should find AttributeContent nested inside EntityFetch (primary child of ReferenceContent)")
		void shouldFindAttributeContentInsideEntityFetch() {
			final AttributeContent result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.requireConstraint,
				AttributeContent.class::isInstance
			);

			assertNotNull(result);
		}

		@Test
		@DisplayName("Should stop traversal into additional children when stopper matches the container")
		void shouldStopTraversalIntoAdditionalChildrenWhenStopperMatchesContainer() {
			// Stopper blocks ReferenceContent — so FilterBy and EntityFetch inside it are unreachable
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.requireConstraint,
				fc -> fc instanceof FilterBy || fc instanceof AttributeEquals,
				ReferenceContent.class::isInstance
			);

			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("Should visit primary children before additional children")
		void shouldVisitPrimaryChildrenBeforeAdditionalChildren() {
			// referenceContent("a", filterBy(equalsTrue("xev")), entityFetch(attributeContentAll()))
			// Primary child: EntityFetch, Additional child: FilterBy
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.requireConstraint,
				fc -> fc instanceof EntityFetch || fc instanceof FilterBy
			);

			// EntityFetch is a primary child and should appear before FilterBy (additional child)
			assertEquals(2, found.size());
			assertInstanceOf(EntityFetch.class, found.get(0));
			assertInstanceOf(FilterBy.class, found.get(1));
		}
	}

	@Nested
	@DisplayName("Traversal order")
	class TraversalOrderTest {

		@Test
		@DisplayName("Should return results in depth-first order")
		void shouldReturnResultsInDepthFirstOrder() {
			// Tree: and(eq("first"), or(eq("second"), not(eq("third"))), eq("fourth"))
			// Depth-first: And → eq("first") → Or → eq("second") → Not → eq("third") → eq("fourth")
			final FilterConstraint constraint = and(
				attributeEquals("first", "v"),
				or(
					attributeEquals("second", "v"),
					not(
						attributeEquals("third", "v")
					)
				),
				attributeEquals("fourth", "v")
			);

			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance
			);

			assertEquals(4, found.size());
			assertEquals("first", found.get(0).getAttributeName());
			assertEquals("second", found.get(1).getAttributeName());
			assertEquals("third", found.get(2).getAttributeName());
			assertEquals("fourth", found.get(3).getAttributeName());
		}

		@Test
		@DisplayName("Should visit container nodes before their descendants in results")
		void shouldVisitContainerNodesBeforeTheirDescendants() {
			// Tree: and(or(eq("inner")))
			// When matching everything: And, Or, eq("inner")
			final FilterConstraint constraint = and(
				or(
					attributeEquals("inner", "v")
				)
			);

			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				constraint,
				c -> true
			);

			assertEquals(3, found.size());
			assertInstanceOf(And.class, found.get(0));
			assertInstanceOf(Or.class, found.get(1));
			assertInstanceOf(AttributeEquals.class, found.get(2));
		}

		@Test
		@DisplayName("Should visit require tree in depth-first order with primary children before additional")
		void shouldVisitRequireTreeInCorrectOrder() {
			// require(page(1,20), referenceContent("a", filterBy(eq("xev")), entityFetch(attrContentAll())))
			// Depth-first: Require → Page → ReferenceContent → EntityFetch → AttributeContent → FilterBy → eq
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.requireConstraint,
				c -> true
			);

			// Verify Require is first, Page comes before ReferenceContent, and EntityFetch (primary) before
			// FilterBy (additional) within ReferenceContent
			assertInstanceOf(Require.class, found.get(0));
			assertInstanceOf(Page.class, found.get(1));
			assertInstanceOf(ReferenceContent.class, found.get(2));
			// EntityFetch (primary child of ReferenceContent) comes before FilterBy (additional child)
			final int entityFetchIndex = indexOf(found, EntityFetch.class);
			final int filterByIndex = indexOf(found, FilterBy.class);
			assertTrue(
				entityFetchIndex < filterByIndex,
				"EntityFetch (primary child) should appear before FilterBy (additional child)"
			);
		}
	}

	@Nested
	@DisplayName("Order constraint traversal")
	class OrderConstraintTest {

		@Test
		@DisplayName("Should find AttributeNatural constraints in an OrderBy tree")
		void shouldFindAttributeNaturalInOrderByTree() {
			final OrderConstraint orderConstraint = orderBy(
				attributeNatural("name", ASC),
				attributeNatural("code", DESC)
			);

			final List<AttributeNatural> found = FinderVisitor.findConstraints(
				orderConstraint,
				AttributeNatural.class::isInstance
			);

			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("Should find single AttributeNatural in OrderBy tree")
		void shouldFindSingleAttributeNaturalInOrderByTree() {
			final OrderConstraint orderConstraint = orderBy(
				attributeNatural("name", ASC)
			);

			final AttributeNatural result = FinderVisitor.findConstraint(
				orderConstraint,
				AttributeNatural.class::isInstance
			);

			assertNotNull(result);
			assertEquals("name", result.getAttributeName());
		}

		@Test
		@DisplayName("Should traverse Segment additional children to find EntityHaving filter constraint")
		void shouldTraverseSegmentAdditionalChildrenToFindEntityHaving() {
			// Segment has OrderBy as primary child and EntityHaving as additional child
			final OrderConstraint segmentConstraint = segment(
				entityHaving(
					attributeEquals("status", "active")
				),
				orderBy(
					attributeNatural("name", ASC)
				)
			);

			final EntityHaving result = FinderVisitor.findConstraint(
				segmentConstraint,
				EntityHaving.class::isInstance
			);

			assertNotNull(result);
			assertInstanceOf(EntityHaving.class, result);
		}

		@Test
		@DisplayName("Should find filter constraint nested inside Segment additional child tree")
		void shouldFindFilterConstraintInsideSegmentAdditionalChildSubtree() {
			// AttributeEquals is inside EntityHaving (additional child of Segment)
			final OrderConstraint segmentConstraint = segment(
				entityHaving(
					attributeEquals("status", "active")
				),
				orderBy(
					attributeNatural("name", ASC)
				)
			);

			final AttributeEquals result = FinderVisitor.findConstraint(
				segmentConstraint,
				AttributeEquals.class::isInstance
			);

			assertNotNull(result);
			assertEquals("status", result.getAttributeName());
		}

		@Test
		@DisplayName("Should find constraints from both order and filter domains in Segment")
		void shouldFindConstraintsFromBothOrderAndFilterDomainsInSegment() {
			final OrderConstraint segmentConstraint = segment(
				entityHaving(
					attributeEquals("status", "active")
				),
				orderBy(
					attributeNatural("name", ASC)
				)
			);

			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				segmentConstraint,
				fc -> fc instanceof AttributeNatural || fc instanceof AttributeEquals
			);

			// Should find AttributeNatural (from primary children) and AttributeEquals (from additional children)
			assertEquals(2, found.size());
		}
	}

	@Nested
	@DisplayName("Error handling — MoreThanSingleResultException")
	class ErrorHandlingTest {

		@Test
		@DisplayName("Should throw when findConstraint() finds multiple matches")
		void shouldThrowWhenMultipleMatchesFound() {
			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(
					FinderVisitorTest.this.filterConstraint,
					fc -> fc instanceof final AttributeEquals eq && eq.getAttributeValue().equals(true)
				)
			);

			assertTrue(
				exception.getMessage().contains("2"),
				"Exception message should mention the count of found constraints"
			);
		}

		@Test
		@DisplayName("Should include generic message when predicate has no description")
		void shouldIncludeGenericMessageWhenPredicateHasNoDescription() {
			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(
					FinderVisitorTest.this.filterConstraint,
					fc -> fc instanceof final AttributeEquals eq && eq.getAttributeValue().equals(true)
				)
			);

			assertTrue(
				exception.getMessage().contains("but expected is only one"),
				"Exception should include generic single-result expectation message"
			);
		}

		@Test
		@DisplayName("Should include predicate description in exception when PredicateWithDescription is used")
		void shouldIncludePredicateDescriptionInException() {
			final PredicateWithDescription<Constraint<?>> predicate = createDescribedPredicate(
				"constraints with attribute value equals to true",
				constraint -> constraint instanceof final AttributeEquals eq &&
					eq.getAttributeValue().equals(true)
			);

			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(FinderVisitorTest.this.filterConstraint, predicate)
			);

			assertTrue(
				exception.getMessage().contains("constraints with attribute value equals to true"),
				"Exception message should contain predicate description"
			);
			assertTrue(
				exception.getMessage().contains("2"),
				"Exception message should mention the count of found constraints"
			);
		}

		@Test
		@DisplayName("Should not throw when findConstraint() finds exactly one match")
		void shouldNotThrowWhenExactlyOneMatchFound() {
			assertDoesNotThrow(
				() -> FinderVisitor.findConstraint(
					FinderVisitorTest.this.filterConstraint,
					AttributeBetween.class::isInstance
				)
			);
		}

		@Test
		@DisplayName("Should throw exception that is an EvitaInvalidUsageException")
		void shouldThrowExceptionThatIsEvitaInvalidUsageException() {
			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(
					FinderVisitorTest.this.filterConstraint,
					fc -> fc instanceof final AttributeEquals eq && eq.getAttributeValue().equals(true)
				)
			);

			assertInstanceOf(EvitaInvalidUsageException.class, exception);
		}

		@Test
		@DisplayName("Should include accurate count in exception when many constraints match")
		void shouldIncludeAccurateCountWhenManyConstraintsMatch() {
			// Build tree with 5 AttributeEquals leaves
			final FilterConstraint constraint = and(
				attributeEquals("a", "v"),
				attributeEquals("b", "v"),
				or(
					attributeEquals("c", "v"),
					attributeEquals("d", "v"),
					not(
						attributeEquals("e", "v")
					)
				)
			);

			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(
					constraint,
					AttributeEquals.class::isInstance
				)
			);

			assertTrue(
				exception.getMessage().contains("5"),
				"Exception message should mention the exact count of 5 found constraints"
			);
		}

		@Test
		@DisplayName("Should include description and count in exception with PredicateWithDescription")
		void shouldIncludeDescriptionAndCountInExceptionWithPredicateWithDescription() {
			final FilterConstraint constraint = and(
				attributeEquals("a", "v"),
				attributeEquals("b", "v"),
				attributeEquals("c", "v")
			);

			final PredicateWithDescription<Constraint<?>> predicate = createDescribedPredicate(
				"attribute equality constraints",
				AttributeEquals.class::isInstance
			);

			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(constraint, predicate)
			);

			assertTrue(
				exception.getMessage().contains("3"),
				"Exception message should mention the count of 3 found constraints"
			);
			assertTrue(
				exception.getMessage().contains("attribute equality constraints"),
				"Exception message should contain the predicate description"
			);
			assertTrue(
				exception.getMessage().contains("but only one was expected"),
				"Exception message should indicate single result was expected"
			);
		}
	}

	@Nested
	@DisplayName("Complex realistic query trees")
	class ComplexQueryTreeTest {

		@Test
		@DisplayName("Should find all AttributeEquals in a deeply nested filter tree")
		void shouldFindAllAttributeEqualsInDeepTree() {
			final FilterConstraint deepTree = and(
				attributeEquals("level1", "a"),
				or(
					attributeEquals("level2a", "b"),
					and(
						attributeEquals("level3a", "c"),
						not(
							attributeEquals("level4", "d")
						)
					),
					attributeEquals("level2b", "e")
				)
			);

			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				deepTree,
				AttributeEquals.class::isInstance
			);

			assertEquals(5, found.size());
		}

		@Test
		@DisplayName("Should use stopper to search only within a specific subtree")
		void shouldUseStopperToSearchOnlyWithinSpecificSubtree() {
			final FilterConstraint tree = and(
				attributeEquals("root-child", "v"),
				or(
					attributeEquals("or-child1", "v"),
					attributeEquals("or-child2", "v")
				),
				not(
					attributeEquals("not-child", "v")
				)
			);

			// Find only AttributeEquals that are NOT inside a Not container
			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				tree,
				AttributeEquals.class::isInstance,
				Not.class::isInstance
			);

			assertEquals(3, found.size());
			// Verify none of the results is "not-child"
			for (final AttributeEquals eq : found) {
				assertNotEquals("not-child", eq.getAttributeName());
			}
		}

		@Test
		@DisplayName("Should find Page and ReferenceContent at the same level in require tree")
		void shouldFindSiblingConstraintsInRequireTree() {
			final List<Constraint<?>> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.requireConstraint,
				fc -> fc instanceof Page || fc instanceof ReferenceContent
			);

			assertEquals(2, found.size());
			assertInstanceOf(Page.class, found.get(0));
			assertInstanceOf(ReferenceContent.class, found.get(1));
		}

		@Test
		@DisplayName("Should find Require root when predicate matches it")
		void shouldFindRequireRootContainer() {
			final Require result = FinderVisitor.findConstraint(
				FinderVisitorTest.this.requireConstraint,
				Require.class::isInstance
			);

			assertNotNull(result);
			assertInstanceOf(Require.class, result);
		}

		@Test
		@DisplayName("Should find constraints across constraint type boundaries in Segment tree")
		void shouldFindConstraintsAcrossTypeBoundariesInSegment() {
			// Segment is an OrderConstraint container with EntityHaving (FilterConstraint) as additional child
			final OrderConstraint segmentTree = segment(
				entityHaving(
					and(
						attributeEquals("visible", true),
						attributeEquals("status", "active")
					)
				),
				orderBy(
					attributeNatural("priority", DESC)
				)
			);

			// Find all AttributeEquals across the type boundary
			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				segmentTree,
				AttributeEquals.class::isInstance
			);

			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("Should handle deeply nested require tree with multiple ReferenceContent entries")
		void shouldHandleDeeplyNestedRequireTreeWithMultipleReferences() {
			final RequireConstraint constraint = require(
				entityFetch(
					attributeContentAll(),
					referenceContent(
						"brand",
						filterBy(attributeEquals("active", true)),
						entityFetch(attributeContentAll())
					),
					referenceContent(
						"category",
						filterBy(attributeEquals("visible", true)),
						entityFetch(attributeContentAll())
					)
				)
			);

			// Find all FilterBy constraints (they are additional children of ReferenceContent)
			final List<FilterBy> found = FinderVisitor.findConstraints(
				constraint,
				FilterBy.class::isInstance
			);

			assertEquals(2, found.size());

			// Find all AttributeEquals across both reference subtrees
			final List<AttributeEquals> attributeEquals = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance
			);

			assertEquals(2, attributeEquals.size());
		}

		@Test
		@DisplayName("Should find constraints in deeply nested additional children chain")
		void shouldFindConstraintsInDeeplyNestedAdditionalChildrenChain() {
			// ReferenceContent has FilterBy as additional child, which contains nested filter constraints
			final RequireConstraint constraint = require(
				referenceContent(
					"categories",
					filterBy(
						and(
							attributeEquals("visible", true),
							or(
								attributeEquals("featured", true),
								attributeEquals("promoted", true)
							)
						)
					),
					entityFetch(attributeContentAll())
				)
			);

			// All three AttributeEquals are nested inside additional children
			final List<AttributeEquals> found = FinderVisitor.findConstraints(
				constraint,
				AttributeEquals.class::isInstance
			);

			assertEquals(3, found.size());
		}
	}

	/**
	 * Returns the index of the first element in the list that is an instance of the given type.
	 *
	 * @param list the list to search through
	 * @param type the class to match against
	 * @return index of the first matching element, or -1 if not found
	 */
	private static int indexOf(@Nonnull List<Constraint<?>> list, @Nonnull Class<?> type) {
		for (int i = 0; i < list.size(); i++) {
			if (type.isInstance(list.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Creates a {@link PredicateWithDescription} with the given description and test logic,
	 * useful for verifying exception messages include the predicate description.
	 *
	 * @param description human-readable description of what the predicate matches
	 * @param test the actual predicate logic
	 * @return a new predicate with description
	 */
	@Nonnull
	private static PredicateWithDescription<Constraint<?>> createDescribedPredicate(
		@Nonnull String description,
		@Nonnull java.util.function.Predicate<Constraint<?>> test
	) {
		return new PredicateWithDescription<>() {
			@Override
			public boolean test(Constraint<?> constraint) {
				return test.test(constraint);
			}

			@Override
			@Nonnull
			public String toString() {
				return description;
			}
		};
	}
}
