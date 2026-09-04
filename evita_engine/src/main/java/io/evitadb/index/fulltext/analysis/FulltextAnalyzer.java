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

import io.evitadb.exception.GenericEvitaInternalError;
import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * A built Lucene analysis chain, owned and driven correctly: it turns a text into the terms an index would
 * store, or a query text into the terms it would be looked up by.
 *
 * It is a **resource holder** — one chain, the name it was built under and the {@link AnalysisMode} that name
 * was registered with — and it exists for three reasons beyond mere delegation:
 *
 * 1. it owns the chain's lifecycle. A Lucene `Analyzer` has to be closed, and something has to be the thing
 *    that closes it; see {@link #close()} and the paragraph below;
 * 2. it drives the Lucene stream protocol correctly. `Analyzer` is a streaming API whose call sequence — obtain
 *    the stream, fetch the attributes, `reset()`, iterate `incrementToken()`, `end()`, close — is not optional:
 *    skipping a step yields empty or truncated results rather than an error;
 * 3. it owns the NFC normalization on the boundary, which is a correctness requirement rather than tidiness —
 *    see {@link #analyze(String, AnalyzedTermConsumer)}.
 *
 * Instances are created and shared by {@link FulltextAnalyzerRegistry}, one per analyzer name; nothing else
 * should build one, because a chain nobody owns is a chain nobody closes.
 *
 * **Thread safety and lifecycle.** A Lucene `Analyzer` is thread-safe and so is this wrapper, but the mechanism
 * has consequences worth knowing. The analyzer caches its stream components in a `CloseableThreadLocal`, which
 * keeps — besides a weakly-referenced `ThreadLocal` — a `WeakHashMap<Thread, T>` written under `synchronized`.
 * On the platform threads the engine uses today that is exactly right: threads come from bounded pools, get
 * recycled, and the cache actually hits. Both properties would invert on virtual threads — a per-thread cache
 * is pointless when every task gets a fresh thread, and the `synchronized` block pins the carrier — so the seam
 * is left open rather than pre-solved: a custom `ReuseStrategy` can be handed to the `Analyzer` constructor,
 * making any future change local to the factory that builds the chain. What must *not* be skipped is
 * {@link #close()}: without it the `hardRefs` map holds every stream component for the lifetime of the process.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextAnalyzer implements Closeable {

	/**
	 * Field name handed to Lucene. Analyzers here are resolved per (collection, locale) rather than per field,
	 * so one constant name is enough — and it keeps every analyzer on the cheap global component-reuse strategy
	 * instead of a per-field one.
	 */
	private static final String FIELD_NAME = "value";

	/**
	 * Name this analyzer was registered under — the public identifier a schema refers to.
	 */
	@Nonnull @Getter private final String analyzerName;
	/**
	 * Side of the pipeline this instance may be used on, as declared when it was registered.
	 */
	@Nonnull @Getter private final AnalysisMode mode;
	/**
	 * The wrapped Lucene analysis chain.
	 */
	@Nonnull private final Analyzer analyzer;

	/**
	 * Creates a wrapper around an already built Lucene analysis chain.
	 *
	 * @param analyzerName name the chain was registered under
	 * @param mode         side of the pipeline the chain may be used on
	 * @param analyzer     the Lucene chain; this instance takes over its lifecycle and closes it in
	 *                     {@link #close()}
	 */
	public FulltextAnalyzer(@Nonnull String analyzerName, @Nonnull AnalysisMode mode, @Nonnull Analyzer analyzer) {
		this.analyzerName = analyzerName;
		this.mode = mode;
		this.analyzer = analyzer;
	}

	/**
	 * Analyses `text` and returns all terms it produced, in order.
	 *
	 * Convenience form of {@link #analyze(String, AnalyzedTermConsumer)} for callers that want the whole result
	 * at once; the write path should use the streaming form instead.
	 *
	 * @param text text to analyse
	 * @return terms produced by the chain, in the order the chain emitted them
	 */
	@Nonnull
	public List<AnalyzedTerm> getTerms(@Nonnull String text) {
		final List<AnalyzedTerm> terms = new ArrayList<>(estimateTermCount(text));
		analyze(
			text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> terms.add(
				new AnalyzedTerm(term, surfaceForm.get(), startOffset, endOffset, positionIncrement)
			)
		);
		return terms;
	}

	/**
	 * Analyses `text` and hands every produced term to `consumer` as soon as the chain emits it.
	 *
	 * **The input is normalized to Unicode NFC first, and that line must not be removed.** It looks redundant
	 * and it is not: language stemmers compare against **precomposed** characters. `CzechStemmer` documents
	 * "input is expected to be in lowercase, but with diacritical marks" and implements it by testing endings
	 * such as `"ěte"` and switching on the single chars `'á'` and `'ě'`. Fed decomposed text — `'a'` followed by
	 * a combining acute — none of those conditions can ever match. The stemmer would not fail, would not throw,
	 * and nothing would look wrong: it would simply stop stemming every word carrying a diacritic, which in
	 * Czech is the vast majority of them, and `Česká` would stop matching `české`. That is a quality regression
	 * a green test suite does not catch, which is why removing the normalization is guarded by an explicit
	 * NFC/NFD test rather than by this comment alone.
	 *
	 * The same reasoning is also why there is **no** shared normalization between this path and the attribute
	 * filter index: that one stores its string keys in NFD (its own on-disk format, migrated when it changes),
	 * full-text needs NFC, and "unifying normalization in one place" would break one of the two.
	 *
	 * Because the terms and the offsets refer to the normalized text, so does {@link AnalyzedTerm#surfaceForm()}
	 * — it is a slice of the normalized string, not of the caller's original.
	 *
	 * @param text     text to analyse
	 * @param consumer callback receiving each produced term
	 */
	public void analyze(@Nonnull String text, @Nonnull AnalyzedTermConsumer consumer) {
		// NFC on the boundary - see the method javadoc; removing this silently disables stemming of every
		// word with a diacritic
		final String normalizedText = Normalizer.normalize(text, Normalizer.Form.NFC);
		// the Lucene stream protocol below has to be followed exactly - a missing reset() or end() yields
		// empty or truncated output instead of an error
		try (final TokenStream tokenStream = this.analyzer.tokenStream(FIELD_NAME, normalizedText)) {
			final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
			final OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
			final PositionIncrementAttribute positionAttribute =
				tokenStream.addAttribute(PositionIncrementAttribute.class);
			tokenStream.reset();
			while (tokenStream.incrementToken()) {
				final int startOffset = offsetAttribute.startOffset();
				final int endOffset = offsetAttribute.endOffset();
				consumer.accept(
					termAttribute.toString(),
					() -> normalizedText.substring(startOffset, endOffset),
					startOffset,
					endOffset,
					positionAttribute.getPositionIncrement()
				);
			}
			tokenStream.end();
		} catch (IOException e) {
			// the chain reads from an in-memory reader, so this cannot happen for reasons outside our control
			throw new GenericEvitaInternalError(
				"Failed to analyse text with analyzer `" + this.analyzerName + "`: " + e.getMessage(),
				"Failed to analyse text with analyzer `" + this.analyzerName + "`.",
				e
			);
		}
	}

	/**
	 * Releases the Lucene chain's per-thread stream components. Mandatory — see the class javadoc.
	 */
	@Override
	public void close() {
		this.analyzer.close();
	}

	/**
	 * Estimates how many terms a text of the given length will produce, so that the collecting list is sized
	 * once instead of growing. Six characters per term is the rough average of a word plus its separator; the
	 * estimate only has to be in the right order of magnitude.
	 *
	 * @param text text about to be analysed
	 * @return initial capacity for the result list, at least one
	 */
	private static int estimateTermCount(@Nonnull String text) {
		return Math.max(1, text.length() / 6);
	}

}
