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

package io.evitadb.spike;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream;

/**
 * One-time, read-only extractor that pulls a single representative product sort-attribute's
 * value -> record-id distribution out of a persisted evitaDB catalog into a neutral, branch-agnostic
 * text file consumed by a cross-branch JMH benchmark.
 *
 * It deliberately avoids bootstrapping the whole `Catalog` / `EntityIndex` machinery: it opens the
 * catalog `.collection` offset index for the `product` collection straight through the supported
 * persistence-service layer ({@link CatalogOffsetIndexStoragePartPersistenceService} to read the
 * catalog header + the product {@link EntityCollectionFileHeader}, then
 * {@link DefaultEntityCollectionPersistenceService} to enumerate every {@link SortIndexStoragePart}).
 *
 * SAFETY: it only ever touches the DISPOSABLE COPY at {@code /var/tmp/decodoma-bench/decodoma_cz};
 * the original {@code data/decodoma_cz} is never opened.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SortAnchorExtractor {
	/** Disposable copy root (NEVER the original under the repo). */
	private static final Path STORAGE_DIRECTORY = Path.of("/var/tmp/decodoma-bench");
	private static final String CATALOG_NAME = "decodoma_cz";
	private static final String ENTITY_TYPE = "product";
	private static final Path OUTPUT_FILE = Path.of("/var/tmp/decodoma-bench/sort-anchor.txt");
	/** Selection thresholds for a representative sort-only single attribute. */
	private static final int MIN_RECORDS = 5_000;
	private static final int MIN_DISTINCT = 100;
	private static final double MAX_DISTINCT_SHARE = 0.9d;

	/**
	 * Read kryo factory replicating {@code DefaultCatalogPersistenceService.VERSIONED_KRYO_FACTORY} (which is
	 * package-private). Both the catalog and the entity-collection offset indexes are read with this configurer chain;
	 * the {@link IndexStoragePartConfigurer} (seeded with the per-file key compressor) registers
	 * {@link SortIndexStoragePart} so the entries deserialize.
	 */
	private static final Function<VersionedKryoKeyInputs, VersionedKryo> KRYO_FACTORY = keyInputs ->
		VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SharedIndexStoragePartConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(keyInputs.keyCompressor()))
		);

	public static void main(String[] args) throws Exception {
		// the decodoma data files are written compressed - compression support must be enabled to read them back
		// (StorageOptions compression/CRC settings must MATCH what the DB was written with; they are not self-describing).
		final StorageOptions storageOptions = StorageOptions.builder()
			.storageDirectory(STORAGE_DIRECTORY)
			.compress(true)
			.build();
		final TransactionOptions transactionOptions = TransactionOptions.builder().build();
		final StorageSettings storageSettings = new StorageSettings(storageOptions, transactionOptions);

		final OffsetIndexRecordTypeRegistry recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
			Mockito.mock(Scheduler.class)
		);
		final CatalogOffHeapMemoryManager offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			CATALOG_NAME,
			storageSettings.transactionMemoryBufferLimitSizeBytes(),
			storageSettings.transactionMemoryRegionCount(),
			storageSettings
		);

		final Path catalogStoragePath = STORAGE_DIRECTORY.resolve(CATALOG_NAME);

		// (1) locate the most recent catalog bootstrap record (it carries the catalog version, the catalog
		// data-store file index and the file location of the catalog header within that file). The bootstrap file is
		// ALWAYS written uncompressed (the data files are compressed) - read it with the bootstrap-specific settings.
		final CatalogBootstrap bootstrap = lastBootstrap(storageSettings.modifyForBootstrapFile());
		final long catalogVersion = bootstrap.catalogVersion();
		System.out.println(
			"Catalog bootstrap: version=" + catalogVersion + " fileIndex=" + bootstrap.catalogFileIndex() +
				" timestamp=" + bootstrap.timestamp() + " location=" + bootstrap.fileLocation()
		);

		final Path catalogFilePath = catalogStoragePath.resolve(
			CatalogPersistenceService.getCatalogDataStoreFileName(
				CATALOG_NAME, bootstrap.catalogFileIndex()
			)
		);

		// (2) open the catalog offset index (read) via the supported persistence-service layer.
		final CatalogOffsetIndexStoragePartPersistenceService catalogService =
			CatalogOffsetIndexStoragePartPersistenceService.create(
				CATALOG_NAME,
				catalogFilePath,
				storageSettings,
				bootstrap,
				recordTypeRegistry,
				offHeapMemoryManager,
				observableOutputKeeper,
				KRYO_FACTORY,
				nonFlushed -> { },
				oldest -> { },
				// read-only spike: nothing is written, so there is no deferred sync to coordinate
				null
			);

		try {
			// (3) resolve the product collection reference + its on-disk header.
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader =
				catalogService.getCatalogHeader(catalogVersion);
			System.out.println("Available collections in catalog header:");
			CollectionFileReference productReference = null;
			for (final CollectionFileReference reference : catalogHeader.getEntityTypeFileIndexes()) {
				System.out.println(
					"  entityType=`" + reference.entityType() + "` pk=" + reference.entityTypePrimaryKey() +
						" fileIndex=" + reference.fileIndex()
				);
				if (reference.entityType().equalsIgnoreCase(ENTITY_TYPE)) {
					productReference = reference;
				}
			}
			if (productReference == null) {
				System.err.println("FATAL: catalog header has no `" + ENTITY_TYPE + "` collection!");
				return;
			}
			final int entityTypePrimaryKey = productReference.entityTypePrimaryKey();
			System.out.println(
				"Product collection: entityType=`" + productReference.entityType() + "` entityTypePrimaryKey=" +
					entityTypePrimaryKey + " fileIndex=" + productReference.fileIndex()
			);

			final EntityCollectionFileHeader productHeader = catalogService.getStoragePart(
				catalogVersion, entityTypePrimaryKey, EntityCollectionFileHeader.class
			);
			if (productHeader == null) {
				System.err.println("FATAL: no EntityCollectionFileHeader for product pk=" + entityTypePrimaryKey);
				return;
			}

			// (4) open the product collection offset index (read) and enumerate every SortIndexStoragePart.
			final DefaultEntityCollectionPersistenceService collectionService =
				new DefaultEntityCollectionPersistenceService(
					catalogVersion,
					CATALOG_NAME,
					catalogStoragePath,
					productHeader,
					storageSettings,
					offHeapMemoryManager,
					observableOutputKeeper,
					recordTypeRegistry,
					// read-only spike: nothing is written, so there is no deferred sync to coordinate
					null
				);
			try {
				run(collectionService);
			} finally {
				collectionService.close();
			}
		} finally {
			catalogService.close();
		}
	}

	/**
	 * Returns the most recent (last) {@link CatalogBootstrap} record. The bootstrap record stream is emitted in
	 * append order, so the live state is the last element.
	 */
	@Nonnull
	private static CatalogBootstrap lastBootstrap(
		@Nonnull StorageSettings storageSettings
	) {
		final AtomicReference<CatalogBootstrap> last = new AtomicReference<>();
		try (final Stream<CatalogBootstrap> stream = getCatalogBootstrapRecordStream(CATALOG_NAME, storageSettings)) {
			stream.forEach(last::set);
		}
		final CatalogBootstrap bootstrap = last.get();
		if (bootstrap == null) {
			throw new IllegalStateException("No catalog bootstrap record found for `" + CATALOG_NAME + "`!");
		}
		return bootstrap;
	}

	/**
	 * Core routine: inventories every product {@link SortIndexStoragePart}, picks one representative sort-only single
	 * attribute, and dumps its real value -> record-id distribution.
	 */
	private static void run(@Nonnull DefaultEntityCollectionPersistenceService collectionService) throws Exception {
		final List<SortIndexStoragePart> parts = new ArrayList<>(256);
		try (final Stream<SortIndexStoragePart> stream =
				 collectionService.getStoragePartPersistenceService().getEntryStream(SortIndexStoragePart.class)) {
			stream.forEach(parts::add);
		}
		System.out.println("\n=== INVENTORY (" + parts.size() + " SortIndexStoragePart records) ===");
		System.out.println(
			"attributeName | referenceName | comparatorBaseLength | valueRuntimeType | N(distinctValues) | R(records) | isPaged"
		);

		SortIndexStoragePart chosen = null;
		boolean chosenIsString = false;
		for (final SortIndexStoragePart part : parts) {
			final AttributeIndexKey key = part.getAttributeIndexKey();
			final int baseLength = part.getComparatorBase().length;
			final Serializable[] values = part.getSortedRecordsValues();
			final int[] records = part.getSortedRecords();
			final int n = values.length;
			final int r = records.length;
			final String runtimeType = runtimeType(part);
			System.out.println(
				key.attributeName() + " | " + key.referenceName() + " | " + baseLength + " | " + runtimeType +
					" | " + n + " | " + r + " | " + part.isPaged()
			);

			// selection: sort-only single, entity-level, real cardinality spread.
			final boolean eligible = baseLength == 1
				&& key.referenceName() == null
				&& key.locale() == null
				&& r >= MIN_RECORDS
				&& n >= MIN_DISTINCT
				&& n < MAX_DISTINCT_SHARE * r
				&& !part.isPaged();
			if (eligible) {
				final boolean isString = String.class.getName().equals(runtimeType);
				// prefer a String-valued attribute; otherwise keep the first eligible non-String fallback.
				if (chosen == null || (isString && !chosenIsString)) {
					chosen = part;
					chosenIsString = isString;
				}
			}
		}

		if (chosen == null) {
			System.err.println("\nFATAL: no eligible sort-only single entity-level attribute found matching the criteria.");
			return;
		}

		dump(chosen, chosenIsString);
	}

	/**
	 * Dumps the chosen attribute's real distribution to {@link #OUTPUT_FILE} in the neutral, branch-agnostic format.
	 */
	private static void dump(@Nonnull SortIndexStoragePart part, boolean isString) throws Exception {
		final AttributeIndexKey key = part.getAttributeIndexKey();
		final ComparatorSource base = part.getComparatorBase()[0];
		final Serializable[] values = part.getSortedRecordsValues();
		final int[] records = part.getSortedRecords();
		final Map<Serializable, Integer> cardinalities = part.getValueCardinalities();
		final int n = values.length;
		final int r = records.length;
		final String runtimeType = runtimeType(part);

		boolean hasNullValue = false;
		for (final Serializable value : values) {
			if (value == null) {
				hasNullValue = true;
				break;
			}
		}

		System.out.println(
			"\n=== CHOSEN: `" + key.attributeName() + "` (entity-level, comparatorBaseLength=1, " +
				(isString ? "String-valued" : "non-String fallback `" + runtimeType + "`") + ") ===\n" +
				"Rationale: sort-only single entity-level attribute with N=" + n + " distinct values over R=" + r +
				" records (N/R=" + String.format("%.4f", (double) n / r) + " < " + MAX_DISTINCT_SHARE +
				"), R>=" + MIN_RECORDS + ", N>=" + MIN_DISTINCT +
				(isString ? "; String preferred (exercises front-coding)." : "; no eligible String attribute, fell back.")
		);
		if (part.isPaged()) {
			System.out.println("NOTE: chosen attribute is PAGED in source.");
		}

		final Base64.Encoder b64 = Base64.getEncoder();
		final List<int[]> blocks = new ArrayList<>(n);
		final List<String> firstThreeDecoded = new ArrayList<>(3);
		long minCard = Long.MAX_VALUE;
		long maxCard = Long.MIN_VALUE;
		final long[] cardSamples = new long[n];

		Files.createDirectories(OUTPUT_FILE.getParent());
		try (final BufferedWriter w = new BufferedWriter(
			new OutputStreamWriter(Files.newOutputStream(OUTPUT_FILE), StandardCharsets.UTF_8))) {
			w.write("#attribute=" + key.attributeName());
			w.newLine();
			w.write("#referenceName=" + (key.referenceName() == null ? "null" : key.referenceName()));
			w.newLine();
			w.write("#valueType=" + runtimeType);
			w.newLine();
			w.write("#orderDirection=" + base.orderDirection());
			w.newLine();
			w.write("#orderBehaviour=" + base.orderBehaviour());
			w.newLine();
			w.write("#indexedDecimalPlaces=" + part.getIndexedDecimalPlaces());
			w.newLine();
			w.write("#distinctValues=" + n);
			w.newLine();
			w.write("#totalRecords=" + r);
			w.newLine();
			w.write("#isPagedInSource=" + part.isPaged());
			w.newLine();
			if (hasNullValue) {
				w.write("#hasNullValue=true");
				w.newLine();
			}
			w.write("#valueEncoding=base64");
			w.newLine();

			// walk sortedRecords with the per-value block length (cardinality), exactly as the serializer does.
			int offset = 0;
			for (int i = 0; i < n; i++) {
				final Serializable value = values[i];
				final int card = cardinalities.getOrDefault(value, 1);
				if (offset + card > r) {
					throw new IllegalStateException(
						"Block overrun at value index " + i + ": offset=" + offset + " card=" + card + " r=" + r
					);
				}
				final int[] block = new int[card];
				System.arraycopy(records, offset, block, 0, card);
				blocks.add(block);
				offset += card;

				cardSamples[i] = card;
				if (card < minCard) {
					minCard = card;
				}
				if (card > maxCard) {
					maxCard = card;
				}

				final String rendered = value == null ? " NULL " : value.toString();
				final String encoded = b64.encodeToString(rendered.getBytes(StandardCharsets.UTF_8));
				final StringBuilder line = new StringBuilder(encoded.length() + card * 8 + 1);
				line.append(encoded).append('\t');
				for (int j = 0; j < card; j++) {
					if (j > 0) {
						line.append(',');
					}
					line.append(block[j]);
				}
				w.write(line.toString());
				w.newLine();

				if (firstThreeDecoded.size() < 3) {
					firstThreeDecoded.add(
						"value=`" + rendered + "` card=" + card + " ids=" + blockPreview(block)
					);
				}
			}
			if (offset != r) {
				throw new IllegalStateException(
					"Block walk consumed " + offset + " ids but sortedRecords holds " + r + "!"
				);
			}
		}

		// cardinality stats (min / median / max).
		java.util.Arrays.sort(cardSamples);
		final long median = cardSamples.length == 0 ? 0 :
			cardSamples[cardSamples.length / 2];

		System.out.println("\n=== DUMP COMPLETE -> " + OUTPUT_FILE + " ===");
		System.out.println("distinctValues(N)=" + n + " totalRecords(R)=" + r);
		System.out.println(
			"per-value cardinality: min=" + minCard + " median=" + median + " max=" + maxCard
		);
		System.out.println("first 3 dumped lines (decoded):");
		for (final String decoded : firstThreeDecoded) {
			System.out.println("  " + decoded);
		}
	}

	/**
	 * Renders a short preview of a record-id block (first 5 ids).
	 */
	@Nonnull
	private static String blockPreview(@Nonnull int[] block) {
		final int limit = Math.min(5, block.length);
		final StringBuilder sb = new StringBuilder(limit * 8 + 8);
		sb.append('[');
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(block[i]);
		}
		if (block.length > limit) {
			sb.append(",...(").append(block.length).append(" total)");
		}
		sb.append(']');
		return sb.toString();
	}

	/**
	 * Resolves the fully-qualified runtime class name of the stored sort values: the class of the first non-null
	 * distinct value, falling back to the declared comparator-base type when no non-null value is present.
	 */
	@Nonnull
	private static String runtimeType(@Nonnull SortIndexStoragePart part) {
		for (final Serializable value : part.getSortedRecordsValues()) {
			if (value != null) {
				return value.getClass().getName();
			}
		}
		final ComparatorSource[] base = part.getComparatorBase();
		return base.length > 0 ? base[0].type().getName() : "<unknown>";
	}
}
