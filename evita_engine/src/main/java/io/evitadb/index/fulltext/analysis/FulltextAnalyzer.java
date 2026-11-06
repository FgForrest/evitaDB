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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.store.AlreadyClosedException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.text.Normalizer;
import java.util.function.Supplier;

/**
 * A built Lucene analysis chain, owned and driven correctly: it turns a text into the terms an index would
 * store, or a query text into the terms it would be looked up by.
 *
 * It is a **resource holder** — one chain and the name it was built under — and it exists for three reasons beyond
 * mere delegation:
 *
 * 1. it owns the chain's lifecycle. A Lucene `Analyzer` has to be closed, and something has to be the thing
 *    that closes it; see {@link #close()} and the paragraph below;
 * 2. it drives the Lucene stream protocol correctly. `Analyzer` is a streaming API whose call sequence — obtain
 *    the stream, fetch the attributes, `reset()`, iterate `incrementToken()`, `end()`, close — is not optional:
 *    skipping a step yields empty or truncated results rather than an error;
 * 3. it owns the NFC normalization on the boundary, which is a correctness requirement rather than tidiness —
 *    see {@link #analyze(String, AnalyzedTermConsumer)}.
 *
 * Instances are created and shared by {@link FulltextAnalyzerRegistry} by resolved content and locale. Eviction
 * drops cache references without closing chains, because in-flight work may still hold them. Catalog close closes
 * the chains still retained by the cache. A handle belongs to the catalog's lifetime; analyzing through a retained
 * chain after close reports an {@link EvitaInvalidUsageException} from
 * {@link #analyze(String, AnalyzedTermConsumer)}.
 *
 * **Thread safety and lifecycle.** A Lucene `Analyzer` is thread-safe and so is this wrapper, but the mechanism
 * has consequences worth knowing. The analyzer caches its stream components in a `CloseableThreadLocal`, which
 * keeps — besides a weakly-referenced `ThreadLocal` — a weak map keyed by `Thread`, written under `synchronized`.
 * On the platform threads the engine uses today that is exactly right: threads come from bounded pools, get
 * recycled, and the cache actually hits. Both properties would invert on virtual threads — a per-thread cache
 * is pointless when every task gets a fresh thread, and the `synchronized` block pins the carrier — so the seam
 * is left open rather than pre-solved: a custom `ReuseStrategy` can be handed to the `Analyzer` constructor,
 * making any future change local to the factory that builds the chain. What must *not* be skipped is
 * {@link #close()}: explicit close releases retained components promptly. Unreachable analyzers and their components
 * can also be collected together.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextAnalyzer implements Closeable {

	/**
	 * Field name handed to Lucene. A resolved analyzer is shared by fields using its definition and locale,
	 * so one constant name is enough — and it keeps every analyzer on the cheap global component-reuse strategy
	 * instead of a per-field one.
	 */
	private static final String FIELD_NAME = "value";

	/**
	 * Name of the chain this analyzer wraps — the identifier an index records it under.
	 */
	@Nonnull @Getter private final String analyzerName;
	/**
	 * The wrapped Lucene analysis chain.
	 */
	@Nonnull private final Analyzer analyzer;

	/**
	 * Creates a wrapper around an already built Lucene analysis chain.
	 *
	 * @param analyzerName name of the chain
	 * @param analyzer     the Lucene chain; this instance takes over its lifecycle and closes it in
	 *                     {@link #close()}
	 */
	public FulltextAnalyzer(@Nonnull String analyzerName, @Nonnull Analyzer analyzer) {
		this.analyzerName = analyzerName;
		this.analyzer = analyzer;
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
	 * Because the terms and the offsets refer to the normalized text, so does the surface form passed to the
	 * consumer: it is a slice of the normalized string, not of the caller's original.
	 *
	 * @param text     text to analyse
	 * @param consumer callback receiving each produced term
	 * @throws EvitaInvalidUsageException when the registry that owns this analyzer has already been closed, i.e.
	 *                                    when the caller kept the handle past its owner's lifetime
	 */
	public void analyze(@Nonnull String text, @Nonnull AnalyzedTermConsumer consumer) {
		analyzeIndexed(text, (term, preStem, surface, start, end, increment) ->
			consumer.accept(term, surface, start, end, increment));
	}

	/**
	 * Emits final index tokens with normalized lookup characters captured before index stemming. The capture travels
	 * with Lucene token states, so multiple stems at one position retain the correct input, including split tokens
	 * sharing positions and offsets. A missing attribute makes the lane unavailable for this value; a cleared capture
	 * makes it unavailable for that token. Both still emit unchanged postings and yield null capture deterministically.
	 * Only index-fingerprinted operations influence persisted identities.
	 *
	 * This is the driver {@link #analyze(String, AnalyzedTermConsumer)} runs too: the same NFC boundary and Lucene
	 * protocol, so everything that method says about normalization holds here. The capture handed over is borrowed
	 * and the surface form computed on demand - see {@link IndexedTermConsumer}.
	 *
	 * @param text the entire field value element
	 * @param consumer callback receiving the paired tokens and unchanged posting attributes
	 * @throws EvitaInvalidUsageException when the registry that owns this analyzer has already been closed
	 */
	public void analyzeIndexed(@Nonnull String text, @Nonnull IndexedTermConsumer consumer) {
		// NFC on the boundary - see the javadoc of analyze(String, AnalyzedTermConsumer); removing this silently
		// disables stemming of every word with a diacritic
		final String normalizedText = Normalizer.normalize(text, Normalizer.Form.NFC);
		// the Lucene stream protocol below has to be followed exactly - a missing reset() or end() yields
		// empty or truncated output instead of an error
		try (final TokenStream tokenStream = this.analyzer.tokenStream(FIELD_NAME, normalizedText)) {
			final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
			final OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
			final PositionIncrementAttribute positionAttribute =
				tokenStream.addAttribute(PositionIncrementAttribute.class);
			final FulltextPreStemAttribute captured = tokenStream.hasAttribute(FulltextPreStemAttribute.class)
				? tokenStream.getAttribute(FulltextPreStemAttribute.class) : null;
			tokenStream.reset();
			while (tokenStream.incrementToken()) {
				final int startOffset = offsetAttribute.startOffset();
				final int endOffset = offsetAttribute.endOffset();
				final String term = termAttribute.toString();
				final CharSequence preStem = captureOf(captured, term);
				consumer.accept(
					term,
					preStem,
					surfaceOf(normalizedText, startOffset, endOffset, preStem),
					startOffset,
					endOffset,
					positionAttribute.getPositionIncrement()
				);
			}
			tokenStream.end();
		} catch (AlreadyClosedException e) {
			// Lucene reports a released CloseableThreadLocal as an IllegalStateException, which would otherwise
			// escape raw. The caller holds a handle that outlived its registry - the same usage error the
			// registry itself reports when it is asked for an analyzer after close()
			throw new EvitaInvalidUsageException(
				"Full-text analyzer `" + this.analyzerName + "` has already been closed."
			);
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
	 * Returns the lookup characters captured for the current token, or null when the token has none.
	 *
	 * @param captured the chain's capture attribute, null when the chain carries no capture
	 * @param term     the final term of the current token, which a tail capture reuses
	 * @return the borrowed capture, the final term for a tail capture, or null when the chain carries no capture or
	 *         the capture was cleared
	 */
	@Nullable
	private static CharSequence captureOf(@Nullable FulltextPreStemAttribute captured, @Nonnull String term) {
		if (captured == null) {
			return null;
		}
		if (captured.isFinalTerm()) {
			return term.isEmpty() ? null : term;
		}
		return captured.length() == 0 ? null : captured;
	}

	/**
	 * Returns the lazy display slice of a token. A text step that changes the length of the text without correcting
	 * offsets can leave them outside it; a token carrying a capture then falls back to the captured lookup characters,
	 * so the write still records its pair - deterministically, which keeps the removal of the value symmetric. A token
	 * without a capture - every token of a query chain - keeps the plain slice.
	 *
	 * @param text    the NFC-normalized text the offsets are meant to address
	 * @param start   the token's start offset
	 * @param end     the token's end offset, exclusive
	 * @param preStem the token's capture, borrowed for the duration of the callback; null when it has none
	 * @return the supplier of the display spelling
	 */
	@Nonnull
	private static Supplier<String> surfaceOf(
		@Nonnull String text, int start, int end, @Nullable CharSequence preStem
	) {
		if (preStem != null && (start < 0 || start > end || end > text.length())) {
			return preStem::toString;
		}
		return () -> text.substring(start, end);
	}

	/**
	 * Releases the Lucene chain's per-thread stream components. Mandatory — see the class javadoc.
	 */
	@Override
	public void close() {
		this.analyzer.close();
	}

}
