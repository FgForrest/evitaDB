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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.parser.EnumWrapper;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.*;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.api.query.require.*;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all require type constraints
 * ({@link RequireConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 * @see EvitaQLConstraintVisitor
 */
public class EvitaQLRequireConstraintVisitor extends EvitaQLBaseConstraintVisitor<RequireConstraint> {

	private static final String ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE = "Only `entityFetch` and `entityGroupFetch` constraints are supported.";

	protected final EvitaQLClassifierTokenVisitor classifierTokenVisitor = new EvitaQLClassifierTokenVisitor();
	protected final EvitaQLValueTokenVisitor queryPriceModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(QueryPriceMode.class);
	protected final EvitaQLValueTokenVisitor facetStatisticsDepthValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(FacetStatisticsDepth.class);
	protected final EvitaQLValueTokenVisitor intValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		Byte.class,
		Short.class,
		Integer.class,
		Long.class
	);
	protected final EvitaQLValueTokenVisitor localeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Locale.class);
	protected final EvitaQLValueTokenVisitor emptyHierarchicalEntityBehaviourValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(EmptyHierarchicalEntityBehaviour.class);
	protected final EvitaQLValueTokenVisitor statisticsArgValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(StatisticsBase.class, StatisticsType.class);

	protected final EvitaQLValueTokenVisitor priceContentArgValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Enum.class, PriceContentMode.class);

	protected final EvitaQLFilterConstraintVisitor filterConstraintVisitor = new EvitaQLFilterConstraintVisitor();
	protected final EvitaQLOrderConstraintVisitor orderConstraintVisitor = new EvitaQLOrderConstraintVisitor();


	@Override
	public RequireConstraint visitRequireContainerConstraint(@Nonnull RequireContainerConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new Require();
				}
				return new Require(
					ctx.args.requirements
						.stream()
						.map(hc -> visitChildConstraint(hc, RequireConstraint.class))
						.toArray(RequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitPageConstraint(@Nonnull PageConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Page(
				ctx.args.pageNumber.accept(intValueTokenVisitor).asInt(),
				ctx.args.pageSize.accept(intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitStripConstraint(@Nonnull StripConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Strip(
				ctx.args.offset.accept(intValueTokenVisitor).asInt(),
				ctx.args.limit.accept(intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitEntityFetchConstraint(@Nonnull EntityFetchConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new EntityFetch();
				}
				return new EntityFetch(
					ctx.args.requirements
						.stream()
						.map(this::visitChildEntityContentRequire)
						.toArray(EntityContentRequire[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitEntityGroupFetchConstraint(@Nonnull EntityGroupFetchConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new EntityGroupFetch();
				}
				return new EntityGroupFetch(
					ctx.args.requirements
						.stream()
						.map(this::visitChildEntityContentRequire)
						.toArray(EntityContentRequire[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitAttributeContentConstraint(@Nonnull AttributeContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new AttributeContent();
				}
				return new AttributeContent(
					ctx.args.classifiers.accept(classifierTokenVisitor).asClassifierArray()
				);
			}
		);
	}

	@Override
	public RequireConstraint visitPriceContentConstraint(@Nonnull PriceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new PriceContent();
				}
				PriceContentMode contentMode = null;
				List<String> priceLists = new LinkedList<>();
				final Serializable[] values = ctx.args.values
					.accept(priceContentArgValueTokenVisitor)
					.asSerializableArray();
				for (int i = 0; i < values.length; i++) {
					final Serializable value = values[i];
					if (i == 0) {
						if (value instanceof PriceContentMode mode) {
							contentMode = mode;
							continue;
						} else if (value instanceof EnumWrapper enumWrapper) {
							contentMode = enumWrapper.toEnum(PriceContentMode.class);
							continue;
						}
					}
					Assert.isTrue(
						value instanceof String,
						() -> new EvitaQLInvalidQueryError(ctx, "Values of `priceContent` constraint must be of type string.")
					);
					priceLists.add((String) value);
				}
				if (contentMode == null) {
					return new PriceContent(priceLists.toArray(String[]::new));
				} else {
					return new PriceContent(contentMode, priceLists.toArray(String[]::new));
				}
			}
		);
	}

	@Override
	public RequireConstraint visitPriceContentAllConstraint(@Nonnull PriceContentAllConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceContent(PriceContentMode.ALL)
		);
	}

	@Override
	public RequireConstraint visitAssociatedDataContentConstraint(@Nonnull AssociatedDataContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new AssociatedDataContent();
				}
				return new AssociatedDataContent(
					ctx.args.classifiers.accept(classifierTokenVisitor).asClassifierArray()
				);
			}
		);
	}

	@Override
	public RequireConstraint visitAllRefsReferenceContentConstraint(@Nonnull AllRefsReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new ReferenceContent();
				}
				if (ctx.args.requirement != null) {
					final EntityRequire require = visitChildEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(entityFetch);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(entityGroupFetch);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class),
						visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentConstraint(@Nonnull SingleRefReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(classifier);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitChildEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(classifier, entityFetch);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(classifier, entityGroupFetch);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						classifier,
						Optional.ofNullable(ctx.args.facetEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityFetch.class))
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefWithFilterReferenceContentConstraint(@Nonnull SingleRefWithFilterReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = (FilterBy) ctx.args.filterBy.accept(filterConstraintVisitor);
				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(
						classifier,
						filterBy
					);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitChildEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(
							classifier,
							filterBy,
							entityFetch
						);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							classifier,
							filterBy,
							entityGroupFetch
						);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						classifier,
						filterBy,
						Optional.ofNullable(ctx.args.facetEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityFetch.class))
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefWithOrderReferenceContentConstraint(@Nonnull SingleRefWithOrderReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final OrderBy orderBy = (OrderBy) ctx.args.orderBy.accept(orderConstraintVisitor);
				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(
						classifier,
						orderBy
					);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitChildEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(
							classifier,
							orderBy,
							entityFetch
						);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							classifier,
							orderBy,
							entityGroupFetch
						);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						classifier,
						orderBy,
						Optional.ofNullable(ctx.args.facetEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityFetch.class))
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefWithFilterAndOrderReferenceContentConstraint(@Nonnull SingleRefWithFilterAndOrderReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = (FilterBy) ctx.args.filterBy.accept(filterConstraintVisitor);
				final OrderBy orderBy = (OrderBy) ctx.args.orderBy.accept(orderConstraintVisitor);
				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(
						classifier,
						filterBy,
						orderBy
					);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitChildEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(
							classifier,
							filterBy,
							orderBy,
							entityFetch
						);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							classifier,
							filterBy,
							orderBy,
							entityGroupFetch
						);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						classifier,
						filterBy,
						orderBy,
						Optional.ofNullable(ctx.args.facetEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityFetch.class))
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitMultipleRefsReferenceContentConstraint(@Nonnull MultipleRefsReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String[] classifiers = ctx.args.classifiers
						.accept(classifierTokenVisitor)
						.asClassifierArray();

				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(classifiers);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitChildEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(classifiers, entityFetch);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(classifiers, entityGroupFetch);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						classifiers,
						Optional.ofNullable(ctx.args.facetEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityFetch.class))
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitEmptyHierarchyContentConstraint(@Nonnull EmptyHierarchyContentConstraintContext ctx) {
		return parse(ctx, HierarchyContent::new);
	}

	@Override
	public RequireConstraint visitSingleRequireHierarchyContentConstraint(@Nonnull SingleRequireHierarchyContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final RequireConstraint requirement = visitChildConstraint(ctx.args.requirement, RequireConstraint.class);
				if (requirement instanceof final HierarchyStopAt stopAt) {
					return new HierarchyContent(stopAt);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new HierarchyContent(entityFetch);
				} else {
					throw new EvitaQLInvalidQueryError(ctx, "Unsupported requirement constraint. Only `stopAt` and `entityFetch` are supported.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitAllRequiresHierarchyContentConstraint(@Nonnull AllRequiresHierarchyContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final HierarchyStopAt stopAt = visitChildConstraint(ctx.args.stopAt, HierarchyStopAt.class);
				final EntityFetch entityFetch = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class);
				return new HierarchyContent(stopAt, entityFetch);
			}
		);
	}

	@Override
	public RequireConstraint visitPriceTypeConstraint(@Nonnull PriceTypeConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceType(
				ctx.args.value
					.accept(queryPriceModeValueTokenVisitor)
					.asEnum(QueryPriceMode.class)
			)
		);
	}

	@Override
	public RequireConstraint visitDataInLocalesConstraint(@Nonnull DataInLocalesConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new DataInLocales();
				} else {
					return new DataInLocales(
						ctx.args.values.accept(localeValueTokenVisitor).asLocaleArray()
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryConstraint(@Nonnull FacetSummaryConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new FacetSummary();
				}

				final FacetStatisticsDepth depth = ctx.args.depth
					.accept(facetStatisticsDepthValueTokenVisitor)
					.asEnum(FacetStatisticsDepth.class);

				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new FacetSummary(depth);
				}

				if (ctx.args.requirement != null) {
					final RequireConstraint requirement = visitChildConstraint(ctx.args.requirement, RequireConstraint.class);
					if (requirement instanceof final EntityFetch facetEntityRequirement) {
						return new FacetSummary(depth, facetEntityRequirement);
					} else if (requirement instanceof final EntityGroupFetch groupEntityRequirement) {
						return new FacetSummary(depth, groupEntityRequirement);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, "Unsupported requirement constraint.");
					}
				}

				final EntityFetch facetEntityRequirement = Optional.ofNullable(ctx.args.facetEntityRequirement)
					.map(c -> visitChildConstraint(c, EntityFetch.class))
					.orElse(null);
				final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(ctx.args.groupEntityRequirement)
					.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
					.orElse(null);
				return new FacetSummary(depth, facetEntityRequirement, groupEntityRequirement);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryOfReferenceConstraint(@Nonnull FacetSummaryOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String referenceName = ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier();
				if (ctx.args.depth == null) {
					return new FacetSummaryOfReference(referenceName);
				}

				final FacetStatisticsDepth depth = ctx.args.depth
					.accept(facetStatisticsDepthValueTokenVisitor)
					.asEnum(FacetStatisticsDepth.class);

				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new FacetSummaryOfReference(referenceName, depth);
				}

				if (ctx.args.requirement != null) {
					final RequireConstraint requirement = visitChildConstraint(ctx.args.requirement, RequireConstraint.class);
					if (requirement instanceof final EntityFetch facetEntityRequirement) {
						return new FacetSummaryOfReference(referenceName, depth, facetEntityRequirement);
					} else if (requirement instanceof final EntityGroupFetch groupEntityRequirement) {
						return new FacetSummaryOfReference(referenceName, depth, groupEntityRequirement);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, "Unsupported requirement constraint.");
					}
				}

				final EntityFetch facetEntityRequirement = Optional.ofNullable(ctx.args.facetEntityRequirement)
					.map(c -> visitChildConstraint(c, EntityFetch.class))
					.orElse(null);
				final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(ctx.args.groupEntityRequirement)
					.map(c -> visitChildConstraint(c, EntityGroupFetch.class))
					.orElse(null);
				return new FacetSummaryOfReference(referenceName, depth, facetEntityRequirement, groupEntityRequirement);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsConjunctionConstraint(@Nonnull FacetGroupsConjunctionConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetGroupsConjunction(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				(FilterBy) ctx.args.filterConstraint().accept(filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsDisjunctionConstraint(@Nonnull FacetGroupsDisjunctionConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetGroupsDisjunction(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				(FilterBy) ctx.args.filterConstraint().accept(filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsNegationConstraint(@Nonnull FacetGroupsNegationConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetGroupsNegation(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				(FilterBy) ctx.args.filterConstraint().accept(filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitAttributeHistogramConstraint(@Nonnull AttributeHistogramConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeHistogram(
				ctx.args.value.accept(intValueTokenVisitor).asInt(),
				ctx.args.classifiers.accept(classifierTokenVisitor).asClassifierArray()
			)
		);
	}

	@Override
	public RequireConstraint visitPriceHistogramConstraint(@Nonnull PriceHistogramConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceHistogram(
				ctx.args.value.accept(intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitHierarchyDistanceConstraint(@Nonnull HierarchyDistanceConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyDistance(ctx.args.value.accept(intValueTokenVisitor).asInt())
		);
	}

	@Override
	public RequireConstraint visitHierarchyLevelConstraint(@Nonnull HierarchyLevelConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyLevel(ctx.args.value.accept(intValueTokenVisitor).asInt())
		);
	}

	@Override
	public RequireConstraint visitHierarchyNodeConstraint(@Nonnull HierarchyNodeConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyNode(visitChildConstraint(filterConstraintVisitor, ctx.args.filter, FilterBy.class))
		);
	}

	@Override
	public RequireConstraint visitHierarchyStopAtConstraint(@Nonnull HierarchyStopAtConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyStopAt(visitChildConstraint(ctx.args.requirement, HierarchyStopAtRequireConstraint.class))
		);
	}

	@Override
	public RequireConstraint visitHierarchyStatisticsConstraint(@Nonnull HierarchyStatisticsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new HierarchyStatistics();
				}
				final LinkedList<Serializable> settings = Arrays.stream(ctx.args.settings
					.accept(statisticsArgValueTokenVisitor)
					.asSerializableArray())
					.collect(Collectors.toCollection(LinkedList::new));

				final Serializable firstSettings = settings.peekFirst();
				if (firstSettings instanceof StatisticsBase) {
					return new HierarchyStatistics(
						castArgument(ctx, settings.pop(), StatisticsBase.class),
						settings.stream()
							.map(it -> {
								if (it instanceof EnumWrapper enumWrapper) {
									return enumWrapper.toEnum(StatisticsType.class);
								}
								return castArgument(ctx, it, StatisticsType.class);
							})
							.toArray(StatisticsType[]::new)
					);
				}
				if (isBase(ctx, firstSettings)) {
					return new HierarchyStatistics(
						castArgument(ctx, settings.pop(), EnumWrapper.class)
							.toEnum(StatisticsBase.class),
						settings.stream()
							.map(it -> {
								if (it instanceof EnumWrapper enumWrapper) {
									return enumWrapper.toEnum(StatisticsType.class);
								}
								return castArgument(ctx, it, StatisticsType.class);
							})
							.toArray(StatisticsType[]::new)
					);
				}
				return new HierarchyStatistics(
					StatisticsBase.WITHOUT_USER_FILTER,
					settings.stream()
						.map(it -> {
							if (it instanceof EnumWrapper enumWrapper) {
								return enumWrapper.toEnum(StatisticsType.class);
							}
							return castArgument(ctx, it, StatisticsType.class);
						})
						.toArray(StatisticsType[]::new)
				);
			}
		);
	}

	private boolean isBase(@Nonnull ParserRuleContext ctx, @Nonnull Serializable value) {
		try {
			// we need this hack because parser doesn't know how to differentiate between different enums right now
			final StatisticsBase base = castArgument(ctx, value, EnumWrapper.class)
				.toEnum(StatisticsBase.class);
			return true;
		} catch (EvitaInvalidUsageException ex) {
			return false;
		}
	}

	@Override
	public RequireConstraint visitHierarchyFromRootConstraint(@Nonnull HierarchyFromRootConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(classifierTokenVisitor).asSingleClassifier();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, RequireConstraint.class))
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return new HierarchyFromRoot(outputName);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return new HierarchyFromRoot(
						outputName,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return new HierarchyFromRoot(
					outputName,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyFromNodeConstraint(@Nonnull HierarchyFromNodeConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(classifierTokenVisitor).asSingleClassifier();
				final HierarchyNode node = visitChildConstraint(ctx.args.node, HierarchyNode.class);
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, RequireConstraint.class))
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return new HierarchyFromNode(
						outputName,
						node
					);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return new HierarchyFromNode(
						outputName,
						node,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return new HierarchyFromNode(
					outputName,
					node,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyChildrenConstraint(@Nonnull HierarchyChildrenConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(classifierTokenVisitor).asSingleClassifier();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, RequireConstraint.class))
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return new HierarchyChildren(outputName);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return new HierarchyChildren(
						outputName,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return new HierarchyChildren(
					outputName,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitEmptyHierarchySiblingsConstraint(EmptyHierarchySiblingsConstraintContext ctx) {
		return parse(ctx, () -> new HierarchySiblings(null));
	}

	@Override
	public RequireConstraint visitBasicHierarchySiblingsConstraint(@Nonnull BasicHierarchySiblingsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, RequireConstraint.class))
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return new HierarchySiblings(null);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return new HierarchySiblings(
						null,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return new HierarchySiblings(
					null,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitFullHierarchySiblingsConstraint(@Nonnull FullHierarchySiblingsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(classifierTokenVisitor).asSingleClassifier();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, RequireConstraint.class))
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return new HierarchySiblings(outputName);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return new HierarchySiblings(
						outputName,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return new HierarchySiblings(
					outputName,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyParentsConstraint(@Nonnull HierarchyParentsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(classifierTokenVisitor).asSingleClassifier();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, RequireConstraint.class))
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return new HierarchyParents(outputName);
				}

				if (requirements.peekFirst() instanceof EntityFetch) {
					final EntityFetch entityFetch = (EntityFetch) requirements.pop();

					if (requirements.peekFirst() instanceof HierarchySiblings) {
						final HierarchySiblings siblings = (HierarchySiblings) requirements.pop();

						return new HierarchyParents(
							outputName,
							entityFetch,
							siblings,
							requirements.stream()
								.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
								.toArray(HierarchyOutputRequireConstraint[]::new)
						);
					}

					return new HierarchyParents(
						outputName,
						entityFetch,
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}

				if (requirements.peekFirst() instanceof HierarchySiblings) {
					return new HierarchyParents(
						outputName,
						(HierarchySiblings) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}

				return new HierarchyParents(
					outputName,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitBasicHierarchyOfSelfConstraint(BasicHierarchyOfSelfConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfSelf(
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitFullHierarchyOfSelfConstraint(FullHierarchyOfSelfConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfSelf(
				visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class),
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitBasicHierarchyOfReferenceConstraint(BasicHierarchyOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfReference(
				ctx.args.referenceNames.accept(classifierTokenVisitor).asClassifierArray(),
				ctx.args.emptyHierarchicalEntityBehaviour
					.accept(emptyHierarchicalEntityBehaviourValueTokenVisitor)
					.asEnum(EmptyHierarchicalEntityBehaviour.class),
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitFullHierarchyOfReferenceConstraint(FullHierarchyOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfReference(
				ctx.args.referenceNames.accept(classifierTokenVisitor).asClassifierArray(),
				ctx.args.emptyHierarchicalEntityBehaviour
					.accept(emptyHierarchicalEntityBehaviourValueTokenVisitor)
					.asEnum(EmptyHierarchicalEntityBehaviour.class),
				visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class),
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitQueryTelemetryConstraint(@Nonnull QueryTelemetryConstraintContext ctx) {
		return parse(ctx, QueryTelemetry::new);
	}


	@Nonnull
	private EntityRequire visitChildEntityRequire(@Nonnull RequireConstraintContext arg) {
		return visitChildConstraint(arg, EntityRequire.class);
	}

	@Nonnull
	private EntityContentRequire visitChildEntityContentRequire(@Nonnull RequireConstraintContext arg) {
		return visitChildConstraint(arg, EntityContentRequire.class);
	}
}
