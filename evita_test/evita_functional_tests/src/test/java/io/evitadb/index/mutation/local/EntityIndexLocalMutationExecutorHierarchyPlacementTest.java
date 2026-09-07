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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.OptionalInt;

import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the hierarchy-placement state machine of {@link EntityIndexLocalMutationExecutor} directly, at the
 * granularity of a single local mutation.
 *
 * A session cannot reach every state this machine has. One executor is built per
 * {@link io.evitadb.api.requestResponse.data.mutation.EntityMutation}, so a batch carrying both a parent change
 * and an entire removal never occurs in production, and the transition that puts a placement *back* after one
 * was torn down therefore has no functional-test route at all. The mutator harness reaches it directly and without an
 * embedded evitaDB instance, which is what this class is for; the shapes a session *can* reach are covered
 * through the query surface in
 * {@link io.evitadb.api.functional.fetch.HierarchyContentParentsBehaviourFunctionalTest} and
 * {@link io.evitadb.api.functional.indexing.EvitaArchivingTest}.
 *
 * Most cases here are about the same asymmetry: the executor's own preparation step places *every* hierarchical
 * entity into the hierarchy index - a root included, as a root node - while the decomposition of an entity
 * removal into local mutations only emits a parent removal for an entity that actually has a parent. The removal
 * path therefore has to tear the placement down itself, and exactly once.
 *
 * The remaining cases pin the other half of that rule: a parent removal that arrives *outside* an entity removal
 * is a user clearing a parent, not a tear-down, so it re-places the node as a root and leaves the removal path a
 * placement to remove later. The two readings of one mutation are told apart by whether the entity is marked as
 * removed entirely, which is why several cases below mark it before applying the mutation rather than after.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndexLocalMutationExecutor — hierarchy placement across parent and entity removals")
@Tag(INDEXING)
@Tag(HIERARCHY)
class EntityIndexLocalMutationExecutorHierarchyPlacementTest extends AbstractMutatorTestBase {
	/**
	 * Primary key of the entity every case places, moves and removes. It is the key the shared harness builds its
	 * executor around, so nothing else may be used here.
	 */
	private static final int ENTITY_PK = 1;
	/**
	 * Primary key handed to a parent-setting mutation. It is deliberately absent from the index, which makes the
	 * entity an orphan rather than a root - a placement all the same, and the one the removal path must find.
	 */
	private static final int PARENT_PK = 2;

	@Override
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schema) {
		// no catalog-level customization required
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schema) {
		// the sample product schema declares no hierarchy, and every case here is about a hierarchy placement
		schema.withHierarchy();
	}

	/**
	 * Marks the entity under test as removed entirely, which is what makes the executor's finishing step take the
	 * removal branch. This is the same route the trigger-detection suite drives a removal through.
	 */
	private void markEntityForRemoval() {
		this.containerAccessor
			.getEntityStoragePart(ENTITY_NAME, ENTITY_PK, EntityExistence.MUST_EXIST)
			.markForRemoval();
	}

	/**
	 * Asserts the entity holds no hierarchy placement at all - it is neither a root, nor an orphan, nor anything
	 * the item index knows about.
	 */
	private void assertNoHierarchyPlacement() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.productIndex.getParentNode(ENTITY_PK)
		);
		assertEquals("The node `" + ENTITY_PK + "` is not present in the index!", exception.getMessage());
		assertFalse(
			this.productIndex.getRootHierarchyNodes().contains(ENTITY_PK),
			"The removed entity must not remain a root of the hierarchy."
		);
		assertFalse(
			this.productIndex.getOrphanHierarchyNodes().contains(ENTITY_PK),
			"The removed entity must not remain an orphan of the hierarchy."
		);
	}

	/**
	 * The state that produced the phantom root of issue #1365: an entity with no parent is placed by the
	 * preparation step, and its removal carries no parent mutation that could take the placement back out. The
	 * removal path itself is the only thing that can, and this case is what proves it does.
	 */
	@Test
	@DisplayName("A removed hierarchy root loses the placement the preparation step gave it")
	void shouldUnindexAHierarchyRootWhenTheEntityIsRemoved() {
		// preparing a hierarchical entity places it as a root, because it has no parent mutation of its own
		this.executor.prepare(List.of());
		assertEquals(OptionalInt.empty(), this.productIndex.getParentNode(ENTITY_PK));
		assertTrue(this.productIndex.getRootHierarchyNodes().contains(ENTITY_PK));

		markEntityForRemoval();
		this.executor.applyChanges();

		assertNoHierarchyPlacement();
	}

	/**
	 * The opposite half of the case above - an entity that does have a parent, so its removal decomposes into a
	 * parent removal that tears the placement down before the removal path is reached. The second tear-down has
	 * to be suppressed, which is what the flag on the executor is for.
	 */
	@Test
	@DisplayName("A placement already torn down by a parent removal is not torn down a second time")
	void shouldNotUnindexTwiceWhenARemoveParentMutationAlreadyDidIt() {
		this.executor.prepare(List.of());
		// the entity has a parent, which is the only shape for which an entity removal decomposes into a parent
		// removal at all
		this.executor.applyMutation(new SetParentMutation(PARENT_PK));

		// the parent removal has to arrive while the entity is already marked as removed entirely - that is what
		// tells it apart from a user clearing a parent, and it is the only ordering a session can produce
		markEntityForRemoval();
		this.executor.applyMutation(new RemoveParentMutation());
		assertNoHierarchyPlacement();

		// the index refuses to remove a node that is not there, so a broken guard fails loudly right here
		assertDoesNotThrow(this.executor::applyChanges);

		assertNoHierarchyPlacement();
	}

	/**
	 * The reset half of the flag: a parent set after a tear-down puts a placement back, so the removal path owes
	 * a tear-down again. The sequence has no session-level route, which is exactly why it is pinned here - see
	 * the comment in the body for why it is kept rather than deleted as unreachable.
	 */
	@Test
	@DisplayName("A placement restored after a parent removal is torn down again by the entity removal")
	void shouldUnindexAgainAfterASetParentMutationRestoredThePlacement() {
		this.executor.prepare(List.of());
		markEntityForRemoval();
		// inside a removal the parent removal tears the placement down, and setting one puts a placement back
		this.executor.applyMutation(new RemoveParentMutation());
		this.executor.applyMutation(new SetParentMutation(PARENT_PK));

		this.executor.applyChanges();

		// this ordering is defensive rather than reachable: one executor is built per entity mutation, so no
		// session can put a parent back inside the same batch that removes the entity. It exists so that the
		// removal path cannot come to depend on the tear-down having been the last thing that happened, and it
		// must not be deleted as dead code on the grounds that nothing produces the sequence today
		assertNoHierarchyPlacement();
	}

	/**
	 * The same mutation read the other way: no removal is in progress, so the parent removal is a user promoting
	 * the entity to a root. Un-indexing it here would be the mirror of the phantom root - a live entity vanishing
	 * from the hierarchy - so the node is re-placed as a root instead.
	 */
	@Test
	@DisplayName("Clearing a parent outside a removal re-roots the entity instead of dropping its placement")
	void shouldRerootTheEntityWhenAParentIsClearedOutsideARemoval() {
		this.executor.prepare(List.of());
		this.executor.applyMutation(new SetParentMutation(PARENT_PK));
		assertEquals(OptionalInt.of(PARENT_PK), this.productIndex.getParentNode(ENTITY_PK));

		// clearing a parent makes the entity a root as far as the entity itself is concerned - the mutation that
		// produces this state reports an empty parent rather than no parent information, and the index has to
		// say the same thing
		this.executor.applyMutation(new RemoveParentMutation());

		assertEquals(OptionalInt.empty(), this.productIndex.getParentNode(ENTITY_PK));
		assertTrue(
			this.productIndex.getRootHierarchyNodes().contains(ENTITY_PK),
			"An entity whose parent was cleared must become a root of the hierarchy."
		);
		assertFalse(
			this.productIndex.getOrphanHierarchyNodes().contains(ENTITY_PK),
			"An entity whose parent was cleared must not be left as an orphan."
		);
	}

	/**
	 * The consequence of the re-rooting for the removal path. Because a re-rooting records no tear-down, the
	 * guard that suppresses a second one must not fire, and the placement the re-rooting produced still has to
	 * go when the entity is removed later on.
	 */
	@Test
	@DisplayName("A re-rooted entity is still un-indexed when it is removed afterwards")
	void shouldUnindexAReRootedEntityWhenItIsRemovedAfterwards() {
		this.executor.prepare(List.of());
		this.executor.applyMutation(new SetParentMutation(PARENT_PK));
		// the parent removal outside an entity removal leaves a placement behind, so the removal path below
		// still has one to tear down - the guard that suppresses a second tear-down must not fire here
		this.executor.applyMutation(new RemoveParentMutation());

		markEntityForRemoval();
		assertDoesNotThrow(this.executor::applyChanges);

		assertNoHierarchyPlacement();
	}

}
