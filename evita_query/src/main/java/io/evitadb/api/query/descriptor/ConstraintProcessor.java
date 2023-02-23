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
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.SilentImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues;
import io.evitadb.api.query.descriptor.annotation.ConstraintChildrenParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
			final ConstraintDef constraintDef = findConstraintDefAnnotation(constraintClass);

			final ConstraintType type = resolveConstraintType(constraintClass);
			final ConstraintPropertyType propertyType = resolveConstraintPropertyType(constraintClass);

			final SupportedValues supportedValues = resolveSupportedValues(constraintDef);
			final Map<String, ConstraintCreator> creators = resolveCreators(constraintClass, constraintDef);

			creators.forEach((fullName, creator) -> {
				final ConstraintDescriptor descriptor = new ConstraintDescriptor(
					constraintClass,
					type,
					propertyType,
					fullName,
					constraintDef.shortDescription(),
					Set.of(constraintDef.supportedIn()),
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
	private  ConstraintDef findConstraintDefAnnotation(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		final ConstraintDef constraintDef = constraintClass.getAnnotation(ConstraintDef.class);
		if (constraintDef == null) {
			throw new EvitaInternalError(
				"Constraint `" + constraintClass.getName() + "` has been registered but there is no definition specified."
			);
		}
		return constraintDef;
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
	private SupportedValues resolveSupportedValues(@Nonnull ConstraintDef constraintDef) {
		final SupportedValues supportedValues;

		final ConstraintSupportedValues constraintSupportedValues = constraintDef.supportedValues();
		if (!constraintSupportedValues.allTypesSupported() && constraintSupportedValues.supportedTypes().length == 0) {
			supportedValues = null;
		} else {
			supportedValues = new SupportedValues(
				constraintSupportedValues.supportedTypes().length > 0 ?
					Set.of(constraintSupportedValues.supportedTypes()) :
					EvitaDataTypes.getSupportedDataTypes(),
				constraintDef.supportedValues().arraysSupported()
			);
		}
		return supportedValues;
	}

	/**
	 * Gathers creator constructors, its parameters and other data from constraint class and creates creator descriptor from them
	 * and associates them with full names.
	 */
	@Nonnull
	private Map<String, ConstraintCreator> resolveCreators(@Nonnull Class<? extends Constraint<?>> constraintClass,
	                                                       @Nonnull ConstraintDef constraintDef) {
		final Map<String, ConstraintCreator> creators = createHashMap(4);

		findCreatorConstructors(constraintClass).forEach(creatorConstructor -> {
			final ConstraintCreatorDef creatorDef = findCreatorDefAnnotation(creatorConstructor);

			final String fullName;
			if (creatorDef.suffix().isEmpty()) {
				fullName = constraintDef.name();
			} else {
				fullName = constraintDef.name() + StringUtils.capitalize(creatorDef.suffix());
			}

			final List<ParameterDescriptor> parameterDescriptors = resolveCreatorParameters(creatorConstructor);

			Assert.isPremiseValid(
				!creators.containsKey(fullName),
				"Constraint `" + constraintClass.getName() + "` has multiple creator constructors with suffix `" + creatorDef.suffix() + "`."
			);

			final ImplicitClassifier implicitClassifier;
			if (creatorDef.silentImplicitClassifier() && !creatorDef.implicitClassifier().isEmpty()) {
				throw new EvitaInternalError(
					"Constraint `" + constraintClass.getName() + "` has both implicit classifiers specified. Cannot decide which one to use. Please define only one of them."
				);
			} else if (creatorDef.silentImplicitClassifier()) {
				implicitClassifier = new SilentImplicitClassifier();
			} else if (!creatorDef.implicitClassifier().isEmpty()) {
				implicitClassifier = new FixedImplicitClassifier(creatorDef.implicitClassifier());
			} else {
				implicitClassifier = null;
			}

			creators.put(
				fullName,
				new ConstraintCreator(creatorConstructor, parameterDescriptors, implicitClassifier)
			);
		});

		return creators;
	}

	/**
	 * Tries to find annotation creator constructor.
	 */
	@Nonnull
	private Set<Constructor<?>> findCreatorConstructors(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		final Set<Constructor<?>> creatorConstructors = Arrays.stream(constraintClass.getDeclaredConstructors())
			.filter(constructor -> constructor.getAnnotation(ConstraintCreatorDef.class) != null)
			.collect(Collectors.toUnmodifiableSet());
		Assert.isPremiseValid(
			!creatorConstructors.isEmpty(),
			"Registered constraint `" + constraintClass.getName() + "` is missing creator."
		);
		return creatorConstructors;
	}

	/**
	 * Tries to find creator definition on constructor.
	 */
	@Nonnull
	private ConstraintCreatorDef findCreatorDefAnnotation(@Nonnull Constructor<?> creatorConstructor) {
		final ConstraintCreatorDef creatorDef = creatorConstructor.getAnnotation(ConstraintCreatorDef.class);
		if (creatorDef == null) {
			throw new EvitaInternalError(
				"Constraint `" + creatorConstructor.getDeclaringClass().getName() + "` has been registered, creator constructor found but there is no creator definition specified."
			);
		}
		return creatorDef;
	}

	/**
	 * Transforms creator constructor Java parameters to its corresponding descriptors in same order.
	 */
	@Nonnull
	private List<ParameterDescriptor> resolveCreatorParameters(@Nonnull Constructor<?> creatorConstructor) {
		final List<ParameterDescriptor> parameterDescriptors = new LinkedList<>();

		final Parameter[] creatorParameters = creatorConstructor.getParameters();
		for (Parameter parameter : creatorParameters) {
			final ClassifierParameterDescriptor classifierParameter = resolveClassifierParameter(
				parameter,
				creatorConstructor.getDeclaringClass()
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
				creatorConstructor.getDeclaringClass()
			);
			if (childParameter != null) {
				parameterDescriptors.add(childParameter);
				continue;
			}

			throw new EvitaInternalError(
				"Constraint `" + creatorConstructor.getDeclaringClass().getName() + "` has creator parameter without supported annotation."
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
		final ConstraintClassifierParamDef classifierDef = parameter.getAnnotation(ConstraintClassifierParamDef.class);
		if (classifierDef == null) {
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
		final ConstraintValueParamDef valueDef = parameter.getAnnotation(ConstraintValueParamDef.class);
		if (valueDef == null) {
			return null;
		}

		//noinspection unchecked
		return new ValueParameterDescriptor(
			parameter.getName(),
			(Class<? extends Serializable>) parameter.getType(),
			isParameterRequired(parameter),
			valueDef.requiresPlainType()
		);
	}

	/**
	 * Tries to resolve constructor parameter as children parameter.
	 */
	@Nullable
	private ChildParameterDescriptor resolveChildParameter(@Nonnull Parameter parameter,
	                                                       @Nonnull Class<?> constraintClass) {
		final ConstraintChildrenParamDef childrenDef = parameter.getAnnotation(ConstraintChildrenParamDef.class);
		if (childrenDef == null) {
			return null;
		}

		final Class<?> parameterType = parameter.getType();
		Assert.isPremiseValid(
			Constraint.class.isAssignableFrom(parameterType) ||
				(parameterType.isArray() && Constraint.class.isAssignableFrom(parameterType.getComponentType())),
			"Constraint `" + constraintClass.getName() + "` have children that are not constraints."
		);

		//noinspection unchecked
		return new ChildParameterDescriptor(
			parameter.getName(),
			parameterType,
			isParameterRequired(parameter),
			childrenDef.uniqueChildren(),
			Arrays.stream(childrenDef.allowed())
				.map(c -> (Class<Constraint<?>>) c)
				.collect(Collectors.toUnmodifiableSet()),
			Arrays.stream(childrenDef.forbidden())
				.map(c -> (Class<Constraint<?>>) c)
				.collect(Collectors.toUnmodifiableSet())
		);
	}

	/**
	 * Checks if parameter is defined as required/non-null.
	 */
	private static boolean isParameterRequired(@Nonnull Parameter parameter) {
		return parameter.getAnnotation(Nonnull.class) != null || parameter.getType().isPrimitive();
	}
}
