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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Describes single variant of {@link Constraint} with all important info to be able to e.g. build another query system corresponding
 * to the main query system and reconstruct from it the original query. Each variant of query has its own unique
 * full name (name + creator suffix) and corresponding creator (each {@link Creator}
 * in {@link Constraint} create one descriptor).
 * <p>
 * Descriptor contains basic categorizing data such as {@link #type()} and {@link #propertyType()} as well as concrete
 * metadata like {@link #fullName()} or {@link #supportedValues()}.
 * Descriptor also contains the default creator constructor and its parameters to be able to reconstruct the original query.
 * <p>
 * It uses set of annotations for describing actual constraints with {@link ConstraintDefinition}
 * as the main one. Those annotations are then processed by {@link ConstraintDescriptorProvider} which generated these
 * descriptors.
 * <p>
 * Equality is determined only {@link #type()}, {@link #propertyType()} and {@link #fullName()} as these properties defines
 * uniqueness of each query. There cannot be multiple constraints with these properties because that would create
 * ambiguity in query search and reconstruction.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ConstraintDescriptor implements Comparable<ConstraintDescriptor> {

	private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z]*$");

	@Nonnull private final Class<?> constraintClass;
	@Nonnull private final ConstraintType type;
	@Nonnull private final ConstraintPropertyType propertyType;
	@Nonnull private final String fullName;
	@Nonnull private final String shortDescription;
	@Nonnull private final String userDocsLink;
	@Nonnull private final Set<ConstraintDomain> supportedIn;
	@Nullable private final SupportedValues supportedValues;
	@Nonnull private final ConstraintCreator creator;

	public ConstraintDescriptor(@Nonnull Class<?> constraintClass,
	                            @Nonnull ConstraintType type,
	                            @Nonnull ConstraintPropertyType propertyType,
	                            @Nonnull String fullName,
								@Nonnull String shortDescription,
								@Nonnull String userDocsLink,
	                            @Nonnull Set<ConstraintDomain> supportedIn,
	                            @Nullable SupportedValues supportedValues,
	                            @Nonnull ConstraintCreator creator) {
		this.constraintClass = constraintClass;
		this.type = type;
		this.propertyType = propertyType;
		this.fullName = fullName;
		this.shortDescription = shortDescription;
		this.userDocsLink = constructFullUserDocsLink(userDocsLink);
		this.supportedIn = supportedIn;
		this.supportedValues = supportedValues;
		this.creator = creator;

		Assert.isPremiseValid(
			NAME_PATTERN.matcher(this.fullName).matches(),
			"Constraint name `" + fullName + "` has invalid format. Should conform to `[a-z][a-zA-Z]*`."
		);
		Assert.isPremiseValid(
			!this.shortDescription.isEmpty(),
			"Constraint `" + fullName + "` is missing short description."
		);
		Assert.isPremiseValid(
			!userDocsLink.isEmpty() && userDocsLink.charAt(0) == '/',
			"Constraint `" + fullName + "` is missing user documentation link or the link has incorrect format."
		);

		if (propertyType == ConstraintPropertyType.GENERIC) {
			Assert.isPremiseValid(
				!creator.hasClassifier(),
				"Creator for query `" + this.constraintClass.getName() + "` cannot have classifier because it is generic query."
			);
		}
	}

	/**
	 * Class of represented constraint.
	 */
	@Nonnull
	public Class<?> constraintClass() {
		return this.constraintClass;
	}

	/**
	 * Specifies what is purpose of the constraint
	 */
	@Nonnull
	public ConstraintType type() {
		return this.type;
	}

	/**
	 * Specifies on which data the constraint will be operating.
	 */
	@Nonnull
	public ConstraintPropertyType propertyType() {
		return this.propertyType;
	}

	/**
	 * Base name of condition or operation this constraint represent, e.g. `equals` + creator suffix.
	 * Its format must be in camelCase.
	 */
	@Nonnull
	public String fullName() {
		return this.fullName;
	}

	@Nonnull
	public String shortDescription() {
		return this.shortDescription;
	}

	/**
	 * Full link to user documentation of this constraint on evitadb.io web.
	 */
	@Nonnull
	public String userDocsLink() {
		return this.userDocsLink;
	}

	/**
	 * Set of domains in which this constraint is supported in and can be used in when querying.
	 */
	@Nonnull
	public Set<ConstraintDomain> supportedIn() {
		return this.supportedIn;
	}

	/**
	 * Description of target data this constraint can operate on. If null, query doesn't support any values
	 */
	@Nullable
	public SupportedValues supportedValues() {
		return this.supportedValues;
	}

	/**
	 * Contains data for reconstructing original constraints.
	 */
	@Nonnull
	public ConstraintCreator creator() {
		return this.creator;
	}

	@Override
	public boolean equals(Object o) {
		// lombok cannot generate equals and hash code for records
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ConstraintDescriptor that = (ConstraintDescriptor) o;
		return this.type == that.type &&
			this.propertyType == that.propertyType &&
			this.fullName.equals(that.fullName) &&
			this.creator.hasClassifierParameter() == that.creator.hasClassifierParameter() &&
			this.creator.hasImplicitClassifier() == that.creator.hasImplicitClassifier();
	}

	@Override
	public int hashCode() {
		// lombok cannot generate equals and hash code for records
		return Objects.hash(this.type, this.propertyType, this.fullName, this.creator.hasClassifierParameter(), this.creator.hasImplicitClassifier());
	}

	@Override
	public String toString() {
		return "ConstraintDescriptor{" +
			"constraintClass=" + this.constraintClass +
			", type=" + this.type +
			", propertyType=" + this.propertyType +
			", fullName='" + this.fullName + '\'' +
			", shortDescription='" + this.shortDescription + '\'' +
			", userDocsLink='" + this.userDocsLink + '\'' +
			", supportedIn=" + this.supportedIn +
			", supportedValues=" + this.supportedValues +
			", creator=" + this.creator +
			'}';
	}

	@Override
	public int compareTo(@Nonnull ConstraintDescriptor o) {
		int result = type().compareTo(o.type());
		if (result == 0) {
			result = propertyType().compareTo(o.propertyType());
		}
		if (result == 0) {
			result = fullName().compareTo(o.fullName());
		}
		if (result == 0) {
			result = Boolean.compare(creator().hasClassifier(), o.creator().hasClassifier());
			if (result == 0 && !creator().hasClassifier()) {
				// if neither creator has classifier, they are equal
				return 0;
			}
			if (creator().hasClassifier() && o.creator().hasClassifier()) {
				// if both creators have some kind of classifier, compare the types of the classifiers
				if (creator().hasImplicitClassifier() == o.creator().hasImplicitClassifier() &&
					creator().hasClassifierParameter() == o.creator().hasClassifierParameter()) {
					return 0;
				}
				if (creator().hasImplicitClassifier() && o.creator().hasClassifierParameter()) {
					return -1;
				}
				return 1;
			}
			return result;
		}
		return result;
	}

	@Nonnull
	private static String constructFullUserDocsLink(@Nonnull String relativeUserDocsLink) {
		return "https://evitadb.io" + relativeUserDocsLink;
	}


	/**
	 * Contains metadata about supported data types of target values this query can operate on.
	 *
	 * @param dataTypes set of value data types of target data this query can operate on
	 * @param supportsArrays if target data can be an array of supported data types
	 * @param compoundsSupported if target data can be a compound of supported data types
	 * @param nullability whether the constraint supports only nullable data or only nonnull data or both and so on.
	 */
	public record SupportedValues(@Nonnull Set<Class<?>> dataTypes,
	                              boolean supportsArrays,
								  boolean compoundsSupported,
	                              @Nonnull ConstraintNullabilitySupport nullability) {}

}
