/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.utils;

import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.InvalidClassifierFormatException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Helper methods for manipulating classifiers (entity types, attribute names, reference names, ...).
 * This is mainly because we don't want to wrap string classifiers to self-validating value object because it would
 * introduce many inconveniences in API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ClassifierUtils {

	/**
	 * Keywords that cannot be used as classifiers because it would introduce ambiguity somewhere.
	 * Keywords must be written in camel case, validating method will check all cases.
	 */
	private static final Map<ClassifierType, Set<String>> RESERVED_KEYWORDS = Map.of(
		ClassifierType.SERVER_NAME,
		Set.of(),
		ClassifierType.CATALOG,
		Set.of(
			"system" // would collide with special system API endpoints for managing evitaDB
		),
		ClassifierType.ENTITY,
		Set.of(
			"catalog", // catalog queries in GraphQL, would collide with entity types
			"entity", // unknown single entity query in GraphQL/REST API, would collide with entity types
			"schema" // would collide with internal schema endpoints in REST API
		),
		ClassifierType.ATTRIBUTE,
		Set.of(
			"primaryKey", // argument in single entity query in GraphQL/REST API, would collide with attributes
			"locale", // argument in single entity query in GraphQL/REST API, would collide with attributes
			"priceValidIn", // argument in single entity query in GraphQL/REST API, would collide with attributes
			"priceValidInNow", // argument in single entity query in GraphQL/REST API, would collide with attributes
			"priceInCurrency", // argument in single entity query in GraphQL/REST API, would collide with attributes
			"priceInPriceList", // argument in single entity query in GraphQL/REST API, would collide with attributes
			"entityBodyContent", // argument in single entity query in REST API, would collide with attributes
			"associatedDataContent", // argument in single entity query in REST API, would collide with attributes
			"associatedDataContentAll", // argument in single entity query in REST API, would collide with attributes
			"attributeContent", // argument in single entity query in REST API, would collide with attributes
			"attributeContentAll", // argument in single entity query in REST API, would collide with attributes
			"referenceContent", // argument in single entity query in REST API, would collide with attributes
			"referenceContentAll", // argument in single entity query in REST API, would collide with attributes
			"priceContent", // argument in single entity query in REST API, would collide with attributes
			"dataInLocales" // argument in single entity query in REST API, would collide with attributes
		),
		ClassifierType.REFERENCE_ATTRIBUTE,
		Set.of(),
		ClassifierType.REFERENCE,
		Set.of(
			"primaryKey", // field in entity object in GraphQL/REST API, would collide with references
			"locale", // field in entity object in GraphQL/REST API, would collide with references
			"type", // field in entity object in GraphQL/REST API, would collide with references
			"price", // field in entity object in GraphQL/REST API, would collide with references
			"priceForSale", // field in entity object in GraphQL/REST API, would collide with references
			"prices", // field in entity object in GraphQL/REST API, would collide with references
			"attributes", // field in entity object in GraphQL/REST API, would collide with references
			"associatedData", // field in entity object in GraphQL/REST API, would collide with references
			"self" // field in hierarchy parents and statistics extra result in GraphQL API, would collide with hierarchy references
		)
	);
	/**
	 * Normalized version of {@link #RESERVED_KEYWORDS} that is case and separator insensitive. This is so that we
	 * don't have to compute it for every case for every checked classifier.
	 */
	private static final Map<ClassifierType, Set<Keyword>> NORMALIZED_RESERVED_KEYWORDS;
	private static final Pattern SUPPORTED_FORMAT_PATTERN = Pattern.compile("(^[\\p{Alpha}][\\p{Alnum}_.\\-~]{0,254}$)");

	static {
		NORMALIZED_RESERVED_KEYWORDS = RESERVED_KEYWORDS.entrySet()
			.stream()
			.map(entry -> Map.entry(
				entry.getKey(),
				entry.getValue()
					.stream()
					.map(ClassifierUtils::normalizeClassifier)
					.collect(Collectors.toUnmodifiableSet())
			))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * Retrieves a map containing the normalized reserved keywords for each classifier type.
	 *
	 * @return a map where the key is a ClassifierType and the value is a set of normalized keywords associated with that classifier type.
	 */
	@Nonnull
	public static Map<ClassifierType, Set<Keyword>> getNormalizedReservedKeywords() {
		return NORMALIZED_RESERVED_KEYWORDS;
	}

	/**
	 * Validates format of passed classifier. Classifier is considered valid if is not empty, doesn't have leading or
	 * trailing whitespace and has valid case (valid cases are base case, camelCase, PascalCase, snake_case,
	 * UPPER_SNAKE_CASE and kebab-case).
	 *
	 * @param classifier classifier to validate
	 * @throws InvalidClassifierFormatException if classifier is not valid according to above-mentioned rules
	 */
	public static void validateClassifierFormat(@Nonnull ClassifierType classifierType, @Nonnull String classifier) {
		Assert.isTrue(
			!classifier.isBlank(),
			() -> new InvalidClassifierFormatException(classifierType, classifier, "it is empty")
		);
		Assert.isTrue(
			classifier.equals(classifier.strip()),
			() -> new InvalidClassifierFormatException(classifierType, classifier, "it contains leading or trailing whitespace")
		);
		Assert.isTrue(
			!isKeyword(classifierType, classifier),
			() -> new InvalidClassifierFormatException(classifierType, classifier, "it is reserved keyword or can be converted into reserved keyword")
		);

		Assert.isTrue(
			SUPPORTED_FORMAT_PATTERN.matcher(classifier).matches(),
			() -> new InvalidClassifierFormatException(
				classifierType, classifier,
				"invalid name - only alphanumeric and these ASCII characters are allowed: _.-~"
			)
		);
	}

	/**
	 * Checks if classifier is any of reserved keywords or can be converted from any supported cases to any of reserved
	 * keywords.
	 */
	private static boolean isKeyword(@Nonnull ClassifierType classifierType, @Nonnull String classifier) {
		if (classifier.isBlank()) {
			return false;
		}
		final Keyword normalizedClassifier = normalizeClassifier(classifier);
		return Optional.ofNullable(NORMALIZED_RESERVED_KEYWORDS.get(classifierType))
			.map(keywords -> keywords.contains(normalizedClassifier))
			.orElse(false);
	}

	/**
	 * Converts classifier to comparable format (lower case, no separators).
	 */
	@Nonnull
	private static Keyword normalizeClassifier(@Nonnull String classifier) {
		final LongHashFunction xx3 = LongHashFunction.xx3();
		final String[] words = StringUtils.splitStringWithCaseIntoWords(classifier)
			.stream()
			.map(String::toLowerCase)
			.toArray(String[]::new);
		final long hash = xx3.hashLongs(
			Arrays.stream(words)
				.mapToLong(xx3::hashChars)
				.toArray()
		);
		return new Keyword(hash, classifier, words);
	}

	/**
	 * The keyword represents an internal keyword split by camel-case containing the has for quick comparison.
	 */
	public record Keyword(
		long hash,
		@Nonnull String classifier,
		@Nonnull String[] words
	) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			Keyword keyword = (Keyword) o;

			if (this.hash != keyword.hash) return false;
			return Arrays.equals(this.words, keyword.words);
		}

		@Override
		public int hashCode() {
			return Long.hashCode(this.hash);
		}
	}

}
