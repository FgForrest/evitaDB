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
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.ArraySchema;
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

/**
 * Implementation of {@link ConstraintSchemaBuilder} for REST API.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public abstract class OpenApiConstraintSchemaBuilder extends ConstraintSchemaBuilder<OpenApiConstraintSchemaBuildingContext, Schema<Object>, Schema<Object>> {

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
	protected Schema<Object> buildContainer(@Nonnull BuildContext buildContext,
	                                        @Nonnull ContainerKey containerKey,
	                                        @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final String containerName = constructContainerName(containerKey);
		final Schema<Object> containerBuilder = createObjectSchema();
		containerBuilder.setName(containerName);
		// cache container for reuse
		sharedContext.cacheContainer(containerKey, containerBuilder);

		final List<Schema<Object>> children = new LinkedList<>();
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
				containerBuilder.getProperties() == null || containerBuilder.getProperties() != null && !containerBuilder.getProperties().containsKey(field.getName()),
				() -> createSchemaBuildingError(
					"There is already defined field `" + field.getName() + "` with container name `" + containerName + "`."
				)
			);
			containerBuilder.addProperty(field.getName(), field);
		});

		sharedContext.addNewType(containerBuilder);
		return containerBuilder;
	}

	@Nullable
	@Override
	protected Schema<Object> buildFieldFromConstraintDescriptor(@Nonnull ConstraintDescriptor constraintDescriptor,
	                                                            @Nonnull String constraintKey,
	                                                            @Nonnull Schema<Object> constraintValue) {
		constraintValue.name(constraintKey);
		if(constraintValue.get$ref() == null) {
			constraintValue.setDescription(constructConstraintDescription(constraintDescriptor));
		}

		return constraintValue;
	}

	@Nonnull
	@Override
	protected Schema<Object> buildNoneConstraintValue() {
		return createBooleanSchema();
	}

	@Nonnull
	@Override
	protected Schema<Object> buildPrimitiveConstraintValue(@Nonnull BuildContext buildContext,
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

			final Schema<Object> schema;
			if (valueParameterType.isArray()) {
				if(suppliedValueType.isArray()) {
					schema = createSchemaByJavaType(suppliedValueType);
				} else {
					schema = createArraySchemaOf(createSchemaByJavaType(suppliedValueType));
				}
			} else {
				schema = createSchemaByJavaType(suppliedValueType);
			}

			if (valueParameter.required() && schema instanceof ArraySchema arraySchema) {
				arraySchema.setMinItems(1);
			}
			return schema;

		} else {
			if (Locale.class.equals(valueParameterType) || Locale.class.equals(valueParameterType.getComponentType())) {
				// if locale data type is explicitly defined, we expect that such locale is schema-defined locale
				final String localeEnumSchema = ENTITY_LOCALE_ENUM.name(findRequiredEntitySchema(buildContext.dataLocator()));

				if(valueParameterType.isArray()) {
					return createArraySchemaOf(createReferenceSchema(localeEnumSchema));
				}
				return createReferenceSchema(localeEnumSchema);
			} else if (Currency.class.equals(valueParameterType) || Currency.class.equals(valueParameterType.getComponentType())) {
				// if currency data type is explicitly defined, we expect that such currency is schema-defined currency
				final String currencyEnumSchema =  ENTITY_CURRENCY_ENUM.name(findRequiredEntitySchema(buildContext.dataLocator()));
				if(valueParameterType.isArray()) {
					return createArraySchemaOf(createReferenceSchema(currencyEnumSchema));
				}
				return createReferenceSchema(currencyEnumSchema);
			} else {
				final var schema = createSchemaByJavaType(valueParameterType);
				if (valueParameter.required() && schema instanceof ArraySchema arraySchema) {
					arraySchema.setMinItems(1);
				}
				return schema;
			}
		}
	}

	@Nonnull
	@Override
	protected Schema<Object> buildWrapperRangeConstraintValue(@Nonnull BuildContext buildContext,
	                                                          @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                          @Nullable ValueTypeSupplier valueTypeSupplier) {
		final Schema<Object> itemType = buildPrimitiveConstraintValue(
			buildContext,
			valueParameters.get(0),
			false,
			valueTypeSupplier
		);
		return createRangeSchemaOf(itemType);
	}

	@Nonnull
	@Override
	protected Schema<Object> buildChildConstraintValue(@Nonnull BuildContext buildContext,
	                                                   @Nonnull ChildParameterDescriptor childParameter) {
		final Schema<Object> childContainer = obtainContainer(buildContext, childParameter);

		if (childContainer.getType().equals(TYPE_BOOLEAN)) {
			// child container didn't have any usable children, but we want to have at least marker constraint, thus boolean value was used instead
			return childContainer;
		} else {
			if (childParameter.type().isArray() && !isChildrenUnique(childParameter)) {
				final var arraySchema = createArraySchemaOf(createReferenceSchema(childContainer.getName()));
				arraySchema.minItems(1);
				return arraySchema;
			} else {
				return createReferenceSchema(childContainer.getName());
			}
		}
	}

	@Nonnull
	@Override
	protected Schema<Object> buildWrapperObjectConstraintValue(@Nonnull BuildContext buildContext,
	                                                           @Nonnull WrapperObjectKey wrapperObjectKey,
	                                                           @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                           @Nullable ChildParameterDescriptor childParameter,
	                                                           @Nullable ValueTypeSupplier valueTypeSupplier) {
		final String wrapperObjectName = constructWrapperObjectName(wrapperObjectKey);
		final Schema<Object> wrapperObjectSchema = createObjectSchema();
		wrapperObjectSchema.name(wrapperObjectName);
		final Schema<Object> wrapperObjectPointer = createReferenceSchema(wrapperObjectName);

		// cache wrapper object for reuse
		sharedContext.cacheWrapperObject(wrapperObjectKey, wrapperObjectPointer);

		// build primitive values
		for (ValueParameterDescriptor valueParameter : valueParameters) {
			final Schema<Object> nestedPrimitiveConstraintValue = buildPrimitiveConstraintValue(
				buildContext,
				valueParameter,
				false,
				valueTypeSupplier
			);
			wrapperObjectSchema.addProperty(valueParameter.name(), nestedPrimitiveConstraintValue);
			if(!valueParameter.type().isArray()) { // we want treat missing arrays as empty arrays for more client convenience
				wrapperObjectSchema.addRequiredItem(valueParameter.name());
			}
		}

		// build child value
		if (childParameter != null) {
			Schema<Object> nestedChildConstraintValue = buildChildConstraintValue(buildContext, childParameter);

			wrapperObjectSchema.addProperty(childParameter.name(), nestedChildConstraintValue);
			if (childParameter.required() &&
				!childParameter.type().isArray() // we want treat missing arrays as empty arrays for more client convenience
			) {
				wrapperObjectSchema.addRequiredItem(childParameter.name());
			}
		}

		sharedContext.addNewType(wrapperObjectSchema);
		return wrapperObjectPointer;
	}

	@Override
	protected <T extends ExternalApiInternalError> T createSchemaBuildingError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new OpenApiSchemaBuildingError(message);
	}
}
