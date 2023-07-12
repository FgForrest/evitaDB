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

package io.evitadb.test.client.query.graphql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyFromNodeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfReferenceHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfSelfHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyParentsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyRequireHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Converts {@link HierarchyOfSelf} and {@link HierarchyOfReference} require constraints from {@link io.evitadb.api.query.Query}
 * into GraphQL output fields for query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class HierarchyOfConverter extends RequireConverter {

	private final EntityFetchConverter entityFetchBuilder;

	public HierarchyOfConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		super(catalogSchema, inputJsonPrinter);
		this.entityFetchBuilder = new EntityFetchConverter(catalogSchema, inputJsonPrinter);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                    @Nonnull String entityType,
	                    @Nullable Locale locale,
	                    @Nullable HierarchyOfSelf hierarchyOfSelf,
	                    @Nullable HierarchyOfReference hierarchyOfReference) {
		if (hierarchyOfSelf == null && hierarchyOfReference == null) {
			return;
		}

		fieldsBuilder.addObjectField(ExtraResultsDescriptor.HIERARCHY, hierarchyBuilder -> {
			// self hierarchy
			if (hierarchyOfSelf != null) {
				final Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = hierarchyOfSelf.getOrderBy()
					.map(orderBy -> (Consumer<GraphQLOutputFieldsBuilder>) (hierarchyOfSelfArgumentsBuilder -> hierarchyOfSelfArgumentsBuilder
						.addFieldArgument(
							HierarchyOfSelfHeaderDescriptor.ORDER_BY,
							offset -> convertOrderConstraint(new EntityDataLocator(entityType), orderBy, offset).orElse(null)
						)
					))
					.orElse(null);

				hierarchyBuilder.addObjectField(
					HierarchyDescriptor.SELF,
					argumentsBuilder,
					hierarchyOfSelfBuilder -> buildHierarchyRequirementsFields(
						hierarchyOfSelfBuilder,
						locale,
						entityType,
						new HierarchyDataLocator(entityType),
						hierarchyOfSelf.getRequirements()
					)
				);
			}

			// referenced hierarchy
			if (hierarchyOfReference != null) {
				for (String referenceName : hierarchyOfReference.getReferenceNames()) {
					final String referencedEntityType = catalogSchema.getEntitySchemaOrThrowException(entityType)
						.getReference(referenceName)
						.get()
						.getReferencedEntityType();

					Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = null;
					if (hierarchyOfReference.getOrderBy().isPresent() ||
						hierarchyOfReference.getEmptyHierarchicalEntityBehaviour() != EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY) {
						argumentsBuilder = hierarchyOfReferenceArgumentsBuilder -> {
							hierarchyOfReference.getOrderBy()
								.ifPresent(orderBy -> {
									hierarchyOfReferenceArgumentsBuilder.addFieldArgument(
										HierarchyOfReferenceHeaderDescriptor.ORDER_BY,
										offset -> convertOrderConstraint(new EntityDataLocator(referencedEntityType), orderBy, offset).orElse(null)
									);
								});

							if (hierarchyOfReference.getEmptyHierarchicalEntityBehaviour() != EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY) {
								hierarchyOfReferenceArgumentsBuilder.addFieldArgument(
									HierarchyOfReferenceHeaderDescriptor.EMPTY_HIERARCHICAL_ENTITY_BEHAVIOUR,
									__ -> hierarchyOfReference.getEmptyHierarchicalEntityBehaviour().name()
								);
							}
						};
					}

					hierarchyBuilder.addObjectField(
						StringUtils.toCamelCase(referenceName),
						argumentsBuilder,
						hierarchyOfReferenceBuilder -> buildHierarchyRequirementsFields(
							hierarchyOfReferenceBuilder,
							locale,
							referencedEntityType,
							new HierarchyDataLocator(entityType, referenceName),
							hierarchyOfReference.getRequirements()
						)
					);
				}
			}
		});
	}

	private void buildHierarchyRequirementsFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                              @Nullable Locale locale,
												  @Nonnull String targetEntityType,
												  @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                              @Nonnull HierarchyRequireConstraint[] requirements) {
		for (HierarchyRequireConstraint requirement : requirements) {
			if (requirement instanceof HierarchyChildren children) {
				buildChildrenFields(hierarchyOfBuilder, locale, targetEntityType, hierarchyDataLocator, children);
			} else if (requirement instanceof HierarchyFromNode fromNode) {
				buildFromNodeFields(hierarchyOfBuilder, locale, targetEntityType, hierarchyDataLocator, fromNode);
			} else if (requirement instanceof HierarchyFromRoot fromRoot) {
				buildFromRootFields(hierarchyOfBuilder, locale, targetEntityType, hierarchyDataLocator, fromRoot);
			} else if (requirement instanceof HierarchyParents parents) {
				buildParentsFields(hierarchyOfBuilder, locale, targetEntityType, hierarchyDataLocator, parents);
			} else if (requirement instanceof HierarchySiblings siblings) {
				buildSiblingsFields(hierarchyOfBuilder, locale, targetEntityType, hierarchyDataLocator, siblings);
			} else {
				throw new IllegalStateException("Unsupported requirement `" + requirement.getClass().getName() + "`.");
			}
		}
	}

	private void buildChildrenFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                 @Nullable Locale locale,
									 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchyChildren children) {
		Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = null;
		if (children.getStopAt().isPresent() ||
			(children.getStatistics().isPresent() && children.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER)) {
			argumentsBuilder = childrenArgumentsBuilder -> {
				children.getStopAt().ifPresent(getStopAtArgumentBuilder(hierarchyDataLocator, childrenArgumentsBuilder));
				children.getStatistics().ifPresent(getStatisticsArgumentBuilder(childrenArgumentsBuilder));
			};
		}

		hierarchyOfBuilder.addObjectField(
			children.getOutputName(),
			HierarchyOfDescriptor.CHILDREN,
			argumentsBuilder,
			childrenBuilder -> buildLevelInfoFields(
				childrenBuilder,
				hierarchyEntityType,
				locale,
				children.getEntityFetch().orElse(null),
				children.getStatistics().orElse(null)
			)
		);
	}

	private void buildFromNodeFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                 @Nullable Locale locale,
	                                 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchyFromNode fromNode) {
		Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = fromNodeArgumentsBuilder -> {
			fromNodeArgumentsBuilder.addFieldArgument(
				HierarchyFromNodeHeaderDescriptor.NODE,
				offset -> convertRequireConstraint(hierarchyDataLocator, fromNode.getFromNode(), offset)
					.orElseThrow(() -> new IllegalStateException("Missing required node constraint"))
			);

			fromNode.getStopAt().ifPresent(getStopAtArgumentBuilder(hierarchyDataLocator, fromNodeArgumentsBuilder));
			fromNode.getStatistics().ifPresent(getStatisticsArgumentBuilder(fromNodeArgumentsBuilder));
		};

		hierarchyOfBuilder.addObjectField(
			fromNode.getOutputName(),
			HierarchyOfDescriptor.FROM_NODE,
			argumentsBuilder,
			fromNodeBuilder -> buildLevelInfoFields(
				fromNodeBuilder,
				hierarchyEntityType,
				locale,
				fromNode.getEntityFetch().orElse(null),
				fromNode.getStatistics().orElse(null)
			)
		);
	}

	private void buildFromRootFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                 @Nullable Locale locale,
	                                 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchyFromRoot fromRoot) {
		Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = null;
		if (fromRoot.getStopAt().isPresent() ||
			(fromRoot.getStatistics().isPresent() && fromRoot.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER)) {
			argumentsBuilder = childrenArgumentsBuilder -> {
				fromRoot.getStopAt().ifPresent(getStopAtArgumentBuilder(hierarchyDataLocator, childrenArgumentsBuilder));
				fromRoot.getStatistics().ifPresent(getStatisticsArgumentBuilder(childrenArgumentsBuilder));
			};
		}

		hierarchyOfBuilder.addObjectField(
			fromRoot.getOutputName(),
			HierarchyOfDescriptor.FROM_ROOT,
			argumentsBuilder,
			fromRootBuilder -> buildLevelInfoFields(
				fromRootBuilder,
				hierarchyEntityType,
				locale,
				fromRoot.getEntityFetch().orElse(null),
				fromRoot.getStatistics().orElse(null)
			)
		);
	}

	private void buildParentsFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                @Nullable Locale locale,
	                                @Nonnull String hierarchyEntityType,
	                                @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                @Nonnull HierarchyParents parents) {
		Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = null;
		if (parents.getSiblings().isPresent() ||
			parents.getStopAt().isPresent() ||
			(parents.getStatistics().isPresent() && parents.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER)) {
			argumentsBuilder = parentsArgumentsBuilder -> {
				parents.getSiblings().ifPresent(siblings -> {
					Assert.isPremiseValid(
						siblings.getStatistics().isEmpty() &&
							siblings.getEntityFetch().isEmpty(),
						"Custom statistics and entityFetch for siblings inside parents is not supported in GraphQL"
					);

					final ObjectNode siblingsArgument = jsonNodeFactory.objectNode();

					siblings.getStopAt()
						.flatMap(stopAt -> requireConstraintToJsonConverter.convert(hierarchyDataLocator, stopAt))
						.ifPresent(constraint -> siblingsArgument.putIfAbsent(HierarchyRequireHeaderDescriptor.STOP_AT.name(), constraint.value()));

					parentsArgumentsBuilder.addFieldArgument(
						HierarchyParentsHeaderDescriptor.SIBLINGS,
						offset -> inputJsonPrinter.print(offset, siblingsArgument).stripLeading()
					);
				});
				parents.getStopAt().ifPresent(getStopAtArgumentBuilder(hierarchyDataLocator, parentsArgumentsBuilder));
				parents.getStatistics().ifPresent(getStatisticsArgumentBuilder(parentsArgumentsBuilder));
			};
		}

		hierarchyOfBuilder.addObjectField(
			parents.getOutputName(),
			HierarchyOfDescriptor.PARENTS,
			argumentsBuilder,
			parentsBuilder -> buildLevelInfoFields(
				parentsBuilder,
				hierarchyEntityType,
				locale,
				parents.getEntityFetch().orElse(null),
				parents.getStatistics().orElse(null)
			)
		);
	}

	private void buildSiblingsFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
									 @Nullable Locale locale,
	                                 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchySiblings siblings) {
		Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder = null;
		if (siblings.getStopAt().isPresent() ||
			(siblings.getStatistics().isPresent() && siblings.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER)) {
			argumentsBuilder = siblingsArgumentsBuilder -> {
				siblings.getStopAt().ifPresent(getStopAtArgumentBuilder(hierarchyDataLocator, siblingsArgumentsBuilder));
				siblings.getStatistics().ifPresent(getStatisticsArgumentBuilder(siblingsArgumentsBuilder));
			};
		}

		hierarchyOfBuilder.addObjectField(
			siblings.getOutputName(),
			HierarchyOfDescriptor.SIBLINGS,
			argumentsBuilder,
			siblingsBuilder -> buildLevelInfoFields(
				siblingsBuilder,
				hierarchyEntityType,
				locale,
				siblings.getEntityFetch().orElse(null),
				siblings.getStatistics().orElse(null)
			)
		);
	}

	@Nonnull
	private Consumer<HierarchyStopAt> getStopAtArgumentBuilder(@Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                                           @Nonnull GraphQLOutputFieldsBuilder argumentsBuilder) {
		return stopAt -> argumentsBuilder.addFieldArgument(
			HierarchyRequireHeaderDescriptor.STOP_AT,
			offset -> convertRequireConstraint(hierarchyDataLocator, stopAt, offset).orElse(null)
		);
	}

	@Nonnull
	private Consumer<HierarchyStatistics> getStatisticsArgumentBuilder(@Nonnull GraphQLOutputFieldsBuilder argumentsBuilder) {
		return statistics -> {
			if (statistics.getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER) {
				argumentsBuilder.addFieldArgument(
					HierarchyRequireHeaderDescriptor.STATISTICS_BASE,
					indentation -> statistics.getStatisticsBase().name()
				);
			}
		};
	}

	private void buildLevelInfoFields(@Nonnull GraphQLOutputFieldsBuilder levelInfoBuilder,
									  @Nonnull String entityType,
                                      @Nullable Locale locale,
	                                  @Nullable EntityFetch entityFetch,
	                                  @Nullable HierarchyStatistics statistics) {
		levelInfoBuilder
			.addPrimitiveField(LevelInfoDescriptor.PARENT_PRIMARY_KEY)
			.addPrimitiveField(LevelInfoDescriptor.LEVEL)
			.addObjectField(LevelInfoDescriptor.ENTITY, entityBuilder ->
				entityFetchBuilder.convert(entityBuilder, entityType, locale, entityFetch));

		if (statistics != null) {
			if (statistics.getStatisticsType().contains(StatisticsType.QUERIED_ENTITY_COUNT)) {
				levelInfoBuilder.addPrimitiveField(LevelInfoDescriptor.QUERIED_ENTITY_COUNT);
			}
			if (statistics.getStatisticsType().contains(StatisticsType.CHILDREN_COUNT)) {
				levelInfoBuilder.addPrimitiveField(LevelInfoDescriptor.CHILDREN_COUNT);
			}
		}

		levelInfoBuilder.addPrimitiveField(LevelInfoDescriptor.HAS_CHILDREN);
	}
}
