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

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerFingerprint;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared real schema analysis for low-level fulltext index fixtures. */
public final class FulltextIndexTestSupport {
	/** The schema shared by fixtures with no custom analysis definitions. */
	public static final CatalogSchemaContract SCHEMA = CatalogSchema._internalBuild(
		"test", NamingConvention.generate("test"), null, EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE);
	/** Keeps the fixture chains reachable for the duration of the test JVM. */
	private static final FulltextAnalyzerRegistry REGISTRY = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());

	/** Prevents construction of this fixture utility. */
	private FulltextIndexTestSupport() { }

	/** Returns the fixture's Czech text chains. */
	@Nonnull
	public static CompiledFulltextAnalyzer testAnalyzer() {
		return REGISTRY.getCompiledAnalyzer(SCHEMA, "text", Locale.forLanguageTag("cs"));
	}

	/**
	 * Returns the fixture's verbatim chains: the built-in `text` analyzer under {@link Locale#ROOT}, which only splits
	 * with the standard tokenizer and lowercases - no stop words, no stemming. A lower-case word written through it is
	 * the term it indexes, so a fixture can place exact terms through the write path production takes.
	 */
	@Nonnull
	public static CompiledFulltextAnalyzer exactAnalyzer() {
		return REGISTRY.getCompiledAnalyzer(SCHEMA, "text", Locale.ROOT);
	}

	/**
	 * Builds a real keyword chain that keeps unpaired surrogates in its whole-value term. The wrapper still applies
	 * Unicode NFC. The caller owns and closes `index()`; every chain refers to that same analyzer instance.
	 */
	@Nonnull
	public static CompiledFulltextAnalyzer keywordAnalyzer() {
		final FulltextAnalyzer chain = new FulltextAnalyzer("keyword", new KeywordAnalyzer());
		return new CompiledFulltextAnalyzer(
			chain, chain, chain, List.of(), chain, chain, new FulltextAnalyzerFingerprint(0L, 0L), Set.of(), List.of()
		);
	}

	/** Registers a fixture field, preserving an explicitly chosen pivot on subsequent lookups. */
	public static int registerField(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key) {
		final int id = index.getFieldId(key);
		return id == FulltextIndex.UNKNOWN_FIELD_ID ? registerField(index, key, 25.0) : id;
	}

	/** Registers a fixture field with an explicit pivot. */
	public static int registerField(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, double pivot) {
		return registerField(index, key, pivot, null);
	}

	/** Registers a fixture field with its extraction expression and real analyzer identity. */
	public static int registerField(
		@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, double pivot, @Nullable String expression
	) {
		final CompiledFulltextAnalyzer analyzer = testAnalyzer();
		return index.getOrAssignFieldId(key, "text", analyzer.fingerprint().index(),
			analyzer.fingerprint().wordPhase(), pivot, expression);
	}

	/** Registers a fixture field with the identity of the verbatim chains of {@link #exactAnalyzer()}. */
	public static int registerExactField(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, double pivot) {
		final CompiledFulltextAnalyzer analyzer = exactAnalyzer();
		return index.getOrAssignFieldId(key, "text", analyzer.fingerprint().index(),
			analyzer.fingerprint().wordPhase(), pivot, null);
	}

	/**
	 * Indexes the words as the entity's one value of the field, through {@link #exactAnalyzer()} and against the
	 * field's own pivot: every word becomes a posting of the entity, a word repeated `n` times a posting of term
	 * frequency `n`, and the value is as long as it has words.
	 */
	public static void addWords(
		@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, int primaryKey, @Nonnull String... words
	) {
		index.addValue(key, primaryKey, exactAnalyzer(), fieldPivot(index, key), null, String.join(" ", words));
	}

	/**
	 * Removes the entity's value of the field written by {@link #addWords} - every distinct word it holds has to be
	 * passed, and passing each once is enough.
	 */
	public static void removeWords(
		@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key, int primaryKey, @Nonnull String... words
	) {
		index.removeValue(key, primaryKey, exactAnalyzer(), fieldPivot(index, key), String.join(" ", words));
	}

	/**
	 * Returns the posting list of exactly the passed term, read the way the query path looks a term up: a walk from the
	 * term itself, whose first key is the term when the field holds it. The answer is a read-only view of the
	 * dictionary's own storage, valid until the next write; empty when the field does not hold the term.
	 */
	@Nonnull
	public static Bitmap postingsOf(@Nonnull FulltextIndex index, int fieldId, @Nonnull String term) {
		final Bitmap[] found = {EmptyBitmap.INSTANCE};
		index.forEachTerm(fieldId, term, (visited, postings, impacts) -> {
			if (visited.equals(term)) {
				found[0] = postings;
			}
			return false;
		});
		return found[0];
	}

	/**
	 * Returns the impacts of exactly the passed term, aligned with {@link #postingsOf}, read the way the query path
	 * reads them; empty when the field does not hold the term.
	 */
	@Nonnull
	public static byte[] impactsOf(@Nonnull FulltextIndex index, int fieldId, @Nonnull String term) {
		final byte[][] found = {new byte[0]};
		index.forEachTerm(fieldId, term, (visited, postings, impacts) -> {
			if (visited.equals(term)) {
				found[0] = impacts.toArray();
			}
			return false;
		});
		return found[0];
	}

	/** Counts the (field, term) keys of the dictionary - the terms of every field, retired ones included. */
	public static int termCount(@Nonnull FulltextIndex index) {
		final int[] count = new int[1];
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				count[0]++;
				return true;
			});
		}
		return count[0];
	}

	/** Returns a registered fixture's chosen pivot, or the schema default for a new field. */
	public static double fieldPivot(@Nonnull FulltextIndex index, @Nonnull FulltextFieldKey key) {
		final int id = index.getFieldId(key);
		return id == FulltextIndex.UNKNOWN_FIELD_ID ? 25.0 : index.getLengthPivot(id);
	}

	/**
	 * Returns how many compiled analyzers a registry retains. Retention is the registry's private bookkeeping and it
	 * exposes no count, so the tests bounding it read the field by name - here, in one place, failing with the field
	 * to follow when it is renamed.
	 */
	public static int retainedAnalyzerCount(@Nonnull FulltextAnalyzerRegistry registry) {
		try {
			final Field compiled = FulltextAnalyzerRegistry.class.getDeclaredField("compiled");
			compiled.setAccessible(true);
			return ((Map<?, ?>) compiled.get(registry)).size();
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(
				"The retained analyzers are read from FulltextAnalyzerRegistry#compiled - follow its rename here.", ex
			);
		}
	}
}
