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

import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fixed-iteration lane proofs using the existing value oracle, without timing or measurement runs. */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Surface lane against the fulltext value oracle")
class FulltextSurfaceFormModelTest {
	/** Both mutation modes include every lane pair and reference count in the oracle comparison. */
	@Nested
	@DisplayName("Bounded churn")
	class BoundedChurn {
		/** Repeated writes and retirements exercise leaf splits and shared reference counts. */
		@Test
		@DisplayName("should follow the value oracle through warm-up churn")
		void shouldFollowWarmUpValueOracle() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					FulltextIndexModel.ANALYSIS_SCHEMA, "text", Locale.forLanguageTag("cs"));
				final Random random = new Random(258_341);
				for (int epoch = 0; epoch < 8; epoch++) {
					final FulltextIndex index = new FulltextIndex();
					final FulltextIndexModel model = newModel(analyzer);
					final StringBuilder log = new StringBuilder(4096);
					for (int batch = 0; batch < 30; batch++) {
						log.setLength(0);
						model.applyRandomOperations(index, random, 8, 16, log);
						assertEquals(model.expectedContent(), model.contentOf(index), log::toString);
					}
				}
			}
		}

		/** Each previous committed version must retain its original lane counts. */
		@Test
		@Tag(TRANSACTION)
		@DisplayName("should isolate surface counts across chained committed versions")
		void shouldFollowCommittedValueOracle() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					FulltextIndexModel.ANALYSIS_SCHEMA, "text", Locale.forLanguageTag("cs"));
				final Random random = new Random(258_342);
				for (int epoch = 0; epoch < 6; epoch++) {
					final AtomicReference<FulltextIndex> current = new AtomicReference<>(new FulltextIndex());
					final AtomicReference<FulltextIndexModel> currentModel = new AtomicReference<>(newModel(analyzer));
					final StringBuilder log = new StringBuilder(4096);
					for (int batch = 0; batch < 25; batch++) {
						final FulltextIndexModel before = currentModel.get();
						final FulltextIndexModel after = before.copy();
						final String expectedBefore = before.expectedContent();
						log.setLength(0);
						assertStateAfterCommit(current.get(),
							index -> {
								after.applyRandomOperations(index, random, 8, 16, log);
								assertEquals(after.expectedContent(), after.contentOf(index), log::toString);
							},
							(original, committed) -> {
								final FulltextIndex published = committed == null ? original : committed;
								assertEquals(expectedBefore, before.contentOf(original), log::toString);
								assertEquals(after.expectedContent(), after.contentOf(published), log::toString);
								current.set(published);
							});
						currentModel.set(after);
					}
				}
			}
		}
	}

	/** Builds the same oracle used by the requested long-running fulltext proofs. */
	private static FulltextIndexModel newModel(CompiledFulltextAnalyzer analyzer) {
		return new FulltextIndexModel(analyzer, LongRunningFulltextIndexTest.KEYS,
			LongRunningFulltextIndexTest.PRIMARY_KEYS);
	}
}
