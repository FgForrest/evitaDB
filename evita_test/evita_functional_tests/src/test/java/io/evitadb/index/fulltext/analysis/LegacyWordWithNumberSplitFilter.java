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
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.AttributeSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test-side port of the EdeeCMS fulltext client's `WordWithNumberSplitFilter` (`prj_fulltext/lib_fulltext`,
 * package `org.apache.lucene.analysis`, 2020), kept behaviourally identical so that the old client's splitting can be
 * measured next to the Lucene filter evitaDB adopts instead. **Not production code** — the comparison is the
 * point; see `WordNumberSplitAnalysisTest`.
 *
 * What the original does, and this port preserves:
 *
 * - A token that **starts** with a digit run is split into that run and the rest; failing that, a token that
 *   **ends** with a digit run is split into the rest and that run. A digit run inside the token is ignored, and at
 *   most one split is ever made: `123abc345xyz678` yields `123` and `abc345xyz678`.
 * - Both parts are pushed as extra tokens **at the original's position** (position increment `0`) and with the
 *   **original's offsets** — the filter restores the original token's state and overwrites the term only. The
 *   type is reset to Lucene's default type.
 * - The parts come off a stack, so they are emitted in **reverse** order after the original: `123xyz`, `xyz`,
 *   `123`. The old client's documentation lists them the other way round; for an index the order is immaterial.
 * - A token made of a single digit is skipped explicitly (the `ONLY_NUMBERS` pattern is `^\d$`, one digit — not
 *   a digit run). A longer digits-only token passes that check and is left alone anyway, because neither scan
 *   ever finds a non-digit to split at.
 * - The old client applied it through an {@link AnalyzerWrapper} that appends the filter **after the whole
 *   chain** of the wrapped analyzer — after its stemmer and diacritics filter — on both the indexing and the
 *   searching analyzer, switched on by the index-config flag `enableWordNumberAnalyzer` (default `false`).
 *   {@link #appendedTo(Analyzer)} reproduces that wiring.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class LegacyWordWithNumberSplitFilter extends TokenFilter {
	private static final Pattern STARTS_WITH_NUMBER = Pattern.compile("^\\d.+?$");
	private static final Pattern ENDS_WITH_NUMBER = Pattern.compile("^.+?\\d$");
	private static final Pattern ONLY_NUMBERS = Pattern.compile("^\\d$");

	private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
	/**
	 * Captured states of the parts still to be emitted; the original pushes them and pops them in LIFO order.
	 */
	private final Deque<State> stack = new ArrayDeque<>(2);
	/**
	 * Scratch attribute source the parts are assembled in, cloned from the input as the original does.
	 */
	private final AttributeSource save;

	/**
	 * Wraps `input` the way the old client did.
	 *
	 * @param input stream to split
	 */
	LegacyWordWithNumberSplitFilter(@Nonnull TokenStream input) {
		super(input);
		this.save = input.cloneAttributes();
	}

	/**
	 * Appends the filter to the end of `delegate`'s chain, mirroring the old client's `WordWithNumberAnalyzerWrapper`
	 * (global reuse strategy, filter after every other component).
	 *
	 * @param delegate analyzer to wrap; closed together with the wrapper
	 * @return the wrapped analyzer
	 */
	@Nonnull
	static Analyzer appendedTo(@Nonnull Analyzer delegate) {
		return new AnalyzerWrapper(Analyzer.GLOBAL_REUSE_STRATEGY) {
			@Override
			protected Analyzer getWrappedAnalyzer(String fieldName) {
				return delegate;
			}

			@Override
			protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
				return new TokenStreamComponents(
					components.getSource(),
					new LegacyWordWithNumberSplitFilter(components.getTokenStream())
				);
			}

			@Override
			public void close() {
				super.close();
				delegate.close();
			}
		};
	}

	/**
	 * The old client's `detectNumberInWordAndSplitIfFound`, verbatim in behaviour: the parts a token splits into, or
	 * an empty list when it does not split.
	 *
	 * @param word the token's text
	 * @return the number part and the rest, in the order the original returned them
	 */
	@Nonnull
	static List<String> detectNumberInWordAndSplitIfFound(@Nonnull String word) {
		if (ONLY_NUMBERS.matcher(word).matches()) {
			return Collections.emptyList();
		}
		final List<String> variants = new ArrayList<>(2);
		if (STARTS_WITH_NUMBER.matcher(word).matches()) {
			final StringBuilder numberPart = new StringBuilder();
			for (int i = 0; i < word.length(); i++) {
				final char charAt = word.charAt(i);
				if (Character.isDigit(charAt)) {
					numberPart.append(charAt);
				} else {
					variants.add(numberPart.toString());
					if (i + 1 < word.length()) {
						variants.add(word.substring(i));
					}
					break;
				}
			}
		} else if (ENDS_WITH_NUMBER.matcher(word).matches()) {
			final StringBuilder numberPart = new StringBuilder();
			for (int i = word.length() - 1; i >= 0; i--) {
				final char charAt = word.charAt(i);
				if (Character.isDigit(charAt)) {
					numberPart.insert(0, charAt);
				} else {
					variants.add(word.substring(0, i + 1));
					variants.add(numberPart.toString());
					break;
				}
			}
		}
		return variants;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (!this.stack.isEmpty()) {
			restoreState(this.stack.pop());
			return true;
		}
		if (!this.input.incrementToken()) {
			return false;
		}
		final State current = captureState();
		for (final String split : detectNumberInWordAndSplitIfFound(this.termAttribute.toString())) {
			this.save.restoreState(current);
			this.save.addAttribute(CharTermAttribute.class).setEmpty().append(split);
			this.save.addAttribute(TypeAttribute.class).setType(TypeAttribute.DEFAULT_TYPE);
			this.save.addAttribute(PositionIncrementAttribute.class).setPositionIncrement(0);
			this.stack.push(this.save.captureState());
		}
		return true;
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		this.stack.clear();
	}

}
