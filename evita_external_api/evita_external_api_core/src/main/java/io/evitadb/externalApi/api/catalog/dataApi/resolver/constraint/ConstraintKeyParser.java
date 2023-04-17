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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintProcessingUtils;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.CLASSIFIER_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Used to parse input constraint key in {@link ConstraintResolver} to find corresponding {@link ConstraintDescriptor} for it.
 *
 * <h3>Formats</h3>
 * This parser supports following key formats
 * Key can have one of 3 formats depending on descriptor data:
 * <ul>
 *     <li>`{fullName}` - if it's generic constraint without classifier</li>
 *     <li>`{propertyType}{fullName}` - if it's not generic constraint and doesn't have classifier</li>
 *     <li>`{propertyType}{classifier}{fullName}` - if it's not generic constraint and has classifier</li>
 * </ul>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ConstraintKeyParser {

	// todo lho spustit perf testy pro gql a rest

	@Nonnull
	private final ConstraintType constraintType;

	/**
	 * Extracts information from key and tries to find corresponding constraint for it. Returns empty if no constraint
	 * was found for the key.
	 */
	@Nonnull
	public Optional<ParsedKey> parse(@Nonnull String key) {
		String remainingKey = key;

		// needed data to parse
		final ConstraintPropertyType propertyType;
		final String classifier;
		final String fullName;
		ConstraintDescriptor constraintDescriptor = null;

		// parse prefix into property type first
		final Entry<String, ConstraintPropertyType> foundPropertyType = ConstraintProcessingUtils.getPropertyTypeByPrefix(key);
		propertyType = foundPropertyType.getValue();
		remainingKey = remainingKey.substring(foundPropertyType.getKey().length());

		// parse remains of key into classifier and full constraint name
		final Deque<String> classifierWords = new LinkedList<>();
		final Deque<String> fullNameWords = new LinkedList<>(StringUtils.splitStringWithCaseIntoWords(remainingKey));
		while (constraintDescriptor == null && !fullNameWords.isEmpty()) {
			final String possibleClassifier = constructClassifier(classifierWords);
			final String possibleFullName = constructFullName(fullNameWords);

			constraintDescriptor = ConstraintDescriptorProvider.getConstraint(
				constraintType,
				propertyType,
				possibleFullName,
				possibleClassifier
			)
				.orElse(null);

			if (constraintDescriptor == null) {
				// this combination didn't work out, move words around and try again
				classifierWords.add(fullNameWords.removeFirst());
			}
		}
		if (constraintDescriptor == null) {
			// couldn't find any valid combination of classifier and full name to find proper constraint
			return Optional.empty();
		}

		classifier = constructClassifier(classifierWords);
		fullName = constructFullName(fullNameWords);
		return Optional.of(new ParsedKey(key, propertyType, classifier, fullName, constraintDescriptor));
	}

	@Nullable
	private String constructClassifier(@Nonnull Deque<String> classifierWords) {
		if (classifierWords.isEmpty()) {
			return null;
		}
		return StringUtils.toSpecificCase(String.join("", classifierWords), CLASSIFIER_NAMING_CONVENTION);
	}

	@Nonnull
	private String constructFullName(@Nonnull Deque<String> fullNameWords) {
		Assert.isPremiseValid(
			!fullNameWords.isEmpty(),
			() -> new ExternalApiInternalError("Full name cannot be empty.")
		);
		return StringUtils.toSpecificCase(String.join("", fullNameWords), PROPERTY_NAME_NAMING_CONVENTION);
	}

	/**
	 * Parsed client key for finding original constraint descriptor.
	 */
	public record ParsedKey(@Nonnull String originalKey,
	                        @Nonnull ConstraintPropertyType propertyType,
	                        @Nullable String classifier,
	                        @Nonnull String fullName,
	                        @Nonnull ConstraintDescriptor constraintDescriptor) {}
}
