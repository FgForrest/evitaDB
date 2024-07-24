/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse.mutation;

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.OptionalInt;

import static java.util.OptionalInt.of;

/**
 * Context that tracks the state of the mutation flow and provides states to {@link MutationPredicate} and helps
 * {@link Mutation} to be converted to {@link ChangeCatalogCapture}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class MutationPredicateContext {
	@Getter private final StreamDirection direction;
	@Getter private long version = 0L;
	@Getter private int index = 0;
	@Getter private String entityType;
	private boolean primaryKeyIdentified;
	private int primaryKey = Integer.MIN_VALUE;

	/**
	 * Returns the last known primary key of the entity. Might reflect current mutation entity or when the mutation is
	 * a local mutation of the entity mutation, it reflects the parent entity mutation primary key.
	 * @return the primary key of the entity
	 */
	@Nonnull
	public OptionalInt getPrimaryKey() {
		return primaryKeyIdentified ? of(primaryKey) : OptionalInt.empty();
	}

	/**
	 * Sets the primary key of the entity from entity mutation
	 * @param entityPrimaryKey the primary key of the entity
	 */
	public void setPrimaryKey(int entityPrimaryKey) {
		this.primaryKeyIdentified = true;
		this.primaryKey = entityPrimaryKey;
	}

	/**
	 * Resets the primary key of the entity - i.e. when we leave the entity mutation context.
	 */
	public void resetPrimaryKey() {
		this.primaryKeyIdentified = false;
		this.primaryKey = Integer.MIN_VALUE;
	}

	/**
	 * Returns true if passed primary key equals to {@link #getPrimaryKey()} and the entity primary key is known in
	 * the context.
	 * @param primaryKey the primary key to be matched
	 * @return true if the primary key matches
	 */
	public boolean matchPrimaryKey(int primaryKey) {
		return this.primaryKeyIdentified && this.primaryKey == primaryKey;
	}

	/**
	 * Sets the entity type extracted from the mutation. All local mutations keep the same entity type as the parent
	 * entity mutation.
	 * @param entityType the entity type
	 */
	public void setEntityType(@Nonnull String entityType) {
		this.entityType = entityType;
		this.primaryKeyIdentified = false;
	}

	/**
	 * Resets the entity type - i.e. when we leave the entity mutation context.
	 */
	public void resetEntityType() {
		this.entityType = null;
	}

	/**
	 * Returns true if passed entity type equals to {@link #getEntityType()} and the entity type is known in the context.
	 * @param entityType the entity type to be matched
	 * @return true if the entity type matches
	 */
	public boolean matchEntityType(@Nonnull String entityType) {
		return this.entityType != null && this.entityType.equals(entityType);
	}

	/**
	 * Sets the version from leading transactional mutation to the context. All mutations in the same atomic context
	 * (transactional) share the same version.
	 * @param version the version to be set
	 */
	public void setVersion(long version, int mutationCount) {
		this.version = version;
		this.primaryKeyIdentified = false;
		this.entityType = null;
		this.index = direction == StreamDirection.FORWARD ? 0 : mutationCount;
	}

	/**
	 * Increments the last known index of the mutation. Used to track the position of the mutation in the transaction.
	 */
	public void advance() {
		if (direction == StreamDirection.FORWARD) {
			this.index++;
		} else {
			this.index--;
		}
	}

}
