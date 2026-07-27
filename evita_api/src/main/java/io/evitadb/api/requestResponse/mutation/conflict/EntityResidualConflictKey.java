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

package io.evitadb.api.requestResponse.mutation.conflict;


import javax.annotation.Nonnull;

/**
 * Marks that a transaction touched an entity's shared, non-carved-out surface.
 *
 * {@link EntityConflictKey} conflates two distinct facts about a transaction: "it changed the entity's very
 * existence or identity" (removal, forced creation, scope change) and "it touched some of the entity's
 * ordinary fields that were not carved out into their own granular key" (the coarse-policy catch-all). Only
 * the first fact must conflict with every carved-out item of the entity; the second must conflict only with
 * other writers of that same shared surface, not with a writer of a `GRANULAR`-overridden item. This key
 * represents the second fact alone, so a coarse writer of an ordinary field and a writer of a carved-out item
 * of the same entity stop falsely conflicting.
 *
 * This key is a sibling of the granular per-item keys ({@link AttributeConflictKey},
 * {@link AssociatedDataConflictKey}, {@link PriceConflictKey}, {@link ReferenceConflictKey},
 * {@link ReferenceAttributeConflictKey}, {@link HierarchyConflictKey}), never their ancestor or descendant:
 * every granular key is only ever produced for an item that was carved out of this residual surface in the
 * first place, so the two families never need to be compared against each other. It remains a *child* of the
 * full {@link EntityConflictKey}: whole-entity operations still conflict with the shared surface, exactly as
 * they conflict with every carved-out item.
 *
 * Carries no schema-derived payload beyond the entity coordinates: matching between the write path and the
 * historical recompute path is by hash equality alone, so the two paths agree without needing to re-derive
 * which items were carved out at emission time.
 *
 * @see ConflictKey
 * @see EntityConflictKey
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record EntityResidualConflictKey(
	@Nonnull String entityType,
	int entityPrimaryKey
) implements ConflictKey {

	/**
	 * The shared surface is contained by the full entity: any whole-entity conflict (removal, forced
	 * creation, scope change) implies a conflict on the entity's shared, non-carved-out surface too.
	 *
	 * @return an {@link EntityConflictKey} for the owning entity
	 */
	@Nonnull
	@Override
	public ConflictKey parentConflictKey() {
		return new EntityConflictKey(this.entityType, this.entityPrimaryKey);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@link ConflictScope#ENTITY}
	 */
	@Nonnull
	@Override
	public ConflictScope conflictScope() {
		return ConflictScope.ENTITY;
	}

	/**
	 * Returns a concise, human-readable representation of this conflict key.
	 *
	 * @return non-null string representation
	 */
	@Nonnull
	@Override
	public String toString() {
		return "shared surface of entity `" + this.entityType + "` with primary key `" + this.entityPrimaryKey + '`';
	}

}
