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

import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyChildren;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfReferenceHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfSelfHeaderDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class HierarchyGraphQLFieldsBuilder {

	public void build(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                  @Nullable HierarchyOfSelf hierarchyOfSelf,
	                  @Nullable HierarchyOfReference hierarchyOfReference) {
		if (hierarchyOfSelf == null && hierarchyOfReference == null) {
			return;
		}

		fieldsBuilder.addObjectField(ExtraResultsDescriptor.HIERARCHY, b1 -> {
			// self hierarchy
			if (hierarchyOfSelf != null) {
				b1.addObjectField(
					HierarchyDescriptor.SELF,
					hierarchyOfSelf.getOrderBy()
						.map(orderBy -> (Consumer<GraphQLOutputFieldsBuilder>) (ab1 -> ab1
							.addFieldArgument(
								HierarchyOfSelfHeaderDescriptor.ORDER_BY,
								indentation -> "{}" // todo lho constraint converter
							)
						))
						.orElse(null),
					fb2 -> buildHierarchyRequirements(fb2, hierarchyOfSelf.getRequirements())
				);
			}

			// referenced hierarchy
			if (hierarchyOfReference != null) {
				for (String referenceName : hierarchyOfReference.getReferenceNames()) {
					b1.addObjectField(
						StringUtils.toCamelCase(referenceName),
						hierarchyOfReference.getOrderBy().isPresent() || hierarchyOfReference.getEmptyHierarchicalEntityBehaviour() != EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY
							? ab1 -> {
								ab1.addFieldArgument(
									HierarchyOfReferenceHeaderDescriptor.ORDER_BY,
									indentation -> "{}" // todo lho constraint converter
								);
								ab1.addFieldArgument(
									HierarchyOfReferenceHeaderDescriptor.EMPTY_HIERARCHICAL_ENTITY_BEHAVIOUR,
									__ -> hierarchyOfReference.getEmptyHierarchicalEntityBehaviour().name()
								);
							}
							: null,
						fb2 -> buildHierarchyRequirements(fb2, hierarchyOfReference.getRequirements())
					);
				}
			}
		});
	}

	private void buildHierarchyRequirements(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                                        @Nonnull HierarchyRequireConstraint[] requirements) {
		for (HierarchyRequireConstraint requirement : requirements) {
			if (requirement instanceof HierarchyChildren children) {
				fieldsBuilder.addObjectField(
					children.getOutputName(),
					HierarchyOfDescriptor.CHILDREN,
					null,
					fb1 -> {}
				);
			}
		}
	}
}
