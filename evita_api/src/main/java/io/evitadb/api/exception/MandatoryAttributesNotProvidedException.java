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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.of;

/**
 * Exception is thrown when store entity lacks mandatory attributes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MandatoryAttributesNotProvidedException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -8575134582708076162L;

	public record MissingReferenceAttribute(@Nonnull String referenceName, @Nonnull List<AttributeKey> missingMandatedAttributes) {}

	public MandatoryAttributesNotProvidedException(@Nonnull String entityName, @Nonnull List<?> missingMandatedAttributes) {
		super(composeErrorMessage(entityName, missingMandatedAttributes));
	}

	private static String composeErrorMessage(@Nonnull String entityName, @Nonnull List<?> missingMandatedAttributes) {
		return Stream.concat(
			Stream.of(
				composeErrorMessage(
					entityName,
					"",
					missingMandatedAttributes
						.stream()
						.filter(AttributeKey.class::isInstance)
						.map(AttributeKey.class::cast)
						.collect(Collectors.toList())
				)
			),
			missingMandatedAttributes
				.stream()
				.filter(MissingReferenceAttribute.class::isInstance)
				.map(it -> composeErrorMessage(
					entityName,
					" reference `" + ((MissingReferenceAttribute)it).referenceName() + "`",
					((MissingReferenceAttribute)it).missingMandatedAttributes()
				))
		).collect(Collectors.joining("\n"));
	}

	private static String composeErrorMessage(@Nonnull String entityName, @Nonnull String reference, @Nonnull List<AttributeKey> missingMandatedAttributes) {
		final String missingGlobalAttributes = of(missingMandatedAttributes.stream()
			.filter(it -> it.locale() == null)
			.map(it -> "`" + it.attributeName() + "`")
			.collect(Collectors.joining(", ")))
			.filter(it -> !it.isBlank())
			.map(it ->
				"Entity `" + entityName + "`" + reference + " requires these attributes to be non-null, " +
					"but they are missing: " + it + "."
			)
			.orElse("");

		final String missingLocalizedAttributes = of(
			missingMandatedAttributes.stream()
				.filter(it -> it.locale() != null)
				.collect(Collectors.groupingBy(AttributeKey::attributeName))
				.entrySet()
				.stream()
				.map(it ->
					"`" + it.getKey() + "` in locales: " +
						it.getValue()
							.stream()
							.map(AttributeKey::locale)
							.filter(Objects::nonNull)
							.map(locale -> "`" + locale.toLanguageTag() + "`")
							.collect(Collectors.joining(", "))
				)
				.collect(Collectors.joining(", "))
		)
			.filter(it -> !it.isBlank())
			.map(it ->
				"Entity `" + entityName + "`" + reference + " requires these localized attributes to be " +
					"specified for all localized versions of the entity, " +
					"but values for some locales are missing: " + it + "."
			)
			.orElse("");

		return Stream.of(
				missingGlobalAttributes,
				missingLocalizedAttributes
			)
			.filter(it -> !it.isBlank())
			.collect(Collectors.joining("\n"));
	}
}
