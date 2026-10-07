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

import org.apache.lucene.analysis.hunspell.AffixedWord;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.SortingStrategy;
import org.apache.lucene.analysis.hunspell.WordFormGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Generates the **inflected word forms** of the hunspell `cs_CZ` fixture through Lucene's own
 * {@link WordFormGenerator} (the "unmunch" of hunspell-tools, in `lucene-analysis-common` since 9.x): the `.dic`
 * file holds headwords with affix flags, the `.aff` file the rules, and the generator applies every rule a
 * headword's flags allow. Over the whole fixture that is about 4.8 million forms (4.35 million distinct), which is
 * the realistic input for typo-tolerance measurements: users type inflected forms, not dictionary headwords.
 *
 * Forms are lowercased (the root `Locale`) and anything containing a non-letter is dropped, mirroring
 * {@link CzechLexicon}. Diacritics are kept — callers fold or stem as the measured chain does.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CzechWordForms {
	/**
	 * Classpath location of the affix rules inside the functional-test module.
	 */
	public static final String AFFIX_RESOURCE = "/fulltext/hunspell/cs_CZ.aff";
	/**
	 * File-system locations of the affix rules tried when the fixture is not on the classpath, relative to the
	 * working directory - the performance module's own directory first, then the repository root.
	 */
	private static final String[] AFFIX_FILE_CANDIDATES = {
		"../evita_functional_tests/src/test/resources/fulltext/hunspell/cs_CZ.aff",
		"evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/cs_CZ.aff"
	};
	/**
	 * File-system locations of the headwords, in the same order as {@link #AFFIX_FILE_CANDIDATES}.
	 */
	private static final String[] DICTIONARY_FILE_CANDIDATES = {
		"../evita_functional_tests/src/test/resources/fulltext/hunspell/cs_CZ.dic",
		"evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/cs_CZ.dic"
	};
	/**
	 * Cancellation hook the generator calls periodically; generation is never cancelled here.
	 */
	private static final Runnable NEVER_CANCELLED = () -> { };

	/**
	 * The generator over the loaded dictionary.
	 */
	@Nonnull private final WordFormGenerator generator;

	/**
	 * @param generator the generator over the loaded dictionary
	 */
	private CzechWordForms(@Nonnull WordFormGenerator generator) {
		this.generator = generator;
	}

	/**
	 * Loads the fixture from the classpath, falling back to the known file-system locations. Loading takes about
	 * a second; generating every form takes about half a minute.
	 *
	 * @return the generator
	 */
	@Nonnull
	public static CzechWordForms load() {
		try (
			final InputStream affix = open(AFFIX_RESOURCE, AFFIX_FILE_CANDIDATES);
			final InputStream dictionary = open(CzechLexicon.RESOURCE, DICTIONARY_FILE_CANDIDATES)
		) {
			final Dictionary hunspell = new Dictionary(affix, List.of(dictionary), false, SortingStrategy.inMemory());
			return new CzechWordForms(new WordFormGenerator(hunspell));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (ParseException e) {
			throw new IllegalStateException("The hunspell cs_CZ fixture cannot be parsed.", e);
		}
	}

	/**
	 * Returns every form the dictionary derives from one headword, lowercased, letters only, without duplicates,
	 * in the generator's stable order.
	 *
	 * @param headword the headword exactly as it appears in the `.dic` file
	 * @return the forms; empty when the headword is not in the dictionary
	 */
	@Nonnull
	public List<String> formsOf(@Nonnull String headword) {
		final Set<String> forms = new LinkedHashSet<>(32);
		for (final AffixedWord word : this.generator.getAllWordForms(headword, NEVER_CANCELLED)) {
			final String form = normalize(word.getWord());
			if (form != null) {
				forms.add(form);
			}
		}
		return new ArrayList<>(forms);
	}

	/**
	 * Hands every form of the whole dictionary to `consumer`, lowercased and letters only. Duplicates occur where
	 * two headwords derive the same form; the order is the generator's and is deterministic for one fixture.
	 *
	 * @param consumer receives each form
	 */
	public void forEachForm(@Nonnull Consumer<String> consumer) {
		this.generator.generateAllSimpleWords(
			word -> {
				final String form = normalize(word.getWord());
				if (form != null) {
					consumer.accept(form);
				}
			},
			NEVER_CANCELLED
		);
	}

	/**
	 * Lowercases a generated form and rejects it when it contains anything but letters.
	 *
	 * @param word the generated form
	 * @return the normalized form, or null when it is to be dropped
	 */
	@Nullable
	private static String normalize(@Nonnull String word) {
		final String lowercased = word.toLowerCase(Locale.ROOT);
		for (int i = 0; i < lowercased.length(); i++) {
			if (!Character.isLetter(lowercased.charAt(i))) {
				return null;
			}
		}
		return lowercased.isEmpty() ? null : lowercased;
	}

	/**
	 * Opens a fixture file from the classpath or, failing that, from the first existing file-system candidate.
	 *
	 * @param resource   classpath location
	 * @param candidates file-system locations tried in order
	 * @return the open stream
	 * @throws IOException when a file exists but cannot be opened
	 */
	@Nonnull
	private static InputStream open(@Nonnull String resource, @Nonnull String[] candidates) throws IOException {
		final InputStream classpath = CzechWordForms.class.getResourceAsStream(resource);
		if (classpath != null) {
			return classpath;
		}
		for (final String candidate : candidates) {
			final Path path = Path.of(candidate);
			if (Files.isRegularFile(path)) {
				return Files.newInputStream(path);
			}
		}
		throw new IllegalStateException(
			"Hunspell fixture " + resource + " found neither on the classpath nor on disk."
		);
	}
}
