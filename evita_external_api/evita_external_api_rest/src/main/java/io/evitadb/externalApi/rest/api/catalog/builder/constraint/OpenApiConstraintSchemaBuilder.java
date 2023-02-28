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

package io.evitadb.externalApi.rest.api.catalog.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.AllowedConstraintPredicate;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ContainerKey;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.WrapperObjectKey;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.rest.api.dto.OpenApiObject;
import io.evitadb.externalApi.rest.api.dto.OpenApiProperty;
import io.evitadb.externalApi.rest.api.dto.OpenApiScalar;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.dataType.DataTypesConverter.ConvertedEnum;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.*;
import static io.evitadb.externalApi.rest.api.dto.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.dto.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.dto.OpenApiObject.newObject;
import static io.evitadb.externalApi.rest.api.dto.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference.typeRefTo;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for REST API.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public abstract class OpenApiConstraintSchemaBuilder
	extends ConstraintSchemaBuilder<OpenApiConstraintSchemaBuildingContext, OpenApiSimpleType, OpenApiObject, OpenApiProperty> {

	@Nonnull
	protected final String rootEntityType;

	protected OpenApiConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext sharedContext, @Nonnull String rootEntityType) {
		super(sharedContext);
		this.rootEntityType = rootEntityType;
	}

	protected OpenApiConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext sharedContext,
											 @Nonnull String rootEntityType,
	                                         @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints,
	                                         @Nonnull Set<Class<? extends Constraint<?>>> forbiddenConstraints) {
		super(sharedContext, allowedConstraints, forbiddenConstraints);
		this.rootEntityType = rootEntityType;
	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildContainer(@Nonnull BuildContext buildContext,
	                                           @Nonnull ContainerKey containerKey,
	                                           @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final String containerName = constructContainerName(containerKey);
		final OpenApiObject.Builder containerBuilder = newObject().name(containerName);
		final OpenApiTypeReference containerPointer = typeRefTo(containerName);
		// cache new container for reuse
		sharedContext.cacheContainer(containerKey, containerPointer);

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
			sharedContext.removeCachedContainer(containerKey);
			return buildNoneConstraintValue();
		}

		children.forEach(property -> {
			Assert.isPremiseValid(
				containerBuilder.hasProperty(property.getName()),
				() -> createSchemaBuildingError(
					"There is already defined property `" + property.getName() + "` with container name `" + containerName + "`."
				)
			);
			containerBuilder.property(property);
		});

		sharedContext.addNewType(containerBuilder.build());
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
	protected OpenApiSimpleType buildPrimitiveConstraintValue(@Nonnull BuildContext buildContext,
	                                                          @Nonnull ValueParameterDescriptor valueParameter,
	                                                          boolean canBeRequired,
	                                                          @Nullable ValueTypeSupplier valueTypeSupplier) {
		final Class<? extends Serializable> valueParameterType = valueParameter.type();
		if (isGeneric(valueParameterType)) {
			// value has generic type, we need to supply value type
			Assert.isPremiseValid(
				valueTypeSupplier != null,
				() -> createSchemaBuildingError(
					"Value parameter `" + valueParameter.name() + "` has generic type but no value type supplier is present."
				)
			);

			final Class<?> suppliedValueType = valueTypeSupplier.apply(valueParameter);
			Assert.isPremiseValid(
				suppliedValueType != null,
				() -> createSchemaBuildingError("Expected value type supplier to supply type not null.")
			);

			return resolveOpenApiType(valueParameterType, suppliedValueType, canBeRequired && valueParameter.required());
		} else {
			if (Locale.class.equals(valueParameterType) || Locale.class.equals(valueParameterType.getComponentType())) {
				// if locale data type is explicitly defined, we expect that such locale is schema-defined locale
				return DataTypesConverter.wrapOpenApiComponentType(
					typeRefTo(ENTITY_LOCALE_ENUM.name(findRequiredEntitySchema(buildContext.dataLocator()))),
					valueParameterType,
					canBeRequired && valueParameter.required()
				);
			} else if (Currency.class.equals(valueParameterType) || Currency.class.equals(valueParameterType.getComponentType())) {
				// if currency data type is explicitly defined, we expect that such currency is schema-defined currency
				return DataTypesConverter.wrapOpenApiComponentType(
					typeRefTo(ENTITY_CURRENCY_ENUM.name(findRequiredEntitySchema(buildContext.dataLocator()))),
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
	protected OpenApiSimpleType buildWrapperRangeConstraintValue(@Nonnull BuildContext buildContext,
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
	protected OpenApiSimpleType buildChildConstraintValue(@Nonnull BuildContext buildContext,
	                                                      @Nonnull ChildParameterDescriptor childParameter) {
		final OpenApiSimpleType childContainer = obtainContainer(buildContext, childParameter);

		if (childContainer instanceof OpenApiScalar) {
			// child container didn't have any usable children, but we want to have at least marker constraint, thus boolean value was used instead
			return childContainer;
		} else {
			if (childParameter.type().isArray() && !isChildrenUnique(childParameter)) {
				return arrayOf(childContainer);
			} else {
				return childContainer;
			}
		}
	}

	@Nonnull
	@Override
	protected OpenApiSimpleType buildWrapperObjectConstraintValue(@Nonnull BuildContext buildContext,
	                                                              @Nonnull WrapperObjectKey wrapperObjectKey,
	                                                              @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                              @Nullable ChildParameterDescriptor childParameter,
	                                                              @Nullable ValueTypeSupplier valueTypeSupplier) {
		final String wrapperObjectName = constructWrapperObjectName(wrapperObjectKey);
		final OpenApiObject.Builder wrapperObjectBuilder = newObject().name(wrapperObjectName);
		final OpenApiTypeReference wrapperObjectPointer = typeRefTo(wrapperObjectName);
		// cache wrapper object for reuse
		sharedContext.cacheWrapperObject(wrapperObjectKey, wrapperObjectPointer);

		// build primitive values
		for (ValueParameterDescriptor valueParameter : valueParameters) {
			final OpenApiSimpleType nestedPrimitiveConstraintValue = buildPrimitiveConstraintValue(
				buildContext,
				valueParameter,
				!valueParameter.type().isArray(),
				valueTypeSupplier
			);
			wrapperObjectBuilder.property(p -> p
				.name(valueParameter.name())
				.type(nestedPrimitiveConstraintValue));
		}

		// build child value
		if (childParameter != null) {
			OpenApiSimpleType nestedChildConstraintValue = buildChildConstraintValue(buildContext, childParameter);
			if (childParameter.required() &&
				!childParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
			) {
				nestedChildConstraintValue = nonNull(nestedChildConstraintValue);
			}

			wrapperObjectBuilder.property(newProperty()
				.name(childParameter.name())
				.type(nestedChildConstraintValue));
		}

		sharedContext.addNewType(wrapperObjectBuilder.build());
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
			sharedContext.getCatalogCtx().registerCustomEnumIfAbsent(convertedEnum.enumType());
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
			sharedContext.getCatalogCtx().registerCustomEnumIfAbsent(convertedEnum.enumType());
			return convertedEnum.resultType();
		} else {
			return DataTypesConverter.getOpenApiScalar(valueType, replacementComponentType, nonNull);
		}
	}

	@Override
	protected <T extends ExternalApiInternalError> T createSchemaBuildingError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new OpenApiSchemaBuildingError(message);
	}
}
