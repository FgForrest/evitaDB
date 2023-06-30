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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.parser.EnumWrapper;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.*;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.api.query.require.*;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
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

	protected final EvitaQLClassifierTokenVisitor classifierTokenVisitor = new EvitaQLClassifierTokenVisitor();
	protected final EvitaQLValueTokenVisitor queryPriceModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(QueryPriceMode.class);
	protected final EvitaQLValueTokenVisitor facetStatisticsDepthValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(FacetStatisticsDepth.class);
	protected final EvitaQLValueTokenVisitor intValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		Byte.class,
		Short.class,
		Integer.class,
		Long.class
	);
	protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);
	protected final EvitaQLValueTokenVisitor localeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Locale.class);
	protected final EvitaQLValueTokenVisitor emptyHierarchicalEntityBehaviourValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(EmptyHierarchicalEntityBehaviour.class);
	protected final EvitaQLValueTokenVisitor statisticsArgValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(StatisticsBase.class, StatisticsType.class);
	protected final EvitaQLValueTokenVisitor priceContentModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(PriceContentMode.class);

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
						.map(c -> visitChildConstraint(c, EntityContentRequire.class))
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
						.map(c -> visitChildConstraint(c, EntityContentRequire.class))
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
			() -> new PriceContent(
				ctx.args.contentMode.accept(priceContentModeValueTokenVisitor).asEnum(PriceContentMode.class),
				ctx.args.priceLists != null
					? ctx.args.priceLists.accept(stringValueTokenVisitor).asStringArray()
					: new String[0]
			)
		);
	}

	@Nullable
	@Override
	public RequireConstraint visitPriceContentAllConstraint(@Nonnull PriceContentAllConstraintContext ctx) {
		return parse(ctx, () -> new PriceContent(PriceContentMode.ALL));
	}

	@Override
	public RequireConstraint visitPriceContentRespectingFilterConstraint(@Nonnull PriceContentRespectingFilterConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new PriceContent(PriceContentMode.RESPECTING_FILTER);
				}
				return new PriceContent(
					PriceContentMode.RESPECTING_FILTER,
					ctx.args.values != null
						? ctx.args.values.accept(stringValueTokenVisitor).asStringArray()
						: new String[0]
				);
			}
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
					final EntityRequire require = visitChildConstraint(ctx.args.requirement, EntityRequire.class);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(entityFetch, null);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(null, entityGroupFetch);
					} else {
						throw new EvitaInternalError("Should never happen!");
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
	public RequireConstraint visitSingleRefReferenceContent1Constraint(SingleRefReferenceContent1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();
				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, null, null, null, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, null, null, null, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, null, null, null, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent2Constraint(SingleRefReferenceContent2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final EntityFetch facetEntityRequirement = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch groupEntityRequirement = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, null, null, null, facetEntityRequirement, groupEntityRequirement);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent3Constraint(SingleRefReferenceContent3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, filterBy, null, null, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, filterBy, null, null, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, filterBy, null, null, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent4Constraint(SingleRefReferenceContent4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final EntityFetch requirement1 = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, filterBy, null, null, requirement1, requirement2);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent5Constraint(SingleRefReferenceContent5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, null, orderBy, null, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, null, orderBy, null, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, null, orderBy, null, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent6Constraint(SingleRefReferenceContent6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final EntityFetch facetEntityRequirement = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch groupEntityRequirement = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, null, orderBy, null, facetEntityRequirement, groupEntityRequirement);
			}
		);
	}


	@Override
	public RequireConstraint visitSingleRefReferenceContent7Constraint(SingleRefReferenceContent7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, filterBy, orderBy, null, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, filterBy, orderBy, null, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, filterBy, orderBy, null, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent8Constraint(SingleRefReferenceContent8ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final EntityFetch facetEntityRequirements = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch groupEntityRequirements = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, filterBy, orderBy, null, facetEntityRequirements, groupEntityRequirements);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes1Constraint(SingleRefReferenceContentWithAttributes1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, null, null, attributeContent, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, null, null, attributeContent, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, null, null, attributeContent, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes2Constraint(SingleRefReferenceContentWithAttributes2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityFetch entityFetch = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch entityGroupFetch = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, null, null, attributeContent, entityFetch, entityGroupFetch);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes3Constraint(SingleRefReferenceContentWithAttributes3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, filterBy, null, attributeContent, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, filterBy, null, attributeContent, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, filterBy, null, attributeContent, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes4Constraint(SingleRefReferenceContentWithAttributes4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityFetch entityFetch = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch entityGroupFetch = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, filterBy, null, attributeContent, entityFetch, entityGroupFetch);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes5Constraint(SingleRefReferenceContentWithAttributes5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, null, orderBy, attributeContent, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, null, orderBy, attributeContent, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, null, orderBy, attributeContent, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes6Constraint(SingleRefReferenceContentWithAttributes6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityFetch entityFetch = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch entityGroupFetch = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, null, orderBy, attributeContent, entityFetch, entityGroupFetch);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes7Constraint(SingleRefReferenceContentWithAttributes7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityRequire requirement = Optional.ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityRequire.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(classifier, filterBy, orderBy, attributeContent, null, null);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(classifier, filterBy, orderBy, attributeContent, entityFetch, null);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(classifier, filterBy, orderBy, attributeContent, null, entityGroupFetch);
				} else {
					throw new EvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes8Constraint(SingleRefReferenceContentWithAttributes8ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				final FilterBy filterBy = visitChildConstraint(filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final EntityFetch entityFetch = visitChildConstraint(ctx.args.facetEntityRequirement, EntityFetch.class);
				final EntityGroupFetch entityGroupFetch = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class);

				return new ReferenceContent(classifier, filterBy, orderBy, attributeContent, entityFetch, entityGroupFetch);
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
					final EntityRequire require = visitChildConstraint(ctx.args.requirement, EntityRequire.class);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(classifiers, entityFetch, null);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(classifiers, null, entityGroupFetch);
					} else {
						throw new EvitaInternalError("Should never happen!");
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
	public RequireConstraint visitFacetSummary1Constraint(FacetSummary1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new FacetSummary();
				}
				return new FacetSummary(
					ctx.args.depth.accept(facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummary2Constraint(FacetSummary2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final FacetStatisticsDepth depth = ctx.args.depth.accept(facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class);

				final FilterConstraint filterBy1 = Optional.ofNullable(ctx.args.filter)
					.map(filter -> filter.filterBy)
					.map(c -> (FilterConstraint) visitChildConstraint(filterConstraintVisitor, c, FilterBy.class, FilterGroupBy.class))
					.orElse(null);
				final FilterGroupBy filterBy2 = Optional.ofNullable(ctx.args.filter)
					.flatMap(filter -> Optional.ofNullable(filter.filterGroupBy))
					.map(c -> visitChildConstraint(filterConstraintVisitor, c, FilterGroupBy.class))
					.orElse(null);
				if (filterBy2 != null) {
					Assert.isTrue(
						filterBy1 instanceof FilterBy,
						() -> new EvitaQLInvalidQueryError(ctx, "Cannot pass 2 `filterGroupBy` constraints.")
					);
				}

				final OrderConstraint orderBy1 = Optional.ofNullable(ctx.args.order)
					.map(order -> order.orderBy)
					.map(c -> (OrderConstraint) visitChildConstraint(orderConstraintVisitor, c, OrderBy.class, OrderGroupBy.class))
					.orElse(null);
				final OrderGroupBy orderBy2 = Optional.ofNullable(ctx.args.order)
					.flatMap(order -> Optional.ofNullable(order.orderGroupBy))
					.map(c -> visitChildConstraint(orderConstraintVisitor, c, OrderGroupBy.class))
					.orElse(null);
				if (orderBy2 != null) {
					Assert.isTrue(
						orderBy1 instanceof OrderBy,
						() -> new EvitaQLInvalidQueryError(ctx, "Cannot pass 2 `orderGroupBy` constraints.")
					);
				}

				return new FacetSummary(
					depth,
					filterBy1 instanceof FilterBy f ? f : null,
					filterBy1 instanceof FilterGroupBy f ? f : filterBy2,
					orderBy1 instanceof OrderBy o ? o : null,
					orderBy1 instanceof OrderGroupBy o ? o : orderBy2,
					parseFacetSummaryRequirementsArgs(ctx.args.requirements)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryOfReference1Constraint(@Nonnull FacetSummaryOfReference1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetSummaryOfReference(
				ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier()
			)
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryOfReference2Constraint(@Nonnull FacetSummaryOfReference2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String referenceName = ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier();
				final FacetStatisticsDepth depth = ctx.args.depth.accept(facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class);

				final FilterConstraint filterBy1 = Optional.ofNullable(ctx.args.filter)
					.map(filter -> filter.filterBy)
					.map(c -> (FilterConstraint) visitChildConstraint(filterConstraintVisitor, c, FilterBy.class, FilterGroupBy.class))
					.orElse(null);
				final FilterGroupBy filterBy2 = Optional.ofNullable(ctx.args.filter)
					.flatMap(filter -> Optional.ofNullable(filter.filterGroupBy))
					.map(c -> visitChildConstraint(filterConstraintVisitor, c, FilterGroupBy.class))
					.orElse(null);
				if (filterBy2 != null) {
					Assert.isTrue(
						filterBy1 instanceof FilterBy,
						() -> new EvitaQLInvalidQueryError(ctx, "Cannot pass 2 `filterGroupBy` constraints.")
					);
				}

				final OrderConstraint orderBy1 = Optional.ofNullable(ctx.args.order)
					.map(order -> order.orderBy)
					.map(c -> (OrderConstraint) visitChildConstraint(orderConstraintVisitor, c, OrderBy.class, OrderGroupBy.class))
					.orElse(null);
				final OrderGroupBy orderBy2 = Optional.ofNullable(ctx.args.order)
					.flatMap(order -> Optional.ofNullable(order.orderGroupBy))
					.map(c -> visitChildConstraint(orderConstraintVisitor, c, OrderGroupBy.class))
					.orElse(null);
				if (orderBy2 != null) {
					Assert.isTrue(
						orderBy1 instanceof OrderBy,
						() -> new EvitaQLInvalidQueryError(ctx, "Cannot pass 2 `orderGroupBy` constraints.")
					);
				}

				return new FacetSummaryOfReference(
					referenceName,
					depth,
					filterBy1 instanceof FilterBy f ? f : null,
					filterBy1 instanceof FilterGroupBy f ? f : filterBy2,
					orderBy1 instanceof OrderBy o ? o : null,
					orderBy1 instanceof OrderGroupBy o ? o : orderBy2,
					parseFacetSummaryRequirementsArgs(ctx.args.requirements)
				);
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

				// due to the varargs of any value in QL, we don't know which enum is where, on top of that it can be
				// enum directly or just wrapper
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
				if (firstSettings instanceof EnumWrapper enumWrapper && enumWrapper.canBeMappedTo(StatisticsBase.class)) {
					return new HierarchyStatistics(
						castArgument(ctx, settings.pop(), EnumWrapper.class)
							.toEnum(StatisticsBase.class),
						settings.stream()
							.map(it -> {
								if (it instanceof EnumWrapper statisticsType) {
									return statisticsType.toEnum(StatisticsType.class);
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
	public RequireConstraint visitBasicHierarchyOfSelfConstraint(@Nonnull BasicHierarchyOfSelfConstraintContext ctx) {
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
	public RequireConstraint visitFullHierarchyOfSelfConstraint(@Nonnull FullHierarchyOfSelfConstraintContext ctx) {
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
	public RequireConstraint visitBasicHierarchyOfReferenceConstraint(@Nonnull BasicHierarchyOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfReference(
				// todo lho support for multiple reference names
				ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier(),
				EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitBasicHierarchyOfReferenceWithBehaviourConstraint(@Nonnull BasicHierarchyOfReferenceWithBehaviourConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfReference(
				// todo lho support for multiple reference names
				ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier(),
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
	public RequireConstraint visitFullHierarchyOfReferenceConstraint(@Nonnull FullHierarchyOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfReference(
				// todo lho support for multiple reference names
				ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier(),
				EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
				visitChildConstraint(orderConstraintVisitor, ctx.args.orderBy, OrderBy.class),
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitFullHierarchyOfReferenceWithBehaviourConstraint(@Nonnull FullHierarchyOfReferenceWithBehaviourConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyOfReference(
				// todo lho support for multiple reference names
				ctx.args.referenceName.accept(classifierTokenVisitor).asSingleClassifier(),
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
	private EntityRequire[] parseFacetSummaryRequirementsArgs(@Nullable FacetSummaryRequirementsArgsContext ctx) {
		if (ctx == null) {
			return new EntityRequire[0];
		}
		if (ctx.requirement != null) {
			final EntityRequire requirement = visitChildConstraint(ctx.requirement, EntityRequire.class);
			if (requirement instanceof final EntityFetch facetEntityRequirement) {
				return new EntityRequire[] { facetEntityRequirement };
			} else if (requirement instanceof final EntityGroupFetch groupEntityRequirement) {
				return new EntityRequire[] { groupEntityRequirement };
			} else {
				throw new EvitaQLInvalidQueryError(ctx, "Unsupported requirement constraint.");
			}
		}
		return new EntityRequire[] {
			visitChildConstraint(ctx.facetEntityRequirement, EntityFetch.class),
			visitChildConstraint(ctx.groupEntityRequirement, EntityGroupFetch.class)
		};
	}
}
