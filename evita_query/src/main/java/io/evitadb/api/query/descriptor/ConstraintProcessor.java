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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

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
		final Set<ConstraintDescriptor> constraintDescriptors = createHashSet(constraintClasses.size());

		constraintClasses.forEach(constraintClass -> {
			final ConstraintDefinition constraintDefinition = findConstraintDefAnnotation(constraintClass);

			final ConstraintType type = resolveConstraintType(constraintClass);
			final ConstraintPropertyType propertyType = resolveConstraintPropertyType(constraintClass);

			final SupportedValues supportedValues = resolveSupportedValues(constraintDefinition);
			final Map<CreatorKey, ConstraintCreator> creators = resolveCreators(constraintClass, constraintDefinition);

			creators.forEach((key, creator) -> {
				final ConstraintDescriptor descriptor = new ConstraintDescriptor(
					constraintClass,
					type,
					propertyType,
					key.fullName(),
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
	private ConstraintType resolveConstraintType(@Nonnull Class<? extends Constraint<?>> constraintClass) {
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

	// todo lho should be full name in creator itself? and this in equals?
	// todo lho tests
	private record CreatorKey(@Nonnull String fullName, boolean hasClassifier) {}

	/**
	 * Gathers creator constructors, its parameters and other data from constraint class and creates creator descriptor from them
	 * and associates them with full names.
	 */
	@Nonnull
	private Map<CreatorKey, ConstraintCreator> resolveCreators(@Nonnull Class<? extends Constraint<?>> constraintClass,
	                                                       @Nonnull ConstraintDefinition constraintDefinition) {
		final Map<CreatorKey, ConstraintCreator> creators = createHashMap(4);

		findCreators(constraintClass).forEach(creatorTemplate -> {
			final Creator creatorDefinition = findCreatorAnnotation(creatorTemplate);

			final String fullName;
			if (creatorDefinition.suffix().isEmpty()) {
				fullName = constraintDefinition.name();
			} else {
				fullName = constraintDefinition.name() + StringUtils.capitalize(creatorDefinition.suffix());
			}

			final List<ParameterDescriptor> parameterDescriptors = resolveCreatorParameters(creatorTemplate);

			Assert.isPremiseValid(
				!creators.containsKey(fullName),
				"Constraint `" + constraintClass.getName() + "` has multiple creator constructors with suffix `" + creatorDefinition.suffix() + "`."
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

			final ConstraintCreator creator = new ConstraintCreator(creatorTemplate, parameterDescriptors, implicitClassifier);
			creators.put(
				new CreatorKey(fullName, creator.hasClassifierParameter() || creator.implicitClassifier() instanceof FixedImplicitClassifier),
				creator
			);
		});

		return creators;
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
	private List<ParameterDescriptor> resolveCreatorParameters(@Nonnull Executable creator) {
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

			final ValueParameterDescriptor valueParameter = resolveValueParameter(parameter);
			if (valueParameter != null) {
				parameterDescriptors.add(valueParameter);
				continue;
			}

			final ChildParameterDescriptor childParameter = resolveChildParameter(
				parameter,
				creator.getDeclaringClass()
			);
			if (childParameter != null) {
				parameterDescriptors.add(childParameter);
				continue;
			}

			final AdditionalChildParameterDescriptor additionalChildParameter = resolveAdditionalChildParameter(
				parameter,
				creator.getDeclaringClass()
			);
			if (additionalChildParameter != null) {
				parameterDescriptors.add(additionalChildParameter);
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
	@Nullable
	private ValueParameterDescriptor resolveValueParameter(@Nonnull Parameter parameter) {
		final Value value = parameter.getAnnotation(Value.class);
		if (value == null) {
			return null;
		}

		//noinspection unchecked
		return new ValueParameterDescriptor(
			parameter.getName(),
			(Class<? extends Serializable>) parameter.getType(),
			isParameterRequired(parameter),
			value.requiresPlainType()
		);
	}

	/**
	 * Tries to resolve constructor parameter as direct child parameter.
	 */
	@Nullable
	private ChildParameterDescriptor resolveChildParameter(@Nonnull Parameter parameter,
	                                                       @Nonnull Class<?> constraintClass) {
		Assert.isPremiseValid(
			ConstraintContainer.class.isAssignableFrom(constraintClass),
			"Constraint `" + constraintClass.getName() + "` have child but it is not a container."
		);

		final Child child = parameter.getAnnotation(Child.class);
		if (child == null) {
			return null;
		}

		final Class<?> parameterType = parameter.getType();
		Assert.isPremiseValid(
			Constraint.class.isAssignableFrom(parameterType) ||
				(parameterType.isArray() && Constraint.class.isAssignableFrom(parameterType.getComponentType())),
			"Constraint `" + constraintClass.getName() + "` have child that is not a constraint."
		);

		//noinspection unchecked
		return new ChildParameterDescriptor(
			parameter.getName(),
			parameterType,
			isParameterRequired(parameter),
			child.domain(),
			child.uniqueChildren(),
			Arrays.stream(child.allowed())
				.map(c -> (Class<Constraint<?>>) c)
				.collect(Collectors.toUnmodifiableSet()),
			Arrays.stream(child.forbidden())
				.map(c -> (Class<Constraint<?>>) c)
				.collect(Collectors.toUnmodifiableSet())
		);
	}

	/**
	 * Tries to resolve constructor parameter as additional child parameter.
	 */
	@Nullable
	private AdditionalChildParameterDescriptor resolveAdditionalChildParameter(@Nonnull Parameter parameter,
	                                                                           @Nonnull Class<?> constraintClass) {
		Assert.isPremiseValid(
			ConstraintContainer.class.isAssignableFrom(constraintClass),
			"Constraint `" + constraintClass.getName() + "` have additional child but it is not a container."
		);

		final AdditionalChild additionalChild = parameter.getAnnotation(AdditionalChild.class);
		if (additionalChild == null) {
			return null;
		}

		final Class<?> parameterType = parameter.getType();
		Assert.isPremiseValid(
			// Because the additional child constraint is usually another separate container, it is possible that it has
			// its own array of constraints, that's why we don't support arrays of the actual container. Also, if it would
			// be just basic constraint instead of container we would have to have logic additional implicit containers in place
			// which currently doesn't make sense.
			!parameterType.isArray() && ConstraintContainer.class.isAssignableFrom(parameterType),
			"Constraint `" + constraintClass.getName() + "` have additional child that is not a constraint container or is an array of constraints."
		);

		//noinspection unchecked
		return new AdditionalChildParameterDescriptor(
			resolveConstraintType((Class<? extends Constraint<?>>) parameterType),
			parameter.getName(),
			parameterType,
			isParameterRequired(parameter),
			additionalChild.domain()
		);
	}

	/**
	 * Checks if parameter is defined as required/non-null.
	 */
	private static boolean isParameterRequired(@Nonnull Parameter parameter) {
		return parameter.getAnnotation(Nonnull.class) != null || parameter.getType().isPrimitive();
	}
}
