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

package io.evitadb.core;

import io.evitadb.api.CatalogState;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This interface needs to be implemented by all data structures that are working with current {@link Catalog} instance
 * or objects that are related to the actual catalog. The catalog instance is constantly changing during its
 * transactional updates because it represents the root of the instance graph that aggregates all other objects in
 * the particular version of the catalog.
 *
 * Beware!!! Late initialization data structures must always create new instance in
 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
 * method in order not to share the same instance of {@link Catalog} related data between different versions of
 * the catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CatalogRelatedDataStructure<T extends CatalogRelatedDataStructure<T>> {

	/**
	 * This method attaches the data structure to the particular catalog instance (version).
	 * It is responsible iterating over its "children" objects and propagating the attachment to them as well.
	 * The implementation must check that the attachment happens exactly once and throws an exception if not.
	 *
	 * @param entityType the type of the entity in which context the call is made
	 * @param catalog the catalog instance to which the data structure should be attached
	 * @throws EvitaInternalError if the data structure is already attached to a catalog
	 */
	void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) throws EvitaInternalError;

	/**
	 * This method creates new instance of the data structure that keeps the same state as the original one but is
	 * not attached to any catalog at all - including all "children" objects. This method is used for reattaching
	 * the data structure to the new version of the catalog, but leaving the original instance attached to the previous
	 * one.
	 *
	 * @return the new instance of the data structure that is not attached to any catalog
	 */
	@Nonnull
	T createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState);

}
