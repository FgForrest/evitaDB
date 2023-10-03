/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

	private static final Pattern CATALOG_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
	private static final Pattern SUPPORTED_FORMAT_PATTERN = Pattern.compile("(^[\\p{Alpha}][\\p{Alnum}_.:+\\-@/\\\\|`~]*$)");

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

		if (classifierType == ClassifierType.CATALOG) {
			Assert.isTrue(
				CATALOG_NAME_PATTERN.matcher(classifier).matches(),
				() -> new InvalidClassifierFormatException(
					classifierType, classifier,
					"invalid name - only alphanumeric and these ASCII characters are allowed (with maximal length of 255 characters): _-"
				)
			);
		} else {
			Assert.isTrue(
				SUPPORTED_FORMAT_PATTERN.matcher(classifier).matches(),
				() -> new InvalidClassifierFormatException(
					classifierType, classifier,
					"invalid name - only alphanumeric and these ASCII characters are allowed: _.:+-@/\\|`~"
				)
			);
		}
	}

	/**
	 * Checks if classifier is any of reserved keywords or can be converted from any supported cases to any of reserved
	 * keywords.
	 */
	private static boolean isKeyword(@Nonnull ClassifierType classifierType, @Nonnull String classifier) {
		if (classifier.isBlank()) {
			return false;
		}
		return Optional.ofNullable(RESERVED_KEYWORDS.get(classifierType))
			.map(keywords -> keywords.stream()
				.flatMap(keyword -> Arrays.stream(NamingConvention.values())
					.map(namingConvention -> StringUtils.toSpecificCase(keyword, namingConvention)))
				.anyMatch(keyword -> keyword.equals(classifier) ||
					keyword.equals(StringUtils.toCamelCase(classifier)) ||
					keyword.equals(StringUtils.toPascalCase(classifier)) ||
					keyword.equals(StringUtils.toSnakeCase(classifier)) ||
					keyword.equals(StringUtils.toUpperSnakeCase(classifier)) ||
					keyword.equals(StringUtils.toKebabCase(classifier))
				))
			.orElse(false);
	}

}
