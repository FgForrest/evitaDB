/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.expression.query;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ExpressionToQueryTranslator} verifying that the translator's
 * output is structurally compatible with downstream PK-scoping parameterization by the executor,
 * and that the output is safe for concurrent access.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ExpressionToQueryTranslator — integration")
class ExpressionToQueryTranslatorIntegrationTest {

	private static final String REF_NAME = "testRef";

	// --- PK-scoping compatibility ---

	@Test
	@DisplayName("group entity FilterBy tree supports groupHaving(entityPrimaryKeyInSet) injection")
	void shouldSupportGroupEntityPkScopingInjection() {
		final FilterBy filterBy = translate(
			"$reference.groupEntity?.attributes['status'] == 'ACTIVE'"
		);

		// locate the referenceHaving node
		final FilterConstraint rootChild = filterBy.getChildren()[0];
		assertInstanceOf(ReferenceHaving.class, rootChild);
		final ReferenceHaving refHaving = (ReferenceHaving) rootChild;
		assertEquals(REF_NAME, refHaving.getReferenceName());

		// inject a groupHaving(entityPrimaryKeyInSet(99)) alongside existing children
		final FilterConstraint[] originalChildren = refHaving.getChildren();
		final FilterConstraint scopingConstraint = groupHaving(entityPrimaryKeyInSet(99));
		final FilterConstraint[] augmentedChildren =
			new FilterConstraint[originalChildren.length + 1];
		System.arraycopy(originalChildren, 0, augmentedChildren, 0, originalChildren.length);
		augmentedChildren[originalChildren.length] = scopingConstraint;

		// reconstruct with additional scoping constraint
		final ReferenceHaving scoped = (ReferenceHaving) refHaving.getCopyWithNewChildren(
			augmentedChildren, new Constraint<?>[0]
		);

		// verify the structure
		assertEquals(REF_NAME, scoped.getReferenceName());
		assertEquals(originalChildren.length + 1, scoped.getChildren().length);
		// last child should be the scoping constraint
		assertEquals(scopingConstraint, scoped.getChildren()[scoped.getChildren().length - 1]);
	}

	@Test
	@DisplayName("referenced entity FilterBy tree supports entityHaving(entityPrimaryKeyInSet) injection")
	void shouldSupportReferencedEntityPkScopingInjection() {
		final FilterBy filterBy = translate(
			"$reference.referencedEntity.attributes['status'] == 'PREVIEW'"
		);

		// locate referenceHaving
		final FilterConstraint rootChild = filterBy.getChildren()[0];
		assertInstanceOf(ReferenceHaving.class, rootChild);
		final ReferenceHaving refHaving = (ReferenceHaving) rootChild;

		// inject entityHaving(entityPrimaryKeyInSet(99))
		final FilterConstraint[] originalChildren = refHaving.getChildren();
		final FilterConstraint scopingConstraint = entityHaving(entityPrimaryKeyInSet(99));
		final FilterConstraint[] augmentedChildren =
			new FilterConstraint[originalChildren.length + 1];
		System.arraycopy(originalChildren, 0, augmentedChildren, 0, originalChildren.length);
		augmentedChildren[originalChildren.length] = scopingConstraint;

		final ReferenceHaving scoped = (ReferenceHaving) refHaving.getCopyWithNewChildren(
			augmentedChildren, new Constraint<?>[0]
		);

		assertEquals(REF_NAME, scoped.getReferenceName());
		assertEquals(originalChildren.length + 1, scoped.getChildren().length);
		assertEquals(scopingConstraint, scoped.getChildren()[scoped.getChildren().length - 1]);
	}

	@Test
	@DisplayName("mixed-path FilterBy tree supports scoping injection on referenceHaving node")
	void shouldSupportScopingWithMixedPaths() {
		// WBS Example 2: entity attribute + group entity attribute
		final FilterBy filterBy = translate(
			"$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX' "
				+ "&& $entity.attributes['isActive'] == true"
		);

		// the tree should be and(referenceHaving(...), attributeEquals(...))
		final ReferenceHaving refHaving = findReferenceHaving(filterBy);
		assertNotNull(refHaving, "referenceHaving node must be present in the tree");

		// inject scoping constraint
		final FilterConstraint[] originalChildren = refHaving.getChildren();
		final FilterConstraint scopingConstraint = groupHaving(entityPrimaryKeyInSet(42));
		final FilterConstraint[] augmentedChildren =
			new FilterConstraint[originalChildren.length + 1];
		System.arraycopy(originalChildren, 0, augmentedChildren, 0, originalChildren.length);
		augmentedChildren[originalChildren.length] = scopingConstraint;

		final ReferenceHaving scoped = (ReferenceHaving) refHaving.getCopyWithNewChildren(
			augmentedChildren, new Constraint<?>[0]
		);

		assertEquals(REF_NAME, scoped.getReferenceName());
		assertEquals(originalChildren.length + 1, scoped.getChildren().length);
	}

	// --- Immutability and thread safety ---

	@Test
	@DisplayName("translated FilterBy uses immutable constraint objects (final fields)")
	void shouldProduceImmutableFilterBy() {
		final FilterBy filterBy = translate(
			"$entity.attributes['a'] == 1 && $reference.attributes['b'] == 2"
		);

		// verify the tree is structurally sound — getChildren() returns same array contents on repeat
		final FilterConstraint[] children1 = filterBy.getChildren();
		final FilterConstraint[] children2 = filterBy.getChildren();
		assertArrayEquals(children1, children2, "children should be consistent across calls");

		// verify constraint equality is stable
		assertEquals(filterBy, filterBy, "constraint should equal itself");
	}

	@Test
	@DisplayName("translated FilterBy is safe for concurrent reads")
	void shouldBeSafeForConcurrentReads() throws Exception {
		final FilterBy filterBy = translate(
			"$entity.attributes['isActive'] == true "
				+ "&& $reference.groupEntity?.attributes['status'] == 'ACTIVE' "
				+ "&& $reference.referencedEntity.attributes['visible'] == true"
		);

		final int threadCount = 8;
		final int iterationsPerThread = 1000;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final List<Future<Boolean>> futures = new ArrayList<>(threadCount);

		for (int t = 0; t < threadCount; t++) {
			futures.add(executor.submit(() -> {
				startLatch.await();
				for (int i = 0; i < iterationsPerThread; i++) {
					// concurrent reads — navigate the tree structure
					final FilterConstraint[] children = filterBy.getChildren();
					assertNotNull(children);
					assertTrue(children.length > 0);
					for (FilterConstraint child : children) {
						assertNotNull(child);
						// recursively read children if it's a container
						if (child instanceof ConstraintContainer<?> container) {
							assertNotNull(container.getChildren());
						}
					}
				}
				return true;
			}));
		}

		// release all threads simultaneously
		startLatch.countDown();

		// collect results — any exception fails the test
		for (Future<Boolean> future : futures) {
			assertTrue(future.get(10, TimeUnit.SECONDS));
		}
		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
	}

	// --- AccessedDataFinder collaboration ---

	@Test
	@DisplayName("pre-validation rejects dynamic attribute paths before AST traversal")
	void shouldRejectDynamicPathsBeforeTraversal() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes[$someVar] == 1")
		);
		assertTrue(
			ex.getMessage().contains("Dynamic attribute path")
				|| ex.getMessage().contains("variable"),
			"message should identify the dynamic path issue: " + ex.getMessage()
		);
	}

	// --- Helper ---

	/**
	 * Parses the given expression string and translates it into a {@link FilterBy} constraint tree
	 * using the default reference name {@link #REF_NAME}.
	 */
	@Nonnull
	private static FilterBy translate(@Nonnull String expressionString) {
		final Expression expression = ExpressionFactory.parse(expressionString);
		return ExpressionToQueryTranslator.translate(expression, REF_NAME);
	}

	/**
	 * Searches the top-level children of the given {@link FilterBy} tree for a {@link ReferenceHaving}
	 * node. If the root child is directly a {@link ReferenceHaving}, it is returned. If it is a
	 * {@link ConstraintContainer} (e.g., `and(...)`), its children are searched for the first
	 * {@link ReferenceHaving} instance.
	 *
	 * @param filterBy the filter constraint tree to search
	 * @return the first {@link ReferenceHaving} found, or `null` if none exists
	 */
	@Nullable
	private static ReferenceHaving findReferenceHaving(@Nonnull FilterBy filterBy) {
		final FilterConstraint rootChild = filterBy.getChildren()[0];
		if (rootChild instanceof ReferenceHaving rh) {
			return rh;
		} else if (rootChild instanceof ConstraintContainer<?> container) {
			for (Constraint<?> child : container.getChildren()) {
				if (child instanceof ReferenceHaving rh) {
					return rh;
				}
			}
		}
		return null;
	}
}
