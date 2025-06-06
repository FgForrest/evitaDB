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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintProcessingUtils;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorResolver;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorWithReference;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.CLASSIFIER_NAMING_CONVENTION;

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
class ConstraintDescriptorResolver {

	@Nonnull private final CatalogSchemaContract catalogSchema;
	@Nonnull private final ConstraintType constraintType;
	@Nonnull private final DataLocatorResolver dataLocatorResolver;

	public ConstraintDescriptorResolver(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull ConstraintType constraintType) {
		this.catalogSchema = catalogSchema;
		this.constraintType = constraintType;
		this.dataLocatorResolver = new DataLocatorResolver(catalogSchema);
	}

	/**
	 * Extracts information from key and tries to find corresponding constraint for it. Returns empty if no constraint
	 * was found for the key.
	 */
	@Nonnull
	public Optional<ParsedConstraintDescriptor> resolve(@Nonnull ConstraintResolveContext resolveContext, @Nonnull String key) {
		// needed data to parse
		final ConstraintPropertyType derivedPropertyType;
		final ConstraintPropertyType fallbackPropertyType = ConstraintProcessingUtils.getFallbackPropertyTypeForDomain(resolveContext.dataLocator().targetDomain());
		final Optional<String> rawClassifier; // classifier in constraint key is transformed into some case to fit the key, it doesn't represent stored classifier
		ConstraintDescriptor constraintDescriptor = null;

		// parse prefix into property type first
		final Entry<String, ConstraintPropertyType> foundPropertyType = ConstraintProcessingUtils.getPropertyTypeFromPrefix(key);
		derivedPropertyType = foundPropertyType.getValue();
		final String remainingKey = key.substring(foundPropertyType.getKey().length());

		// parse remains of key into classifier and full constraint name
		final Deque<String> classifierWords = new LinkedList<>();
		final Deque<String> fullNameWords = new LinkedList<>(StringUtils.splitStringWithCaseIntoWords(remainingKey));
		while (constraintDescriptor == null && !fullNameWords.isEmpty()) {
			final String possibleClassifier = constructClassifier(classifierWords).orElse(null);
			final String possibleFullName = constructFullName(fullNameWords);

			constraintDescriptor = ConstraintDescriptorProvider.getConstraint(
					this.constraintType,
					derivedPropertyType,
					possibleFullName,
					possibleClassifier
				)
				.or(() -> {
					// when child constraint is allowed only as direct children of specific parent and domain between parent and child
					// didn't change, we can use simplified name without prefix and classifier
					if (derivedPropertyType.equals(ConstraintPropertyType.GENERIC) &&
						!resolveContext.isAtRoot() &&
						resolveContext.dataLocator().targetDomain().equals(Objects.requireNonNull(resolveContext.parentDataLocator()).targetDomain()) &&
						possibleClassifier == null) {
						return ConstraintDescriptorProvider.getConstraint(
							this.constraintType,
							fallbackPropertyType,
							possibleFullName,
							null
						);
					}
					return Optional.empty();
				})
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

		rawClassifier = constructClassifier(classifierWords);

		final Optional<String> actualClassifier = resolveActualClassifier(resolveContext, constraintDescriptor, rawClassifier);
		final DataLocator innerDataLocator = resolveInnerDataLocator(resolveContext, constraintDescriptor, actualClassifier.orElse(null));

		return Optional.of(new ParsedConstraintDescriptor(
			key,
			actualClassifier.orElse(null),
			constraintDescriptor,
			innerDataLocator
		));
	}

	@Nonnull
	private static Optional<String> constructClassifier(@Nonnull Deque<String> classifierWords) {
		if (classifierWords.isEmpty()) {
			return Optional.empty();
		}
		//noinspection DataFlowIssue
		return Optional.of(
			StringUtils.uncapitalize(String.join("", classifierWords))
		);
	}

	@Nonnull
	private static String constructFullName(@Nonnull Deque<String> fullNameWords) {
		Assert.isPremiseValid(
			!fullNameWords.isEmpty(),
			() -> new ExternalApiInternalError("Full name cannot be empty.")
		);
		//noinspection DataFlowIssue
		return StringUtils.uncapitalize(String.join("", fullNameWords));
	}

	/**
	 * Tries to find internally stored classifier based on property type represented by the constraint key classifier
	 * which may be in completely different case to fit the constraint key.
	 */
	@Nonnull
	private Optional<String> resolveActualClassifier(@Nonnull ConstraintResolveContext resolveContext,
	                                                 @Nonnull ConstraintDescriptor constraintDescriptor,
	                                                 @SuppressWarnings("OptionalUsedAsFieldOrParameterType") @Nonnull Optional<String> rawClassifier) {
		return rawClassifier.map(c -> {
			final Optional<ImplicitClassifier> implicitClassifier = constraintDescriptor.creator().implicitClassifier();
			if (implicitClassifier.isPresent()) {
				// there is possible only fixed classifier because silent has no value
				return ((FixedImplicitClassifier) implicitClassifier.get()).classifier();
			}

			final DataLocator parentDataLocator = resolveContext.dataLocator();
			if (parentDataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) {
				final ReferenceSchemaContract referenceSchema = this.catalogSchema.getEntitySchemaOrThrowException(dataLocatorWithReference.entityType())
					.getReference(Objects.requireNonNull(dataLocatorWithReference.referenceName()))
					// we can safely check this because if there was any reference schema there should be any classifier to begin with
					.orElseThrow(() -> new ExternalApiInternalError("Missing reference schema in data locator with reference. Cannot resolve classifier `" + c + "`."));

				return switch (constraintDescriptor.propertyType()) {
					case ATTRIBUTE -> referenceSchema
						.getAttributeByName(c, CLASSIFIER_NAMING_CONVENTION)
						.map(AttributeSchemaContract::getName)
						.orElseThrow(() -> new ExternalApiInternalError(
							"Could not find attribute schema for classifier `" + c + "`."
						));
					default -> throw new ExternalApiInternalError(
						"Constraint property type `" + constraintDescriptor.propertyType() + "` does not support classifier parameter."
					);
				};
			} else {
				final EntitySchemaContract schemaForClassifier = this.catalogSchema.getEntitySchemaOrThrowException(parentDataLocator.entityType());
				return switch (constraintDescriptor.propertyType()) {
					case ATTRIBUTE -> schemaForClassifier.getAttributeByName(c, CLASSIFIER_NAMING_CONVENTION)
						.map(NamedSchemaContract.class::cast)
						.or(() -> schemaForClassifier.getSortableAttributeCompoundByName(c, CLASSIFIER_NAMING_CONVENTION))
						.map(NamedSchemaContract::getName)
						.orElseThrow(() -> new ExternalApiInternalError(
							"Could not find attribute schema for classifier `" + c + "`."
						));
					case ASSOCIATED_DATA -> schemaForClassifier.getAssociatedDataByName(c, CLASSIFIER_NAMING_CONVENTION)
						.map(AssociatedDataSchemaContract::getName)
						.orElseThrow(() -> new ExternalApiInternalError(
							"Could not find associated data schema for classifier `" + c + "`."
						));
					case REFERENCE, HIERARCHY, FACET -> schemaForClassifier.getReferenceByName(c, CLASSIFIER_NAMING_CONVENTION)
						.map(ReferenceSchemaContract::getName)
						.orElseThrow(() -> new ExternalApiInternalError(
							"Could not find reference schema for classifier `" + c + "`."
						));
					default -> throw new ExternalApiInternalError(
						"Constraint property type `" + constraintDescriptor.propertyType() + "` does not support classifier parameter."
					);
				};
			}
		});
	}

	/**
	 * Resolves data locator relevant inside the parsed constraint based on property type which defines inner domain
	 * of the constraint for its parameters, mainly its children. We need this to provide needed data from classifier,
	 * otherwise we don't have any other point to gather such data later when resolving children.
	 */
	@Nonnull
	private DataLocator resolveInnerDataLocator(@Nonnull ConstraintResolveContext resolveContext,
	                                            @Nonnull ConstraintDescriptor constraintDescriptor,
	                                            @Nullable String classifier) {
		return this.dataLocatorResolver.resolveConstraintDataLocator(resolveContext.dataLocator(), constraintDescriptor, classifier);
	}


	/**
	 * Parsed client key representing actual constraint which is described by {@link ConstraintDescriptor}
	 */
	public record ParsedConstraintDescriptor(@Nonnull String originalKey,
	                                         @Nullable String classifier,
	                                         @Nonnull ConstraintDescriptor constraintDescriptor,
	                                         @Nonnull DataLocator innerDataLocator) {}
}
