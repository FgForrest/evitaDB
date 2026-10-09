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

package io.evitadb.index.fulltext.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the HTML-stripping character filter in front of every built-in index chain does — the step proposed in
 * the PR #1453 review after measuring a production CMS corpus whose article bodies are HTML carrying a JSON payload in
 * `data-` attributes: 79 % of the distinct terms and half of the dictionary-plus-postings heap were markup. The
 * measurements are recorded in `p5-analyzers.md` §13; this class establishes the behaviour they rest on, at the level
 * of the terms, offsets and positions the engine actually consumes.
 *
 * The filter started as an opt-in test fixture and was made part of every index chain when associated data became
 * searchable (#258): a stored value may be HTML, and the strip is a no-op on markup-free text. The search chains do not
 * strip - a query text is what a user typed. Three things are established:
 *
 * 1. **The baseline.** A chain without the character filter indexes tag names, attribute names, attribute values and
 *    entity names as terms — the vocabulary the review found dominating the dictionary (`p`, `class`, `dynamik`,
 *    `dat`, `valu`, `fals`, `quot`) is reproduced here on a synthetic body.
 * 2. **What the production index chain does with {@link HtmlStrippingAnalyzerWrapper}**: which markup vanishes, how
 *    entities decode, that inline tags join word halves while block tags separate them, and that plain text is left
 *    byte-for-byte alone.
 * 3. **The sharp edges the production wiring accepts**, each pinned as observed rather than as wished: end offsets
 *    (and with them {@link AnalyzedTerm#surfaceForm()}) of a term directly followed by a tag swallow the tag; an
 *    entity that decodes to a combining mark bypasses the NFC normalization on the boundary because the filter runs
 *    after it; a trailing `&lt;X` at the very end of a value is eaten as an unclosed tag.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HTML markup stripping in front of the full-text index chains")
@Tag(ENGINE)
@Tag(FULLTEXT)
class HtmlMarkupStrippingAnalysisTest {

	private static final String ENTITY_TYPE = "ARTICLE";
	private static final Locale CZECH = new Locale("cs");
	/**
	 * A body of the shape the review measured: block markup carrying a JSON payload in a `data-` attribute,
	 * with a little Czech prose inside. Every markup word of the review's "widest posting lists" is in it.
	 */
	private static final String PAYLOAD_BODY =
		"<p class=\"dynamic\" data-value='{\"value\": false, \"date\": \"2026\"}'>Černá pánská obuv</p>";

	private final List<FulltextAnalyzer> builtChains = new ArrayList<>(4);
	private FulltextAnalyzerRegistry registry;
	/**
	 * The Czech index chain without the character filter - the baseline the strip is measured against.
	 */
	private FulltextAnalyzer unstripped;
	/**
	 * The built-in Czech index chain as the registry hands it out - with {@link HtmlStrippingAnalyzerWrapper}.
	 */
	private FulltextAnalyzer stripped;
	/**
	 * The built-in Czech search chain as the registry hands it out - without the character filter.
	 */
	private FulltextAnalyzer productionSearch;

	@BeforeEach
	void setUp() {
		this.registry = new FulltextAnalyzerRegistry();
		this.stripped = this.registry.getIndexAnalyzer(ENTITY_TYPE, CZECH);
		this.productionSearch = this.registry.getSearchAnalyzer(ENTITY_TYPE, CZECH);
		this.unstripped = chain(
			"czech-unstripped", new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer())
		);
	}

	@AfterEach
	void tearDown() {
		this.builtChains.forEach(FulltextAnalyzer::close);
		this.builtChains.clear();
		this.registry.close();
	}

	/**
	 * Wraps a Lucene chain into the engine's analyzer holder — so that the terms come through exactly the code
	 * path production uses, NFC normalization on the boundary included — and registers it for closing.
	 *
	 * @param name     name the chain is reported under
	 * @param analyzer the Lucene chain
	 * @return the wrapped analyzer
	 */
	@Nonnull
	private FulltextAnalyzer chain(@Nonnull String name, @Nonnull Analyzer analyzer) {
		final FulltextAnalyzer wrapped = new FulltextAnalyzer(name, AnalysisMode.ALL, analyzer);
		this.builtChains.add(wrapped);
		return wrapped;
	}

	/**
	 * Analyses `text` and returns only the terms.
	 *
	 * @param analyzer chain to analyse with
	 * @param text     text to analyse
	 * @return terms produced by the chain, in order
	 */
	@Nonnull
	private static List<String> terms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::term).toList();
	}

	/**
	 * Analyses `text` and returns only the surface forms.
	 *
	 * @param analyzer chain to analyse with
	 * @param text     text to analyse
	 * @return surface forms of the produced terms, in order
	 */
	@Nonnull
	private static List<String> surfaceForms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::surfaceForm).toList();
	}

	/**
	 * Analyses `text` and returns the distinct terms it produced — the quantity the review's dictionary
	 * measurement counts.
	 *
	 * @param analyzer chain to analyse with
	 * @param text     text to analyse
	 * @return distinct terms
	 */
	@Nonnull
	private static Set<String> distinctTerms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return new HashSet<>(terms(analyzer, text));
	}

	@Nested
	@DisplayName("A chain without the character filter")
	class ChainWithoutCharacterFilter {

		@Test
		@DisplayName("Index tag names, attribute names and attribute payload as terms")
		void shouldIndexMarkupAsTerms() {
			// the review's "widest posting lists" reproduced: `p` in 98 % of articles, `dat`/`dynamik`/`fals`/
			// `class`/`valu` in 61 % - every one of them is a stemmed piece of markup, not a word of the article
			assertIterableEquals(
				List.of(
					"p", "class", "dynamik", "dat", "valu", "valu", "fals", "dat", "2026",
					"cern", "pansk", "obuv", "p"
				),
				terms(unstripped, PAYLOAD_BODY)
			);
		}

		@Test
		@DisplayName("Index entity names as terms and lose the character they stand for")
		void shouldIndexEntityNamesAsTerms() {
			// `quot` is the review's 60.7 % term; `nbsp` glues to the following stop word and both vanish, so the
			// non-breaking space costs a position gap as well as a term
			assertIterableEquals(
				List.of("quot", "cern", "quot", "lt", "obuv", "gt", "amp", "nbsp"),
				terms(unstripped, "&quot;Černá&quot; &lt;obuv&gt; &amp; k&nbsp;tomu")
			);
			// a numeric entity is worse than a named one: the code point becomes a number term and the word it
			// belonged to is torn apart
			assertIterableEquals(List.of("268", "ern", "225", "obuv"), terms(unstripped, "&#268;ern&#225; obuv"));
		}

		@Test
		@DisplayName("Tear a word apart at an inline tag and stem each half separately")
		void shouldTearWordApartAtInlineTag() {
			// `Čer` and `ná` are each stemmed as if they were words - two garbage stems and two `b` terms instead
			// of the one term `cern` the word would have produced
			assertIterableEquals(List.of("cr", "b", "na", "b", "obuv"), terms(unstripped, "Čer<b>ná</b> obuv"));
		}

		@Test
		@DisplayName("Index script bodies, style sheets and comments")
		void shouldIndexScriptStyleAndCommentBodies() {
			assertIterableEquals(
				List.of("script", "typ", "application", "json", "titl", "skryt", "script", "viditeln"),
				terms(unstripped, "<script type=\"application/json\">{\"title\": \"Skryté\"}</script>Viditelné")
			);
			assertIterableEquals(
				List.of("styl", "x", "color:rd", "styl", "viditeln"),
				terms(unstripped, "<style>.x{color:red}</style>Viditelné")
			);
			assertIterableEquals(List.of("komentar", "viditeln"), terms(unstripped, "<!-- komentář --> Viditelné"));
		}

	}

	@Nested
	@DisplayName("The built-in index chain")
	class StrippedChain {

		@Test
		@DisplayName("Drops tags, attributes and the attribute payload, keeping only the prose")
		void shouldDropMarkupAndAttributePayload() {
			assertIterableEquals(List.of("cern", "pansk", "obuv"), terms(stripped, PAYLOAD_BODY));
			// none of the review's markup vocabulary survives
			final Set<String> survivors = distinctTerms(stripped, PAYLOAD_BODY);
			for (final String markupTerm : List.of("p", "class", "dynamik", "dat", "valu", "fals", "quot")) {
				assertFalse(survivors.contains(markupTerm), "Markup term `" + markupTerm + "` survived stripping.");
			}
		}

		@Test
		@DisplayName("Shrinks the distinct-term set of a markup-heavy body to its prose")
		void shouldShrinkDistinctTermSetToProse() {
			// the review measured -79 % distinct terms on 972,611 articles; on this single body the same ratio is
			// 10 to 3. The body is the unit the dictionary pays per, so the shape of the saving is the same
			assertEquals(10, distinctTerms(unstripped, PAYLOAD_BODY).size());
			assertEquals(3, distinctTerms(stripped, PAYLOAD_BODY).size());
		}

		@Test
		@DisplayName("Decodes named and numeric entities into the characters they stand for")
		void shouldDecodeEntities() {
			// `k` and `tomu` are Czech stop words, so once `&nbsp;` is a space again both disappear as words should
			assertIterableEquals(
				List.of("cern", "obuv"), terms(stripped, "&quot;Černá&quot; &lt;obuv&gt; &amp; k&nbsp;tomu")
			);
			// a precomposed code point decodes to a letter the stemmer reads - `Černá` stems as if typed
			assertIterableEquals(List.of("cern", "obuv"), terms(stripped, "&#268;ern&#225; obuv"));
		}

		@Test
		@DisplayName("Joins the halves of a word an inline tag split, so it stems as one word")
		void shouldJoinWordSplitByInlineTag() {
			// an inline tag is removed without a separator: `Čer<b>ná</b>` reads `Černá` and stems to `cern`
			assertIterableEquals(List.of("cern", "obuv"), terms(stripped, "Čer<b>ná</b> obuv"));
			assertIterableEquals(List.of("cern"), terms(stripped, "Č<!--x-->erná"));
		}

		@Test
		@DisplayName("Separates words a block tag or line break stands between")
		void shouldSeparateWordsSplitByBlockTag() {
			// a block-level tag (`<p>`, `<br>`, `<div>`...) is replaced by whitespace, so two adjacent paragraphs
			// never fuse into one token even when the source carries no whitespace between them
			assertIterableEquals(List.of("cern", "obuv"), terms(stripped, "<p>Černá</p><p>obuv</p>"));
			assertIterableEquals(List.of("cern", "obuv"), terms(stripped, "Černá<br>obuv"));
			// the flip side: a block tag INSIDE a word splits it, exactly as the whitespace it stands for would
			assertIterableEquals(List.of("cr", "na"), terms(stripped, "Čer<p>ná</p>"));
		}

		@Test
		@DisplayName("Drops script, style and comment bodies but keeps CDATA, title and textarea content")
		void shouldDropScriptStyleAndCommentBodies() {
			assertIterableEquals(
				List.of("viditeln"),
				terms(stripped, "<script type=\"application/json\">{\"title\": \"Skryté\"}</script>Viditelné")
			);
			assertIterableEquals(List.of("viditeln"), terms(stripped, "<style>.x{color:red}</style>Viditelné"));
			assertIterableEquals(List.of("viditeln"), terms(stripped, "<!-- komentář --> Viditelné"));
			// a JSON payload in an attribute goes with the attribute - the review's `data-` case
			assertIterableEquals(
				List.of("viditeln"),
				terms(stripped, "<div data-json='{&quot;title&quot;:&quot;Skryté&quot;}'>Viditelné</div>")
			);
			// what is NOT dropped: the section marker of a CDATA block goes, its content stays; `<title>` and
			// `<textarea>` are ordinary elements to this filter
			assertIterableEquals(List.of("uvnitr", "venk"), terms(stripped, "<![CDATA[ Uvnitř ]]> Venku"));
			assertIterableEquals(
				List.of("titulk", "uvnitr"), terms(stripped, "<title>Titulek</title><textarea>uvnitř</textarea>")
			);
		}

		@Test
		@DisplayName("Drops the text of an image's alt attribute along with the tag")
		void shouldDropAltTextWithTheTag() {
			// an attribute is an attribute: nothing distinguishes `alt` from `class` to a markup stripper, so the
			// one attribute that does carry prose is lost with the rest. A wiring wanting alt text indexed has to
			// extract it before stripping - the filter has no hook for it
			assertTrue(terms(stripped, "<img src=\"x.png\" alt=\"Popisek obrázku\">").isEmpty());
		}

		@Test
		@DisplayName("Leaves no position gap where markup was removed")
		void shouldLeaveNoPositionGapForRemovedMarkup() {
			// removed markup is not a dropped token, so a phrase spanning a tag boundary still matches: `černá obuv`
			// as a phrase finds `<p>Černá</p>\n<p>obuv</p>`
			for (final AnalyzedTerm analyzedTerm : stripped.getTerms("<p>Černá</p>\n<p>obuv</p>")) {
				assertEquals(
					1, analyzedTerm.positionIncrement(), "Unexpected gap before `" + analyzedTerm.term() + "`."
				);
			}
		}

		@Test
		@DisplayName("Is a no-op on plain text, offsets and positions included")
		void shouldBeNoOpOnPlainText() {
			// the property that lets the step run over every field of a (collection, locale) rather than needing a
			// per-attribute switch: on a value without markup the two chains agree on every term, offset and position
			for (final String plainText : List.of(
				"Černá pánská obuv z pravé kůže, velikost 42",
				"Velikost 5 < 10 a 10 > 5",
				"Velikost 5<10 a 10>5",
				"<3 boty",
				"Text s neuzavřeným <tagem a dál",
				"email@example.com 3.5mm iPhone15",
				"{\"title\": \"Černá obuv\", \"price\": 100}"
			)) {
				assertIterableEquals(
					unstripped.getTerms(plainText), stripped.getTerms(plainText),
					"Stripping changed the analysis of the markup-free text `" + plainText + "`."
				);
			}
		}

	}

	@Nested
	@DisplayName("Offsets and surface forms of stripped text")
	class OffsetsAndSurfaceForms {

		@Test
		@DisplayName("Start offsets point into the original, markup-bearing text")
		void shouldKeepStartOffsetsInOriginalText() {
			// the filter corrects offsets back through the removed runs, so a term still knows where in the STORED
			// value it begins - the property highlighting depends on
			final List<AnalyzedTerm> analyzed = stripped.getTerms("<p>Černá <b>pánská</b> obuv</p>");
			assertEquals(3, analyzed.size());
			assertEquals(3, analyzed.get(0).startOffset());
			assertEquals(12, analyzed.get(1).startOffset());
			assertEquals(23, analyzed.get(2).startOffset());
		}

		@Test
		@DisplayName(
			"End offset of a term directly followed by a tag extends over the tag - and so does its surface form"
		)
		void shouldExtendEndOffsetOverTrailingMarkup() {
			// SHARP EDGE, pinned as observed. Lucene's offset correction maps an output offset sitting at the
			// boundary of a removed run onto the END of that run, so `pánská` followed by `</b>` ends at 22, not 18,
			// and the surface form cut from those offsets carries the tag. A term followed by whitespace before its
			// tag (`Černá <b>`) is unaffected. Any consumer of `surfaceForm()` - the suggester, highlighting - has to
			// either tolerate trailing markup or trim it; the term itself is correct
			final List<AnalyzedTerm> analyzed = stripped.getTerms("<p>Černá <b>pánská</b> obuv</p>");
			assertEquals(new AnalyzedTerm("cern", "Černá", 3, 8, 1), analyzed.get(0));
			assertEquals(new AnalyzedTerm("pansk", "pánská</b>", 12, 22, 1), analyzed.get(1));
			assertEquals(new AnalyzedTerm("obuv", "obuv", 23, 27, 1), analyzed.get(2));
		}

		@Test
		@DisplayName(
			"A word joined across an inline tag reports the whole span, tag included, as its surface form"
		)
		void shouldReportWholeSpanOfWordJoinedAcrossInlineTag() {
			// consistent with the previous test: the term is right, the surface form is the raw slice of the stored
			// value between the corrected offsets and therefore contains the markup the filter removed
			assertIterableEquals(List.of("Čer<b>ná</b>", "obuv"), surfaceForms(stripped, "Čer<b>ná</b> obuv"));
			assertIterableEquals(List.of("&#268;ern&#225;", "obuv"), surfaceForms(stripped, "&#268;ern&#225; obuv"));
		}

	}

	@Nested
	@DisplayName("Sharp edges the production wiring accepts")
	class SharpEdges {

		@Test
		@DisplayName("Keeps a less-than sign used in prose")
		void shouldKeepLessThanInProse() {
			// `<` followed by a space, a digit or nothing tag-like is not a tag and is left alone - a product text
			// comparing sizes or prices is safe in the common shapes
			final List<String> sizes = List.of("velikost", "5", "10", "10", "5");
			assertIterableEquals(sizes, terms(stripped, "Velikost 5 < 10 a 10 > 5"));
			assertIterableEquals(sizes, terms(stripped, "Velikost 5<10 a 10>5"));
			assertIterableEquals(List.of("3", "bot"), terms(stripped, "<3 boty"));
			// an opened tag that never closes and runs into more prose is given back as text
			assertIterableEquals(
				List.of("text", "neuzavrn", "tag", "dal"), terms(stripped, "Text s neuzavřeným <tagem a dál")
			);
		}

		@Test
		@DisplayName("Swallows a letter-led `<X` at the very end of a value as an unclosed tag")
		void shouldSwallowTrailingUnclosedTagLikeText() {
			// SHARP EDGE, pinned as observed: `<L` at end of input is consumed as a tag that never closed, so the `l`
			// term the unstripped chain produces is gone. Accepted with the strip in every index chain: a size range
			// written `S<M<L` in a product description loses its last size
			assertIterableEquals(List.of("velikost", "m", "l"), terms(unstripped, "Velikost S<M<L"));
			assertIterableEquals(List.of("velikost", "m"), terms(stripped, "Velikost S<M<L"));
			// and a letter between angle brackets in prose IS a tag: `a<b>c` joins into `ac`
			assertIterableEquals(List.of("ak"), terms(stripped, "a<b>c"));
		}

		@Test
		@DisplayName("An entity decoding to a combining mark bypasses the NFC normalization on the boundary")
		void shouldNotNormalizeEntityDecodedCombiningMarks() {
			// SHARP EDGE, pinned as observed. FulltextAnalyzer normalizes the text to NFC BEFORE handing it to the
			// chain, and a character filter runs inside the chain - after it. `c&#780;` decodes to `c` plus a
			// combining caron, i.e. NFD, which the Czech stemmer cannot read and ASCIIFoldingFilter does not fold.
			// The term comes out neither stemmed nor folded. Accepted for now: fixing it takes an offset-correcting NFC
			// character filter after the strip, which is not worth it until a corpus shows entity-encoded diacritics
			final List<String> terms = terms(stripped, "c&#780;erna&#769; obuv");
			assertEquals(2, terms.size());
			assertNotEquals("cern", terms.get(0));
			assertEquals("černá", terms.get(0));
			// the same word with precomposed entities is fine, which is what makes the gap easy to miss
			assertIterableEquals(List.of("cern", "obuv"), terms(stripped, "&#269;ern&#225; obuv"));
		}

	}

	@Nested
	@DisplayName("Both sides of the pipeline")
	class BothSides {

		@Test
		@DisplayName("Strips markup on the index side only")
		void shouldStripOnIndexSideOnly() {
			// the registry hands out the wrapper for the index slot and a bare chain for the search slot - a query text
			// is what a user typed, so `<b>` in it is two characters the user meant
			assertIterableEquals(List.of("cern"), terms(stripped, "<b>Černá</b>"));
			assertTrue(terms(productionSearch, "<b>cerna</b>").contains("b"));
		}

		@Test
		@DisplayName("Strips markup in every built-in index chain and in no search chain")
		void shouldStripInEveryIndexChainAndNoSearchChain() {
			for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de", "xx")) {
				final Locale locale = new Locale(language);
				final FulltextAnalyzer indexAnalyzer = registry.getIndexAnalyzer(ENTITY_TYPE, locale);
				final FulltextAnalyzer searchAnalyzer = registry.getSearchAnalyzer(ENTITY_TYPE, locale);
				assertNotEquals(indexAnalyzer.getAnalyzerName(), searchAnalyzer.getAnalyzerName(), language);
				assertTrue(
					terms(indexAnalyzer, "<p class=\"x\">word</p>").stream().noneMatch(it -> it.startsWith("class")),
					"Index chain of `" + language + "` indexed markup."
				);
				assertTrue(
					terms(searchAnalyzer, "<p class=\"x\">word</p>").stream().anyMatch(it -> it.startsWith("class")),
					"Search chain of `" + language + "` stripped markup."
				);
				assertEquals(AnalysisMode.SEARCH_TIME, searchAnalyzer.getMode(), language);
				assertEquals(AnalysisMode.ALL, indexAnalyzer.getMode(), language);
			}
		}

		@Test
		@DisplayName("Leaves markup-free text exactly as the unstripped chain analyses it")
		void shouldAgreeWithUnstrippedChainOnPlainText() {
			for (final String plainText : List.of("černá pánská obuv", "cerna panska obuv", "Velikost 5 < 10")) {
				assertIterableEquals(unstripped.getTerms(plainText), stripped.getTerms(plainText));
			}
		}

		@Test
		@DisplayName("Keeps the folded stop words keyed by the unchanged index analyzer name")
		void shouldKeepFoldedStopWordsOfIndexAnalyzer() {
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.ENGLISH_ANALYZER_NAME, "The"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "neni"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.ENGLISH_SEARCH_ANALYZER_NAME, "the"));
			assertFalse(registry.isFoldedStopWord(BuiltInAnalyzers.GENERIC_SEARCH_ANALYZER_NAME, "the"));
		}

		@Test
		@DisplayName("Puts the wrapper in front of the index chain the registry builds")
		void shouldBuildIndexChainThroughTheWrapper() {
			assertInstanceOf(
				HtmlStrippingAnalyzerWrapper.class,
				BuiltInAnalyzers.definitionFor(BuiltInAnalyzers.CZECH_ANALYZER_NAME).factory().get()
			);
		}

	}

}
