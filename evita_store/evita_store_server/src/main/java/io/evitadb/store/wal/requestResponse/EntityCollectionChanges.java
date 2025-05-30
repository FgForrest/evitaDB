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

package io.evitadb.store.wal.requestResponse;


import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Describes changes in particular entity collection in a very generic way.
 *
 * @param entityName    name of the entity ({@link EntitySchema#getName()})
 * @param schemaChanges number of schema altering mutations
 * @param upserted      number of upserted entities
 * @param removed       number of removed entities
 */
public record EntityCollectionChanges(
	@Nonnull String entityName,
	int schemaChanges,
	int upserted,
	int removed
) implements Serializable {

	/**
	 * Merges the given {@link EntityCollectionChanges} with this instance.
	 *
	 * @param otherChanges the {@link EntityCollectionChanges} to merge
	 * @return a new {@link EntityCollectionChanges} instance representing the merged changes
	 * @throws IllegalArgumentException if the entity names of the two collections are not the same
	 */
	@Nonnull
	public EntityCollectionChanges mergeWith(@Nonnull EntityCollectionChanges otherChanges) {
		Assert.isPremiseValid(
			this.entityName.equals(otherChanges.entityName),
			"Entity name must be the same for merging changes"
		);
		return new EntityCollectionChanges(
			this.entityName,
			this.schemaChanges + otherChanges.schemaChanges,
			this.upserted + otherChanges.upserted,
			this.removed + otherChanges.removed
		);
	}

	@Nonnull
	@Override
	public String toString() {
		return "changes in `" + this.entityName + "`: " +
			(this.schemaChanges > 0 ? this.schemaChanges + " schema changes" : "") +
			(this.upserted > 0 ? (this.schemaChanges > 0 ? ", " : "") + this.upserted + " upserted entities" : "") +
			(this.removed > 0 ?
				(this.schemaChanges > 0 || this.upserted > 0 ? ", " : "") + this.removed + " removed entities" :
				"");
	}
}
