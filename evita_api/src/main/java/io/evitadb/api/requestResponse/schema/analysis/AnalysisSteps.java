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

package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Factories for analysis descriptions shared by attribute normalization and future analysis APIs.
 * These factories describe steps; implementations belong to the server.
 *
 * The constants are the only definition of the built-in step and parameter names: the server's built-in providers
 * report them, and a Java model can name them in `@AnalysisStepDef`, which needs a compile-time constant. A step from
 * another provider is described with {@link #step(String, AnalysisStepParameter...)}.
 */
public final class AnalysisSteps {
	/** Name of the lowercase step. */
	public static final String LOWERCASE = "lowercase";
	/** Name of the ASCII folding step. */
	public static final String ASCII_FOLDING = "ascii-folding";
	/** Name of the whitespace trimming step. */
	public static final String TRIM = "trim";
	/** Name of the character removal step. */
	public static final String REMOVE_CHARACTERS = "remove-characters";
	/** Parameter naming the characters to remove. */
	public static final String CHARACTERS = "characters";

	/** Name of the markup stripping step of the fulltext text phase. */
	public static final String HTML_STRIP = "html-strip";
	/** Name of the Unicode word-break tokenizer that splits fulltext into words. */
	public static final String STANDARD_TOKENIZER = "standard";
	/** Name of the splitting step that also splits a word at each letter/digit boundary. */
	public static final String WORD_NUMBER_SPLIT = "word-number-split";
	/** Name of the step removing the words of word-list resources. */
	public static final String STOP_WORDS = "stop-words";
	/** Name of the Czech stemming step (stems and folds diacritics). */
	public static final String CZECH_STEMMING = "czech-stemming";
	/** Name of the Slovak stemming step (stems and folds diacritics). */
	public static final String SLOVAK_STEMMING = "slovak-stemming";
	/** Name of the Polish stemming step (stems and folds diacritics). */
	public static final String POLISH_STEMMING = "polish-stemming";
	/** Name of the Romanian stemming step (stems and folds diacritics). */
	public static final String ROMANIAN_STEMMING = "romanian-stemming";
	/** Name of the English stemming step (the Porter stemmer). */
	public static final String ENGLISH_STEMMING = "english-stemming";
	/** Name of the German stemming step (umlaut normalization and a light stemmer). */
	public static final String GERMAN_STEMMING = "german-stemming";
	/** Name of the step running the built-in stemming step of the field's language, if it has one. */
	public static final String STEMMING = "stemming";
	/** Name of the step replacing the Romanian cedilla letters with their comma-below forms. */
	public static final String ROMANIAN_NORMALIZATION = "romanian-normalization";
	/** Name of the step removing the English possessive `'s`. */
	public static final String ENGLISH_POSSESSIVE = "english-possessive";
	/** Name of the query-side step expanding a word to its synonym group. */
	public static final String SYNONYMS = "synonyms";
	/** Parameter naming the word-list resources of {@link #STOP_WORDS}. */
	public static final String WORDS = "words";
	/** Parameter naming the word-list resources whose words a stemming step leaves unstemmed. */
	public static final String PROTECTED_WORDS = "protected-words";
	/** Parameter naming the synonym-list resources of {@link #SYNONYMS}. */
	public static final String SYNONYM_LISTS = "synonyms";
	/** Name of the built-in, read-only Czech stop list resource. */
	public static final String CZECH_STOP_WORDS = "czech-stop-words";
	/** Name of the built-in, read-only Polish stop list resource. */
	public static final String POLISH_STOP_WORDS = "polish-stop-words";
	/** Name of the built-in, read-only Romanian stop list resource. */
	public static final String ROMANIAN_STOP_WORDS = "romanian-stop-words";
	/** Name of the built-in, read-only English stop list resource. */
	public static final String ENGLISH_STOP_WORDS = "english-stop-words";
	/** Name of the built-in, read-only German stop list resource. */
	public static final String GERMAN_STOP_WORDS = "german-stop-words";
	/** Names of all built-in resources - reserved, a catalog schema cannot declare a resource of the same name. */
	public static final Set<String> BUILT_IN_RESOURCES = Set.of(
		CZECH_STOP_WORDS, POLISH_STOP_WORDS, ROMANIAN_STOP_WORDS, ENGLISH_STOP_WORDS, GERMAN_STOP_WORDS
	);
	/**
	 * The step parameters that read resources, each with the kind of resource it reads. The catalog schema checks
	 * every such parameter of every analyzer step against the resources it names.
	 */
	public static final Map<String, FulltextResourceKind> RESOURCE_PARAMETERS = Map.of(
		WORDS, FulltextResourceKind.WORD_LIST,
		PROTECTED_WORDS, FulltextResourceKind.WORD_LIST,
		SYNONYM_LISTS, FulltextResourceKind.SYNONYM_LIST
	);

	/** Prevents instantiation of this factory holder. */
	private AnalysisSteps() {
	}

	/**
	 * Describes lowercasing using the index locale, or the root locale for non-localized values.
	 *
	 * @return description of the `lowercase` step without parameters
	 */
	@Nonnull
	public static AnalysisStep lowercase() {
		return step(LOWERCASE);
	}

	/**
	 * Describes folding accented and other supported characters to their ASCII equivalents.
	 *
	 * @return description of the `ascii-folding` step without parameters
	 */
	@Nonnull
	public static AnalysisStep asciiFolding() {
		return step(ASCII_FOLDING);
	}

	/**
	 * Describes removing leading and trailing whitespace, including non-breaking spaces.
	 *
	 * @return description of the `trim` step without parameters
	 */
	@Nonnull
	public static AnalysisStep trim() {
		return step(TRIM);
	}

	/**
	 * Describes removing every occurrence of the supplied nonempty set of characters.
	 *
	 * @param characters the characters to remove, each of them independently; never `null` or empty
	 * @return description of the `remove-characters` step carrying the `characters` parameter
	 */
	@Nonnull
	public static AnalysisStep removeCharacters(@Nonnull String characters) {
		Assert.notNull(characters, "Characters must not be null.");
		Assert.isTrue(!characters.isEmpty(), "Characters must not be empty.");
		return step(REMOVE_CHARACTERS, parameter(CHARACTERS, characters));
	}

	/**
	 * Describes stripping markup from the text before it is split into words: tags go, entities are decoded. Acts on
	 * the index side only - a query is what the user typed. Belongs to the text phase of a fulltext analyzer.
	 *
	 * @return description of the `html-strip` step without parameters
	 */
	@Nonnull
	public static AnalysisStep htmlStrip() {
		return step(HTML_STRIP);
	}

	/**
	 * Describes splitting text into words at Unicode word breaks (UAX #29) - the tokenizer of a fulltext language
	 * that declares no other one.
	 *
	 * @return description of the `standard` tokenizer without parameters
	 */
	@Nonnull
	public static AnalysisStep standardTokenizer() {
		return step(STANDARD_TOKENIZER);
	}

	/**
	 * Describes splitting each word also at its letter/digit boundaries while keeping the original word: `iPhone15`
	 * yields `iPhone15`, `iPhone` and `15`. Language-level word splitting only, opted into per language.
	 *
	 * @return description of the `word-number-split` step without parameters
	 */
	@Nonnull
	public static AnalysisStep wordNumberSplit() {
		return step(WORD_NUMBER_SPLIT);
	}

	/**
	 * Describes removing the words of the given word-list resources, on both sides.
	 *
	 * @param lists the word-list resources to remove words of, at least one
	 * @return description of the `stop-words` step carrying the `words` parameter
	 */
	@Nonnull
	public static AnalysisStep stopWords(@Nonnull FulltextResourceReference... lists) {
		return step(STOP_WORDS, parameter(WORDS, resourceNames(lists)));
	}

	/**
	 * Describes Czech stemming: on the index side the word is stemmed and then its diacritics are folded, on the query
	 * side it is folded and then expanded to its variant stems. The step carries the whole fold, so
	 * {@link #asciiFolding()} placed before it is refused.
	 *
	 * @param parameters optional parameters, such as {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `czech-stemming` step
	 */
	@Nonnull
	public static AnalysisStep czechStemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(CZECH_STEMMING, parameters);
	}

	/**
	 * Describes Slovak stemming, which works like {@link #czechStemming(AnalysisStepParameter...)}.
	 *
	 * @param parameters optional parameters, such as {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `slovak-stemming` step
	 */
	@Nonnull
	public static AnalysisStep slovakStemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(SLOVAK_STEMMING, parameters);
	}

	/**
	 * Describes Polish stemming with the Snowball stemmer; the fold works like in
	 * {@link #czechStemming(AnalysisStepParameter...)}.
	 *
	 * @param parameters optional parameters, such as {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `polish-stemming` step
	 */
	@Nonnull
	public static AnalysisStep polishStemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(POLISH_STEMMING, parameters);
	}

	/**
	 * Describes Romanian stemming; the fold works like in {@link #czechStemming(AnalysisStepParameter...)}.
	 *
	 * @param parameters optional parameters, such as {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `romanian-stemming` step
	 */
	@Nonnull
	public static AnalysisStep romanianStemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(ROMANIAN_STEMMING, parameters);
	}

	/**
	 * Describes English stemming with the Porter stemmer, on both sides. It neither folds nor removes possessives -
	 * see {@link #englishPossessive()}.
	 *
	 * @param parameters optional parameters, such as {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `english-stemming` step
	 */
	@Nonnull
	public static AnalysisStep englishStemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(ENGLISH_STEMMING, parameters);
	}

	/**
	 * Describes German stemming: umlaut normalization and a light stemmer, on both sides.
	 *
	 * @param parameters optional parameters, such as {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `german-stemming` step
	 */
	@Nonnull
	public static AnalysisStep germanStemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(GERMAN_STEMMING, parameters);
	}

	/**
	 * Describes running the built-in stemming step of the field's language, and nothing for a language without one.
	 * Lets an analyzer defined for all languages stem each of them its own way.
	 *
	 * @param parameters optional parameters passed to the language's stemming step, such as
	 *                   {@link #protectedWords(FulltextResourceReference...)}
	 * @return description of the `stemming` step
	 */
	@Nonnull
	public static AnalysisStep stemming(@Nonnull AnalysisStepParameter... parameters) {
		return step(STEMMING, parameters);
	}

	/**
	 * Describes replacing the Romanian cedilla letters `ş` and `ţ` with their comma-below forms `ș` and `ț`, on both
	 * sides. It runs before stop words, so it is a step of its own and not part of Romanian stemming.
	 *
	 * @return description of the `romanian-normalization` step without parameters
	 */
	@Nonnull
	public static AnalysisStep romanianNormalization() {
		return step(ROMANIAN_NORMALIZATION);
	}

	/**
	 * Describes removing the English possessive `'s` from a word, on both sides.
	 *
	 * @return description of the `english-possessive` step without parameters
	 */
	@Nonnull
	public static AnalysisStep englishPossessive() {
		return step(ENGLISH_POSSESSIVE);
	}

	/**
	 * Describes expanding a query word to the other words of its synonym group in the given synonym-list resources.
	 * Acts on the query side only, so editing the lists takes effect on the next query without reindexing.
	 *
	 * @param lists the synonym-list resources, at least one
	 * @return description of the `synonyms` step carrying the `synonyms` parameter
	 */
	@Nonnull
	public static AnalysisStep synonyms(@Nonnull FulltextResourceReference... lists) {
		return step(SYNONYMS, parameter(SYNONYM_LISTS, resourceNames(lists)));
	}

	/**
	 * Creates the `protected-words` parameter of a stemming step: the words of the given word-list resources stay
	 * unstemmed.
	 *
	 * @param lists the word-list resources, at least one
	 * @return the `protected-words` parameter
	 */
	@Nonnull
	public static AnalysisStepParameter protectedWords(@Nonnull FulltextResourceReference... lists) {
		return parameter(PROTECTED_WORDS, resourceNames(lists));
	}

	/**
	 * Refers to a fulltext resource by name - one declared in the catalog schema or a built-in one.
	 *
	 * @param name the name of the resource
	 * @return the reference
	 */
	@Nonnull
	public static FulltextResourceReference resource(@Nonnull String name) {
		return new FulltextResourceReference(name);
	}

	/**
	 * Refers to the built-in Czech stop list.
	 *
	 * @return reference to the {@link #CZECH_STOP_WORDS} resource
	 */
	@Nonnull
	public static FulltextResourceReference czechStopWords() {
		return resource(CZECH_STOP_WORDS);
	}

	/**
	 * Refers to the built-in Polish stop list.
	 *
	 * @return reference to the {@link #POLISH_STOP_WORDS} resource
	 */
	@Nonnull
	public static FulltextResourceReference polishStopWords() {
		return resource(POLISH_STOP_WORDS);
	}

	/**
	 * Refers to the built-in Romanian stop list.
	 *
	 * @return reference to the {@link #ROMANIAN_STOP_WORDS} resource
	 */
	@Nonnull
	public static FulltextResourceReference romanianStopWords() {
		return resource(ROMANIAN_STOP_WORDS);
	}

	/**
	 * Refers to the built-in English stop list.
	 *
	 * @return reference to the {@link #ENGLISH_STOP_WORDS} resource
	 */
	@Nonnull
	public static FulltextResourceReference englishStopWords() {
		return resource(ENGLISH_STOP_WORDS);
	}

	/**
	 * Refers to the built-in German stop list.
	 *
	 * @return reference to the {@link #GERMAN_STOP_WORDS} resource
	 */
	@Nonnull
	public static FulltextResourceReference germanStopWords() {
		return resource(GERMAN_STOP_WORDS);
	}

	/**
	 * Creates word-list content for a fulltext resource - a stop list or a list of protected words.
	 *
	 * @param words the words, none of them blank
	 * @return the word-list content
	 */
	@Nonnull
	public static WordList wordList(@Nonnull String... words) {
		Assert.notNull(words, "Words must not be null.");
		return new WordList(Arrays.asList(words));
	}

	/**
	 * Creates synonym-list content for a fulltext resource.
	 *
	 * @param groups the synonym groups
	 * @return the synonym-list content
	 */
	@Nonnull
	public static SynonymList synonymList(@Nonnull SynonymGroup... groups) {
		Assert.notNull(groups, "Synonym groups must not be null.");
		return new SynonymList(Arrays.asList(groups));
	}

	/**
	 * Creates one group of words a query treats as equal, for {@link #synonymList(SynonymGroup...)}.
	 *
	 * @param words the single words of the group, at least two
	 * @return the synonym group
	 */
	@Nonnull
	public static SynonymGroup synonymGroup(@Nonnull String... words) {
		Assert.notNull(words, "Synonym group words must not be null.");
		return new SynonymGroup(Arrays.asList(words));
	}

	/**
	 * Converts resource references into the value of a parameter that reads resources.
	 *
	 * @param lists the references, at least one
	 * @return the resource names in the given order
	 */
	@Nonnull
	private static String[] resourceNames(@Nonnull FulltextResourceReference[] lists) {
		Assert.notNull(lists, "Resources must not be null.");
		Assert.isTrue(lists.length > 0, "At least one resource must be named.");
		final String[] names = new String[lists.length];
		for (int i = 0; i < lists.length; i++) {
			Assert.notNull(lists[i], "Resource must not be null.");
			names[i] = lists[i].name();
		}
		return names;
	}

	/**
	 * Describes an installed step without executing it. The server checks provider availability, normalizer safety
	 * and provider-specific parameter restrictions when adopting a schema. Duplicate parameter names are refused here.
	 *
	 * @param name stable provider name, such as `lowercase` or `ascii-folding`
	 * @param parameters named evitaDB values in declaration order; array values are defensively copied
	 * @return immutable description suitable for a normalization chain
	 */
	@Nonnull
	public static AnalysisStep step(
		@Nonnull String name, @Nonnull AnalysisStepParameter... parameters
	) {
		Assert.notNull(parameters, "Analysis parameters must not be null.");
		final Map<String, Serializable> values = CollectionUtils.createLinkedHashMap(parameters.length);
		for (final AnalysisStepParameter parameter : parameters) {
			Assert.notNull(parameter, "Analysis parameter must not be null.");
			Assert.isTrue(!values.containsKey(parameter.name()), "Duplicate analysis parameter: " + parameter.name());
			values.put(parameter.name(), parameter.value());
		}
		return new AnalysisStep(name, values);
	}

	/**
	 * Creates a named parameter for {@link #step(String, AnalysisStepParameter...)}.
	 * Only supported evitaDB scalar values and arrays are accepted; arrays are defensively copied.
	 *
	 * @param name nonblank provider parameter name
	 * @param value non-null supported value
	 * @return immutable named parameter
	 */
	@Nonnull
	public static AnalysisStepParameter parameter(@Nonnull String name, @Nonnull Serializable value) {
		return new AnalysisStepParameter(name, value);
	}
}
