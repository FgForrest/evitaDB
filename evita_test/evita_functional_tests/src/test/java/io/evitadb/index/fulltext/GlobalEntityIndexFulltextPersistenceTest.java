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

package io.evitadb.index.fulltext;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.component.loader.LoadContext;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexRootRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fulltext indexes attached to a {@link GlobalEntityIndex}, persisted and read back the way a catalog does it: the
 * index's own flush emits the parts, a real on-disk {@link OffsetIndex} stores them through the production Kryo chain -
 * the manifest serializer included - and {@link GlobalEntityIndex#reloadPlan()} rebuilds the index from them. Covers
 * the reload of every locale, the analyzer the reload uses, the reclaim of a dropped locale, of a dropped entity index
 * and of a replaced index, and a transactional commit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(FULLTEXT)
@Tag(INDEXING)
@Tag(STORAGE)
@Tag(SERIALIZATION)
@DisplayName("Fulltext indexes attached to the global entity index")
class GlobalEntityIndexFulltextPersistenceTest {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final EntityIndexKey ENTITY_INDEX_KEY = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final Locale ENGLISH = Locale.ENGLISH;
	/** Primary keys below this bound are probed for their lengths; the fixtures stay below it. */
	private static final int LENGTH_PROBE_BOUND = 150_000;
	private static final Consumer<NonFlushedBlock> NO_OP_NON_FLUSHED_BLOCK_CALLBACK = Functions.noOpConsumer();
	private static final Consumer<Optional<OffsetDateTime>> NO_OP_OLDEST_RECORD_CALLBACK = Functions.noOpConsumer();

	/**
	 * Registry providing the real analyzers.
	 */
	private static FulltextAnalyzerRegistry registry;

	private final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
	private final StorageSettings storageSettings = new StorageSettings(
		StorageOptions.temporary(), TransactionOptions.builder().build()
	);
	private ObservableOutputKeeper observableOutputKeeper;
	private Path targetFile;

	@BeforeAll
	static void setUpRegistry() {
		registry = new FulltextAnalyzerRegistry();
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	@BeforeEach
	void setUp() throws Exception {
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		this.targetFile = Files.createTempFile("globalEntityIndexFulltextPersistence", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	/**
	 * Creates an empty global entity index.
	 *
	 * @return the index
	 */
	@Nonnull
	private static GlobalEntityIndex newGlobalIndex() {
		return new GlobalEntityIndex(ENTITY_INDEX_PK, ENTITY_TYPE, ENTITY_INDEX_KEY);
	}

	/**
	 * Fills a fulltext index with enough terms for several dictionary pages and with lengths in two blocks of the title
	 * table and one of the body table.
	 *
	 * @param index     the index
	 * @param seed      tells the terms and impacts of two fixtures apart
	 * @param termCount how many terms to add
	 */
	private static void fill(@Nonnull FulltextIndex index, int seed, int termCount) {
		final int title = index.getOrAssignFieldId("title");
		final int body = index.getOrAssignFieldId("body", 300.0);
		for (int i = 0; i < termCount; i++) {
			final String term = String.format("t%d_%04d", seed, i);
			for (int j = 0; j <= i % 4; j++) {
				index.addPosting(i % 3 == 0 ? body : title, term, j * 70_000 + i, 1 + (i * 7 + j * 31 + seed) % 255);
			}
		}
		for (int primaryKey = 1; primaryKey <= 300; primaryKey++) {
			index.addValue("title", primaryKey, "krátký titulek " + primaryKey);
		}
		for (int primaryKey = 70_001; primaryKey <= 70_050; primaryKey++) {
			index.addValue("title", primaryKey, "jiný titulek " + primaryKey);
		}
		for (int primaryKey = 1; primaryKey <= 100; primaryKey++) {
			index.addValue("body", primaryKey, "slovo " + "dlouhé ".repeat(primaryKey % 30));
		}
	}

	/**
	 * Flushes the global index the way the flush pipeline does: collects its parts, then advances its baseline.
	 *
	 * @param index the index
	 * @return the parts the flush emitted, in emission order
	 */
	@Nonnull
	private static List<StoragePart> flush(@Nonnull GlobalEntityIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.getModifiedStorageParts(trappedChanges);
		index.notifyFlushed();
		return drain(trappedChanges);
	}

	/**
	 * Lists the parts collected in the accumulator.
	 *
	 * @param trappedChanges the accumulator
	 * @return the parts in emission order
	 */
	@Nonnull
	private static List<StoragePart> drain(@Nonnull TrappedChanges trappedChanges) {
		final List<StoragePart> parts = new ArrayList<>(64);
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Picks the parts of one type.
	 *
	 * @param parts the parts
	 * @param type  the type
	 * @param <T>   the type
	 * @return the parts of the type, in order
	 */
	@Nonnull
	private static <T extends StoragePart> List<T> partsOf(@Nonnull List<StoragePart> parts, @Nonnull Class<T> type) {
		final List<T> result = new ArrayList<>(parts.size());
		for (final StoragePart part : parts) {
			if (type.isInstance(part)) {
				result.add(type.cast(part));
			}
		}
		return result;
	}

	/**
	 * Returns the only manifest among the parts.
	 *
	 * @param parts the parts
	 * @return the manifest
	 */
	@Nonnull
	private static EntityIndexStoragePart manifestOf(@Nonnull List<StoragePart> parts) {
		final List<EntityIndexStoragePart> manifests = partsOf(parts, EntityIndexStoragePart.class);
		assertEquals(1, manifests.size(), "The flush must write exactly one manifest.");
		return manifests.get(0);
	}

	/**
	 * Returns the primary keys of the fulltext parts of one locale among already written parts - the root, the
	 * dictionary pages and the length blocks.
	 *
	 * @param parts  the written parts, their primary keys assigned by the store
	 * @param locale the locale
	 * @return the type and primary key of each fulltext part of the locale
	 */
	@Nonnull
	private static List<PersistedPart> fulltextPartsOf(@Nonnull List<StoragePart> parts, @Nonnull Locale locale) {
		final List<PersistedPart> result = new ArrayList<>(parts.size());
		for (final StoragePart part : parts) {
			final Locale partLocale;
			if (part instanceof FulltextIndexStoragePart root) {
				partLocale = root.getLocale();
			} else if (part instanceof FulltextDictionaryLeafPagePart page) {
				partLocale = page.getLocale();
			} else if (part instanceof FulltextFieldLengthBlockPart block) {
				partLocale = block.getLocale();
			} else {
				continue;
			}
			if (locale.equals(partLocale)) {
				result.add(new PersistedPart(part.getClass(), part.getStoragePartPK()));
			}
		}
		return result;
	}

	/**
	 * Describes a fulltext index the way it reads: its analyzer and pivot, every field with every term, postings and
	 * impacts, and every length below {@link #LENGTH_PROBE_BOUND}.
	 *
	 * @param index the index
	 * @return one line per fact
	 */
	@Nonnull
	private static List<String> describe(@Nonnull FulltextIndex index) {
		final List<String> lines = new ArrayList<>(4_096);
		lines.add("analyzer " + index.getAnalyzerName() + " pivot " + index.getDefaultLengthPivot());
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			lines.add("field " + fieldId + " " + index.getFieldName(fieldId) + " " + index.getLengthPivot(fieldId));
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				lines.add(term + " " + Arrays.toString(postings.getArray()) + " " + Arrays.toString(impacts.toArray()));
				return true;
			});
			final FieldLengthTable lengths = index.getFieldLengths(fieldId);
			lines.add("lengths " + lengths.size());
			for (int primaryKey = 0; primaryKey < LENGTH_PROBE_BOUND; primaryKey++) {
				final int encoded = lengths.getEncoded(primaryKey);
				if (encoded != 0) {
					lines.add(primaryKey + "=" + encoded);
				}
			}
		}
		return lines;
	}

	/**
	 * Asserts two global indexes hold the same fulltext indexes.
	 *
	 * @param expected the expected index
	 * @param actual   the actual index
	 */
	private static void assertSameFulltext(@Nonnull GlobalEntityIndex expected, @Nonnull GlobalEntityIndex actual) {
		assertEquals(expected.getFulltextIndexLocales(), actual.getFulltextIndexLocales());
		for (final Locale locale : expected.getFulltextIndexLocales()) {
			assertEquals(
				describe(expected.getFulltextIndex(locale)), describe(actual.getFulltextIndex(locale)),
				"The fulltext index of `" + locale + "` must survive the round trip."
			);
		}
	}

	@Nested
	@DisplayName("Reload")
	class Reload {

		@Test
		@DisplayName("every locale is listed in the manifest and reloads through the global reload plan")
		void shouldReloadEveryLocaleThroughTheGlobalReloadPlan() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 1_000);
			fill(index.getOrCreateFulltextIndex(ENGLISH, registry.getIndexAnalyzer(ENTITY_TYPE, ENGLISH)), 2, 200);

			final List<StoragePart> parts = flush(index);
			assertEquals(
				Set.of(new FulltextIndexKey(CZECH), new FulltextIndexKey(ENGLISH)),
				manifestOf(parts).getFulltextIndexes()
			);
			final List<FulltextIndexStoragePart> roots = partsOf(parts, FulltextIndexStoragePart.class);
			assertEquals(2, roots.size(), "Each locale writes its root.");
			assertTrue(
				partsOf(parts, FulltextDictionaryLeafPagePart.class).size() >= 4,
				"The Czech dictionary must span several pages."
			);
			assertEquals(
				6, partsOf(parts, FulltextFieldLengthBlockPart.class).size(),
				"Two title blocks and one body block in each locale."
			);

			try (final Disk disk = new Disk()) {
				disk.write(parts);
				final GlobalEntityIndex reloaded = disk.reload(registry);
				assertSameFulltext(index, reloaded);
				assertTrue(flush(reloaded).isEmpty(), "An untouched reloaded index writes nothing.");
			}
		}

		@Test
		@DisplayName("a flush leaves the indexes clean: the next flush writes nothing until the next write")
		void shouldWriteNothingOnTheNextFlushWithoutAWrite() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex czech = index.getOrCreateFulltextIndex(
				CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)
			);
			fill(czech, 1, 600);

			assertFalse(flush(index).isEmpty());
			assertTrue(flush(index).isEmpty(), "Nothing was written since the last flush.");

			czech.addPosting(czech.getFieldId("title"), "novinka", 5, 100);
			final List<StoragePart> parts = flush(index);
			assertEquals(1, partsOf(parts, FulltextIndexStoragePart.class).size(), "The written index rewrites its root.");
			assertTrue(
				partsOf(parts, FulltextDictionaryLeafPagePart.class).size() <= 2,
				"Only the changed leaf is written, or the two halves it split into."
			);
			assertTrue(partsOf(parts, EntityIndexStoragePart.class).isEmpty(), "The locale set did not change.");
		}

		@Test
		@DisplayName("the reload uses the analyzer the index was built with, not the one the locale resolves to now")
		void shouldReadBackWithThePersistedAnalyzer() {
			final GlobalEntityIndex index = newGlobalIndex();
			// an index of the Czech partition built with the English chain - not what the registry resolves for `cs`
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzerByName("english")), 1, 50);

			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				final GlobalEntityIndex reloaded = disk.reload(registry);
				assertEquals("czech", registry.getIndexAnalyzer(ENTITY_TYPE, CZECH).getAnalyzerName());
				assertEquals("english", reloaded.getFulltextIndex(CZECH).getAnalyzerName());
				assertSameFulltext(index, reloaded);
			}
		}

		@Test
		@DisplayName("a manifest listing a fulltext index refuses to load without an analyzer registry")
		void shouldRefuseToLoadWithoutARegistry() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 50);

			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class, () -> disk.reload(null)
				);
				assertTrue(error.getMessage().contains("no analyzer registry"), error.getMessage());
			}
		}

		@Test
		@DisplayName("an index nothing was written to is neither persisted nor listed")
		void shouldNotPersistAnIndexNothingWasWrittenTo() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 50);
			index.getOrCreateFulltextIndex(ENGLISH, registry.getIndexAnalyzer(ENTITY_TYPE, ENGLISH));

			final List<StoragePart> parts = flush(index);
			assertEquals(Set.of(new FulltextIndexKey(CZECH)), manifestOf(parts).getFulltextIndexes());
			assertEquals(1, partsOf(parts, FulltextIndexStoragePart.class).size());

			try (final Disk disk = new Disk()) {
				disk.write(parts);
				assertEquals(Set.of(CZECH), disk.reload(registry).getFulltextIndexLocales());
			}
		}

		@Test
		@DisplayName("an existing index refuses to be obtained with another analyzer")
		void shouldRefuseAnotherAnalyzerForAnExistingIndex() {
			final GlobalEntityIndex index = newGlobalIndex();
			index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzerByName("english"))
			);
		}

	}

	@Nested
	@DisplayName("Reclaim")
	class Reclaim {

		@Test
		@DisplayName("a dropped locale removes its root, every dictionary page and every length block")
		void shouldReclaimADroppedLocale() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 600);
			fill(index.getOrCreateFulltextIndex(ENGLISH, registry.getIndexAnalyzer(ENTITY_TYPE, ENGLISH)), 2, 600);

			try (final Disk disk = new Disk()) {
				final List<StoragePart> first = flush(index);
				disk.write(first);
				final List<PersistedPart> czechParts = fulltextPartsOf(first, CZECH);
				final List<PersistedPart> englishParts = fulltextPartsOf(first, ENGLISH);

				index.removeFulltextIndex(ENGLISH);
				final List<StoragePart> second = flush(index);
				assertEquals(Set.of(new FulltextIndexKey(CZECH)), manifestOf(second).getFulltextIndexes());
				assertEquals(1, partsOf(second, FulltextIndexRootRemoval.class).size());
				assertEquals(
					englishParts.size(),
					partsOf(second, FulltextIndexRootRemoval.class).size() +
						partsOf(second, FulltextDictionaryLeafPageRemoval.class).size() +
						partsOf(second, FulltextFieldLengthBlockRemoval.class).size(),
					"Every English part is removed, and nothing else."
				);
				disk.write(second);

				final OffsetIndex store = disk.reopen();
				for (final PersistedPart part : englishParts) {
					assertNull(store.get(disk.version(), part.primaryKey(), part.type()), "Removed: " + part);
				}
				for (final PersistedPart part : czechParts) {
					assertNotNull(store.get(disk.version(), part.primaryKey(), part.type()), "Kept: " + part);
				}
				assertSameFulltext(index, disk.reload(registry));
			}
		}

		@Test
		@DisplayName("a dropped entity index removes every fulltext part of every locale")
		void shouldReclaimEverythingWhenTheEntityIndexIsDropped() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 600);
			fill(index.getOrCreateFulltextIndex(ENGLISH, registry.getIndexAnalyzer(ENTITY_TYPE, ENGLISH)), 2, 100);

			try (final Disk disk = new Disk()) {
				final List<StoragePart> first = flush(index);
				disk.write(first);
				final List<PersistedPart> written = new ArrayList<>(fulltextPartsOf(first, CZECH));
				written.addAll(fulltextPartsOf(first, ENGLISH));

				final TrappedChanges removals = new TrappedChanges();
				index.emitFootprintRemovals(removals);
				final List<StoragePart> second = drain(removals);
				assertEquals(written.size(), second.size(), "One removal per fulltext part, nothing else.");
				disk.write(second);

				final OffsetIndex store = disk.reopen();
				for (final PersistedPart part : written) {
					assertNull(store.get(disk.version(), part.primaryKey(), part.type()), "Removed: " + part);
				}
			}
		}

		@Test
		@DisplayName("a replaced index loses only the pages and blocks its replacement does not overwrite")
		void shouldReclaimOnlyWhatAReplacementDoesNotOverwrite() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 1_000);

			try (final Disk disk = new Disk()) {
				final List<StoragePart> first = flush(index);
				disk.write(first);
				final List<PersistedPart> old = fulltextPartsOf(first, CZECH);

				index.removeFulltextIndex(CZECH);
				final FulltextIndex replacement = index.getOrCreateFulltextIndex(
					CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)
				);
				for (int primaryKey = 1; primaryKey <= 5; primaryKey++) {
					replacement.addValue("title", primaryKey, "nový titulek " + primaryKey);
				}
				final List<StoragePart> second = flush(index);
				assertTrue(partsOf(second, FulltextIndexRootRemoval.class).isEmpty(), "The root is overwritten.");
				assertEquals(1, partsOf(second, FulltextDictionaryLeafPagePart.class).size());
				disk.write(second);
				final List<PersistedPart> current = fulltextPartsOf(second, CZECH);

				final OffsetIndex store = disk.reopen();
				for (final PersistedPart part : old) {
					if (!current.contains(part)) {
						assertNull(store.get(disk.version(), part.primaryKey(), part.type()), "Removed: " + part);
					}
				}
				for (final PersistedPart part : current) {
					assertNotNull(store.get(disk.version(), part.primaryKey(), part.type()), "Written: " + part);
				}
				assertTrue(old.size() > current.size() + 2, "The fixture must leave old pages and blocks behind.");
				assertSameFulltext(index, disk.reload(registry));
			}
		}

	}

	@Nested
	@DisplayName("Transactions")
	@Tag(TRANSACTION)
	class Transactions {

		@Test
		@DisplayName("a committed transaction persists its changes and new locale, and the committed copy starts clean")
		void shouldPersistACommittedTransaction() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)), 1, 1_000);

			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				final GlobalEntityIndex loaded = disk.reload(registry);
				final List<StoragePart> flushed = new ArrayList<>(64);

				assertStateAfterCommit(
					loaded,
					original -> {
						final FulltextIndex czech = original.getFulltextIndex(CZECH);
						czech.addPosting(czech.getFieldId("title"), "novinka", 5, 100);
						original.getOrCreateFulltextIndex(ENGLISH, registry.getIndexAnalyzer(ENTITY_TYPE, ENGLISH))
							.addValue("title", 1, "a brand new title");
						// the flush of a transaction runs before its commit merge
						flushed.addAll(flush(original));
					},
					(original, committed) -> {
						assertNull(original.getFulltextIndex(ENGLISH), "The original version stays as it was.");
						assertEquals(
							Set.of(new FulltextIndexKey(CZECH), new FulltextIndexKey(ENGLISH)),
							manifestOf(flushed).getFulltextIndexes()
						);
						final List<FulltextDictionaryLeafPagePart> pages =
							partsOf(flushed, FulltextDictionaryLeafPagePart.class);
						final long czechPages = pages.stream().filter(page -> CZECH.equals(page.getLocale())).count();
						assertTrue(
							czechPages == 1 || czechPages == 2,
							"Only the changed Czech leaf is written, or the two halves it split into: " + czechPages
						);
						assertEquals(1, pages.size() - czechPages, "The English dictionary is a single page.");
						assertTrue(flush(committed).isEmpty(), "The committed copy starts clean.");

						disk.write(flushed);
						assertSameFulltext(committed, disk.reload(registry));
					}
				);
			}
		}

	}

	/**
	 * A persisted part: its type and primary key.
	 *
	 * @param type       the part type
	 * @param primaryKey the primary key the store assigned
	 */
	private record PersistedPart(@Nonnull Class<? extends StoragePart> type, @Nonnull Long primaryKey) {
	}

	/**
	 * A real on-disk store across several flushes: each {@link #write(List)} applies one flush at the next catalog
	 * version - parts put, deferred removals resolved store-side and removed, as the production drain does - and
	 * {@link #reload(FulltextAnalyzerRegistry)} reopens the file and rebuilds the global index from it.
	 */
	private final class Disk implements AutoCloseable {
		@Nonnull private OffsetIndex offsetIndex = openWritableOffsetIndex();
		@Nullable private OffsetIndexDescriptor descriptor;
		private long version;

		/**
		 * Applies one flush at the next catalog version and flushes the file.
		 *
		 * @param parts the parts of the flush
		 */
		void write(@Nonnull List<StoragePart> parts) {
			this.version++;
			for (final StoragePart part : parts) {
				if (part instanceof DeferredRemovalStoragePart removal) {
					final long primaryKey = removal.computeUniquePartIdAndSet(this.offsetIndex.getReadOnlyKeyCompressor());
					this.offsetIndex.remove(this.version, primaryKey, removal.removedContainerType());
				} else {
					this.offsetIndex.put(this.version, part);
				}
			}
			this.descriptor = this.offsetIndex.flush(this.version);
		}

		/**
		 * @return the catalog version of the last write
		 */
		long version() {
			return this.version;
		}

		/**
		 * Closes the store and opens it again from the file, so every later read goes to the bytes.
		 *
		 * @return the reopened store
		 */
		@Nonnull
		OffsetIndex reopen() {
			IOUtils.closeQuietly(this.offsetIndex::close);
			this.offsetIndex = loadOffsetIndex(assertNotNullDescriptor(), this.version);
			return this.offsetIndex;
		}

		/**
		 * Reopens the store and rebuilds the global index through its reload plan, from the manifest it reads back.
		 *
		 * @param analyzerRegistry the registry the context carries, or `null` for none
		 * @return the reloaded index
		 */
		@Nonnull
		GlobalEntityIndex reload(@Nullable FulltextAnalyzerRegistry analyzerRegistry) {
			final OffsetIndexReadService service = new OffsetIndexReadService(reopen());
			final EntityIndexStoragePart manifest = service.getStoragePart(
				this.version, ENTITY_INDEX_PK, EntityIndexStoragePart.class
			);
			assertNotNull(manifest, "The manifest must be on disk.");
			final EntityIdsStoragePart entityIds = service.getStoragePart(
				this.version, ENTITY_INDEX_PK, EntityIdsStoragePart.class
			);
			final LoadContext context = new LoadContext(
				this.version, ENTITY_INDEX_PK, EntitySchema._internalBuild(ENTITY_TYPE), manifest.getEntityIndexKey(),
				manifest, manifest.getVersion(),
				entityIds == null ? EmptyBitmap.INSTANCE : entityIds.getEntityIds(),
				entityIds == null ? Map.of() : entityIds.getEntityIdsByLanguage(),
				service, null, true, analyzerRegistry
			);
			return (GlobalEntityIndex) GlobalEntityIndex.reloadPlan().run(context);
		}

		/**
		 * @return the descriptor of the last write
		 */
		@Nonnull
		private OffsetIndexDescriptor assertNotNullDescriptor() {
			assertNotNull(this.descriptor, "Nothing was written yet.");
			return this.descriptor;
		}

		@Override
		public void close() {
			IOUtils.closeQuietly(this.offsetIndex::close);
		}
	}

	@Nonnull
	private OffsetIndex openWritableOffsetIndex() {
		return new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0), createKryo(), 1.0, 0L),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordRegistry,
			createWriteHandle(),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private OffsetIndex loadOffsetIndex(@Nonnull OffsetIndexDescriptor descriptor, long catalogVersion) {
		return new OffsetIndex(
			catalogVersion,
			new OffsetIndexDescriptor(
				new FileLocationAndWrittenBytes(descriptor.fileLocation(), 0),
				descriptor,
				1.0,
				descriptor.getFileSize()
			),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordRegistry,
			createWriteHandle(),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private WriteOnlyFileHandle createWriteHandle() {
		return new WriteOnlyFileHandle(
			this.targetFile,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.syncWrites(),
			this.storageSettings,
			this.storageSettings,
			this.observableOutputKeeper
		);
	}

	/**
	 * The Kryo chain an entity collection file is written with.
	 *
	 * @return the factory of the versioned Kryo instances
	 */
	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryo() {
		return keyInputs -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SharedIndexStoragePartConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(keyInputs.keyCompressor()))
		);
	}

	/**
	 * Thin read-only {@link StoragePartPersistenceService} over a real {@link OffsetIndex}: it forwards the two methods
	 * the loaders call and fails loudly on everything else.
	 *
	 * @param offsetIndex the real store to read from
	 */
	private record OffsetIndexReadService(
		@Nonnull OffsetIndex offsetIndex
	) implements StoragePartPersistenceService<StorageDescriptor> {

		@Nullable
		@Override
		public <T extends StoragePart> T getStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			return this.offsetIndex.get(catalogVersion, storagePartPk, containerType);
		}

		@Nonnull
		@Override
		public KeyCompressor getReadOnlyKeyCompressor() {
			return this.offsetIndex.getReadOnlyKeyCompressor();
		}

		// --- the loaders never call anything below; fail loudly if that changes -------------------

		@Nonnull
		@Override
		public StoragePartPersistenceService<StorageDescriptor> createTransactionalService(@Nonnull UUID transactionId) {
			throw new UnsupportedOperationException();
		}

		@Nullable
		@Override
		public <T extends StoragePart> byte[] getStoragePartAsBinary(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean removeStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean containsStoragePart(
			long catalogVersion, long primaryKey, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int countStorageParts(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> T deserializeStoragePart(
			@Nonnull byte[] storagePart, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public KeyCompressorSnapshot getKeyCompressorSnapshot() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getVersion() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void forgetVolatileData() {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor flush(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor copySnapshotTo(
			long catalogVersion, @Nonnull OutputStream outputStream,
			@Nullable IntConsumer progressConsumer, @Nullable StoragePart... updatedStorageParts
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isNew() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isClosed() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			throw new UnsupportedOperationException();
		}
	}

}
