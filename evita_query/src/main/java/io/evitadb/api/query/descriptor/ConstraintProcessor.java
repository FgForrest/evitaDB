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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.SilentImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Transforms {@link Constraint} classes of concrete constraints into {@link ConstraintDescriptor}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ConstraintProcessor {

	/**
	 * Takes list of constraint classes and gathers data about each constraint and creates descriptor for each
	 * constraint.
	 */
	@Nonnull
	Set<ConstraintDescriptor> process(@Nonnull Set<Class<? extends Constraint<?>>> constraintClasses) {
		final Set<ConstraintDescriptor> constraintDescriptors = new TreeSet<>();

		constraintClasses.forEach(constraintClass -> {
			final ConstraintDefinition constraintDefinition = findConstraintDefAnnotation(constraintClass);

			final ConstraintType type = resolveConstraintType(constraintClass);
			final ConstraintPropertyType propertyType = resolveConstraintPropertyType(constraintClass);

			final SupportedValues supportedValues = resolveSupportedValues(constraintDefinition);
			final List<ConstraintCreator> creators = resolveCreators(constraintClass, type);

			creators.forEach((creator) -> {
				final ConstraintDescriptor descriptor = new ConstraintDescriptor(
					constraintClass,
					type,
					propertyType,
					creator.suffix().isEmpty()
						? constraintDefinition.name()
						: constraintDefinition.name() + StringUtils.capitalize(creator.suffix().get()),
					constraintDefinition.shortDescription(),
					Set.of(constraintDefinition.supportedIn()),
					supportedValues,
					creator
				);
				final boolean descriptorAdded = constraintDescriptors.add(descriptor);
				Assert.isPremiseValid(descriptorAdded, "Constraint `" + descriptor + "` is duplicate.");
			});
		});

		return Collections.unmodifiableSet(constraintDescriptors);
	}

	/**
	 * Tries to find annotation defining passed constraint.
	 */
	@Nonnull
	private ConstraintDefinition findConstraintDefAnnotation(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		final ConstraintDefinition constraintDefinition = constraintClass.getAnnotation(ConstraintDefinition.class);
		if (constraintDefinition == null) {
			throw new EvitaInternalError(
				"Constraint `" + constraintClass.getName() + "` has been registered but there is no definition specified."
			);
		}
		return constraintDefinition;
	}

	/**
	 * Resolves concrete constraint type enum from constraint class interfaces.
	 */
	@Nonnull
	private ConstraintType resolveConstraintType(@Nonnull Class<?> constraintClass) {
		return Arrays.stream(ConstraintType.values())
			.filter(t -> t.getRepresentingInterface().isAssignableFrom(constraintClass))
			.findFirst()
			.orElseThrow(() ->
				new EvitaInternalError("Constraint `" + constraintClass.getName() + "` has to have defined supported type.")
			);
	}

	/**
	 * Resolves concrete constraint property type enum from constraint class interfaces.
	 */
	@Nonnull
	private ConstraintPropertyType resolveConstraintPropertyType(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		return Arrays.stream(ConstraintPropertyType.values())
			.filter(t -> t.getRepresentingInterface().isAssignableFrom(constraintClass))
			.findFirst()
			.orElseThrow(() ->
				new EvitaInternalError("Constraint `" + constraintClass.getName() + "` has to have defined supported property type.")
			);
	}

	/**
	 * Resolves supported values defining annotation to descriptor record.
	 */
	@Nullable
	private SupportedValues resolveSupportedValues(@Nonnull ConstraintDefinition constraintDefinition) {
		final SupportedValues supportedValues;

		final ConstraintSupportedValues constraintSupportedValues = constraintDefinition.supportedValues();
		if (!constraintSupportedValues.allTypesSupported() && constraintSupportedValues.supportedTypes().length == 0) {
			supportedValues = null;
		} else {
			supportedValues = new SupportedValues(
				constraintSupportedValues.supportedTypes().length > 0 ?
					Set.of(constraintSupportedValues.supportedTypes()) :
					EvitaDataTypes.getSupportedDataTypes(),
				constraintDefinition.supportedValues().arraysSupported()
			);
		}
		return supportedValues;
	}

	/**
	 * Gathers creator constructors, its parameters and other data from constraint class and creates creator descriptor from them
	 * and associates them with full names.
	 */
	@Nonnull
	private List<ConstraintCreator> resolveCreators(@Nonnull Class<? extends Constraint<?>> constraintClass,
	                                                @Nonnull ConstraintType constraintType) {
		return findCreators(constraintClass)
			.stream()
			.map(creatorTemplate -> {
				final Creator creatorDefinition = findCreatorAnnotation(creatorTemplate);

				final String suffix = creatorDefinition.suffix().isBlank()
					? null
					: creatorDefinition.suffix();

				final List<ParameterDescriptor> parameterDescriptors = resolveCreatorParameters(
					creatorTemplate,
					constraintType
				);

				final ImplicitClassifier implicitClassifier;
				if (creatorDefinition.silentImplicitClassifier() && !creatorDefinition.implicitClassifier().isEmpty()) {
					throw new EvitaInternalError(
						"Constraint `" + constraintClass.getName() + "` has both implicit classifiers specified. Cannot decide which one to use. Please define only one of them."
					);
				} else if (creatorDefinition.silentImplicitClassifier()) {
					implicitClassifier = new SilentImplicitClassifier();
				} else if (!creatorDefinition.implicitClassifier().isEmpty()) {
					implicitClassifier = new FixedImplicitClassifier(creatorDefinition.implicitClassifier());
				} else {
					implicitClassifier = null;
				}

				return new ConstraintCreator(
					creatorTemplate,
					suffix,
					parameterDescriptors,
					implicitClassifier
				);
			})
			.toList();
	}

	/**
	 * Tries to find annotation creator constructor.
	 */
	@Nonnull
	private Set<Executable> findCreators(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		final Stream<Executable> constructors = Arrays.stream(constraintClass.getDeclaredConstructors())
			.filter(constructor -> constructor.getAnnotation(Creator.class) != null)
			.map(it -> it);

		final Stream<Executable> factoryMethods = Arrays.stream(constraintClass.getDeclaredMethods())
			.filter(method -> method.getAnnotation(Creator.class) != null)
			.peek(method -> Assert.isPremiseValid(
				Modifier.isStatic(method.getModifiers()),
				() -> "Method used as creator must be static."
			))
			.map(it -> it);

		final Set<Executable> creators = Stream.concat(constructors, factoryMethods).collect(Collectors.toUnmodifiableSet());
		Assert.isPremiseValid(
			!creators.isEmpty(),
			"Registered constraint `" + constraintClass.getName() + "` is missing creator."
		);

		return creators;
	}

	/**
	 * Tries to find creator definition on constructor.
	 */
	@Nonnull
	private Creator findCreatorAnnotation(@Nonnull Executable creator) {
		final Creator creatorDef = creator.getAnnotation(Creator.class);
		if (creatorDef == null) {
			throw new EvitaInternalError(
				"Constraint `" + creator.getDeclaringClass().getName() + "` has been registered, creator constructor found but there is no creator definition specified."
			);
		}
		return creatorDef;
	}

	/**
	 * Transforms creator constructor Java parameters to its corresponding descriptors in same order.
	 */
	@Nonnull
	private List<ParameterDescriptor> resolveCreatorParameters(@Nonnull Executable creator, @Nonnull ConstraintType constraintType) {
		final List<ParameterDescriptor> parameterDescriptors = new LinkedList<>();

		final Parameter[] creatorParameters = creator.getParameters();
		for (Parameter parameter : creatorParameters) {
			final ClassifierParameterDescriptor classifierParameter = resolveClassifierParameter(
				parameter,
				creator.getDeclaringClass()
			);
			if (classifierParameter != null) {
				parameterDescriptors.add(classifierParameter);
				continue;
			}

			final Optional<ValueParameterDescriptor> valueParameter = resolveValueParameter(parameter);
			if (valueParameter.isPresent()) {
				parameterDescriptors.add(valueParameter.get());
				continue;
			}

			final Optional<ChildParameterDescriptor> childParameter = resolveChildParameter(
				parameter,
				creator.getDeclaringClass(),
				constraintType
			);
			if (childParameter.isPresent()) {
				parameterDescriptors.add(childParameter.get());
				continue;
			}

			final Optional<AdditionalChildParameterDescriptor> additionalChildParameter = resolveAdditionalChildParameter(
				parameter,
				creator.getDeclaringClass(),
				constraintType
			);
			if (additionalChildParameter.isPresent()) {
				parameterDescriptors.add(additionalChildParameter.get());
				continue;
			}

			throw new EvitaInternalError(
				"Constraint `" + creator.getDeclaringClass().getName() + "` has creator parameter without supported annotation."
			);
		}
		return parameterDescriptors;
	}

	/**
	 * Tries to resolve constructor parameter as classifier parameter.
	 */
	@Nullable
	private ClassifierParameterDescriptor resolveClassifierParameter(@Nonnull Parameter parameter,
                                                                     @Nonnull Class<?> constraintClass) {
		final Classifier classifier = parameter.getAnnotation(Classifier.class);
		if (classifier == null) {
			return null;
		}

		Assert.isPremiseValid(
			isParameterRequired(parameter),
			"Constraint `" + constraintClass.getName() + "` has classifier which is optional."
		);
		Assert.isPremiseValid(
			!parameter.getType().isArray(),
			"Constraint `" + constraintClass.getName() + "` has classifier which is array. This is not supported."
		);
		return new ClassifierParameterDescriptor(parameter.getName());
	}

	/**
	 * Tries to resolve constructor parameter as value parameter.
	 */
	@Nonnull
	private Optional<ValueParameterDescriptor> resolveValueParameter(@Nonnull Parameter parameter) {
		final Value definition = parameter.getAnnotation(Value.class);
		//noinspection unchecked
		final Class<? extends Serializable> parameterType = (Class<? extends Serializable>) parameter.getType();
		final Class<?> parameterItemType = parameterType.isArray() ? parameterType.getComponentType() : parameterType;

		if (definition != null) {
			return Optional.of(
				new ValueParameterDescriptor(
					parameter.getName(),
					parameterType,
					isParameterRequired(parameter),
					definition.requiresPlainType()
				)
			);
		} else {
			if (!Constraint.class.isAssignableFrom(parameterItemType)) {
				return Optional.of(
					new ValueParameterDescriptor(
						parameter.getName(),
						parameterType,
						isParameterRequired(parameter),
						false
					)
				);
			}

			// parameter doesn't have appropriate value type
			return Optional.empty();
		}
	}

	/**
	 * Tries to resolve constructor parameter as direct child parameter.
	 */
	@Nonnull
	private Optional<ChildParameterDescriptor> resolveChildParameter(@Nonnull Parameter parameter,
	                                                                 @Nonnull Class<?> constraintClass,
	                                                                 @Nonnull ConstraintType constraintType) {
		final Child definition = parameter.getAnnotation(Child.class);
		final Class<?> parameterType = parameter.getType();
		final Class<?> parameterItemType = parameterType.isArray() ? parameterType.getComponentType() : parameterType;

		if (definition != null) {
			Assert.isPremiseValid(
				ConstraintContainer.class.isAssignableFrom(constraintClass),
				"Constraint `" + constraintClass.getName() + "` have child but it is not a container."
			);
			Assert.isPremiseValid(
				Constraint.class.isAssignableFrom(parameterItemType),
				"Constraint `" + constraintClass.getName() + "` have child that is not a constraint."
			);
			//noinspection unchecked
			return Optional.of(
				new ChildParameterDescriptor(
					parameter.getName(),
					parameterType,
					isParameterRequired(parameter),
					definition.domain(),
					definition.uniqueChildren(),
					Arrays.stream(definition.allowed())
						.map(c -> (Class<Constraint<?>>) c)
						.collect(Collectors.toUnmodifiableSet()),
					Arrays.stream(definition.forbidden())
						.map(c -> (Class<Constraint<?>>) c)
						.collect(Collectors.toUnmodifiableSet())
				)
			);
		} else {
			// trying to guess default type of parameter as fallback
			if (Constraint.class.isAssignableFrom(parameterItemType) &&
				constraintType.equals(resolveConstraintType(parameterItemType)) &&
				ConstraintContainer.class.isAssignableFrom(constraintClass)) {
				return Optional.of(
					new ChildParameterDescriptor(
						parameter.getName(),
						parameterType,
						isParameterRequired(parameter),
						ConstraintDomain.DEFAULT,
						false,
						Set.of(),
						Set.of()
					)
				);
			}

			// parameter doesn't have appropriate child constraint type
			return Optional.empty();
		}
	}

	/**
	 * Tries to resolve constructor parameter as additional child parameter.
	 */
	@Nonnull
	private Optional<AdditionalChildParameterDescriptor> resolveAdditionalChildParameter(@Nonnull Parameter parameter,
	                                                                                     @Nonnull Class<?> constraintClass,
	                                                                                     @Nonnull ConstraintType constraintType) {
		final AdditionalChild definition = parameter.getAnnotation(AdditionalChild.class);
		final Class<?> parameterType = parameter.getType();

		if (definition != null) {
			Assert.isPremiseValid(
				ConstraintContainer.class.isAssignableFrom(constraintClass),
				"Constraint `" + constraintClass.getName() + "` have additional child but it is not a container."
			);
			Assert.isPremiseValid(
				// Because the additional child constraint is usually another separate container, it is possible that it has
				// its own array of constraints, that's why we don't support arrays of the actual container. Also, if it would
				// be just basic constraint instead of container we would have to have logic additional implicit containers in place
				// which currently doesn't make sense.
				!parameterType.isArray() && ConstraintContainer.class.isAssignableFrom(parameterType),
				"Constraint `" + constraintClass.getName() + "` have additional child that is not a constraint container or is an array of constraints."
			);
			return Optional.of(
				new AdditionalChildParameterDescriptor(
					resolveConstraintType(parameterType),
					parameter.getName(),
					parameterType,
					isParameterRequired(parameter),
					definition.domain()
				)
			);
		} else {
			// trying to guess default type of parameter as fallback
			if (ConstraintContainer.class.isAssignableFrom(parameterType) &&
				ConstraintContainer.class.isAssignableFrom(constraintClass) &&
				!constraintType.equals(resolveConstraintType(parameterType))) {
				return Optional.of(
					new AdditionalChildParameterDescriptor(
						resolveConstraintType(parameterType),
						parameter.getName(),
						parameterType,
						isParameterRequired(parameter),
						ConstraintDomain.DEFAULT
					)
				);
			}

			// parameter doesn't have appropriate additional child constraint type
			return Optional.empty();
		}
	}

	/**
	 * Checks if parameter is defined as required/non-null.
	 */
	private static boolean isParameterRequired(@Nonnull Parameter parameter) {
		return parameter.getAnnotation(Nonnull.class) != null || parameter.getType().isPrimitive();
	}
}
