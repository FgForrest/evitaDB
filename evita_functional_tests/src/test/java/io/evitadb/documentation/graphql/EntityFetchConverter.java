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
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts {@link EntityFetch} require constraint from {@link io.evitadb.api.query.Query} into
 * GraphQL output fields for query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntityFetchConverter {

	public void convert(@Nonnull CatalogSchemaContract catalogSchema,
	                    @Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                    @Nonnull String entityType,
	                    @Nullable EntityFetch entityFetch) {
		fieldsBuilder.addPrimitiveField(EntityDescriptor.PRIMARY_KEY);
		if (catalogSchema.getEntitySchemaOrThrowException(entityType).isWithHierarchy()) {
			fieldsBuilder.addPrimitiveField(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY);
		}

		if (entityFetch == null || entityFetch.getChildrenCount() == 0) {
			return;
		}

		/* TODO LHO - add support for references */
		//Assert.isPremiseValid(entityFetch.getChildrenCount() == 1, "Currently only attributeContent constraint is supported");
		final AttributeContent attributeContent = QueryUtils.findConstraint(entityFetch, AttributeContent.class, SeparateEntityContentRequireContainer.class);
		if (attributeContent != null) {
			final String[] attributeNames = attributeContent.getAttributeNames();
			Assert.isPremiseValid(attributeNames.length > 0, "Fetching all attributes is not supported by GraphQL.");

			fieldsBuilder.addObjectField(
				EntityDescriptor.ATTRIBUTES,
				attributesBuilder -> {
					for (String attributeName : attributeNames) {
						attributesBuilder.addPrimitiveField(StringUtils.toCamelCase(attributeName));
					}
				}
			);
		}
	}
}
