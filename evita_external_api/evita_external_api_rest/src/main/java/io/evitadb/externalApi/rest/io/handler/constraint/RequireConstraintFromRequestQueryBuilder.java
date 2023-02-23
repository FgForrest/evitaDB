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

package io.evitadb.externalApi.rest.io.handler.constraint;

import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.ParamDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Creates {@link Require} constraint for Evita query from request parameters
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RequireConstraintFromRequestQueryBuilder {

	/**
	 * Creates require constraints from request parameters
	 * @param parameters
	 * @return require constraint or <code>NULL</code> when no constraint found in parameters
	 */
	@Nullable
	public static Require buildRequire(@Nonnull Map<String, Object> parameters) {
		final EntityContentRequire[] contentRequires = getEntityContentRequires(parameters);

		if(contentRequires.length == 0 && !isBooleanParameterPresentAndTrue(parameters, ParamDescriptor.BODY_FETCH)) {
			return null;
		}

		return require(
			entityFetch(contentRequires)
		);
	}

	@Nonnull
	public static EntityContentRequire[] getEntityContentRequires(@Nonnull Map<String, Object> parameters) {

		if (isBooleanParameterPresentAndTrue(parameters, ParamDescriptor.FETCH_ALL)) {
			return entityFetchAll().getRequirements();
		}

		final List<EntityContentRequire> contentRequires = new LinkedList<>();
		if (isBooleanParameterPresentAndTrue(parameters, ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL)) {
			contentRequires.add(new AssociatedDataContent());
		} else if(isParameterPresent(parameters, ParamDescriptor.ASSOCIATED_DATA_CONTENT)) {
			contentRequires.add(new AssociatedDataContent((String[]) parameters.get(ParamDescriptor.ASSOCIATED_DATA_CONTENT.name())));
		}
		if (isBooleanParameterPresentAndTrue(parameters, ParamDescriptor.ATTRIBUTE_CONTENT_ALL)) {
			contentRequires.add(new AttributeContent());
		} else if(isParameterPresent(parameters, ParamDescriptor.ATTRIBUTE_CONTENT)) {
			contentRequires.add(new AttributeContent((String[]) parameters.get(ParamDescriptor.ATTRIBUTE_CONTENT.name())));
		}
		if (isBooleanParameterPresentAndTrue(parameters, ParamDescriptor.REFERENCE_CONTENT_ALL)) {
			contentRequires.add(new ReferenceContent());
		} else if(isParameterPresent(parameters, ParamDescriptor.REFERENCE_CONTENT)) {
			contentRequires.add(new ReferenceContent((String[]) parameters.get(ParamDescriptor.REFERENCE_CONTENT.name())));
		}
		if (isBooleanParameterPresentAndTrue(parameters, ParamDescriptor.PRICE_CONTENT)) {
			contentRequires.add(new PriceContent());
		}
		if (parameters.containsKey(ParamDescriptor.DATA_IN_LOCALES.name())) {
			contentRequires.add(new DataInLocales((Locale[]) parameters.get(ParamDescriptor.DATA_IN_LOCALES.name())));
		}

		return contentRequires.toArray(EntityContentRequire[]::new);
	}

	private static boolean isBooleanParameterPresentAndTrue(@Nonnull Map<String, Object> parameters, @Nonnull PropertyDescriptor parameter) {
		return Boolean.TRUE.equals(parameters.get(parameter.name()));
	}

	private static boolean isParameterPresent(@Nonnull Map<String, Object> parameters, @Nonnull PropertyDescriptor parameter) {
		return parameters.get(parameter.name()) != null;
	}
}
