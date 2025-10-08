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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_PART_NAMING_CONVENTION;

/**
 * Used to build constraint key in {@link ConstraintSchemaBuilder} to uniquely represent {@link ConstraintDescriptor} in
 * certain context.
 *
 * <h3>Formats</h3>
 * This parser supports following key formats
 * Key can have one of 3 formats depending on descriptor data:
 * <ul>
 *     <li>`{fullName}` - if it's generic constraint without classifier</li>
 *     <li>`{propertyType}{fullName}` - if it's not generic constraint and doesn't have classifier</li>
 *     <li>`{propertyType}{classifier}{fullName}` - if it's not generic constraint and has classifier</li>
 * </ul>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ConstraintKeyBuilder {

	/**
	 * Builds field key uniquely representing single variant of constraint.
	 *
	 * @param constraintDescriptor constraint constraintDescriptor to represent by this key
	 * @param classifierSupplier   supplies concrete classifier for constraint key if required by descriptor
	 * @return key
	 */
	@Nonnull
	public String build(@Nonnull ConstraintTraverseContext<?> traverseContext,
	                    @Nonnull ConstraintDescriptor constraintDescriptor,
	                    @Nullable Supplier<String> classifierSupplier) {
		final ConstraintCreator creator = constraintDescriptor.creator();

		final String prefix = ConstraintProcessingUtils.getPrefixForPropertyType(constraintDescriptor.propertyType())
			.orElseThrow(() -> new ExternalApiInternalError("Missing prefix pro constraint property type `" + constraintDescriptor.propertyType() + "`."));

		// we can simplify child constraint if is in same domain as its parent and if it has property type the
		// one that is expected when derived from child domain. These constraints are usually valid only in specific context
		// and not globally available
		if (!traverseContext.isAtRoot() &&
			Objects.equals(traverseContext.dataLocator().targetDomain(), Objects.requireNonNull(traverseContext.parentDataLocator()).targetDomain()) &&
			!creator.hasClassifier() &&
			constraintDescriptor.propertyType().equals(ConstraintProcessingUtils.getFallbackPropertyTypeForDomain(traverseContext.dataLocator().targetDomain()))) {
			return StringUtils.toSpecificCase(constraintDescriptor.fullName(), PROPERTY_NAME_NAMING_CONVENTION);
		}

		final StringBuilder keyBuilder = new StringBuilder(3);
		if (!prefix.isEmpty()) {
			keyBuilder.append(prefix);
		}
		if (creator.hasClassifier()) {
			final Optional<ImplicitClassifier> implicitClassifier = creator.implicitClassifier();
			if (implicitClassifier.isPresent()) {
				if (implicitClassifier.get() instanceof FixedImplicitClassifier fixedImplicitClassifier) {
					keyBuilder.append(StringUtils.toSpecificCase(fixedImplicitClassifier.classifier(), PROPERTY_NAME_PART_NAMING_CONVENTION));
				}
			} else {
				Assert.isPremiseValid(
					classifierSupplier != null,
					() -> new ExternalApiInternalError(
						"Constraint `" + constraintDescriptor.fullName() + "` requires classifier resolver but no resolver passed."
					)
				);
				keyBuilder.append(StringUtils.toSpecificCase(classifierSupplier.get(), PROPERTY_NAME_PART_NAMING_CONVENTION));
			}
		}
		keyBuilder.append(StringUtils.toSpecificCase(constraintDescriptor.fullName(), PROPERTY_NAME_PART_NAMING_CONVENTION));

		return StringUtils.toSpecificCase(keyBuilder.toString(), PROPERTY_NAME_NAMING_CONVENTION);
	}
}
