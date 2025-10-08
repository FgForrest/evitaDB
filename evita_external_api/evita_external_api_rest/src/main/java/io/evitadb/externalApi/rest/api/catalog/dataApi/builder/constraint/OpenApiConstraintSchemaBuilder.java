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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.AllowedConstraintPredicate;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintBuildContext;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ContainerKey;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.WrapperObjectKey;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintProcessingUtils;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter.ConvertedEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiScalar;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiObject.newObject;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for REST API.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public abstract class OpenApiConstraintSchemaBuilder
	extends ConstraintSchemaBuilder<OpenApiConstraintSchemaBuildingContext, OpenApiSimpleType, OpenApiObject, OpenApiProperty> {

	protected OpenApiConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext sharedContext,
	                                         @Nonnull Map<ConstraintType, AtomicReference<? extends ConstraintSchemaBuilder<OpenApiConstraintSchemaBuildingContext, OpenApiSimpleType, OpenApiObject, OpenApiProperty>>> additionalBuilders,
	                                         @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints,
	                                         @Nonnull Set<Class<? extends Constraint<?>>> forbiddenConstraints) {
		super(sharedContext, additionalBuilders, allowedConstraints, forbiddenConstraints);
	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildContainer(@Nonnull ConstraintBuildContext buildContext,
	                                           @Nonnull ContainerKey containerKey,
	                                           @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final String containerName = constructContainerName(containerKey);
		final OpenApiObject.Builder containerBuilder = newObject().name(containerName);
		final OpenApiTypeReference containerPointer = typeRefTo(containerName);
		// cache new container for reuse
		this.sharedContext.cacheContainer(containerKey, containerPointer);

		final List<OpenApiProperty> children = new LinkedList<>();
		children.addAll(buildGenericChildren(buildContext, allowedChildrenPredicate));
		children.addAll(buildEntityChildren(buildContext, allowedChildrenPredicate));
		children.addAll(buildAttributeChildren(buildContext, allowedChildrenPredicate));
		children.addAll(buildAssociatedDataChildren(buildContext, allowedChildrenPredicate));
		children.addAll(buildPriceChildren(buildContext, allowedChildrenPredicate));
		final Collection<ReferenceSchemaContract> referenceSchemas = findReferenceSchemas(buildContext.dataLocator());
		children.addAll(buildReferenceChildren(buildContext, allowedChildrenPredicate, referenceSchemas));
		children.addAll(buildHierarchyChildren(buildContext, allowedChildrenPredicate, referenceSchemas));
		children.addAll(buildFacetChildren(buildContext, allowedChildrenPredicate, referenceSchemas));

		// abort container creation and use boolean value instead as this container would be empty which wouldn't be user-friendly
		if (children.isEmpty()) {
			this.sharedContext.removeCachedContainer(containerKey);
			return buildNoneConstraintValue();
		}

		children.forEach(property -> {
			Assert.isPremiseValid(
				!containerBuilder.hasProperty(property.getName()),
				() -> createSchemaBuildingError(
					"There is already defined property `" + property.getName() + "` with container name `" + containerName + "`."
				)
			);
			containerBuilder.property(property);
		});

		this.sharedContext.addNewType(containerBuilder.build());
		return containerPointer;
	}

	@Nullable
	@Override
	protected OpenApiProperty buildFieldFromConstraintDescriptor(@Nonnull ConstraintDescriptor constraintDescriptor,
	                                                             @Nonnull String constraintKey,
	                                                             @Nonnull OpenApiSimpleType constraintValue) {
		return newProperty()
			.name(constraintKey)
			.description(constructConstraintDescription(constraintDescriptor))
			.type(constraintValue)
			.build();
	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildNoneConstraintValue() {
		return DataTypesConverter.getOpenApiScalar(Boolean.class);
	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildPrimitiveConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                          @Nonnull ValueParameterDescriptor valueParameter,
	                                                          boolean canBeRequired,
	                                                          @Nullable ValueTypeSupplier valueTypeSupplier) {
		final Class<? extends Serializable> valueParameterType = valueParameter.type();
		if (isJavaTypeGeneric(valueParameterType)) {
			// value has generic type, we need to supply value type or fallback to generic GQL type
			final Class<?> suppliedValueType = valueTypeSupplier != null
				? valueTypeSupplier.apply(valueParameter)
				: Any.class;
			Assert.isPremiseValid(
				suppliedValueType != null,
				() -> createSchemaBuildingError("Expected value type supplier to supply type not null.")
			);

			return resolveOpenApiType(valueParameterType, suppliedValueType, canBeRequired && valueParameter.required());
		} else {
			if (Locale.class.equals(valueParameterType) || Locale.class.equals(valueParameterType.getComponentType())) {
				// if locale data type is explicitly defined, we expect that such locale is schema-defined locale
				return DataTypesConverter.wrapOpenApiComponentType(
					typeRefTo(LOCALE_ENUM.name()),
					valueParameterType,
					canBeRequired && valueParameter.required()
				);
			} else if (Currency.class.equals(valueParameterType) || Currency.class.equals(valueParameterType.getComponentType())) {
				// if currency data type is explicitly defined, we expect that such currency is schema-defined currency
				return DataTypesConverter.wrapOpenApiComponentType(
					typeRefTo(CURRENCY_ENUM.name()),
					valueParameterType,
					canBeRequired && valueParameter.required()
				);
			} else {
				return resolveOpenApiType(valueParameterType, canBeRequired && valueParameter.required());
			}
		}
	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildWrapperRangeConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                             @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                             @Nullable ValueTypeSupplier valueTypeSupplier) {
		final OpenApiSimpleType itemType = buildPrimitiveConstraintValue(
			buildContext,
			valueParameters.get(0),
			false,
			valueTypeSupplier
		);
		return arrayOf(itemType, 2, 2);
	}

	@Nonnull
	@Override
	protected Map<String, OpenApiSimpleType> buildChildConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                                   @Nonnull ChildParameterDescriptor childParameter) {
		final Optional<DataLocator> childDataLocator = resolveChildDataLocator(buildContext, childParameter.domain());
		if (childDataLocator.isEmpty()) {
			// we don't have data for the switch, thus we don't want to build this child parameter
			return Map.of();
		}
		final ConstraintBuildContext childBuildContext = buildContext.switchToChildContext(childDataLocator.get());
		final Map<String, OpenApiSimpleType> childTypes = createLinkedHashMap(1);

		final Class<?> childParameterType = childParameter.type();
		if (!childParameterType.isArray() && !ClassUtils.isAbstract(childParameterType)) {
			//noinspection unchecked
			ConstraintDescriptorProvider.getConstraints((Class<? extends Constraint<?>>) childParameterType)
				.forEach(childConstraintDescriptor -> {
					final String key = this.keyBuilder.build(buildContext, childConstraintDescriptor, null);

					// we need to switch child domain again manually based on the property type of the child constraint because there
					// is no intermediate wrapper container that would do it for us (while generating all possible constraint for that container)
					final Optional<DataLocator> childConstraintDataLocator = resolveChildDataLocator(
						buildContext,
						ConstraintProcessingUtils.getDomainForPropertyType(childConstraintDescriptor.propertyType())
					);
					if (childConstraintDataLocator.isEmpty()) {
						// we don't have data for the switch, thus we don't want to build this child parameter
						return;
					}
					childTypes.put(
						key,
						build(
							childBuildContext.switchToChildContext(childConstraintDataLocator.get()),
							childConstraintDescriptor
						)
					);
				});
		} else {
			final OpenApiSimpleType childType = obtainContainer(childBuildContext, childParameter);
			if (childType instanceof OpenApiScalar) {
				// child container didn't have any usable children, but we want to have at least marker constraint, thus boolean value was used instead
				childTypes.put(SINGLE_CHILD_CONSTRAINT_KEY, childType);
			} else {
				if (childParameterType.isArray() && !childParameter.uniqueChildren()) {
					childTypes.put(SINGLE_CHILD_CONSTRAINT_KEY, arrayOf(childType));
				} else {
					childTypes.put(SINGLE_CHILD_CONSTRAINT_KEY, childType);
				}
			}
		}

		return childTypes;

	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildWrapperObjectConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                              @Nonnull WrapperObjectKey wrapperObjectKey,
	                                                              @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                              @Nonnull List<ChildParameterDescriptor> childParameters,
	                                                              @Nonnull List<AdditionalChildParameterDescriptor> additionalChildParameters,
	                                                              @Nullable ValueTypeSupplier valueTypeSupplier) {
		final List<OpenApiProperty.Builder> wrapperObjectFields = new ArrayList<>(valueParameters.size() + childParameters.size() + additionalChildParameters.size());

		// build primitive values
		valueParameters.forEach(valueParameter -> {
			final OpenApiSimpleType nestedPrimitiveConstraintValue = buildPrimitiveConstraintValue(
				buildContext,
				valueParameter,
				!valueParameter.type().isArray(),
				valueTypeSupplier
			);
			wrapperObjectFields.add(newProperty()
				.name(valueParameter.name())
				.type(nestedPrimitiveConstraintValue));
		});

		// build children values
		childParameters.forEach(childParameter -> {
			final Map<String, OpenApiSimpleType> nestedChildConstraints = buildChildConstraintValue(buildContext, childParameter);
			if (nestedChildConstraints.isEmpty()) {
				// no usable data found, so we don't want to generate anything
				return;
			} else if (nestedChildConstraints.size() == 1) {
				OpenApiSimpleType nestedChildConstraintValue = nestedChildConstraints.values().iterator().next();
				if (childParameter.required() &&
					!childParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
				) {
					nestedChildConstraintValue = nonNull(nestedChildConstraintValue);
				}

				wrapperObjectFields.add(newProperty()
					.name(childParameter.name())
					.type(nestedChildConstraintValue));
			} else {
				nestedChildConstraints
					.entrySet()
					.forEach(nestedChildConstraint -> {
						final String key = nestedChildConstraint.getKey();
						Assert.isPremiseValid(
							!key.equals(SINGLE_CHILD_CONSTRAINT_KEY),
							() -> createSchemaBuildingError("Multiple nested child constraint variants but missing proper key.")
						);

						if (childParameter.required() &&
							!childParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
						) {
							nestedChildConstraint.setValue(nonNull(nestedChildConstraint.getValue()));
						}
						wrapperObjectFields.add(newProperty()
							.name(key)
							.type(nestedChildConstraint.getValue()));
					});
			}
		});

		// build additional children values
		additionalChildParameters.forEach(additionalChildParameter -> {
			buildAdditionalChildConstraintValue(buildContext, additionalChildParameter).ifPresent(nestedAdditionalChildConstraintValue -> {
				if (additionalChildParameter.required() &&
					!additionalChildParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
				) {
					nestedAdditionalChildConstraintValue = nonNull(nestedAdditionalChildConstraintValue);
				}

				wrapperObjectFields.add(newProperty()
					.name(additionalChildParameter.name())
					.type(nestedAdditionalChildConstraintValue));
			});
		});

		if (wrapperObjectFields.isEmpty()) {
			// no fields (no usable parameters), there is nothing specific to specify, but we still want to be able to use
			// the constraint without parameters
			final OpenApiSimpleType emptyWrapperObject = buildNoneConstraintValue();
			this.sharedContext.cacheWrapperObject(wrapperObjectKey, emptyWrapperObject);
			return emptyWrapperObject;
		}

		final String wrapperObjectName = constructWrapperObjectName(wrapperObjectKey);
		final OpenApiObject.Builder wrapperObjectBuilder = newObject().name(wrapperObjectName);
		wrapperObjectFields.forEach(wrapperObjectBuilder::property);
		final OpenApiTypeReference wrapperObjectPointer = typeRefTo(wrapperObjectName);
		// cache wrapper object for reuse
		this.sharedContext.cacheWrapperObject(wrapperObjectKey, wrapperObjectPointer);

		this.sharedContext.addNewType(wrapperObjectBuilder.build());
		return wrapperObjectPointer;
	}

	/**
	 * Converts Java data type to GraphQL equivalent. If enum creates and registers new enum globally.
	 */
	@Nonnull
	private OpenApiSimpleType resolveOpenApiType(@Nonnull Class<?> valueType, boolean nonNull) {
		if (isJavaTypeEnum(valueType)) {
			final ConvertedEnum convertedEnum = DataTypesConverter.getOpenApiEnum(
				valueType,
				nonNull
			);
			this.sharedContext.getCatalogCtx().registerCustomEnumIfAbsent(convertedEnum.enumType());
			return convertedEnum.resultType();
		} else {
			return DataTypesConverter.getOpenApiScalar(valueType, nonNull);
		}
	}

	/**
	 * Converts Java data type to OpenAPI equivalent. If enum creates and registers new enum globally.
	 * Item type will be replaced with {@code replacementComponentType}, the {@code valueType} is used for array
	 * recognition and so on.
	 */
	@Nonnull
	private OpenApiSimpleType resolveOpenApiType(@Nonnull Class<?> valueType,
	                                             @Nonnull Class<?> replacementComponentType,
	                                             boolean nonNull) {
		if (isJavaTypeEnum(replacementComponentType)) {
			final ConvertedEnum convertedEnum = DataTypesConverter.getOpenApiEnum(
				valueType,
				replacementComponentType,
				nonNull
			);
			this.sharedContext.getCatalogCtx().registerCustomEnumIfAbsent(convertedEnum.enumType());
			return convertedEnum.resultType();
		} else {
			return DataTypesConverter.getOpenApiScalar(valueType, replacementComponentType, nonNull);
		}
	}

	@Override
	protected <T extends ExternalApiInternalError> T createSchemaBuildingError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new OpenApiBuildingError(message);
	}
}
