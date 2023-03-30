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
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.SingleRefWithFilterReferenceContentConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.SingleRefWithOrderReferenceContentConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.api.query.require.*;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all require type constraints
 * ({@link RequireConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 * @see EvitaQLConstraintVisitor
 */
public class EvitaQLRequireConstraintVisitor extends EvitaQLBaseVisitor<RequireConstraint> {

	private static final String ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE = "Only `entityFetch` and `entityGroupFetch` constraints are supported.";

	protected final EvitaQLClassifierTokenVisitor classifierTokenVisitor = new EvitaQLClassifierTokenVisitor();
	protected final EvitaQLValueTokenVisitor queryPriceModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(QueryPriceMode.class);
	protected final EvitaQLValueTokenVisitor facetStatisticsDepthValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(FacetStatisticsDepth.class);
	protected final EvitaQLValueTokenVisitor intValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class
	);
	protected final EvitaQLValueTokenVisitor localeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Locale.class);

	protected final EvitaQLValueTokenVisitor priceContentArgValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Enum.class, PriceContentMode.class);

	protected final EvitaQLFilterConstraintVisitor filterConstraintVisitor = new EvitaQLFilterConstraintVisitor();
	protected final EvitaQLOrderConstraintVisitor orderConstraintVisitor = new EvitaQLOrderConstraintVisitor();


	@Override
	public RequireConstraint visitRequireContainerConstraint(@Nonnull EvitaQLParser.RequireContainerConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new Require();
				}
				return new Require(
					ctx.args.requirements
						.stream()
						.map(hc -> hc.accept(this))
						.toArray(RequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitPageConstraint(@Nonnull EvitaQLParser.PageConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Page(
				ctx.args.pageNumber.accept(intValueTokenVisitor).asInt(),
				ctx.args.pageSize.accept(intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitStripConstraint(@Nonnull EvitaQLParser.StripConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Strip(
				ctx.args.offset.accept(intValueTokenVisitor).asInt(),
				ctx.args.limit.accept(intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitEntityFetchConstraint(@Nonnull EvitaQLParser.EntityFetchConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new EntityFetch();
				}
				return new EntityFetch(
					ctx.args.requirements
						.stream()
						.map(this::visitEntityContentRequire)
						.toArray(EntityContentRequire[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitEntityGroupFetchConstraint(@Nonnull EvitaQLParser.EntityGroupFetchConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new EntityGroupFetch();
				}
				return new EntityGroupFetch(
					ctx.args.requirements
						.stream()
						.map(this::visitEntityContentRequire)
						.toArray(EntityContentRequire[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitAttributeContentConstraint(@Nonnull EvitaQLParser.AttributeContentConstraintContext ctx) {
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
	public RequireConstraint visitPriceContentConstraint(@Nonnull EvitaQLParser.PriceContentConstraintContext ctx) {
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
	public RequireConstraint visitPriceContentAllConstraint(@Nonnull EvitaQLParser.PriceContentAllConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceContent(PriceContentMode.ALL)
		);
	}

	@Override
	public RequireConstraint visitAssociatedDataContentConstraint(@Nonnull EvitaQLParser.AssociatedDataContentConstraintContext ctx) {
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
	public RequireConstraint visitAllRefsReferenceContentConstraint(@Nonnull EvitaQLParser.AllRefsReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new ReferenceContent();
				}
				if (ctx.args.requirement != null) {
					final EntityRequire require = visitInnerEntityRequire(ctx.args.requirement);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(entityFetch);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(entityGroupFetch);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE);
					}
				} else {
					return new ReferenceContent(
						visitInnerEntityFetch(ctx.args.facetEntityRequirement),
						visitInnerEntityGroupFetch(ctx.args.groupEntityRequirement)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentConstraint(@Nonnull EvitaQLParser.SingleRefReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier();

				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(classifier);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitInnerEntityRequire(ctx.args.requirement);
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
							.map(this::visitInnerEntityFetch)
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(this::visitInnerEntityGroupFetch)
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefWithFilterReferenceContentConstraint(SingleRefWithFilterReferenceContentConstraintContext ctx) {
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
					final EntityRequire require = visitInnerEntityRequire(ctx.args.requirement);
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
							.map(this::visitInnerEntityFetch)
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(this::visitInnerEntityGroupFetch)
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefWithOrderReferenceContentConstraint(SingleRefWithOrderReferenceContentConstraintContext ctx) {
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
					final EntityRequire require = visitInnerEntityRequire(ctx.args.requirement);
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
							.map(this::visitInnerEntityFetch)
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(this::visitInnerEntityGroupFetch)
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefWithFilterAndOrderReferenceContentConstraint(SingleRefWithFilterAndOrderReferenceContentConstraintContext ctx) {
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
					final EntityRequire require = visitInnerEntityRequire(ctx.args.requirement);
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
							.map(this::visitInnerEntityFetch)
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(this::visitInnerEntityGroupFetch)
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitMultipleRefsReferenceContentConstraint(@Nonnull EvitaQLParser.MultipleRefsReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String[] classifiers = ctx.args.classifiers
						.accept(classifierTokenVisitor)
						.asClassifierArray();

				if (ctx.args.requirement == null && ctx.args.facetEntityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(classifiers);
				} else if (ctx.args.requirement != null) {
					final EntityRequire require = visitInnerEntityRequire(ctx.args.requirement);
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
							.map(this::visitInnerEntityFetch)
							.orElse(null),
						Optional.ofNullable(ctx.args.groupEntityRequirement)
							.map(this::visitInnerEntityGroupFetch)
							.orElse(null)
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitPriceTypeConstraint(@Nonnull EvitaQLParser.PriceTypeConstraintContext ctx) {
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
	public RequireConstraint visitDataInLocalesConstraint(@Nonnull EvitaQLParser.DataInLocalesConstraintContext ctx) {
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
	public RequireConstraint visitHierarchyParentsOfSelfConstraint(@Nonnull EvitaQLParser.HierarchyParentsOfSelfConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new HierarchyParentsOfSelf();
				}
				return new HierarchyParentsOfSelf(visitInnerEntityFetch(ctx.args.requirement));
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyParentsOfReferenceConstraint(@Nonnull EvitaQLParser.HierarchyParentsOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String[] classifiers = ctx.args.classifiers
					.accept(classifierTokenVisitor)
					.asClassifierArray();
				if (ctx.args.requirement == null) {
					return new HierarchyParentsOfReference(classifiers);
				}
				return new HierarchyParentsOfReference(classifiers, visitInnerEntityFetch(ctx.args.requirement));
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryConstraint(@Nonnull EvitaQLParser.FacetSummaryConstraintContext ctx) {
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
					final RequireConstraint requirement = ctx.args.requirement.accept(this);
					if (requirement instanceof final EntityFetch facetEntityRequirement) {
						return new FacetSummary(depth, facetEntityRequirement);
					} else if (requirement instanceof final EntityGroupFetch groupEntityRequirement) {
						return new FacetSummary(depth, groupEntityRequirement);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, "Unsupported requirement constraint.");
					}
				}

				final EntityFetch facetEntityRequirement = Optional.ofNullable(ctx.args.facetEntityRequirement)
					.map(this::visitInnerEntityFetch)
					.orElse(null);
				final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(ctx.args.groupEntityRequirement)
					.map(this::visitInnerEntityGroupFetch)
					.orElse(null);
				return new FacetSummary(depth, facetEntityRequirement, groupEntityRequirement);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryOfReferenceConstraint(@Nonnull EvitaQLParser.FacetSummaryOfReferenceConstraintContext ctx) {
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
					final RequireConstraint requirement = ctx.args.requirement.accept(this);
					if (requirement instanceof final EntityFetch facetEntityRequirement) {
						return new FacetSummaryOfReference(referenceName, depth, facetEntityRequirement);
					} else if (requirement instanceof final EntityGroupFetch groupEntityRequirement) {
						return new FacetSummaryOfReference(referenceName, depth, groupEntityRequirement);
					} else {
						throw new EvitaQLInvalidQueryError(ctx, "Unsupported requirement constraint.");
					}
				}

				final EntityFetch facetEntityRequirement = Optional.ofNullable(ctx.args.facetEntityRequirement)
					.map(this::visitInnerEntityFetch)
					.orElse(null);
				final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(ctx.args.groupEntityRequirement)
					.map(this::visitInnerEntityGroupFetch)
					.orElse(null);
				return new FacetSummaryOfReference(referenceName, depth, facetEntityRequirement, groupEntityRequirement);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsConjunctionConstraint(@Nonnull EvitaQLParser.FacetGroupsConjunctionConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetGroupsConjunction(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.values.accept(intValueTokenVisitor).asIntegerArray()
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsDisjunctionConstraint(@Nonnull EvitaQLParser.FacetGroupsDisjunctionConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetGroupsDisjunction(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.values.accept(intValueTokenVisitor).asIntegerArray()
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsNegationConstraint(@Nonnull EvitaQLParser.FacetGroupsNegationConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetGroupsNegation(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.values.accept(intValueTokenVisitor).asIntegerArray()
			)
		);
	}

	@Override
	public RequireConstraint visitAttributeHistogramConstraint(@Nonnull EvitaQLParser.AttributeHistogramConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeHistogram(
				ctx.args.value.accept(intValueTokenVisitor).asInt(),
				ctx.args.classifiers.accept(classifierTokenVisitor).asClassifierArray()
			)
		);
	}

	@Override
	public RequireConstraint visitPriceHistogramConstraint(@Nonnull EvitaQLParser.PriceHistogramConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceHistogram(
				ctx.args.value.accept(intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitHierarchyStatisticsOfSelfConstraint(@Nonnull EvitaQLParser.HierarchyStatisticsOfSelfConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new HierarchyOfSelf();
				}
				/* TODO LHO - update */
				//return new HierarchyOfSelf(visitInnerEntityFetch(ctx.args.requirement));
				return null;
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyStatisticsOfReferenceConstraint(@Nonnull EvitaQLParser.HierarchyStatisticsOfReferenceConstraintContext ctx) {
		return parse(
			ctx,
			() -> {

				final String[] classifiers = ctx.args.classifiers
					.accept(classifierTokenVisitor)
					.asClassifierArray();
				/* TODO LHO - update */
				/*
				if (ctx.args.requirement == null) {
					return new HierarchyOfReference(classifiers);
				}
				return new HierarchyOfReference(
					classifiers,
					visitInnerEntityFetch(ctx.args.requirement)
				);*/
				return null;
			}
		);
	}

	@Override
	public RequireConstraint visitQueryTelemetryConstraint(@Nonnull EvitaQLParser.QueryTelemetryConstraintContext ctx) {
		return parse(ctx, QueryTelemetry::new);
	}


	@Nonnull
	private EntityFetch visitInnerEntityFetch(@Nonnull EvitaQLParser.RequireConstraintContext arg) {
		final RequireConstraint entityFetch = arg.accept(this);
		Assert.isTrue(
			entityFetch instanceof EntityFetch,
			() -> new EvitaQLInvalidQueryError(arg, "Only `entityFetch` constraint is supported.")
		);
		return (EntityFetch) entityFetch;
	}

	@Nonnull
	private EntityGroupFetch visitInnerEntityGroupFetch(@Nonnull EvitaQLParser.RequireConstraintContext arg) {
		final RequireConstraint entityGroupFetch = arg.accept(this);
		Assert.isTrue(
			entityGroupFetch instanceof EntityGroupFetch,
			() -> new EvitaQLInvalidQueryError(arg, "Only `entityGroupFetch` constraint is supported.")
		);
		return (EntityGroupFetch) entityGroupFetch;
	}

	@Nonnull
	private EntityRequire visitInnerEntityRequire(@Nonnull EvitaQLParser.RequireConstraintContext arg) {
		final RequireConstraint entityRequire = arg.accept(this);
		Assert.isTrue(
			entityRequire instanceof EntityRequire,
			() -> new EvitaQLInvalidQueryError(arg, ONLY_ENTITY_FETCH_CONSTRAINTS_ARE_SUPPORTED_ERROR_MESSAGE)
		);
		return (EntityRequire) entityRequire;
	}

	@Nonnull
	private EntityContentRequire visitEntityContentRequire(@Nonnull EvitaQLParser.RequireConstraintContext arg) {
		final RequireConstraint constraint = arg.accept(this);
		if (!(constraint instanceof EntityContentRequire)) {
			throw new EvitaQLInvalidQueryError(arg, "Child constraint `" + constraint.getName() + "` is not of type `EntityContentRequire`.");
		}
		return (EntityContentRequire) constraint;
	}
}
