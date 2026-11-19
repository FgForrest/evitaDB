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

import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everyday self-match samples of the built-in languages - Czech, Slovak, Polish, Romanian, English and German - used
 * only in their own language, never in the root definition.
 */
final class BuiltInFulltextSamples {
	/** Twelve neutral words per language, including inflected forms and the language's diacritics. */
	@Nonnull private static final Map<String, List<String>> SAMPLES = createSamples();

	/** Prevents instantiation of the sample holder. */
	private BuiltInFulltextSamples() {
	}

	/**
	 * Builds the language map once; it is frozen because every behaviour check of every catalog shares it.
	 * @return twelve everyday words for each built-in language
	 */
	@Nonnull
	private static Map<String, List<String>> createSamples() {
		final Map<String, List<String>> samples = CollectionUtils.createHashMap(6);
		samples.put("cs", List.of("Hrnek", "hrnky", "židle", "židlí", "stůl", "stoly", "modrá", "zelené",
			"vaří", "vařili", "dveře", "okna"));
		samples.put("sk", List.of("Hrnček", "hrnčeky", "stolička", "stoličky", "stôl", "stoly", "modrá", "zelené",
			"varí", "varili", "dvere", "okná"));
		samples.put("pl", List.of("Kubek", "kubki", "krzesło", "krzesła", "stół", "stoły", "niebieski", "zielone",
			"gotuje", "gotowali", "drzwi", "okna"));
		samples.put("ro", List.of("Cană", "căni", "scaun", "scaune", "masă", "mese", "albastru", "verzi",
			"gătește", "gătesc", "ușă", "ferestre"));
		samples.put("en", List.of("Cup", "cups", "chair", "chairs", "table", "tables", "blue", "green",
			"cooks", "cooking", "doors", "windows"));
		samples.put("de", List.of("Tasse", "Tassen", "Stuhl", "Stühle", "Tisch", "Tische", "blau", "grüne",
			"kocht", "kochen", "Türen", "Fenster"));
		return Collections.unmodifiableMap(samples);
	}

	/**
	 * Returns the samples belonging to the checked language.
	 * @param locale language of the resolved chains
	 * @return twelve words for a built-in language, otherwise an empty list
	 */
	@Nonnull
	static List<String> forLocale(@Nonnull Locale locale) {
		return SAMPLES.getOrDefault(locale.getLanguage(), List.of());
	}
}
