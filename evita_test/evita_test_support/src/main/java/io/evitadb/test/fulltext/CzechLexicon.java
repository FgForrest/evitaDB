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

package io.evitadb.test.fulltext;

import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads the hunspell `cs_CZ.dic` fixture as a plain vocabulary: one lowercase word per line, flags stripped, words
 * with anything but letters dropped. Shared by the functional tests (which read it from the classpath) and the
 * performance spikes (which read it from the file system, because the fixture lives in the functional-test module's
 * resources).
 *
 * The two shapes offered mirror the two normalization spaces evitaDB works in: the fulltext index chain folds
 * diacritics after stemming, so a term dictionary holds **folded** words; the attribute filter index stores values in
 * Unicode **NFD** with diacritics kept, so a value tree holds **accented** words.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CzechLexicon {
	/**
	 * Classpath location of the fixture inside the functional-test module.
	 */
	public static final String RESOURCE = "/fulltext/hunspell/cs_CZ.dic";
	/**
	 * File-system locations tried in order when the fixture is not on the classpath, relative to the working
	 * directory - the performance module's own directory first, then the repository root.
	 */
	private static final String[] FILE_CANDIDATES = {
		"../evita_functional_tests/src/test/resources/fulltext/hunspell/cs_CZ.dic",
		"evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/cs_CZ.dic"
	};

	private CzechLexicon() {
	}

	/**
	 * Reads the vocabulary from an open stream of the `.dic` file.
	 *
	 * @param stream the file contents, UTF-8
	 * @param fold   whether to fold diacritics to ASCII (the term-dictionary shape); otherwise NFD with diacritics
	 * @return sorted distinct words
	 */
	@Nonnull
	public static Set<String> load(@Nonnull InputStream stream, boolean fold) {
		final Set<String> words = new TreeSet<>();
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			// the first line of a .dic file is the entry count
			reader.readLine();
			String line;
			while ((line = reader.readLine()) != null) {
				final int flagsSeparator = line.indexOf('/');
				final String word = (flagsSeparator < 0 ? line : line.substring(0, flagsSeparator))
					.trim()
					.toLowerCase(Locale.ROOT);
				if (word.isEmpty() || !word.chars().allMatch(Character::isLetter)) {
					continue;
				}
				words.add(fold ? fold(word) : Normalizer.normalize(word, Normalizer.Form.NFD));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return words;
	}

	/**
	 * Reads the vocabulary from the classpath resource, falling back to the known file-system locations. The
	 * `evita.lexicon.cs` system property overrides the file location.
	 *
	 * @param fold whether to fold diacritics to ASCII
	 * @return sorted distinct words
	 */
	@Nonnull
	public static Set<String> load(boolean fold) {
		final InputStream resource = CzechLexicon.class.getResourceAsStream(RESOURCE);
		if (resource != null) {
			return load(resource, fold);
		}
		final String override = System.getProperty("evita.lexicon.cs");
		final Path[] candidates = override == null
			? new Path[]{Path.of(FILE_CANDIDATES[0]), Path.of(FILE_CANDIDATES[1])}
			: new Path[]{Path.of(override)};
		for (final Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				try {
					return load(Files.newInputStream(candidate), fold);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		throw new IllegalStateException(
			"Czech lexicon not found on the classpath (" + RESOURCE + ") nor on disk; " +
				"set -Devita.lexicon.cs=<path to cs_CZ.dic>"
		);
	}

	/**
	 * Folds diacritics to ASCII the way `ASCIIFoldingFilter` does at the end of the index-side analyzer chain.
	 *
	 * @param word the word, any normalization form
	 * @return the folded word
	 */
	@Nonnull
	public static String fold(@Nonnull String word) {
		final char[] input = word.toCharArray();
		final char[] output = new char[input.length * 4];
		final int length = ASCIIFoldingFilter.foldToASCII(input, 0, output, 0, input.length);
		return new String(output, 0, length);
	}
}
