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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.index.component.FulltextIndexMapComponent;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.FulltextIndex.DictionaryPage;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.index.IndexHeapSizeAssertions.AUTOBOX_CACHE_CEILING;
import static io.evitadb.index.IndexHeapSizeAssertions.assertExceedsMeasuredHeapBy;
import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.measuredHeapOf;
import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FulltextIndex#getHeapSizeInBytes()}, {@link FieldLengthTable#getHeapSizeInBytes()} and
 * {@link FulltextIndexMapComponent#getHeapSizeInBytes()} against what a JOL walk finds, following
 * `documentation/developer/heap-size-testing.md`.
 *
 * Three things every walk here has to be told, each a documented convention rather than a gap:
 *
 * - The exact fixtures name their fields and terms with a character outside Latin-1, so every string the index owns
 *   is stored as UTF-16 - the encoding the shared string sizer prices every string at. A Latin-1 fixture pins the
 *   deliberate over-report that sizer documents instead.
 * - The impact of a single posting is a boxed {@link Byte}, always the JVM's cached instance - `Byte.valueOf` caches
 *   every value by the language's contract, not by a flag - which `ImpactRecords` charges nothing for. The walk is
 *   handed the whole cache as shared roots.
 * - `MapHeapSize` charges a map pre-sized below sixteen slots for the sixteen organic growth would allocate. The
 *   fixtures whose maps are pre-sized from a known count therefore hold at least seven entries, where the two agree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(FULLTEXT)
@Tag(INDEXING)
@DisplayName("Fulltext index heap accounting")
class FulltextIndexHeapSizeTest {
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final List<Locale> LOCALES = List.of(
		CZECH, Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("sk"), Locale.forLanguageTag("pl"),
		Locale.forLanguageTag("ro"), Locale.FRENCH, Locale.ITALIAN
	);
	/**
	 * What every fulltext index reaches but does not charge: the analyzer the registry shares, the page bookkeeping no
	 * paged index charges, and the dictionary's column factories - lambdas the tree does not own and JOL cannot walk.
	 */
	private static final String[] INDEX_EXCLUSIONS = {
		"indexAnalyzer", "pageStreamRegistry", "dictionary.valueColumnFactory", "dictionary.recordColumnFactory"
	};

	/**
	 * Every cached {@link Byte}: a single posting's impact is one of them, and no index owns it.
	 */
	private static final Object[] CACHED_BYTES = cachedBytes();

	/**
	 * Registry providing the real analyzers.
	 */
	private static FulltextAnalyzerRegistry registry;

	@BeforeAll
	static void setUpRegistry() {
		registry = new FulltextAnalyzerRegistry();
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	/**
	 * @return every cached {@link Byte} instance
	 */
	@Nonnull
	private static Object[] cachedBytes() {
		final Object[] bytes = new Object[256];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = Byte.valueOf((byte) i);
		}
		return bytes;
	}

	/**
	 * Creates an empty Czech index.
	 *
	 * @return the index
	 */
	@Nonnull
	private static FulltextIndex newIndex() {
		return new FulltextIndex(registry.getIndexAnalyzer("product", CZECH));
	}

	/**
	 * Fills an index with two fields: enough terms for several dictionary leaves, single postings and postings shared
	 * by several entities, a sparse length block in each field and a dense one in the body.
	 *
	 * @param index     the index
	 * @param termCount how many terms to add
	 * @param stem      the stem every field name and term starts with - its encoding decides how they are stored
	 */
	private static void fill(@Nonnull FulltextIndex index, int termCount, @Nonnull String stem) {
		final int title = index.getOrAssignFieldId(stem + "title");
		final int body = index.getOrAssignFieldId(stem + "body", 300.0);
		for (int i = 0; i < termCount; i++) {
			final String term = stem + String.format("%05d", i);
			for (int j = 0; j <= i % 4; j++) {
				index.addPosting(i % 3 == 0 ? body : title, term, AUTOBOX_CACHE_CEILING + j * 70_000 + i, 1 + i % 255);
			}
		}
		for (int primaryKey = AUTOBOX_CACHE_CEILING; primaryKey < AUTOBOX_CACHE_CEILING + 300; primaryKey++) {
			index.getFieldLengths(title).put(primaryKey, 1 + primaryKey % 40);
		}
		// past a third of a block, which the table stores densely
		for (int primaryKey = 70_000; primaryKey < 70_000 + 25_000; primaryKey++) {
			index.getFieldLengths(body).put(primaryKey, 1 + primaryKey % 200);
		}
	}

	/**
	 * Registers further small fields, one posting and one length each, so the field registry's map holds enough
	 * entries for its pre-size to reach the sixteen slots `MapHeapSize` charges.
	 *
	 * @param index the index
	 * @param count how many fields to add
	 */
	private static void addSmallFields(@Nonnull FulltextIndex index, int count) {
		for (int i = 0; i < count; i++) {
			final int fieldId = index.getOrAssignFieldId("žpole" + i);
			index.addPosting(fieldId, "žslovo", AUTOBOX_CACHE_CEILING + i, 7);
			index.getFieldLengths(fieldId).put(AUTOBOX_CACHE_CEILING + i, 3);
		}
	}

	/**
	 * Lists what the index reaches but does not charge: the shared exclusions, plus the flush bookkeeping of every
	 * field's length table and, for an index without a field, the field registry every such index shares.
	 *
	 * @param index the index
	 * @return the exclusion paths
	 */
	@Nonnull
	private static String[] exclusionsOf(@Nonnull FulltextIndex index) {
		final List<String> paths = new ArrayList<>(List.of(INDEX_EXCLUSIONS));
		if (index.getFieldCount() == 0) {
			paths.add("fields");
		}
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			paths.add("fields." + fieldId + ".lengths.flushState");
		}
		return paths.toArray(String[]::new);
	}

	@Nested
	@DisplayName("Index")
	class Index {

		@Test
		@DisplayName("an empty index reports exactly what a JOL walk finds")
		void shouldPriceAnEmptyIndexExactly() {
			final FulltextIndex index = newIndex();
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, CACHED_BYTES, exclusionsOf(index));
		}

		@Test
		@DisplayName("a filled index - several leaves, sparse and dense length blocks - reports exactly what JOL finds")
		void shouldPriceAFilledIndexExactly() {
			final FulltextIndex index = newIndex();
			fill(index, 2_000, "ž");
			assertTrue(index.getFieldLengths(index.getFieldId("žbody")).isDenseBlock(70_000), "A dense block is priced.");
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, CACHED_BYTES, exclusionsOf(index));
		}

		@Test
		@DisplayName("an index reloaded from its pages exceeds JOL by its field ids the walk shares with its live pages")
		void shouldPriceAReloadedIndexExactly() {
			final FulltextIndex source = newIndex();
			fill(source, 2_000, "ž");
			addSmallFields(source, 5);
			final PageEmission<DictionaryPage> pages = source.collectChangedPages();
			final LengthBlockEmission[] lengths = source.collectChangedLengthBlocks();
			final List<FulltextIndex.Field> fields = new ArrayList<>(lengths.length);
			for (int fieldId = 0; fieldId < lengths.length; fieldId++) {
				fields.add(
					new FulltextIndex.Field(
						source.getFieldName(fieldId), source.getLengthPivot(fieldId),
						FieldLengthTable.fromPersistedBlocks(lengths[fieldId].changedBlocks().toArray(LengthBlock[]::new))
					)
				);
			}
			final FulltextIndex reloaded = FulltextIndex.fromPersistedPages(
				registry.getIndexAnalyzer("product", CZECH), source.getDefaultLengthPivot(), fields,
				pages.orderedPageSequences(), pages.changedPages().toArray(DictionaryPage[]::new),
				pages.highWaterPageSequence()
			);
			// trap 1 of the heap-size guide: a boxed field id below 128 is the JVM's cached Integer, and the restored page
			// registry - excluded, as no paged index charges it - holds the same cached instances as its live page
			// sequences. The walk subtracts them with the registry; the arithmetic charges each to the map holding it,
			// as rule 1 says a boxed Integer is charged. A fresh index has no live page before its first flush, which is
			// why the other cases are exact
			final int fieldCount = reloaded.getFieldCount();
			for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
				assertTrue(
					reloaded.getPageStreamRegistry().livePages(0).contains(fieldId),
					"The excess below assumes every field id is also a live page sequence."
				);
			}
			assertExceedsMeasuredHeapBy(
				reloaded.getHeapSizeInBytes(), fieldCount * VMLayout.current().sizeOfObject(Integer.BYTES),
				reloaded, CACHED_BYTES, exclusionsOf(reloaded)
			);
		}

		@Test
		@DisplayName("the committed copy of a transaction that added a field reports exactly what JOL finds")
		@Tag(TRANSACTION)
		void shouldPriceACommittedCopyExactly() {
			final FulltextIndex index = newIndex();
			fill(index, 600, "ž");
			addSmallFields(index, 5);
			assertStateAfterCommit(
				index,
				original -> {
					final int perex = original.getOrAssignFieldId("žperex");
					original.addPosting(perex, "žnovinka", AUTOBOX_CACHE_CEILING, 100);
					original.getFieldLengths(perex).put(AUTOBOX_CACHE_CEILING, 5);
				},
				(original, committed) -> {
					assertEquals(8, committed.getFieldCount());
					assertMatchesMeasuredHeap(
						committed.getHeapSizeInBytes(), committed, CACHED_BYTES, exclusionsOf(committed)
					);
				}
			);
		}

		@Test
		@DisplayName("a Latin-1 index over-reports by its keys' Latin-1 saving alone, a rounding error of the figure")
		void shouldOverReportLatinOneKeysByLittle() {
			// the shared string sizer prices every string as UTF-16 while the JVM stores an all-Latin-1 one at a byte
			// per char - the deliberate over-report MemoryMeasuringConstants#computeStringSize declares. It falls on the
			// field names and the dictionary's separator keys only, so it must stay small against the whole figure
			final FulltextIndex index = newIndex();
			fill(index, 2_000, "t");
			final long measured = measuredHeapOf(index, CACHED_BYTES, exclusionsOf(index));
			final long excess = index.getHeapSizeInBytes() - measured;
			assertTrue(excess > 0, "Latin-1 keys are charged as UTF-16.");
			assertTrue(excess < measured / 100, "The over-report must stay under one percent: " + excess);
		}

	}

	@Nested
	@DisplayName("Map component")
	class MapComponent {

		@Test
		@DisplayName("the footprint snapshot reports exactly what JOL finds, borrowing the registries and block arrays")
		void shouldPriceTheFootprintSnapshotExactly() {
			// eight locales, so the snapshot map - pre-sized from the count - reaches the sixteen slots MapHeapSize charges
			final TransactionalMap<Locale, FulltextIndex> map = new TransactionalMap<>(
				new HashMap<>(), FulltextIndex.class, Function.identity()
			);
			final List<FulltextIndex> indexes = new ArrayList<>(LOCALES.size());
			for (int i = 0; i < LOCALES.size(); i++) {
				final Locale locale = LOCALES.get(i);
				final FulltextIndex index = new FulltextIndex(registry.getIndexAnalyzer("product", locale));
				fill(index, i == 0 ? 600 : 50, "ž");
				map.put(locale, index);
				indexes.add(index);
			}
			final FulltextIndexMapComponent component = new FulltextIndexMapComponent(map);
			// a flush is what fills the snapshot
			component.collectModifiedStorageParts(7, new EntityIndexManifest(), new TrappedChanges());

			// the snapshot borrows each index's page registry and each length table's on-disk block array
			final List<Object> borrowed = new ArrayList<>(8);
			for (final FulltextIndex index : indexes) {
				borrowed.add(index.getPageStreamRegistry());
				for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
					borrowed.add(index.getPersistedLengthBlocks(fieldId));
				}
			}
			final long measured = measuredHeapOf(component, borrowed.toArray(), "fulltextIndexes");
			assertEquals(
				LOCALES.size(), ((Map<?, ?>) readField(component, "persistedFootprints")).size(),
				"Every index is on disk after the flush."
			);
			assertEquals(measured, component.getHeapSizeInBytes());
		}

	}

}
