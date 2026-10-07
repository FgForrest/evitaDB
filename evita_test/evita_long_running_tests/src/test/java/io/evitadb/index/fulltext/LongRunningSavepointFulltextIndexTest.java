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

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized proof that a {@link FulltextIndex} is restored exactly by a per-entity savepoint rollback
 * and kept by a savepoint commit - its term dictionary with the impact column, its length tables and its field
 * registry together.
 *
 * Each generation seeds a fresh index through {@link FulltextIndexModel}, then applies a random baseline batch (it
 * must survive) and a random in-savepoint batch (it must be reverted by a rollback and kept by a commit). Both
 * batches write and remove values, register fields and retire them, so a savepoint can open in the middle of a
 * field's life and close after its retirement or its successor's registration. The index's content is read through
 * {@link FulltextIndexModel#contentOf(FulltextIndex)} - the term walk, a descent per term, the length tables and the
 * key resolution - which is also the harness's mid-savepoint read.
 *
 * The in-savepoint batch ends with a marker: a value written under a field key the random steps never use, so the
 * batch always registers a field and always adds postings, and can never come out as a no-op.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint, and the WARM_UP savepoint where the writes land on the index itself and are rewound from the inverses
 * the index, its dictionary and its length tables journal. See that class for the shape of one generation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext index savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointFulltextIndexTest extends AbstractSavepointFuzzTest<String> {
	/**
	 * The key of the marker field, which the random steps never draw.
	 */
	private static final FulltextFieldKey MARKER_KEY = FulltextFieldKey.attribute("marker");
	/**
	 * The most steps a batch takes.
	 */
	private static final int MAX_OPERATIONS = 12;
	/**
	 * A generation is short, so it never accumulates enough fields to need a bound; this one only keeps the model's
	 * contract satisfied.
	 */
	private static final int MAX_FIELD_COUNT = 64;

	/**
	 * Registry providing the real Czech index-slot analyzer.
	 */
	private static FulltextAnalyzerRegistry registry;
	/**
	 * The analyzer every index in this test uses.
	 */
	private static FulltextAnalyzer analyzer;

	@BeforeAll
	static void setUpAnalyzer() {
		registry = new FulltextAnalyzerRegistry();
		analyzer = registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs"));
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	@Nonnull
	@Override
	protected FuzzGeneration<String> newGeneration(@Nonnull Random random) {
		return new IndexState(random);
	}

	/**
	 * One generation's fixture: a fresh index paired with its model.
	 */
	private static final class IndexState implements FuzzGeneration<String> {
		/**
		 * The index under test.
		 */
		@Nonnull private final FulltextIndex index = new FulltextIndex(analyzer);
		/**
		 * The model the random steps are drawn from. It is not rewound by a rollback - a generation ends with the
		 * savepoint, so nothing draws from it afterwards.
		 */
		@Nonnull private final FulltextIndexModel model = new FulltextIndexModel(
			analyzer, LongRunningFulltextIndexTest.KEYS, LongRunningFulltextIndexTest.PRIMARY_KEYS
		);
		/**
		 * The steps of the generation, for the failure report.
		 */
		@Nonnull private final StringBuilder log = new StringBuilder(1_024);

		/**
		 * Seeds the index with a random history, outside any savepoint.
		 *
		 * @param random the generation's source of randomness
		 */
		IndexState(@Nonnull Random random) {
			this.model.applyRandomOperations(this.index, random, 20 + random.nextInt(60), MAX_FIELD_COUNT, this.log);
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public String contents() {
			return FulltextIndexModel.contentOf(
				this.index, LongRunningFulltextIndexTest.KEYS, LongRunningFulltextIndexTest.PRIMARY_KEYS
			) + "\nmarker -> " + this.index.getFieldId(MARKER_KEY);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			this.model.applyRandomOperations(
				this.index, random, 1 + random.nextInt(MAX_OPERATIONS), MAX_FIELD_COUNT, this.log
			);
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			this.model.applyRandomOperations(
				this.index, random, random.nextInt(MAX_OPERATIONS), MAX_FIELD_COUNT, this.log
			);
			// applied LAST: a marker applied first could be undone by a later random step
			this.index.addValue(MARKER_KEY, 0, "Praha kolo");
		}
	}

}
