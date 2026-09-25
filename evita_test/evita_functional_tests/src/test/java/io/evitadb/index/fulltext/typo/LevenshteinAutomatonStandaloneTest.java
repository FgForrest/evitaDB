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

package io.evitadb.index.fulltext.typo;

import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof of concept: the Levenshtein automaton of {@code lucene-core} is usable on its own, without a Lucene index,
 * a {@code Directory}, a {@code Terms} dictionary or anything from {@code org.apache.lucene.index}. Everything this
 * test touches lives in {@code org.apache.lucene.util.automaton}, which is already on evitaDB's classpath.
 *
 * The reference distance the automaton is checked against is the restricted Damerau–Levenshtein distance (optimal
 * string alignment: insert, delete, substitute, and swap two adjacent characters), computed by the textbook DP.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Lucene Levenshtein automaton used standalone, without a Lucene index")
@Tag(ENGINE)
@Tag(FULLTEXT)
class LevenshteinAutomatonStandaloneTest {

	/**
	 * Restricted Damerau–Levenshtein (optimal string alignment) distance over code points.
	 */
	static int osaDistance(@Nonnull String a, @Nonnull String b) {
		final int[] x = a.codePoints().toArray();
		final int[] y = b.codePoints().toArray();
		final int[][] d = new int[x.length + 1][y.length + 1];
		for (int i = 0; i <= x.length; i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= y.length; j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= x.length; i++) {
			for (int j = 1; j <= y.length; j++) {
				final int cost = x[i - 1] == y[j - 1] ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[x.length][y.length];
	}

	@Nonnull
	private static CharacterRunAutomaton acceptor(@Nonnull String word, int maxEdits) {
		return new CharacterRunAutomaton(
			new LevenshteinAutomata(word.codePoints().toArray(), Character.MAX_CODE_POINT, true).toAutomaton(maxEdits)
		);
	}

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("Builds a deterministic automaton without dead states from a plain string")
		void shouldBuildDeterministicAutomatonWithoutDeadStates() {
			final Automaton automaton = new LevenshteinAutomata("cerna", true).toAutomaton(2);
			assertTrue(automaton.isDeterministic());
			assertFalse(Operations.hasDeadStates(automaton));
			assertTrue(automaton.getNumStates() > 1);
		}

		@Test
		@DisplayName("Returns no automaton for a distance above the supported maximum of two")
		void shouldCapDistanceAtTwo() {
			assertEquals(2, LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE);
			// the library signals the cap by returning null rather than throwing - a caller has to check
			assertNull(new LevenshteinAutomata("cerna", true).toAutomaton(3));
			assertNotNull(new LevenshteinAutomata("cerna", true).toAutomaton(2));
		}
	}

	@Nested
	@DisplayName("Acceptance")
	class Acceptance {

		@Test
		@DisplayName("Accepts one substitution, insertion, deletion or transposition at distance one")
		void shouldAcceptSingleEditsAtDistanceOne() {
			final CharacterRunAutomaton one = acceptor("cerna", 1);
			assertTrue(one.run("cerna"), "exact");
			assertTrue(one.run("cerne"), "substitution");
			assertTrue(one.run("cern"), "deletion");
			assertTrue(one.run("cernaa"), "insertion");
			assertTrue(one.run("cenra"), "transposition counts as one edit");
			assertFalse(one.run("cxrnx"), "two substitutions");
			assertFalse(one.run("black"));
		}

		@Test
		@DisplayName("Accepts two edits at distance two and rejects three")
		void shouldAcceptTwoEditsAtDistanceTwo() {
			final CharacterRunAutomaton two = acceptor("cerna", 2);
			assertTrue(two.run("cxrnx"));
			assertTrue(two.run("cer"));
			assertFalse(two.run("cxrxx"), "three substitutions");
			assertFalse(two.run("ce"), "three deletions");
		}

		@Test
		@DisplayName("Permits no edit inside a frozen prefix")
		void shouldFreezePrefix() {
			// the automaton is built over the suffix and the prefix is attached as a literal chain
			final Automaton frozen = new LevenshteinAutomata("erna", true).toAutomaton(1, "c");
			final CharacterRunAutomaton acceptor = new CharacterRunAutomaton(frozen);
			assertTrue(acceptor.run("cerna"));
			assertTrue(acceptor.run("cerne"), "edit after the prefix");
			assertFalse(acceptor.run("xerna"), "edit in the prefix");
			assertFalse(acceptor.run("erna"), "prefix deleted");
		}

		@Test
		@DisplayName("Exact distance is learned by cascading the narrower acceptors")
		void shouldClassifyDistanceByCascade() {
			final CharacterRunAutomaton zero = acceptor("cerna", 0);
			final CharacterRunAutomaton one = acceptor("cerna", 1);
			final CharacterRunAutomaton two = acceptor("cerna", 2);
			assertTrue(two.run("cxrnx") && !one.run("cxrnx"), "distance 2");
			assertTrue(one.run("cerne") && !zero.run("cerne"), "distance 1");
			assertTrue(zero.run("cerna"), "distance 0");
		}
	}

	@Nested
	@DisplayName("Alphabet")
	class Alphabet {

		@Test
		@DisplayName("In code point space a diacritic costs one edit; in UTF-8 byte space it costs two")
		void shouldCountDiacriticAsOneEditInCodePointSpace() {
			final String accented = "černá";
			final String bare = "cerna";
			// code point space: č→c and á→a are two single-symbol substitutions
			final LevenshteinAutomata codePointBuilder = new LevenshteinAutomata(
				accented.codePoints().toArray(), Character.MAX_CODE_POINT, true
			);
			assertTrue(
				new CharacterRunAutomaton(codePointBuilder.toAutomaton(2)).run(bare),
				"distance 2 in code point space"
			);
			assertFalse(
				new CharacterRunAutomaton(codePointBuilder.toAutomaton(1)).run(bare),
				"but not distance 1"
			);
			// byte space: each of č and á is two UTF-8 bytes, so the same pair is four byte edits away
			final byte[] accentedBytes = accented.getBytes(StandardCharsets.UTF_8);
			final int[] accentedByteSymbols = new int[accentedBytes.length];
			for (int i = 0; i < accentedBytes.length; i++) {
				accentedByteSymbols[i] = accentedBytes[i] & 0xff;
			}
			final Automaton byteAutomaton = new LevenshteinAutomata(accentedByteSymbols, 255, true).toAutomaton(2);
			final byte[] bareBytes = bare.getBytes(StandardCharsets.UTF_8);
			int state = 0;
			for (final byte b : bareBytes) {
				state = byteAutomaton.step(state, b & 0xff);
				if (state == -1) {
					break;
				}
			}
			assertTrue(state == -1 || !byteAutomaton.isAccept(state), "rejected within distance 2 in byte space");
		}
	}

	@Nested
	@DisplayName("Agreement with the reference distance")
	class Agreement {

		@Test
		@DisplayName("Acceptance equals `osa(query, candidate) <= maxEdits` on random strings")
		void shouldAgreeWithDynamicProgrammingDistance() {
			final Random random = new Random(42);
			final String alphabet = "abcdeěščřž";
			int checked = 0;
			for (int round = 0; round < 300; round++) {
				final String query = randomWord(random, alphabet, 3 + random.nextInt(6));
				final CharacterRunAutomaton[] acceptors = {
					acceptor(query, 0), acceptor(query, 1), acceptor(query, 2)
				};
				for (int i = 0; i < 40; i++) {
					final String candidate = mutate(random, alphabet, query);
					final int distance = osaDistance(query, candidate);
					for (int maxEdits = 0; maxEdits <= 2; maxEdits++) {
						assertEquals(
							distance <= maxEdits,
							acceptors[maxEdits].run(candidate),
							"query `" + query + "`, candidate `" + candidate + "`, maxEdits " + maxEdits +
								", reference distance " + distance
						);
						checked++;
					}
				}
			}
			assertTrue(checked > 30_000);
		}

		@Nonnull
		private String randomWord(@Nonnull Random random, @Nonnull String alphabet, int length) {
			final StringBuilder sb = new StringBuilder(length);
			for (int i = 0; i < length; i++) {
				sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			return sb.toString();
		}

		/**
		 * Applies 0–3 random edits (substitution, insertion, deletion, adjacent swap) so that the sample covers the
		 * boundary on both sides.
		 */
		@Nonnull
		private String mutate(@Nonnull Random random, @Nonnull String alphabet, @Nonnull String word) {
			final StringBuilder sb = new StringBuilder(word);
			final int edits = random.nextInt(4);
			for (int e = 0; e < edits && !sb.isEmpty(); e++) {
				final int pos = random.nextInt(sb.length());
				switch (random.nextInt(4)) {
					case 0 -> sb.setCharAt(pos, alphabet.charAt(random.nextInt(alphabet.length())));
					case 1 -> sb.insert(pos, alphabet.charAt(random.nextInt(alphabet.length())));
					case 2 -> sb.deleteCharAt(pos);
					default -> {
						if (pos + 1 < sb.length()) {
							final char c = sb.charAt(pos);
							sb.setCharAt(pos, sb.charAt(pos + 1));
							sb.setCharAt(pos + 1, c);
						}
					}
				}
			}
			return sb.toString();
		}
	}
}
