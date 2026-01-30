/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.core.query.response;

import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.EntityFetchAwareDecorator;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.ReferenceSetFetcher;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceAttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.fetch.ReferencedEntityFetcher;
import io.evitadb.dataType.DataChunk;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * This class is a server-side model decorator that adds the number of I/O fetches and bytes fetched from underlying
 * storage to the entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ServerEntityDecorator extends EntityDecorator implements EntityFetchAwareDecorator {
	@Serial private static final long serialVersionUID = 3606251189133258706L;

	/**
	 * The count of I/O fetches used to load this entity from underlying storage.
	 */
	private final int ioFetchCount;
	/**
	 * The count of bytes fetched from underlying storage to load this entity and all referenced entities.
	 */
	private final int ioFetchedBytes;
	/**
	 * Memoized ioFetchCount when {@link #getIoFetchCount()} is called for the first time.
	 */
	private int memoizedIoFetchCount = -1;
	/**
	 * Memoized ioFetchedBytes when {@link #getIoFetchedBytes()} is called for the first time.
	 */
	private int memoizedIoFetchedBytes = -1;
	/**
	 * Specialized reference sets accessible by reference content instance name.
	 */
	@Nullable private Map<ReferenceContentKey, DataChunk<ReferenceContract>> namedReferenceSets;

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 */
	@Nonnull
	public static ServerEntityDecorator decorate(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		return new ServerEntityDecorator(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes
		);
	}

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 */
	@Nonnull
	public static ServerEntityDecorator decorate(
		@Nonnull ServerEntityDecorator entity,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		return new ServerEntityDecorator(
			entity, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes,
			entity.namedReferenceSets
		);
	}

	public ServerEntityDecorator(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull ServerEntityDecorator entity,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		super(entity, parentEntity, referenceFetcher, evitaRequest);
		this.ioFetchCount = entity.getIoFetchCount();
		this.ioFetchedBytes = entity.getIoFetchedBytes();
	}

	private ServerEntityDecorator(
		@Nonnull Entity delegate,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		super(
			delegate, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate, attributePredicate, associatedDataPredicate,
			referencePredicate, pricePredicate,
			alignedNow
		);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

	public ServerEntityDecorator(
		@Nonnull ServerEntityDecorator delegate,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes,
		@Nullable Map<ReferenceContentKey, DataChunk<ReferenceContract>> namedReferenceSets
	) {
		super(
			delegate, parentEntity, localePredicate, hierarchyPredicate, attributePredicate, associatedDataPredicate,
			referencePredicate, pricePredicate, alignedNow
		);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
		this.namedReferenceSets = namedReferenceSets;
	}

	@Override
	protected int fillFilteredSortedAndFetchedReferences(
		int entityPrimaryKey,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull ReferenceSetFetcher referenceFetcher,
		@Nonnull ReferenceContract[] inputReferences,
		@Nonnull ReferenceDecorator[] outputReferences,
		@Nullable EvitaRequest evitaRequest
	) {
		if (evitaRequest != null && referenceFetcher instanceof ReferencedEntityFetcher serverFetcher) {
			final Map<ReferenceContentKey, RequirementContext> namedReferenceEntityFetch = evitaRequest.getNamedReferenceEntityFetch();
			if (!namedReferenceEntityFetch.isEmpty()) {
				final Entity entity = getDelegate();
				this.namedReferenceSets = CollectionUtils.createHashMap(namedReferenceEntityFetch.size());
				ReferenceSchemaContract referenceSchema = null;
				int start = 0;
				int end = inputReferences.length;
				// iterator is sorted by ReferenceContentKey natural ordering
				for (Map.Entry<ReferenceContentKey, RequirementContext> entry : namedReferenceEntityFetch.entrySet()) {
					final ReferenceContentKey rck = entry.getKey();
					final String referenceName = rck.referenceName();
					// find the range of references with this name
					if (referenceSchema == null || !referenceSchema.getName().equals(referenceName)) {
						referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
						final int middle = ArrayUtils.binarySearch(
							inputReferences,
							referenceName,
							Math.max(start, 0),
							inputReferences.length,
							(referenceContract, rn) -> referenceContract.getReferenceName().compareTo(rn)
						);
						if (middle < 0) {
							start = -1;
							end = -1;
						} else {
							start = middle;
							while (start > 0 && inputReferences[start - 1].getReferenceName().equals(referenceName)) {
								start--;
							}
							end = middle;
							while (end < inputReferences.length && inputReferences[end].getReferenceName().equals(
								referenceName)) {
								end++;
							}
						}
					}

					if (start == -1) {
						this.namedReferenceSets.put(
							rck,
							referenceFetcher.createChunk(
								entity,
								referenceName,
								Collections.emptyList()
							)
						);
					} else {
						final ReferenceSetFetcher mrf = serverFetcher.getMinimalReferenceFetcher(
							Objects.requireNonNull(rck.instanceName())
						);
						final Function<Integer, SealedEntity> entityFetcher = mrf.getEntityFetcher(referenceSchema);
						final Function<Integer, SealedEntity> entityGroupFetcher = mrf.getEntityGroupFetcher(referenceSchema);
						final BiPredicate<Integer, ReferenceDecorator> referenceFilter = mrf.getEntityFilter(referenceSchema);
						final ReferenceComparator fetchedReferenceComparator = mrf.getEntityComparator(referenceSchema);
						final AttributeContent attributeContentToPrefetch = mrf.getAttributeContentToPrefetch(referenceSchema);

						final ReferenceContractSerializablePredicate namedReferencePredicate =
							new ReferenceContractSerializablePredicate(
								evitaRequest,
								referenceName,
								attributeContentToPrefetch == null ?
									entry.getValue() :
									entry.getValue().withExtendedAttributeContentRequirement(attributeContentToPrefetch)
							);
						final ReferenceAttributeValueSerializablePredicate namedAttributePredicate =
							namedReferencePredicate.getAttributePredicate(referenceName);
						final int size = end - start;
						for (int i = 0; i < size; i++) {
							final ReferenceContract referenceContract = inputReferences[start + i];
							outputReferences[i] = ofNullable(
								fetchReference(
									referenceContract,
									referenceSchema,
									entityFetcher,
									entityGroupFetcher,
									namedReferencePredicate
								)
							).orElseGet(
								() -> new ReferenceDecorator(
									referenceContract,
									namedAttributePredicate
								)
							);
						}

						final int filteredOutReferences = sortAndFilterSubList(
							entityPrimaryKey,
							outputReferences,
							namedReferencePredicate,
							referenceFilter,
							fetchedReferenceComparator,
							0, size
						);
						final DataChunk<ReferenceContract> chunk = mrf.createChunk(
							entity,
							referenceName,
							Arrays.asList(Arrays.copyOf(outputReferences, size - filteredOutReferences))
						);
						this.namedReferenceSets.put(rck, chunk);
					}
				}
			}
		}

		return super.fillFilteredSortedAndFetchedReferences(
			entityPrimaryKey, entitySchema, referencePredicate, referenceFetcher,
			inputReferences, outputReferences,
			evitaRequest
		);
	}

	/**
	 * Returns the filtered, sorted and deeply fetched references identified by special reference content instance name,
	 * if the named request exists.
	 *
	 * @param instanceName name of the reference content instance
	 * @return collection of references
	 */
	@Nonnull
	public Optional<DataChunk<ReferenceContract>> getReferencesForReferenceContentInstance(@Nonnull ReferenceContentKey instanceName) {
		if (this.namedReferenceSets == null) {
			return empty();
		}
		final DataChunk<ReferenceContract> referenceChunk = this.namedReferenceSets.get(instanceName);
		return ofNullable(referenceChunk);
	}

	@Override
	public int getIoFetchCount() {
		if (this.memoizedIoFetchCount == -1) {
			this.memoizedIoFetchCount = this.ioFetchCount +
				(
					parentAvailable() ?
						getParentEntity()
							.filter(ServerEntityDecorator.class::isInstance)
							.map(ServerEntityDecorator.class::cast)
							.map(ServerEntityDecorator::getIoFetchCount)
							.orElse(0)
						:
						0
				) +
				(
					referencesAvailable() ?
						getReferences().stream()
							.map(ReferenceContract::getReferencedEntity)
							.filter(Optional::isPresent)
							.map(Optional::get)
							.map(ServerEntityDecorator.class::cast)
							.mapToInt(ServerEntityDecorator::getIoFetchCount)
							.sum()
						:
						0
				);
		}
		return this.memoizedIoFetchCount;
	}

	@Override
	public int getIoFetchedBytes() {
		if (this.memoizedIoFetchedBytes == -1) {
			this.memoizedIoFetchedBytes = this.ioFetchedBytes +
				(
					parentAvailable() ?
						getParentEntity()
							.filter(ServerEntityDecorator.class::isInstance)
							.map(ServerEntityDecorator.class::cast)
							.map(ServerEntityDecorator::getIoFetchedBytes)
							.orElse(0)
						:
						0
				) +
				(
					referencesAvailable() ?
						getReferences().stream()
							.map(ReferenceContract::getReferencedEntity)
							.filter(Optional::isPresent)
							.map(Optional::get)
							.map(ServerEntityDecorator.class::cast)
							.mapToInt(ServerEntityDecorator::getIoFetchedBytes)
							.sum()
						:
						0
				);
		}
		return this.memoizedIoFetchedBytes;
	}
}
