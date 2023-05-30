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

package io.evitadb.documentation.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorResolver;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Resolves descriptor for passed {@link Constraint} based on current context.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
class ConstraintDescriptorResolver {

	@Nonnull private final ConstraintParameterValueResolver parameterValueResolver;
	@Nonnull private final DataLocatorResolver dataLocatorResolver;

	/**
	 * Extracts information from constraint and tries to find corresponding descriptor for it.
	 */
	@Nonnull
	public ParsedConstraintDescriptor resolve(@Nonnull ConstraintToJsonConvertContext convertContext,
	                                          @Nonnull Constraint<?> constraint) {
		final ConstraintDescriptor constraintDescriptor = resolveDescriptor(constraint);
		final Optional<String> classifier = resolveClassifier(constraintDescriptor, constraint);
		final DataLocator innerDataLocator = resolveInnerDataLocator(convertContext, constraintDescriptor, classifier);

		return new ParsedConstraintDescriptor(
			classifier.orElse(null),
			constraintDescriptor,
			innerDataLocator
		);
	}

	@Nonnull
	private ConstraintDescriptor resolveDescriptor(@Nonnull Constraint<?> constraint) {
		if (constraint instanceof ConstraintWithSuffix constraintWithSuffix) {
			//noinspection unchecked
			return ConstraintDescriptorProvider.getConstraint(
				(Class<? extends Constraint<?>>) constraint.getClass(),
				constraintWithSuffix.getSuffixIfApplied().orElse(null)
			);
		} else {
			//noinspection unchecked
			return ConstraintDescriptorProvider.getConstraint((Class<? extends Constraint<?>>) constraint.getClass());
		}
	}

	/**
	 * Tries to find value of classifier parameter from original constraint.
	 */
	@Nonnull
	private Optional<String> resolveClassifier(@Nonnull ConstraintDescriptor constraintDescriptor,
	                                           @Nonnull Constraint<?> constraint) {
		return constraintDescriptor.creator().classifierParameter()
			.map(it -> (String) parameterValueResolver.resolveParameterValue(constraint, it));
	}

	/**
	 * Resolves data locator relevant inside the parsed constraint based on property type which defines inner domain
	 * of the constraint for its parameters, mainly its children. We need this to provide needed data from classifier,
	 * otherwise we don't have any other point to gather such data later when resolving children.
	 */
	@Nonnull
	private DataLocator resolveInnerDataLocator(@Nonnull ConstraintToJsonConvertContext resolveContext,
	                                            @Nonnull ConstraintDescriptor constraintDescriptor,
	                                            @Nonnull Optional<String> classifier) {
		return dataLocatorResolver.resolveConstraintDataLocator(resolveContext.dataLocator(), constraintDescriptor, classifier);
	}


	/**
	 * Parsed descriptor of input {@link Constraint}.
	 */
	public record ParsedConstraintDescriptor(@Nullable String classifier,
	                                         @Nonnull ConstraintDescriptor constraintDescriptor,
	                                         @Nonnull DataLocator innerDataLocator) {}
}
