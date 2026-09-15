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

import org.apache.lucene.util.ArrayUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The **branching** form of the M7 Romanian hypothesis set: produces the exact union of all 512 (2⁹)
 * {@link FoldedRomanianStemmer} configurations plus the folded surface, without running them.
 *
 * Romanian needs the heaviest walk of the four languages, because its nine switches split into two kinds and
 * its pipeline rewrites the buffer between steps:
 *
 * - **Five step gates** (`step0`, `combo`, `a_3`, `verb`, `vowel`) each turn a whole pipeline stage on or off.
 *   The walk handles them as a **staged worklist**: every state (a buffer copy, its length, and the
 *   `standardRemoved` sentinel that decides whether the verb step may run) forks at each gate into a skipped
 *   and an applied successor, and successors are deduplicated immediately — a stage that matches nothing
 *   collapses its two branches back into one state, which is what keeps the state count near one for ordinary
 *   words. States carry real buffer copies (pooled, reused across calls) because the combo, `a_3` and step-0
 *   actions rewrite characters (`icator`→`ic`, `ism`→`ist`), so a hypothesis is no longer prefix + tail chars
 *   the way the Czech and Slovak walks could store it.
 * - **Four rule switches** fork *inside* a stage. `tiuneRewrite` guards one decision in the `a_3` scan (fire
 *   versus that scan's abort) and forks at most once. `sVerbEndings`, `aVerbEndings` and `amUnconditional`
 *   decide which entries the verb table holds — and unlike everywhere else in the four ports, one of these
 *   flags really can be consulted twice on one path (`asesi` skipped → the shorter `sesi` matches, both
 *   `sVerbEndings`-gated), so the verb scan is a **constraint scan** like the Polish walk's: cells of
 *   configuration space carry committed flag masks, a matched entry partitions its cell, and an entry whose
 *   predicate contradicts the cell's commitments is simply absent for it.
 *
 * The Snowball prelude and regions are computed **once**: intervocalic `i`/`u` marking and `RV`/`R1`/`R2` are
 * identical for every configuration (every edit happens at the tail, so the positions stay valid — the same
 * argument the flat port makes), and the markers are lowercased per final outcome at materialization.
 *
 * **Prototype, test scope only.** Not thread-safe — one instance per stream. Zero steady-state allocation
 * apart from lazily grown pooled buffers.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class BranchingFoldedRomanianStemmer implements BranchingStemmer {

	/**
	 * One suffix entry of a static table — mirror of the flat port's record.
	 *
	 * @param suffix folded suffix, matched at the end of the current buffer
	 * @param action action code, interpreted by the step that owns the table
	 */
	private record Ending(@Nonnull String suffix, int action) {
	}

	/**
	 * One entry of the merged verb table: the suffix, its action (1 delete when preceded inside RV by a
	 * non-vowel or `u`, 2 delete unconditionally) and the local flag assignment under which the entry exists.
	 *
	 * @param suffix  folded suffix
	 * @param action  action code
	 * @param needOn  local flags that must be on for the entry to exist
	 * @param needOff local flags that must be off for the entry to exist
	 */
	private record VerbEntry(@Nonnull String suffix, int action, int needOn, int needOff) {
	}

	/**
	 * Local flag bits of the verb-scan cells — meaningful only inside one verb scan; the step gates never need
	 * tracking because each is consulted exactly once, at its own stage.
	 */
	private static final int S_VERB_ENDINGS = 1;
	private static final int A_VERB_ENDINGS = 2;
	private static final int AM_UNCONDITIONAL = 4;

	/**
	 * Step 0 endings — verbatim mirror of the flat port's `STEP0_ENDINGS`.
	 */
	private static final Ending[] STEP0_ENDINGS = byLengthDescending(
		new Ending("ea", 3), new Ending("atia", 7), new Ending("aua", 2), new Ending("iua", 4),
		new Ending("atie", 7), new Ending("ele", 3), new Ending("ile", 5), new Ending("iile", 4),
		new Ending("iei", 4), new Ending("atei", 6), new Ending("ii", 4), new Ending("ului", 1),
		new Ending("ul", 1), new Ending("elor", 3), new Ending("ilor", 4), new Ending("iilor", 4)
	);

	/**
	 * Combo-suffix endings — verbatim mirror of the flat port's `COMBO_ENDINGS`.
	 */
	private static final Ending[] COMBO_ENDINGS = byLengthDescending(
		new Ending("icala", 4), new Ending("iciva", 4), new Ending("ativa", 5), new Ending("itiva", 6),
		new Ending("icale", 4), new Ending("atiune", 5), new Ending("itiune", 6), new Ending("atoare", 5),
		new Ending("itoare", 6), new Ending("icitate", 4), new Ending("abilitate", 1),
		new Ending("ibilitate", 2), new Ending("ivitate", 3), new Ending("icive", 4), new Ending("ative", 5),
		new Ending("itive", 6), new Ending("icali", 4), new Ending("atori", 5), new Ending("icatori", 4),
		new Ending("itori", 6), new Ending("icitati", 4), new Ending("abilitati", 1), new Ending("ivitati", 3),
		new Ending("icivi", 4), new Ending("ativi", 5), new Ending("itivi", 6), new Ending("icitai", 4),
		new Ending("abilitai", 1), new Ending("ivitai", 3), new Ending("ical", 4), new Ending("ator", 5),
		new Ending("icator", 4), new Ending("itor", 6), new Ending("iciv", 4), new Ending("ativ", 5),
		new Ending("itiv", 6)
	);

	/**
	 * Standard-suffix endings — verbatim mirror of the flat port's `STANDARD_ENDINGS`.
	 */
	private static final Ending[] STANDARD_ENDINGS = byLengthDescending(
		new Ending("ica", 1), new Ending("abila", 1), new Ending("ibila", 1), new Ending("oasa", 1),
		new Ending("ata", 1), new Ending("ita", 1), new Ending("anta", 1), new Ending("ista", 3),
		new Ending("uta", 1), new Ending("iva", 1), new Ending("ic", 1), new Ending("ice", 1),
		new Ending("abile", 1), new Ending("ibile", 1), new Ending("isme", 3), new Ending("iune", 2),
		new Ending("oase", 1), new Ending("ate", 1), new Ending("itate", 1), new Ending("ite", 1),
		new Ending("ante", 1), new Ending("iste", 3), new Ending("ute", 1), new Ending("ive", 1),
		new Ending("ici", 1), new Ending("abili", 1), new Ending("ibili", 1), new Ending("iuni", 2),
		new Ending("atori", 1), new Ending("osi", 1), new Ending("ati", 1), new Ending("itati", 1),
		new Ending("iti", 1), new Ending("anti", 1), new Ending("isti", 3), new Ending("uti", 1),
		new Ending("ivi", 1), new Ending("itai", 1), new Ending("abil", 1), new Ending("ibil", 1),
		new Ending("ism", 3), new Ending("ator", 1), new Ending("os", 1), new Ending("at", 1),
		new Ending("it", 1), new Ending("ant", 1), new Ending("ist", 3), new Ending("ut", 1),
		new Ending("iv", 1)
	);

	/**
	 * Vowel-suffix endings — verbatim mirror of the flat port's `VOWEL_ENDINGS`.
	 */
	private static final Ending[] VOWEL_ENDINGS = byLengthDescending(
		new Ending("ie", 1), new Ending("a", 1), new Ending("e", 1), new Ending("i", 1)
	);

	/**
	 * The merged verb table — every entry any configuration's verb table can hold, annotated with the local
	 * flag assignment of its existence, longest suffix first. The two `am` variants carry disjoint predicates,
	 * exactly as the flat constructor's ternary selects one action per configuration.
	 */
	private static final VerbEntry[] VERB = mergedVerbTable();

	/**
	 * Hard bound on live states per stage and on recorded outcomes. Generous — gate forks collapse through
	 * dedup whenever their step matched nothing, and ordinary words hold one to four states throughout.
	 */
	private static final int MAX_STATES = 64;

	/**
	 * State buffers of the current stage, pooled and reused across calls; swapped with the `next` set between
	 * stages by reference.
	 */
	private char[][] currentBuffers = new char[MAX_STATES][];
	private int[] currentLengths = new int[MAX_STATES];
	private boolean[] currentRemoved = new boolean[MAX_STATES];
	private int currentCount;

	/**
	 * State buffers the running stage emits into.
	 */
	private char[][] nextBuffers = new char[MAX_STATES][];
	private int[] nextLengths = new int[MAX_STATES];
	private boolean[] nextRemoved = new boolean[MAX_STATES];
	private int nextCount;

	/**
	 * Final outcomes — unmarked hypothesis texts, pooled.
	 */
	private final char[][] outcomeBuffers = new char[MAX_STATES][];
	private final int[] outcomeLengths = new int[MAX_STATES];
	private int outcomeCount;

	/**
	 * Pending verb-scan cells: table position and the cell's local flag masks.
	 */
	private final int[] verbIndex = new int[MAX_STATES];
	private final int[] verbOn = new int[MAX_STATES];
	private final int[] verbOff = new int[MAX_STATES];
	private int verbTop;

	/**
	 * The marked master copy of the current word — intervocalic `i`/`u` uppercased, shared by every state.
	 */
	private char[] master = new char[16];
	/**
	 * Scratch buffer the mutating stage lanes build their result in before it is copied into a state slot.
	 */
	private char[] scratch = new char[16];

	@Override
	public int hypothesize(@Nonnull char[] s, int len) {
		this.outcomeCount = 0;

		// the prelude and regions are configuration-independent: mark once, share everywhere
		if (this.master.length < len) {
			this.master = new char[ArrayUtil.oversize(len, Character.BYTES)];
			this.scratch = new char[this.master.length];
		}
		System.arraycopy(s, 0, this.master, 0, len);
		markIntervocalic(this.master, len);
		final int pV = markRV(this.master, len);
		final int p1 = markAfterVowelConsonant(this.master, len, 0);
		final int p2 = markAfterVowelConsonant(this.master, len, p1);

		this.nextCount = 0;
		emitNext(this.master, len, false);
		swapStages();

		// stage 1 - the step-0 gate
		for (int i = 0; i < this.currentCount; i++) {
			emitNext(this.currentBuffers[i], this.currentLengths[i], this.currentRemoved[i]);
			emitStep0Applied(i, p1);
		}
		swapStages();

		// stage 2 - the combo gate (the repeat loop is deterministic once the gate is on)
		for (int i = 0; i < this.currentCount; i++) {
			emitNext(this.currentBuffers[i], this.currentLengths[i], this.currentRemoved[i]);
			emitComboApplied(i, p1);
		}
		swapStages();

		// stage 3 - the a_3 gate, with the tiuneRewrite fork inside the applied lane
		for (int i = 0; i < this.currentCount; i++) {
			emitNext(this.currentBuffers[i], this.currentLengths[i], this.currentRemoved[i]);
			emitA3Applied(i, p2);
		}
		swapStages();

		// stage 4 - the verb gate; the step runs only where no standard suffix was removed, and its scan runs
		// under local flag-cell constraints because sVerb/aVerb/amUnconditional gate table entries
		for (int i = 0; i < this.currentCount; i++) {
			emitNext(this.currentBuffers[i], this.currentLengths[i], this.currentRemoved[i]);
			if (!this.currentRemoved[i]) {
				verbScanOutcomes(i, pV);
			}
		}
		swapStages();

		// stage 5 - the vowel gate (deterministic once on)
		for (int i = 0; i < this.currentCount; i++) {
			emitNext(this.currentBuffers[i], this.currentLengths[i], this.currentRemoved[i]);
			emitVowelApplied(i, pV);
		}
		swapStages();

		// finalize: unmark each state into an outcome, then the folded surface (the original, unmarked word)
		for (int i = 0; i < this.currentCount; i++) {
			addOutcomeUnmarked(this.currentBuffers[i], this.currentLengths[i]);
		}
		addOutcomePlain(s, len);
		return this.outcomeCount;
	}

	@Override
	public int length(int hypothesisIndex) {
		return this.outcomeLengths[hypothesisIndex];
	}

	@Override
	public int materialize(int hypothesisIndex, @Nonnull char[] originalWord, @Nonnull char[] destination) {
		final int length = this.outcomeLengths[hypothesisIndex];
		System.arraycopy(this.outcomeBuffers[hypothesisIndex], 0, destination, 0, length);
		return length;
	}

	/**
	 * Emits the applied lane of the step-0 gate: the deterministic step-0 table on the state's buffer. Mirror
	 * of the flat `step0`, abort semantics included; a lane that matches nothing emits the unchanged state,
	 * which the dedup collapses with the skipped lane.
	 *
	 * @param stateIndex current-stage state to apply the step to
	 * @param p1         R1 start
	 */
	private void emitStep0Applied(int stateIndex, int p1) {
		final char[] source = this.currentBuffers[stateIndex];
		final int len = this.currentLengths[stateIndex];
		final boolean removed = this.currentRemoved[stateIndex];
		for (final Ending ending : STEP0_ENDINGS) {
			final int pos = len - ending.suffix().length();
			if (pos >= 0 && regionMatches(source, pos, ending.suffix())) {
				if (p1 > pos) {
					// the original aborts the whole step on a region failure here
					emitNext(source, len, removed);
					return;
				}
				switch (ending.action()) {
					case 1 -> emitNext(source, pos, removed);
					case 2 -> emitRewritten(source, pos, "a", removed);
					case 3 -> emitRewritten(source, pos, "e", removed);
					case 4 -> emitRewritten(source, pos, "i", removed);
					case 5 -> {
						// `ile` is kept when it follows `ab` (abile is derivational, not a plural)
						if (pos >= 2 && source[pos - 2] == 'a' && source[pos - 1] == 'b') {
							emitNext(source, len, removed);
						} else {
							emitRewritten(source, pos, "i", removed);
						}
					}
					case 6 -> emitRewritten(source, pos, "at", removed);
					case 7 -> emitRewritten(source, pos, "ati", removed);
					default -> throw new IllegalStateException(
						"Unknown step-0 action " + ending.action() + "."
					);
				}
				return;
			}
		}
		emitNext(source, len, removed);
	}

	/**
	 * Emits the applied lane of the combo gate: the repeat loop run to its fixpoint on a scratch copy, setting
	 * the `standardRemoved` sentinel when any round fired. Mirror of the flat driver's `while` plus
	 * `comboSuffix`, backtracking semantics included.
	 *
	 * @param stateIndex current-stage state to apply the step to
	 * @param p1         R1 start
	 */
	private void emitComboApplied(int stateIndex, int p1) {
		int len = this.currentLengths[stateIndex];
		System.arraycopy(this.currentBuffers[stateIndex], 0, this.scratch, 0, len);
		boolean fired = false;
		rounds:
		while (true) {
			for (final Ending ending : COMBO_ENDINGS) {
				final int pos = len - ending.suffix().length();
				if (pos >= 0 && regionMatches(this.scratch, pos, ending.suffix())) {
					if (p1 > pos) {
						// the port backtracks to shorter entries where Snowball aborts - mirror it
						continue;
					}
					final String replacement = switch (ending.action()) {
						case 1 -> "abil";
						case 2 -> "ibil";
						case 3 -> "iv";
						case 4 -> "ic";
						case 5 -> "at";
						case 6 -> "it";
						default -> throw new IllegalStateException(
							"Unknown combo action " + ending.action() + "."
						);
					};
					for (int i = 0; i < replacement.length(); i++) {
						this.scratch[pos + i] = replacement.charAt(i);
					}
					len = pos + replacement.length();
					fired = true;
					continue rounds;
				}
			}
			break;
		}
		emitNext(this.scratch, len, this.currentRemoved[stateIndex] || fired);
	}

	/**
	 * Emits the applied lane of the `a_3` gate. Deterministic except for one decision: a matched `iune`/`iuni`
	 * preceded by `t` forks on `tiuneRewrite` — fire (a removal) versus the scan's abort (`-1`, no removal).
	 * Mirror of the flat `standardSuffix`, backtracking and abort semantics included.
	 *
	 * @param stateIndex current-stage state to apply the step to
	 * @param p2         R2 start
	 */
	private void emitA3Applied(int stateIndex, int p2) {
		final char[] source = this.currentBuffers[stateIndex];
		final int len = this.currentLengths[stateIndex];
		final boolean removed = this.currentRemoved[stateIndex];
		for (final Ending ending : STANDARD_ENDINGS) {
			final int pos = len - ending.suffix().length();
			if (pos >= 0 && regionMatches(source, pos, ending.suffix())) {
				if (p2 > pos) {
					// backtrack to shorter entries, as the flat port does
					continue;
				}
				switch (ending.action()) {
					case 1 -> emitNext(source, pos, true);
					case 2 -> {
						if (pos > 0 && source[pos - 1] == 't') {
							// tiuneRewrite on fires; off takes the scan's abort with no removal
							emitNext(source, pos, true);
						}
						emitNext(source, len, removed);
					}
					case 3 -> {
						// the `ist` rewrite counts as a removal even when the length stays (`ism` -> `ist`)
						System.arraycopy(source, 0, this.scratch, 0, pos);
						this.scratch[pos] = 'i';
						this.scratch[pos + 1] = 's';
						this.scratch[pos + 2] = 't';
						emitNext(this.scratch, pos + 3, true);
					}
					default -> throw new IllegalStateException(
						"Unknown standard-suffix action " + ending.action() + "."
					);
				}
				return;
			}
		}
		// nothing fired - identical to the skipped lane
		emitNext(source, len, removed);
	}

	/**
	 * Emits every outcome of the verb step for one state — the constraint scan over the merged verb table.
	 * Each fired cell truncates (verb actions never rewrite characters); an exhausted cell leaves the length
	 * unchanged, collapsing with the skipped-gate lane. Mirror of the flat `verbSuffix`, its RV window and
	 * action-1 condition included.
	 *
	 * @param stateIndex current-stage state the verb step runs on
	 * @param pV         RV start
	 */
	private void verbScanOutcomes(int stateIndex, int pV) {
		final char[] source = this.currentBuffers[stateIndex];
		final int len = this.currentLengths[stateIndex];
		final boolean removed = this.currentRemoved[stateIndex];
		this.verbTop = 0;
		pushVerbCell(0, 0, 0);
		while (this.verbTop > 0) {
			this.verbTop--;
			final int from = this.verbIndex[this.verbTop];
			final int on = this.verbOn[this.verbTop];
			final int off = this.verbOff[this.verbTop];
			boolean fired = false;
			for (int i = from; i < VERB.length; i++) {
				final VerbEntry entry = VERB[i];
				if ((entry.needOn() & off) != 0 || (entry.needOff() & on) != 0) {
					// the entry exists in no configuration of this cell
					continue;
				}
				final int pos = len - entry.suffix().length();
				if (pos < 0 || !regionMatches(source, pos, entry.suffix())) {
					continue;
				}
				if (pos < pV) {
					// not a match inside the RV window - deterministic, shorter entries are still tried
					continue;
				}
				if (entry.action() == 1
					&& !(pos > pV && (!isVowel(source[pos - 1]) || source[pos - 1] == 'u'))) {
					// the conditional delete's condition failed - deterministic backtrack
					continue;
				}
				// the sub-cell holding this entry fires: a pure truncation to pos
				emitNext(source, pos, removed);
				fired = true;
				// the rest of the cell continues past the entry, decomposed into conjunction cells
				final int freeOn = entry.needOn() & ~on;
				final int freeOff = entry.needOff() & ~off;
				int accumulatedOn = on;
				int accumulatedOff = off;
				for (int bits = freeOn; bits != 0; bits &= bits - 1) {
					final int bit = bits & -bits;
					pushVerbCell(i + 1, accumulatedOn, accumulatedOff | bit);
					accumulatedOn |= bit;
				}
				for (int bits = freeOff; bits != 0; bits &= bits - 1) {
					final int bit = bits & -bits;
					pushVerbCell(i + 1, accumulatedOn | bit, accumulatedOff);
					accumulatedOff |= bit;
				}
				break;
			}
			if (!fired) {
				// this cell exhausted the table - its configurations leave the word unchanged
				emitNext(source, len, removed);
			}
		}
	}

	/**
	 * Emits the applied lane of the vowel gate — the deterministic RV-gated final-vowel table, abort semantics
	 * included.
	 *
	 * @param stateIndex current-stage state to apply the step to
	 * @param pV         RV start
	 */
	private void emitVowelApplied(int stateIndex, int pV) {
		final char[] source = this.currentBuffers[stateIndex];
		final int len = this.currentLengths[stateIndex];
		final boolean removed = this.currentRemoved[stateIndex];
		for (final Ending ending : VOWEL_ENDINGS) {
			final int pos = len - ending.suffix().length();
			if (pos >= 0 && regionMatches(source, pos, ending.suffix())) {
				emitNext(source, pos < pV ? len : pos, removed);
				return;
			}
		}
		emitNext(source, len, removed);
	}

	/**
	 * Emits a state whose tail is rewritten: the source's prefix up to `pos` plus the replacement.
	 *
	 * @param source      buffer the prefix comes from
	 * @param pos         position the replacement starts at
	 * @param replacement replacement text
	 * @param removed     the `standardRemoved` sentinel of the emitted state
	 */
	private void emitRewritten(@Nonnull char[] source, int pos, @Nonnull String replacement, boolean removed) {
		System.arraycopy(source, 0, this.scratch, 0, pos);
		for (int i = 0; i < replacement.length(); i++) {
			this.scratch[pos + i] = replacement.charAt(i);
		}
		emitNext(this.scratch, pos + replacement.length(), removed);
	}

	/**
	 * Emits one state into the next stage, deduplicated on content, length and the sentinel.
	 *
	 * @param content buffer holding the state's text
	 * @param len     state length
	 * @param removed the `standardRemoved` sentinel
	 */
	private void emitNext(@Nonnull char[] content, int len, boolean removed) {
		for (int i = 0; i < this.nextCount; i++) {
			if (this.nextLengths[i] == len && this.nextRemoved[i] == removed
				&& regionEquals(this.nextBuffers[i], content, len)) {
				return;
			}
		}
		if (this.nextCount == MAX_STATES) {
			throw new IllegalStateException(
				"More than " + MAX_STATES + " pipeline states - the branching walk has diverged from "
					+ "FoldedRomanianStemmer."
			);
		}
		if (this.nextBuffers[this.nextCount] == null || this.nextBuffers[this.nextCount].length < len) {
			this.nextBuffers[this.nextCount] = new char[ArrayUtil.oversize(len, Character.BYTES)];
		}
		System.arraycopy(content, 0, this.nextBuffers[this.nextCount], 0, len);
		this.nextLengths[this.nextCount] = len;
		this.nextRemoved[this.nextCount] = removed;
		this.nextCount++;
	}

	/**
	 * Swaps the emitted states in as the current stage and resets the emission set.
	 */
	private void swapStages() {
		final char[][] buffers = this.currentBuffers;
		this.currentBuffers = this.nextBuffers;
		this.nextBuffers = buffers;
		final int[] lengths = this.currentLengths;
		this.currentLengths = this.nextLengths;
		this.nextLengths = lengths;
		final boolean[] removed = this.currentRemoved;
		this.currentRemoved = this.nextRemoved;
		this.nextRemoved = removed;
		this.currentCount = this.nextCount;
		this.nextCount = 0;
	}

	/**
	 * Pushes one pending verb-scan cell.
	 *
	 * @param index table position to resume at
	 * @param on    local flags the cell has committed on
	 * @param off   local flags the cell has committed off
	 */
	private void pushVerbCell(int index, int on, int off) {
		if (this.verbTop == MAX_STATES) {
			throw new IllegalStateException(
				"More than " + MAX_STATES + " pending verb cells - the branching walk has diverged from "
					+ "FoldedRomanianStemmer."
			);
		}
		this.verbIndex[this.verbTop] = index;
		this.verbOn[this.verbTop] = on;
		this.verbOff[this.verbTop] = off;
		this.verbTop++;
	}

	/**
	 * Records one final outcome from a marked state buffer, lowercasing the intervocalic markers — the
	 * postlude, applied per outcome.
	 *
	 * @param content marked buffer
	 * @param len     outcome length
	 */
	private void addOutcomeUnmarked(@Nonnull char[] content, int len) {
		for (int i = 0; i < len; i++) {
			final char c = content[i];
			this.scratch[i] = c == 'I' ? 'i' : c == 'U' ? 'u' : c;
		}
		addOutcomePlain(this.scratch, len);
	}

	/**
	 * Records one final outcome, deduplicated by content.
	 *
	 * @param content unmarked buffer
	 * @param len     outcome length
	 */
	private void addOutcomePlain(@Nonnull char[] content, int len) {
		for (int i = 0; i < this.outcomeCount; i++) {
			if (this.outcomeLengths[i] == len && regionEquals(this.outcomeBuffers[i], content, len)) {
				return;
			}
		}
		if (this.outcomeCount == MAX_STATES) {
			throw new IllegalStateException(
				"More than " + MAX_STATES + " hypotheses for one word - the branching walk has diverged from "
					+ "FoldedRomanianStemmer."
			);
		}
		if (this.outcomeBuffers[this.outcomeCount] == null
			|| this.outcomeBuffers[this.outcomeCount].length < len) {
			this.outcomeBuffers[this.outcomeCount] = new char[ArrayUtil.oversize(len, Character.BYTES)];
		}
		System.arraycopy(content, 0, this.outcomeBuffers[this.outcomeCount], 0, len);
		this.outcomeLengths[this.outcomeCount] = len;
		this.outcomeCount++;
	}

	/**
	 * Tells whether two buffers carry the same first `len` characters.
	 *
	 * @param a   first buffer
	 * @param b   second buffer
	 * @param len characters to compare
	 * @return true when equal
	 */
	private static boolean regionEquals(@Nonnull char[] a, @Nonnull char[] b, int len) {
		for (int i = 0; i < len; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
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
	 * Marks intervocalic `i` and `u` with their uppercase counterparts — the Snowball prelude, mirror of the
	 * flat port.
	 *
	 * @param s   buffer to mark
	 * @param len current length
	 */
	private static void markIntervocalic(@Nonnull char[] s, int len) {
		for (int i = 1; i < len - 1; i++) {
			if ((s[i] == 'i' || s[i] == 'u') && isVowel(s[i - 1]) && isVowel(s[i + 1])) {
				s[i] = Character.toUpperCase(s[i]);
			}
		}
	}

	/**
	 * Computes the RV region start — mirror of the flat port.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return start position of RV, or `len` when the word has no RV
	 */
	private static int markRV(@Nonnull char[] s, int len) {
		if (len < 2) {
			return len;
		}
		if (isVowel(s[0])) {
			if (!isVowel(s[1])) {
				for (int i = 2; i < len; i++) {
					if (isVowel(s[i])) {
						return i + 1;
					}
				}
				return len;
			}
			for (int i = 2; i < len; i++) {
				if (!isVowel(s[i])) {
					return i + 1;
				}
			}
			return len;
		}
		if (!isVowel(s[1])) {
			for (int i = 2; i < len; i++) {
				if (isVowel(s[i])) {
					return i + 1;
				}
			}
			return len;
		}
		return len > 2 ? 3 : len;
	}

	/**
	 * Computes an R-region start — mirror of the flat port.
	 *
	 * @param s    input buffer
	 * @param len  current length
	 * @param from position to start scanning at
	 * @return start position of the region, or `len` when there is none
	 */
	private static int markAfterVowelConsonant(@Nonnull char[] s, int len, int from) {
		for (int i = from; i < len - 1; i++) {
			if (isVowel(s[i]) && !isVowel(s[i + 1])) {
				return i + 2;
			}
		}
		return len;
	}

	/**
	 * Tells whether the character is a folded Romanian vowel; the intervocalic markers `I`/`U` are not vowels,
	 * which is their purpose. Mirror of the flat port.
	 *
	 * @param c character to test
	 * @return true for `a`, `e`, `i`, `o`, `u`
	 */
	private static boolean isVowel(char c) {
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
	}

	/**
	 * Sorts table entries longest-suffix-first — mirror of the flat port.
	 *
	 * @param endings table entries in any order
	 * @return the entries sorted by suffix length, longest first
	 */
	@Nonnull
	private static Ending[] byLengthDescending(@Nonnull Ending... endings) {
		final List<Ending> sorted = new ArrayList<>(List.of(endings));
		sorted.sort(Comparator.comparingInt((Ending ending) -> ending.suffix().length()).reversed());
		return sorted.toArray(Ending[]::new);
	}

	/**
	 * Builds the merged verb table from the flat constructor's switch blocks, longest suffix first.
	 *
	 * @return the merged table
	 */
	@Nonnull
	private static VerbEntry[] mergedVerbTable() {
		final List<VerbEntry> table = new ArrayList<>(96);
		// plain entries, present in every configuration
		table.add(new VerbEntry("ea", 1, 0, 0));
		table.add(new VerbEntry("ia", 1, 0, 0));
		table.add(new VerbEntry("esc", 1, 0, 0));
		table.add(new VerbEntry("ind", 1, 0, 0));
		table.add(new VerbEntry("are", 1, 0, 0));
		table.add(new VerbEntry("ere", 1, 0, 0));
		table.add(new VerbEntry("ire", 1, 0, 0));
		table.add(new VerbEntry("se", 2, 0, 0));
		table.add(new VerbEntry("ase", 1, 0, 0));
		table.add(new VerbEntry("sese", 2, 0, 0));
		table.add(new VerbEntry("ise", 1, 0, 0));
		table.add(new VerbEntry("use", 1, 0, 0));
		table.add(new VerbEntry("eze", 1, 0, 0));
		table.add(new VerbEntry("ai", 1, 0, 0));
		table.add(new VerbEntry("eai", 1, 0, 0));
		table.add(new VerbEntry("iai", 1, 0, 0));
		table.add(new VerbEntry("sei", 2, 0, 0));
		table.add(new VerbEntry("ui", 1, 0, 0));
		table.add(new VerbEntry("ezi", 1, 0, 0));
		table.add(new VerbEntry("eati", 1, 0, 0));
		table.add(new VerbEntry("iati", 1, 0, 0));
		table.add(new VerbEntry("eti", 2, 0, 0));
		table.add(new VerbEntry("iti", 2, 0, 0));
		table.add(new VerbEntry("serati", 2, 0, 0));
		table.add(new VerbEntry("aserati", 1, 0, 0));
		table.add(new VerbEntry("seserati", 2, 0, 0));
		table.add(new VerbEntry("iserati", 1, 0, 0));
		table.add(new VerbEntry("userati", 1, 0, 0));
		table.add(new VerbEntry("irati", 1, 0, 0));
		table.add(new VerbEntry("urati", 1, 0, 0));
		table.add(new VerbEntry("em", 2, 0, 0));
		table.add(new VerbEntry("asem", 1, 0, 0));
		table.add(new VerbEntry("sesem", 2, 0, 0));
		table.add(new VerbEntry("isem", 1, 0, 0));
		table.add(new VerbEntry("usem", 1, 0, 0));
		table.add(new VerbEntry("im", 2, 0, 0));
		table.add(new VerbEntry("au", 1, 0, 0));
		table.add(new VerbEntry("eau", 1, 0, 0));
		table.add(new VerbEntry("iau", 1, 0, 0));
		table.add(new VerbEntry("indu", 1, 0, 0));
		table.add(new VerbEntry("ez", 1, 0, 0));
		// folded `am` carries whichever action the switch selects - two disjoint variants
		table.add(new VerbEntry("am", 2, AM_UNCONDITIONAL, 0));
		table.add(new VerbEntry("am", 1, 0, AM_UNCONDITIONAL));
		// the partnerless ă/â-spelled endings
		table.add(new VerbEntry("asc", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("and", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("andu", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("easca", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("eaza", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("ara", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("sera", 2, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("asera", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("sesera", 2, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("isera", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("usera", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("ira", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("ura", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("ati", 2, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("arati", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("aram", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("seram", 2, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("aseram", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("seseram", 2, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("iseram", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("useram", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("iram", 1, A_VERB_ENDINGS, 0));
		table.add(new VerbEntry("uram", 1, A_VERB_ENDINGS, 0));
		// the ş-spelled endings
		table.add(new VerbEntry("este", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("aste", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("esti", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("asti", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("asi", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("isi", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("usi", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("sesi", 2, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("asesi", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("sesesi", 2, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("isesi", 1, S_VERB_ENDINGS, 0));
		table.add(new VerbEntry("usesi", 1, S_VERB_ENDINGS, 0));
		table.sort(Comparator.comparingInt((VerbEntry entry) -> entry.suffix().length()).reversed());
		return table.toArray(VerbEntry[]::new);
	}

}
