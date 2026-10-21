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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Generates the **inflected word forms** of any hunspell fixture in `fulltext/hunspell/` (`cs_CZ`, `sk_SK`, `pl_PL`,
 * `ro_RO`) through Lucene's {@link WordFormGenerator} — the language-neutral sibling of {@link CzechWordForms},
 * which stays as it is for its existing callers. The `.dic` file holds headwords with affix flags, the `.aff`
 * file the rules (and the `SET` encoding Lucene reads them in), and the generator applies every rule a headword's
 * flags allow.
 *
 * Forms are lowercased (the root `Locale`) and anything containing a non-letter is dropped, exactly as
 * {@link CzechWordForms} does. Diacritics are kept — callers fold or stem as the measured chain does.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class HunspellWordForms {
	/**
	 * Classpath folder of the fixtures inside the functional-test module.
	 */
	private static final String RESOURCE_FOLDER = "/fulltext/hunspell/";
	/**
	 * File-system folders tried when a fixture is not on the classpath, relative to the working directory - the
	 * performance module's own directory first, then the repository root.
	 */
	private static final String[] FOLDER_CANDIDATES = {
		"../evita_functional_tests/src/test/resources/fulltext/hunspell/",
		"evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/"
	};
	/**
	 * Cancellation hook the generator calls periodically; generation is never cancelled here.
	 */
	private static final Runnable NEVER_CANCELLED = () -> { };

	/**
	 * Name of the fixture the forms come from, such as `sk_SK`.
	 */
	@Nonnull private final String fixture;
	/**
	 * The generator over the loaded dictionary.
	 */
	@Nonnull private final WordFormGenerator generator;

	/**
	 * @param fixture   name of the fixture
	 * @param generator the generator over the loaded dictionary
	 */
	private HunspellWordForms(@Nonnull String fixture, @Nonnull WordFormGenerator generator) {
		this.fixture = fixture;
		this.generator = generator;
	}

	/**
	 * Returns the fixture name of a locale: language and country joined by an underscore, as the files are named.
	 *
	 * @param locale a locale with both a language and a country, such as `sk-SK`
	 * @return the fixture name, such as `sk_SK`
	 */
	@Nonnull
	public static String fixtureOf(@Nonnull Locale locale) {
		if (locale.getLanguage().isEmpty() || locale.getCountry().isEmpty()) {
			throw new IllegalArgumentException(
				"A hunspell fixture is named by language and country, got locale `" + locale.toLanguageTag() + "`."
			);
		}
		return locale.getLanguage() + "_" + locale.getCountry();
	}

	/**
	 * Loads one fixture from the classpath, falling back to the known file-system locations.
	 *
	 * @param fixture the fixture name, such as `sk_SK`
	 * @return the generator
	 */
	@Nonnull
	public static HunspellWordForms load(@Nonnull String fixture) {
		try (
			final InputStream affix = open(fixture + ".aff");
			final InputStream dictionary = open(fixture + ".dic")
		) {
			final Dictionary hunspell = new Dictionary(affix, List.of(dictionary), false, SortingStrategy.inMemory());
			return new HunspellWordForms(fixture, new WordFormGenerator(hunspell));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (ParseException e) {
			throw new IllegalStateException("The hunspell " + fixture + " fixture cannot be parsed.", e);
		}
	}

	/**
	 * @return name of the fixture the forms come from
	 */
	@Nonnull
	public String getFixture() {
		return this.fixture;
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
	 * @param fileName the file name inside the fixture folder
	 * @return the open stream
	 * @throws IOException when a file exists but cannot be opened
	 */
	@Nonnull
	private static InputStream open(@Nonnull String fileName) throws IOException {
		final InputStream classpath = HunspellWordForms.class.getResourceAsStream(RESOURCE_FOLDER + fileName);
		if (classpath != null) {
			return classpath;
		}
		for (final String candidate : FOLDER_CANDIDATES) {
			final Path path = Path.of(candidate + fileName);
			if (Files.isRegularFile(path)) {
				return Files.newInputStream(path);
			}
		}
		throw new IllegalStateException(
			"Hunspell fixture " + RESOURCE_FOLDER + fileName + " found neither on the classpath nor on disk."
		);
	}
}
