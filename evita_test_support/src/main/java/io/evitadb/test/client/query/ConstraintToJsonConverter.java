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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainerWithSuffix;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.test.client.query.ConstraintDescriptorResolver.ParsedConstraintDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintKeyBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintProcessingUtils;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintValueStructure;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorResolver;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.parsers.ParserConfigurationException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Converts input {@link Constraint} tree into JSON representation used in GraphQL and REST APIs. It uses same syntax
 * rules as {@link io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder} and
 * {@link io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver}, and thus it should be
 * completely compatible.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class ConstraintToJsonConverter {

	@Nonnull private final Predicate<Class<? extends Constraint<?>>> constraintPredicate;

	@Nonnull private final JsonNodeFactory jsonNodeFactory;
	@Nonnull private final ObjectJsonSerializer objectJsonSerializer;
	@Nonnull private final ConstraintKeyBuilder constraintKeyBuilder;

	@Nonnull private final ConstraintParameterValueResolver parameterValueResolver;
	@Nonnull private final DataLocatorResolver dataLocatorResolver;
	@Nonnull private final ConstraintDescriptorResolver constraintDescriptorResolver;

	protected ConstraintToJsonConverter(@Nonnull CatalogSchemaContract catalogSchema) {
		this(catalogSchema, c -> true);
	}

	protected ConstraintToJsonConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                                    @Nonnull Predicate<Class<? extends Constraint<?>>> constraintPredicate) {
		this.constraintPredicate = constraintPredicate;

		this.jsonNodeFactory = new JsonNodeFactory(true);
		this.objectJsonSerializer = new ObjectJsonSerializer();
		this.constraintKeyBuilder = new ConstraintKeyBuilder();

		this.parameterValueResolver = new ConstraintParameterValueResolver();
		this.dataLocatorResolver = new DataLocatorResolver(catalogSchema);
		this.constraintDescriptorResolver = new ConstraintDescriptorResolver(parameterValueResolver, dataLocatorResolver);
	}

	/**
	 * Converts single {@link Constraint} into JSON representation. If constraint is container, child constraints
	 * are recursively converted as well, ultimately returning a whole tree of constraints.
	 *
	 * @param rootDataLocator defines data context for the root constraint container
	 * @param constraint constraint to convert to JSON
	 * @return converted constraint with its possible children
	 */
	@Nonnull
	public Optional<JsonConstraint> convert(@Nonnull DataLocator rootDataLocator, @Nullable Constraint<?> constraint) {
		return convert(
			new ConstraintToJsonConvertContext(rootDataLocator),
			constraint
		);
	}

	/**
	 * Converts single {@link Constraint} into JSON representation. If constraint is container, child constraints
	 * are recursively converted as well, ultimately returning a whole tree of constraints.
	 *
	 * @param parentDataLocator defines virtual data context of virtual parent constraint that doesn't really exist we
	 *                          want to act like it does (e.g. when the parent constraint is implicit in some context)
	 * @param dataLocator defines data context for the current constraint container
	 * @param constraint constraint to convert to JSON
	 * @return converted constraint with its possible children
	 */
	@Nonnull
	public Optional<JsonConstraint> convert(@Nonnull DataLocator parentDataLocator,
	                                        @Nonnull DataLocator dataLocator,
	                                        @Nullable Constraint<?> constraint) {
		return convert(
			new ConstraintToJsonConvertContext(parentDataLocator, dataLocator),
			constraint
		);
	}

	/**
	 * Converts single {@link Constraint} into JSON representation. If constraint is container, child constraints
	 * are recursively converted as well, ultimately returning a whole tree of constraints.
	 *
	 * @param constraint constraint to convert to JSON
	 * @return converted constraint with its possible children
	 */
	@Nonnull
	protected Optional<JsonConstraint> convert(@Nonnull ConstraintToJsonConvertContext convertContext, @Nullable Constraint<?> constraint) {
		if (constraint == null) {
			return Optional.empty();
		}
		//noinspection unchecked
		if (!constraintPredicate.test((Class<? extends Constraint<?>>) constraint.getClass())) {
			return Optional.empty();
		}

		final ParsedConstraintDescriptor parsedConstraintDescriptor = constraintDescriptorResolver.resolve(convertContext, constraint);
		final ConstraintToJsonConvertContext innerConvertContext = convertContext.switchToChildContext(parsedConstraintDescriptor.innerDataLocator());
		return constructConstraint(innerConvertContext, parsedConstraintDescriptor, constraint);
	}


	/**
	 * Returns root query container from which other nested constraints will be built.
	 */
	@Nonnull
	protected abstract ConstraintDescriptor getDefaultRootConstraintContainerDescriptor();

	/**
	 * Determines if children constraints are unique.
	 */
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		return childParameter.uniqueChildren();
	}

	/**
	 * Tries to construct JSON from original constraint.
	 *
	 * @param parsedConstraintDescriptor descriptor representing original constraint
	 * @param constraint constraint to convert to JSON
	 * @return constructed constraint
	 */
	@Nonnull
	private Optional<JsonConstraint> constructConstraint(@Nonnull ConstraintToJsonConvertContext convertContext,
	                                           @Nonnull ParsedConstraintDescriptor parsedConstraintDescriptor,
	                                           @Nonnull Constraint<?> constraint) {
		final String constraintKey = constraintKeyBuilder.build(
			convertContext,
			parsedConstraintDescriptor.constraintDescriptor(),
			parsedConstraintDescriptor::classifier
		);

		final ConstraintCreator creator = parsedConstraintDescriptor.constraintDescriptor().creator();
		final ConstraintValueStructure valueStructure = ConstraintProcessingUtils.getValueStructureForConstraintCreator(creator);
		final JsonNode constraintValue = switch (valueStructure) {
			case NONE -> convertNoneStructure();
			case PRIMITIVE -> convertValueParameter(constraint, creator.valueParameters().get(0)).orElse(null);
			case WRAPPER_RANGE -> convertWrapperRangeStructure(constraint, creator.valueParameters());
			case CHILD -> convertChildParameter(
					convertContext,
					constraint,
					parsedConstraintDescriptor,
					creator.childParameters().get(0)
				)
				.map(JsonConstraint::value)
				.orElse(jsonNodeFactory.objectNode());
			case WRAPPER_OBJECT -> convertWrapperObjectStructure(
				convertContext,
				constraint,
				parsedConstraintDescriptor,
				creator.valueParameters(),
				creator.childParameters(),
				creator.additionalChildParameters()
			);
			default -> throw new IllegalStateException("Unknown constraint structure.");
		};

		if (constraintValue == null) {
			return Optional.empty();
		}
		return Optional.of(new JsonConstraint(constraintKey, constraintValue));
	}

	@Nonnull
	private Optional<JsonNode> convertValueParameter(@Nonnull Constraint<?> constraint, @Nonnull ValueParameterDescriptor parameterDescriptor) {
		return parameterValueResolver.resolveParameterValue(constraint, parameterDescriptor)
			.map(objectJsonSerializer::serializeObject);
	}

	@Nonnull
	private Optional<JsonConstraint> convertChildParameter(@Nonnull ConstraintToJsonConvertContext convertContext,
	                                                 @Nonnull Constraint<?> constraint,
	                                                 @Nonnull ParsedConstraintDescriptor parsedConstraintDescriptor,
	                                                 @Nonnull ChildParameterDescriptor parameterDescriptor) {
		final DataLocator childDataLocator = resolveChildDataLocator(convertContext, parsedConstraintDescriptor, parameterDescriptor.domain());
		final ConstraintToJsonConvertContext childConvertContext = convertContext.switchToChildContext(childDataLocator);
		final Class<?> childParameterType = parameterDescriptor.type();

		final Optional<?> parameterValue = parameterValueResolver.resolveParameterValue(constraint, parameterDescriptor);
		// leave out implicit children
		if (parameterValue.isPresent() &&
			constraint instanceof ConstraintContainerWithSuffix ccws &&
			parameterValue.get() instanceof Constraint<?> &&
			ccws.isChildImplicitForSuffix((Constraint<?>) parameterValue.get())) {
			return Optional.empty();
		}

		if (!childParameterType.isArray() && !ClassUtils.isAbstract(childParameterType)) {
			if (parameterValue.isEmpty()) {
				return Optional.empty();
			}
			return convert(childConvertContext, (Constraint<?>) parameterValue.get());
		}
		if (childParameterType.isArray()) {
			if (parameterValue.isEmpty()) {
				return Optional.of(new JsonConstraint(parameterDescriptor.name(), jsonNodeFactory.arrayNode()));
			}

			final Stream<? extends Constraint<?>> children = Arrays.stream((Object[]) parameterValue.get())
				.map(it -> (Constraint<?>) it);

			if (isChildrenUnique(parameterDescriptor)) {
				// if unique children are needed, single wrapping container is expected from client, listing unique constraints
				// inside. But in a constraint constructor, we expect array of concrete unique child constraints without
				// any wrapping container, thus we need to extract child constraints from the wrapping container
				final ObjectNode wrapperContainer = jsonNodeFactory.objectNode();
				children.forEach(child -> {
					convert(childDataLocator, child)
						.ifPresent(jsonConstraint -> wrapperContainer.putIfAbsent(jsonConstraint.key(), jsonConstraint.value()));
				});

				if (wrapperContainer.isEmpty()) {
					// there are no children, we don't want render empty wrapper container
					return Optional.empty();
				}
				return Optional.of(new JsonConstraint(parameterDescriptor.name(), wrapperContainer));
			} else {
				final ArrayNode jsonChildren = jsonNodeFactory.arrayNode();

				final List<JsonConstraint> convertedChildren = children
					.map(it -> convert(childDataLocator, it))
					.filter(Optional::isPresent)
					.map(Optional::get)
					.toList();
				if (convertedChildren.isEmpty()) {
					// if there are no children we don't want to render array with empty wrapper containers
					return Optional.of(new JsonConstraint(parameterDescriptor.name(), jsonNodeFactory.arrayNode()));
				}

				final long distinctChildren = convertedChildren.stream()
					.map(JsonConstraint::key)
					.distinct()
					.count();

				if (distinctChildren == convertedChildren.size() &&
					!parsedConstraintDescriptor.constraintDescriptor().constraintClass().equals(Or.class) &&
					!OrderConstraint.class.isAssignableFrom(parsedConstraintDescriptor.constraintDescriptor().constraintClass())) {
					// we can use single wrapper container as each child has unique key
					final ObjectNode wrapperContainer = jsonNodeFactory.objectNode();
					convertedChildren.forEach(child -> wrapperContainer.putIfAbsent(child.key(), child.value()));

					jsonChildren.add(wrapperContainer);
				} else {
					// we need to use separate wrapper container for each child because some children have duplicate keys
					convertedChildren.forEach(child -> {
						final ObjectNode wrapperContainer = jsonNodeFactory.objectNode();
						wrapperContainer.putIfAbsent(child.key(), child.value());
						jsonChildren.add(wrapperContainer);
					});
				}

				return Optional.of(new JsonConstraint(parameterDescriptor.name(), jsonChildren));
			}
		} else {
			if (parameterValue.isEmpty()) {
				return Optional.empty();
			}
			final Constraint<?> child = (Constraint<?>) parameterValue.get();

			final ObjectNode wrapperContainer = jsonNodeFactory.objectNode();
			convert(childDataLocator, child)
				.ifPresent(jsonConstraint -> wrapperContainer.putIfAbsent(jsonConstraint.key(), jsonConstraint.value()));
			return Optional.of(new JsonConstraint(parameterDescriptor.name(), wrapperContainer));
		}
	}

	@Nonnull
	private Optional<JsonConstraint> convertAdditionalChildParameter(@Nonnull ConstraintToJsonConvertContext convertContext,
	                                                                 @Nonnull Constraint<?> constraint,
	                                                                 @Nonnull ParsedConstraintDescriptor parsedConstraintDescriptor,
	                                                                 @Nonnull AdditionalChildParameterDescriptor parameterDescriptor) {
		final DataLocator childDataLocator = resolveChildDataLocator(convertContext, parsedConstraintDescriptor, parameterDescriptor.domain());
		final ConstraintToJsonConvertContext childConvertContext = convertContext.switchToChildContext(childDataLocator);

		final Optional<?> parameterValue = parameterValueResolver.resolveParameterValue(constraint, parameterDescriptor);
		// leave out implicit additional children
		if (parameterValue.isPresent() &&
			constraint instanceof ConstraintContainerWithSuffix ccws &&
			parameterValue.get() instanceof Constraint<?> &&
			ccws.isAdditionalChildImplicitForSuffix((Constraint<?>) parameterValue.get())) {
			return Optional.empty();
		}

		return parameterValue.flatMap(it -> convert(childConvertContext, (Constraint<?>) it));
	}

	@Nonnull
	private JsonNode convertNoneStructure() {
		return jsonNodeFactory.booleanNode(true);
	}

	@Nonnull
	private JsonNode convertWrapperRangeStructure(@Nonnull Constraint<?> constraint, @Nonnull List<ValueParameterDescriptor> parameterDescriptors) {
		Assert.isPremiseValid(
			parameterDescriptors.size() == 2,
			"Constraint `" + constraint.getClass().getSimpleName() + "` doesn't have exactly 2 value parameters."
		);
		final ValueParameterDescriptor fromParameter = parameterDescriptors.get(0);
		final ValueParameterDescriptor toParameter = parameterDescriptors.get(1);
		Assert.isPremiseValid(
			fromParameter.name().equals(ConstraintProcessingUtils.WRAPPER_RANGE_FROM_VALUE_PARAMETER) &&
				toParameter.name().equals(ConstraintProcessingUtils.WRAPPER_RANGE_TO_VALUE_PARAMETER) &&
				fromParameter.type().equals(toParameter.type()),
			"Constraint `" + constraint.getClass().getSimpleName() + "` doesn't have matching value parameters for wrapper range."
		);

		final ArrayNode wrapperRange = jsonNodeFactory.arrayNode();
		wrapperRange.add(convertValueParameter(constraint, fromParameter).orElse(null));
		wrapperRange.add(convertValueParameter(constraint, toParameter).orElse(null));
		return wrapperRange;
	}

	@Nonnull
	private JsonNode convertWrapperObjectStructure(@Nonnull ConstraintToJsonConvertContext convertContext,
	                                               @Nonnull Constraint<?> constraint,
	                                               @Nonnull ParsedConstraintDescriptor parsedConstraintDescriptor,
	                                               @Nonnull List<ValueParameterDescriptor> valueParameterDescriptors,
	                                               @Nonnull List<ChildParameterDescriptor> childParameterDescriptors,
	                                               @Nonnull List<AdditionalChildParameterDescriptor> additionalChildParameterDescriptors) {
		final ObjectNode wrapperObject = jsonNodeFactory.objectNode();

		valueParameterDescriptors.forEach(valueParameterDescriptor ->
			convertValueParameter(constraint, valueParameterDescriptor)
				.ifPresent(it -> wrapperObject.putIfAbsent(valueParameterDescriptor.name(), it)));

		childParameterDescriptors.forEach(childParameterDescriptor ->
			convertChildParameter(convertContext, constraint, parsedConstraintDescriptor, childParameterDescriptor)
				.ifPresent(child -> wrapperObject.putIfAbsent(child.key(), child.value())));

		additionalChildParameterDescriptors.forEach(additionalChildParameterDescriptor -> {
			convertAdditionalChildParameter(
				convertContext,
				constraint,
				parsedConstraintDescriptor,
				additionalChildParameterDescriptor
			)
				.ifPresent(jsonConstraint -> wrapperObject.putIfAbsent(
					additionalChildParameterDescriptor.name(),
					jsonConstraint.value()
				));
		});

		return wrapperObject;
	}

	/**
	 * Tries to resolve or switch domain of current constraint to desired domain for child constraints.
	 *
	 * @param convertContext current context with current domain (data locator)
	 * @param parsedConstraintDescriptor current constraint descriptor
	 * @param desiredChildDomain desired domain for child constraints
	 */
	@Nonnull
	private DataLocator resolveChildDataLocator(@Nonnull ConstraintToJsonConvertContext convertContext,
	                                            @Nonnull ParsedConstraintDescriptor parsedConstraintDescriptor,
	                                            @Nonnull ConstraintDomain desiredChildDomain) {
		final ConstraintDescriptor constraintDescriptor = parsedConstraintDescriptor.constraintDescriptor();
		if (constraintDescriptor.constraintClass().equals(getDefaultRootConstraintContainerDescriptor().constraintClass())) {
			return convertContext.dataLocator();
		}
		return dataLocatorResolver.resolveChildParameterDataLocator(convertContext.dataLocator(), desiredChildDomain);
	}
}
