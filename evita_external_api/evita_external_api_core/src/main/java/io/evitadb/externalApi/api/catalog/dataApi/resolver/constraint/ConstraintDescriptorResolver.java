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

import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintProcessingUtils;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorWithReference;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.FacetDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
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
class ConstraintDescriptorResolver {

	@Nonnull
	private final CatalogSchemaContract catalogSchema;
	@Nonnull
	private final ConstraintType constraintType;

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
					constraintType,
					derivedPropertyType,
					possibleFullName,
					possibleClassifier
				)
				.or(() -> {
					// when child constraint is allowed only as direct children of specific parent and domain between parent and child
					// didn't change, we can use simplified name without prefix and classifier
					if (derivedPropertyType.equals(ConstraintPropertyType.GENERIC) &&
						!resolveContext.isAtRoot() &&
						resolveContext.dataLocator().targetDomain().equals(resolveContext.parentDataLocator().targetDomain()) &&
						possibleClassifier == null) {
						return ConstraintDescriptorProvider.getConstraint(
							constraintType,
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
		final DataLocator innerDataLocator = resolveInnerDataLocator(resolveContext, key, constraintDescriptor, actualClassifier);

		return Optional.of(new ParsedConstraintDescriptor(
			key,
			actualClassifier.orElse(null),
			constraintDescriptor,
			innerDataLocator
		));
	}

	@Nonnull
	private Optional<String> constructClassifier(@Nonnull Deque<String> classifierWords) {
		if (classifierWords.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(
			StringUtils.toSpecificCase(String.join("", classifierWords), CLASSIFIER_NAMING_CONVENTION)
		);
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
	 * Tries to find internally stored classifier based on property type represented by the constraint key classifier
	 * which may be in completely different case to fit the constraint key.
	 */
	@Nonnull
	private Optional<String> resolveActualClassifier(@Nonnull ConstraintResolveContext resolveContext,
	                                                 @Nonnull ConstraintDescriptor constraintDescriptor,
	                                                 @Nonnull Optional<String> rawClassifier) {
		return rawClassifier.map(c -> {
			if (constraintDescriptor.creator().hasImplicitClassifier()) {
				// there is possible only fixed classifier because silent has no value
				return ((FixedImplicitClassifier) constraintDescriptor.creator().implicitClassifier()).classifier();
			}

			final DataLocator parentDataLocator = resolveContext.dataLocator();
			if (parentDataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) {
				final String referenceName = dataLocatorWithReference.referenceName();

				return switch (constraintDescriptor.propertyType()) {
					case ATTRIBUTE -> {
						final AttributeSchemaProvider<?> attributeSchemaProvider;
						if (referenceName == null) {
							attributeSchemaProvider = findEntitySchema(dataLocatorWithReference);
						} else {
							attributeSchemaProvider = findEntitySchema(parentDataLocator)
								.getReference(referenceName)
								.orElseThrow(() -> new ExternalApiInternalError("Could not find reference schema for reference `" + referenceName + "`."));
						}
						yield attributeSchemaProvider
							.getAttributeByName(c, CLASSIFIER_NAMING_CONVENTION)
							.map(AttributeSchemaContract::getName)
							.orElseThrow(() -> new ExternalApiInternalError(
								"Could not find attribute schema for classifier `" + c + "`."
							));
					}
					default -> throw new ExternalApiInternalError(
						"Constraint property type `" + constraintDescriptor.propertyType() + "` does not support classifier parameter."
					);
				};
			} else {
				final EntitySchemaContract schemaForClassifier = findEntitySchema(parentDataLocator);
				return switch (constraintDescriptor.propertyType()) {
					case ATTRIBUTE -> schemaForClassifier.getAttributeByName(c, CLASSIFIER_NAMING_CONVENTION)
						.map(AttributeSchemaContract::getName)
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
	 * of the constraint for its parameters, mainly its children.
	 */
	@Nonnull
	private DataLocator resolveInnerDataLocator(@Nonnull ConstraintResolveContext resolveContext,
												@Nonnull String originalKey,
	                                            @Nonnull ConstraintDescriptor constraintDescriptor,
	                                            @Nonnull Optional<String> classifier) {
		final DataLocator parentDataLocator = resolveContext.dataLocator();
		return switch (constraintDescriptor.propertyType()) {
			case GENERIC, ATTRIBUTE, ASSOCIATED_DATA, PRICE -> parentDataLocator;
			case ENTITY -> {
				if (!(parentDataLocator instanceof DataLocatorWithReference)) {
					yield new EntityDataLocator(parentDataLocator.entityType());
				} else {
					final EntitySchemaContract referencedEntitySchema = findReferencedEntitySchema(parentDataLocator);
					yield new EntityDataLocator(referencedEntitySchema.getName());
				}
			}
			case REFERENCE -> new ReferenceDataLocator(
				parentDataLocator.entityType(),
				classifier.orElseThrow(() -> new ExternalApiInternalError("Missing required classifier in `" + originalKey + "`."))
			);
			case HIERARCHY -> {
				if (constraintDescriptor.creator().hasClassifierParameter() && classifier.isEmpty()) {
					throw new ExternalApiInternalError("Missing required classifier in `" + originalKey + "`.");
				}
				yield new HierarchyDataLocator(
					parentDataLocator.entityType(),
					classifier.orElse(null)
				);
			}
			case FACET -> {
				if (constraintDescriptor.creator().hasClassifierParameter() && classifier.isEmpty()) {
					throw new ExternalApiInternalError("Missing required classifier in `" + originalKey + "`.");
				}
				yield new FacetDataLocator(
					parentDataLocator.entityType(),
					classifier.orElse(null)
				);
			}
			default -> throw new ExternalApiInternalError("Unsupported property type `" + constraintDescriptor.propertyType() + "`.");
		};
	}

	@Nonnull
	protected EntitySchemaContract findEntitySchema(@Nonnull DataLocator dataLocator) {
		return catalogSchema.getEntitySchema(dataLocator.entityType())
			.orElseThrow(() -> new ExternalApiInternalError("Entity schema `" + dataLocator.entityType() + "` is required."));
	}

	@Nonnull
	protected EntitySchemaContract findReferencedEntitySchema(@Nonnull DataLocator dataLocator) {
		return catalogSchema.getEntitySchema(dataLocator.entityType())
			.flatMap(entitySchema -> {
				final String referenceName;
				if (dataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) {
					referenceName = dataLocatorWithReference.referenceName();
				} else {
					throw new ExternalApiInternalError("Cannot find referenced entity schema for non-reference data locator.");
				}
				if (referenceName == null) {
					// we do not reference any other collection, thus the main one is used as fall back
					return Optional.of(entitySchema);
				}

				return entitySchema.getReference(referenceName)
					.filter(ReferenceSchemaContract::isReferencedEntityTypeManaged)
					.flatMap(referenceSchema -> catalogSchema.getEntitySchema(referenceSchema.getReferencedEntityType()));
			})
			.orElseThrow(() -> new ExternalApiInternalError("Could not find schema for referenced entity."));
	}

	/**
	 * Parsed client key representing actual constraint which is described by {@link ConstraintDescriptor}
	 */
	public record ParsedConstraintDescriptor(@Nonnull String originalKey,
	                                         @Nullable String classifier,
	                                         @Nonnull ConstraintDescriptor constraintDescriptor,
	                                         @Nonnull DataLocator innerDataLocator) {}
}
