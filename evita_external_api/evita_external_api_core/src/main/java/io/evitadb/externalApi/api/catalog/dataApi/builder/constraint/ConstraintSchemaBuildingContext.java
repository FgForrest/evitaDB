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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Context holding and caching constraint data during constraint schema building. This context object is supposed to be
 * shared across all constraint schema builders to ensure correct cache usage.
 *
 * @param <SIMPLE_TYPE> type that references remote object or scalar and can be safely used anywhere
 * @param <OBJECT_TYPE> type that holds actual full object that others reference to, needs to be registered
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class ConstraintSchemaBuildingContext<SIMPLE_TYPE, OBJECT_TYPE> {

	@Nonnull
	@Getter
	protected final CatalogContract catalog;
	/**
	 * Already built containers ready for reuse.
	 */
	protected final Map<ContainerKey, SIMPLE_TYPE> cachedContainers = createHashMap(200);
	/**
	 * Already built wrapper objects ready for reuse.
	 */
	protected final Map<WrapperObjectKey, SIMPLE_TYPE> cachedWrapperObjects = createHashMap(200);

	/**
	 * Built schema types during build process that have to be registered in the API schema because build process
	 * works mainly with type references due to the cycling references to types.
	 */
	protected final List<OBJECT_TYPE> builtTypes = new LinkedList<>();

	/**
	 * Returns {@link EntitySchemaContract} for passed `entityType`.
	 */
	@Nonnull
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return this.catalog.getEntitySchema(entityType).map(it -> it);
	}

	/**
	 * Returns {@link EntitySchemaContract} for passed `entityType`. If missing throws {@link ExternalApiInternalError}.
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchemaOrThrowException(@Nonnull String entityType) {
		return getEntitySchema(entityType)
			.orElseThrow(() -> new ExternalApiInternalError("Could not find required schema for entity `" + entityType + "`."));
	}

	/**
	 * Caches created container under specified key if absent.
	 */
	public void cacheContainer(@Nonnull ContainerKey key, @Nonnull SIMPLE_TYPE container) {
		this.cachedContainers.putIfAbsent(key, container);
	}

	/**
	 * Tries to find cached container for specified key.
	 */
	@Nullable
	public SIMPLE_TYPE getCachedContainer(@Nonnull ContainerKey key) {
		return this.cachedContainers.get(key);
	}

	/**
	 * Removes cached container for specified key
	 */
	public void removeCachedContainer(@Nonnull ContainerKey key) {
		this.cachedContainers.remove(key);
	}

	/**
	 * Caches created container under specified key if absent.
	 */
	public void cacheWrapperObject(@Nonnull WrapperObjectKey key, @Nonnull SIMPLE_TYPE wrapperObject) {
		this.cachedWrapperObjects.putIfAbsent(key, wrapperObject);
	}

	/**
	 * Tries to find cached container for specified key.
	 */
	@Nullable
	public SIMPLE_TYPE getCachedWrapperObject(@Nonnull WrapperObjectKey key) {
		return this.cachedWrapperObjects.get(key);
	}

	/**
	 * Add new built type that will be later registered.
	 */
	public void addNewType(@Nonnull OBJECT_TYPE type) {
		this.builtTypes.add(type);
	}

	/**
	 * Get all built types that needs registering.
	 */
	@Nonnull
	public List<OBJECT_TYPE> getBuiltTypes() {
		return Collections.unmodifiableList(this.builtTypes);
	}

}
