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


import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * A thread-local context holder for tracking hierarchical entity information during conflict generation.
 * This class provides temporary context scoping for catalog, entity, and reference information,
 * ensuring that nested operations can access the appropriate contextual data while maintaining
 * proper cleanup semantics.
 *
 * The context is designed to be used in a try-finally pattern through its {@code with*} methods,
 * which automatically set and clear context values around the execution of provided runnables.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictGenerationContext {
	/**
	 * The name of the catalog currently being processed. May be null if not within a catalog context.
	 */
	@Nullable private String catalogName;

	/**
	 * The type of the entity currently being processed. May be null if not within an entity context.
	 */
	@Nullable private String entityType;

	/**
	 * The primary key of the entity currently being processed. May be null if the entity doesn't have
	 * a primary key yet or if not within an entity context.
	 */
	@Nullable private Integer entityPrimaryKey;

	/**
	 * The reference key currently being processed. May be null if not within a reference context.
	 */
	@Nullable private ReferenceKey referenceKey;

	/**
	 * Retrieves the catalog name associated with the current context.
	 * If the catalog name is not set, an internal error is thrown.
	 *
	 * @return the name of the catalog
	 * @throws GenericEvitaInternalError if the catalog name is not set in the context
	 */
	@Nonnull
	public String getCatalogName() {
		Assert.isPremiseValid(
			this.catalogName != null,
			"Catalog name is not set in the current context!"
		);
		return this.catalogName;
	}

	/**
	 * Determines if the entity type is set in the current context.
	 *
	 * @return true if an entity type is present, false otherwise
	 */
	public boolean isEntityTypePresent() {
		return this.entityType != null;
	}

	/**
	 * Retrieves the entity type associated with the current context.
	 * If the entity type is not set, an internal error is thrown.
	 *
	 * @return the type of the entity
	 * @throws GenericEvitaInternalError if the entity type is not set in the context
	 */
	@Nonnull
	public String getEntityType() {
		Assert.isPremiseValid(
			this.entityType != null,
			"Entity type is not set in the current context!"
		);
		return this.entityType;
	}

	/**
	 * Retrieves the primary key of the entity associated with the current context.
	 *
	 * @return the primary key of the entity
	 */
	@Nullable
	public Integer getEntityPrimaryKey() {
		return this.entityPrimaryKey;
	}

	/**
	 * Retrieves the reference key associated with the current context.
	 * If the reference key is not set, an assertion error is triggered.
	 *
	 * @return the reference key set in the current context
	 * @throws GenericEvitaInternalError if the catalog name is not set in the context
	 */
	@Nonnull
	public ReferenceKey getReferenceKey() {
		Assert.isPremiseValid(
			this.referenceKey != null,
			"The reference key is not set in the current context!"
		);
		return this.referenceKey;
	}

	/**
	 * Executes the given lambda within a catalog name context. The catalog name is set before
	 * execution and automatically cleared afterwards, even if an exception occurs.
	 *
	 * @param catalogName the name of the catalog to set in the context
	 * @param lambda the code to execute within the catalog context
	 */
	@Nonnull
	public <T> T withCatalogName(@Nonnull String catalogName, @Nonnull Function<ConflictGenerationContext, T> lambda) {
		try {
			this.catalogName = catalogName;
			return lambda.apply(this);
		} finally {
			this.catalogName = null;
		}
	}

	/**
	 * Executes the given lambda within an entity context. The entity type and primary key are set
	 * before execution and automatically cleared afterwards, even if an exception occurs.
	 *
	 * @param entityType the type of the entity to set in the context
	 * @param entityPrimaryKey the primary key of the entity, or null if the entity doesn't have one yet
	 * @param lambda the code to execute within the entity context
	 */
	@Nonnull
	public <T> T withEntityType(@Nonnull String entityType, @Nullable Integer entityPrimaryKey, @Nonnull Function<ConflictGenerationContext, T> lambda) {
		try {
			this.entityType = entityType;
			this.entityPrimaryKey = entityPrimaryKey;
			return lambda.apply(this);
		} finally {
			this.entityType = null;
			this.entityPrimaryKey = null;
		}
	}

}
