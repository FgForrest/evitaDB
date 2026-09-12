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
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * This class is a server-side model decorator that adds the number of I/O fetches and bytes fetched from underlying
 * storage to the entity, along with the provenance of the data it carries - the identity and version of the committed
 * catalog snapshot they were materialised from.
 *
 * Every construction path has to honour one rule, and a new one must be placed in the right family before anything
 * else is decided about it:
 *
 * - a path that **materialises** data from storage stamps the identity and version it read at;
 * - a path that merely **wraps** or narrows an existing decorator inherits `catalogId` and `catalogVersion` from it,
 *   because wrapping performs no read and the data stay exactly as current - or as stale - as what it wraps;
 * - a path that cannot vouch for the provenance passes NULL and {@link #UNKNOWN_CATALOG_VERSION}, which never compare
 *   equal to a real snapshot.
 *
 * Both halves are load-bearing: versions are numbered per catalog, so the version alone would let an entity carried
 * over from an unrelated catalog pass for current. A wrapping path that takes a fresh version instead of inheriting
 * one is the way this breaks silently - stale data start looking current, and the enrichment shortcut in
 * `EntityCollection#enrichEntityInternal`, which keys on {@link #isMaterialisedFrom(UUID, long)} to skip a storage
 * round trip, hands them straight back untouched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ServerEntityDecorator extends EntityDecorator implements EntityFetchAwareDecorator {
	@Serial private static final long serialVersionUID = 3606251189133258706L;
	/**
	 * Value {@link #catalogVersion} carries when the data behind the decorator did not come from a committed,
	 * immutable catalog snapshot - a cache restore, a catalog that is still warming up, or anything materialised
	 * while a transaction was in flight. Catalog versions are drawn from a monotonically increasing, never-negative
	 * sequence, so this never compares equal to one and a decorator carrying it can never take a shortcut that
	 * assumes its data are still current.
	 */
	public static final long UNKNOWN_CATALOG_VERSION = -1L;
	/**
	 * Value {@link #resolvedIoFetchCount} and {@link #resolvedIoFetchedBytes} carry until they are first asked for -
	 * neither statistic can legitimately be negative.
	 */
	private static final int NOT_RESOLVED = -1;

	/**
	 * The count of I/O fetches used to load this entity from underlying storage, **excluding** those its
	 * {@link #deferredIoStatisticsSource} still owes.
	 */
	private final int ioFetchCount;
	/**
	 * The count of bytes fetched from underlying storage to load this entity, **excluding** those its
	 * {@link #deferredIoStatisticsSource} still owes.
	 */
	private final int ioFetchedBytes;
	/**
	 * Version of the committed, immutable catalog snapshot the entity data behind this decorator came from, or
	 * {@link #UNKNOWN_CATALOG_VERSION} when they came from no such snapshot. Two decorators agreeing on both this
	 * value and {@link #catalogId} therefore carry byte-identical stored data by construction - which is what lets
	 * an enrichment that widens nothing skip the storage round trip instead of re-reading the body only to compare
	 * versions.
	 *
	 * A version is recordable only while the catalog actually versions its snapshots, which is narrower than it
	 * looks: a warming-up catalog never moves its version while the data underneath it change freely, and an
	 * uncommitted - possibly later rolled back - transaction overlay shares the version of the snapshot it is based
	 * on. Data read in either situation therefore carry {@link #UNKNOWN_CATALOG_VERSION}; see
	 * {@link #isMaterialisedFrom(UUID, long)}.
	 *
	 * A decorator that merely wraps another one inherits the wrapped decorator's provenance rather than taking a
	 * fresh one: wrapping performs no read, so the data are exactly as current (or as stale) as what it wraps.
	 */
	private final long catalogVersion;
	/**
	 * Identity of the catalog the snapshot named by {@link #catalogVersion} belongs to, or NULL when the provenance
	 * is unknown. Versions are numbered per catalog rather than globally, so the version alone does not identify a
	 * snapshot - two unrelated catalogs sitting at the same number have nothing in common, and an entity carried
	 * from one into a session for the other would otherwise pass for current there.
	 *
	 * The identifier is created once per catalog and carried forward across every version of it, so this costs one
	 * shared reference per decorator. The {@link io.evitadb.core.catalog.Catalog} itself is deliberately not
	 * retained: holding it would pin the whole query execution context it belongs to.
	 */
	@Nullable private final UUID catalogId;
	/**
	 * {@link #ioFetchCount} completed with the aggregate the {@link #deferredIoStatisticsSource} owes, memoized on
	 * the first ask; {@link #NOT_RESOLVED} until then.
	 */
	private int resolvedIoFetchCount = NOT_RESOLVED;
	/**
	 * {@link #ioFetchedBytes} completed with the aggregate the {@link #deferredIoStatisticsSource} owes, memoized on
	 * the first ask; {@link #NOT_RESOLVED} until then.
	 */
	private int resolvedIoFetchedBytes = NOT_RESOLVED;
	/**
	 * Decorator this one wraps, whose aggregated I/O statistics form the base of this decorator's own. NULL for
	 * a decorator that was handed its statistics directly; otherwise it stays in place for the decorator's whole life,
	 * because {@link #resolveDeferredIoStatistics()} reads it rather than consuming it.
	 *
	 * Aggregating the statistics eagerly in the constructor is what makes this expensive: both
	 * {@link #getIoFetchCount()} and {@link #getIoFetchedBytes()} walk the entire reference graph, and the fetch
	 * pipeline wraps every entity several times over (limit, enrich, decorate), so the walk was repeated once per
	 * wrapping rather than once per entity. Deferring it means an entity nobody asks about pays nothing, and the
	 * response-level aggregate walks the graph once.
	 */
	@Nullable private ServerEntityDecorator deferredIoStatisticsSource;
	/**
	 * Specialized reference sets accessible by reference content instance name.
	 */
	@Nullable private Map<ReferenceContentKey, DataChunk<ReferenceContract>> namedReferenceSets;

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 *
	 * @param catalogId      identity of the catalog the snapshot belongs to, or NULL when the caller cannot vouch for
	 *                       the provenance
	 * @param catalogVersion version of the committed snapshot the entity data came from, or
	 *                       {@link #UNKNOWN_CATALOG_VERSION} when the caller cannot vouch for it
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
		@Nullable UUID catalogId,
		long catalogVersion,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		return decorate(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			catalogId, catalogVersion, ioFetchCount, ioFetchedBytes, null
		);
	}

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 *
	 * @param catalogId                  identity of the catalog the snapshot belongs to, or NULL when the caller
	 *                                   cannot vouch for the provenance
	 * @param catalogVersion             version of the committed snapshot the entity data came from, or
	 *                                   {@link #UNKNOWN_CATALOG_VERSION} when the caller cannot vouch for it
	 * @param ioFetchCount               reads performed to produce THIS decorator, excluding those already accounted
	 *                                   for by `deferredIoStatisticsSource`
	 * @param deferredIoStatisticsSource decorator whose aggregated statistics complete this one's, resolved only if
	 *                                   somebody actually asks for {@link #getIoFetchCount()}; see
	 *                                   {@link #deferredIoStatisticsSource}
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
		@Nullable UUID catalogId,
		long catalogVersion,
		int ioFetchCount,
		int ioFetchedBytes,
		@Nullable ServerEntityDecorator deferredIoStatisticsSource
	) {
		final ServerEntityDecorator result = new ServerEntityDecorator(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			catalogId, catalogVersion, ioFetchCount, ioFetchedBytes
		);
		result.deferredIoStatisticsSource = deferredIoStatisticsSource;
		return result;
	}

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched. There is no `catalogVersion` parameter on purpose - this factory merely re-wraps `entity`, so the
	 * result inherits its provenance.
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
		return decorate(
			entity, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes, null
		);
	}

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched. There is no `catalogVersion` parameter on purpose - this factory merely re-wraps `entity`, so the
	 * result inherits its provenance.
	 *
	 * @param ioFetchCount               reads performed to produce THIS decorator, excluding those already accounted
	 *                                   for by `deferredIoStatisticsSource`
	 * @param deferredIoStatisticsSource decorator whose aggregated statistics complete this one's, resolved only if
	 *                                   somebody actually asks for {@link #getIoFetchCount()}; see
	 *                                   {@link #deferredIoStatisticsSource}
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
		int ioFetchedBytes,
		@Nullable ServerEntityDecorator deferredIoStatisticsSource
	) {
		final ServerEntityDecorator result = new ServerEntityDecorator(
			entity, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes,
			entity.namedReferenceSets
		);
		result.deferredIoStatisticsSource = deferredIoStatisticsSource;
		return result;
	}

	/**
	 * Wrapping constructor that re-fetches the wrapped entity's references through the passed fetcher. It belongs to
	 * the **wrapping** family described in the class documentation: it reads nothing of its own, therefore it inherits
	 * the wrapped decorator's provenance and owes the whole of its I/O statistics to it.
	 *
	 * @param evitaRequest     request whose requirements drive the reference fetching
	 * @param entity           decorator being wrapped - the source of both the provenance and the I/O statistics
	 * @param parentEntity     parent entity to expose, or NULL when the request does not ask for one
	 * @param referenceFetcher fetcher producing the deeply fetched references
	 */
	public ServerEntityDecorator(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull ServerEntityDecorator entity,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		super(entity, parentEntity, referenceFetcher, evitaRequest);
		// the statistics are deliberately NOT resolved here (see #deferredIoStatisticsSource)
		this.catalogId = entity.catalogId;
		this.catalogVersion = entity.catalogVersion;
		this.ioFetchCount = 0;
		this.ioFetchedBytes = 0;
		this.deferredIoStatisticsSource = entity;
	}

	/**
	 * The only constructor of the **materialising** family described in the class documentation, and therefore the
	 * only one that accepts a provenance rather than inheriting one: the caller has just read the data and is the
	 * sole party that knows which snapshot they came from.
	 *
	 * @param catalogId      identity of the catalog the data were read from, or NULL when the caller cannot vouch for
	 *                       the provenance
	 * @param catalogVersion version of the committed snapshot the data were read at, or
	 *                       {@link #UNKNOWN_CATALOG_VERSION} when the caller cannot vouch for it
	 * @param ioFetchCount   reads performed to produce THIS decorator
	 * @param ioFetchedBytes bytes read to produce THIS decorator
	 */
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
		@Nullable UUID catalogId,
		long catalogVersion,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		super(
			delegate, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate, attributePredicate, associatedDataPredicate,
			referencePredicate, pricePredicate,
			alignedNow
		);
		this.catalogId = catalogId;
		this.catalogVersion = catalogVersion;
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

	/**
	 * Wrapping constructor that re-applies a narrower or wider set of predicates over an entity that is already in
	 * memory. It belongs to the **wrapping** family described in the class documentation: it reads nothing, therefore
	 * it inherits `delegate`'s provenance, and the statistics it is handed cover only whatever reads the caller
	 * performed on its behalf.
	 *
	 * @param delegate           decorator being wrapped - the source of the provenance
	 * @param ioFetchCount       reads the caller performed to produce THIS decorator; `delegate`'s own statistics are
	 *                           not folded in here - a caller that means the two to be aggregated goes through
	 *                           a `decorate` factory and hands it {@link #deferredIoStatisticsSource}
	 * @param ioFetchedBytes     bytes the caller read to produce THIS decorator, on the same terms
	 * @param namedReferenceSets reference sets keyed by reference content instance name, or NULL when the request
	 *                           declares none
	 */
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
		// wrapping reads nothing, so the data stay exactly as current as the decorator being wrapped
		this.catalogId = delegate.catalogId;
		this.catalogVersion = delegate.catalogVersion;
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
		resolveDeferredIoStatistics();
		return this.resolvedIoFetchCount;
	}

	@Override
	public int getIoFetchedBytes() {
		resolveDeferredIoStatistics();
		return this.resolvedIoFetchedBytes;
	}

	/**
	 * Returns the version of the committed catalog snapshot the entity data behind this decorator came from, or
	 * {@link #UNKNOWN_CATALOG_VERSION} when they came from no such snapshot.
	 *
	 * @return the catalog version this decorator's data came from
	 */
	public long getCatalogVersion() {
		return this.catalogVersion;
	}

	/**
	 * Returns the identity of the catalog the snapshot reported by {@link #getCatalogVersion()} belongs to, or NULL
	 * when the provenance is unknown.
	 *
	 * @return the catalog this decorator's data came from
	 */
	@Nullable
	public UUID getCatalogId() {
		return this.catalogId;
	}

	/**
	 * Tells whether the entity data behind this decorator came from exactly the committed catalog snapshot named by
	 * the passed identity and version, which is what makes them interchangeable with a fresh read of that snapshot.
	 * A decorator of unknown provenance answers FALSE for every snapshot, because {@link #UNKNOWN_CATALOG_VERSION}
	 * is negative and real catalog versions never are.
	 *
	 * @param catalogId      identity of the catalog to test against
	 * @param catalogVersion version of the snapshot to test against
	 * @return TRUE when this decorator's data came from that very snapshot
	 */
	public boolean isMaterialisedFrom(@Nonnull UUID catalogId, long catalogVersion) {
		return this.catalogVersion == catalogVersion && catalogId.equals(this.catalogId);
	}

	/**
	 * Completes this decorator's own I/O statistics with the aggregate of the decorator it wraps, memoizing the
	 * result. A decorator produced by an enrichment step carries the reads that step performed itself, while the
	 * reads that produced its input are still owed by the input decorator - resolving them here, and only here, is
	 * what keeps the whole chain lazy.
	 *
	 * The memo is **computed and assigned**, never accumulated into the decorator's own numbers, and the source is
	 * deliberately left in place. Both getters are public and reachable from response serialization, traffic
	 * recording and the metric events at once, and an accumulation guarded only by nulling the source doubles the
	 * entity's statistics permanently whenever two of them interleave. Assignment makes a duplicated resolution
	 * recompute the identical value instead, which is the benign race this used to have - the memos need no
	 * publication guarantee because a thread that misses one simply computes it again.
	 */
	private void resolveDeferredIoStatistics() {
		final ServerEntityDecorator source = this.deferredIoStatisticsSource;
		if (this.resolvedIoFetchCount == NOT_RESOLVED) {
			this.resolvedIoFetchCount = this.ioFetchCount + (source == null ? 0 : source.getIoFetchCount());
		}
		if (this.resolvedIoFetchedBytes == NOT_RESOLVED) {
			this.resolvedIoFetchedBytes = this.ioFetchedBytes + (source == null ? 0 : source.getIoFetchedBytes());
		}
	}

}
