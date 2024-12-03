/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Interface enables deep fetching of the {@link ReferenceContract referenced entities}. The entities might be filtered
 * and sorted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface ReferenceFetcher {
	ReferenceFetcher NO_IMPLEMENTATION = new ReferenceFetcher() {

		@Nonnull
		@Override
		public <T extends SealedEntity> T initReferenceIndex(@Nonnull T entity, @Nonnull EntityCollectionContract entityCollection) {
			return entity;
		}

		@Nonnull
		@Override
		public <T extends SealedEntity> List<T> initReferenceIndex(@Nonnull List<T> entities, @Nonnull EntityCollectionContract entityCollection) {
			return entities;
		}

		@Nonnull
		@Override
		public EvitaRequest getEnvelopingEntityRequest() {
			throw new UnsupportedOperationException("No implementation");
		}

		@Nullable
		@Override
		public Function<Integer, EntityClassifierWithParent> getParentEntityFetcher() {
			return null;
		}

		@Nullable
		@Override
		public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return null;
		}

		@Nullable
		@Override
		public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return null;
		}

		@Nonnull
		@Override
		public ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema) {
			return ReferenceComparator.DEFAULT;
		}

		@Nullable
		@Override
		public BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema) {
			return null;
		}

	};

	/**
	 * Method captures information from entity {@link ReferenceContract} and fetches the referenced entities so that
	 * the supporting methods can be instantiated:
	 *
	 * - {@link #getEntityFetcher(ReferenceSchemaContract)}
	 * - {@link #getEntityGroupFetcher(ReferenceSchemaContract)}
	 * - {@link #getEntityFilter(ReferenceSchemaContract)}
	 * - {@link #getEntityComparator(ReferenceSchemaContract)}
	 *
	 * The reason for up front initialization is that we want to fetch / filter/ sort the data in "bulk" to minimize
	 * the computational overhead. The logic must accept entity even if it's only partially loaded - the information
	 * about references might be missing (the entity might have been taken from the cache) and needs to be fetched.
	 *
	 * @param entity holding the information about references
	 * @param entityCollection for lazy fetching reference container for the passed entity if its missing
	 */
	@Nonnull
	<T extends SealedEntity> T initReferenceIndex(
		@Nonnull T entity,
		@Nonnull EntityCollectionContract entityCollection
	);

	/**
	 * Method captures information from entity {@link ReferenceContract} and fetches the referenced entities so that
	 * the supporting methods can be instantiated:
	 *
	 * - {@link #getEntityFetcher(ReferenceSchemaContract)}
	 * - {@link #getEntityGroupFetcher(ReferenceSchemaContract)}
	 * - {@link #getEntityFilter(ReferenceSchemaContract)}
	 * - {@link #getEntityComparator(ReferenceSchemaContract)}
	 *
	 * The reason for up front initialization is that we want to fetch / filter/ sort the data in "bulk" to minimize
	 * the computational overhead. The logic must accept entity even if it's only partially loaded - the information
	 * about references might be missing (the entity might have been taken from the cache) and needs to be fetched.
	 *
	 * @param entities list of entities holding the information about references
	 * @param entityCollection for lazy fetching reference container for the passed entity if its missing
	 */
	@Nonnull
	<T extends SealedEntity> List<T> initReferenceIndex(
		@Nonnull List<T> entities,
		@Nonnull EntityCollectionContract entityCollection
	);

	/**
	 * Creates a fetcher lambda that for passed entity parent primary key fetches the rich form of the entity.
	 * The fetcher is expected to provide only access to the data fetched during `initReferenceIndex` methods.
	 * If none the init methods is not called, the exception is thrown.
	 */
	@Nullable
	Function<Integer, EntityClassifierWithParent> getParentEntityFetcher();

	/**
	 * Creates a fetcher lambda that for passed referenced entity primary key fetches the rich form of the entity.
	 * The fetcher is expected to provide only access to the data fetched during `initReferenceIndex` methods.
	 * If none the init methods is not called, the exception is thrown.
	 */
	@Nullable
	Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Creates a fetcher lambda that for passed referenced entity group primary key fetches the rich form of the entity.
	 * The fetcher is expected to provide only access to the data fetched during `initReferenceIndex` methods.
	 * If none the init methods is not called, the exception is thrown.
	 */
	@Nullable
	Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Creates a comparator that orders the references according to requirements.
	 * The comparator is created during `initReferenceIndex` methods invocation, and takes advantage of the indexes.
	 *
	 * @return null if the references should remain in the order they were fetched
	 */
	@Nullable
	ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Returns FALSE if the entity should contain references with empty {@link ReferenceDecorator#getReferencedEntity()}.
	 * The predicate is created during `initReferenceIndex` methods invocation, and takes advantage of the indexes.
	 */
	@Nullable
	BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema);

	/**
	 * Returns evita request that should be used to fetch top-level (enveloping) entity. The request may contain
	 * extended requirements so that the comparators have all the necessary data.
	 *
	 * @return request that should be used to fetch top-level entity
	 */
	@Nonnull
	EvitaRequest getEnvelopingEntityRequest();

}
