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

package io.evitadb.test.client.query.graphql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyFromNodeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfReferenceHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyParentsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyRequireHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.Argument;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.ArgumentSupplier;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts {@link HierarchyOfSelf} and {@link HierarchyOfReference} require constraints from {@link io.evitadb.api.query.Query}
 * into GraphQL output fields for query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class HierarchyOfConverter extends RequireConverter {

	private final EntityFetchConverter entityFetchConverter;

	public HierarchyOfConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull Query query) {
		super(catalogSchema, query);
		this.entityFetchConverter = new EntityFetchConverter(catalogSchema, query);
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
				final ArgumentSupplier[] arguments = hierarchyOfSelf.getOrderBy()
					.map(orderBy -> new ArgumentSupplier[] {
						(offset, multipleArguments) -> new Argument(
							HierarchyHeaderDescriptor.ORDER_BY,
							offset,
							multipleArguments,
							convertOrderConstraint(new EntityDataLocator(new ManagedEntityTypePointer(entityType)), orderBy).orElse(null)
						)
					})
					.orElse(new ArgumentSupplier[0]);

				hierarchyBuilder.addObjectField(
					HierarchyDescriptor.SELF,
					hierarchyOfSelfBuilder -> buildHierarchyRequirementsFields(
						hierarchyOfSelfBuilder,
						locale,
						entityType,
						new HierarchyDataLocator(new ManagedEntityTypePointer(entityType)),
						hierarchyOfSelf.getRequirements()
					),
					arguments
				);
			}

			// referenced hierarchy
			if (hierarchyOfReference != null) {
				for (String referenceName : hierarchyOfReference.getReferenceNames()) {
					final String referencedEntityType = this.catalogSchema.getEntitySchemaOrThrowException(entityType)
						.getReference(referenceName)
						.get()
						.getReferencedEntityType();

					final List<ArgumentSupplier> arguments = new ArrayList<>(2);
					if (hierarchyOfReference.getOrderBy().isPresent() ||
						hierarchyOfReference.getEmptyHierarchicalEntityBehaviour() != EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY) {
						if (hierarchyOfReference.getOrderBy().isPresent()) {
							arguments.add(
								(offset, multipleArguments) -> new Argument(
									HierarchyHeaderDescriptor.ORDER_BY,
									offset,
									multipleArguments,
									convertOrderConstraint(
										new EntityDataLocator(new ManagedEntityTypePointer(referencedEntityType)),
										hierarchyOfReference.getOrderBy().get()
									)
										.orElse(null)
								)
							);
						}

						if (hierarchyOfReference.getEmptyHierarchicalEntityBehaviour() != EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY) {
							arguments.add(
								(offset, multipleArguments) -> new Argument(
									HierarchyOfReferenceHeaderDescriptor.EMPTY_HIERARCHICAL_ENTITY_BEHAVIOUR,
									offset,
									multipleArguments,
									hierarchyOfReference.getEmptyHierarchicalEntityBehaviour().name()
								)
							);
						}
					}

					hierarchyBuilder.addObjectField(
						StringUtils.toCamelCase(referenceName),
						hierarchyOfReferenceBuilder -> buildHierarchyRequirementsFields(
							hierarchyOfReferenceBuilder,
							locale,
							referencedEntityType,
							new HierarchyDataLocator(new ManagedEntityTypePointer(entityType), referenceName),
							hierarchyOfReference.getRequirements()
						),
						arguments.toArray(ArgumentSupplier[]::new)
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
		final List<ArgumentSupplier> arguments = new ArrayList<>(2);
		if (children.getStopAt().isPresent()) {
			arguments.add(getStopAtArgument(children.getStopAt().get(), hierarchyDataLocator));
		}
		if (children.getStatistics().isPresent() && children.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER) {
			arguments.add(getStatisticsArgument(children.getStatistics().get()));
		}

		hierarchyOfBuilder.addObjectField(
			children.getOutputName(),
			HierarchyOfDescriptor.CHILDREN,
			childrenBuilder -> buildLevelInfoFields(
				childrenBuilder,
				hierarchyEntityType,
				locale,
				children.getEntityFetch().orElse(null),
				children.getStatistics().orElse(null)
			),
			arguments.toArray(ArgumentSupplier[]::new)
		);
	}

	private void buildFromNodeFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                 @Nullable Locale locale,
	                                 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchyFromNode fromNode) {
		final List<ArgumentSupplier> arguments = new ArrayList<>(3);
		arguments.add(
			(offset, multipleArguments) -> new Argument(
				HierarchyFromNodeHeaderDescriptor.NODE,
				offset,
				multipleArguments,
				convertRequireConstraint(hierarchyDataLocator, fromNode.getFromNode())
					.orElseThrow(() -> new IllegalStateException("Missing required node constraint"))
			)
		);
		if (fromNode.getStopAt().isPresent()) {
			arguments.add(getStopAtArgument(fromNode.getStopAt().get(), hierarchyDataLocator));
		}
		if (fromNode.getStatistics().isPresent() && fromNode.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER) {
			arguments.add(getStatisticsArgument(fromNode.getStatistics().get()));
		}

		hierarchyOfBuilder.addObjectField(
			fromNode.getOutputName(),
			HierarchyOfDescriptor.FROM_NODE,
			fromNodeBuilder -> buildLevelInfoFields(
				fromNodeBuilder,
				hierarchyEntityType,
				locale,
				fromNode.getEntityFetch().orElse(null),
				fromNode.getStatistics().orElse(null)
			),
			arguments.toArray(ArgumentSupplier[]::new)
		);
	}

	private void buildFromRootFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                 @Nullable Locale locale,
	                                 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchyFromRoot fromRoot) {
		final List<ArgumentSupplier> arguments = new ArrayList<>(2);
		if (fromRoot.getStopAt().isPresent()) {
			arguments.add(getStopAtArgument(fromRoot.getStopAt().get(), hierarchyDataLocator));
		}
		if (fromRoot.getStatistics().isPresent() && fromRoot.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER) {
			arguments.add(getStatisticsArgument(fromRoot.getStatistics().get()));
		}

		hierarchyOfBuilder.addObjectField(
			fromRoot.getOutputName(),
			HierarchyOfDescriptor.FROM_ROOT,
			fromRootBuilder -> buildLevelInfoFields(
				fromRootBuilder,
				hierarchyEntityType,
				locale,
				fromRoot.getEntityFetch().orElse(null),
				fromRoot.getStatistics().orElse(null)
			),
			arguments.toArray(ArgumentSupplier[]::new)
		);
	}

	private void buildParentsFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
	                                @Nullable Locale locale,
	                                @Nonnull String hierarchyEntityType,
	                                @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                @Nonnull HierarchyParents parents) {
		final List<ArgumentSupplier> arguments = new ArrayList<>(3);
		if (parents.getSiblings().isPresent()) {
			final HierarchySiblings siblings = parents.getSiblings().get();
			Assert.isPremiseValid(
				siblings.getStatistics().isEmpty() &&
					siblings.getEntityFetch().isEmpty(),
				"Custom statistics and entityFetch for siblings inside parents is not supported in GraphQL"
			);

			final ObjectNode siblingsArgument = this.jsonNodeFactory.objectNode();
			siblings.getStopAt()
				.flatMap(stopAt -> this.requireConstraintToJsonConverter.convert(hierarchyDataLocator, stopAt))
				.ifPresent(constraint -> siblingsArgument.putIfAbsent(HierarchyRequireHeaderDescriptor.STOP_AT.name(), constraint.value()));

			arguments.add(
				(offset, multipleArguments) -> new Argument(
					HierarchyParentsHeaderDescriptor.SIBLINGS,
					offset,
					multipleArguments,
					siblingsArgument
				)
			);
		}
		if (parents.getStopAt().isPresent()) {
			arguments.add(getStopAtArgument(parents.getStopAt().get(), hierarchyDataLocator));
		}
		if (parents.getStatistics().isPresent() && parents.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER) {
			arguments.add(getStatisticsArgument(parents.getStatistics().get()));
		}

		hierarchyOfBuilder.addObjectField(
			parents.getOutputName(),
			HierarchyOfDescriptor.PARENTS,
			parentsBuilder -> buildLevelInfoFields(
				parentsBuilder,
				hierarchyEntityType,
				locale,
				parents.getEntityFetch().orElse(null),
				parents.getStatistics().orElse(null)
			),
			arguments.toArray(ArgumentSupplier[]::new)
		);
	}

	private void buildSiblingsFields(@Nonnull GraphQLOutputFieldsBuilder hierarchyOfBuilder,
									 @Nullable Locale locale,
	                                 @Nonnull String hierarchyEntityType,
	                                 @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                 @Nonnull HierarchySiblings siblings) {
		final List<ArgumentSupplier> arguments = new ArrayList<>(2);
		if (siblings.getStopAt().isPresent()) {
			arguments.add(getStopAtArgument(siblings.getStopAt().get(), hierarchyDataLocator));
		}
		if (siblings.getStatistics().isPresent() && siblings.getStatistics().get().getStatisticsBase() != StatisticsBase.WITHOUT_USER_FILTER) {
			arguments.add(getStatisticsArgument(siblings.getStatistics().get()));
		}

		hierarchyOfBuilder.addObjectField(
			siblings.getOutputName(),
			HierarchyOfDescriptor.SIBLINGS,
			siblingsBuilder -> buildLevelInfoFields(
				siblingsBuilder,
				hierarchyEntityType,
				locale,
				siblings.getEntityFetch().orElse(null),
				siblings.getStatistics().orElse(null)
			),
			arguments.toArray(ArgumentSupplier[]::new)
		);
	}

	@Nonnull
	private ArgumentSupplier getStopAtArgument(@Nonnull HierarchyStopAt stopAt,
	                                           @Nonnull HierarchyDataLocator hierarchyDataLocator) {
		return (offset, multipleArguments) -> new Argument(
			HierarchyRequireHeaderDescriptor.STOP_AT,
			offset,
			multipleArguments,
			convertRequireConstraint(hierarchyDataLocator, stopAt).orElse(null)
		);
	}

	@Nonnull
	private ArgumentSupplier getStatisticsArgument(@Nonnull HierarchyStatistics statistics) {
		return (offset, multipleArguments) -> new Argument(
			HierarchyRequireHeaderDescriptor.STATISTICS_BASE,
			offset,
			multipleArguments,
			statistics.getStatisticsBase().name()
		);
	}

	private void buildLevelInfoFields(@Nonnull GraphQLOutputFieldsBuilder levelInfoBuilder,
									  @Nonnull String entityType,
                                      @Nullable Locale locale,
	                                  @Nullable EntityFetch entityFetch,
	                                  @Nullable HierarchyStatistics statistics) {
		levelInfoBuilder
			.addPrimitiveField(LevelInfoDescriptor.LEVEL)
			.addObjectField(LevelInfoDescriptor.ENTITY, entityBuilder ->
				this.entityFetchConverter.convert(entityBuilder, entityType, locale, entityFetch))
			.addPrimitiveField(LevelInfoDescriptor.REQUESTED);

		if (statistics != null) {
			if (statistics.getStatisticsType().contains(StatisticsType.QUERIED_ENTITY_COUNT)) {
				levelInfoBuilder.addPrimitiveField(LevelInfoDescriptor.QUERIED_ENTITY_COUNT);
			}
			if (statistics.getStatisticsType().contains(StatisticsType.CHILDREN_COUNT)) {
				levelInfoBuilder.addPrimitiveField(LevelInfoDescriptor.CHILDREN_COUNT);
			}
		}
	}
}
