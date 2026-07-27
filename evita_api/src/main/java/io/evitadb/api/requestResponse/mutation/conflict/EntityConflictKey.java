/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
 * Entity-level conflict key for serializing concurrent engine mutations.
 *
 * Represents a whole-entity operation: removal, forced creation ({@link
 * io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence#MUST_NOT_EXIST}) or a scope
 * change (archive/restore). These are the only writes that must conflict with *every* part of the entity,
 * including an item that was carved out into its own granular conflict key — which is why every granular
 * key's containment chain still reaches this key. An ordinary write that merely touches some of the entity's
 * non-carved-out fields, without changing its existence or identity, instead produces the finer
 * {@link EntityResidualConflictKey}, a sibling of the granular keys and a child of this one.
 *
 * @see ConflictKey
 * @see EntityResidualConflictKey
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record EntityConflictKey(
	@Nonnull String entityType,
	int entityPrimaryKey
) implements ConflictKey {

	/**
	 * An entity is contained by its collection: any collection-wide conflict implies a conflict on this entity.
	 *
	 * @return a {@link CollectionConflictKey} for this entity's collection
	 */
	@Nonnull
	@Override
	public ConflictKey parentConflictKey() {
		return new CollectionConflictKey(this.entityType);
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
		return "entity `" + this.entityType + "` with primary key `" + this.entityPrimaryKey + '`';
	}

}
