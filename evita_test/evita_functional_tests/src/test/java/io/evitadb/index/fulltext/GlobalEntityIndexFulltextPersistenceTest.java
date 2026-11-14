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
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.analysis.AttributeNormalizationResolver;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.component.loader.LoadContext;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.RowValues;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextReferenceValueLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextReferenceValueLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextSurfaceFormLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextSurfaceFormLeafPageRemoval;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fulltext indexes attached to a {@link GlobalEntityIndex}, persisted and read back the way a catalog does it: the
 * index's own flush emits the parts, a real on-disk {@link OffsetIndex} stores them through the production Kryo chain -
 * the manifest serializer included - and {@link GlobalEntityIndex#reloadPlan()} rebuilds the index from them. Covers
 * the reload of every locale, the analyzer the reload uses, the refusal to reload an index missing a part it lists,
 * the reclaim of a dropped locale, of a dropped entity index and of a replaced index, and a transactional commit.
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
	/** Primary keys below this bound are probed for an indexed value; the fixtures stay below it. */
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
		registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());
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
	 * table and one of the body table. Every entity holds one value per field: its own text, and the synthetic terms
	 * spread over the entities of their field, each repeated a varying number of times so the impacts differ.
	 *
	 * @param index     the index
	 * @param seed      tells the terms and impacts of two fixtures apart
	 * @param termCount how many synthetic terms to add
	 */
	private static void fill(@Nonnull FulltextIndex index, int seed, int termCount) {
		registerField(index, attribute("title"));
		registerField(index, attribute("body"), 300.0);
		final Map<Integer, StringBuilder> titles = new TreeMap<>();
		for (int primaryKey = 1; primaryKey <= 300; primaryKey++) {
			titles.put(primaryKey, new StringBuilder(64).append("krátký titulek ").append(primaryKey).append(' '));
		}
		for (int primaryKey = 70_001; primaryKey <= 70_050; primaryKey++) {
			titles.put(primaryKey, new StringBuilder(64).append("jiný titulek ").append(primaryKey).append(' '));
		}
		final Map<Integer, StringBuilder> bodies = new TreeMap<>();
		for (int primaryKey = 1; primaryKey <= 100; primaryKey++) {
			bodies.put(primaryKey, new StringBuilder(256).append("slovo ").append("dlouhé ".repeat(primaryKey % 30)));
		}
		final Integer[] titleKeys = titles.keySet().toArray(Integer[]::new);
		final Integer[] bodyKeys = bodies.keySet().toArray(Integer[]::new);
		for (int i = 0; i < termCount; i++) {
			final String term = String.format("t%d_%04d", seed, i);
			// one to four entities each, never the same one twice
			for (int j = 0; j <= i % 4; j++) {
				final String occurrences = (term + ' ').repeat(1 + (i * 7 + j * 31 + seed) % 5);
				if (i % 3 == 0) {
					bodies.get(bodyKeys[(i + j * 25) % bodyKeys.length]).append(occurrences);
				} else {
					titles.get(titleKeys[(i + j * 87) % titleKeys.length]).append(occurrences);
				}
			}
		}
		for (final Map.Entry<Integer, StringBuilder> value : titles.entrySet()) {
			index.addValue(attribute("title"), value.getKey(), testAnalyzer(), fieldPivot(index, attribute("title")),
				null, value.getValue().toString());
		}
		for (final Map.Entry<Integer, StringBuilder> value : bodies.entrySet()) {
			index.addValue(attribute("body"), value.getKey(), testAnalyzer(), fieldPivot(index, attribute("body")),
				null, value.getValue().toString());
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
			} else if (part instanceof FulltextSurfaceFormLeafPagePart page) {
				partLocale = (Locale) io.evitadb.index.IndexHeapSizeAssertions.readField(page, "locale");
			} else if (part instanceof FulltextReferenceValueLeafPagePart page) {
				partLocale = (Locale) io.evitadb.index.IndexHeapSizeAssertions.readField(page, "locale");
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
	 * Describes a fulltext index the way it reads: each field's analysis identities and pivot, every term, postings and
	 * impacts, and every entity below {@link #LENGTH_PROBE_BOUND} with a value indexed.
	 *
	 * @param index the index
	 * @return one line per fact
	 */
	@Nonnull
	private static List<String> describe(@Nonnull FulltextIndex index) {
		final List<String> lines = new ArrayList<>(4_096);

		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			lines.add(
				"field " + fieldId + " " + index.getFieldKey(fieldId) + " " + index.getLengthPivot(fieldId) +
					" " + index.getAnalyzerName(fieldId) + " " + index.getIndexFingerprint(fieldId) +
					" " + index.getWordPhaseId(fieldId) +
					(index.isFieldRetired(fieldId) ? " retired" : "")
			);
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				lines.add(term + " " + Arrays.toString(postings.getArray()) + " " + Arrays.toString(impacts.toArray()));
				return true;
			});
			for (int primaryKey = 0; primaryKey < LENGTH_PROBE_BOUND; primaryKey++) {
				if (index.hasIndexedValue(fieldId, primaryKey)) {
					lines.add(primaryKey + " indexed");
				}
			}
		}
		lines.add("phases " + new TreeMap<>(index.getWordPhaseIds()));
		for (final long phase : new TreeMap<>(index.getWordPhaseIds()).keySet()) {
			lines.add("references " + surfaceReferenceCounts(index, phase));
			final FulltextIndex.SurfaceFormCursor cursor = index.surfaceFormCursor(phase, "");
			while (cursor.next()) {
				lines.add(foldedAndStem(cursor));
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

		/** Distinct word-phase ids survive reload and an emptied retired phase releases its short id. */
		@Test
		@DisplayName("should reload multiple surface word phases and free an unused phase")
		void shouldReloadSurfacePhases() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex fulltext = index.getOrCreateFulltextIndex(CZECH);
			fulltext.addValue(attribute("description"), 1, testAnalyzer(), 25, null, "černé");
			fulltext.addValue(attribute("brand"), 1, exactAnalyzer(), 25, null, "Cern café");
			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				final GlobalEntityIndex loaded = disk.reload(registry);
				assertSameFulltext(index, loaded);
				final FulltextIndex restored = loaded.getFulltextIndex(CZECH);
				assertEquals(2, restored.getWordPhaseIds().size());
				assertTrue(flush(loaded).isEmpty(), "An untouched reloaded index writes nothing.");
				// Reusing the folded/stem pair must preserve the first, accented display after reconstruction.
				restored.addValue(attribute("description"), 2, testAnalyzer(), 25, null, "cerne");
				final List<StoragePart> changed = flush(loaded);
				assertEquals(List.of("cerne/cern/černé"), persistedSurfaceEntries(
					partsOf(changed, FulltextSurfaceFormLeafPagePart.class).stream()
						.map(FulltextSurfaceFormLeafPagePart::getPage).toList(),
					restored.getWordPhaseIds().get(testAnalyzer().fingerprint().wordPhase())),
					"The reconstructed global index must retain the original accented display spelling.");
				disk.write(changed);
				restored.removeValue(attribute("brand"), 1, exactAnalyzer(), 25, "Cern café");
				restored.retireField(attribute("brand"));
				assertEquals(Map.of(testAnalyzer().fingerprint().wordPhase(), 0), restored.getWordPhaseIds());
				disk.write(flush(loaded));
				assertSameFulltext(loaded, disk.reload(registry));
			}
		}

		@Test
		@DisplayName("every locale is listed in the manifest and reloads through the global reload plan")
		void shouldReloadEveryLocaleThroughTheGlobalReloadPlan() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 1_000);
			fill(index.getOrCreateFulltextIndex(ENGLISH), 2, 200);

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
			assertTrue(partsOf(parts, FulltextSurfaceFormLeafPagePart.class).size() >= 4,
				"The surface dictionary must also span several pages.");
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
			final FulltextIndex czech = index.getOrCreateFulltextIndex(CZECH);
			fill(czech, 1, 600);

			assertFalse(flush(index).isEmpty());
			assertTrue(flush(index).isEmpty(), "Nothing was written since the last flush.");

			// an entity without a title value yet
			czech.addValue(attribute("title"), 500, testAnalyzer(), fieldPivot(czech, attribute("title")), null,
				"novinka");
			final List<StoragePart> parts = flush(index);
			assertEquals(1, partsOf(parts, FulltextIndexStoragePart.class).size(), "The written index rewrites its root.");
			assertTrue(
				partsOf(parts, FulltextDictionaryLeafPagePart.class).size() <= 2,
				"Only the changed leaf is written, or the two halves it split into."
			);
			assertTrue(partsOf(parts, EntityIndexStoragePart.class).isEmpty(), "The locale set did not change.");
		}

		@Test
		@DisplayName("a retirement alone rewrites only the root; the retired field reloads retired, postings and all")
		void shouldReloadARetiredField() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex czech = index.getOrCreateFulltextIndex(CZECH);
			fill(czech, 1, 600);

			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				assertTrue(czech.retireField(attribute("title")));
				final List<StoragePart> retirement = flush(index);
				final List<FulltextIndexStoragePart> roots = partsOf(retirement, FulltextIndexStoragePart.class);
				assertEquals(1, roots.size(), "The retirement changes the field registry the root carries.");
				assertTrue(roots.get(0).getFields()[0].retired());
				assertFalse(roots.get(0).getFields()[1].retired());
				assertEquals(1, retirement.size(), "No posting and no length moved.");
				disk.write(retirement);

				czech.addValue(attribute("title"), 7, testAnalyzer(), fieldPivot(czech, attribute("title")), null,
					"nový titulek");
				disk.write(flush(index));
				final GlobalEntityIndex reloaded = disk.reload(registry);
				assertSameFulltext(index, reloaded);
				final FulltextIndex reloadedCzech = reloaded.getFulltextIndex(CZECH);
				assertTrue(reloadedCzech.isFieldRetired(0));
				assertEquals(2, reloadedCzech.getFieldId(attribute("title")));
				assertEquals(1, reloadedCzech.getFieldId(attribute("body")));
			}
		}

		@Test
		@DisplayName("the reload preserves the identity of the chain each field was built with")
		void shouldReadBackWithThePersistedFieldAnalysis() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex czech = index.getOrCreateFulltextIndex(CZECH);
			// Deliberately build one field in the Czech partition with an English chain. Loading must only restore
			// the recorded identity; the locale's current definition cannot rewrite it.
			final CompiledFulltextAnalyzer english =
				registry.getCompiledAnalyzer(SCHEMA, "text", ENGLISH);
			final CompiledFulltextAnalyzer html =
				registry.getCompiledAnalyzer(SCHEMA, "html-text", CZECH);
			czech.addValue(attribute("title"), 1, english, 50.0, null, "black tea");
			czech.addValue(attribute("body"), 1, html, 300.0, null, "<b>čaj</b>");
			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				final GlobalEntityIndex reloaded = disk.reload(registry);
				final FulltextIndex restored = reloaded.getFulltextIndex(CZECH);
				assertEquals("text", restored.getAnalyzerName(0));
				assertEquals("html-text", restored.getAnalyzerName(1));
				assertEquals(english.fingerprint().index(), restored.getIndexFingerprint(0));
				assertEquals(html.fingerprint().wordPhase(), restored.getWordPhaseId(1));
				assertNotEquals(testAnalyzer().fingerprint().index(), restored.getIndexFingerprint(0));
				assertSameFulltext(index, reloaded);
			}
		}

		@Test
		@DisplayName("an index nothing was written to is neither persisted nor listed")
		void shouldNotPersistAnIndexNothingWasWrittenTo() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 50);
			index.getOrCreateFulltextIndex(ENGLISH);

			final List<StoragePart> parts = flush(index);
			assertEquals(Set.of(new FulltextIndexKey(CZECH)), manifestOf(parts).getFulltextIndexes());
			assertEquals(1, partsOf(parts, FulltextIndexStoragePart.class).size());

			try (final Disk disk = new Disk()) {
				disk.write(parts);
				assertEquals(Set.of(CZECH), disk.reload(registry).getFulltextIndexLocales());
			}
		}

		@Test
		@DisplayName("a baseline captured before a new index's first flush leaves that flush to list the index")
		void shouldListANewIndexWhenTheBaselineWasCapturedBeforeItsFirstFlush() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 50);

			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				index.getOrCreateFulltextIndex(ENGLISH)
					.addValue(attribute("title"), 1, testAnalyzer(), 25.0, null, "a brand new title");
				// captured while the new index is dirty with nothing on disk, as building an entity index over it
				// does - were the capture to announce it, the flush would find the manifest unchanged and the
				// written root would stay unlisted
				index.notifyFlushed();

				final List<StoragePart> parts = flush(index);
				assertEquals(
					Set.of(new FulltextIndexKey(CZECH), new FulltextIndexKey(ENGLISH)),
					manifestOf(parts).getFulltextIndexes()
				);
				disk.write(parts);
				assertSameFulltext(index, disk.reload(registry));
			}
		}

		@Test
		@DisplayName("writes to one locale index use the analyzer supplied for each field")
		void shouldAnalyzeEachWriteWithThePassedChain() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex czech = index.getOrCreateFulltextIndex(CZECH);

			// the locale resolves to another analyzer now, yet the existing index is returned, with its own analyzer
			final FulltextIndex obtained = index.getOrCreateFulltextIndex(CZECH);
			assertSame(czech, obtained);


			// a value written through it gets the terms of a Czech-built index, not those of an English-built one ...
			final String value = "černé čaje";
			obtained.addValue(attribute("title"), 1, testAnalyzer(), fieldPivot(obtained, attribute("title")),
				null, value);
			final FulltextIndex czechReference = new FulltextIndex();
			czechReference.addValue(attribute("title"), 1, testAnalyzer(), fieldPivot(czechReference,
				attribute("title")), null, value);
			final FulltextIndex englishReference = new FulltextIndex();
			englishReference.addValue(attribute("title"), 1, registry.getCompiledAnalyzer(SCHEMA, "text", ENGLISH),
				fieldPivot(englishReference, attribute("title")), null, value);
			assertNotEquals(
				titleTermsOf(czechReference), titleTermsOf(englishReference), "the value must tell the analyzers apart"
			);
			assertEquals(titleTermsOf(czechReference), titleTermsOf(obtained));

			// ... and removing it finds every posting the write produced
			obtained.removeValue(attribute("title"), 1, testAnalyzer(), fieldPivot(obtained, attribute("title")),
				value);
			assertTrue(titleTermsOf(obtained).isEmpty());
		}

		/**
		 * Returns the terms the `title` field holds in the index, in dictionary order.
		 */
		@Nonnull
		private static List<String> titleTermsOf(@Nonnull FulltextIndex index) {
			final List<String> terms = new ArrayList<>(8);
			index.forEachTerm(index.getFieldId(attribute("title")), "", (term, postings, impacts) -> terms.add(term));
			return terms;
		}

		@Test
		@DisplayName("a field without lengths ahead of one with lengths reloads, each with its own table")
		void shouldReloadAFieldWithoutLengthsBesideAFieldWithLengths() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex czech = index.getOrCreateFulltextIndex(CZECH);
			// field 0 is registered without a value, so the length stream is first named by field 1
			registerField(czech, attribute("code"));
			czech.addValue(attribute("title"), 1, testAnalyzer(), fieldPivot(czech, attribute("title")), null,
				"zelený čaj");
			czech.addValue(attribute("title"), 70_001, testAnalyzer(), fieldPivot(czech, attribute("title")), null,
				"černý čaj");

			final List<StoragePart> parts = flush(index);
			final FulltextIndexStoragePart root = partsOf(parts, FulltextIndexStoragePart.class).get(0);
			assertEquals(
				0, root.getFields()[0].lengthBlocks().length, "The field without a value has no length block."
			);
			assertEquals(2, root.getFields()[1].lengthBlocks().length);

			try (final Disk disk = new Disk()) {
				disk.write(parts);
				assertSameFulltext(index, disk.reload(registry));
			}
		}

		@Test
		@DisplayName("an index whose fields have no lengths at all reloads without a length stream")
		void shouldReloadAnIndexWithoutAnyLengths() {
			final GlobalEntityIndex index = newGlobalIndex();
			final FulltextIndex czech = index.getOrCreateFulltextIndex(CZECH);
			registerField(czech, attribute("code"));
			// a value without a token is indexed as nothing - no posting, and no length
			czech.addValue(attribute("code"), 1, testAnalyzer(), fieldPivot(czech, attribute("code")), null, "!!!");

			final List<StoragePart> parts = flush(index);
			assertTrue(
				partsOf(parts, FulltextFieldLengthBlockPart.class).isEmpty(),
				"No block was written, so the length stream key was never registered."
			);

			try (final Disk disk = new Disk()) {
				disk.write(parts);
				assertSameFulltext(index, disk.reload(registry));
			}
		}

		@Test
		@DisplayName("a reload missing the root of a listed index refuses to load")
		void shouldRefuseAReloadMissingTheRoot() {
			assertReloadRefusedWithout(
				parts -> partsOf(parts, FulltextIndexStoragePart.class).get(0),
				root -> "Fulltext index of entity index `" + ENTITY_INDEX_PK + "` and locale `cs` was not found"
			);
		}

		@Test
		@DisplayName("a reload missing a listed dictionary page refuses to load")
		void shouldRefuseAReloadMissingADictionaryPage() {
			assertReloadRefusedWithout(
				parts -> partsOf(parts, FulltextDictionaryLeafPagePart.class).get(1),
				page -> "Dictionary page " + ((FulltextDictionaryLeafPagePart) page).getPageSequence() + " of"
			);
		}

		/** A listed surface page is required just as a postings page is. */
		@Test
		@DisplayName("a reload missing a listed surface page refuses to load")
		void shouldRefuseAReloadMissingASurfacePage() {
			assertReloadRefusedWithout(
				parts -> partsOf(parts, FulltextSurfaceFormLeafPagePart.class).get(1),
				page -> "Surface dictionary page " + ((FulltextSurfaceFormLeafPagePart) page).getPageSequence() + " of"
			);
		}

		@Test
		@DisplayName("a reload missing a listed length block refuses to load")
		void shouldRefuseAReloadMissingALengthBlock() {
			assertReloadRefusedWithout(
				parts -> {
					for (final FulltextFieldLengthBlockPart block : partsOf(parts, FulltextFieldLengthBlockPart.class)) {
						// the second block of the title table - the one holding primary keys from 65,536 on
						if (block.getFieldId() == 0 && block.getBlock().blockKey() == 1) {
							return block;
						}
					}
					throw new AssertionError("The title table must have a block of key 1.");
				},
				block -> "Length block 1 of field "
			);
		}

		/**
		 * Flushes a filled Czech index, writes every part of the flush except the one picked, and asserts that the reload
		 * refuses to load the index rather than restoring it empty or partial.
		 *
		 * @param omitted         picks the part left out of the write
		 * @param expectedMessage the fragment the refusal message must contain, given the omitted part
		 */
		private void assertReloadRefusedWithout(
			@Nonnull Function<List<StoragePart>, StoragePart> omitted,
			@Nonnull Function<StoragePart, String> expectedMessage
		) {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 1_000);
			final List<StoragePart> parts = flush(index);
			final StoragePart omittedPart = omitted.apply(parts);
			final List<StoragePart> written = new ArrayList<>(parts.size());
			for (final StoragePart part : parts) {
				if (part != omittedPart) {
					written.add(part);
				}
			}
			assertEquals(parts.size() - 1, written.size(), "Exactly one part is left out.");

			try (final Disk disk = new Disk()) {
				disk.write(written);
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class, () -> disk.reload(registry)
				);
				assertTrue(
					error.getMessage().contains(expectedMessage.apply(omittedPart)),
					"The refusal must name the missing part: " + error.getMessage()
				);
			}
		}

	}

	@Nested
	@DisplayName("Reference value child persistence")
	class ReferenceValues {
		/** Scalar, singleton, small-set and bitmap payloads all use the registered Kryo format. */
		@Test
		@DisplayName("should reload reference bucket payloads across their adaptive tiers")
		void shouldReloadReferenceBucketTiers() {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final int field = registerExactField(index, FulltextFieldKey.referenceAttribute("category", "label"), 25.0);
			for (int owner = 1; owner <= 96; owner++) {
				final Set<String> values = owner > 93 ? Set.of("shared", "red " + owner, "rare") :
					Set.of("shared", "red " + owner);
				index.reconcileReferenceValues(field, owner, List.of(),
					List.of(new RowValues(10, values), new RowValues(20 + owner, Set.of("shared"))),
					true, exactAnalyzer());
				index.reconcileReferenceValues(field, 100 + owner, List.of(),
					List.of(new RowValues(10, Set.of())), true, exactAnalyzer());
			}
			try (final Disk disk = new Disk()) {
				disk.write(flush(global));
				final ReferenceFulltextValueIndex restored = disk.reload(registry).getFulltextIndex(ENGLISH)
					.getReferenceValues(field);
				assertEquals(96, restored.terms.getRecordsEqualTo("red").size());
				final int shared = (int) restored.values.getLongRecordEqualTo("shared").orElseThrow();
				assertEquals(97, restored.valueTargets.getRecordsEqualTo(shared).size());
				assertEquals(98, restored.targetValues.getRecordsEqualTo(10).size());
				final ReferenceFulltextValueIndex.ValueMatch common = restored.lookup(Set.of("shared")).stream()
					.filter(match -> match.targetPK() == 10).findFirst().orElseThrow();
				assertEquals(ReferenceFulltextValueIndex.MembershipForm.EXCEPT, common.form());
				assertEquals(96, common.owners().size());
				assertArrayEquals(new int[]{1, 96, 101, 196},
					restored.getEnrolledOwners(new BaseBitmap(1, 96, 101, 196)).getArray());
				assertFalse(common.owners().contains(1));
				assertFalse(common.owners().contains(96));
				assertTrue(common.owners().contains(101));
				assertTrue(common.owners().contains(196));
				final ReferenceFulltextValueIndex.ValueMatch rare = restored.lookup(Set.of("rare")).getFirst();
				assertArrayEquals(new int[]{94, 95, 96}, rare.owners().getArray());
				assertEquals(ReferenceFulltextValueIndex.MembershipForm.HOLDERS, rare.form());
			}
		}

		/** Replacements overwrite matching stream sequences and reclaim every remaining old generation page. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("should reclaim reference pages on whole-index removal and locale replacement")
		void shouldReclaimReferenceFootprints(boolean replace) {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final FulltextFieldKey key = FulltextFieldKey.referenceAttribute("category", "label");
			final int field = registerExactField(index, key, 25.0);
			for (int number = 1; number <= 300; number++) {
				referencePair(index, field, number, true);
			}
			index.retireField(key);
			final int current = registerExactField(index, key, 50.0);
			referencePair(index, current, 301, true);
			try (final Disk disk = new Disk()) {
				final List<StoragePart> first = flush(global);
				disk.write(first);
				final List<PersistedPart> old = fulltextPartsOf(first, ENGLISH);
				final List<StoragePart> second;
				if (replace) {
					global.removeFulltextIndex(ENGLISH);
					final FulltextIndex replacement = global.getOrCreateFulltextIndex(ENGLISH);
					final int replacementField = registerExactField(replacement, key, 25.0);
					referencePair(replacement, replacementField, 1, true);
					second = flush(global);
				} else {
					final TrappedChanges removals = new TrappedChanges();
					global.emitFootprintRemovals(removals);
					second = drain(removals);
				}
				assertFalse(partsOf(second, FulltextReferenceValueLeafPageRemoval.class).isEmpty());
				disk.write(second);
				final List<PersistedPart> retained = fulltextPartsOf(second, ENGLISH);
				final OffsetIndex store = disk.reopen();
				for (final PersistedPart part : old) {
					if (!retained.contains(part)) {
						assertNull(store.get(disk.version(), part.primaryKey(), part.type()), "Removed: " + part);
					}
				}
				if (replace) {
					final FulltextIndex restored = disk.reload(registry).getFulltextIndex(ENGLISH);
					assertEquals(1, restored.getFieldCount());
					assertEquals(1, restored.getReferenceValues(0).values.size());
					assertTrue(flush(global).isEmpty());
				}
			}
		}

		/** Every child splits and later frees leaves; real bytes retain allocator holes and mutation state. */
		@Test
		@DisplayName("should page every reference child and reload after consecutive warm-up merges")
		void shouldPageEveryReferenceChildAndReload() {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final int field = registerExactField(index, FulltextFieldKey.referenceAttribute("category", "label"), 25.0);
			for (int i = 1; i <= 600; i++) {
				referencePair(index, field, i, true);
			}
			try (final Disk disk = new Disk()) {
				final List<StoragePart> first = flush(global);
				assertEquals(Set.of(new FulltextIndexKey(ENGLISH)), manifestOf(first).getFulltextIndexes());
				for (final ChildKind kind : ChildKind.values()) {
					assertTrue(partsOf(first, FulltextReferenceValueLeafPagePart.class).stream()
						.filter(page -> page.getPage().kind() == kind).count() > 1, "Split child: " + kind);
				}
				disk.write(first);
				assertTrue(flush(global).isEmpty());
				for (int i = 101; i <= 600; i++) {
					referencePair(index, field, i, false);
				}
				final List<StoragePart> second = flush(global);
				assertFalse(partsOf(second, FulltextReferenceValueLeafPageRemoval.class).isEmpty());
				assertTrue(partsOf(second, FulltextDictionaryLeafPagePart.class).isEmpty(),
					"Side-only changes do not rewrite union postings.");
				disk.write(second);
				final GlobalEntityIndex reloaded = disk.reload(registry);
				final FulltextIndex restored = reloaded.getFulltextIndex(ENGLISH);
				final ReferenceFulltextValueIndex values = restored.getReferenceValues(field);
				assertEquals(601, values.allocator.getNextValueId());
				assertEquals(100, values.values.size());
				assertEquals(ReferenceFulltextValueIndex.MembershipForm.EXCEPT,
					values.lookup(Set.of("value1")).getFirst().form());
				assertArrayEquals(new int[]{65_537, 65_538},
					values.getEnrolledOwners(new BaseBitmap(65_537, 65_538)).getArray());
				assertFalse(values.lookup(Set.of("value1")).getFirst().owners().contains(65_537));
				assertTrue(values.lookup(Set.of("value1")).getFirst().owners().contains(65_538));
				assertTrue(flush(reloaded).isEmpty(), "Cold-loaded children start clean.");
				referencePair(restored, field, 601, true);
				assertEquals(601L, values.values.getLongRecordEqualTo("value601").orElseThrow());
				disk.write(flush(reloaded));
				assertEquals(602, disk.reload(registry).getFulltextIndex(ENGLISH)
					.getReferenceValues(field).allocator.getNextValueId());
			}
		}

		/** Missing root-listed child pages are errors, even when the top-level union has no postings. */
		@Test
		@DisplayName("should refuse a missing reference child page")
		void shouldRefuseMissingReferencePage() {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final int field = registerExactField(index, FulltextFieldKey.referenceAttribute("category", "label"), 25.0);
			for (int number = 1; number <= 300; number++) {
				referencePair(index, field, number, true);
			}
			final List<StoragePart> parts = flush(global);
			final StoragePart missing = partsOf(parts, FulltextReferenceValueLeafPagePart.class).getFirst();
			parts.remove(missing);
			try (final Disk disk = new Disk()) {
				disk.write(parts);
				assertThrows(GenericEvitaInternalError.class, () -> disk.reload(registry));
			}
		}

		/** A page addressed by the expected stream must still identify the correct field in its payload. */
		@Test
		@DisplayName("should reject a reference page associated with the wrong field")
		void shouldRejectWrongReferenceFieldAssociation() {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final int field = registerExactField(index, FulltextFieldKey.referenceAttribute("category", "label"), 25.0);
			referencePair(index, field, 1, true);
			final List<StoragePart> first = flush(global);
			try (final Disk disk = new Disk()) {
				disk.write(first);
				final FulltextReferenceValueLeafPagePart page =
					partsOf(first, FulltextReferenceValueLeafPagePart.class).getFirst();
				disk.write(List.of(
					new FulltextReferenceValueLeafPagePart(page.getStreamId(), field + 1, page.getPage())));
				final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
					() -> disk.reload(registry));
				assertTrue(error.getMessage().contains("field/child manifest"));
			}
		}

		/** Invalid ids/counts/tags must fail at load after passing through real Kryo bytes. */
		@ParameterizedTest
		@EnumSource(value = ChildKind.class, names = {"RAW_VALUE_IDS", "VALUE_ROW_COUNTS", "OWNER_FORMS"})
		@DisplayName("should reject malformed persisted reference ids, counts and forms")
		void shouldRejectMalformedReferenceState(ChildKind corruptedKind) {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final int field = registerExactField(index, FulltextFieldKey.referenceAttribute("category", "label"), 25.0);
			referencePair(index, field, 1, true);
			final List<StoragePart> parts = flush(global);
			final FulltextReferenceValueLeafPagePart old = partsOf(parts, FulltextReferenceValueLeafPagePart.class)
				.stream().filter(page -> page.getPage().kind() == corruptedKind).findFirst().orElseThrow();
			final ReferenceFulltextValueIndex.ValuePage page = old.getPage();
			final long[] invalid = page.scalars().clone();
			invalid[0] = 999L;
			parts.set(parts.indexOf(old), new FulltextReferenceValueLeafPagePart(ENTITY_INDEX_PK, ENGLISH, field,
				new ReferenceFulltextValueIndex.ValuePage(page.pageSequence(), page.kind(), page.keys(),
					invalid, page.records())));
			try (final Disk disk = new Disk()) {
				disk.write(parts);
				assertThrows(GenericEvitaInternalError.class, () -> disk.reload(registry));
			}
		}

		/** Retired generations remain persisted, and dropping the locale reclaims them as well as the current one. */
		@Test
		@DisplayName("should retain retired reference footprints until their locale is dropped")
		void shouldRetainRetiredReferenceFootprints() {
			final GlobalEntityIndex global = newGlobalIndex();
			final FulltextIndex index = global.getOrCreateFulltextIndex(ENGLISH);
			final FulltextFieldKey key = FulltextFieldKey.referenceAttribute("category", "label");
			final int first = registerExactField(index, key, 25.0);
			referencePair(index, first, 1, true);
			try (final Disk disk = new Disk()) {
				disk.write(flush(global));
				index.retireField(key);
				final int current = registerExactField(index, key, 50.0);
				referencePair(index, current, 2, true);
				disk.write(flush(global));
				final GlobalEntityIndex reloaded = disk.reload(registry);
				assertTrue(reloaded.getFulltextIndex(ENGLISH).isFieldRetired(first));
				assertEquals(1, reloaded.getFulltextIndex(ENGLISH).getReferenceValues(first).values.size());
				reloaded.removeFulltextIndex(ENGLISH);
				final List<StoragePart> dropped = flush(reloaded);
				assertEquals(2 * ChildKind.values().length,
					partsOf(dropped, FulltextReferenceValueLeafPageRemoval.class).size());
				disk.write(dropped);
				assertNull(disk.reload(registry).getFulltextIndex(ENGLISH));
			}
		}

		/** Batches two real owners into one target with one holder and one bare row. */
		private void referencePair(FulltextIndex index, int field, int number, boolean insert) {
			final List<RowValues> holder = List.of(new RowValues(number, Set.of("value" + number)));
			final List<RowValues> bare = List.of(new RowValues(number, Set.of()));
			index.reconcileReferenceValues(field, number * 65_536 + 1,
				insert ? List.of() : holder, insert ? holder : List.of(), insert, exactAnalyzer());
			index.reconcileReferenceValues(field, number * 65_536 + 2,
				insert ? List.of() : bare, insert ? bare : List.of(), insert, exactAnalyzer());
		}
	}

	@Nested
	@DisplayName("Reclaim")
	class Reclaim {

		@Test
		@DisplayName("a dropped locale removes its root, every dictionary page and every length block")
		void shouldReclaimADroppedLocale() {
			final GlobalEntityIndex index = newGlobalIndex();
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 600);
			fill(index.getOrCreateFulltextIndex(ENGLISH), 2, 600);

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
						partsOf(second, FulltextSurfaceFormLeafPageRemoval.class).size() +
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
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 600);
			fill(index.getOrCreateFulltextIndex(ENGLISH), 2, 100);

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
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 1_000);

			try (final Disk disk = new Disk()) {
				final List<StoragePart> first = flush(index);
				disk.write(first);
				final List<PersistedPart> old = fulltextPartsOf(first, CZECH);

				index.removeFulltextIndex(CZECH);
				final FulltextIndex replacement = index.getOrCreateFulltextIndex(CZECH);
				for (int primaryKey = 1; primaryKey <= 5; primaryKey++) {
					replacement.addValue(attribute("title"), primaryKey, testAnalyzer(), fieldPivot(replacement,
						attribute("title")), null, "nový titulek " + primaryKey);
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
			fill(index.getOrCreateFulltextIndex(CZECH), 1, 1_000);

			try (final Disk disk = new Disk()) {
				disk.write(flush(index));
				final GlobalEntityIndex loaded = disk.reload(registry);
				final List<StoragePart> flushed = new ArrayList<>(64);

				assertStateAfterCommit(
					loaded,
					original -> {
						final FulltextIndex czech = original.getFulltextIndex(CZECH);
						// an entity without a title value yet
						czech.addValue(attribute("title"), 500, testAnalyzer(), fieldPivot(czech, attribute("title")),
							null, "novinka");
						original.getOrCreateFulltextIndex(ENGLISH)
							.addValue(attribute("title"), 1, testAnalyzer(), 25.0, null, "a brand new title");
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

		@Test
		@DisplayName("a commit carries an unflushed index forward, and the next flush writes and lists it")
		void shouldCarryAnUnflushedIndexForwardToTheNextFlush() {
			final GlobalEntityIndex index = newGlobalIndex();
			// written outside a transaction, as warm-up writes, and not flushed
			index.getOrCreateFulltextIndex(CZECH)
				.addValue(attribute("title"), 1, testAnalyzer(), 25.0, null, "rozepsaný titulek");

			try (final Disk disk = new Disk()) {
				// the transaction writes the global index elsewhere, so the commit merge builds a copy carrying the
				// untouched fulltext index forward - and capturing the copy's baseline must not consume its changes
				assertStateAfterCommit(
					index,
					original -> original.insertPrimaryKeyIfMissing(1),
					(original, committed) -> {
						assertNotNull(committed);
						assertEquals(
							termCount(index.getFulltextIndex(CZECH)),
							termCount(committed.getFulltextIndex(CZECH)),
							"The unflushed index is carried forward."
						);

						final List<StoragePart> parts = flush(committed);
						assertEquals(
							Set.of(new FulltextIndexKey(CZECH)), manifestOf(parts).getFulltextIndexes(),
							"The manifest lists the index its first flush writes."
						);
						assertEquals(
							1, partsOf(parts, FulltextIndexStoragePart.class).size(),
							"The carried index writes its root."
						);
						assertFalse(
							partsOf(parts, FulltextDictionaryLeafPagePart.class).isEmpty(),
							"The carried index writes its dictionary."
						);

						disk.write(parts);
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
		/** Expected surface payloads retained across flushes; clean pages are never requested from emissions again. */
		private final Map<Long, String[]> surfaceKeys = new TreeMap<>();

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
					if (removal.removedContainerType() == FulltextSurfaceFormLeafPagePart.class) {
						this.surfaceKeys.remove(primaryKey);
					}
				} else {
					this.offsetIndex.put(this.version, part);
					if (part instanceof FulltextSurfaceFormLeafPagePart surface) {
						this.surfaceKeys.put(surface.getStoragePartPK(), surface.getPage().keys().clone());
					}
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
		 * @param analyzerRegistry the registry the context carries
		 * @return the reloaded index
		 */
		@Nonnull
		GlobalEntityIndex reload(@Nonnull FulltextAnalyzerRegistry analyzerRegistry) {
			final OffsetIndexReadService service = new OffsetIndexReadService(reopen());
			for (final Map.Entry<Long, String[]> expected : this.surfaceKeys.entrySet()) {
				final FulltextSurfaceFormLeafPagePart stored = service.getStoragePart(
					this.version, expected.getKey(), FulltextSurfaceFormLeafPagePart.class);
				assertNotNull(stored, "Every retained surface page must survive the store round trip.");
				assertArrayEquals(expected.getValue(), stored.getPage().keys(),
					"Persisted display spellings must survive the store round trip.");
			}
			final EntityIndexStoragePart manifest = service.getStoragePart(
				this.version, ENTITY_INDEX_PK, EntityIndexStoragePart.class
			);
			assertNotNull(manifest, "The manifest must be on disk.");
			final EntityIdsStoragePart entityIds = service.getStoragePart(
				this.version, ENTITY_INDEX_PK, EntityIdsStoragePart.class
			);
			final LoadContext context = new LoadContext(this.version, ENTITY_INDEX_PK,
				EntitySchema._internalBuild(ENTITY_TYPE), manifest.getEntityIndexKey(), manifest,
				manifest.getVersion(), entityIds == null ? EmptyBitmap.INSTANCE : entityIds.getEntityIds(),
				entityIds == null ? Map.of() : entityIds.getEntityIdsByLanguage(), service, null, true,
				AttributeNormalizationResolver.LEGACY_NFD);
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
