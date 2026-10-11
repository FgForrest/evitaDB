/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.virtual;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.virtual.VirtualPartitionInvariants.RegisteredRow;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_CODE;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_ORDER;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_RANK;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.CATALOG;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.newProduct;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertInvariants;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertStoredParts;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertUnchanged;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.catalogOf;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.collectionOf;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.describeIndexes;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.registeredRowsOf;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves that a catalog writing its single-owner reference partitions as registry rows survives every event of its
 * lifecycle with every row, the primary key of every row and the membership of every owner intact, and that nothing on
 * the way silently brings an index object of a single-owner partition back or loses a row. The catalog is compared
 * with itself: what it was before an event is what it must be after it.
 *
 * At every checkpoint - after every committed step of a history and after every event - the catalog must keep the
 * invariants a reload relies on (see {@link VirtualPartitionInvariants#assertInvariants}), and its partitions must
 * agree with the entity bodies (see {@link VirtualReferencePartitionRawRowOracle}), their primary keys with those an
 * identity ledger of the test observed at the checkpoints before (see {@link PartitionIdentityLedger}). After every
 * reload the parts the catalog stores must be those its indexes and a history-free rebuild of its bodies account for
 * (see {@link VirtualPartitionInvariants#assertStoredParts}).
 *
 * The index-level description of every collection (see {@link CatalogDescriber#describeIndexesOf}) - the registry
 * rows with their primary keys, owners, sort values and chain predecessors, the advertised partitions, the index
 * objects and the membership - must be exactly what it was before an event, and so must the query-level description
 * (see {@link CatalogDescriber#describeQueriesOf}) where the event keeps the names of the collections. A partition an
 * owner enters after the event must get a primary key greater than every primary key the collection used before it,
 * which a reload must keep without writing an index for it (see {@link #assertMintsAboveEveryUsedPrimaryKey}).
 *
 * One exception is legitimate, and only one: a load that replays the write-ahead log - a crash recovery, and a copy
 * of a catalog loaded from its last checkpoint and the log after it - mints the primary keys of the indexes the log
 * created after the last checkpoint anew. Those primary keys were never published, and the replay may hand them out
 * in another order: a live transaction mints a partition it creates once in its session and once more when the trunk
 * executes it, the replay only once. Such an event is compared modulo exactly those primary keys (see
 * {@link #assertUnchangedModuloReminted}); every index the last checkpoint published keeps its primary key, and every
 * other line is compared as it is.
 *
 * Loading a catalog and taking it live run `initSchema` over every collection, and a schema-driven path writing the
 * registry there would pass `VirtualReferencePartitionWriterGuardTest`, which restricts the direct callers of the
 * writer only. The behavioural half of that guarantee is here: a clean reload followed by a close leaves every file of
 * the catalog byte for byte as it was, and going live leaves the description of the registry as it was.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-owner partitions written as registry rows through the lifecycle of their catalog")
@Tag(INDEXING)
@Tag(STORAGE)
@Tag(REFERENCE)
class VirtualPartitionReloadInvariantTest implements EvitaTestSupport {
	/**
	 * The collection the product collection is renamed to, or replaced by.
	 */
	private static final String ENTITY_GOODS = "goods";
	/**
	 * Associated data a test writes where it needs a write that changes no index.
	 */
	private static final String ASSOCIATED_DATA_NOTE = "note";
	/**
	 * A checkpoint interval that never elapses within a test, so the committed transactions stay in the write-ahead
	 * log.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * Generous bound on a backup, a restore or a load of a catalog holding a handful of entities.
	 */
	private static final int OPERATION_TIMEOUT_SECONDS = 120;
	/**
	 * The product the history archives.
	 */
	private static final int ARCHIVED_PRODUCT = 5;
	/**
	 * The product the history creates, entering two partitions of its own.
	 */
	private static final int NEW_PRODUCT = 9;
	/**
	 * The second category the new product of the history enters alone.
	 */
	private static final int NEW_PRODUCT_SECOND_CATEGORY = 12;
	/**
	 * The entity a test creates after the event to mint the primary key of a new partition.
	 */
	private static final int MINTING_ENTITY = 31;
	/**
	 * The category no entity of any history references - the partition the minting entity enters.
	 */
	private static final int MINTED_CATEGORY = 17;
	/**
	 * The categories the goods of {@link #setUpWithGoods(EvitaSessionContract)} reference alone, one each.
	 */
	private static final int[] GOODS_CATEGORIES = {13, 14, 15, 16};
	/**
	 * The category the first two goods share.
	 */
	private static final int SHARED_GOODS_CATEGORY = 20;
	/**
	 * Matches the partition lists of one filter index line of a referenced-type entity index - see
	 * {@link #translateIndexPrimaryKeys(SortedMap, Map)}.
	 */
	private static final Pattern FILTER_BUCKET_RECORDS = Pattern.compile("=(\\[[0-9, ]*]); ");

	/**
	 * The directories of the running engine - those of the crash image once a test recovered from one.
	 */
	@Nullable private TestPaths paths;
	/**
	 * The transaction settings of the engine, `null` for the defaults of the test configuration.
	 */
	@Nullable private TransactionOptions transactionOptions;
	/**
	 * TRUE when the engine is configured with compaction thresholds low enough for a few rewrites to cross them.
	 */
	private boolean compacting;
	/**
	 * The configuration of the running engine.
	 */
	@Nullable private EvitaConfiguration configuration;
	/**
	 * The running engine.
	 */
	@Nullable private Evita evita;
	/**
	 * Every set of directories the test allocated, removed after it.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	/**
	 * The identity of the partitions each catalog showed at its checkpoints so far, by catalog.
	 */
	private final Map<String, PartitionIdentityLedger> ledgers = CollectionUtils.createHashMap(4);

	/**
	 * Runs the history every test starts from on top of its fixture, each change in a session of its own: an owner
	 * archived (rows in both scopes), a single-owner partition gaining a second owner (an index object amid the rows),
	 * a new owner entering two partitions alone (rows at the top of the primary key sequence), and an owner leaving
	 * its only partition (a primary key the sequence handed out and nothing uses any longer).
	 *
	 * @param session runs one change in a session of its own
	 */
	private static void runHistory(@Nonnull Consumer<Consumer<EvitaSessionContract>> session) {
		session.accept(it -> it.archiveEntity(ENTITY_PRODUCT, ARCHIVED_PRODUCT));
		session.accept(
			updateProduct(
				1, it -> it.setReference(REFERENCE_CATEGORIES, 2, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 21))
			)
		);
		session.accept(
			it -> newProduct(it, NEW_PRODUCT)
				.setReference(
					REFERENCE_CATEGORIES, NEW_PRODUCT_SECOND_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, NEW_PRODUCT_SECOND_CATEGORY)
				)
				.upsertVia(it)
		);
		session.accept(updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4)));
	}

	/**
	 * Defines the fixture of single-owner partitions without the reflected reference and the goods collection: an
	 * entity type with a reference to categories indexed for filtering, of the same shape as the products', whose goods
	 * reference a category of their own each, and the first two of them one more category they share.
	 *
	 * @param session the session to write in
	 */
	private static void setUpWithGoods(@Nonnull EvitaSessionContract session) {
		SingleOwnerPartitionFixture.setUp(session);
		session.defineEntitySchema(ENTITY_GOODS)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
			.withAttribute(ATTRIBUTE_RANK, Integer.class, whichIs -> whichIs.filterable().sortable().nullable())
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.faceted()
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					)
			)
			.updateVia(session);
		for (int i = 0; i < GOODS_CATEGORIES.length; i++) {
			final int goods = i + 1;
			final int category = GOODS_CATEGORIES[i];
			final EntityBuilder builder = session.createNewEntity(ENTITY_GOODS, goods)
				.setAttribute(ATTRIBUTE_CODE, "G" + goods)
				.setAttribute(ATTRIBUTE_RANK, goods)
				.setReference(REFERENCE_CATEGORIES, category, ref -> ref.setAttribute(ATTRIBUTE_ORDER, category));
			if (goods <= 2) {
				builder.setReference(
					REFERENCE_CATEGORIES, SHARED_GOODS_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, SHARED_GOODS_CATEGORY + goods)
				);
			}
			builder.upsertVia(session);
		}
	}

	/**
	 * Creates the step in which a new entity of the type enters {@link #MINTED_CATEGORY} alone.
	 *
	 * @param entityType the entity type, whose schema has the category reference of the products
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> mintingStep(@Nonnull String entityType) {
		return session -> session.createNewEntity(entityType, MINTING_ENTITY)
			.setAttribute(ATTRIBUTE_CODE, "M" + MINTING_ENTITY)
			.setReference(REFERENCE_CATEGORIES, MINTED_CATEGORY, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 1))
			.upsertVia(session);
	}

	/**
	 * Returns every index primary key a collection uses - the stored indexes its header lists, its global index and
	 * every registered partition.
	 *
	 * @param collection the collection
	 * @return the primary keys, ascending
	 */
	@Nonnull
	private static SortedSet<Integer> usedPrimaryKeysOf(@Nonnull EntityCollection collection) {
		final EntityCollectionHeader header = collection.getEntityCollectionHeader();
		final SortedSet<Integer> used = new TreeSet<>(header.usedEntityIndexPrimaryKeys());
		if (header.globalEntityIndexPrimaryKey() != null) {
			used.add(header.globalEntityIndexPrimaryKey());
		}
		used.addAll(registeredRowsOf(collection).keySet());
		return used;
	}

	/**
	 * Renames the collection prefix of the index-level lines of one collection, so the lines of a collection known
	 * under another name can be compared with them.
	 *
	 * @param lines    the lines
	 * @param fromType the entity type the lines describe
	 * @param toType   the entity type to describe them as
	 * @return the renamed lines
	 */
	@Nonnull
	private static SortedMap<String, String> renamed(
		@Nonnull SortedMap<String, String> lines,
		@Nonnull String fromType,
		@Nonnull String toType
	) {
		final SortedMap<String, String> result = new TreeMap<>();
		final String fromPrefix = "index/" + fromType + "/";
		final String toPrefix = "index/" + toType + "/";
		for (final Entry<String, String> line : lines.entrySet()) {
			result.put(
				line.getKey().startsWith(fromPrefix) ?
					toPrefix + line.getKey().substring(fromPrefix.length()) : line.getKey(),
				line.getValue()
			);
		}
		return result;
	}

	/**
	 * Returns the primary key of the registered partition of a key in a committed catalog.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @param entityType  the entity type owning the reference
	 * @param scope       the scope
	 * @param key         the key of the partition
	 * @return the partition primary key, or `-1` when the registry holds no row of the key
	 */
	private static int registeredPartitionOf(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityIndex typeIndex = collectionOf(evita, catalogName, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, key.referenceName())
		);
		final VirtualReferencePartition partition =
			typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex ?
			ReferencePartitions.findRegisteredForWrite(referencedTypeIndex, key) : null;
		return partition == null ? -1 : partition.getPrimaryKey();
	}

	/**
	 * Creates the key of a category partition.
	 *
	 * @param category the category
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey categoryKey(int category) {
		return SingleOwnerPartitionFixture.category(category);
	}

	/**
	 * Identifies every index of every collection of a committed catalog by what it indexes rather than by its primary
	 * key: the global index and the referenced-type entity indexes by their scope and reference, and every partition a
	 * referenced-type entity index advertises - an index object or a registry row alike - by its key, the way
	 * {@link PartitionIdentityLedger#identityKeyOf} identifies it. A partition keeps its identity when it turns from a
	 * row into an object or back.
	 *
	 * Partitions of referenced groups are not identified: none of the fixtures of this test has one, and a referenced
	 * group type index advertising a partition fails the description.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the indexes by their identity
	 */
	@Nonnull
	private static SortedMap<String, IndexIdentity> describeIndexIdentities(
		@Nonnull Evita evita,
		@Nonnull String catalogName
	) {
		final SortedMap<String, IndexIdentity> identities = new TreeMap<>();
		for (final String entityType : catalogOf(evita, catalogName).getEntityTypes()) {
			final EntityCollection collection = collectionOf(evita, catalogName, entityType);
			for (final Scope scope : Scope.values()) {
				final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.GLOBAL, scope)
				);
				if (globalIndex != null) {
					putIdentity(
						identities, entityType + "/" + EntityIndexType.GLOBAL + "/" + scope,
						new IndexIdentity(entityType, globalIndex.getPrimaryKey(), null, null)
					);
				}
				for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
					for (final EntityIndexType family : new EntityIndexType[]{
						EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
					}) {
						final String referenceName = referenceSchema.getName();
						final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
							new EntityIndexKey(family, scope, referenceName)
						);
						if (!(typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex)) {
							continue;
						}
						final String path = entityType + "/" + family + "/" + scope + "/" + referenceName;
						putIdentity(
							identities, path, new IndexIdentity(entityType, typeIndex.getPrimaryKey(), null, null)
						);
						if (family == EntityIndexType.REFERENCED_ENTITY_TYPE) {
							putPartitionIdentities(identities, collection, scope, referencedTypeIndex);
						} else {
							assertTrue(
								typeIndex.getAllPrimaryKeys().isEmpty(),
								path + ": partitions of referenced groups are not identified by this test"
							);
						}
					}
				}
			}
		}
		return identities;
	}

	/**
	 * Adds the identity of every partition a referenced-type entity index of referenced entities advertises - see
	 * {@link #describeIndexIdentities(Evita, String)}.
	 *
	 * @param identities the identities to add to
	 * @param collection the collection the partitions belong to
	 * @param scope      the scope of the type index
	 * @param typeIndex  the referenced-type entity index advertising the partitions
	 */
	private static void putPartitionIdentities(
		@Nonnull SortedMap<String, IndexIdentity> identities,
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		final String entityType = collection.getEntityType();
		final String referenceName = typeIndex.getReferenceName();
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		for (final Integer target : new TreeSet<>(typeIndex.getAllTrackedReferencedEntityPrimaryKeys())) {
			for (final int partitionPrimaryKey : typeIndex.getAllReferenceIndexes(target)) {
				final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
				final RepresentativeReferenceKey key;
				if (partition != null) {
					key = (RepresentativeReferenceKey) partition.getIndexKey().discriminator();
				} else {
					assertTrue(
						registry.getPartitionOwner(partitionPrimaryKey).isPresent(),
						entityType + "/" + scope + "/" + referenceName + "/" + target + ": partition " +
							partitionPrimaryKey + " is advertised with neither an index object nor a row"
					);
					final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
					key = new RepresentativeReferenceKey(
						new ReferenceKey(referenceName, target),
						tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
							new Serializable[0] : registry.getTuple(tupleId)
					);
				}
				putIdentity(
					identities,
					entityType + "/" + EntityIndexType.REFERENCED_ENTITY + "/" +
						PartitionIdentityLedger.identityKeyOf(scope, key),
					new IndexIdentity(entityType, partitionPrimaryKey, scope, key)
				);
			}
		}
	}

	/**
	 * Adds one identity, refusing a second index of the same identity.
	 *
	 * @param identities the identities to add to
	 * @param path       the identity
	 * @param identity   the index
	 */
	private static void putIdentity(
		@Nonnull SortedMap<String, IndexIdentity> identities,
		@Nonnull String path,
		@Nonnull IndexIdentity identity
	) {
		final IndexIdentity previous = identities.put(path, identity);
		assertNull(
			previous,
			"two indexes of one identity `" + path + "`: primary keys " +
				(previous == null ? -1 : previous.primaryKey()) + " and " + identity.primaryKey()
		);
	}

	/**
	 * Returns the identities of the indexes a later state holds that an earlier one did not - see
	 * {@link #describeIndexIdentities(Evita, String)}.
	 *
	 * @param earlier the identities of the earlier state
	 * @param later   the identities of the later state
	 * @return the identities created in between
	 */
	@Nonnull
	private static SortedSet<String> createdSince(
		@Nonnull SortedMap<String, IndexIdentity> earlier,
		@Nonnull SortedMap<String, IndexIdentity> later
	) {
		final SortedSet<String> created = new TreeSet<>(later.keySet());
		created.removeAll(earlier.keySet());
		return created;
	}

	/**
	 * Asserts that the index-level description of a catalog after a load replaying the write-ahead log equals the one
	 * of the catalog it replays, except for the primary keys the replay minted anew, and returns the identities whose
	 * primary key it minted anew.
	 *
	 * - both catalogs hold indexes of exactly the same identities (see
	 *   {@link #describeIndexIdentities(Evita, String)});
	 * - an index may have another primary key after the load only when the history created it after the last
	 *   checkpoint, so that its primary key was never published; every index the checkpoint published keeps its own;
	 * - the description after the load, its primary keys translated back to those the indexes had before (see
	 *   {@link #translateIndexPrimaryKeys(SortedMap, Map)}), equals the description before, line for line.
	 *
	 * @param label                  names the event in failure messages
	 * @param before                 the index-level description of the catalog the load replays
	 * @param identitiesBefore       the identities of its indexes
	 * @param after                  the index-level description of the loaded catalog
	 * @param identitiesAfter        the identities of its indexes
	 * @param createdAfterCheckpoint the identities of the indexes the history created after the last checkpoint
	 * @return the identities of the indexes whose primary key the load minted anew
	 */
	@Nonnull
	private static SortedSet<String> assertUnchangedModuloReminted(
		@Nonnull String label,
		@Nonnull SortedMap<String, String> before,
		@Nonnull SortedMap<String, IndexIdentity> identitiesBefore,
		@Nonnull SortedMap<String, String> after,
		@Nonnull SortedMap<String, IndexIdentity> identitiesAfter,
		@Nonnull Set<String> createdAfterCheckpoint
	) {
		assertEquals(
			identitiesBefore.keySet(), identitiesAfter.keySet(),
			label + ": the loaded catalog must hold indexes of exactly the identities it held before"
		);
		final Map<String, Map<Integer, Integer>> translation = CollectionUtils.createHashMap(8);
		final SortedSet<String> reminted = new TreeSet<>();
		final List<String> violations = new ArrayList<>(4);
		for (final Entry<String, IndexIdentity> entry : identitiesBefore.entrySet()) {
			final int primaryKeyBefore = entry.getValue().primaryKey();
			final IndexIdentity identityAfter = identitiesAfter.get(entry.getKey());
			if (identityAfter.primaryKey() == primaryKeyBefore) {
				continue;
			}
			if (createdAfterCheckpoint.contains(entry.getKey())) {
				reminted.add(entry.getKey());
				translation
					.computeIfAbsent(identityAfter.entityType(), __ -> CollectionUtils.createHashMap(16))
					.put(identityAfter.primaryKey(), primaryKeyBefore);
			} else {
				violations.add(
					entry.getKey() + ": primary key " + primaryKeyBefore + " published by the last checkpoint, " +
						identityAfter.primaryKey() + " after the load"
				);
			}
		}
		if (!violations.isEmpty()) {
			fail(
				label + ": " + violations.size() + " index(es) the last checkpoint published changed their primary " +
					"key:\n" + String.join("\n", violations)
			);
		}
		assertUnchanged(
			label + ", modulo the primary keys minted anew for " + reminted,
			before, translateIndexPrimaryKeys(after, translation)
		);
		return reminted;
	}

	/**
	 * Translates the index primary keys of an index-level description (see {@link CatalogDescriber#describeIndexesOf})
	 * - and only those, never a primary key of an entity. Per collection, a primary key the translation maps is
	 * replaced by its image, and any other is left as it is, wherever the description names an index:
	 *
	 * - the path segment of a partition (`partition/{pk}`) and of a registry row (`registry/row/{pk}`);
	 * - the value of every `primaryKey` line - of the global index, a referenced-type entity index and a partition;
	 * - the advertised partitions of a referenced-type entity index, its partitions of one referenced entity
	 *   (`target/{pk}`) and the partitions every value of its filter indexes maps to;
	 * - the covered and residual partitions of the membership and the partitions of every covered owner.
	 *
	 * Lists are sorted again after the translation, as the description sorts them.
	 *
	 * @param lines       the description
	 * @param translation the primary key translation per entity type
	 * @return the translated description
	 */
	@Nonnull
	private static SortedMap<String, String> translateIndexPrimaryKeys(
		@Nonnull SortedMap<String, String> lines,
		@Nonnull Map<String, Map<Integer, Integer>> translation
	) {
		final SortedMap<String, String> result = new TreeMap<>();
		for (final Entry<String, String> line : lines.entrySet()) {
			final String path = line.getKey();
			// index/{entity type}/{scope}/{the rest}
			final String[] head = path.split("/", 4);
			assertTrue(head.length == 4 && "index".equals(head[0]), "not an index-level line: " + path);
			final Map<Integer, Integer> primaryKeys = translation.getOrDefault(head[1], Map.of());
			String translatedPath = path;
			String value = line.getValue();
			if (!primaryKeys.isEmpty()) {
				final String rest = head[3];
				if (rest.startsWith("global/")) {
					if ("global/primaryKey".equals(rest)) {
						value = translateNumber(value, primaryKeys);
					}
				} else if (rest.startsWith("membership/")) {
					// membership/{reference}/{leaf}
					final String leaf = rest.split("/", 3)[2];
					if ("covered".equals(leaf) || "residual".equals(leaf) || leaf.startsWith("owner/")) {
						value = translateList(value, primaryKeys);
					}
				} else {
					// {type index family}/{reference}/{leaf}
					final String[] typeIndexPath = rest.split("/", 3);
					assertEquals(3, typeIndexPath.length, "not a line of a referenced-type entity index: " + path);
					final String leaf = typeIndexPath[2];
					if ("primaryKey".equals(leaf)) {
						value = translateNumber(value, primaryKeys);
					} else if ("advertised".equals(leaf) || leaf.startsWith("target/")) {
						value = translateList(value, primaryKeys);
					} else if (leaf.startsWith("filter/")) {
						value = translateFilterBuckets(value, primaryKeys);
					} else if (leaf.startsWith("partition/") || leaf.startsWith("registry/row/")) {
						final String segment = leaf.startsWith("partition/") ? "partition/" : "registry/row/";
						final String afterSegment = leaf.substring(segment.length());
						final int slash = afterSegment.indexOf('/');
						final String primaryKey = slash < 0 ? afterSegment : afterSegment.substring(0, slash);
						final String subPath = slash < 0 ? "" : afterSegment.substring(slash);
						translatedPath = path.substring(0, path.length() - leaf.length()) + segment +
							translateNumber(primaryKey, primaryKeys) + subPath;
						if ("/primaryKey".equals(subPath)) {
							value = translateNumber(value, primaryKeys);
						}
					}
				}
			}
			final String previous = result.put(translatedPath, value);
			assertNull(previous, "the translation maps two lines onto `" + translatedPath + "`");
		}
		return result;
	}

	/**
	 * Translates one primary key - see {@link #translateIndexPrimaryKeys(SortedMap, Map)}.
	 *
	 * @param primaryKey  the primary key as text
	 * @param primaryKeys the translation
	 * @return the translated primary key as text
	 */
	@Nonnull
	private static String translateNumber(@Nonnull String primaryKey, @Nonnull Map<Integer, Integer> primaryKeys) {
		final int value = Integer.parseInt(primaryKey);
		return String.valueOf(primaryKeys.getOrDefault(value, value));
	}

	/**
	 * Translates a list of primary keys rendered as `[a, b]` and sorts it - see
	 * {@link #translateIndexPrimaryKeys(SortedMap, Map)}.
	 *
	 * @param list        the rendered list
	 * @param primaryKeys the translation
	 * @return the translated list, sorted ascending
	 */
	@Nonnull
	private static String translateList(@Nonnull String list, @Nonnull Map<Integer, Integer> primaryKeys) {
		assertTrue(list.startsWith("[") && list.endsWith("]"), "not a list of primary keys: " + list);
		final String elements = list.substring(1, list.length() - 1);
		if (elements.isBlank()) {
			return list;
		}
		final String[] parts = elements.split(", ");
		final int[] translated = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			final int value = Integer.parseInt(parts[i]);
			translated[i] = primaryKeys.getOrDefault(value, value);
		}
		Arrays.sort(translated);
		return Arrays.toString(translated);
	}

	/**
	 * Translates the partition list of every value of one filter index line of a referenced-type entity index - see
	 * {@link #translateIndexPrimaryKeys(SortedMap, Map)}.
	 *
	 * @param buckets     the rendered buckets, `value=[pks]; ` each
	 * @param primaryKeys the translation
	 * @return the translated buckets
	 */
	@Nonnull
	private static String translateFilterBuckets(@Nonnull String buckets, @Nonnull Map<Integer, Integer> primaryKeys) {
		final Matcher matcher = FILTER_BUCKET_RECORDS.matcher(buckets);
		final StringBuilder translated = new StringBuilder(buckets.length() + 16);
		while (matcher.find()) {
			matcher.appendReplacement(
				translated, Matcher.quoteReplacement("=" + translateList(matcher.group(1), primaryKeys) + "; ")
			);
		}
		matcher.appendTail(translated);
		return translated.toString();
	}

	/**
	 * Lists every file under a directory with its length and last modification time in milliseconds.
	 *
	 * @param root the directory
	 * @return the relative path of every file mapped to its length and modification time
	 */
	@Nonnull
	private static SortedMap<String, String> listFiles(@Nonnull Path root) {
		final SortedMap<String, String> listing = new TreeMap<>();
		try (final Stream<Path> tree = Files.walk(root)) {
			for (final Path file : tree.filter(Files::isRegularFile).toList()) {
				listing.put(
					root.relativize(file).toString(),
					// milliseconds, because the copy keeps the modification time only as precisely as the file system
					// it copies to stores it
					Files.size(file) + " bytes, modified at " + Files.getLastModifiedTime(file).toMillis()
				);
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot list `" + root + "`!", ex);
		}
		return listing;
	}

	/**
	 * Digests every file under a directory.
	 *
	 * @param root the directory
	 * @return the relative path of every file mapped to its length and SHA-256 digest
	 */
	@Nonnull
	private static SortedMap<String, String> digestFiles(@Nonnull Path root) {
		final SortedMap<String, String> digests = new TreeMap<>();
		try (final Stream<Path> tree = Files.walk(root)) {
			for (final Path file : tree.filter(Files::isRegularFile).toList()) {
				final MessageDigest digest = MessageDigest.getInstance("SHA-256");
				final byte[] content = Files.readAllBytes(file);
				digests.put(
					root.relativize(file).toString(),
					content.length + " bytes, SHA-256 " + HexFormat.of().formatHex(digest.digest(content))
				);
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot digest `" + root + "`!", ex);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		return digests;
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		for (final TestPaths allocated : this.allocatedPaths) {
			cleanupTestPaths(allocated);
		}
	}

	/**
	 * Starts the engine in directories of its own.
	 *
	 * @param label              a label naming the directories
	 * @param transactionOptions the transaction settings, `null` for the defaults of the test configuration
	 * @param compacting         TRUE to configure compaction thresholds low enough for a few rewrites to cross them
	 */
	private void start(@Nonnull String label, @Nullable TransactionOptions transactionOptions, boolean compacting) {
		this.transactionOptions = transactionOptions;
		this.compacting = compacting;
		this.paths = allocatePaths(label);
		this.configuration = configurationOf(this.paths);
		startEngine();
	}

	/**
	 * Allocates a set of directories and remembers it for {@link #tearDown()}.
	 *
	 * @param label a label naming the directories
	 * @return the directories, not created yet
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths allocated =
			createTestPaths(VirtualPartitionReloadInvariantTest.class.getSimpleName() + "_" + label);
		this.allocatedPaths.add(allocated);
		return allocated;
	}

	/**
	 * Builds the configuration of an engine over the given directories, with the settings {@link #start} chose.
	 *
	 * @param enginePaths the directories of the engine
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(@Nonnull TestPaths enginePaths) {
		final EvitaConfiguration.Builder builder = newTestEvitaConfigurationBuilder(enginePaths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build());
		if (this.transactionOptions != null) {
			builder.transaction(this.transactionOptions);
		}
		if (this.compacting) {
			builder.storage(
				StorageOptions.builder()
					.storageDirectory(enginePaths.storage())
					.workDirectory(enginePaths.work())
					.fileSizeCompactionThresholdBytes(16_384)
					.minimalActiveRecordShare(0.9)
					.minCompactionIntervalMilliseconds(0)
					.timeTravelEnabled(false)
					.build()
			);
		}
		return builder.build();
	}

	/**
	 * Starts a fresh engine with the current configuration, told nothing about any catalog, and waits until it has
	 * loaded every catalog.
	 */
	private void startEngine() {
		final Evita started = new Evita(Objects.requireNonNull(this.configuration));
		this.evita = started;
		started.waitUntilFullyInitialized();
		for (final String catalogName : started.getCatalogNames()) {
			await()
				.atMost(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(() -> started.getCatalogInstance(catalogName).orElse(null) instanceof Catalog);
		}
	}

	/**
	 * Closes the engine cleanly, which publishes everything it applied, and starts a fresh one over the same
	 * directories.
	 */
	private void restart() {
		engine().close();
		startEngine();
	}

	/**
	 * Takes the image a crash would leave of the running engine - a copy of its storage directory taken while the
	 * engine is quiescent and before it is closed - closes the engine and boots a fresh one from the copy, which
	 * replays the write-ahead log the copy holds past the last checkpoint.
	 *
	 * Closing the engine publishes everything it applied, so the copy is taken before the close. The copy is taken
	 * only once the catalog has incorporated everything its write-ahead log holds, and is accepted only when no file of
	 * the storage directory changed while it was being copied and the catalog published no new version meanwhile.
	 *
	 * @param catalogName the catalog
	 * @return what the engine had applied and checkpointed when it was copied, and what the recovered one loaded
	 */
	@Nonnull
	private CrashRecovery crashAndRecover(@Nonnull String catalogName) {
		final Evita running = engine();
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> catalogOf(running, catalogName).getVersion() ==
					catalogOf(running, catalogName).getLastCatalogVersionInMutationStream()
			);
		final Catalog catalog = catalogOf(running, catalogName);
		final long appliedVersion = catalog.getVersion();
		final long persistedVersion = catalog.getLastPersistedCatalogVersion();

		final TestPaths image = allocatePaths("crashImage");
		final Path storage = storageDirectory();
		final SortedMap<String, String> beforeCopy = listFiles(storage);
		CatalogReloadTestSupport.copyRecursively(storage, image.storage());
		assertEquals(
			beforeCopy, listFiles(storage),
			"The storage of the engine changed while it was being copied - the image is not the state of one moment"
		);
		assertEquals(beforeCopy, listFiles(image.storage()), "The image must hold every file of the storage, whole");
		assertSame(catalog, catalogOf(running, catalogName), "The catalog published a new version while copied");

		running.close();
		this.paths = image;
		this.configuration = configurationOf(image);
		startEngine();
		return new CrashRecovery(appliedVersion, persistedVersion, catalogOf(engine(), catalogName).getVersion());
	}

	/**
	 * Defines a catalog, asserts it is born with the storage protocol of registry rows, writes the fixture into it as
	 * one step and, for `ALIVE`, takes it live.
	 *
	 * @param catalogName the catalog
	 * @param state       the state the catalog should end in
	 * @param setUp       the fixture
	 */
	private void prepare(
		@Nonnull String catalogName,
		@Nonnull CatalogState state,
		@Nonnull Consumer<EvitaSessionContract> setUp
	) {
		engine().defineCatalog(catalogName).updateViaNewSession(engine());
		assertProtocolOfRegistryRows("the new catalog", catalogName);
		step(catalogName, "the fixture", setUp);
		if (state == CatalogState.ALIVE) {
			engine().updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
			assertEquals(CatalogState.ALIVE, catalogOf(engine(), catalogName).getCatalogState());
			assertCheckpoint("after going live", catalogName);
		}
	}

	/**
	 * Runs the history of {@link #runHistory(Consumer)} as steps of the catalog.
	 *
	 * @param catalogName the catalog
	 */
	private void runHistory(@Nonnull String catalogName) {
		final int[] stepNumber = {0};
		runHistory(step -> step(catalogName, "history step " + (++stepNumber[0]), step));
	}

	/**
	 * Runs one step in a session of its own, waits until it is committed and visible, and checks the checkpoint it
	 * reached (see {@link #assertCheckpoint(String, String)}).
	 *
	 * @param catalogName the catalog
	 * @param label       names the step in failure messages
	 * @param step        the step
	 */
	private void step(
		@Nonnull String catalogName,
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> step
	) {
		engine().updateCatalog(catalogName, step);
		assertCheckpoint("after " + label, catalogName);
	}

	/**
	 * Checks a committed checkpoint of a catalog: the invariants of every collection, and every partition of every
	 * collection against the entity bodies and its primary key against the identity the ledger of the catalog
	 * observed at the checkpoints before.
	 *
	 * @param label       names the checkpoint in failure messages
	 * @param catalogName the catalog
	 */
	private void assertCheckpoint(@Nonnull String label, @Nonnull String catalogName) {
		final Evita theEvita = engine();
		assertInvariants(label, theEvita, catalogName);
		final PartitionIdentityLedger ledger = ledgerOf(catalogName);
		theEvita.queryCatalog(
			catalogName,
			(Consumer<EvitaSessionContract>) session -> {
				for (final String entityType : new TreeSet<>(catalogOf(theEvita, catalogName).getEntityTypes())) {
					VirtualReferencePartitionRawRowOracle.assertConsistent(
						label, session, collectionOf(theEvita, catalogName, entityType), ledger
					);
				}
			}
		);
	}

	/**
	 * Returns the identity ledger of a catalog, creating it at its first checkpoint.
	 *
	 * @param catalogName the catalog
	 * @return the ledger
	 */
	@Nonnull
	private PartitionIdentityLedger ledgerOf(@Nonnull String catalogName) {
		return this.ledgers.computeIfAbsent(catalogName, __ -> new PartitionIdentityLedger());
	}

	/**
	 * Asserts the header of a loaded catalog carries the current storage protocol, under which every catalog writes its
	 * single-owner partitions as registry rows.
	 *
	 * @param label       names the moment in the failure message
	 * @param catalogName the catalog
	 */
	private void assertProtocolOfRegistryRows(@Nonnull String label, @Nonnull String catalogName) {
		// through the wildcard: `Catalog` holds the service under the interface's parameterisation
		final CatalogPersistenceService<?, ?, ?> persistenceService =
			catalogOf(engine(), catalogName).getPersistenceService();
		final DefaultCatalogPersistenceService defaultPersistenceService =
			(DefaultCatalogPersistenceService) persistenceService;
		assertEquals(
			PersistenceService.STORAGE_PROTOCOL_VERSION,
			defaultPersistenceService.getCatalogHeader(defaultPersistenceService.getLastCatalogVersion())
				.storageProtocolVersion(),
			label + ": the storage protocol of `" + catalogName + "`"
		);
	}

	/**
	 * Lets a new entity enter a partition alone after an event and asserts that the partition got a primary key
	 * greater than every primary key the collection used before - a sequence restored lower than a registered
	 * primary key would hand one out again - and that a reload keeps the new row under its primary key, persists the
	 * sequence past it and writes no index for it.
	 *
	 * @param label       names the event in failure messages
	 * @param catalogName the catalog
	 * @param entityType  the entity type whose new entity enters the partition
	 * @param reload      reloads the engine of the catalog
	 */
	private void assertMintsAboveEveryUsedPrimaryKey(
		@Nonnull String label,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Runnable reload
	) {
		final SortedSet<Integer> usedBefore = usedPrimaryKeysOf(collectionOf(engine(), catalogName, entityType));
		final SortedMap<Integer, RegisteredRow> rowsBefore =
			registeredRowsOf(collectionOf(engine(), catalogName, entityType));
		final RepresentativeReferenceKey mintedKey = categoryKey(MINTED_CATEGORY);

		// the checkpoint follows the assertions on the primary key, so a sequence handing out a used primary key fails
		// on them, not on whatever the reuse broke
		engine().updateCatalog(catalogName, mintingStep(entityType));

		final int minted = registeredPartitionOf(engine(), catalogName, entityType, Scope.LIVE, mintedKey);
		assertTrue(minted > 0, label + ": the new entity must have entered its partition as a row");
		assertTrue(
			minted > usedBefore.last(),
			label + ": the partition entered after the event must get a primary key above every one the collection " +
				"used before it (" + usedBefore.last() + "), got " + minted + " - used before: " + usedBefore
		);
		final SortedMap<Integer, RegisteredRow> rowsAfterMint =
			registeredRowsOf(collectionOf(engine(), catalogName, entityType));
		for (final Entry<Integer, RegisteredRow> row : rowsBefore.entrySet()) {
			assertEquals(
				row.getValue(), rowsAfterMint.get(row.getKey()),
				label + ": entering a new partition must leave the row of partition " + row.getKey() + " alone"
			);
		}
		assertCheckpoint(label + " / after a new entity entered a free category alone", catalogName);

		reload.run();
		assertEquals(
			minted, registeredPartitionOf(engine(), catalogName, entityType, Scope.LIVE, mintedKey),
			label + ": the reload must keep the new row under its primary key"
		);
		final EntityCollection reloaded = collectionOf(engine(), catalogName, entityType);
		assertTrue(
			reloaded.getEntityCollectionHeader().lastEntityIndexPrimaryKey() >= minted,
			label + ": the persisted sequence must have passed the primary key of the new row"
		);
		assertCheckpoint(label + " / after the new row was reloaded", catalogName);
	}

	/**
	 * Returns the running engine.
	 *
	 * @return the engine
	 */
	@Nonnull
	private Evita engine() {
		return Objects.requireNonNull(this.evita);
	}

	/**
	 * Events keeping the catalog where it is, each compared with the catalog before it.
	 */
	@Nested
	@DisplayName("Events keeping the catalog, compared with the catalog before them")
	class InPlace {

		@Test
		@DisplayName("a new catalog is born with the storage protocol of registry rows and keeps it through a reload")
		void shouldBeBornWithTheStorageProtocolOfRegistryRows() {
			start("born", null, false);
			prepare(CATALOG, CatalogState.WARMING_UP, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			assertFalse(
				registeredRowsOf(collectionOf(engine(), CATALOG, ENTITY_PRODUCT)).isEmpty(),
				"a catalog created without any request must write its single-owner partitions as registry rows"
			);

			restart();

			assertProtocolOfRegistryRows("after the reload", CATALOG);
			assertCheckpoint("after the reload", CATALOG);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a close and reopen keeps every row, its primary key, the sequence past it and no index of it")
		void shouldKeepEveryRowThroughCloseAndReopen(CatalogState state) {
			start("reopen", null, false);
			prepare(CATALOG, state, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			runHistory(CATALOG);
			final SortedMap<String, String> before = describeIndexes(engine(), CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), CATALOG);

			restart();

			assertUnchanged("after the reopen", before, describeIndexes(engine(), CATALOG));
			assertUnchanged(
				"after the reopen: the query lines", queries, CatalogDescriber.describeQueriesOf(engine(), CATALOG)
			);
			assertCheckpoint("after the reopen", CATALOG);
			assertStoredParts("after the reopen", engine(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(
				"after the reopen", CATALOG, ENTITY_PRODUCT, VirtualPartitionReloadInvariantTest.this::restart
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a partition entered without an index object advances the persisted sequence and stores no index")
		void shouldPersistTheSequencePastAPartitionEnteredWithoutAnIndexObject(CatalogState state) {
			start("enteredThenReloaded", null, false);
			prepare(CATALOG, state, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			// a reload first, so the persisted sequence the entering is measured against is the one a load starts from
			restart();
			final int sequenceBefore = collectionOf(engine(), CATALOG, ENTITY_PRODUCT)
				.getEntityCollectionHeader()
				.lastEntityIndexPrimaryKey();

			step(CATALOG, "a new product enters a free category alone", mintingStep(ENTITY_PRODUCT));
			final int entered = registeredPartitionOf(
				engine(), CATALOG, ENTITY_PRODUCT, Scope.LIVE, categoryKey(MINTED_CATEGORY)
			);
			assertTrue(entered > sequenceBefore, "the partition must have taken a new primary key: " + entered);

			restart();

			final EntityCollection products = collectionOf(engine(), CATALOG, ENTITY_PRODUCT);
			assertEquals(
				entered,
				registeredPartitionOf(engine(), CATALOG, ENTITY_PRODUCT, Scope.LIVE, categoryKey(MINTED_CATEGORY)),
				"the reload must keep the row under the primary key it was entered with"
			);
			assertTrue(
				products.getEntityCollectionHeader().lastEntityIndexPrimaryKey() >= entered,
				"the persisted sequence must have passed the primary key of the row entered without an index object"
			);
			assertFalse(
				products.getEntityCollectionHeader().usedEntityIndexPrimaryKeys().contains(entered),
				"no index may be listed for the row entered without an index object"
			);
			assertNull(
				products.getStoragePartPersistenceService().getStoragePart(
					catalogOf(engine(), CATALOG).getVersion(), entered, EntityIndexStoragePart.class
				),
				"no entity index may be stored for the row entered without an index object"
			);
			assertCheckpoint("after the reload", CATALOG);
			assertStoredParts("after the reload", engine(), CATALOG);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a clean reload followed by a close leaves every file of the catalog as it was")
		void shouldLeaveEveryFileOfTheCatalogAsItWasThroughACleanReload(CatalogState state) {
			start("cleanReload", null, false);
			prepare(CATALOG, state, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			runHistory(CATALOG);
			final SortedMap<String, String> before = describeIndexes(engine(), CATALOG);
			// the close publishes everything the history applied, so the files are what the next load starts from
			engine().close();
			final Path catalogDirectory = EvitaTestSupport.catalogDirectory(storageDirectory(), CATALOG);
			final SortedMap<String, String> files = digestFiles(catalogDirectory);
			assertFalse(files.isEmpty(), "the catalog must have been written to `" + catalogDirectory + "`");

			startEngine();
			assertUnchanged("after the load", before, describeIndexes(engine(), CATALOG));
			assertCheckpoint("after the load", CATALOG);
			engine().close();

			assertUnchanged("the files of the catalog after a load and a close", files, digestFiles(catalogDirectory));
		}

		@Test
		@DisplayName("a crash between commit and checkpoint replays every row of the tail under the same keys")
		@Tag(WAL)
		void shouldReplayEveryRowOfTheWriteAheadLogTail() {
			start(
				"crash", TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build(), false
			);
			prepare(CATALOG, CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			// going live is deferred like any other round; the clean close publishes it, so the history is the tail
			restart();
			assertCheckpoint("after the checkpoint", CATALOG);
			final SortedMap<String, IndexIdentity> checkpointed = describeIndexIdentities(engine(), CATALOG);
			runHistory(CATALOG);
			final SortedMap<String, String> before = describeIndexes(engine(), CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), CATALOG);
			final SortedMap<String, IndexIdentity> identitiesBefore = describeIndexIdentities(engine(), CATALOG);
			final SortedSet<String> createdAfterCheckpoint = createdSince(checkpointed, identitiesBefore);
			// the replay mints the primary key of every partition the tail created anew - the ledger is told so
			final PartitionIdentityLedger ledger = ledgerOf(CATALOG);
			for (final String created : createdAfterCheckpoint) {
				final IndexIdentity identity = identitiesBefore.get(created);
				if (identity.partitionKey() != null) {
					ledger.declareReplayed(
						identity.entityType(), Objects.requireNonNull(identity.scope()), identity.partitionKey()
					);
				}
			}

			final CrashRecovery recovered = crashAndRecover(CATALOG);

			assertTrue(
				recovered.appliedVersion() > recovered.persistedVersion(),
				"the image must hold the history in the write-ahead log past the last checkpoint: applied " +
					recovered.appliedVersion() + ", checkpointed " + recovered.persistedVersion()
			);
			assertEquals(
				recovered.appliedVersion(), recovered.loadedVersion(),
				"the recovery must have replayed the whole tail of the write-ahead log"
			);
			assertUnchangedModuloReminted(
				"after the crash recovery",
				before, identitiesBefore,
				describeIndexes(engine(), CATALOG), describeIndexIdentities(engine(), CATALOG),
				createdAfterCheckpoint
			);
			assertUnchanged(
				"after the crash recovery: the query lines", queries,
				CatalogDescriber.describeQueriesOf(engine(), CATALOG)
			);
			final int comparedBefore = ledger.getComparedObservations();
			assertCheckpoint("after the crash recovery", CATALOG);
			assertTrue(
				ledger.getComparedObservations() > comparedBefore,
				"the ledger must have compared the partitions with those it observed before the crash"
			);
			final SortedMap<String, String> recoveredIndexes = describeIndexes(engine(), CATALOG);
			restart();
			assertUnchanged(
				"after the reload of the recovered catalog", recoveredIndexes, describeIndexes(engine(), CATALOG)
			);
			assertCheckpoint("after the reload of the recovered catalog", CATALOG);
			assertStoredParts("after the reload of the recovered catalog", engine(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(
				"after the crash recovery", CATALOG, ENTITY_PRODUCT, VirtualPartitionReloadInvariantTest.this::restart
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a renamed collection keeps every row under its primary key")
		void shouldKeepEveryRowOfARenamedCollection(CatalogState state) {
			start("rename", null, false);
			prepare(CATALOG, state, SingleOwnerPartitionFixture::setUp);
			runHistory(CATALOG);
			final SortedMap<String, String> before = renamed(indexesOf(ENTITY_PRODUCT), ENTITY_PRODUCT, ENTITY_GOODS);

			step(CATALOG, "the products are renamed", session -> {
				session.renameCollection(ENTITY_PRODUCT, ENTITY_GOODS);
			});

			assertUnchanged("after the rename", before, indexesOf(ENTITY_GOODS));
			final Runnable reload = reloadAfterCollectionChange(state, ENTITY_GOODS);
			reload.run();
			assertUnchanged("after the reload of the renamed collection", before, indexesOf(ENTITY_GOODS));
			assertCheckpoint("after the reload of the renamed collection", CATALOG);
			assertStoredParts("after the reload of the renamed collection", engine(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey("after the rename", CATALOG, ENTITY_GOODS, reload);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a collection replacing another one brings its rows along under their primary keys")
		void shouldKeepEveryRowOfACollectionReplacingAnother(CatalogState state) {
			start("replace", null, false);
			prepare(CATALOG, state, VirtualPartitionReloadInvariantTest::setUpWithGoods);
			runHistory(CATALOG);
			final SortedMap<String, String> before = renamed(indexesOf(ENTITY_GOODS), ENTITY_GOODS, ENTITY_PRODUCT);
			assertFalse(
				registeredRowsOf(collectionOf(engine(), CATALOG, ENTITY_GOODS)).isEmpty(),
				"the replacing collection must hold rows"
			);
			// the product collection is another collection from now on, with other partitions under the primary keys
			// the products used - its identity is observed anew
			VirtualPartitionReloadInvariantTest.this.ledgers.remove(CATALOG);

			step(CATALOG, "the goods replace the products", session -> {
				session.replaceCollection(ENTITY_PRODUCT, ENTITY_GOODS);
			});

			assertUnchanged("after the replacement", before, indexesOf(ENTITY_PRODUCT));
			final Runnable reload = reloadAfterCollectionChange(state, ENTITY_PRODUCT);
			reload.run();
			assertUnchanged("after the reload of the replaced collection", before, indexesOf(ENTITY_PRODUCT));
			assertCheckpoint("after the reload of the replaced collection", CATALOG);
			assertStoredParts("after the reload of the replaced collection", engine(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey("after the replacement", CATALOG, ENTITY_PRODUCT, reload);
		}

		@Test
		@DisplayName("going live leaves every row and the registry as a warming-up catalog wrote them, also reloaded")
		void shouldKeepEveryRowThroughGoingLive() {
			start("goLive", null, false);
			prepare(CATALOG, CatalogState.WARMING_UP, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			runHistory(CATALOG);
			final SortedMap<String, String> before = describeIndexes(engine(), CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), CATALOG);

			engine().updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);

			assertEquals(CatalogState.ALIVE, catalogOf(engine(), CATALOG).getCatalogState());
			assertUnchanged("after going live", before, describeIndexes(engine(), CATALOG));
			assertUnchanged(
				"after going live: the query lines", queries, CatalogDescriber.describeQueriesOf(engine(), CATALOG)
			);
			assertCheckpoint("after going live", CATALOG);
			restart();
			assertUnchanged("after the reload of the live catalog", before, describeIndexes(engine(), CATALOG));
			assertCheckpoint("after the reload of the live catalog", CATALOG);
			assertStoredParts("after the reload of the live catalog", engine(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(
				"after going live", CATALOG, ENTITY_PRODUCT, VirtualPartitionReloadInvariantTest.this::restart
			);
		}

		/**
		 * Describes the indexes of one collection of the catalog - see
		 * {@link CatalogDescriber#describeIndexesOf(EntityCollection)}.
		 *
		 * @param entityType the entity type
		 * @return the lines, sorted by path
		 */
		@Nonnull
		private SortedMap<String, String> indexesOf(@Nonnull String entityType) {
			return CatalogDescriber.describeIndexesOf(collectionOf(engine(), CATALOG, entityType));
		}

		/**
		 * Returns how a test reloads the engine after a collection was renamed or replaced: a catalog warming up is
		 * restarted. A live catalog whose last transaction before a restart renamed or replaced a collection does not
		 * load again - issue #1709 - so a live one first takes one more write into the relabelled collection, the
		 * workaround that issue documents, and is restarted after it. The write adds associated data to an entity,
		 * which no index holds, so the indexes the test compares stay as they were.
		 *
		 * @param state      the state of the catalog
		 * @param entityType the relabelled collection
		 * @return the reload
		 */
		@Nonnull
		private Runnable reloadAfterCollectionChange(@Nonnull CatalogState state, @Nonnull String entityType) {
			if (state == CatalogState.WARMING_UP) {
				return VirtualPartitionReloadInvariantTest.this::restart;
			}
			final int[] round = {0};
			return () -> {
				final String note = "written after the relabel, round " + (++round[0]);
				step(
					CATALOG, "one more write into the relabelled collection",
					session -> session.getEntity(entityType, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAssociatedData(ASSOCIATED_DATA_NOTE, note)
						.upsertVia(session)
				);
				restart();
			};
		}
	}

	/**
	 * Events producing another catalog, each compared with the catalog it was produced from.
	 */
	@Nested
	@DisplayName("Events producing another catalog, compared with the catalog they were produced from")
	class IntoAnotherCatalog {
		/**
		 * The catalog the events produce another one from.
		 */
		private static final String SOURCE_CATALOG = "sourceCatalog";
		/**
		 * The catalog an event produces.
		 */
		private static final String PRODUCED_CATALOG = "producedCatalog";
		/**
		 * Filler wide enough that a few rewrites of the products cross the tiny compaction threshold.
		 */
		private static final String FILLER = "x".repeat(2_048);
		/**
		 * How many rounds rewriting every product a compacting test runs.
		 */
		private static final int REWRITE_ROUNDS = 4;
		/**
		 * The products every round of a compacting test rewrites - the live ones the history keeps in their own
		 * category.
		 */
		private static final int[] REWRITTEN_PRODUCTS = {1, 2, 3, 6};

		/**
		 * The identities of the indexes of the source catalog at its last checkpoint, before its history.
		 */
		@Nullable private SortedMap<String, IndexIdentity> checkpointed;

		@Test
		@DisplayName("a duplicate under a new name and identifier keeps every row of its source")
		void shouldKeepEveryRowOfADuplicate() {
			startWithHistory("duplicate", false);
			final SortedMap<String, String> before = describeIndexes(engine(), SOURCE_CATALOG);
			final SortedMap<String, IndexIdentity> identitiesBefore = describeIndexIdentities(engine(), SOURCE_CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), SOURCE_CATALOG);

			// a duplicate loads the last checkpoint of its source and replays the write-ahead log on top of it
			engine().duplicateCatalog(SOURCE_CATALOG, PRODUCED_CATALOG);
			engine().activateCatalog(PRODUCED_CATALOG);

			assertNotEquals(
				catalogOf(engine(), SOURCE_CATALOG).getCatalogId(),
				catalogOf(engine(), PRODUCED_CATALOG).getCatalogId(),
				"the duplicate must have been given an identifier of its own"
			);
			assertProduced("after the duplication", before, identitiesBefore, queries);
			final SortedMap<String, String> duplicated = describeIndexes(engine(), PRODUCED_CATALOG);
			restart();
			assertReloaded("after the reload of the duplicate", duplicated, queries);
			assertMintsAboveEveryUsedPrimaryKey(
				"after the duplication", PRODUCED_CATALOG, ENTITY_PRODUCT,
				VirtualPartitionReloadInvariantTest.this::restart
			);
			assertUnchanged(
				"the source after a new row in its duplicate", before, describeIndexes(engine(), SOURCE_CATALOG)
			);
		}

		@ParameterizedTest(name = "point in time: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("a backup restored under a new name keeps every row of its source")
		void shouldKeepEveryRowOfARestoredBackup(boolean pointInTime) throws Exception {
			startWithHistory("restore", false);
			final SortedMap<String, String> before = describeIndexes(engine(), SOURCE_CATALOG);
			final SortedMap<String, IndexIdentity> identitiesBefore = describeIndexIdentities(engine(), SOURCE_CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), SOURCE_CATALOG);

			final FileForFetch backup = pointInTime ?
				engine().management().backupCatalog(SOURCE_CATALOG, OffsetDateTime.now(), null, false)
					.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS) :
				engine().management().fullBackupCatalog(SOURCE_CATALOG)
					.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			restore(backup);

			assertNotEquals(
				catalogOf(engine(), SOURCE_CATALOG).getCatalogId(),
				catalogOf(engine(), PRODUCED_CATALOG).getCatalogId(),
				"the restored catalog must have been given an identifier of its own"
			);
			assertProduced("after the restore", before, identitiesBefore, queries);
			final SortedMap<String, String> restored = describeIndexes(engine(), PRODUCED_CATALOG);
			restart();
			assertReloaded("after the reload of the restored catalog", restored, queries);
			assertMintsAboveEveryUsedPrimaryKey(
				"after the restore", PRODUCED_CATALOG, ENTITY_PRODUCT, VirtualPartitionReloadInvariantTest.this::restart
			);
		}

		@Test
		@DisplayName("compaction of a catalog keeps every row, also when reloaded")
		void shouldKeepEveryRowThroughCompactionAndReload() {
			startWithHistory("compaction", true);
			// every round rewrites the products, superseding their bodies and the registry values of their rows
			for (int round = 1; round <= REWRITE_ROUNDS; round++) {
				final int theRound = round;
				step(SOURCE_CATALOG, "rewrite round " + round, session -> rewriteProducts(session, theRound));
			}
			assertCompacted(SOURCE_CATALOG);
			final SortedMap<String, String> before = describeIndexes(engine(), SOURCE_CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), SOURCE_CATALOG);

			restart();

			assertUnchanged(
				"after the reload of the compacted catalog", before, describeIndexes(engine(), SOURCE_CATALOG)
			);
			assertUnchanged(
				"after the reload of the compacted catalog: the query lines", queries,
				CatalogDescriber.describeQueriesOf(engine(), SOURCE_CATALOG)
			);
			assertCheckpoint("after the reload of the compacted catalog", SOURCE_CATALOG);
			assertStoredParts("after the reload of the compacted catalog", engine(), SOURCE_CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(
				"after the compaction", SOURCE_CATALOG, ENTITY_PRODUCT,
				VirtualPartitionReloadInvariantTest.this::restart
			);
		}

		/**
		 * Starts the engine and builds the source catalog: the fixture of single-owner partitions with the reflected
		 * reference loaded in warm-up and the history of {@link #runHistory(Consumer)}.
		 *
		 * A compacting engine keeps the catalog warming up, where every session flushes, and runs the history there.
		 * Any other engine defers every checkpoint, takes the catalog live and is restarted, which publishes going live
		 * as the last checkpoint, and commits the history after it - so the history is exactly the tail of the
		 * write-ahead log a copy of the catalog replays past that checkpoint.
		 *
		 * @param label      a label naming the engine's directories
		 * @param compacting TRUE to configure compaction thresholds low enough for a few rewrites to cross them
		 */
		private void startWithHistory(@Nonnull String label, boolean compacting) {
			start(
				label,
				compacting ?
					null : TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build(),
				compacting
			);
			if (compacting) {
				prepare(
					SOURCE_CATALOG, CatalogState.WARMING_UP, SingleOwnerPartitionFixture::setUpWithReflectedProducts
				);
			} else {
				prepare(SOURCE_CATALOG, CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
				restart();
			}
			this.checkpointed = describeIndexIdentities(engine(), SOURCE_CATALOG);
			runHistory(SOURCE_CATALOG);
		}

		/**
		 * Asserts that the catalog an event produced is born with the storage protocol of registry rows, holds
		 * exactly the indexes of the source modulo the primary keys a replay of the source's write-ahead log minted
		 * anew (see {@link #assertUnchangedModuloReminted}), answers every query line as the source does and keeps the
		 * invariants.
		 *
		 * @param label            names the moment in failure messages
		 * @param before           the index-level lines of the source
		 * @param identitiesBefore the identities of the indexes of the source
		 * @param queries          the query lines of the source
		 */
		private void assertProduced(
			@Nonnull String label,
			@Nonnull SortedMap<String, String> before,
			@Nonnull SortedMap<String, IndexIdentity> identitiesBefore,
			@Nonnull SortedMap<String, String> queries
		) {
			assertProtocolOfRegistryRows(label, PRODUCED_CATALOG);
			assertUnchangedModuloReminted(
				label,
				before, identitiesBefore,
				describeIndexes(engine(), PRODUCED_CATALOG), describeIndexIdentities(engine(), PRODUCED_CATALOG),
				createdSince(Objects.requireNonNull(this.checkpointed), identitiesBefore)
			);
			assertUnchanged(
				label + ": the query lines", queries, CatalogDescriber.describeQueriesOf(engine(), PRODUCED_CATALOG)
			);
			assertCheckpoint(label, PRODUCED_CATALOG);
		}

		/**
		 * Asserts that the produced catalog reloaded holds exactly the indexes it held before the reload, answers every
		 * query line as the source does, keeps the invariants and stores exactly what its indexes and its bodies
		 * account for.
		 *
		 * @param label   names the moment in failure messages
		 * @param indexes the index-level lines of the produced catalog before the reload
		 * @param queries the query lines of the source
		 */
		private void assertReloaded(
			@Nonnull String label,
			@Nonnull SortedMap<String, String> indexes,
			@Nonnull SortedMap<String, String> queries
		) {
			assertProtocolOfRegistryRows(label, PRODUCED_CATALOG);
			assertUnchanged(label, indexes, describeIndexes(engine(), PRODUCED_CATALOG));
			assertUnchanged(
				label + ": the query lines", queries, CatalogDescriber.describeQueriesOf(engine(), PRODUCED_CATALOG)
			);
			assertCheckpoint(label, PRODUCED_CATALOG);
			assertStoredParts(label, engine(), PRODUCED_CATALOG);
		}

		/**
		 * Rewrites every product of the catalog: its rank, a wide code and the order of its own category reference -
		 * a value the row of that partition carries.
		 *
		 * @param session the session to write in
		 * @param round   the round, mixed into every rewritten value
		 */
		private static void rewriteProducts(@Nonnull EvitaSessionContract session, int round) {
			for (final int product : REWRITTEN_PRODUCTS) {
				updateProduct(
					product,
					it -> it.setAttribute(ATTRIBUTE_RANK, product * 10 + round)
						.setAttribute(ATTRIBUTE_CODE, "P" + product + "-" + round + "-" + FILLER)
						.setReference(
							REFERENCE_CATEGORIES, product,
							ref -> ref.setAttribute(ATTRIBUTE_ORDER, product * 100 + round)
						)
				).accept(session);
			}
		}

		/**
		 * Asserts that the product collection of the catalog has compacted at least once - a file index above zero is
		 * the only evidence a compaction leaves behind, and without it the test asserts nothing.
		 *
		 * @param catalogName the catalog
		 */
		private void assertCompacted(@Nonnull String catalogName) {
			final Pattern pattern = Pattern.compile(
				Pattern.quote(StringUtils.toCamelCase(ENTITY_PRODUCT)) + "-\\d+_(\\d+)" +
					Pattern.quote(ENTITY_COLLECTION_FILE_SUFFIX)
			);
			final Path catalogDirectory = EvitaTestSupport.catalogDirectory(storageDirectory(), catalogName);
			final Set<Integer> fileIndexes = new TreeSet<>();
			try (Stream<Path> files = Files.list(catalogDirectory)) {
				files.forEach(file -> {
					final Matcher matcher = pattern.matcher(file.getFileName().toString());
					if (matcher.matches()) {
						fileIndexes.add(Integer.parseInt(matcher.group(1)));
					}
				});
			} catch (IOException ex) {
				throw new IllegalStateException("Cannot list `" + catalogDirectory + "`!", ex);
			}
			assertTrue(
				fileIndexes.stream().anyMatch(index -> index > 0),
				"the products of `" + catalogName + "` must have compacted at least once: " + fileIndexes
			);
		}

		/**
		 * Restores the backup under {@link #PRODUCED_CATALOG} and activates it.
		 *
		 * @param backup the finished backup
		 */
		private void restore(@Nonnull FileForFetch backup) throws Exception {
			final Path archive = backup.path(
				((FileSystemExportOptions) engine().getConfiguration().export()).getDirectory()
			);
			assertTrue(Files.isRegularFile(archive), "the backup archive must have been written");
			try (InputStream inputStream = Files.newInputStream(archive)) {
				engine().management()
					.restoreCatalog(PRODUCED_CATALOG, Files.size(archive), inputStream)
					.getFutureResult()
					.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			engine().activateCatalog(PRODUCED_CATALOG);
		}
	}

	/**
	 * Returns the storage directory of the running engine.
	 *
	 * @return the directory
	 */
	@Nonnull
	private Path storageDirectory() {
		return Objects.requireNonNull(this.paths).storage();
	}

	/**
	 * One index of a collection as {@link #describeIndexIdentities(Evita, String)} identifies it.
	 *
	 * @param entityType   the entity type of the collection
	 * @param primaryKey   the primary key of the index
	 * @param scope        the scope of a partition, `null` for any other index
	 * @param partitionKey the key of a partition, `null` for any other index
	 */
	private record IndexIdentity(
		@Nonnull String entityType,
		int primaryKey,
		@Nullable Scope scope,
		@Nullable RepresentativeReferenceKey partitionKey
	) {
	}

	/**
	 * What a crash recovery found and did.
	 *
	 * @param appliedVersion   the catalog version the engine had applied when its storage was copied
	 * @param persistedVersion the catalog version the engine had checkpointed when its storage was copied
	 * @param loadedVersion    the catalog version the recovered engine loaded, the write-ahead log replay included
	 */
	private record CrashRecovery(long appliedVersion, long persistedVersion, long loadedVersion) {
	}

}
