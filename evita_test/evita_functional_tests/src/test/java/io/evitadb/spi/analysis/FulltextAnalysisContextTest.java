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


package io.evitadb.spi.analysis;

import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies what a fulltext step gets besides its parameters: the locale and the resources, kept as they were given
 * when the chain was compiled, and the preparation of the words of the resources.
 */
@DisplayName("Fulltext analysis context")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextAnalysisContextTest {
	/** The resource the contexts here are built with. */
	private static final WordList BRANDS = new WordList(List.of("acme"));

	/** A caller changing its map afterwards does not change the resources the steps read. */
	@Test
	@DisplayName("should keep an unmodifiable copy of the resources")
	void shouldKeepUnmodifiableCopyOfResources() {
		final Map<String, FulltextResourceContent> source = new HashMap<>();
		source.put("brands", BRANDS);

		final FulltextAnalysisContext context = new FulltextAnalysisContext(Locale.ROOT, source);
		source.clear();

		assertEquals(Map.of("brands", BRANDS), context.resources());
		assertThrows(UnsupportedOperationException.class, () -> context.resources().put("other", BRANDS));
	}

	/** A context created without a preparation hands the words over as the resource holds them. */
	@Test
	@DisplayName("should hand words over as the resource holds them without a preparation")
	void shouldHandWordsOverAsResourceHoldsThemWithoutPreparation() {
		final FulltextAnalysisContext context = new FulltextAnalysisContext(Locale.ROOT, Map.of());

		assertSame(FulltextWordPreparation.NONE, context.preparation());
		assertEquals(List.of("Nike"), context.prepare("Nike"));
	}

	/** The context of the next step differs in its preparation only. */
	@Test
	@DisplayName("should keep the locale and the resources when the preparation changes")
	void shouldKeepLocaleAndResourcesWhenPreparationChanges() {
		final FulltextAnalysisContext context =
			new FulltextAnalysisContext(Locale.GERMAN, Map.of("brands", BRANDS));
		final FulltextWordPreparation lowercase = word -> List.of(word.toLowerCase(Locale.ROOT));

		final FulltextAnalysisContext next = context.withPreparation(lowercase);

		assertEquals(Locale.GERMAN, next.locale());
		assertEquals(context.resources(), next.resources());
		assertSame(lowercase, next.preparation());
	}

	/** Narrowing keeps preparation and locale but exposes only explicitly named available resources. */
	@Test
	@DisplayName("should narrow resources while preserving locale and preparation")
	void shouldNarrowResources() {
		final FulltextWordPreparation preparation = word -> List.of(word.toLowerCase(Locale.ROOT));
		final FulltextAnalysisContext context = new FulltextAnalysisContext(
			Locale.GERMAN, Map.of("brands", BRANDS, "other", new WordList(List.of("other"))), preparation
		);
		final FulltextAnalysisContext narrowed = context.withResources(Set.of("brands", "missing"));
		assertEquals(Map.of("brands", BRANDS), narrowed.resources());
		assertEquals(Locale.GERMAN, narrowed.locale());
		assertSame(preparation, narrowed.preparation());
		assertEquals(BRANDS, narrowed.getResource("brands"));
		assertThrows(EvitaInvalidUsageException.class, () -> narrowed.getResource("other"));
		assertThrows(EvitaInvalidUsageException.class, () -> narrowed.getResource("missing"));
		assertEquals(2, context.resources().size());
	}

	/** Each word of a list is prepared; the result keeps the order of the list and holds every word once. */
	@Test
	@DisplayName("should prepare the words of a word list in its order, each once")
	void shouldPrepareWordsOfWordListInItsOrderEachOnce() {
		final FulltextAnalysisContext context = new FulltextAnalysisContext(
			Locale.ROOT, Map.of("brands", new WordList(List.of("Nike", "the", "NIKE", "Puma")))
		).withPreparation(word -> "the".equals(word) ? List.of() : List.of(word.toLowerCase(Locale.ROOT)));

		assertEquals(List.of("nike", "puma"), List.copyOf(context.getPreparedWords("brands")));
	}

	/** Only a word list has words to prepare. */
	@Test
	@DisplayName("should refuse to prepare the words of a resource that is not a word list")
	void shouldRefuseToPrepareWordsOfResourceThatIsNotWordList() {
		final FulltextAnalysisContext context = new FulltextAnalysisContext(
			Locale.ROOT, Map.of("groups", new SynonymList(List.of(new SynonymGroup(List.of("tv", "televize")))))
		);

		final EvitaInvalidUsageException exception =
			assertThrows(EvitaInvalidUsageException.class, () -> context.getPreparedWords("groups"));

		assertTrue(exception.getMessage().contains("`groups` is not a word list"), exception.getMessage());
	}

	/**
	 * A resource no one declared is a user error: a step of another provider may name one the catalog schema could
	 * not check.
	 */
	@Test
	@DisplayName("should refuse a resource that does not exist as a user error")
	void shouldRefuseMissingResourceAsUserError() {
		final FulltextAnalysisContext context = new FulltextAnalysisContext(Locale.ROOT, Map.of("brands", BRANDS));

		final EvitaInvalidUsageException exception =
			assertThrows(EvitaInvalidUsageException.class, () -> context.getResource("colors"));

		assertTrue(exception.getMessage().contains("`colors` does not exist"), exception.getMessage());
	}
}
