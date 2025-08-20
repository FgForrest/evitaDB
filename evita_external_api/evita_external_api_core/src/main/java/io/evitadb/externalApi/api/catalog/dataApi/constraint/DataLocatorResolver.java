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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Helper for resolving {@link DataLocator}s usually from other locators.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class DataLocatorResolver {

	private static final Set<ConstraintDomain> CONSTRAINT_DOMAINS_WITH_REFERENCE = Set.of(ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.HIERARCHY, ConstraintDomain.HIERARCHY_TARGET, ConstraintDomain.FACET);

	@Nonnull private final CatalogSchemaContract catalogSchema;

	/**
	 * Tries to create new {@link DataLocator} child constraint parameter inside parent container based on parent data locator and desired
	 * domain of child constraint parameter.
	 * Important to note that we resolve child data locator only for child parameter while looking at parent container which
	 * already has correct data locator with references and so on resolved. Thus, we check if we can switch context
	 * of child constraints to desired domain with the data that we have from parent container.
	 *
	 * @param parentDataLocator             parent data locator to the new child data locator of child constraints, data locator of current constraint container
	 * @param desiredChildDomainOfParameter domain of child constraint parameter to use for child constraints of current container
	 * @return resolved child data locator or empty if data for the child locator is missing, but it is still logically correct
	 */
	@Nonnull
	public Optional<DataLocator> resolveChildParameterDataLocator(@Nonnull DataLocator parentDataLocator,
	                                                              @Nonnull ConstraintDomain desiredChildDomainOfParameter) {
		if (desiredChildDomainOfParameter == ConstraintDomain.DEFAULT || parentDataLocator.targetDomain().equals(desiredChildDomainOfParameter)) {
			return Optional.of(parentDataLocator);
		} else if (CONSTRAINT_DOMAINS_WITH_REFERENCE.contains(desiredChildDomainOfParameter)) {
			Assert.isPremiseValid(
				parentDataLocator instanceof DataLocatorWithReference,
				() -> new ExternalApiInternalError("Cannot switch to `" + desiredChildDomainOfParameter + "` domain because parent domain doesn't contain any reference.")
			);

			final EntityTypePointer childEntityTypePointer = parentDataLocator.entityTypePointer();
			final String childReferenceName = ((DataLocatorWithReference) parentDataLocator).referenceName();
			return switch (desiredChildDomainOfParameter) {
				case REFERENCE -> {
					Assert.isPremiseValid(
						childReferenceName != null,
						() -> new ExternalApiInternalError("Child domain `" + ConstraintDomain.REFERENCE + "` requires explicit reference name.")
					);
					yield Optional.of(new ReferenceDataLocator(childEntityTypePointer, childReferenceName));
				}
				case INLINE_REFERENCE -> {
					Assert.isPremiseValid(
						childReferenceName != null,
						() -> new ExternalApiInternalError("Child domain `" + ConstraintDomain.INLINE_REFERENCE + "` requires explicit reference name.")
					);
					yield Optional.of(new InlineReferenceDataLocator(childEntityTypePointer, childReferenceName));
				}
				case HIERARCHY -> {
					Assert.isPremiseValid(
						parentDataLocator instanceof HierarchyDataLocator,
						() -> new ExternalApiInternalError("Cannot switch to `" + desiredChildDomainOfParameter + "` domain because parent domain doesn't locate any hierarchy.")
					);
					yield Optional.of(new HierarchyDataLocator(childEntityTypePointer, childReferenceName));
				}
				case HIERARCHY_TARGET -> {
					Assert.isPremiseValid(
						parentDataLocator instanceof HierarchyDataLocator,
						() -> new ExternalApiInternalError("Cannot switch to `" + desiredChildDomainOfParameter + "` domain because parent domain doesn't locate any hierarchy.")
					);
					if (childReferenceName == null) {
						yield Optional.of(new EntityDataLocator(childEntityTypePointer));
					} else {
						yield Optional.of(new ReferenceDataLocator(childEntityTypePointer, childReferenceName));
					}
				}
				case FACET -> {
					Assert.isPremiseValid(
						parentDataLocator instanceof FacetDataLocator,
						() -> new ExternalApiInternalError("Cannot switch to `" + desiredChildDomainOfParameter + "` domain because parent domain doesn't locate any facets.")
					);
					yield Optional.of(new FacetDataLocator(childEntityTypePointer, childReferenceName));
				}
				default ->
					throw new ExternalApiInternalError("Unsupported domain `" + desiredChildDomainOfParameter + "`.");
			};
		} else {
			if (parentDataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) {
				if (dataLocatorWithReference.referenceName() == null) {
					return switch (desiredChildDomainOfParameter) {
						case GENERIC ->
							Optional.of(new GenericDataLocator(dataLocatorWithReference.entityTypePointer()));
						case ENTITY -> Optional.of(new EntityDataLocator(dataLocatorWithReference.entityTypePointer()));
						default ->
							throw new ExternalApiInternalError("Unsupported domain `" + desiredChildDomainOfParameter + "`.");
					};
				} else {
					//noinspection DataFlowIssue
					final ReferenceSchemaContract referenceSchema = this.catalogSchema.getEntitySchemaOrThrowException(dataLocatorWithReference.entityType())
						.getReferenceOrThrowException(dataLocatorWithReference.referenceName());

					final String referencedEntityType = referenceSchema.getReferencedEntityType();
					return switch (desiredChildDomainOfParameter) {
						case GENERIC -> Optional.of(new GenericDataLocator(
							referenceSchema.isReferencedEntityTypeManaged()
								? new ManagedEntityTypePointer(referencedEntityType)
								: new ExternalEntityTypePointer(referencedEntityType)
						));
						case ENTITY -> Optional.of(new EntityDataLocator(
							referenceSchema.isReferencedEntityTypeManaged()
								? new ManagedEntityTypePointer(referencedEntityType)
								: new ExternalEntityTypePointer(referencedEntityType)
						));
						case GROUP_ENTITY -> {
							final String referencedGroupType = referenceSchema.getReferencedGroupType();
							if (referencedGroupType == null) {
								// we can logically do this switch, but we don't have data for it
								yield Optional.empty();
							}
							yield Optional.of(new EntityDataLocator(
								referenceSchema.isReferencedGroupTypeManaged()
									? new ManagedEntityTypePointer(referencedGroupType)
									: new ExternalEntityTypePointer(referencedGroupType)
							));
						}
						case SEGMENT ->
							Optional.of(new SegmentDataLocator(dataLocatorWithReference.entityTypePointer()));
						default ->
							throw new ExternalApiInternalError("Unsupported domain `" + desiredChildDomainOfParameter + "`.");
					};
				}
			} else {
				return switch (desiredChildDomainOfParameter) {
					case GENERIC -> Optional.of(new GenericDataLocator(parentDataLocator.entityTypePointer()));
					case ENTITY -> Optional.of(new EntityDataLocator(parentDataLocator.entityTypePointer()));
					case SEGMENT -> Optional.of(new SegmentDataLocator(parentDataLocator.entityTypePointer()));
					default ->
						throw new ExternalApiInternalError("Unsupported domain `" + desiredChildDomainOfParameter + "`.");
				};
			}
		}
	}

	/**
	 * Tries to create new {@link DataLocator} for passed constraint container based on its {@link ConstraintPropertyType}
	 * and classifier. Not all combinations with parent container are currently allowed as they don't make currently sense.
	 *
	 * @param parentDataLocator    data locator of parent container in which this constraint is placed
	 * @param constraintDescriptor descriptor of current constraint
	 * @param classifier           optional classifier of current constraint
	 */
	@Nonnull
	public DataLocator resolveConstraintDataLocator(
		@Nonnull DataLocator parentDataLocator,
		@Nonnull ConstraintDescriptor constraintDescriptor,
		@Nullable String classifier
	) {
		return switch (constraintDescriptor.propertyType()) {
			case GENERIC, ATTRIBUTE, ASSOCIATED_DATA, PRICE ->
				parentDataLocator; // these property type currently doesn't have any container constraints
			case ENTITY -> {
				if (parentDataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) {
					if (dataLocatorWithReference.referenceName() == null) {
						// should point to itself, so we just pass the existing pointer
						yield new EntityDataLocator(dataLocatorWithReference.entityTypePointer());
					} else {
						final ReferenceSchemaContract referenceSchema = this.catalogSchema.getEntitySchemaOrThrowException(parentDataLocator.entityType())
							.getReferenceOrThrowException(Objects.requireNonNull(dataLocatorWithReference.referenceName()));
						if (referenceSchema.isReferencedEntityTypeManaged()) {
							yield new EntityDataLocator(new ManagedEntityTypePointer(referenceSchema.getReferencedEntityType()));
						} else {
							yield new EntityDataLocator(new ExternalEntityTypePointer(referenceSchema.getReferencedEntityType()));
						}
					}
				} else {
					yield new EntityDataLocator(parentDataLocator.entityTypePointer());
				}
			}
			case REFERENCE, HIERARCHY, FACET -> {
				if (constraintDescriptor.creator().hasClassifier()) {
					// if reference constraint has classifier, it means we need to change context to that reference
					Assert.isPremiseValid(
						!(parentDataLocator instanceof DataLocatorWithReference),
						() -> new ExternalApiInternalError("`" + constraintDescriptor.propertyType() + "` containers cannot have `" + constraintDescriptor.propertyType() + "` constraints with different classifiers.")
					);
					if (constraintDescriptor.creator().hasClassifierParameter() && classifier == null) {
						throw new ExternalApiInternalError("Missing required classifier.");
					}
					yield switch (constraintDescriptor.propertyType()) {
						case REFERENCE ->
							new ReferenceDataLocator(parentDataLocator.entityTypePointer(), Objects.requireNonNull(classifier));
						case HIERARCHY ->
							new HierarchyDataLocator(parentDataLocator.entityTypePointer(), classifier);
						case FACET ->
							new FacetDataLocator(parentDataLocator.entityTypePointer(), classifier);
						default ->
							throw new ExternalApiInternalError("Unexpected property type `" + constraintDescriptor.propertyType() + "`.");
					};
				} else {
					// if reference constraint doesn't have classifier, it means it is either in another reference container or it is some more general constraint
					if (constraintDescriptor.propertyType().equals(ConstraintPropertyType.REFERENCE)) {
						if (parentDataLocator instanceof AbstractReferenceDataLocator ||
							parentDataLocator instanceof EntityDataLocator) {
							yield parentDataLocator;
						}
						throw new ExternalApiInternalError("Reference constraints without classifier must be encapsulated in parent reference or entity containers.");
					} else if (constraintDescriptor.propertyType().equals(ConstraintPropertyType.HIERARCHY)) {
						if (parentDataLocator instanceof HierarchyDataLocator) {
							yield parentDataLocator;
						}
						if (parentDataLocator instanceof EntityDataLocator &&
							this.catalogSchema.getEntitySchemaOrThrowException(parentDataLocator.entityType()).isWithHierarchy()) {
							yield new HierarchyDataLocator(parentDataLocator.entityTypePointer());
						}
						throw new ExternalApiInternalError("Hierarchy constraints must have specified hierarchy");
					} else if (constraintDescriptor.propertyType().equals(ConstraintPropertyType.FACET)) {
						Assert.isPremiseValid(
							parentDataLocator instanceof GenericDataLocator || parentDataLocator instanceof FacetDataLocator,
							() -> new ExternalApiInternalError("Facet constraints without classifier must be encapsulated into parent generic or facet containers.")
						);
						yield parentDataLocator;
					} else {
						throw new ExternalApiInternalError("Unexpected property type `" + constraintDescriptor.propertyType() + "`.");
					}
				}
			}
		};
	}
}
