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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Contains metadata for reconstructing original constraint described by {@link ConstraintDescriptor}.
 *
 * @param constructor main constructor for reconstructing the original constraint usually from {@link #parameters()}
 * @param parameters descriptors of original parameters of {@link #constructor()} in same order to be able to reconstruct
 *                   the original constraint
 * @param implicitClassifier fixed implicit classifier, alternative to dynamic classifier parameter
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record ConstraintCreator(@Nonnull Constructor<?> constructor,
                                @Nonnull List<ParameterDescriptor> parameters,
                                @Nullable ImplicitClassifier implicitClassifier) {

	public ConstraintCreator(@Nonnull Constructor<?> constructor,
	                         @Nonnull List<ParameterDescriptor> parameters,
	                         @Nullable ImplicitClassifier implicitClassifier) {
		this.constructor = constructor;
		this.parameters = parameters;
		this.implicitClassifier = implicitClassifier;

		final boolean hasClassifierDescriptor = this.parameters.stream().anyMatch(ClassifierParameterDescriptor.class::isInstance);
		final boolean hasImplicitClassifier = this.implicitClassifier != null;
		Assert.isPremiseValid(
			!(hasClassifierDescriptor && hasImplicitClassifier),
			"Constraint cannot have both classifier parameter and implicit classifier specified."
		);
		Assert.isPremiseValid(
			this.parameters.stream().filter(ClassifierParameterDescriptor.class::isInstance).count() <= 1,
			"Constraint must have maximum of 1 classifier."
		);

		// todo lho what about additional children parameters, should it be as parameters or as other children and what about validation
	}

	/**
	 * Instantiates new constraint with passed arguments.
	 *
	 * @param args arguments for constraint constructor
	 * @param parsedName name of parsed constraint in target API, used to locale errors
	 * @return new constraint
	 */
	public Constraint<?> instantiateConstraint(@Nonnull Object[] args, @Nonnull String parsedName) {
		try {
			constructor().trySetAccessible();
			return (Constraint<?>) constructor().newInstance(args);
		} catch (Exception e) {
			throw new EvitaInternalError(
				"Could not instantiate constraint `" + parsedName + "` to original constraint `" + constructor.getDeclaringClass().getName() + "`: " + e.getMessage(),
				"Could not recreate constraint `" + parsedName + "`.",
				e
			);
		}
	}

	/**
	 * Whether constraint has implicit classifier instead of classifier parameter.
	 */
	public boolean hasImplicitClassifier() {
		return implicitClassifier() != null;
	}

	/**
	 * Whether there is classifier parameter in {@link #parameters()}.
	 */
	public boolean hasClassifierParameter() {
		return parameters().stream().anyMatch(ClassifierParameterDescriptor.class::isInstance);
	}

	/**
	 * Whether this constraint requires classifier, either fixed {@link #implicitClassifier()} or dynamic {@link #classifierParameter()}.
	 */
	public boolean needsClassifier() {
		return hasClassifierParameter() || hasImplicitClassifier();
	}

	/**
	 * Finds classifier parameter in {@link #parameters()}.
	 */
	@Nonnull
	public Optional<ClassifierParameterDescriptor> classifierParameter() {
		return parameters().stream()
			.filter(ClassifierParameterDescriptor.class::isInstance)
			.map(ClassifierParameterDescriptor.class::cast)
			.findFirst();
	}

	/**
	 * Finds value parameters in {@link #parameters()}.
	 */
	@Nonnull
	public List<ValueParameterDescriptor> valueParameters() {
		return parameters().stream()
			.filter(ValueParameterDescriptor.class::isInstance)
			.map(ValueParameterDescriptor.class::cast)
			.toList();
	}

	/**
	 * Finds children parameter in {@link #parameters()}.
	 */
	@Nonnull
	public Optional<ChildParameterDescriptor> childParameter() {
		return parameters().stream()
			.filter(ChildParameterDescriptor.class::isInstance)
			.map(ChildParameterDescriptor.class::cast)
			.findFirst();
	}

	/**
	 * Represents classifier that is not specified by client but by system.
	 */
	public sealed interface ImplicitClassifier permits SilentImplicitClassifier, FixedImplicitClassifier {}

	/**
	 * Implicit classifier that is silently resolved by system without client knowing.
	 */
	public record SilentImplicitClassifier() implements ImplicitClassifier {}

	/**
	 * Implicit classifier that has fixed value specified during constraint definition.
	 */
	public record FixedImplicitClassifier(@Nonnull String classifier) implements ImplicitClassifier {

		public FixedImplicitClassifier {
			Assert.isPremiseValid(!classifier.isEmpty(), "Classifier cannot be empty");
		}
	}

	/**
	 * Common ancestor for all constraint parameters.
	 */
	public interface ParameterDescriptor {}

	/**
	 * Describes single constraint constructor parameter whose purpose is to classify target data (e.g. entity type, attribute name...).
	 * Currently, if classifier parameter is present it is always required.
	 */
	public record ClassifierParameterDescriptor(@Nonnull String name) implements ParameterDescriptor {}

	/**
	 * Describes single constraint constructor parameter which holds some specific value to usually compare against target
	 * data.
	 *
	 * @param name name of original parameter
	 * @param type data type of original parameter
	 * @param required value cannot be null
	 * @param requiresPlainType if true, value is required to be plain type of original one (e.g. if original type is integer range, this constraint
	 *                          requires integer to be passed)
	 */
	public record ValueParameterDescriptor(@Nonnull String name,
	                                       @Nonnull Class<? extends Serializable> type,
	                                       boolean required,
	                                       boolean requiresPlainType) implements ParameterDescriptor {
	}

	/**
	 * Describes single constraint constructor parameter.
	 *
	 * @param name name of original parameter
	 * @param type data type of original parameter
	 * @param required children cannot be null
	 * @param uniqueChildren if each child constraint can be passed only once in this list parameter.
	 * @param allowedChildTypes set of allowed child constraints. Constraint not specified in this set will be forbidden.
	 * @param forbiddenChildTypes set of forbidden child constraints. All constraints are allowed except of these.
	 */
	public record ChildParameterDescriptor(@Nonnull String name,
	                                       @Nonnull Class<?> type,
	                                       boolean required,
	                                       boolean uniqueChildren,
	                                       @Nonnull Set<Class<? extends Constraint<?>>> allowedChildTypes,
	                                       @Nonnull Set<Class<? extends Constraint<?>>> forbiddenChildTypes) implements ParameterDescriptor {
	}
}
