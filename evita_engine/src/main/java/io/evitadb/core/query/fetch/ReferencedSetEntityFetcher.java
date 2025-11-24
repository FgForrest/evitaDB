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

package io.evitadb.core.query.fetch;


import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceSetFetcher;
import io.evitadb.api.requestResponse.data.structure.References.ChunkTransformerAccessor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Minimal implementation of {@link ReferenceSetFetcher} that provides access to prefetched referenced entities
 * and their groups. This class was refactored out from {@link ReferencedEntityFetcher} to allow separate use for
 * named reference sets in {@link ServerEntityDecorator}.
 *
 * The implementation represents the minimal interface for serving separate reference sets with different filtering,
 * ordering, or slicing operations from the same source of base entity references. This allows multiple views of the
 * same reference data with different constraints applied to each view.
 *
 * @param fetchedEntities Index of prefetched entities assembled in constructor and quickly available when the entity is
 *                        requested by the {@link EntityDecorator} constructor.
 *                        The key is {@link ReferenceSchemaContract#getName()}, the value is the information containing
 *                        the indexes of fetched entities and their groups, information about their ordering and
 *                        validity index.
 * @param chunkTransformerAccessor Function providing access to {@link ChunkTransformer} implementations for particular
 *                                 references. Accessor is simple wrapper over {@link EvitaRequest} method references.
 *
 * @see PrefetchedEntities
 * @see ReferencedEntityFetcher
 * @see ReferenceSetFetcher
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ReferencedSetEntityFetcher(
	@Nonnull Map<String, PrefetchedEntities> fetchedEntities,
	@Nonnull ChunkTransformerAccessor chunkTransformerAccessor
) implements ReferenceSetFetcher {

	@Nonnull
	@Override
	public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		final PrefetchedEntities prefetchedEntities = this.fetchedEntities.get(referenceSchema.getName());
		if (prefetchedEntities == null) {
			return Functions.noOpFunction();
		} else {
			return prefetchedEntities::getEntity;
		}
	}

	@Nonnull
	@Override
	public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		final PrefetchedEntities prefetchedEntities = this.fetchedEntities.get(referenceSchema.getName());
		if (prefetchedEntities == null) {
			return Functions.noOpFunction();
		} else {
			return prefetchedEntities::getGroupEntity;
		}
	}

	@Nullable
	@Override
	public ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema) {
		final PrefetchedEntities prefetchedEntities = this.fetchedEntities.get(referenceSchema.getName());
		if (prefetchedEntities == null) {
			return null;
		} else {
			return prefetchedEntities.referenceComparator();
		}
	}

	@Nullable
	@Override
	public BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema) {
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return null;
		} else {
			final PrefetchedEntities prefetchedEntities = this.fetchedEntities.get(referenceSchema.getName());
			if (prefetchedEntities == null) {
				return null;
			} else {
				final ValidEntityToReferenceMapping vm = prefetchedEntities.validityMapping();
				return vm == null ?
					null :
					(entityPrimaryKey, referenceDecorator) ->
						ofNullable(referenceDecorator)
							.map(refDec -> vm.isReferenceSelected(entityPrimaryKey, refDec))
							.orElse(false);
			}
		}
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> createChunk(
		@Nonnull Entity entity,
		@Nonnull String referenceName,
		@Nonnull List<ReferenceContract> references
	) {
		Assert.isPremiseValid(
			this.fetchedEntities != null,
			() -> new GenericEvitaInternalError("Method `prefetchEntities` must be called prior creating chunks!")
		);
		return this.chunkTransformerAccessor.apply(referenceName)
			.createChunk(references);
	}

}
