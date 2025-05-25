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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * This class represents a mock implementation of the EntitySchemaProvider interface, specifically designed
 * for mutation scenarios. It prevents access to entity schemas and always throws an UnsupportedOperationException.
 *
 * Note: This class is a singleton and should be accessed using the INSTANCE constant.
 */
public class MutationEntitySchemaAccessor implements EntitySchemaProvider {
	public static final MutationEntitySchemaAccessor INSTANCE = new MutationEntitySchemaAccessor();
	/**
	 * The base accessor for accessing entity schema.
	 */
	@Nullable private final EntitySchemaProvider baseAccessor;
	/**
	 * Index that stores newly upserted entity schemas. If no entity schema is altered, map is NULL.
	 *
	 * The keys in the map are strings, which represent the names or identifiers of the entity schemas.
	 * The values in the map are objects that implement the {@link EntitySchemaContract} interface, representing
	 * the entity schemas themselves.
	 */
	@Nullable private Map<String, EntitySchemaContract> entitySchemas;
	/**
	 * Set of removed entity schemas.
	 */
	@Nullable private Set<String> removedEntitySchemas;

	private MutationEntitySchemaAccessor() {
		// immutable version of the schema accessor (shared static instance)
		this.baseAccessor = null;
	}

	public MutationEntitySchemaAccessor(@Nonnull EntitySchemaProvider entitySchemaProvider) {
		// mutable version of the schema accessor
		this.baseAccessor = entitySchemaProvider;
	}

	/**
	 * Adds an upserted entity schema to the collection of entity schemas.
	 *
	 * @param entitySchema the entity schema to be added
	 */
	public void addUpsertedEntitySchema(@Nonnull EntitySchemaContract entitySchema) {
		if (this.baseAccessor == null) {
			// do nothing - this instance is immutable
		} else {
			if (this.entitySchemas == null) {
				this.entitySchemas = new HashMap<>(8);
			}
			this.entitySchemas.put(entitySchema.getName(), entitySchema);
		}
	}

	/**
	 * Removes an entity schema from the collection of entity schemas.
	 *
	 * @param name the name of the entity schema to be removed
	 */
	public void removeEntitySchema(@Nonnull String name) {
		if (this.baseAccessor == null) {
			// do nothing - this instance is immutable
		} else {
			if (this.removedEntitySchemas == null) {
				this.removedEntitySchemas = new HashSet<>(8);
			}
			if (this.entitySchemas != null) {
				this.entitySchemas.remove(name);
			}
			if (this.baseAccessor.getEntitySchema(name).isPresent()) {
				this.removedEntitySchemas.add(name);
			}
		}
	}

	/**
	 * Replaces an entity schema with a new entity schema.
	 *
	 * @param oldName the name of the entity schema to be replaced
	 * @param entitySchema the new entity schema to be added
	 */
	public void replaceEntitySchema(@Nonnull String oldName, @Nonnull EntitySchemaContract entitySchema) {
		if (this.baseAccessor == null) {
			// do nothing - this instance is immutable
		} else {
			if (this.entitySchemas == null) {
				this.entitySchemas = new HashMap<>(8);
			}
			if (this.removedEntitySchemas == null) {
				this.removedEntitySchemas = new HashSet<>(8);
			}
			this.entitySchemas.put(entitySchema.getName(), entitySchema);
			if (this.baseAccessor.getEntitySchema(oldName).isPresent()) {
				this.removedEntitySchemas.add(oldName);
			}
		}
	}

	@Nonnull
	@Override
	public Collection<EntitySchemaContract> getEntitySchemas() {
		return Stream.concat(
			this.entitySchemas == null ?
				Stream.empty() : this.entitySchemas.values().stream(),
			this.baseAccessor == null ?
				Stream.empty() :
				this.baseAccessor.getEntitySchemas()
					.stream()
					.filter(it -> this.entitySchemas == null || !this.entitySchemas.containsKey(it.getName()))
		)
			.filter(it -> this.removedEntitySchemas == null || !this.removedEntitySchemas.contains(it.getName()))
			.toList();
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		if (this.entitySchemas == null) {
			if (this.baseAccessor == null) {
				return empty();
			} else {
				return this.removedEntitySchemas == null || !this.removedEntitySchemas.contains(entityType) ?
					this.baseAccessor.getEntitySchema(entityType) : empty();
			}
		} else {
			final EntitySchemaContract updatedSchema = this.entitySchemas.get(entityType);
			if (updatedSchema != null) {
				return of(updatedSchema);
			} else if (this.baseAccessor != null) {
				return this.removedEntitySchemas == null || !this.removedEntitySchemas.contains(entityType) ?
					this.baseAccessor.getEntitySchema(entityType) : empty();
			} else {
				return empty();
			}
		}
	}
}
