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

package io.evitadb.api.requestResponse.data.structure;


import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DataChunk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Minimal interface for fetching referenced entities and their groups with support for filtering, ordering,
 * and slicing operations. This interface was refactored out from {@link ReferenceFetcher} to allow separate
 * use for named reference sets in entity decorators, enabling multiple independent views of the same reference
 * data within a single entity.
 *
 * The interface represents the minimal contract for serving separate reference sets with different filtering,
 * ordering, or slicing operations from the same source of base entity references. This enables multiple views
 * of the same reference data, each with different constraints applied (e.g., different filters, ordering rules,
 * or pagination settings), which is particularly useful when the same entity needs to expose its references
 * in multiple ways based on different query requirements.
 *
 * Implementations of this interface provide access to:
 * <ul>
 *     <li>Referenced entity fetchers - for retrieving rich entity forms by their primary keys</li>
 *     <li>Referenced entity group fetchers - for retrieving entity group forms by their primary keys</li>
 *     <li>Entity comparators - for ordering references according to query requirements</li>
 *     <li>Entity filters - for filtering references based on query constraints</li>
 *     <li>Chunk creation - for paginating reference collections</li>
 * </ul>
 *
 * @see ReferenceFetcher
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ReferenceSetFetcher {

	/**
	 * Creates a fetcher lambda that for passed referenced entity primary key fetches the rich form of the entity.
	 * The fetcher is expected to provide only access to the data fetched during `initReferenceIndex` methods.
	 * If none the init methods is not called, the exception is thrown.
	 *
	 * @param referenceSchema the reference schema for which the attribute content is requested
	 * @return fetcher lambda that retrieves the entity by its primary key
	 */
	@Nonnull
	Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Creates a fetcher lambda that for passed referenced entity group primary key fetches the rich form of the entity.
	 * The fetcher is expected to provide only access to the data fetched during `initReferenceIndex` methods.
	 * If none the init methods is not called, the exception is thrown.
	 *
	 * @param referenceSchema the reference schema for which the attribute content is requested
	 * @return fetcher lambda that retrieves the entity group by its primary key
	 */
	@Nonnull
	Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Creates a comparator that orders the references according to requirements.
	 * The comparator is created during `initReferenceIndex` methods invocation, and takes advantage of the indexes.
	 *
	 * @param referenceSchema the reference schema for which the attribute content is requested
	 * @return null if the references should remain in the order they were fetched
	 */
	@Nullable
	ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Returns FALSE if the entity should contain references with empty {@link ReferenceDecorator#getReferencedEntity()}.
	 * The predicate is created during `initReferenceIndex` methods invocation, and takes advantage of the indexes.
	 *
	 * @param referenceSchema the reference schema for which the attribute content is requested
	 * @return filtering predicate or null if no filtering should be applied
	 */
	@Nullable
	BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Retrieves the list of entity content requirements that are scheduled for prefetching.
	 *
	 * @param referenceSchema the reference schema for which the attribute content is requested
	 * @return the attribute content requirementsto prefetch or null if no attributes should be prefetched
	 */
	@Nullable
	AttributeContent getAttributeContentToPrefetch(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Creates a chunk of data containing reference contracts. This method processes the provided entity,
	 * the name of the reference, and a list of reference contracts to produce a structured data chunk.
	 *
	 * @param entity        the entity containing reference information
	 * @param referenceName the name of the reference being processed
	 * @param references    the list of references to be included in the data chunk
	 * @return a data chunk containing the specified reference contracts
	 */
	@Nonnull
	DataChunk<ReferenceContract> createChunk(
		@Nonnull Entity entity,
		@Nonnull String referenceName,
		@Nonnull List<ReferenceContract> references
	);
}
