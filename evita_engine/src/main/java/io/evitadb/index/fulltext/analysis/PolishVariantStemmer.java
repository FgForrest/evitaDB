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

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Polish {@link VariantStemmer}: the folded reading of {@link PolishSnowballStemmer}, forking wherever a
 * rule is fold-ambiguous, plus the folded surface itself. Its emitted set contains the folded image of whatever
 * {@link PolishSnowballStemmer} produced on the index side, which the pl_PL lexicon sweep verifies word by word.
 *
 * Polish needs a different walk than Czech or Slovak, because its forks do not guard `if` branches inside a
 * fixed control flow — they decide **which entries exist in the ending table** (and one entry's R1 condition),
 * and the table is scanned longest-suffix-first with first-match-wins. Two configurations differing in one
 * fork can therefore fire entirely different entries. The walk that stays exact is a **constraint scan**:
 * every possible entry of every configuration sits in one merged, length-sorted table, annotated with the flag
 * assignments under which it exists (`needOn`/`needOff` masks). A scan state is a *cell* of configuration
 * space — a partial flag assignment `(onMask, offMask)` plus a table position. When a positionally-matching
 * entry's predicate is compatible with the cell, the cell partitions: the sub-cell satisfying the predicate
 * fires the entry (an outcome), and the rest of the cell continues scanning past it, decomposed into
 * conjunction-shaped sub-cells. A scan that exhausts the table yields the unchanged length for its whole cell.
 * Deterministic checks — the two-character floor, a failed R1 condition — never partition: the entry backtracks
 * identically whether it exists or not, exactly as the underlying stemmer's `continue` does.
 *
 * This constraint machinery is what keeps the set exact even though one fork *can* control several entries:
 * a cell that skipped an `lEndings` entry carries `lEndings=off` in its mask, so any later `lEndings` entry is
 * simply absent for it — the walk cannot manufacture a skip-then-fire path no accented reading has.
 *
 * Every firing action either truncates or writes a single character at the new final position, so a variant
 * is fully described by `(length, final character)` over the untouched input — even simpler than the Czech
 * triple — and the walk allocates nothing after construction.
 *
 * Input is expected lowercased and diacritics-folded; the instance is stateful scratch and **not thread-safe**
 * — one per stream.
 *
 * See `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/` for the measurements and the rejected
 * alternatives behind this design.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PolishVariantStemmer implements VariantStemmer {

	/**
	 * Fold-ambiguity fork bits — one per family of ending-table entries whose existence depends on how the
	 * word was spelled before folding.
	 */
	private static final int L_ENDINGS = 1;
	private static final int NASAL_ENDINGS = 2;
	private static final int SOFT_ENDINGS = 4;
	private static final int OW_ENDING = 8;
	private static final int NASAL_HOMOGRAPHS = 16;
	private static final int SZ_NASAL_ACTIONS = 32;
	private static final int CIE_HOMOGRAPH = 64;

	/**
	 * One entry of the merged table: the suffix, its action (1 delete, 2 → `s`, 3 delete-in-R1-else-`s`,
	 * 4 → `l`, 5 delete then second layer), the R1 condition, and the flag assignment under which the entry
	 * exists in a configuration's table.
	 *
	 * @param suffix     folded suffix
	 * @param action     action code
	 * @param r1Required whether the suffix must start inside R1
	 * @param needOn     flags that must be on for the entry to exist
	 * @param needOff    flags that must be off for the entry to exist
	 */
	private record Entry(@Nonnull String suffix, int action, boolean r1Required, int needOn, int needOff) {
	}

	/**
	 * The merged main table — every entry any configuration can hold, longest suffix first. Within one length
	 * the order is irrelevant: distinct suffixes cannot both match, and same-suffix variants have disjoint
	 * predicates.
	 */
	private static final Entry[] MAIN = mergedMainTable();

	/**
	 * The merged second-layer table (the Snowball `a_1`), tried after an action-5 delete.
	 */
	private static final Entry[] SECOND = {
		new Entry("iejsz", 1, false, 0, 0),
		new Entry("ajac", 1, false, NASAL_ENDINGS, 0),
		new Entry("szac", 2, false, NASAL_ENDINGS, 0),
		new Entry("ac", 1, false, NASAL_ENDINGS, 0),
		new Entry("sz", 1, false, 0, 0),
	};

	/**
	 * The `by`-particle pre-pass strings, identical in every configuration.
	 */
	private static final String[] BY_PARTICLES = {"byscie", "bysmy", "bym", "bys", "by"};

	/**
	 * Hard bound on distinct variants per word. Generous: the scan fires at most one entry per matched
	 * suffix, a word matches only a handful of the table's suffix lengths, and duplicates collapse.
	 */
	private static final int MAX_VARIANTS = 24;
	/**
	 * Hard bound on pending scan cells. Each partition pushes at most two continuation cells (predicates carry
	 * at most two literals), and partitions happen only at matched conditional entries.
	 */
	private static final int MAX_STATES = 32;

	/**
	 * Lengths of the deduplicated variants of the current word.
	 */
	private final int[] lengths = new int[MAX_VARIANTS];
	/**
	 * Effective final character of each variant (the action's written character, or the original character
	 * at `length - 1`), `0` for an empty variant.
	 */
	private final char[] lasts = new char[MAX_VARIANTS];
	/**
	 * Number of valid entries in the variant arrays.
	 */
	private int count;

	/**
	 * Pending main-scan cells: table position and the cell's flag masks.
	 */
	private final int[] stateIndex = new int[MAX_STATES];
	private final int[] stateOn = new int[MAX_STATES];
	private final int[] stateOff = new int[MAX_STATES];
	private int stateTop;

	@Override
	public int stem(@Nonnull char[] s, int len) {
		this.count = 0;
		this.stateTop = 0;

		if (len < 2) {
			// short words are returned unchanged under every reading
			addOutcome(s, len, (char) 0);
			return this.count;
		}

		final int p1 = markR1(s, len);
		// the by-particle pre-pass carries no switches - deterministic for every configuration
		final int afterBy = removeByParticle(s, len, p1);

		// the constraint scan over the merged table, starting from the unconstrained cell
		pushState(0, 0, 0);
		while (this.stateTop > 0) {
			this.stateTop--;
			scanMain(
				s, afterBy, p1,
				this.stateIndex[this.stateTop], this.stateOn[this.stateTop], this.stateOff[this.stateTop]
			);
		}

		// the folded surface itself, so an unstemmable word still matches its own index term
		addOutcome(s, len, (char) 0);
		return this.count;
	}

	@Override
	public int length(int variantIndex) {
		return this.lengths[variantIndex];
	}

	@Override
	public int materialize(int variantIndex, @Nonnull char[] originalWord, @Nonnull char[] destination) {
		final int length = this.lengths[variantIndex];
		System.arraycopy(originalWord, 0, destination, 0, length);
		if (length >= 1) {
			destination[length - 1] = this.lasts[variantIndex];
		}
		return length;
	}

	/**
	 * Scans the merged main table for one cell of configuration space, partitioning at every matched
	 * conditional entry — see the class javadoc for the mechanism.
	 *
	 * @param s       input buffer, read only
	 * @param len     length after the by-particle pre-pass
	 * @param p1      R1 start
	 * @param fromIdx table position to resume at
	 * @param on      flags this cell has committed on
	 * @param off     flags this cell has committed off
	 */
	private void scanMain(@Nonnull char[] s, int len, int p1, int fromIdx, int on, int off) {
		for (int i = fromIdx; i < MAIN.length; i++) {
			final Entry entry = MAIN[i];
			if ((entry.needOn() & off) != 0 || (entry.needOff() & on) != 0) {
				// the entry exists in no configuration of this cell
				continue;
			}
			final int pos = len - entry.suffix().length();
			if (pos < 2 || !regionMatches(s, pos, entry.suffix())) {
				continue;
			}
			if (entry.r1Required() && pos < p1) {
				// deterministic condition failure - configurations with and without the entry both backtrack
				continue;
			}
			// the sub-cell satisfying the entry's predicate fires here
			fire(s, entry, pos, p1, on | entry.needOn(), off | entry.needOff());
			// the rest of the cell continues past the entry, decomposed into conjunction cells
			final int freeOn = entry.needOn() & ~on;
			final int freeOff = entry.needOff() & ~off;
			if (freeOn == 0 && freeOff == 0) {
				// the predicate was fully implied - the whole cell fired, nothing continues
				return;
			}
			int accumulatedOn = on;
			int accumulatedOff = off;
			for (int bits = freeOn; bits != 0; bits &= bits - 1) {
				final int bit = bits & -bits;
				// cell where this required-on flag is off instead (and the previous literals hold)
				pushState(i + 1, accumulatedOn, accumulatedOff | bit);
				accumulatedOn |= bit;
			}
			for (int bits = freeOff; bits != 0; bits &= bits - 1) {
				final int bit = bits & -bits;
				// cell where this required-off flag is on instead
				pushState(i + 1, accumulatedOn | bit, accumulatedOff);
				accumulatedOff |= bit;
			}
			return;
		}
		// scan exhausted - every configuration of this cell leaves the word at the pre-pass length
		addOutcome(s, len, (char) 0);
	}

	/**
	 * Applies one fired entry's action and records the outcome; an action-5 delete continues into the
	 * second-layer scan with the firing sub-cell's masks.
	 *
	 * @param s     input buffer, read only
	 * @param entry the fired entry
	 * @param pos   position the suffix starts at
	 * @param p1    R1 start
	 * @param on    flags the firing sub-cell has committed on
	 * @param off   flags the firing sub-cell has committed off
	 */
	private void fire(@Nonnull char[] s, @Nonnull Entry entry, int pos, int p1, int on, int off) {
		switch (entry.action()) {
			case 1:
				addOutcome(s, pos, (char) 0);
				break;
			case 2:
				addOutcome(s, pos + 1, 's');
				break;
			case 3:
				// the szą action: delete in R1, rewrite to `s` below it - deterministic given the word
				if (pos >= p1) {
					addOutcome(s, pos, (char) 0);
				} else {
					addOutcome(s, pos + 1, 's');
				}
				break;
			case 4:
				addOutcome(s, pos + 1, 'l');
				break;
			case 5:
				scanSecond(s, pos, 0, on, off);
				break;
			default:
				throw new GenericEvitaInternalError("Unknown ending action " + entry.action() + ".");
		}
	}

	/**
	 * The second-layer constraint scan after an action-5 delete — same partitioning as the main scan, small
	 * enough for direct recursion (its predicates carry a single literal).
	 *
	 * @param s       input buffer, read only
	 * @param len     length after the action-5 delete
	 * @param fromIdx table position to resume at
	 * @param on      flags this cell has committed on
	 * @param off     flags this cell has committed off
	 */
	private void scanSecond(@Nonnull char[] s, int len, int fromIdx, int on, int off) {
		for (int i = fromIdx; i < SECOND.length; i++) {
			final Entry entry = SECOND[i];
			if ((entry.needOn() & off) != 0 || (entry.needOff() & on) != 0) {
				continue;
			}
			final int pos = len - entry.suffix().length();
			if (pos < 2 || !regionMatches(s, pos, entry.suffix())) {
				continue;
			}
			if (entry.action() == 2) {
				addOutcome(s, pos + 1, 's');
			} else {
				addOutcome(s, pos, (char) 0);
			}
			final int freeOn = entry.needOn() & ~on;
			if (freeOn == 0) {
				return;
			}
			// single-literal predicates only: the continuation is the cell with that flag off
			scanSecond(s, len, i + 1, on, off | freeOn);
			return;
		}
		// nothing fired in the second layer - the action-5 delete stands alone
		addOutcome(s, len, (char) 0);
	}

	/**
	 * Records one outcome as `(length, effective final character)`, deduplicated — equal pairs mean equal
	 * variants because every variant shares the untouched input prefix.
	 *
	 * @param s        input buffer, read only
	 * @param length   outcome length
	 * @param override character the action wrote at `length - 1`, or `0` for a pure truncation
	 */
	private void addOutcome(@Nonnull char[] s, int length, char override) {
		final char last = override != 0 ? override : (length > 0 ? s[length - 1] : (char) 0);
		for (int i = 0; i < this.count; i++) {
			if (this.lengths[i] == length && this.lasts[i] == last) {
				return;
			}
		}
		if (this.count == MAX_VARIANTS) {
			throw new GenericEvitaInternalError(
				"More than " + MAX_VARIANTS + " variants for one word - the variant walk has diverged "
					+ "from the Polish stemming rules."
			);
		}
		this.lengths[this.count] = length;
		this.lasts[this.count] = last;
		this.count++;
	}

	/**
	 * Pushes one pending main-scan cell.
	 *
	 * @param index table position to resume at
	 * @param on    flags the cell has committed on
	 * @param off   flags the cell has committed off
	 */
	private void pushState(int index, int on, int off) {
		if (this.stateTop == MAX_STATES) {
			throw new GenericEvitaInternalError(
				"More than " + MAX_STATES + " pending scan cells - the variant walk has diverged from "
					+ "the Polish stemming rules."
			);
		}
		this.stateIndex[this.stateTop] = index;
		this.stateOn[this.stateTop] = on;
		this.stateOff[this.stateTop] = off;
		this.stateTop++;
	}

	/**
	 * Computes the R1 start — the Snowball `mark_regions` step, read over the folded alphabet.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return start position of R1, or `len` when the word has none
	 */
	private static int markR1(@Nonnull char[] s, int len) {
		int i = 0;
		while (i < len && !isVowel(s[i])) {
			i++;
		}
		if (i >= len) {
			return len;
		}
		i++;
		while (i < len && isVowel(s[i])) {
			i++;
		}
		if (i >= len) {
			return len;
		}
		return i + 1;
	}

	/**
	 * Strips one `by`-particle sitting wholly inside R1 — the Snowball `remove_by_particle` step.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @param p1  R1 start
	 * @return length after the particle was stripped; unchanged when none matched
	 */
	private static int removeByParticle(@Nonnull char[] s, int len, int p1) {
		for (final String particle : BY_PARTICLES) {
			final int pos = len - particle.length();
			if (pos >= p1 && regionMatches(s, pos, particle)) {
				return pos;
			}
		}
		return len;
	}

	/**
	 * Tells whether the character is a folded Polish vowel.
	 *
	 * @param c character to test
	 * @return true for `a`, `e`, `i`, `o`, `u`, `y`
	 */
	private static boolean isVowel(char c) {
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
	}

	/**
	 * Tells whether the buffer carries `suffix` starting at `pos`.
	 *
	 * @param s      input buffer
	 * @param pos    position the suffix would start at
	 * @param suffix suffix text
	 * @return true when the characters match exactly
	 */
	private static boolean regionMatches(@Nonnull char[] s, int pos, @Nonnull String suffix) {
		for (int i = 0; i < suffix.length(); i++) {
			if (s[pos + i] != suffix.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Builds the merged main table: the union of every reading's entries, each annotated with the fork
	 * assignment under which it exists.
	 * Same-suffix variants (`sza`, `sze`, `e`, `ac`) carry disjoint predicates that partition the
	 * configuration space exactly as the constructor's `if`/`else if` chains do.
	 *
	 * @return the merged table, longest suffix first
	 */
	@Nonnull
	private static Entry[] mergedMainTable() {
		final List<Entry> table = new ArrayList<>(160);
		// plain-spelled entries, present in every configuration
		table.add(new Entry("a", 1, true, 0, 0));
		table.add(new Entry("ia", 1, true, 0, 0));
		table.add(new Entry("iejsza", 1, false, 0, 0));
		table.add(new Entry("icie", 1, false, 0, 0));
		table.add(new Entry("ajcie", 1, false, 0, 0));
		table.add(new Entry("iejsze", 1, false, 0, 0));
		table.add(new Entry("ach", 1, true, 0, 0));
		table.add(new Entry("iach", 1, true, 0, 0));
		table.add(new Entry("ich", 5, false, 0, 0));
		table.add(new Entry("ych", 5, false, 0, 0));
		table.add(new Entry("i", 1, true, 0, 0));
		table.add(new Entry("ali", 1, false, 0, 0));
		table.add(new Entry("ieli", 1, false, 0, 0));
		table.add(new Entry("ili", 1, false, 0, 0));
		table.add(new Entry("ami", 1, true, 0, 0));
		table.add(new Entry("iami", 1, true, 0, 0));
		table.add(new Entry("imi", 5, false, 0, 0));
		table.add(new Entry("ymi", 5, false, 0, 0));
		table.add(new Entry("owi", 1, true, 0, 0));
		table.add(new Entry("iowi", 1, true, 0, 0));
		table.add(new Entry("aj", 1, false, 0, 0));
		table.add(new Entry("ej", 5, false, 0, 0));
		table.add(new Entry("iej", 5, false, 0, 0));
		table.add(new Entry("am", 1, false, 0, 0));
		table.add(new Entry("em", 1, true, 0, 0));
		table.add(new Entry("iem", 1, true, 0, 0));
		table.add(new Entry("im", 5, false, 0, 0));
		table.add(new Entry("om", 1, true, 0, 0));
		table.add(new Entry("iom", 1, true, 0, 0));
		table.add(new Entry("ym", 5, false, 0, 0));
		table.add(new Entry("o", 1, true, 0, 0));
		table.add(new Entry("ego", 5, false, 0, 0));
		table.add(new Entry("iego", 5, false, 0, 0));
		table.add(new Entry("u", 1, true, 0, 0));
		table.add(new Entry("iu", 1, true, 0, 0));
		table.add(new Entry("y", 5, false, 0, 0));
		table.add(new Entry("amy", 1, false, 0, 0));
		table.add(new Entry("emy", 1, false, 0, 0));
		table.add(new Entry("imy", 1, false, 0, 0));
		table.add(new Entry("asz", 1, false, 0, 0));
		table.add(new Entry("esz", 1, false, 0, 0));
		table.add(new Entry("isz", 1, false, 0, 0));
		table.add(new Entry("emu", 5, false, 0, 0));
		table.add(new Entry("iemu", 5, false, 0, 0));
		// the three-way sza/sze readings - disjoint predicates partitioning the configuration space
		table.add(new Entry("sza", 3, false, SZ_NASAL_ACTIONS, 0));
		table.add(new Entry("sza", 1, false, 0, SZ_NASAL_ACTIONS | NASAL_HOMOGRAPHS));
		table.add(new Entry("sze", 2, false, SZ_NASAL_ACTIONS, 0));
		table.add(new Entry("sze", 1, false, 0, SZ_NASAL_ACTIONS | NASAL_HOMOGRAPHS));
		// e is R1-gated as plain `e`, unconditional as folded `ę` - the one action conflict of the main table
		table.add(new Entry("e", 1, true, 0, NASAL_ENDINGS));
		table.add(new Entry("e", 1, false, NASAL_ENDINGS, 0));
		// the nasal-homograph strings, read as the plain endings only with the switch off
		table.add(new Entry("ie", 1, true, 0, NASAL_HOMOGRAPHS));
		table.add(new Entry("acie", 1, false, 0, NASAL_HOMOGRAPHS));
		table.add(new Entry("ecie", 1, false, 0, NASAL_HOMOGRAPHS));
		table.add(new Entry("cie", 1, false, 0, CIE_HOMOGRAPH));
		// the ł-spelled past-tense system
		table.add(new Entry("ala", 1, false, L_ENDINGS, 0));
		table.add(new Entry("iala", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ila", 1, false, L_ENDINGS, 0));
		table.add(new Entry("alam", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialam", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ilam", 1, false, L_ENDINGS, 0));
		table.add(new Entry("alem", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialem", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ilem", 1, false, L_ENDINGS, 0));
		table.add(new Entry("alo", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialo", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ilo", 1, false, L_ENDINGS, 0));
		table.add(new Entry("aly", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialy", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ily", 1, false, L_ENDINGS, 0));
		table.add(new Entry("al", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ial", 1, false, L_ENDINGS, 0));
		table.add(new Entry("il", 1, false, L_ENDINGS, 0));
		table.add(new Entry("las", 4, false, L_ENDINGS, 0));
		table.add(new Entry("alas", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialas", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ilas", 1, false, L_ENDINGS, 0));
		table.add(new Entry("les", 4, false, L_ENDINGS, 0));
		table.add(new Entry("ales", 1, false, L_ENDINGS, NASAL_HOMOGRAPHS));
		table.add(new Entry("iales", 1, false, L_ENDINGS, 0));
		table.add(new Entry("iles", 1, false, L_ENDINGS, 0));
		table.add(new Entry("lyscie", 4, false, L_ENDINGS, 0));
		table.add(new Entry("alyscie", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialyscie", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ilyscie", 1, false, L_ENDINGS, 0));
		table.add(new Entry("lysmy", 4, false, L_ENDINGS, 0));
		table.add(new Entry("alysmy", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ialysmy", 1, false, L_ENDINGS, 0));
		table.add(new Entry("ilysmy", 1, false, L_ENDINGS, 0));
		// the ą/ę-spelled endings
		table.add(new Entry("aca", 1, false, NASAL_ENDINGS, 0));
		table.add(new Entry("ajaca", 1, false, NASAL_ENDINGS, 0));
		table.add(new Entry("szaca", 2, false, NASAL_ENDINGS, 0));
		table.add(new Entry("ajac", 1, false, NASAL_ENDINGS, 0));
		table.add(new Entry("ace", 1, false, NASAL_ENDINGS, 0));
		table.add(new Entry("ajace", 1, false, NASAL_ENDINGS, 0));
		table.add(new Entry("szace", 2, false, NASAL_ENDINGS, 0));
		table.add(new Entry("aja", 1, false, NASAL_ENDINGS, 0));
		// ac exists when nasal OR soft endings are on - a disjunction, modelled as two disjoint cells
		table.add(new Entry("ac", 1, false, NASAL_ENDINGS, 0));
		table.add(new Entry("ac", 1, false, SOFT_ENDINGS, NASAL_ENDINGS));
		// the ś/ć-spelled endings
		table.add(new Entry("iec", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("ic", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("asc", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("esc", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("liscie", 4, false, SOFT_ENDINGS, 0));
		table.add(new Entry("aliscie", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("ieliscie", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("iliscie", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("lismy", 4, false, SOFT_ENDINGS, 0));
		table.add(new Entry("alismy", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("ielismy", 1, false, SOFT_ENDINGS, 0));
		table.add(new Entry("ilismy", 1, false, SOFT_ENDINGS, 0));
		// genitive-plural ów
		table.add(new Entry("ow", 1, true, OW_ENDING, 0));

		table.sort(Comparator.comparingInt((Entry entry) -> entry.suffix().length()).reversed());
		return table.toArray(Entry[]::new);
	}

}
