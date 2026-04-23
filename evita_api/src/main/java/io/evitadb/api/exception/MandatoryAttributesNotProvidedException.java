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
 * Exception thrown when attempting to persist an entity that lacks required attributes defined as mandatory
 * in its schema.
 *
 * Attributes can be marked as mandatory (non-nullable) in the entity schema using
 * {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract}. When an entity is saved or updated,
 * evitaDB validates that all mandatory attributes are present. This exception is thrown if any required
 * values are missing.
 *
 * The exception handles two categories of mandatory attributes:
 * - **Entity attributes**: direct attributes on the entity itself
 * - **Reference attributes**: attributes attached to entity references
 *
 * Each category is further divided into:
 * - **Global attributes**: non-localized values required for all entities
 * - **Localized attributes**: values required for each locale the entity supports
 *
 * The error message groups missing attributes by entity vs. reference and by locale to facilitate debugging.
 *
 * **Usage Context:**
 * - {@link io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor}: validates attribute
 *   completeness before persisting entity mutations
 * - Thrown during entity upsert operations when mandatory attributes are absent
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MandatoryAttributesNotProvidedException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -8575134582708076162L;

	/**
	 * Record representing missing mandatory attributes on a specific reference.
	 *
	 * @param referenceName the name of the reference that has missing mandatory attributes
	 * @param missingMandatedAttributes list of attribute keys that are required but missing on this reference
	 */
	public record MissingReferenceAttribute(
		@Nonnull String referenceName,
		@Nonnull List<AttributeKey> missingMandatedAttributes
	) {}

	/**
	 * Creates a new exception listing all missing mandatory attributes.
	 *
	 * @param entityName the name (type) of the entity being validated
	 * @param missingMandatedAttributes list of missing attributes; may contain {@link AttributeKey} instances
	 *                                  (for entity attributes) or {@link MissingReferenceAttribute} instances
	 *                                  (for reference attributes)
	 */
	public MandatoryAttributesNotProvidedException(@Nonnull String entityName, @Nonnull List<?> missingMandatedAttributes) {
		super(composeErrorMessage(entityName, missingMandatedAttributes));
	}

	/**
	 * Creates a new exception with a pre-composed error message. Used for cases where the standard
	 * error message composition is not applicable, such as when the entity has no locales but
	 * non-nullable localized attributes are defined in the schema.
	 *
	 * @param message the complete error message
	 */
	public MandatoryAttributesNotProvidedException(@Nonnull String message) {
		super(message);
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

	private static String composeErrorMessage(
		@Nonnull String entityName,
		@Nonnull String reference,
		@Nonnull List<AttributeKey> missingMandatedAttributes
	) {
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
