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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
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
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService.ReadRecord;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
	 * Sentinel telling {@link #resolvedOwnReadRecords} apart from a chain that resolved to no identities at all.
	 * Distinct from {@link ReadRecord#NONE} by identity on purpose: "resolved to nothing" and "cannot be resolved"
	 * lead to opposite accounting, the first to a union and the second to the summed-ints fall-back.
	 */
	private static final ReadRecord[] UNIDENTIFIED_READ_RECORDS = new ReadRecord[0];

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
	 * {@link #ioFetchCount} completed with the whole of {@link #attachedBodies()}, memoized on the first ask;
	 * {@link #NOT_RESOLVED} until then. This is what {@link #getIoFetchCount()} answers.
	 */
	private int resolvedIoFetchCount = NOT_RESOLVED;
	/**
	 * {@link #ioFetchedBytes} completed with the whole of {@link #attachedBodies()}, memoized on the first ask;
	 * {@link #NOT_RESOLVED} until then. This is what {@link #getIoFetchedBytes()} answers.
	 */
	private int resolvedIoFetchedBytes = NOT_RESOLVED;
	/**
	 * {@link #ioFetchCount} summed along the {@link #deferredIoStatisticsSource} chain and **excluding** every
	 * attached body, memoized on the first ask; {@link #NOT_RESOLVED} until then. See
	 * {@link #resolveDeferredIoStatistics()} for why the two halves are kept apart.
	 */
	private int resolvedOwnIoFetchCount = NOT_RESOLVED;
	/**
	 * {@link #ioFetchedBytes} summed along the {@link #deferredIoStatisticsSource} chain and **excluding** every
	 * attached body, memoized on the first ask; {@link #NOT_RESOLVED} until then.
	 */
	private int resolvedOwnIoFetchedBytes = NOT_RESOLVED;
	/**
	 * Every entity this one reaches through the bodies it exposes, keyed by the entity it is and mapped to what
	 * that entity's **own** storage parts cost, memoized on the first ask; NULL until then. See
	 * {@link #reachableBodies()}.
	 *
	 * `volatile` because the memoized value is a map rather than an `int`: a reader seeing the reference before the
	 * map's own contents would iterate an empty or half-filled table and memoize a too-small aggregate, and unlike
	 * the `int` memos nothing would ever recompute it - the reference is already non-null. A shared referenced body
	 * is exactly the object several owners resolve at once.
	 */
	@Nullable private volatile Map<BodyKey, BodyCost> resolvedReachableBodies;
	/**
	 * Identities of the records **this** decorator's own composition read, or NULL when the caller did not carry
	 * them. A decorator that read nothing needs none, which is why NULL alone does not mean "unknown" - see
	 * {@link #ownReadRecords()}.
	 */
	@Nullable private final ReadRecord[] ownReadRecords;
	/**
	 * {@link #ownReadRecords} de-duplicated along the {@link #deferredIoStatisticsSource} chain, resolved on the
	 * first ask; NULL until then, {@link #UNIDENTIFIED_READ_RECORDS} once the chain turns out not to carry them for
	 * every link that read something.
	 *
	 * One field carrying both facts, rather than a value beside a `resolved` flag, and `volatile` rather than plain.
	 * "Resolved" and "unidentified" are both legitimate outcomes here, so a reader that saw the flag set before the
	 * value it guards would read a torn state as the real answer and memoize the summed-ints fall-back for good -
	 * a permanently wrong number rather than the benign repeated computation the `int` memos risk.
	 */
	@Nullable private volatile ReadRecord[] resolvedOwnReadRecords;
	/**
	 * Whether this decorator attached the bodies it exposes rather than inheriting them from the one it wraps.
	 *
	 * Only the constructor that runs a {@link ReferenceFetcher} attaches anything; every other one re-applies
	 * predicates over bodies that are already in place. That makes the reachable set a property of the decorator
	 * that fetched them, which the whole wrapping chain can then share instead of each link re-deriving it - and
	 * re-deriving it is what forces a narrowed decorator to materialize its filtered reference set purely to answer
	 * a statistic.
	 */
	private final boolean attachesBodies;
	/**
	 * Entities whose bodies this decorator had read and then dropped because they fell outside the requested chunk,
	 * mapped to what their own storage parts cost; NULL when the chunk dropped nothing.
	 *
	 * They are counted because the entity's own requirement caused the read: an ordering that ranks references by a
	 * property of their group cannot rank them without reading every candidate, so asking for the first five of a
	 * hundred reads a hundred. What the request then chose to show changes what the entity exposes, not what
	 * obtaining it cost. The bodies themselves are released as soon as this map is built - see
	 * {@link EntityDecorator#getChunkedOutReferences()}.
	 */
	@Nullable private Map<BodyKey, BodyCost> chunkedOutBodies;
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
	 *
	 * The `decorate` factories assign this **after** the constructor returns, so no statistic may be resolved in
	 * between: {@link #ownReadRecords()} and {@link #reachableBodies()} memoize permanently, and one resolved
	 * while this is still NULL would pin a source-less answer for the decorator's whole life. This field is safe
	 * unsynchronized only because it is written before the decorator escapes - not by the argument that made those
	 * two memos `volatile`.
	 */
	@Nullable private ServerEntityDecorator deferredIoStatisticsSource;
	/**
	 * Specialized reference sets accessible by reference content instance name.
	 */
	@Nullable private Map<ReferenceContentKey, DataChunk<ReferenceContract>> namedReferenceSets;
	/**
	 * The named requirements the sets above were built from, keyed the same way.
	 *
	 * Kept because a chunk cannot be re-produced from itself. An enrichment is additive: it must end up carrying
	 * every named set the entity already had plus whatever it asks for anew, and the earlier sets can only be
	 * rebuilt against the freshly read body if what the earlier request asked for is still known. Carrying the
	 * requirements rather than re-using the old chunks is what keeps an enrichment that lands on a newer body from
	 * answering out of the older one.
	 */
	@Nullable private Map<ReferenceContentKey, RequirementContext> namedReferenceRequirements;

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
			catalogId, catalogVersion, ioFetchCount, ioFetchedBytes, null, null
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
	 * @param ownReadRecords             identities of the records those reads obtained, or NULL when the caller does
	 *                                   not carry them; see {@link #ownReadRecords}
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
		@Nullable ServerEntityDecorator deferredIoStatisticsSource,
		@Nullable ReadRecord[] ownReadRecords
	) {
		return decorate(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate, attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate, alignedNow,
			catalogId, catalogVersion, ioFetchCount, ioFetchedBytes,
			deferredIoStatisticsSource, ownReadRecords,
			null, null
		);
	}

	/**
	 * The same, for a caller that also has named reference sets to place on the result - the narrowing half of
	 * enrichment, which keeps the sets a limiting request still asks for instead of re-reading them.
	 *
	 * @param namedReferenceSets         sets to expose by reference content instance name, NULL when there are none
	 * @param namedReferenceRequirements the requirements those sets were built from, keyed the same way
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
		@Nullable ServerEntityDecorator deferredIoStatisticsSource,
		@Nullable ReadRecord[] ownReadRecords,
		@Nullable Map<ReferenceContentKey, DataChunk<ReferenceContract>> namedReferenceSets,
		@Nullable Map<ReferenceContentKey, RequirementContext> namedReferenceRequirements
	) {
		final ServerEntityDecorator result = new ServerEntityDecorator(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			catalogId, catalogVersion, ioFetchCount, ioFetchedBytes, ownReadRecords
		);
		result.deferredIoStatisticsSource = deferredIoStatisticsSource;
		result.namedReferenceSets = namedReferenceSets;
		result.namedReferenceRequirements = namedReferenceRequirements;
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
			ioFetchCount, ioFetchedBytes, null, null
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
	 * @param ownReadRecords             identities of the records those reads obtained, or NULL when the caller does
	 *                                   not carry them; see {@link #ownReadRecords}
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
		@Nullable ServerEntityDecorator deferredIoStatisticsSource,
		@Nullable ReadRecord[] ownReadRecords
	) {
		final ServerEntityDecorator result = new ServerEntityDecorator(
			entity, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes,
			entity.namedReferenceSets, entity.namedReferenceRequirements, ownReadRecords
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
		// this is the one constructor that puts bodies onto an entity, so it is the one that derives what the
		// entity reaches; everything wrapping it inherits that instead of re-deriving it
		this.attachesBodies = true;
		this.ownReadRecords = ReadRecord.NONE;
		// the statistics are deliberately NOT resolved here (see #deferredIoStatisticsSource) - with one exception:
		// the bodies the chunking dropped are reachable from nowhere else, so what they cost is taken now and they
		// are released immediately, rather than keeping a discarded page of bodies alive to be asked later
		Map<BodyKey, BodyCost> dropped = null;
		for (ReferenceContract chunkedOut : getChunkedOutReferences()) {
			dropped = collectReferenceBodies(dropped, chunkedOut);
		}
		forgetChunkedOutReferences();
		// groups read for references whose own body the slicing dropped are carried by no reference at all - see
		// EntityDecorator#getUnexposedBodies
		for (SealedEntity unexposed : getUnexposedBodies()) {
			dropped = collectBody(dropped, unexposed);
		}
		forgetUnexposedBodies();
		this.chunkedOutBodies = dropped;
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
	 * @param ownReadRecords identities of the records those reads obtained, NULL when the caller does not carry them
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
		int ioFetchedBytes,
		@Nullable ReadRecord[] ownReadRecords
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
		this.ownReadRecords = ownReadRecords;
		// this constructor attaches no references, but it does attach whatever parent it is handed
		this.attachesBodies = parentEntity instanceof SealedEntity;
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
	 * @param namedReferenceRequirements the requirements those sets were built from, keyed the same way, or NULL
	 *                           when there are none
	 * @param ownReadRecords     identities of the records those reads obtained, NULL when the caller does not carry
	 *                           them
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
		@Nullable Map<ReferenceContentKey, DataChunk<ReferenceContract>> namedReferenceSets,
		@Nullable Map<ReferenceContentKey, RequirementContext> namedReferenceRequirements,
		@Nullable ReadRecord[] ownReadRecords
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
		this.namedReferenceRequirements = namedReferenceRequirements;
		this.ownReadRecords = ownReadRecords;
		// this constructor attaches no references, but it does attach whatever parent it is handed
		this.attachesBodies = parentEntity instanceof SealedEntity;
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
			// deliberately the FETCHER's map rather than the request's: on an enrichment it carries the named
			// requirements of every request that contributed to this entity, so the sets an earlier request asked
			// for are rebuilt here against the body this read just materialised instead of being lost or carried
			// over stale. On a first fetch the two maps are the same thing.
			final Map<ReferenceContentKey, RequirementContext> namedReferenceEntityFetch = serverFetcher.getNamedReferenceFetch();
			if (!namedReferenceEntityFetch.isEmpty()) {
				final Entity entity = getDelegate();
				this.namedReferenceRequirements = namedReferenceEntityFetch;
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
						final BiPredicate<Integer, ReferenceContract> referenceFilter = mrf.getEntityFilter(referenceSchema);
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
						final boolean referenceNameRequested = namedReferencePredicate.isReferenceRequested(referenceName);
						// `keptCount` counts the references that survived the filter, so it parts ways with the input
						// index at the first discard
						int keptCount = 0;
						// `BiPredicate<Integer, ...>` boxes its first argument once here rather than per reference
						final Integer boxedEntityPrimaryKey = entityPrimaryKey;
						for (int i = 0; i < size; i++) {
							final ReferenceContract referenceContract = inputReferences[start + i];
							// decide before decorating rather than after - `sortAndFilterSubList` below applies
							// exactly these three tests, and the decorator, the prefetched-body lookup and the
							// group resolution that building one costs are wasted on a reference that fails them.
							// An entity may carry tens of thousands of back-references of which the query keeps one
							if (!referenceNameRequested || !referenceContract.exists() ||
								(referenceFilter != null && !referenceFilter.test(boxedEntityPrimaryKey, referenceContract))) {
								continue;
							}
							outputReferences[keptCount++] = ofNullable(
								fetchReference(
									referenceContract,
									referenceSchema,
									entityFetcher,
									entityGroupFetcher,
									namedAttributePredicate
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
							0, keptCount
						);
						// only the references this entity kept - the group prefetch index is shared by the whole
						// batch, so a reference a filterBy excluded says nothing about what this entity read
						noteUnexposedGroups(
							mrf, referenceSchema, entityGroupFetcher,
							outputReferences, 0, keptCount - filteredOutReferences
						);
						final List<ReferenceContract> namedReferences = Arrays.asList(
							Arrays.copyOf(outputReferences, keptCount - filteredOutReferences)
						);
						final DataChunk<ReferenceContract> chunk = mrf.createChunk(
							entity, referenceName, namedReferences
						);
						// a named set slices its own chunk, and whatever falls outside it was read all the same -
						// nothing else ever sees those references again, so they are noted here or nowhere
						if (chunk.getData().size() < namedReferences.size()) {
							final Set<ReferenceKey> kept = CollectionUtils.createHashSet(chunk.getData().size());
							for (ReferenceContract keptReference : chunk) {
								kept.add(keptReference.getReferenceKey());
							}
							for (ReferenceContract namedReference : namedReferences) {
								if (!kept.contains(namedReference.getReferenceKey())) {
									noteChunkedOutReference(namedReference);
								}
							}
						}
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
	 * A reference name that **only named** reference content asked for has an EMPTY unnamed view on the entity: the
	 * query asked for those named chunks and for nothing else, so there is nothing for the unnamed view to carry.
	 *
	 * Every externally issued query produces named reference content, because a GraphQL field alias or a REST
	 * projection name becomes the instance name. Building the unnamed view all the same composed the entity twice
	 * over the same references: once as the named chunks the response reads, and once as a complete and
	 * *unfiltered* unnamed set. On a production catalog where one entity holds 72,342 back-references, that second
	 * copy is 72,342 decorators and a map sized for them, per entity, per request, for data nothing goes on to
	 * read.
	 *
	 * The contract this expresses: a query gets what it asked for, and nothing it did not. A caller that wants the
	 * unnamed view of a name declares its own unnamed `referenceContent()` for it - that makes
	 * {@link ReferenceContractSerializablePredicate#isReferenceRequestedOnlyAsNamed(String)} answer FALSE and the
	 * view is built from the entity's own references exactly as before. A catch-all `referenceContent()` does the
	 * same for every name at once.
	 *
	 * Nothing here has to guard against the named chunks having gone missing. Enrichment re-fetches every named
	 * requirement the entity carries, so a name reported as requested only as named still has its chunks - see
	 * {@link #getNamedReferenceRequirements()}. Were that not so, a name would be reachable through neither view.
	 */
	@Override
	protected boolean isUnnamedReferenceViewEmpty(
		@Nonnull String referenceName,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate
	) {
		return referencePredicate.isReferenceRequestedOnlyAsNamed(referenceName);
	}

	/**
	 * Returns the filtered, sorted and deeply fetched references identified by special reference content instance name,
	 * if the named request exists.
	 *
	 * @param instanceName name of the reference content instance
	 * @return collection of references
	 */
	/**
	 * Returns the reference sets this decorator carries, keyed by reference content instance name.
	 *
	 * Narrowing reads this to keep the sets a limiting request still asks for; nothing else needs the whole map -
	 * a reader after one set asks {@link #getReferencesForReferenceContentInstance(ReferenceContentKey)}.
	 *
	 * @return the sets, or NULL when this decorator carries none
	 */
	@Nullable
	public Map<ReferenceContentKey, DataChunk<ReferenceContract>> getNamedReferenceSets() {
		return this.namedReferenceSets;
	}

	/**
	 * Returns the named requirements the reference sets on this decorator were built from.
	 *
	 * An enrichment reads this to fetch them again alongside whatever it asks for anew - enrichment adds and never
	 * subtracts, so a set an earlier request asked for has to survive one that does not mention it. Rebuilding from
	 * the requirement rather than carrying the old chunk over is what keeps the answer consistent with the body the
	 * enrichment actually read.
	 *
	 * @return the requirements keyed by reference content instance name, empty when this decorator carries none
	 */
	@Nonnull
	public Map<ReferenceContentKey, RequirementContext> getNamedReferenceRequirements() {
		return this.namedReferenceRequirements == null ?
			Collections.emptyMap() : this.namedReferenceRequirements;
	}

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
	 * Completes this decorator's own I/O statistics with everything else the request had to read to produce the data
	 * this decorator exposes, memoizing the result.
	 *
	 * The statistic is split in two halves that are resolved by different rules, because they are owed by different
	 * decorators:
	 *
	 * - the **own** half is every read that produced this entity's own storage parts - body, attributes, associated
	 *   data, prices, references. The fetch pipeline wraps an entity several times over (limit, enrich, decorate) and
	 *   each step contributes the reads it performed itself, so this half is simply summed along the
	 *   {@link #deferredIoStatisticsSource} chain by {@link #ownIoFetchCount()};
	 * - the **attached** half is every read that produced a body this entity reaches - a parent in the chain
	 *   `hierarchyContent` asked for, a referenced or group entity a `referenceContent` asked for, in the ordinary
	 *   reference set or in a named one, and recursively whatever those bodies reach in turn. These are counted by
	 *   walking what this decorator actually carries, in {@link #reachableBodies()}.
	 *
	 * Keeping them apart is what makes the arithmetic hold across enrichment. An enrichment reuses the bodies its
	 * input already resolved and re-attaches them to the decorator it produces, so the same bodies are reachable
	 * from both ends of the chain - and a scheme that lets the source contribute its attached bodies too counts them
	 * twice. Ownership is therefore never inferred from the source, neither by comparing instances (a re-wrapped
	 * body is a different instance carrying the same reads) nor by a flag on the decorator that attached them
	 * (the decorator above it is handed them just the same): whoever exposes a body counts it, and the chain
	 * contributes nothing but its own reads.
	 *
	 * The memo is **computed and assigned**, never accumulated into the decorator's own numbers. Both getters are
	 * public and reachable from response serialization, traffic recording and the metric events at once, and an
	 * accumulation doubles the entity's statistics permanently whenever two of them interleave. Assignment makes
	 * a duplicated resolution recompute the identical value instead, which is the benign race this used to have -
	 * the memos need no publication guarantee because a thread that misses one simply computes it again.
	 */
	private void resolveDeferredIoStatistics() {
		if (this.resolvedIoFetchCount == NOT_RESOLVED || this.resolvedIoFetchedBytes == NOT_RESOLVED) {
			final BodyKey ownKey = new BodyKey(getType(), getPrimaryKeyOrThrowException());
			BodyCost own = BodyCost.of(this);
			int attachedFetchCount = 0;
			int attachedFetchedBytes = 0;
			for (Entry<BodyKey, BodyCost> attachedBody : reachableBodies().entrySet()) {
				if (ownKey.equals(attachedBody.getKey())) {
					// this entity reached itself - a reference pointing back at its owner, or a nesting that
					// closes the loop a level further down. The two views are views of one entity, so what they
					// read is one set: adding the reached view on top of the own half would bill this entity's
					// body once for being the owner and once again for being its own referenced entity
					own = BodyCost.combine(own, attachedBody.getValue());
				} else {
					attachedFetchCount += attachedBody.getValue().ioFetchCount();
					attachedFetchedBytes += attachedBody.getValue().ioFetchedBytes();
				}
			}
			this.resolvedIoFetchCount = own.ioFetchCount() + attachedFetchCount;
			this.resolvedIoFetchedBytes = own.ioFetchedBytes() + attachedFetchedBytes;
		}
	}

	/**
	 * Returns the reads that produced this entity's own storage parts, summed along the
	 * {@link #deferredIoStatisticsSource} chain and excluding every attached body.
	 *
	 * @return this entity's own fetch count
	 */
	private int ownIoFetchCount() {
		if (this.resolvedOwnIoFetchCount == NOT_RESOLVED) {
			final ReadRecord[] records = ownReadRecords();
			if (records == null) {
				final ServerEntityDecorator source = this.deferredIoStatisticsSource;
				this.resolvedOwnIoFetchCount = this.ioFetchCount + (source == null ? 0 : source.ownIoFetchCount());
			} else {
				this.resolvedOwnIoFetchCount = records.length;
			}
		}
		return this.resolvedOwnIoFetchCount;
	}

	/**
	 * Returns the bytes that producing this entity's own storage parts cost, summed along the
	 * {@link #deferredIoStatisticsSource} chain and excluding every attached body.
	 *
	 * @return this entity's own fetched bytes
	 */
	private int ownIoFetchedBytes() {
		if (this.resolvedOwnIoFetchedBytes == NOT_RESOLVED) {
			final ReadRecord[] records = ownReadRecords();
			if (records == null) {
				final ServerEntityDecorator source = this.deferredIoStatisticsSource;
				this.resolvedOwnIoFetchedBytes = this.ioFetchedBytes + (source == null ? 0 : source.ownIoFetchedBytes());
			} else {
				this.resolvedOwnIoFetchedBytes = sizeOf(records);
			}
		}
		return this.resolvedOwnIoFetchedBytes;
	}

	/**
	 * Returns the records this entity's own storage parts were read from, de-duplicated along the whole
	 * {@link #deferredIoStatisticsSource} chain, or NULL when the chain does not identify them.
	 *
	 * De-duplication here is what makes the statistic say what it claims to. The chain is a record of *reads*, and
	 * a read is not the same thing as a record: an enrichment re-reads a part its input already held whenever the
	 * request widens something else, and counting that read would make the same entity at the same richness cost
	 * more by one route than by another. Counting records instead of reads settles it once, for every route.
	 *
	 * A link that read nothing carries no identities and does not need to - it has nothing to identify. A link that
	 * read something and carries none does break the chain, and the whole of it falls back to adding up the raw
	 * counts, which is what this reported before any of them were carried.
	 *
	 * @return the distinct records read along the chain, or NULL when the chain does not identify them
	 */
	@Nullable
	private ReadRecord[] ownReadRecords() {
		ReadRecord[] resolved = this.resolvedOwnReadRecords;
		if (resolved == null) {
			LinkedHashSet<ReadRecord> collected = null;
			boolean identified = true;
			for (ServerEntityDecorator link = this; link != null; link = link.deferredIoStatisticsSource) {
				if (link.ownReadRecords == null) {
					if (link.ioFetchCount != 0 || link.ioFetchedBytes != 0) {
						// this link read something and kept no record of what - nothing downstream can be trusted
						// to be a union rather than a sum
						identified = false;
						break;
					}
				} else {
					if (collected == null) {
						collected = new LinkedHashSet<>(16);
					}
					Collections.addAll(collected, link.ownReadRecords);
				}
			}
			resolved = !identified ?
				UNIDENTIFIED_READ_RECORDS :
				(collected == null ? ReadRecord.NONE : collected.toArray(ReadRecord[]::new));
			this.resolvedOwnReadRecords = resolved;
		}
		return resolved == UNIDENTIFIED_READ_RECORDS ? null : resolved;
	}

	/**
	 * Adds up what the passed records occupied.
	 *
	 * @param records records to measure
	 * @return the number of Bytes they occupied in total
	 */
	private static int sizeOf(@Nonnull ReadRecord[] records) {
		int sizeInBytes = 0;
		for (ReadRecord record : records) {
			sizeInBytes += record.sizeInBytes();
		}
		return sizeInBytes;
	}

	/**
	 * Collects every entity this decorator reaches through the bodies it exposes, each exactly once, mapped to what
	 * that entity's own storage parts cost.
	 *
	 * A body hangs off this entity only because the request asked for it - `hierarchyContent` for the parent chain,
	 * `entityFetch` / `entityGroupFetch` inside a `referenceContent` for a referenced or group body - so its reads
	 * belong to the statistics of the entity that carried the requirement. Named reference sets are walked
	 * alongside the ordinary one: a named `referenceContent` fetches bodies of its own, into a set the ordinary
	 * traversal never reaches.
	 *
	 * The walk is **transitive**, and each body contributes its own parts rather than its aggregate. A body reaches
	 * bodies of its own - a referenced entity's parent chain, an `entityFetch` nested inside another one - and two
	 * of this entity's bodies routinely reach the same third entity: two stores in one category, two categories
	 * under one parent. Adding up the bodies' aggregates would then bill that third entity once per path leading to
	 * it, and de-duplicating only among the immediate bodies cannot see it, because it is not one of them. Taking
	 * every reachable entity into one map keyed by the entity it is settles every depth at once.
	 *
	 * De-duplication is therefore by the **entity** - its type and primary key - rather than by the object carrying
	 * it, and it is load-bearing rather than defensive. Every reference sharing a group points at one and the same
	 * group body; a named set and the ordinary set reach the same referenced entity through separate prefetch
	 * indexes; and an enrichment re-wraps a body it reused rather than reading it again. The same entity therefore
	 * arrives here as several distinct objects, which object identity cannot collapse. Where two views of one
	 * entity account for different amounts, because they were fetched under different requirements, their records
	 * are **united**: this entity needed everything either view had to read, and neither summing (which bills the
	 * body both views share twice) nor taking the larger (which drops what the smaller view alone read) says that.
	 * Only where a view carries no record identities at all does the larger of the two stand in - see
	 * {@link BodyCost#combine(BodyCost, BodyCost)}.
	 *
	 * The map is memoized per decorator, which is what keeps the transitive walk affordable: a body shared by every
	 * entity of a page resolves its own reachable set once, and every owner merges the finished map.
	 *
	 * Memoizing every node's complete closure does cost more than one shared traversal would: a parent chain of N
	 * links retains `N + (N-1) + ... + 1` entries rather than N, because every link holds everything above it. That
	 * is accepted rather than overlooked. The quadratic term is entries of two small records in a map, set against
	 * a fetch that already performed N storage reads to produce that chain, and it stays invisible far past any
	 * hierarchy anyone builds: resolving the leaf of a chain measures 10 ms at 50 links, 19 ms at 100 and 29 ms at
	 * 400 - linear in N across the whole range, because the reads dominate. Collapsing the memo into a single
	 * visited-set walk would give up the per-body memo above, and with it the property that one body shared by
	 * a whole page is walked once rather than once per owner - which is the case that actually occurs, where a deep
	 * chain is not.
	 *
	 * @return the reachable entities and what their own parts cost, empty when this entity reaches none
	 */
	@Nonnull
	private Map<BodyKey, BodyCost> reachableBodies() {
		Map<BodyKey, BodyCost> resolved = this.resolvedReachableBodies;
		if (resolved == null) {
			final ServerEntityDecorator source = this.deferredIoStatisticsSource;
			if (!this.attachesBodies && source != null) {
				// this decorator put nothing in place; it re-applied predicates over bodies that were already
				// there, so what it reaches is what the decorator it wraps reaches - and deriving that again here
				// would make every getter walk, and materialize, a reference set somebody else has already walked
				resolved = source.reachableBodies();
				this.resolvedReachableBodies = resolved;
				return resolved;
			}
			// a decorator that built its own references knows whether any of them carries a body; when none does,
			// there is nothing for the reference walk below to find and every reference it would materialize to
			// discover that is wasted (see EntityDecorator#areReferenceBodiesAttached)
			final boolean referencesWorthWalking = !this.attachesBodies || areReferenceBodiesAttached();
			// a body the chunking dropped is exposed by nobody, so the walk below cannot find it - the decorator
			// that dropped it noted what it cost, and every decorator wrapping or narrowing that one inherits the
			// note through the chain exactly as it inherits the own half
			Map<BodyKey, BodyCost> bodies = collectChunkedOutBodies();
			if (parentAvailable()) {
				// a bodyless pointer costs nothing - nothing was read to produce it
				bodies = collectBody(bodies, getParentEntity().orElse(null));
			}
			if (referencesWorthWalking) {
				if (referencesAvailable()) {
					for (ReferenceContract reference : getReferences()) {
						bodies = collectReferenceBodies(bodies, reference);
					}
				}
				if (this.namedReferenceSets != null) {
					for (DataChunk<ReferenceContract> namedReferenceSet : this.namedReferenceSets.values()) {
						for (ReferenceContract reference : namedReferenceSet) {
							bodies = collectReferenceBodies(bodies, reference);
						}
					}
				}
			}
			// unmodifiable because the fast path above shares this very map by reference with every decorator
			// wrapping this one
			resolved = bodies == null ? Collections.emptyMap() : Collections.unmodifiableMap(bodies);
			this.resolvedReachableBodies = resolved;
		}
		return resolved;
	}

	/**
	 * Collects what the bodies dropped by chunking cost, along the whole {@link #deferredIoStatisticsSource} chain.
	 *
	 * The chain is walked for the same reason {@link #ownIoFetchCount()} walks it: the decorator that performed the
	 * work is rarely the one anybody asks, because the fetch pipeline wraps and narrows it several times over.
	 *
	 * @return a fresh map of the dropped entities and what their own parts cost, NULL when the chain dropped none
	 */
	@Nullable
	private Map<BodyKey, BodyCost> collectChunkedOutBodies() {
		Map<BodyKey, BodyCost> collected = null;
		for (ServerEntityDecorator link = this; link != null; link = link.deferredIoStatisticsSource) {
			if (link.chunkedOutBodies != null) {
				if (collected == null) {
					collected = CollectionUtils.createHashMap(link.chunkedOutBodies.size());
				}
				for (Entry<BodyKey, BodyCost> dropped : link.chunkedOutBodies.entrySet()) {
					collected.merge(dropped.getKey(), dropped.getValue(), BodyCost::combine);
				}
			}
		}
		return collected;
	}

	/**
	 * Collects both bodies a single reference may carry - the referenced entity and its group - into `bodies`.
	 *
	 * @param bodies    set collected so far, NULL until the first body is found
	 * @param reference reference to take the bodies from
	 * @return the set to carry on with
	 */
	@Nullable
	private static Map<BodyKey, BodyCost> collectReferenceBodies(
		@Nullable Map<BodyKey, BodyCost> bodies,
		@Nonnull ReferenceContract reference
	) {
		return collectBody(
			collectBody(bodies, reference.getReferencedEntity().orElse(null)),
			reference.getGroupEntity().orElse(null)
		);
	}

	/**
	 * Adds `candidate` and everything it reaches in turn to `bodies` when it is a body carrying I/O statistics of
	 * its own, creating the map on first use so an entity with nothing attached allocates nothing.
	 *
	 * The body contributes its **own** parts, not its aggregate, and its reachable set is merged in beside it. The
	 * two are separated for a reason: an aggregate already has that body's own bodies folded into it, and folding
	 * aggregates one level at a time is what bills an entity two of these bodies share once for each of them.
	 *
	 * @param bodies    map collected so far, NULL until the first body is found
	 * @param candidate what is attached at the examined slot, NULL or a bodyless pointer when nothing was read
	 * @return the map to carry on with
	 */
	@Nullable
	private static Map<BodyKey, BodyCost> collectBody(
		@Nullable Map<BodyKey, BodyCost> bodies,
		@Nullable Object candidate
	) {
		if (candidate instanceof ServerEntityDecorator body) {
			final Map<BodyKey, BodyCost> result = bodies == null ?
				CollectionUtils.createHashMap(8) : bodies;
			result.merge(
				new BodyKey(body.getType(), body.getPrimaryKeyOrThrowException()),
				BodyCost.of(body),
				BodyCost::combine
			);
			for (Entry<BodyKey, BodyCost> reachable : body.reachableBodies().entrySet()) {
				result.merge(reachable.getKey(), reachable.getValue(), BodyCost::combine);
			}
			return result;
		} else {
			return bodies;
		}
	}

	/**
	 * Identity of an attached body, as the entity it is rather than as the object carrying it.
	 *
	 * @param entityType  type of the attached entity
	 * @param primaryKey  primary key of the attached entity
	 */
	private record BodyKey(@Nonnull String entityType, int primaryKey) {
	}

	/**
	 * What obtaining one attached entity cost, taken from a body that carries it.
	 *
	 * @param ioFetchCount   number of records read to produce that entity
	 * @param ioFetchedBytes number of Bytes those records occupied
	 * @param records        identities of those records, or NULL when the body does not carry them
	 */
	private record BodyCost(int ioFetchCount, int ioFetchedBytes, @Nullable ReadRecord[] records) {

		/**
		 * Takes the cost of one view of one entity off the body carrying it.
		 *
		 * @param body the body to measure
		 * @return what obtaining that entity cost, through this view of it
		 */
		@Nonnull
		static BodyCost of(@Nonnull ServerEntityDecorator body) {
			final ReadRecord[] records = body.ownReadRecords();
			return records == null ?
				new BodyCost(body.ownIoFetchCount(), body.ownIoFetchedBytes(), null) :
				new BodyCost(records.length, sizeOf(records), records);
		}

		/**
		 * Combines two views of one and the same entity into what that entity cost the owner exposing both.
		 *
		 * The answer is the **union** of what the two views read, and neither of the two obvious alternatives is
		 * it. Adding them up bills a record both views needed once per view - and both views normally start by
		 * reading the same body. Keeping the larger loses whatever the smaller view read and the larger one did
		 * not: ordinary and named reference requirements are held in independent maps, so one request really can
		 * ask for a referenced entity's attributes through one and its prices through the other, and then neither
		 * view is contained in the other.
		 *
		 * The union is computable only because each view says *which* records it read. Where a view does not -
		 * a decorator restored from the cache, one rebuilt on the driver side - the larger of the two is kept, on
		 * the reasoning that this entity needed everything the richer view had to read. That is the older answer,
		 * now confined to the cases that cannot do better.
		 *
		 * @param left  one view of the entity
		 * @param right the other view of the same entity
		 * @return the combined cost
		 */
		@Nonnull
		static BodyCost combine(@Nonnull BodyCost left, @Nonnull BodyCost right) {
			final ReadRecord[] leftRecords = left.records();
			final ReadRecord[] rightRecords = right.records();
			if (leftRecords == null || rightRecords == null) {
				// the two statistics are decided separately on purpose: views fetched under different
				// requirements need not order the same way on both, and a view reading fewer records can easily
				// have read more Bytes - a single associated data record against several attribute ones, say
				return new BodyCost(
					Math.max(left.ioFetchCount(), right.ioFetchCount()),
					Math.max(left.ioFetchedBytes(), right.ioFetchedBytes()),
					null
				);
			}
			if (leftRecords == rightRecords) {
				// the same view reached twice - by far the common case, and it needs no set at all
				return left;
			}
			final LinkedHashSet<ReadRecord> union = new LinkedHashSet<>(
				leftRecords.length + rightRecords.length
			);
			Collections.addAll(union, leftRecords);
			Collections.addAll(union, rightRecords);
			if (union.size() == leftRecords.length) {
				return left;
			}
			if (union.size() == rightRecords.length) {
				return right;
			}
			final ReadRecord[] merged = union.toArray(ReadRecord[]::new);
			return new BodyCost(merged.length, sizeOf(merged), merged);
		}
	}

}
