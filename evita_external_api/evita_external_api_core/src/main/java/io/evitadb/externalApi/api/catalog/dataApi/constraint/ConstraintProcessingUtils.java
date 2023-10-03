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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Utils for building API-specific schema from {@link io.evitadb.api.query.descriptor.ConstraintDescriptor}s and resolving
 * client input into original {@link io.evitadb.api.query.Constraint}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ConstraintProcessingUtils {

	/**
	 * Constraint property types properties
	 */

	public static final String GENERIC_PREFIX = "";
	private static final String ENTITY_PREFIX = "entity";
	private static final String ATTRIBUTE_PREFIX = "attribute";
	private static final String ASSOCIATED_DATA_PREFIX = "associatedData";
	private static final String PRICE_PREFIX = "price";
	private static final String REFERENCE_PREFIX = "reference";
	private static final String HIERARCHY_PREFIX = "hierarchy";
	private static final String FACET_PREFIX = "facet";

	private static final Map<ConstraintPropertyType, String> PROPERTY_TYPE_TO_PREFIX = Map.of(
		ConstraintPropertyType.GENERIC, GENERIC_PREFIX,
		ConstraintPropertyType.ENTITY, ENTITY_PREFIX,
		ConstraintPropertyType.ATTRIBUTE, ATTRIBUTE_PREFIX,
		ConstraintPropertyType.ASSOCIATED_DATA, ASSOCIATED_DATA_PREFIX,
		ConstraintPropertyType.PRICE, PRICE_PREFIX,
		ConstraintPropertyType.REFERENCE, REFERENCE_PREFIX,
		ConstraintPropertyType.HIERARCHY, HIERARCHY_PREFIX,
		ConstraintPropertyType.FACET, FACET_PREFIX
	);

	private static final Map<String, ConstraintPropertyType> PREFIX_TO_PROPERTY_TYPE = Map.of(
		ENTITY_PREFIX, ConstraintPropertyType.ENTITY,
		ATTRIBUTE_PREFIX, ConstraintPropertyType.ATTRIBUTE,
		ASSOCIATED_DATA_PREFIX, ConstraintPropertyType.ASSOCIATED_DATA,
		PRICE_PREFIX, ConstraintPropertyType.PRICE,
		REFERENCE_PREFIX, ConstraintPropertyType.REFERENCE,
		HIERARCHY_PREFIX, ConstraintPropertyType.HIERARCHY,
		FACET_PREFIX, ConstraintPropertyType.FACET
	);

	private static final Map<ConstraintDomain, ConstraintPropertyType> DOMAIN_TO_PROPERTY_TYPE = Map.of(
		ConstraintDomain.GENERIC, ConstraintPropertyType.GENERIC,
		ConstraintDomain.ENTITY, ConstraintPropertyType.ENTITY,
		ConstraintDomain.REFERENCE, ConstraintPropertyType.REFERENCE,
		ConstraintDomain.HIERARCHY, ConstraintPropertyType.HIERARCHY,
		ConstraintDomain.FACET, ConstraintPropertyType.FACET
	);

	private static final Map<ConstraintPropertyType, ConstraintDomain> PROPERTY_TYPE_TO_DOMAIN = Map.of(
		ConstraintPropertyType.GENERIC, ConstraintDomain.GENERIC,
		ConstraintPropertyType.ENTITY, ConstraintDomain.ENTITY,
		ConstraintPropertyType.ATTRIBUTE, ConstraintDomain.GENERIC, // this property type doesn't currently have its own domain, generic domain is used as safe fallback
		ConstraintPropertyType.ASSOCIATED_DATA, ConstraintDomain.GENERIC, // this property type doesn't currently have its own domain, generic domain is used as safe fallback
		ConstraintPropertyType.PRICE, ConstraintDomain.GENERIC, // this property type doesn't currently have its own domain, generic domain is used as safe fallback
		ConstraintPropertyType.REFERENCE, ConstraintDomain.REFERENCE,
		ConstraintPropertyType.HIERARCHY, ConstraintDomain.HIERARCHY,
		ConstraintPropertyType.FACET, ConstraintDomain.FACET
	);


	/**
	 * Wrapper range properties
	 */

	public static final String WRAPPER_RANGE_FROM_VALUE_PARAMETER = "from";
	public static final String WRAPPER_RANGE_TO_VALUE_PARAMETER = "to";
	public static final int WRAPPER_RANGE_PARAMETERS_COUNT = 2;

	/**
	 * Finds correct query JSON key prefix by property type.
	 */
	@Nonnull
	public static Optional<String> getPrefixForPropertyType(@Nonnull ConstraintPropertyType propertyType) {
		return Optional.ofNullable(PROPERTY_TYPE_TO_PREFIX.get(propertyType));
	}

	/**
	 * Finds corresponding property type for query JSON key prefix. Returns found prefix with corresponding property type.
	 */
	@Nonnull
	public static Entry<String, ConstraintPropertyType> getPropertyTypeFromPrefix(@Nonnull String s) {
		return ConstraintProcessingUtils.PREFIX_TO_PROPERTY_TYPE.entrySet()
			.stream()
			.filter(it -> s.startsWith(it.getKey()))
			.findFirst()
			.orElse(Map.entry(ConstraintProcessingUtils.GENERIC_PREFIX, ConstraintPropertyType.GENERIC));
	}

	/**
	 * Finds property type that is valid in passed domain.
	 */
	@Nonnull
	public static ConstraintPropertyType getFallbackPropertyTypeForDomain(@Nonnull ConstraintDomain domain) {
		Assert.isPremiseValid(
			!domain.isDynamic(),
			() -> new ExternalApiInternalError("Dynamic domain (`" + domain + "`) cannot be mapped to specific property type.")
		);
		return Optional.ofNullable(DOMAIN_TO_PROPERTY_TYPE.get(domain))
			.orElseThrow(() -> new ExternalApiInternalError("Domain `" + domain + "` doesn't have assigned property type."));
	}

	/**
	 * Finds domain that corresponds to specified property type of constraint.
	 */
	@Nonnull
	public static ConstraintDomain getDomainForPropertyType(@Nonnull ConstraintPropertyType propertyType) {
		return Optional.ofNullable(PROPERTY_TYPE_TO_DOMAIN.get(propertyType))
			.orElseThrow(() -> new ExternalApiInternalError("Property type `" + propertyType + "` doesn't have assigned default domain."));
	}

	/**
	 * Decides which value structure a query in API should have depending on creator parameters.
	 */
	@Nonnull
	public static ConstraintValueStructure getValueStructureForConstraintCreator(@Nonnull ConstraintCreator creator) {
		final List<ValueParameterDescriptor> valueParameters = creator.valueParameters();
		final List<ChildParameterDescriptor> childParameters = creator.childParameters();
		final List<AdditionalChildParameterDescriptor> additionalChildParameters = creator.additionalChildParameters();

		final ConstraintValueStructure valueStructure;
		if (valueParameters.isEmpty() && childParameters.isEmpty() && additionalChildParameters.isEmpty()) {
			valueStructure = ConstraintValueStructure.NONE;
		} else if (valueParameters.size() == 1 && childParameters.isEmpty() && additionalChildParameters.isEmpty()) {
			valueStructure = ConstraintValueStructure.PRIMITIVE;
		} else if (
			valueParameters.size() == WRAPPER_RANGE_PARAMETERS_COUNT &&
			childParameters.isEmpty() &&
			additionalChildParameters.isEmpty() &&
			valueParameters.stream().filter(p -> p.name().equals(WRAPPER_RANGE_FROM_VALUE_PARAMETER) || p.name().equals(WRAPPER_RANGE_TO_VALUE_PARAMETER)).count() == WRAPPER_RANGE_PARAMETERS_COUNT &&
			valueParameters.get(0).type().equals(valueParameters.get(1).type())
		) {
			valueStructure = ConstraintValueStructure.WRAPPER_RANGE;
		} else if (
			valueParameters.isEmpty() &&
			childParameters.size() == 1 &&
			ClassUtils.isAbstract(childParameters.get(0).type()) &&
			additionalChildParameters.isEmpty()
		) {
			valueStructure = ConstraintValueStructure.CHILD;
		} else {
			valueStructure = ConstraintValueStructure.WRAPPER_OBJECT;
		}

		return valueStructure;
	}
}
