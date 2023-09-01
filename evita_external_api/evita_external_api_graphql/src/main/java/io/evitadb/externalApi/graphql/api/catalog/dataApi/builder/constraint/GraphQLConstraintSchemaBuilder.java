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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
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
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter.ConvertedEnum;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static graphql.schema.GraphQLInputObjectField.newInputObjectField;
import static graphql.schema.GraphQLInputObjectType.newInputObject;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for GraphQL API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GraphQLConstraintSchemaBuilder extends ConstraintSchemaBuilder<GraphQLConstraintSchemaBuildingContext, GraphQLInputType, GraphQLInputType, GraphQLInputObjectField> {

	protected GraphQLConstraintSchemaBuilder(@Nonnull GraphQLConstraintSchemaBuildingContext sharedContext,
	                                         @Nonnull Map<ConstraintType, AtomicReference<? extends ConstraintSchemaBuilder<GraphQLConstraintSchemaBuildingContext, GraphQLInputType, GraphQLInputType, GraphQLInputObjectField>>> additionalBuilders,
	                                         @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints,
	                                         @Nonnull Set<Class<? extends Constraint<?>>> forbiddenConstraints) {
		super(sharedContext, additionalBuilders, allowedConstraints, forbiddenConstraints);
	}

	@Nonnull
	@Override
	protected GraphQLInputType buildContainer(@Nonnull ConstraintBuildContext buildContext,
                                              @Nonnull ContainerKey containerKey,
                                              @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final String containerName = constructContainerName(containerKey);
		final GraphQLInputObjectType.Builder containerBuilder = newInputObject().name(containerName);
		final GraphQLInputType containerPointer = typeRef(containerName);
		// cache new container for reuse
		sharedContext.cacheContainer(containerKey, containerPointer);

		final List<GraphQLInputObjectField> children = new LinkedList<>();
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

		children.forEach(field -> {
			Assert.isPremiseValid(
				!containerBuilder.hasField(field.getName()),
				() -> createSchemaBuildingError(
					"There is already defined field `" + field.getName() + "` in container name `" + containerName + "`."
				)
			);
			containerBuilder.field(field);
		});

		sharedContext.addNewType(containerBuilder.build());
		return containerPointer;
	}

	@Nullable
	@Override
	protected GraphQLInputObjectField buildFieldFromConstraintDescriptor(@Nonnull ConstraintDescriptor constraintDescriptor,
	                                                                     @Nonnull String constraintKey,
	                                                                     @Nonnull GraphQLInputType constraintValue) {
		return newInputObjectField()
			.name(constraintKey)
			.description(constructConstraintDescription(constraintDescriptor))
			.type(constraintValue)
			.build();
	}


	@Nonnull
	@Override
	protected GraphQLInputType buildNoneConstraintValue() {
		return resolveGraphQLType(Boolean.class, false);
	}

	@Nonnull
	@Override
	protected GraphQLInputType buildPrimitiveConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                         @Nonnull ValueParameterDescriptor valueParameter,
	                                                         boolean canBeRequired,
	                                                         @Nullable ValueTypeSupplier valueTypeSupplier) {
		final Class<? extends Serializable> valueParameterType = valueParameter.type();
		if (isJavaTypeGeneric(valueParameterType)) {
			// value has generic type, we need to supply value type
			Assert.isPremiseValid(
				valueTypeSupplier != null,
				() -> new GraphQLSchemaBuildingError(
					"Value parameter `" + valueParameter.name() + "` has generic type but no value type supplier is present."
				)
			);

			final Class<?> suppliedValueType = valueTypeSupplier.apply(valueParameter);
			Assert.isPremiseValid(
				suppliedValueType != null,
				() -> new GraphQLSchemaBuildingError("Expected value type supplier to supply type not null.")
			);
			return resolveGraphQLType(valueParameterType, suppliedValueType, canBeRequired && valueParameter.required());
		} else {
			if (Locale.class.equals(valueParameterType) || Locale.class.equals(valueParameterType.getComponentType())) {
				// if locale data type is explicitly defined, we expect that such locale is schema-defined locale
				return DataTypesConverter.wrapGraphQLComponentType(
					typeRef(LOCALE_ENUM.name()),
					valueParameterType,
					canBeRequired && valueParameter.required()
				);
			} else if (Currency.class.equals(valueParameterType) || Currency.class.equals(valueParameterType.getComponentType())) {
				// if currency data type is explicitly defined, we expect that such currency is schema-defined currency
				return DataTypesConverter.wrapGraphQLComponentType(
					typeRef(CURRENCY_ENUM.name()),
					valueParameterType,
					canBeRequired && valueParameter.required()
				);
			} else {
				return resolveGraphQLType(valueParameterType, canBeRequired && valueParameter.required());
			}
		}
	}

	@Nonnull
	@Override
	protected GraphQLInputType buildWrapperRangeConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                       @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                       @Nullable ValueTypeSupplier valueTypeSupplier) {
		final boolean itemsAreRequired = valueParameters.get(0).required() && valueParameters.get(1).required();
		final GraphQLInputType itemType = buildPrimitiveConstraintValue(
			buildContext,
			valueParameters.get(0),
			itemsAreRequired,
			valueTypeSupplier
		);
		return list(itemType);
	}

	@Nonnull
	@Override
	protected GraphQLInputType buildChildConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                     @Nonnull ChildParameterDescriptor childParameter) {
		final DataLocator childDataLocator = resolveChildDataLocator(buildContext, childParameter.domain());
		final ConstraintBuildContext childBuildContext = buildContext.switchToChildContext(childDataLocator);
		final GraphQLInputType childType;

		final Class<?> childParameterType = childParameter.type();
		if (!childParameterType.isArray() && !ClassUtils.isAbstract(childParameterType)) {
			//noinspection unchecked
			final ConstraintDescriptor childConstraintDescriptor = ConstraintDescriptorProvider.getConstraints(
				(Class<? extends Constraint<?>>) childParameterType
			).iterator().next(); // todo lho https://github.com/FgForrest/evitaDB/issues/158

			// we need switch child domain again manually based on property type of the child constraint because there
			// is no intermediate wrapper container that would do it for us (while generating all possible constraint for that container)
			final DataLocator childConstraintDataLocator = resolveChildDataLocator(
				buildContext,
				ConstraintProcessingUtils.getDomainForPropertyType(childConstraintDescriptor.propertyType())
			);
			childType = build(
				childBuildContext.switchToChildContext(childConstraintDataLocator),
				childConstraintDescriptor
			);
		} else {
			childType = obtainContainer(childBuildContext, childParameter);
		}

		if (childType.equals(GraphQLScalars.BOOLEAN)) {
			// child container didn't have any usable children, but we want to have at least marker constraint, thus boolean value was used instead
			return childType;
		} else {
			if (childParameter.type().isArray() && !childParameter.uniqueChildren()) {
				return list(nonNull(childType));
			} else {
				return childType;
			}
		}
	}

	@Nonnull
	@Override
	protected GraphQLInputType buildWrapperObjectConstraintValue(@Nonnull ConstraintBuildContext buildContext,
																 @Nonnull WrapperObjectKey wrapperObjectKey,
	                                                             @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                             @Nullable List<ChildParameterDescriptor> childParameters,
	                                                             @Nonnull List<AdditionalChildParameterDescriptor> additionalChildParameters,
	                                                             @Nullable ValueTypeSupplier valueTypeSupplier) {
		final String wrapperObjectName = constructWrapperObjectName(wrapperObjectKey);
		final GraphQLInputObjectType.Builder wrapperObjectBuilder = newInputObject().name(wrapperObjectName);
		final GraphQLInputType wrapperObjectPointer = typeRef(wrapperObjectName);
		// cache wrapper object for reuse
		sharedContext.cacheWrapperObject(wrapperObjectKey, wrapperObjectPointer);

		// build primitive values
		for (ValueParameterDescriptor valueParameter : valueParameters) {
			final GraphQLInputType nestedPrimitiveConstraintValue = buildPrimitiveConstraintValue(
				buildContext,
				valueParameter,
				!valueParameter.type().isArray(), // we want treat missing arrays as empty arrays for more client convenience
				valueTypeSupplier
			);
			wrapperObjectBuilder.field(f -> f
				.name(valueParameter.name())
				.type(nestedPrimitiveConstraintValue));
		}

		// build children values
		childParameters.forEach(childParameter -> {
			GraphQLInputType nestedChildConstraintValue = buildChildConstraintValue(buildContext, childParameter);
			if (childParameter.required() &&
				!childParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
			) {
				nestedChildConstraintValue = nonNull(nestedChildConstraintValue);
			}

			wrapperObjectBuilder.field(newInputObjectField()
				.name(childParameter.name())
				.type(nestedChildConstraintValue));
		});

		// build additional child value
		additionalChildParameters.forEach(additionalChildParameter -> {
			GraphQLInputType nestedAdditionalChildConstraintValue = buildAdditionalChildConstraintValue(buildContext, additionalChildParameter);
			if (additionalChildParameter.required() &&
				!additionalChildParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
			) {
				nestedAdditionalChildConstraintValue = nonNull(nestedAdditionalChildConstraintValue);
			}

			wrapperObjectBuilder.field(newInputObjectField()
				.name(additionalChildParameter.name())
				.type(nestedAdditionalChildConstraintValue));
		});

		sharedContext.addNewType(wrapperObjectBuilder.build());
		return wrapperObjectPointer;
	}

	/**
	 * Converts Java data type to GraphQL equivalent. If enum creates and registers new enum globally.
	 */
	@Nonnull
	private GraphQLInputType resolveGraphQLType(@Nonnull Class<?> valueType, boolean nonNull) {
		if (isJavaTypeEnum(valueType)) {
			final ConvertedEnum<? extends GraphQLInputType> convertedEnum = DataTypesConverter.getGraphQLEnumType(
				valueType,
				nonNull
			);
			sharedContext.getCatalogCtx().registerCustomEnumIfAbsent(convertedEnum.enumType());
			return convertedEnum.resultType();
		} else {
			return DataTypesConverter.getGraphQLScalarType(valueType, nonNull);
		}
	}

	/**
	 * Converts Java data type to GraphQL equivalent. If enum creates and registers new enum globally.
	 * Item type will be replaced with {@code replacementComponentType}, the {@code valueType} is used for array
	 * recognition and so on.
	 */
	@Nonnull
	private GraphQLInputType resolveGraphQLType(@Nonnull Class<?> valueType,
	                                            @Nonnull Class<?> replacementComponentType,
	                                            boolean nonNull) {
		if (isJavaTypeEnum(replacementComponentType)) {
			final ConvertedEnum<? extends GraphQLInputType> convertedEnum = DataTypesConverter.getGraphQLEnumType(
				valueType,
				replacementComponentType,
				nonNull
			);
			sharedContext.getCatalogCtx().registerCustomEnumIfAbsent(convertedEnum.enumType());
			return convertedEnum.resultType();
		} else {
			return DataTypesConverter.getGraphQLScalarType(valueType, replacementComponentType, nonNull);
		}
	}

	@Override
	protected <T extends ExternalApiInternalError> T createSchemaBuildingError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new GraphQLSchemaBuildingError(message);
	}
}
