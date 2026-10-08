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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a write needs the index of a referenced entity that a reference keeps in a form its current
 * indexing cannot use.
 *
 * A reference indexed {@link ReferenceIndexType#FOR_FILTERING} keeps the index of a referenced entity that only one
 * entity references in a compact form, which holds what filtering needs and nothing else. When the reference is
 * re-indexed {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} later, the indexes already built stay as they
 * are - nothing converts them. A write that would put into such an index what partitioning needs (an owner entering
 * it, an attribute, a locale, a price or a facet) cannot be applied to it, and is refused with this exception rather
 * than leaving the index silently incomplete. Writes that only take data out of such an index still succeed.
 *
 * The client resolves the situation by reindexing the data of the entity type - rebuilding the catalog in full - so
 * that every index of the reference is built for its current indexing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferencePartitionReindexRequiredException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3187296436410578115L;

	/**
	 * The entity type owning the reference.
	 */
	@Getter private final String entityType;
	/**
	 * The name of the reference whose indexing changed.
	 */
	@Getter private final String referenceName;
	/**
	 * The scope in which the reference's indexing changed.
	 */
	@Getter private final Scope scope;

	/**
	 * Creates the exception for a write refused on the reference in the scope.
	 *
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the name of the reference whose indexing changed
	 * @param scope         the scope in which the reference's indexing changed
	 */
	public ReferencePartitionReindexRequiredException(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		super(
			"Reference `" + referenceName + "` of entity type `" + entityType + "` is indexed `" +
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING + "` in scope `" + scope + "`, but the indexes of " +
				"its referenced entities were built while it was indexed `" + ReferenceIndexType.FOR_FILTERING +
				"`, and an index of a referenced entity referenced by a single entity cannot hold the data the " +
				"write adds to it. Reindex the data of entity type `" + entityType + "` (rebuild the catalog in " +
				"full) before writing it again."
		);
		this.entityType = entityType;
		this.referenceName = referenceName;
		this.scope = scope;
	}

}
