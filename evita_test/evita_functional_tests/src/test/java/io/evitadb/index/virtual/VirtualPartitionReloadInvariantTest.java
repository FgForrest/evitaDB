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

// TODO JNO - remove at the end of #1615 (the whole file)
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
import io.evitadb.utils.NumberUtils;
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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Map.Entry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a catalog writing its single-owner reference partitions as registry rows survives every event of its
 * lifecycle with every row, the primary key of every row and the membership of every owner intact, and that nothing on
 * the way silently brings an index object of a single-owner partition back or loses a row.
 *
 * After every event the catalog must keep the invariants a reload relies on (see
 * {@link VirtualPartitionInvariants}).
 *
 * The index-level description of every collection (see {@link CatalogDescriber#describeIndexesOf}) - the registry
 * rows with their primary keys, owners, sort values and chain predecessors, the advertised partitions, the index
 * objects and the membership - must be exactly what it was before the event, and a partition an owner enters after the
 * event must get a primary key greater than every primary key the collection used before it, which a reload must keep
 * without writing an index for it (see {@link #assertMintsAboveEveryUsedPrimaryKey}).
 *
 * The events that keep the catalog in its engine run through {@link DifferentialEngines#writtenVirtually}, so the
 * catalog is also compared with its twin writing index objects at every checkpoint, the query lines included: a reload,
 * a crash recovery replaying the write-ahead log, a collection renamed or replaced, and going live. The events that
 * produce another catalog - a catalog replaced across the two representations in either direction, a duplicate, a
 * backup restored under a new name, and a compaction - run in one engine hosting a virtual catalog next to a catalog
 * with index objects built by the same history, and the query lines of the catalog the event produced must equal those
 * of the catalog with index objects.
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
	 * Every entity type of the histories of this test.
	 */
	private static final Set<String> ALL_ENTITY_TYPES = Set.of(
		ENTITY_PRODUCT, ENTITY_CATEGORY, SingleOwnerPartitionFixture.ENTITY_MEDIA,
		SingleOwnerPartitionFixture.ENTITY_BRAND, ENTITY_GOODS
	);
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
	 * Runs the history of {@link #runHistory(Consumer)} as steps of the engines.
	 *
	 * @param engines the engines
	 */
	private static void runHistory(@Nonnull DifferentialEngines engines) {
		final int[] stepNumber = {0};
		runHistory(step -> engines.step("history step " + (++stepNumber[0]), step));
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
	static SortedSet<Integer> usedPrimaryKeysOf(@Nonnull EntityCollection collection) {
		final EntityCollectionHeader header = collection.getEntityCollectionHeader();
		final SortedSet<Integer> used = new TreeSet<>(header.usedEntityIndexPrimaryKeys());
		if (header.globalEntityIndexPrimaryKey() != null) {
			used.add(header.globalEntityIndexPrimaryKey());
		}
		used.addAll(registeredRowsOf(collection).keySet());
		return used;
	}

	/**
	 * Describes every partition of every reference of every collection of a committed catalog by its key - the scope,
	 * the reference, the referenced entity and the representative attribute values - with its owners and, when asked,
	 * its primary key: a partition with an index object read out of the object, any other one out of its row. The
	 * description does not depend on how a single-owner partition is represented, so a virtual catalog and a catalog of
	 * index objects holding the same partitions under the same primary keys describe alike.
	 *
	 * @param evita            the engine
	 * @param catalogName      the catalog
	 * @param withPrimaryKeys  TRUE to describe the primary key of each partition too
	 * @return the lines, sorted by path
	 */
	@Nonnull
	static SortedMap<String, String> describePartitionsByKey(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		boolean withPrimaryKeys
	) {
		final SortedMap<String, String> lines = new TreeMap<>();
		final Catalog catalog = catalogOf(evita, catalogName);
		for (final String entityType : catalog.getEntityTypes()) {
			final EntityCollection collection = collectionOf(evita, catalogName, entityType);
			for (final Scope scope : Scope.values()) {
				for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
					final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
						new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceSchema.getName())
					);
					if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
						final String prefix = entityType + "/" + scope + "/" + referenceSchema.getName() + "/";
						describePartitionsByKey(lines, prefix, collection, referencedTypeIndex, withPrimaryKeys);
					}
				}
			}
		}
		return lines;
	}

	/**
	 * Adds the lines of every partition one referenced-type entity index advertises - see
	 * {@link #describePartitionsByKey(Evita, String, boolean)}.
	 *
	 * @param lines           the lines to add to
	 * @param prefix          the path prefix naming the collection, the scope and the reference
	 * @param collection      the collection the partitions belong to
	 * @param typeIndex       the referenced-type entity index advertising them
	 * @param withPrimaryKeys TRUE to describe the primary key of each partition too
	 */
	private static void describePartitionsByKey(
		@Nonnull SortedMap<String, String> lines,
		@Nonnull String prefix,
		@Nonnull EntityCollection collection,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		boolean withPrimaryKeys
	) {
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		for (final Integer target : new TreeSet<>(typeIndex.getAllTrackedReferencedEntityPrimaryKeys())) {
			for (final int partitionPrimaryKey : typeIndex.getAllReferenceIndexes(target)) {
				// the index object wins over a row. A virtual catalog never holds both - the invariants refuse that. A
				// catalog of index objects has a registry only while the forced test mode maintains it, and then holds
				// rows next to the objects of single-owner partitions and drops a row once its partition gains a second
				// owner. A duplicate of such a catalog has a new identifier, so the forced mode does not maintain its
				// registry (`VirtualReferencePartitionMaintenance#forLoadedCatalog`): the registry it copied keeps the
				// row of every partition its replayed write-ahead log made a shared one, and only the object is current
				final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
				final Serializable[] tuple;
				final String owners;
				if (partition != null) {
					tuple = ((RepresentativeReferenceKey) partition.getIndexKey().discriminator())
						.representativeAttributeValues();
					owners = Arrays.toString(partition.getAllPrimaryKeys().getArray());
				} else {
					final OptionalLong row = registry.getPartitionOwner(partitionPrimaryKey);
					assertTrue(
						row.isPresent(),
						prefix + target + ": partition " + partitionPrimaryKey +
							" is advertised with neither an index object nor a row"
					);
					final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
					tuple = tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
						new Serializable[0] : registry.getTuple(tupleId);
					owners = "[" + NumberUtils.unpackHigh(row.getAsLong()) + "]";
				}
				final String path = prefix + target + "/" + CanonicalValue.encode(tuple);
				final String previous = lines.put(
					path,
					(withPrimaryKeys ? "primary key " + partitionPrimaryKey + ", " : "") + "owners " + owners
				);
				assertNull(previous, "two partitions of one key: " + path);
			}
		}
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
	static SortedMap<String, String> renamed(
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
	 * Lets a new entity enter a partition alone after an event and asserts that the partition got a primary key
	 * greater than every primary key the collection used before - a sequence restored lower than a registered
	 * primary key would hand one out again - and that a reload keeps the new row under its primary key, persists the
	 * sequence past it and writes no index for it.
	 *
	 * @param label       names the event in failure messages
	 * @param evita       supplies the engine of the catalog - it changes with every reload
	 * @param catalogName the catalog, representing its single-owner partitions virtually
	 * @param entityType  the entity type whose new entity enters the partition
	 * @param write       runs the minting step in the engine of the catalog
	 * @param reload      reloads the engine of the catalog
	 */
	private static void assertMintsAboveEveryUsedPrimaryKey(
		@Nonnull String label,
		@Nonnull EngineAccessor evita,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Consumer<Consumer<EvitaSessionContract>> write,
		@Nonnull Runnable reload
	) {
		final SortedSet<Integer> usedBefore = usedPrimaryKeysOf(collectionOf(evita.get(), catalogName, entityType));
		final SortedMap<Integer, RegisteredRow> rowsBefore =
			registeredRowsOf(collectionOf(evita.get(), catalogName, entityType));
		final RepresentativeReferenceKey mintedKey = categoryKey(MINTED_CATEGORY);

		write.accept(mintingStep(entityType));

		final int minted = registeredPartitionOf(evita.get(), catalogName, entityType, Scope.LIVE, mintedKey);
		assertTrue(minted > 0, label + ": the new entity must have entered its partition as a row");
		assertTrue(
			minted > usedBefore.last(),
			label + ": the partition entered after the event must get a primary key above every one the collection " +
				"used before it (" + usedBefore.last() + "), got " + minted + " - used before: " + usedBefore
		);
		final SortedMap<Integer, RegisteredRow> rowsAfterMint =
			registeredRowsOf(collectionOf(evita.get(), catalogName, entityType));
		for (final Entry<Integer, RegisteredRow> row : rowsBefore.entrySet()) {
			assertEquals(
				row.getValue(), rowsAfterMint.get(row.getKey()),
				label + ": entering a new partition must leave the row of partition " + row.getKey() + " alone"
			);
		}

		reload.run();
		assertEquals(
			minted, registeredPartitionOf(evita.get(), catalogName, entityType, Scope.LIVE, mintedKey),
			label + ": the reload must keep the new row under its primary key"
		);
		final EntityCollection reloaded = collectionOf(evita.get(), catalogName, entityType);
		assertTrue(
			reloaded.getEntityCollectionHeader().lastEntityIndexPrimaryKey() >= minted,
			label + ": the persisted sequence must have passed the primary key of the new row"
		);
		assertInvariants(label + " / after the new row was reloaded", evita.get(), catalogName);
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
	 * Events keeping the catalog in its engine, each compared with the twin writing index objects at every
	 * checkpoint.
	 */
	@Nested
	@DisplayName("In one engine, compared with the twin writing index objects")
	class InOneEngine {
		/**
		 * The engines of the test, closed after it.
		 */
		@Nullable private DifferentialEngines engines;

		@AfterEach
		void tearDown() {
			if (this.engines != null) {
				this.engines.close();
				this.engines = null;
			}
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a close and reopen keeps every row, its primary key, the sequence past it and no index of it")
		void shouldKeepEveryRowThroughCloseAndReopen(CatalogState state) {
			final DifferentialEngines theEngines = start(
				"reopen", state, null, SingleOwnerPartitionFixture::setUpWithReflectedProducts
			);
			runHistory(theEngines);
			final SortedMap<String, String> before = describeIndexes(theEngines.getMaintaining(), CATALOG);

			theEngines.restart();

			assertUnchanged("after the reopen", before, describeIndexes(theEngines.getMaintaining(), CATALOG));
			assertInvariants("after the reopen", theEngines.getMaintaining(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(theEngines, "after the reopen", ENTITY_PRODUCT);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a partition entered without an index object advances the persisted sequence and stores no index")
		void shouldPersistTheSequencePastAPartitionEnteredWithoutAnIndexObject(CatalogState state) {
			final DifferentialEngines theEngines = start(
				"enteredThenReloaded", state, null, SingleOwnerPartitionFixture::setUpWithReflectedProducts
			);
			// a reload first, so the persisted sequence the entering is measured against is the one a load starts from
			theEngines.restart();
			final int sequenceBefore = collectionOf(theEngines.getMaintaining(), CATALOG, ENTITY_PRODUCT)
				.getEntityCollectionHeader()
				.lastEntityIndexPrimaryKey();

			theEngines.step("a new product enters a free category alone", mintingStep(ENTITY_PRODUCT));
			final int entered = registeredPartitionOf(
				theEngines.getMaintaining(), CATALOG, ENTITY_PRODUCT, Scope.LIVE, categoryKey(MINTED_CATEGORY)
			);
			assertTrue(entered > sequenceBefore, "the partition must have taken a new primary key: " + entered);

			theEngines.restart();

			final EntityCollection products = collectionOf(theEngines.getMaintaining(), CATALOG, ENTITY_PRODUCT);
			assertEquals(
				entered,
				registeredPartitionOf(
					theEngines.getMaintaining(), CATALOG, ENTITY_PRODUCT, Scope.LIVE, categoryKey(MINTED_CATEGORY)
				),
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
					catalogOf(theEngines.getMaintaining(), CATALOG).getVersion(), entered, EntityIndexStoragePart.class
				),
				"no entity index may be stored for the row entered without an index object"
			);
			assertInvariants("after the reload", theEngines.getMaintaining(), CATALOG);
		}

		@Test
		@DisplayName("a crash between commit and checkpoint replays every row of the tail as the twin replays it")
		@Tag(WAL)
		void shouldReplayEveryRowOfTheWriteAheadLogTail() {
			final DifferentialEngines theEngines = start(
				"crash", CatalogState.ALIVE,
				TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build(),
				SingleOwnerPartitionFixture::setUpWithReflectedProducts
			);
			// going live is deferred like any other round; the clean close publishes it, so the history is the tail
			theEngines.restart();
			runHistory(theEngines);
			final SortedMap<String, String> before =
				describePartitionsByKey(theEngines.getMaintaining(), CATALOG, false);

			final DifferentialEngines.Reload recovered = theEngines.crashAndRecover();

			assertTrue(
				recovered.maintaining().loadedVersion() > recovered.maintaining().imageVersion(),
				"the recovery must have replayed the tail of the write-ahead log"
			);
			// the replay mints the primary keys of the tail anew from the last checkpointed sequence, exactly as it
			// does for index objects - the harness has compared them with the twin's - so the partitions are compared
			// by their keys, and the primary keys from here on
			assertUnchanged(
				"after the crash recovery", before,
				describePartitionsByKey(theEngines.getMaintaining(), CATALOG, false)
			);
			assertInvariants("after the crash recovery", theEngines.getMaintaining(), CATALOG);
			final SortedMap<String, String> recoveredIndexes = describeIndexes(theEngines.getMaintaining(), CATALOG);
			theEngines.restart();
			assertUnchanged(
				"after the reload of the recovered catalog", recoveredIndexes,
				describeIndexes(theEngines.getMaintaining(), CATALOG)
			);
			assertMintsAboveEveryUsedPrimaryKey(theEngines, "after the crash recovery", ENTITY_PRODUCT);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a renamed collection keeps every row under its primary key")
		void shouldKeepEveryRowOfARenamedCollection(CatalogState state) {
			final DifferentialEngines theEngines = start(
				"rename", state, null, SingleOwnerPartitionFixture::setUp
			);
			runHistory(theEngines);
			final SortedMap<String, String> before = renamed(
				indexesOf(theEngines, ENTITY_PRODUCT),
				ENTITY_PRODUCT, ENTITY_GOODS
			);

			theEngines.step("the products are renamed", session -> {
				session.renameCollection(ENTITY_PRODUCT, ENTITY_GOODS);
			});

			assertUnchanged("after the rename", before, indexesOf(theEngines, ENTITY_GOODS));
			final Runnable reload = reloadAfterCollectionChange(theEngines, state, ENTITY_GOODS);
			reload.run();
			assertUnchanged("after the reload of the renamed collection", before, indexesOf(theEngines, ENTITY_GOODS));
			assertInvariants("after the reload of the renamed collection", theEngines.getMaintaining(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(theEngines, "after the rename", ENTITY_GOODS, reload);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a collection replacing another one brings its rows along under their primary keys")
		void shouldKeepEveryRowOfACollectionReplacingAnother(CatalogState state) {
			final DifferentialEngines theEngines = start(
				"replace", state, null, VirtualPartitionReloadInvariantTest::setUpWithGoods
			);
			runHistory(theEngines);
			final SortedMap<String, String> before = renamed(
				indexesOf(theEngines, ENTITY_GOODS),
				ENTITY_GOODS, ENTITY_PRODUCT
			);
			assertFalse(
				registeredRowsOf(collectionOf(theEngines.getMaintaining(), CATALOG, ENTITY_GOODS)).isEmpty(),
				"the replacing collection must hold rows"
			);

			theEngines.step("the goods replace the products", session -> {
				session.replaceCollection(ENTITY_PRODUCT, ENTITY_GOODS);
			});

			assertUnchanged("after the replacement", before, indexesOf(theEngines, ENTITY_PRODUCT));
			final Runnable reload = reloadAfterCollectionChange(theEngines, state, ENTITY_PRODUCT);
			reload.run();
			assertUnchanged(
				"after the reload of the replaced collection", before, indexesOf(theEngines, ENTITY_PRODUCT)
			);
			assertInvariants("after the reload of the replaced collection", theEngines.getMaintaining(), CATALOG);
			assertMintsAboveEveryUsedPrimaryKey(theEngines, "after the replacement", ENTITY_PRODUCT, reload);
		}

		@Test
		@DisplayName("going live keeps every row a warming-up catalog wrote, also when reloaded")
		void shouldKeepEveryRowThroughGoingLive() {
			final DifferentialEngines theEngines = start(
				"goLive", CatalogState.WARMING_UP, null, SingleOwnerPartitionFixture::setUpWithReflectedProducts
			);
			runHistory(theEngines);
			final SortedMap<String, String> before = describeIndexes(theEngines.getMaintaining(), CATALOG);

			theEngines.getReference().updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			theEngines.getMaintaining().updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			theEngines.assertEqual("after going live");

			assertEquals(CatalogState.ALIVE, catalogOf(theEngines.getMaintaining(), CATALOG).getCatalogState());
			assertUnchanged("after going live", before, describeIndexes(theEngines.getMaintaining(), CATALOG));
			assertInvariants("after going live", theEngines.getMaintaining(), CATALOG);
			theEngines.restart();
			assertUnchanged(
				"after the reload of the live catalog", before, describeIndexes(theEngines.getMaintaining(), CATALOG)
			);
			assertMintsAboveEveryUsedPrimaryKey(theEngines, "after going live", ENTITY_PRODUCT);
		}

		/**
		 * Starts the engines comparing the catalog writing virtually with its twin, with the check refusing index
		 * objects of single-owner partitions, and prepares the fixture in the given state.
		 *
		 * @param label              a label naming the engines' directories
		 * @param state              the catalog state the engines should end in
		 * @param transactionOptions the transaction settings of both engines, `null` for the defaults
		 * @param setUp              the fixture
		 * @return the engines
		 */
		@Nonnull
		private DifferentialEngines start(
			@Nonnull String label,
			@Nonnull CatalogState state,
			@Nullable TransactionOptions transactionOptions,
			@Nonnull Consumer<EvitaSessionContract> setUp
		) {
			final DifferentialEngines theEngines = DifferentialEngines.writtenVirtually(
				VirtualPartitionReloadInvariantTest.this, label, ALL_ENTITY_TYPES, transactionOptions
			);
			this.engines = theEngines;
			theEngines.addMaintainingEngineCheck(
				VirtualPartitionInvariants::assertNoPartitionObjectBelowTwoOwners
			);
			theEngines.prepare(state, setUp);
			return theEngines;
		}

		/**
		 * Describes the indexes of one collection of the catalog writing virtually - see
		 * {@link CatalogDescriber#describeIndexesOf(EntityCollection)}.
		 *
		 * @param engines    the engines
		 * @param entityType the entity type
		 * @return the lines, sorted by path
		 */
		@Nonnull
		private static SortedMap<String, String> indexesOf(
			@Nonnull DifferentialEngines engines,
			@Nonnull String entityType
		) {
			return CatalogDescriber.describeIndexesOf(collectionOf(engines.getMaintaining(), CATALOG, entityType));
		}

		/**
		 * Asserts {@link VirtualPartitionReloadInvariantTest#assertMintsAboveEveryUsedPrimaryKey} in the catalog
		 * writing virtually, the new entity written to both engines and the reload a restart of both.
		 *
		 * @param engines    the engines
		 * @param label      names the event in failure messages
		 * @param entityType the entity type whose new entity enters the partition
		 */
		private static void assertMintsAboveEveryUsedPrimaryKey(
			@Nonnull DifferentialEngines engines,
			@Nonnull String label,
			@Nonnull String entityType
		) {
			assertMintsAboveEveryUsedPrimaryKey(engines, label, entityType, engines::restart);
		}

		/**
		 * Asserts {@link VirtualPartitionReloadInvariantTest#assertMintsAboveEveryUsedPrimaryKey} in the catalog
		 * writing virtually, the new entity written to both engines and the reload the given one.
		 *
		 * @param engines    the engines
		 * @param label      names the event in failure messages
		 * @param entityType the entity type whose new entity enters the partition
		 * @param reload     reloads the engines
		 */
		private static void assertMintsAboveEveryUsedPrimaryKey(
			@Nonnull DifferentialEngines engines,
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull Runnable reload
		) {
			VirtualPartitionReloadInvariantTest.assertMintsAboveEveryUsedPrimaryKey(
				label, engines::getMaintaining, CATALOG, entityType,
				step -> engines.step("a new entity enters a free category alone " + label, step),
				reload
			);
		}

		/**
		 * Returns how a test reloads the engines after a collection was renamed or replaced: a catalog warming up is
		 * restarted. A live catalog whose last transaction before a restart renamed or replaced a collection does not
		 * load again, in either representation - issue #1709 - so a live one first takes one more write into the
		 * relabelled collection, the workaround that issue documents, and is restarted after it. The write adds
		 * associated data to an entity, which no index holds, so the indexes the test compares stay as they were.
		 *
		 * @param engines    the engines
		 * @param state      the state of the catalog
		 * @param entityType the relabelled collection
		 * @return the reload
		 */
		@Nonnull
		private static Runnable reloadAfterCollectionChange(
			@Nonnull DifferentialEngines engines,
			@Nonnull CatalogState state,
			@Nonnull String entityType
		) {
			if (state == CatalogState.WARMING_UP) {
				return engines::restart;
			}
			final int[] round = {0};
			return () -> {
				final String note = "written after the relabel, round " + (++round[0]);
				engines.step(
					"one more write into the relabelled collection",
					session -> session.getEntity(entityType, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAssociatedData(ASSOCIATED_DATA_NOTE, note)
						.upsertVia(session)
				);
				engines.restart();
			};
		}
	}

	/**
	 * Events producing another catalog, in one engine hosting a virtual catalog next to a catalog with index objects
	 * built by the same history.
	 */
	@Nested
	@DisplayName("Across catalogs, compared with a catalog of index objects built by the same history")
	class AcrossCatalogs {
		/**
		 * The catalog representing its single-owner partitions virtually.
		 */
		private static final String VIRTUAL_CATALOG = "virtualCatalog";
		/**
		 * The catalog representing its single-owner partitions by index objects.
		 */
		private static final String OBJECTS_CATALOG = "objectsCatalog";
		/**
		 * The catalog an event produces.
		 */
		private static final String PRODUCED_CATALOG = "producedCatalog";
		/**
		 * The catalog an event produces out of the catalog of index objects.
		 */
		private static final String PRODUCED_OBJECTS_CATALOG = "producedObjectsCatalog";
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

		@Nullable private TestPaths paths;
		@Nullable private Evita evita;
		@Nullable private EvitaConfiguration configuration;

		@AfterEach
		void tearDown() {
			if (this.evita != null && this.evita.isActive()) {
				this.evita.close();
			}
			if (this.paths != null) {
				cleanupTestPaths(this.paths);
			}
		}

		@Test
		@DisplayName("a virtual catalog replacing a catalog of index objects brings its rows along")
		void shouldBringEveryRowAlongWhenAVirtualCatalogReplacesOneOfIndexObjects() {
			startWithTwins("replacedByVirtual", false);
			final SortedMap<String, String> before = describeIndexes(engine(), VIRTUAL_CATALOG);
			final SortedMap<String, String> queries = assertTwinsAnswerAlike("before the replacement");

			engine().replaceCatalog(VIRTUAL_CATALOG, OBJECTS_CATALOG);

			assertRepresentation(OBJECTS_CATALOG, true);
			assertProduced("after the replacement", OBJECTS_CATALOG, before, queries);
			restart();
			assertRepresentation(OBJECTS_CATALOG, true);
			assertProduced("after the reload of the replaced catalog", OBJECTS_CATALOG, before, queries);
			assertMints("after the replacement", OBJECTS_CATALOG);
		}

		@Test
		@DisplayName("a catalog of index objects replacing a virtual catalog brings its index objects along")
		void shouldBringEveryIndexObjectAlongWhenACatalogOfIndexObjectsReplacesAVirtualOne() {
			startWithTwins("replacedByObjects", false);
			final SortedMap<String, String> before = describeIndexes(engine(), OBJECTS_CATALOG);
			final SortedMap<String, String> queries = assertTwinsAnswerAlike("before the replacement");

			engine().replaceCatalog(OBJECTS_CATALOG, VIRTUAL_CATALOG);

			assertRepresentation(VIRTUAL_CATALOG, false);
			assertUnchanged("after the replacement", before, describeIndexes(engine(), VIRTUAL_CATALOG));
			assertEquals(queries, CatalogDescriber.describeQueriesOf(engine(), VIRTUAL_CATALOG));
			restart();
			assertRepresentation(VIRTUAL_CATALOG, false);
			assertUnchanged(
				"after the reload of the replaced catalog", before, describeIndexes(engine(), VIRTUAL_CATALOG)
			);
			assertEquals(queries, CatalogDescriber.describeQueriesOf(engine(), VIRTUAL_CATALOG));

			// the replaced catalog writes index objects from now on - a new single-owner partition is one
			engine().updateCatalog(VIRTUAL_CATALOG, mintingStep(ENTITY_PRODUCT));
			final EntityIndex minted = collectionOf(engine(), VIRTUAL_CATALOG, ENTITY_PRODUCT).getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, categoryKey(MINTED_CATEGORY))
			);
			assertNotNull(minted, "a catalog of index objects must write a new single-owner partition as one");
		}

		@Test
		@DisplayName("a duplicate under a new name and identifier keeps every row as a duplicate of objects does")
		void shouldKeepEveryRowOfADuplicate() {
			startWithTwins("duplicate", false);
			final SortedMap<String, String> before = describeIndexes(engine(), VIRTUAL_CATALOG);
			final SortedMap<String, String> partitions = describePartitionsByKey(engine(), VIRTUAL_CATALOG, false);
			final SortedMap<String, String> queries = assertTwinsAnswerAlike("before the duplication");

			// the catalog of index objects is duplicated too: a duplicate loads the last checkpoint of its source and
			// replays the write-ahead log on top of it, which mints the primary keys of the replayed partitions anew -
			// and the virtual duplicate must mint exactly the ones the duplicate of index objects mints
			engine().duplicateCatalog(VIRTUAL_CATALOG, PRODUCED_CATALOG);
			engine().activateCatalog(PRODUCED_CATALOG);
			engine().duplicateCatalog(OBJECTS_CATALOG, PRODUCED_OBJECTS_CATALOG);
			engine().activateCatalog(PRODUCED_OBJECTS_CATALOG);

			assertNotEquals(
				catalogOf(engine(), VIRTUAL_CATALOG).getCatalogId(),
				catalogOf(engine(), PRODUCED_CATALOG).getCatalogId(),
				"the duplicate must have been given an identifier of its own"
			);
			assertRepresentation(PRODUCED_CATALOG, true);
			assertUnchanged(
				"after the duplication", partitions, describePartitionsByKey(engine(), PRODUCED_CATALOG, false)
			);
			assertUnchanged(
				"after the duplication, the primary keys of the duplicate of index objects",
				describePartitionsByKey(engine(), PRODUCED_OBJECTS_CATALOG, true),
				describePartitionsByKey(engine(), PRODUCED_CATALOG, true)
			);
			assertUnchanged(
				"after the duplication: the query lines", queries,
				CatalogDescriber.describeQueriesOf(engine(), PRODUCED_CATALOG)
			);
			assertInvariants("after the duplication", engine(), PRODUCED_CATALOG);
			final SortedMap<String, String> duplicated = describeIndexes(engine(), PRODUCED_CATALOG);
			restart();
			assertProduced("after the reload of the duplicate", PRODUCED_CATALOG, duplicated, queries);
			assertMints("after the duplication", PRODUCED_CATALOG);
			assertUnchanged(
				"the source after a new row in its duplicate", before, describeIndexes(engine(), VIRTUAL_CATALOG)
			);
		}

		@ParameterizedTest(name = "point in time: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("a backup restored under a new name keeps every row under its primary key")
		void shouldKeepEveryRowOfARestoredBackup(boolean pointInTime) throws Exception {
			startWithTwins("restore", false);
			final SortedMap<String, String> before = describeIndexes(engine(), VIRTUAL_CATALOG);
			final SortedMap<String, String> queries = assertTwinsAnswerAlike("before the backup");

			final FileForFetch backup = pointInTime ?
				engine().management().backupCatalog(VIRTUAL_CATALOG, OffsetDateTime.now(), null, false)
					.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS) :
				engine().management().fullBackupCatalog(VIRTUAL_CATALOG)
					.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			restore(backup);

			assertNotEquals(
				catalogOf(engine(), VIRTUAL_CATALOG).getCatalogId(),
				catalogOf(engine(), PRODUCED_CATALOG).getCatalogId(),
				"the restored catalog must have been given an identifier of its own"
			);
			assertRepresentation(PRODUCED_CATALOG, true);
			assertProduced("after the restore", PRODUCED_CATALOG, before, queries);
			restart();
			assertProduced("after the reload of the restored catalog", PRODUCED_CATALOG, before, queries);
			assertMints("after the restore", PRODUCED_CATALOG);
		}

		@Test
		@DisplayName("compaction of a virtual catalog keeps every row, also when reloaded")
		void shouldKeepEveryRowThroughCompactionAndReload() {
			startWithTwins("compaction", true);
			// every round rewrites the products, superseding their bodies and the registry values of their rows
			for (int round = 1; round <= REWRITE_ROUNDS; round++) {
				final int theRound = round;
				for (final String catalogName : new String[]{VIRTUAL_CATALOG, OBJECTS_CATALOG}) {
					engine().updateCatalog(catalogName, session -> {
						rewriteProducts(session, theRound);
					});
				}
			}
			assertCompacted(VIRTUAL_CATALOG);
			final SortedMap<String, String> before = describeIndexes(engine(), VIRTUAL_CATALOG);
			final SortedMap<String, String> queries = assertTwinsAnswerAlike("after the compaction");

			restart();

			assertProduced("after the reload of the compacted catalog", VIRTUAL_CATALOG, before, queries);
			assertMints("after the compaction", VIRTUAL_CATALOG);
		}

		/**
		 * Starts the engine and builds the virtual catalog and the catalog of index objects by the same history - the
		 * fixture of single-owner partitions with the reflected reference loaded in warm-up, taken live, and the
		 * history of {@link #runHistory(Consumer)} committed in the live catalogs. A compacting engine keeps both
		 * catalogs warming up, where every session flushes.
		 *
		 * @param label      a label naming the engine's directories
		 * @param compacting TRUE to configure compaction thresholds low enough for a few rewrites to cross them
		 */
		private void startWithTwins(@Nonnull String label, boolean compacting) {
			this.paths = createTestPaths(VirtualPartitionReloadInvariantTest.class.getSimpleName() + "_" + label);
			final EvitaConfiguration.Builder builder = newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build());
			if (compacting) {
				builder.storage(
					StorageOptions.builder()
						.storageDirectory(this.paths.storage())
						.workDirectory(this.paths.work())
						.fileSizeCompactionThresholdBytes(16_384)
						.minimalActiveRecordShare(0.9)
						.minCompactionIntervalMilliseconds(0)
						.timeTravelEnabled(false)
						.build()
				);
			}
			this.configuration = builder.build();
			this.evita = new Evita(this.configuration);
			for (final String catalogName : new String[]{VIRTUAL_CATALOG, OBJECTS_CATALOG}) {
				final boolean virtual = VIRTUAL_CATALOG.equals(catalogName);
				this.evita.createCatalogWithVirtualReferencePartitions(catalogName, virtual);
				this.evita.defineCatalog(catalogName).updateViaNewSession(this.evita);
				this.evita.updateCatalog(catalogName, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
				if (compacting) {
					// the compaction is driven by the warm-up rounds, which flush on every session
					runHistory(step -> engine().updateCatalog(catalogName, step));
				} else {
					this.evita.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
					runHistory(step -> engine().updateCatalog(catalogName, step));
				}
			}
			assertRepresentation(VIRTUAL_CATALOG, true);
			assertRepresentation(OBJECTS_CATALOG, false);
			assertInvariants("after the history", engine(), VIRTUAL_CATALOG);
		}

		/**
		 * Asserts that both catalogs built by the same history answer every query line alike.
		 *
		 * @param label names the moment in the failure message
		 * @return the query lines of both catalogs
		 */
		@Nonnull
		private SortedMap<String, String> assertTwinsAnswerAlike(@Nonnull String label) {
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine(), VIRTUAL_CATALOG);
			assertUnchanged(
				label + ": the catalog of index objects answering as the virtual one",
				queries, CatalogDescriber.describeQueriesOf(engine(), OBJECTS_CATALOG)
			);
			return queries;
		}

		/**
		 * Asserts that a virtual catalog an event produced holds exactly the indexes the virtual catalog it came from
		 * held, answers every query line as the catalog of index objects does and keeps the invariants.
		 *
		 * @param label       names the moment in failure messages
		 * @param catalogName the produced catalog
		 * @param indexes     the index-level lines of the catalog it came from
		 * @param queries     the query lines of the catalog of index objects
		 */
		private void assertProduced(
			@Nonnull String label,
			@Nonnull String catalogName,
			@Nonnull SortedMap<String, String> indexes,
			@Nonnull SortedMap<String, String> queries
		) {
			assertUnchanged(label, indexes, describeIndexes(engine(), catalogName));
			assertUnchanged(
				label + ": the query lines", queries, CatalogDescriber.describeQueriesOf(engine(), catalogName)
			);
			assertInvariants(label, engine(), catalogName);
		}

		/**
		 * Asserts {@link VirtualPartitionReloadInvariantTest#assertMintsAboveEveryUsedPrimaryKey} in a virtual catalog
		 * of the engine, the reload a restart of the engine.
		 *
		 * @param label       names the event in failure messages
		 * @param catalogName the virtual catalog
		 */
		private void assertMints(@Nonnull String label, @Nonnull String catalogName) {
			VirtualPartitionReloadInvariantTest.assertMintsAboveEveryUsedPrimaryKey(
				label, this::engine, catalogName, ENTITY_PRODUCT,
				step -> engine().updateCatalog(catalogName, step),
				this::restart
			);
		}

		/**
		 * Asserts the representation of a loaded catalog: the storage protocol of its header and its switch holder.
		 *
		 * @param catalogName the catalog
		 * @param virtual     TRUE when the catalog must be virtual
		 */
		private void assertRepresentation(@Nonnull String catalogName, boolean virtual) {
			final Catalog catalog = catalogOf(engine(), catalogName);
			// through the wildcard: `Catalog` holds the service under the interface's parameterisation
			final CatalogPersistenceService<?, ?, ?> persistenceService = catalog.getPersistenceService();
			final DefaultCatalogPersistenceService defaultPersistenceService =
				(DefaultCatalogPersistenceService) persistenceService;
			assertEquals(
				virtual ?
					PersistenceService.VIRTUAL_REFERENCE_PARTITIONS_STORAGE_PROTOCOL_VERSION :
					PersistenceService.STORAGE_PROTOCOL_VERSION,
				defaultPersistenceService.getCatalogHeader(defaultPersistenceService.getLastCatalogVersion())
					.storageProtocolVersion(),
				"the storage protocol of `" + catalogName + "`"
			);
			assertEquals(
				virtual, catalog.getVirtualReferencePartitionMaintenance().isVirtual(),
				"the representation of `" + catalogName + "`"
			);
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
			final Path catalogDirectory = EvitaTestSupport.catalogDirectory(
				Objects.requireNonNull(this.paths).storage(), catalogName
			);
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

		/**
		 * Closes the engine and starts a fresh one over the same directories, told nothing about any catalog, and waits
		 * until it has loaded every catalog.
		 */
		private void restart() {
			engine().close();
			final Evita restarted = new Evita(Objects.requireNonNull(this.configuration));
			this.evita = restarted;
			restarted.waitUntilFullyInitialized();
			for (final String catalogName : restarted.getCatalogNames()) {
				await()
					.atMost(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.pollInterval(50, TimeUnit.MILLISECONDS)
					.until(() -> restarted.getCatalogInstance(catalogName).orElse(null) instanceof Catalog);
			}
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
	}

	/**
	 * Supplies the engine of a catalog, which a reload replaces.
	 */
	@FunctionalInterface
	private interface EngineAccessor {

		/**
		 * Returns the current engine.
		 *
		 * @return the engine
		 */
		@Nonnull
		Evita get();
	}

}
