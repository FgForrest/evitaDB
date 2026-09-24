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

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * **Prototype, test scope only.** The Snowball Polish stemmer ({@link PolishSnowballStemmer}, the official
 * upstream algorithm) with its tables rewritten into **diacritics-folded space**, so the stemmer can run
 * **after** folding instead of before it — mechanism M1 of the prior-art survey and the query half of mechanism
 * M7, applied to Polish
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-sk-pl-ro.md`, §5).
 *
 * **What folds cleanly.** Polish folding maps vowels to vowels (`ą`→`a`, `ę`→`e`, `ó`→`o`) and consonants to
 * consonants (`ł`→`l`, `ś`→`s`, `ź`/`ż`→`z`, `ć`→`c`, `ń`→`n`), so the R1 region marking is identical on folded
 * and accented input — the same result the Romanian port verified, and one the survey did not predict for a
 * language with a stroked consonant. Two more things come free: the final-consonant normalization step
 * (`ć`→`c`, `ń`→`n`, `ś`→`s`, `ź`→`z`) is the identity on folded input and is simply dropped, and the `ó`↔`o`
 * **stem-internal** alternation (`stół`/`stołu`) — the alternation the survey flagged as the Czech-`ů`-like
 * hazard — is *repaired* by folding rather than broken by it, because Polish spells both alternants with plain
 * letters the fold maps onto each other.
 *
 * **Four groups of endings cannot be folded mechanically.** Each group folds onto strings that genuine
 * plain-spelled words end with, so each is a language judgment made switchable to be measured:
 *
 * - **{@link #lEndings}** — the `ł`-spelled past-tense system (`ał`/`ił`/`ała`/`iło`/`ałem`/`ałyście`…) folds
 *   onto `-al`/`-il` strings: with the entries included, folded `metal` loses `al` as if it were a past tense.
 *   The survey predicted this one — "the entire past-tense system folds onto `-al`/`-il` strings".
 * - **{@link #nasalEndings}** — the `ą`/`ę`-spelled endings (`ąc`/`ąca`/`ają`/`ące`…) fold onto `-ac`/`-aja`/…
 *   strings (folded `pałac` loses `ac`), and folded final `e` inherits `ę`'s *unconditional* delete where plain
 *   `e` deletes only in R1 — the one action conflict folding creates in the main table.
 * - **{@link #softEndings}** — the `ś`/`ć`-spelled infinitive and noun endings (`ać`/`ieć`/`ić`/`aść`/`eść`,
 *   the `-liście`/`-liśmy` past plurals) fold onto `-ac`/`-iec`/`-ic`/… strings: folded `kupiec` loses `iec` as
 *   if it were the infinitive of *kupieć*.
 * - **{@link #owEnding}** — genitive-plural `ów` folds onto `-ow`, which native Polish words rarely end in but
 *   proper names and loanwords do.
 *
 * Collisions where both spellings carry the same action (`ą`/`a`, `ią`/`ia`, `ąć`/`ać`→`ac`) are collapsed
 * unconditionally.
 *
 * - **{@link #nasalHomographs}** — a fifth group found by the pl_PL lexicon sweep, not by the fixture: folded
 *   strings whose accented sources are a **plain ending** and a **nasal-vowel stem letter plus a shorter
 *   ending**, with different outcomes. `arabie` is both `-ie` (strip two) and `arabi+ę` (strip one); `okecie`
 *   is both `-ecie` and `okę+cie`; `bladnales` is both `-ałeś` and `-ą+łeś`; and the `sza`/`szą`, `sze`/`szę`
 *   action conflicts this class javadoc originally dismissed as sub-R1-only turned out to bite on real words
 *   (`paszą` → accented `pas`, folded plain-`sza` delete gave `pa`). With the switch on, the folded string is
 *   read as the nasal spelling: `ie`, `acie`, `ecie`, `ales` fall through to their shorter suffix, `sza` gets
 *   `szą`'s R1-or-`s` action, `sze` gets `szę`'s `s` rewrite.
 *
 * **NOTE**: input is expected to be lowercased **and diacritics-folded** — the exact opposite of the Snowball
 * stemmer's contract.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class FoldedPolishStemmer implements FoldedStemmer {

	/**
	 * One suffix entry of the ending table.
	 *
	 * @param suffix     folded suffix, matched at the end of the current buffer
	 * @param action     1 delete, 2 → `s`, 4 → `l` (the folded image of the original's → `ł`), 5 delete and try
	 *                   the second-layer table
	 * @param r1Required whether the suffix must start inside R1 — a failed condition backtracks to shorter
	 *                   entries, as Snowball's `find_among_b` does
	 */
	private record Ending(@Nonnull String suffix, int action, boolean r1Required) {
	}

	/**
	 * The conditional-particle pre-pass (the Snowball `a_0`): the movable `by`-family is stripped before the
	 * main table wherever it sits inside R1. The `ś`-spelled members are kept unconditionally in their folded
	 * spelling — the pre-pass is R1-gated and the strings are too specific to over-fire measurably.
	 */
	private static final List<String> BY_PARTICLES = List.of("byscie", "bysmy", "bym", "bys", "by");

	/**
	 * The second-layer table (the Snowball `a_1`), tried after an action-5 delete: comparative/participle
	 * remnants. The `ą`-spelled members are gated by {@link #nasalEndings}.
	 */
	private static final Ending SECOND_SZ = new Ending("sz", 1, false);
	private static final Ending SECOND_IEJSZ = new Ending("iejsz", 1, false);
	private static final Ending SECOND_AC = new Ending("ac", 1, false);
	private static final Ending SECOND_AJAC = new Ending("ajac", 1, false);
	private static final Ending SECOND_SZAC = new Ending("szac", 2, false);

	/**
	 * Whether the `ł`-spelled past-tense endings are included in their folded spelling.
	 */
	private final boolean lEndings;
	/**
	 * Whether the `ą`/`ę`-spelled endings are included in their folded spelling, and folded final `e` gets
	 * `ę`'s unconditional delete.
	 */
	private final boolean nasalEndings;
	/**
	 * Whether the `ś`/`ć`-spelled endings are included in their folded spelling.
	 */
	private final boolean softEndings;
	/**
	 * Whether genitive-plural `ów` is included as folded `ow`.
	 */
	private final boolean owEnding;
	/**
	 * Whether the folded nasal-homograph strings are read as their nasal-spelled source — the colliding entries
	 * (`ie`, `cie`, `acie`, `ecie`, `ales`, `sza`, `sze`) are dropped so the shorter suffix fires. See the
	 * class javadoc.
	 */
	private final boolean nasalHomographs;
	/**
	 * Whether `sza`/`sze` carry the `szą`/`szę` **actions** (delete-in-R1-else-`s`, always-`s`) instead of the
	 * plain delete. A third reading of the same folded strings — `kasże` folds to `kasze` too, needing a bare
	 * `e`-strip — so this cannot fold into {@link #nasalHomographs}; it is a separate hypothesis position.
	 */
	private final boolean szNasalActions;
	/**
	 * Whether `cie` itself is read as a nasal-final form (`dzieci+ę` → folded `dziecie` needs a bare `e`-strip)
	 * rather than the plain second-person ending (`kopcie`) or the `ę+cie` neuter's suffix (`okęcie` → `cie`
	 * must fire). A third reading of the same folded strings, independent of {@link #nasalHomographs}.
	 */
	private final boolean cieHomograph;
	/**
	 * The main ending table (the Snowball `a_2`), assembled per instance from the switches.
	 */
	@Nonnull private final List<Ending> endings;
	/**
	 * The second-layer table (the Snowball `a_1`), assembled per instance from the switches.
	 */
	@Nonnull private final List<Ending> secondLayer;

	/**
	 * Creates a stemmer with the four fold-ambiguous ending groups independently enabled or disabled.
	 *
	 * @param lEndings     whether the folded `ł` past-tense endings are stripped
	 * @param nasalEndings whether the folded `ą`/`ę` endings are stripped
	 * @param softEndings  whether the folded `ś`/`ć` endings are stripped
	 * @param owEnding     whether folded `ow` is stripped as the genitive plural `ów`
	 */
	FoldedPolishStemmer(boolean lEndings, boolean nasalEndings, boolean softEndings, boolean owEnding) {
		this(lEndings, nasalEndings, softEndings, owEnding, false);
	}

	/**
	 * Creates a stemmer with all five fold-ambiguous groups independently enabled or disabled.
	 *
	 * @param lEndings        whether the folded `ł` past-tense endings are stripped
	 * @param nasalEndings    whether the folded `ą`/`ę` endings are stripped
	 * @param softEndings     whether the folded `ś`/`ć` endings are stripped
	 * @param owEnding        whether folded `ow` is stripped as the genitive plural `ów`
	 * @param nasalHomographs whether the folded nasal-homograph strings are read as their nasal source
	 */
	FoldedPolishStemmer(
		boolean lEndings,
		boolean nasalEndings,
		boolean softEndings,
		boolean owEnding,
		boolean nasalHomographs
	) {
		this(lEndings, nasalEndings, softEndings, owEnding, nasalHomographs, false);
	}

	/**
	 * Creates a stemmer with all six fold-ambiguous positions independently enabled or disabled.
	 *
	 * @param lEndings        whether the folded `ł` past-tense endings are stripped
	 * @param nasalEndings    whether the folded `ą`/`ę` endings are stripped
	 * @param softEndings     whether the folded `ś`/`ć` endings are stripped
	 * @param owEnding        whether folded `ow` is stripped as the genitive plural `ów`
	 * @param nasalHomographs whether the folded nasal-homograph strings are read as their nasal source
	 * @param szNasalActions  whether `sza`/`sze` carry the `szą`/`szę` actions
	 */
	FoldedPolishStemmer(
		boolean lEndings,
		boolean nasalEndings,
		boolean softEndings,
		boolean owEnding,
		boolean nasalHomographs,
		boolean szNasalActions
	) {
		this(lEndings, nasalEndings, softEndings, owEnding, nasalHomographs, szNasalActions, false);
	}

	/**
	 * Creates a stemmer with all seven fold-ambiguous positions independently enabled or disabled.
	 *
	 * @param lEndings        whether the folded `ł` past-tense endings are stripped
	 * @param nasalEndings    whether the folded `ą`/`ę` endings are stripped
	 * @param softEndings     whether the folded `ś`/`ć` endings are stripped
	 * @param owEnding        whether folded `ow` is stripped as the genitive plural `ów`
	 * @param nasalHomographs whether the folded nasal-homograph strings are read as their nasal source
	 * @param szNasalActions  whether `sza`/`sze` carry the `szą`/`szę` actions
	 * @param cieHomograph    whether `cie` is read as a nasal-final form
	 */
	FoldedPolishStemmer(
		boolean lEndings,
		boolean nasalEndings,
		boolean softEndings,
		boolean owEnding,
		boolean nasalHomographs,
		boolean szNasalActions,
		boolean cieHomograph
	) {
		this.lEndings = lEndings;
		this.nasalEndings = nasalEndings;
		this.softEndings = softEndings;
		this.owEnding = owEnding;
		this.nasalHomographs = nasalHomographs;
		this.szNasalActions = szNasalActions;
		this.cieHomograph = cieHomograph;

		final List<Ending> table = new ArrayList<>(128);
		// plain-spelled entries, present in every configuration; conditions ported one to one
		table.add(new Ending("a", 1, true));       // a + ą (same action and condition)
		table.add(new Ending("ia", 1, true));      // ia + ią
		// sza has three folded readings: plain sza (delete), szą (R1-or-`s`, action 3, szNasalActions) and
		// s+ż+a with the bare vowel (nasalHomographs drops the entry so the shorter suffix fires)
		if (szNasalActions) {
			table.add(new Ending("sza", 3, false));
		} else if (!nasalHomographs) {
			table.add(new Ending("sza", 1, false));
		}
		table.add(new Ending("iejsza", 1, false));
		table.add(new Ending("e", 1, !nasalEndings)); // e is R1-gated; ę deletes everywhere - the one conflict
		if (!nasalHomographs) {
			// read as the plain endings; with the switch on the folded string is the nasal spelling and the
			// shorter suffix fires instead (ziemi+ę -> `e`, okę+cie -> `cie`)
			table.add(new Ending("ie", 1, true));
			table.add(new Ending("acie", 1, false));
			table.add(new Ending("ecie", 1, false));
		}
		if (!cieHomograph) {
			// cie has three readings of its own: the plain ending (kopcie), the suffix after a nasal-final
			// stem letter (okęcie, needs nasalHomographs on and this off) and part of a nasal-final form
			// (dzieci+ę -> dziecie, needs both on so the bare `e` fires)
			table.add(new Ending("cie", 1, false));
		}
		table.add(new Ending("icie", 1, false));
		table.add(new Ending("ajcie", 1, false));
		// sze likewise: plain sze (delete), szę (-> `s`, szNasalActions) or s+ż+e with the bare vowel
		if (szNasalActions) {
			table.add(new Ending("sze", 2, false));
		} else if (!nasalHomographs) {
			table.add(new Ending("sze", 1, false));
		}
		table.add(new Ending("iejsze", 1, false));
		table.add(new Ending("ach", 1, true));
		table.add(new Ending("iach", 1, true));
		table.add(new Ending("ich", 5, false));
		table.add(new Ending("ych", 5, false));
		table.add(new Ending("i", 1, true));
		table.add(new Ending("ali", 1, false));
		table.add(new Ending("ieli", 1, false));
		table.add(new Ending("ili", 1, false));
		table.add(new Ending("ami", 1, true));
		table.add(new Ending("iami", 1, true));
		table.add(new Ending("imi", 5, false));
		table.add(new Ending("ymi", 5, false));
		table.add(new Ending("owi", 1, true));
		table.add(new Ending("iowi", 1, true));
		table.add(new Ending("aj", 1, false));
		table.add(new Ending("ej", 5, false));
		table.add(new Ending("iej", 5, false));
		table.add(new Ending("am", 1, false));
		table.add(new Ending("em", 1, true));
		table.add(new Ending("iem", 1, true));
		table.add(new Ending("im", 5, false));
		table.add(new Ending("om", 1, true));
		table.add(new Ending("iom", 1, true));
		table.add(new Ending("ym", 5, false));
		table.add(new Ending("o", 1, true));
		table.add(new Ending("ego", 5, false));
		table.add(new Ending("iego", 5, false));
		table.add(new Ending("u", 1, true));
		table.add(new Ending("iu", 1, true));
		table.add(new Ending("y", 5, false));
		table.add(new Ending("amy", 1, false));
		table.add(new Ending("emy", 1, false));
		table.add(new Ending("imy", 1, false));
		table.add(new Ending("asz", 1, false));
		table.add(new Ending("esz", 1, false));
		table.add(new Ending("isz", 1, false));
		table.add(new Ending("emu", 5, false));
		table.add(new Ending("iemu", 5, false));
		if (lEndings) {
			table.add(new Ending("ala", 1, false));      // ała
			table.add(new Ending("iala", 1, false));     // iała
			table.add(new Ending("ila", 1, false));      // iła
			table.add(new Ending("alam", 1, false));     // ałam
			table.add(new Ending("ialam", 1, false));    // iałam
			table.add(new Ending("ilam", 1, false));     // iłam
			table.add(new Ending("alem", 1, false));     // ałem
			table.add(new Ending("ialem", 1, false));    // iałem
			table.add(new Ending("ilem", 1, false));     // iłem
			table.add(new Ending("alo", 1, false));      // ało
			table.add(new Ending("ialo", 1, false));     // iało
			table.add(new Ending("ilo", 1, false));      // iło
			table.add(new Ending("aly", 1, false));      // ały
			table.add(new Ending("ialy", 1, false));     // iały
			table.add(new Ending("ily", 1, false));      // iły
			table.add(new Ending("al", 1, false));       // ał
			table.add(new Ending("ial", 1, false));      // iał
			table.add(new Ending("il", 1, false));       // ił
			table.add(new Ending("las", 4, false));      // łaś
			table.add(new Ending("alas", 1, false));     // ałaś
			table.add(new Ending("ialas", 1, false));    // iałaś
			table.add(new Ending("ilas", 1, false));     // iłaś
			table.add(new Ending("les", 4, false));      // łeś
			if (!nasalHomographs) {
				table.add(new Ending("ales", 1, false)); // ałeś; as ą+łeś the shorter `les` fires instead
			}
			table.add(new Ending("iales", 1, false));    // iałeś
			table.add(new Ending("iles", 1, false));     // iłeś
			table.add(new Ending("lyscie", 4, false));   // łyście
			table.add(new Ending("alyscie", 1, false));  // ałyście
			table.add(new Ending("ialyscie", 1, false)); // iałyście
			table.add(new Ending("ilyscie", 1, false));  // iłyście
			table.add(new Ending("lysmy", 4, false));    // łyśmy
			table.add(new Ending("alysmy", 1, false));   // ałyśmy
			table.add(new Ending("ialysmy", 1, false));  // iałyśmy
			table.add(new Ending("ilysmy", 1, false));   // iłyśmy
		}
		if (nasalEndings) {
			table.add(new Ending("aca", 1, false));      // ąca + ącą
			table.add(new Ending("ajaca", 1, false));    // ająca + ającą
			table.add(new Ending("szaca", 2, false));    // sząca + szącą
			table.add(new Ending("ajac", 1, false));     // ając
			table.add(new Ending("ace", 1, false));      // ące
			table.add(new Ending("ajace", 1, false));    // ające
			table.add(new Ending("szace", 2, false));    // szące
			table.add(new Ending("aja", 1, false));      // ają
		}
		if (nasalEndings || softEndings) {
			// ąc, ać and ąć all fold onto `ac` with the same action - one entry with a dual provenance
			table.add(new Ending("ac", 1, false));
		}
		if (softEndings) {
			table.add(new Ending("iec", 1, false));      // ieć
			table.add(new Ending("ic", 1, false));       // ić
			table.add(new Ending("asc", 1, false));      // aść
			table.add(new Ending("esc", 1, false));      // eść
			table.add(new Ending("liscie", 4, false));   // liście
			table.add(new Ending("aliscie", 1, false));  // aliście
			table.add(new Ending("ieliscie", 1, false)); // ieliście
			table.add(new Ending("iliscie", 1, false));  // iliście
			table.add(new Ending("lismy", 4, false));    // liśmy
			table.add(new Ending("alismy", 1, false));   // aliśmy
			table.add(new Ending("ielismy", 1, false));  // ieliśmy
			table.add(new Ending("ilismy", 1, false));   // iliśmy
		}
		if (owEnding) {
			table.add(new Ending("ow", 1, true));        // ów
		}
		table.sort(Comparator.comparingInt((Ending ending) -> ending.suffix().length()).reversed());
		this.endings = List.copyOf(table);

		final List<Ending> second = new ArrayList<>(8);
		second.add(SECOND_SZ);
		second.add(SECOND_IEJSZ);
		if (nasalEndings) {
			second.add(SECOND_AC);
			second.add(SECOND_AJAC);
			second.add(SECOND_SZAC);
		}
		second.sort(Comparator.comparingInt((Ending ending) -> ending.suffix().length()).reversed());
		this.secondLayer = List.copyOf(second);
	}

	/**
	 * Builds the full M7 hypothesis set: every combination of the six positions, plus the folded surface
	 * itself. One list shared by the P20 matrix chain and the pl_PL lexicon sweep.
	 *
	 * @return every hypothesis stemmer, surface hypothesis last
	 */
	@Nonnull
	static List<FoldedStemmer> allHypotheses() {
		final List<FoldedStemmer> hypotheses = new ArrayList<>(129);
		for (int mask = 0; mask < 128; mask++) {
			hypotheses.add(new FoldedPolishStemmer(
				(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
				(mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0
			));
		}
		hypotheses.add((buffer, length) -> length);
		return hypotheses;
	}

	/**
	 * Stems an input buffer of diacritics-folded, lowercased Polish text. Mirrors the Snowball driver: R1
	 * marking, the `by`-particle pre-pass, one pass of the main table with the two-character floor, the
	 * second-layer table after an action-5 delete. The accented original's final-consonant normalization is
	 * dropped — it is the identity on folded input.
	 *
	 * @param s   input buffer
	 * @param len length of the input buffer
	 * @return length of the buffer after stemming
	 */
	@Override
	public int stem(@Nonnull char[] s, int len) {
		if (len < 2) {
			return len;
		}
		final int p1 = markR1(s, len);
		len = removeByParticle(s, len, p1);
		return removeEnding(s, len, p1);
	}

	/**
	 * Computes the R1 start, ported from the Snowball `mark_regions`: skip to the first vowel, step past it,
	 * skip any further vowels, step past the first consonant after them.
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
	 * Tells whether the character is a folded Polish vowel — `y` included, as in the original grouping.
	 *
	 * @param c character to test
	 * @return true for `a`, `e`, `i`, `o`, `u`, `y`
	 */
	private static boolean isVowel(char c) {
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
	}

	/**
	 * Strips one `by`-particle sitting wholly inside R1, mirroring the original's pre-pass.
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
	 * Applies the main ending table with the original's two-character floor and per-entry R1 conditions; a
	 * failed condition falls through to shorter entries, as Snowball's backtracking does. An action-5 delete
	 * is followed by one second-layer attempt.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @param p1  R1 start
	 * @return length after the step; unchanged when nothing fired
	 */
	private int removeEnding(@Nonnull char[] s, int len, int p1) {
		for (final Ending ending : this.endings) {
			final int pos = len - ending.suffix().length();
			// the two-character floor is the original's `limit_backward = 2`
			if (pos < 2 || !regionMatches(s, pos, ending.suffix())) {
				continue;
			}
			if (ending.r1Required() && pos < p1) {
				// condition failed - backtrack to shorter entries rather than aborting
				continue;
			}
			switch (ending.action()) {
				case 1:
					return pos;
				case 2:
					s[pos] = 's';
					return pos + 1;
				case 3:
					// the szą action: delete in R1, rewrite to `s` below it
					if (pos >= p1) {
						return pos;
					}
					s[pos] = 's';
					return pos + 1;
				case 4:
					s[pos] = 'l';
					return pos + 1;
				case 5:
					return secondLayer(s, pos);
				default:
					throw new IllegalStateException("Unknown ending action " + ending.action() + ".");
			}
		}
		return len;
	}

	/**
	 * Applies the second-layer table after an action-5 delete.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after the layer; unchanged when nothing fired
	 */
	private int secondLayer(@Nonnull char[] s, int len) {
		for (final Ending ending : this.secondLayer) {
			final int pos = len - ending.suffix().length();
			if (pos < 2 || !regionMatches(s, pos, ending.suffix())) {
				continue;
			}
			if (ending.action() == 2) {
				s[pos] = 's';
				return pos + 1;
			}
			return pos;
		}
		return len;
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

}
