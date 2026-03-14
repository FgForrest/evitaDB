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

package io.evitadb.index.mutation.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceIndexMutator#applyFacetDecisionMatrix} verifying the four-way decision matrix
 * (was-faceted x now-faceted) correctly adds, removes, or leaves unchanged the facet entries in an entity index.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceIndexMutator — applyFacetDecisionMatrix")
class ReferenceIndexMutatorDecisionMatrixTest {
	private static final String ENTITY_TYPE = Entities.PRODUCT;
	private static final String REFERENCE_NAME = Entities.BRAND;
	private static final int FACET_PK = 10;
	private static final int ENTITY_PK = 1;

	/**
	 * Creates a {@link GlobalEntityIndex} for the product entity type.
	 *
	 * @return a fresh global entity index
	 */
	@Nonnull
	private static GlobalEntityIndex createGlobalIndex() {
		return new GlobalEntityIndex(1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL));
	}

	/**
	 * Creates a faceted {@link ReferenceSchema} for the brand reference with indexing and faceting enabled
	 * in the default (LIVE) scope.
	 *
	 * @return a reference schema that is both indexed and faceted
	 */
	@Nonnull
	private static ReferenceSchema createFacetedReferenceSchema() {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			REFERENCE_NAME,
			false,
			Cardinality.ZERO_OR_MORE,
			null,
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
			},
			new Scope[]{Scope.DEFAULT_SCOPE}
		);
	}

	/**
	 * Checks whether the given entity primary key is present as a facet in the index for the specified reference key.
	 * This mirrors the private `wasFaceted` logic in {@link ReferenceIndexMutator}.
	 *
	 * @param index            the entity index to inspect
	 * @param referenceKey     the reference key identifying the facet
	 * @param entityPrimaryKey the entity primary key to look for
	 * @return true if the entity is recorded as a facet under the given reference key
	 */
	private static boolean isFacetPresent(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey
	) {
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		if (facetRefIndex == null) {
			return false;
		}
		// check ungrouped bucket
		final FacetGroupIndex notGrouped = facetRefIndex.getNotGroupedFacets();
		if (notGrouped != null) {
			final FacetIdIndex facetIdIndex = notGrouped.getFacetIdIndex(referenceKey.primaryKey());
			if (facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey)) {
				return true;
			}
		}
		// check grouped buckets
		for (final FacetGroupIndex groupIndex : facetRefIndex.getGroupedFacets()) {
			final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(referenceKey.primaryKey());
			if (facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether the given entity primary key is present as a facet under a specific group in the index.
	 *
	 * @param index            the entity index to inspect
	 * @param referenceKey     the reference key identifying the facet
	 * @param groupId          the expected group id
	 * @param entityPrimaryKey the entity primary key to look for
	 * @return true if the entity is recorded as a facet under the specified group
	 */
	private static boolean isFacetPresentInGroup(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		int groupId,
		int entityPrimaryKey
	) {
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		if (facetRefIndex == null) {
			return false;
		}
		for (final FacetGroupIndex groupIndex : facetRefIndex.getGroupedFacets()) {
			if (groupIndex.getGroupId() != null && groupIndex.getGroupId() == groupId) {
				final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(referenceKey.primaryKey());
				return facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey);
			}
		}
		return false;
	}

	/**
	 * Verifies that a facet is removed from the index when the entity was previously faceted but
	 * the decision matrix determines it should no longer be (nowFaceted = false).
	 */
	@Test
	@DisplayName("Should remove facet when entity was faceted and now is not")
	void shouldRemoveFacetWhenWasFacetedAndNowNotFaceted() {
		final GlobalEntityIndex index = createGlobalIndex();
		final ReferenceSchema refSchema = createFacetedReferenceSchema();
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, FACET_PK);

		// pre-add the facet so the entity is currently faceted
		index.addFacet(refSchema, refKey, null, ENTITY_PK);
		assertTrue(isFacetPresent(index, refKey, ENTITY_PK), "Facet should be present before the call");

		// apply decision matrix with nowFaceted=false — should remove the facet
		ReferenceIndexMutator.applyFacetDecisionMatrix(index, refSchema, refKey, null, ENTITY_PK, false, null);

		assertFalse(isFacetPresent(index, refKey, ENTITY_PK), "Facet should be removed after the call");
	}

	/**
	 * Verifies that a facet is added to the index when the entity was not previously faceted but
	 * the decision matrix determines it should now be (nowFaceted = true).
	 */
	@Test
	@DisplayName("Should add facet when entity was not faceted and now is")
	void shouldAddFacetWhenWasNotFacetedAndNowFaceted() {
		final GlobalEntityIndex index = createGlobalIndex();
		final ReferenceSchema refSchema = createFacetedReferenceSchema();
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, FACET_PK);

		// verify the facet is not present initially
		assertFalse(isFacetPresent(index, refKey, ENTITY_PK), "Facet should not be present before the call");

		// apply decision matrix with nowFaceted=true — should add the facet
		ReferenceIndexMutator.applyFacetDecisionMatrix(index, refSchema, refKey, null, ENTITY_PK, true, null);

		assertTrue(isFacetPresent(index, refKey, ENTITY_PK), "Facet should be present after the call");
	}

	/**
	 * Verifies that the index is unchanged when the entity was already faceted and the decision matrix
	 * confirms it should stay faceted (nowFaceted = true) in the same group.
	 */
	@Test
	@DisplayName("Should be no-op when entity was faceted and still is")
	void shouldBeNoopWhenWasFacetedAndStillFaceted() {
		final GlobalEntityIndex index = createGlobalIndex();
		final ReferenceSchema refSchema = createFacetedReferenceSchema();
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, FACET_PK);

		// pre-add the facet
		index.addFacet(refSchema, refKey, null, ENTITY_PK);
		assertTrue(isFacetPresent(index, refKey, ENTITY_PK), "Facet should be present before the call");

		// apply decision matrix with nowFaceted=true — should leave the facet as-is
		ReferenceIndexMutator.applyFacetDecisionMatrix(index, refSchema, refKey, null, ENTITY_PK, true, null);

		assertTrue(isFacetPresent(index, refKey, ENTITY_PK), "Facet should still be present after the call");
	}

	/**
	 * Verifies that the index is unchanged when the entity was not faceted and the decision matrix
	 * confirms it should stay non-faceted (nowFaceted = false).
	 */
	@Test
	@DisplayName("Should be no-op when entity was not faceted and still is not")
	void shouldBeNoopWhenWasNotFacetedAndStillNotFaceted() {
		final GlobalEntityIndex index = createGlobalIndex();
		final ReferenceSchema refSchema = createFacetedReferenceSchema();
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, FACET_PK);

		// verify the facet is not present initially
		assertFalse(isFacetPresent(index, refKey, ENTITY_PK), "Facet should not be present before the call");

		// apply decision matrix with nowFaceted=false — should be a no-op
		ReferenceIndexMutator.applyFacetDecisionMatrix(index, refSchema, refKey, null, ENTITY_PK, false, null);

		assertFalse(isFacetPresent(index, refKey, ENTITY_PK), "Facet should still not be present after the call");
		assertTrue(index.getFacetingEntities().isEmpty(), "Facet index should remain empty");
	}

	/**
	 * Verifies that a facet is added under the correct group when flipping from not-faceted to faceted
	 * with a specific target group id.
	 */
	@Test
	@DisplayName("Should add facet with correct group when flipping to true")
	void shouldAddFacetWithCorrectGroupWhenFlippingToTrue() {
		final GlobalEntityIndex index = createGlobalIndex();
		final ReferenceSchema refSchema = createFacetedReferenceSchema();
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, FACET_PK);
		final int targetGroupId = 7;

		// verify the facet is not present initially
		assertFalse(isFacetPresent(index, refKey, ENTITY_PK), "Facet should not be present before the call");

		// apply decision matrix with nowFaceted=true and targetGroupId=7
		ReferenceIndexMutator.applyFacetDecisionMatrix(
			index, refSchema, refKey, targetGroupId, ENTITY_PK, true, null
		);

		assertTrue(isFacetPresent(index, refKey, ENTITY_PK), "Facet should be present after the call");
		assertTrue(
			isFacetPresentInGroup(index, refKey, targetGroupId, ENTITY_PK),
			"Facet should be in group " + targetGroupId
		);
	}
}
