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

package io.evitadb.documentation.graphql;

import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class EntityFetchGraphQLFieldsBuilder {

	public void build(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder, @Nullable EntityFetch entityFetch) {
		if (entityFetch == null) {
			return;
		}

		fieldsBuilder.addPrimitiveField(EntityDescriptor.PRIMARY_KEY);
		if (entityFetch.getChildrenCount() == 0) {
			return;
		}

		Assert.isPremiseValid(entityFetch.getChildrenCount() == 1, "Currently only attributeContent constraint is supported");
		final AttributeContent attributeContent = QueryUtils.findConstraint(entityFetch, AttributeContent.class);
		if (attributeContent != null) {
			final String[] attributeNames = attributeContent.getAttributeNames();
			Assert.isPremiseValid(attributeNames.length > 0, "Fetching all attributes is not supported by GraphQL.");
			fieldsBuilder.addObjectField(
				EntityDescriptor.ATTRIBUTES,
				builder -> {
					for (String attributeName : attributeNames) {
						builder.addPrimitiveField(StringUtils.toCamelCase(attributeName));
					}
				}
			);
		}
	}
}
