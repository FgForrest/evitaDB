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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.ConstraintArguments;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.parser.EnumWrapper;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.*;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.api.query.require.*;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all require type constraints
 * ({@link RequireConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 * @see EvitaQLConstraintVisitor
 */
public class EvitaQLRequireConstraintVisitor extends EvitaQLBaseConstraintVisitor<RequireConstraint> {

	protected final EvitaQLValueTokenVisitor attributeHistogramArgValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class,
		String.class,
		HistogramBehavior.class,
		String[].class,
		Iterable.class
	);
	protected final EvitaQLValueTokenVisitor emptyHierarchicalEntityBehaviourValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(EmptyHierarchicalEntityBehaviour.class);
	protected final EvitaQLValueTokenVisitor facetStatisticsDepthValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(FacetStatisticsDepth.class);
	protected final EvitaQLFilterConstraintVisitor filterConstraintVisitor = new EvitaQLFilterConstraintVisitor();
	protected final EvitaQLValueTokenVisitor hierarchyParentsBehaviourValueTokenVisitor =
		EvitaQLValueTokenVisitor.withAllowedTypes(HierarchyParentsBehaviour.class);
	protected final EvitaQLValueTokenVisitor histogramBehaviorValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(HistogramBehavior.class);
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
	protected final EvitaQLValueTokenVisitor managedReferenceBehaviourValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		ManagedReferencesBehaviour.class,
		String.class,
		String[].class,
		Iterable.class
	);
	protected final EvitaQLOrderConstraintVisitor orderConstraintVisitor = new EvitaQLOrderConstraintVisitor();
	protected final EvitaQLValueTokenVisitor priceContentModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(PriceContentMode.class);
	protected final EvitaQLValueTokenVisitor facetGroupRelationLevelValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(FacetGroupRelationLevel.class);
	protected final EvitaQLValueTokenVisitor facetRelationTypeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(FacetRelationType.class);
	protected final EvitaQLValueTokenVisitor queryPriceModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(QueryPriceMode.class);
	protected final EvitaQLValueTokenVisitor queryTelemetryContentValueTokenVisitor =
		EvitaQLValueTokenVisitor.withAllowedTypes(QueryTelemetryContent.class);
	protected final EvitaQLValueTokenVisitor statisticsArgValueTokenVisitor =
		EvitaQLValueTokenVisitor.withAllowedTypes(
			StatisticsBase.class, StatisticsType.class, StatisticsType[].class, Iterable.class
		);
	protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);
	protected final EvitaQLValueTokenVisitor stringValueListTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		String.class,
		String[].class,
		Iterable.class
	);
	protected final EvitaQLValueTokenVisitor scopeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(Scope.class);

	@Nonnull
	private static ManagedReferencesBehaviour toManagedReferencesBehaviour(@Nonnull Serializable arg) {
		if (arg instanceof ManagedReferencesBehaviour theEnum) {
			return theEnum;
		} else if (arg instanceof EnumWrapper theEnumWrapper && theEnumWrapper.canBeMappedTo(ManagedReferencesBehaviour.class)) {
			return theEnumWrapper.toEnum(ManagedReferencesBehaviour.class);
		} else {
			// return default
			return ManagedReferencesBehaviour.ANY;
		}
	}

	@Override
	public RequireConstraint visitRequireContainerConstraint(RequireContainerConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.require();
				}
				return QueryConstraints.require(
					ctx.args.requirements
						.stream()
						.map(hc -> visitChildConstraint(hc, RequireConstraint.class))
						.toArray(RequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitPageConstraint(PageConstraintContext ctx) {
		final Integer pageNumber = ctx.args.pageNumber.accept(this.intValueTokenVisitor).asInt();
		final Integer pageSize = ctx.args.pageSize.accept(this.intValueTokenVisitor).asInt();
		final Spacing spacing = ctx.args.requireConstraint() == null ?
			null :
			parseSpacing(ctx);
		return parse(
			ctx,
			() -> QueryConstraints.page(
				pageNumber,
				pageSize,
				spacing
			)
		);
	}

	@Override
	public RequireConstraint visitSpacingConstraint(SpacingConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.spacing(
				ctx.args.constraints.stream()
					.map(it -> visitChildConstraint(it, SpacingGap.class))
					.toArray(SpacingGap[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitGapConstraint(GapConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.gap(
				ctx.args.size.accept(this.intValueTokenVisitor).asRequiredInt(),
				ctx.args.expression.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public RequireConstraint visitStripConstraint(StripConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.strip(
				ctx.args.offset.accept(this.intValueTokenVisitor).asInt(),
				ctx.args.limit.accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public RequireConstraint visitEntityFetchConstraint(EntityFetchConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.entityFetch();
				}
				return QueryConstraints.entityFetch(
					ctx.args.requirements
						.stream()
						.map(c -> visitChildConstraint(c, EntityContentRequire.class))
						.toArray(EntityContentRequire[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitEntityGroupFetchConstraint(EntityGroupFetchConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.entityGroupFetch();
				}
				return QueryConstraints.entityGroupFetch(
					ctx.args.requirements
						.stream()
						.map(c -> visitChildConstraint(c, EntityContentRequire.class))
						.toArray(EntityContentRequire[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitAttributeContentConstraint(AttributeContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.attributeContent();
				}
				return QueryConstraints.attributeContent(
					ctx.args.classifiers.accept(this.stringValueListTokenVisitor).asStringArray()
				);
			}
		);
	}

	@Override
	public RequireConstraint visitPriceContentConstraint(PriceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.priceContent(
				ctx.args.contentMode.accept(this.priceContentModeValueTokenVisitor).asEnum(PriceContentMode.class),
				ctx.args.priceLists != null
					? ctx.args.priceLists.accept(this.stringValueListTokenVisitor).asStringArray()
					: new String[0]
			)
		);
	}

	@Nullable
	@Override
	public RequireConstraint visitPriceContentAllConstraint(PriceContentAllConstraintContext ctx) {
		return parse(ctx, () -> QueryConstraints.priceContent(PriceContentMode.ALL));
	}

	@Override
	public RequireConstraint visitPriceContentRespectingFilterConstraint(PriceContentRespectingFilterConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.priceContent(PriceContentMode.RESPECTING_FILTER);
				}
				return QueryConstraints.priceContent(
					PriceContentMode.RESPECTING_FILTER,
					ctx.args.values != null
						? ctx.args.values.accept(this.stringValueListTokenVisitor).asStringArray()
						: new String[0]
				);
			}
		);
	}

	@Override
	public RequireConstraint visitAssociatedDataContentConstraint(AssociatedDataContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.associatedDataContent();
				}
				return QueryConstraints.associatedDataContent(
					ctx.args.classifiers.accept(this.stringValueListTokenVisitor).asStringArray()
				);
			}
		);
	}

	@Override
	public RequireConstraint visitAllRefsReferenceContentConstraint(AllRefsReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new ReferenceContent();
				}
				final ManagedReferencesBehaviour managedReferencesBehaviour = getManagedReferencesBehaviourAsEnum(
					ctx.args.managedReferencesBehaviour
				);
				if (ctx.args.requirement != null) {
					final RequireConstraint require = visitChildConstraint(ctx.args.requirement, EntityFetchRequire.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(
							managedReferencesBehaviour, entityFetch, null
						);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							managedReferencesBehaviour, null, entityGroupFetch
						);
					} else if (require instanceof final ChunkingRequireConstraint chunk) {
						return new ReferenceContent(
							managedReferencesBehaviour, (EntityFetch) null, null, chunk
						);
					} else {
						throw new GenericEvitaInternalError("Should never happen!");
					}
				} else {
					final RequireConstraint requirement1 = ctx.args.entityRequirement == null ? null : visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
					final RequireConstraint requirement2 = ctx.args.groupEntityRequirement == null ? null : visitChildConstraint(ctx.args.groupEntityRequirement, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
					if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							managedReferencesBehaviour, entityFetch, entityGroupFetch
						);
					} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
						return new ReferenceContent(
							managedReferencesBehaviour, entityFetch, null, chunk
						);
					} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
						return new ReferenceContent(
							managedReferencesBehaviour, null, entityGroupFetch, chunk
						);
					} else {
						return new ReferenceContent(managedReferencesBehaviour);
					}
				}
			}
		);
	}

	@Override
	public RequireConstraint visitMultipleRefsReferenceContentConstraint(MultipleRefsReferenceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String[] referenceNames = firstArg instanceof String firstArgAsString ?
					ArrayUtils.mergeArrays(
						new String[]{firstArgAsString},
						ctx.args.classifiers
							.accept(this.stringValueListTokenVisitor)
							.asStringArray()
					) :
					ctx.args.classifiers
						.accept(this.stringValueListTokenVisitor)
						.asStringArray();
				ConstraintArguments.requireNonNullItems(referenceNames, "referenceNames");

				if (ctx.args.requirement == null && ctx.args.entityRequirement == null && ctx.args.groupEntityRequirement == null) {
					return new ReferenceContent(managedReferencesBehaviour, referenceNames);
				} else if (ctx.args.requirement != null) {
					final RequireConstraint require = visitChildConstraint(ctx.args.requirement, EntityFetchRequire.class, ChunkingRequireConstraint.class, ChunkingRequireConstraint.class);
					if (require instanceof final EntityFetch entityFetch) {
						return new ReferenceContent(
							managedReferencesBehaviour, referenceNames, entityFetch, null
						);
					} else if (require instanceof final EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							managedReferencesBehaviour, referenceNames, null, entityGroupFetch
						);
					} else if (require instanceof final ChunkingRequireConstraint chunk) {
						return new ReferenceContent(
							managedReferencesBehaviour, referenceNames, null, null, chunk
						);
					} else {
						throw new GenericEvitaInternalError("Should never happen!");
					}
				} else {
					final RequireConstraint requirement1 = ofNullable(ctx.args.entityRequirement).map(c -> (RequireConstraint) visitChildConstraint(c, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class)).orElse(null);
					final RequireConstraint requirement2 = ofNullable(ctx.args.groupEntityRequirement).map(c -> (RequireConstraint) visitChildConstraint(c, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class)).orElse(null);
					if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
						return new ReferenceContent(
							managedReferencesBehaviour,
							referenceNames,
							entityFetch,
							entityGroupFetch
						);
					} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
						return new ReferenceContent(
							managedReferencesBehaviour,
							referenceNames,
							entityFetch,
							null,
							chunk
						);
					} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
						return new ReferenceContent(
							managedReferencesBehaviour,
							referenceNames,
							null,
							entityGroupFetch,
							chunk
						);
					} else {
						return new ReferenceContent(managedReferencesBehaviour, referenceNames);
					}
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent1Constraint(SingleRefReferenceContent1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);

				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				return new ReferenceContent(
					managedReferencesBehaviour,
					referenceName, null, null, (EntityFetch) null, null, null
				);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent2Constraint(SingleRefReferenceContent2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null,
						entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null,
						entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null,
						null, entityGroupFetch, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent3Constraint(SingleRefReferenceContent3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final RequireConstraint requirement = ofNullable(ctx.args.requirement)
					.map(c -> (RequireConstraint) visitChildConstraint(c, EntityFetchRequire.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, (EntityFetch) null, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, entityFetch, null, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, null, entityGroupFetch, null
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent4Constraint(SingleRefReferenceContent4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, null, entityGroupFetch, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent5Constraint(SingleRefReferenceContent5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement = (RequireConstraint) ofNullable(ctx.args.requirement)
					.map(c -> visitChildConstraint(c, EntityFetchRequire.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, (EntityFetch) null, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, entityFetch, null, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, null, entityGroupFetch, null
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent6Constraint(SingleRefReferenceContent6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, null, entityGroupFetch, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent7Constraint(SingleRefReferenceContent7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement = ofNullable(ctx.args.requirement)
					.map(c -> (RequireConstraint) visitChildConstraint(c, EntityFetchRequire.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, (EntityFetch) null, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, entityFetch, null, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, null, entityGroupFetch, null
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContent8Constraint(SingleRefReferenceContent8ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy,
						entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy,
						entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy,
						null, entityGroupFetch, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitAllRefsWithAttributesReferenceContent1Constraint(AllRefsWithAttributesReferenceContent1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new ReferenceContent(AttributeContent.ALL_ATTRIBUTES);
				}

				final ManagedReferencesBehaviour managedReferencesBehaviour = getManagedReferencesBehaviourAsEnum(
					ctx.args.managedReferencesBehaviour
				);

				final RequireConstraint requirement = ofNullable(ctx.args.requirement)
					.map(c -> (RequireConstraint) visitChildConstraint(c, AttributeContent.class,
						EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, null, null
					);
				} else if (requirement instanceof final AttributeContent attributeContent) {
					return new ReferenceContent(
						managedReferencesBehaviour, attributeContent, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, entityFetch, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitAllRefsWithAttributesReferenceContent2Constraint(AllRefsWithAttributesReferenceContent2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final ManagedReferencesBehaviour managedReferencesBehaviour = getManagedReferencesBehaviourAsEnum(
					ctx.args.managedReferencesBehaviour
				);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.requirement1, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.requirement2, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				Assert.isTrue(
					requirement1 == null || requirement2 == null ||
					!requirement1.getClass().equals(requirement2.getClass()),
					() -> new EvitaSyntaxException(ctx, "Each requirement must be of a different type.")
				);

				if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, entityFetch, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, attributeContent, entityFetch, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, attributeContent, null, entityGroupFetch
					);
				} else if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof final EntityGroupFetch entityGroupFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, attributeContent, null, null, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitAllRefsWithAttributesReferenceContent3Constraint(AllRefsWithAttributesReferenceContent3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final ManagedReferencesBehaviour managedReferencesBehaviour = getManagedReferencesBehaviourAsEnum(
					ctx.args.managedReferencesBehaviour
				);
				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(managedReferencesBehaviour, attributeContent, entityFetch, entityGroupFetch, chunk);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(managedReferencesBehaviour, attributeContent, entityFetch, null, chunk);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(managedReferencesBehaviour, attributeContent, null, entityGroupFetch, chunk);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes0Constraint(SingleRefReferenceContentWithAttributes0ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final RequireConstraint requirement = visitChildConstraint(ctx.args.requirement, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement instanceof  AttributeContent attributeContent) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, null, null, null
					);
				} else if (requirement instanceof EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, null
					);
				} else if (requirement instanceof EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, null
					);
				} else if (requirement instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes1Constraint(SingleRefReferenceContentWithAttributes1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				return new ReferenceContent(
					managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, null, null, null
				);
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes2Constraint(SingleRefReferenceContentWithAttributes2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.requirement1, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.requirement2, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				Assert.isTrue(
					requirement1 == null || requirement2 == null ||
					!requirement1.getClass().equals(requirement2.getClass()),
					() -> new EvitaSyntaxException(ctx, "Each requirement must be of a different type.")
				);

				if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, entityFetch, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, entityFetch, null, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, null, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof final EntityGroupFetch entityGroupFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, null, null, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes3Constraint(SingleRefReferenceContentWithAttributes3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, null, attributeContent, null, entityGroupFetch, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes4Constraint(SingleRefReferenceContentWithAttributes4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final RequireConstraint requirement = ofNullable(ctx.args.requirement)
					.map(c -> (RequireConstraint) visitChildConstraint(c, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, null, null, null
					);
				} else if (requirement instanceof final AttributeContent attributeContent) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, null, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, null
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes5Constraint(SingleRefReferenceContentWithAttributes5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.requirement1, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.requirement2, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				Assert.isTrue(
					requirement1 == null || requirement2 == null ||
					!requirement1.getClass().equals(requirement2.getClass()),
					() -> new EvitaSyntaxException(ctx, "Each requirement must be of a different type.")
				);

				if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, entityFetch, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, entityFetch, null, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, null, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof final EntityGroupFetch entityGroupFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, null, null, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes6Constraint(SingleRefReferenceContentWithAttributes6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, null, attributeContent, null, entityGroupFetch, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes7Constraint(SingleRefReferenceContentWithAttributes7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement = ofNullable(ctx.args.requirement)
					.map(c -> (RequireConstraint) visitChildConstraint(c, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, null, null, null
					);
				} else if (requirement instanceof final AttributeContent attributeContent) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, null, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, null
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes8Constraint(SingleRefReferenceContentWithAttributes8ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.requirement1, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.requirement2, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class, ChunkingRequireConstraint.class);
				Assert.isTrue(
					requirement1 == null || requirement2 == null ||
					!requirement1.getClass().equals(requirement2.getClass()),
					() -> new EvitaSyntaxException(ctx, "Each requirement must be of a different type.")
				);

				if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, entityFetch, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, entityFetch, null, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, null, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof final EntityGroupFetch entityGroupFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, null, null, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes9Constraint(SingleRefReferenceContentWithAttributes9ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, null, orderBy, attributeContent, null, entityGroupFetch, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes10Constraint(SingleRefReferenceContentWithAttributes10ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement = ofNullable(ctx.args.requirement)
					.map(c -> (RequireConstraint) visitChildConstraint(c, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class))
					.orElse(null);

				if (requirement == null) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES,null, null, null
					);
				} else if (requirement instanceof final AttributeContent attributeContent) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, null, null, null
					);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, null
					);
				} else if (requirement instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, null
					);
				} else if (requirement instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES, null, null, chunk
					);
				} else {
					throw new GenericEvitaInternalError("Should never happen!");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes11Constraint(SingleRefReferenceContentWithAttributes11ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.requirement1, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.requirement2, AttributeContent.class, EntityFetch.class, EntityGroupFetch.class, ChunkingRequireConstraint.class);
				Assert.isTrue(
					requirement1 == null || requirement2 == null ||
					!requirement1.getClass().equals(requirement2.getClass()),
					() -> new EvitaSyntaxException(ctx, "Each requirement must be of a different type.")
				);

				if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES, entityFetch, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityFetch entityFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, entityFetch, null, null
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final EntityGroupFetch entityGroupFetch) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, null, entityGroupFetch, null
					);
				} else if (requirement1 instanceof final EntityFetch entityFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof final EntityGroupFetch entityGroupFetch && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, AttributeContent.ALL_ATTRIBUTES, null, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof final AttributeContent attributeContent && requirement2 instanceof final ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, null, null, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes12Constraint(SingleRefReferenceContentWithAttributes12ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Serializable firstArg = getManagedReferencesBehaviour(
					ctx.args.managedReferencesBehaviour
				);
				final ManagedReferencesBehaviour managedReferencesBehaviour = toManagedReferencesBehaviour(firstArg);

				final String referenceName = firstArg instanceof String firstArgAsString ?
					firstArgAsString :
					ctx.args.classifier
						.accept(this.stringValueTokenVisitor)
						.asString();
				ConstraintArguments.requireName(referenceName, "referenceName");

				final FilterBy filterBy = visitChildConstraint(this.filterConstraintVisitor, ctx.args.filterBy, FilterBy.class);
				final OrderBy orderBy = visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class);

				final AttributeContent attributeContent = visitChildConstraint(ctx.args.attributeContent, AttributeContent.class);
				final RequireConstraint requirement1 = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class, ChunkingRequireConstraint.class);
				final RequireConstraint requirement2 = visitChildConstraint(ctx.args.groupEntityRequirement, EntityGroupFetch.class, ChunkingRequireConstraint.class);

				if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof EntityGroupFetch entityGroupFetch) {
					final ChunkingRequireConstraint chunk = ctx.args.requirement == null ?
						null : visitChildConstraint(ctx.args.requirement, ChunkingRequireConstraint.class);
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, entityFetch, entityGroupFetch, chunk
					);
				} else if (requirement1 instanceof EntityFetch entityFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, entityFetch, null, chunk
					);
				} else if (requirement1 instanceof EntityGroupFetch entityGroupFetch && requirement2 instanceof ChunkingRequireConstraint chunk) {
					return new ReferenceContent(
						managedReferencesBehaviour, referenceName, filterBy, orderBy, attributeContent, null, entityGroupFetch, chunk
					);
				} else {
					throw new EvitaSyntaxException(ctx, "Invalid combination of requirements.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitEmptyHierarchyContentConstraint(EmptyHierarchyContentConstraintContext ctx) {
		return parse(ctx, QueryConstraints::hierarchyContent);
	}

	@Override
	public RequireConstraint visitSingleRequireHierarchyContentConstraint(SingleRequireHierarchyContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final HierarchyParentsBehaviour parentsBehaviour = getParentsBehaviour(ctx.args.parentsBehaviour);
				// the behaviour may stand on its own - `hierarchyContent(COMPLETE)` carries no child constraint
				if (ctx.args.requirement == null) {
					return QueryConstraints.hierarchyContent(parentsBehaviour);
				}
				final RequireConstraint requirement = visitChildConstraint(ctx.args.requirement,
					HierarchyStopAt.class, EntityFetch.class);
				if (requirement == null) {
					return QueryConstraints.hierarchyContent(parentsBehaviour);
				} else if (requirement instanceof final HierarchyStopAt stopAt) {
					return QueryConstraints.hierarchyContent(parentsBehaviour, stopAt);
				} else if (requirement instanceof final EntityFetch entityFetch) {
					return QueryConstraints.hierarchyContent(parentsBehaviour, entityFetch);
				} else {
					throw new EvitaSyntaxException(ctx, "Unsupported requirement constraint. Only `stopAt` and `entityFetch` are supported.");
				}
			}
		);
	}

	@Override
	public RequireConstraint visitAllRequiresHierarchyContentConstraint(AllRequiresHierarchyContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final HierarchyParentsBehaviour parentsBehaviour = getParentsBehaviour(ctx.args.parentsBehaviour);
				final HierarchyStopAt stopAt = visitChildConstraint(ctx.args.stopAt, HierarchyStopAt.class);
				final EntityFetch entityFetch = visitChildConstraint(ctx.args.entityRequirement, EntityFetch.class);
				return QueryConstraints.hierarchyContent(parentsBehaviour, stopAt, entityFetch);
			}
		);
	}

	@Override
	public RequireConstraint visitPriceTypeConstraint(PriceTypeConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.priceType(
				ctx.args.value
					.accept(this.queryPriceModeValueTokenVisitor)
					.asEnum(QueryPriceMode.class)
			)
		);
	}

	@Override
	public RequireConstraint visitDefaultAccompanyingPriceListsConstraint(DefaultAccompanyingPriceListsConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.defaultAccompanyingPriceLists(
				ctx.args.classifiers.accept(this.stringValueListTokenVisitor).asStringArray()
			)
		);
	}

	@Override
	public RequireConstraint visitAccompanyingPriceContentDefaultConstraint(AccompanyingPriceContentDefaultConstraintContext ctx) {
		return parse(
			ctx,
			QueryConstraints::accompanyingPriceContentDefault
		);
	}

	@Override
	public RequireConstraint visitAccompanyingPriceContentConstraint(AccompanyingPriceContentConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args.values == null) {
					return QueryConstraints.accompanyingPriceContent(
						ctx.args.classifier.accept(this.stringValueListTokenVisitor).asString()
					);
				} else {
					return QueryConstraints.accompanyingPriceContent(
						ctx.args.classifier.accept(this.stringValueListTokenVisitor).asString(),
						ctx.args.values.accept(this.stringValueListTokenVisitor).asStringArray()
					);
				}
			}
		);
	}

	@Override
	public RequireConstraint visitDataInLocalesAllConstraint(DataInLocalesAllConstraintContext ctx) {
		return parse(ctx, QueryConstraints::dataInLocales);
	}

	@Override
	public RequireConstraint visitDataInLocalesConstraint(DataInLocalesConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.dataInLocales(
				ctx.args.values.accept(this.localeValueTokenVisitor).asLocaleArray()
			)
		);
	}

	@Override
	public RequireConstraint visitFacetSummary1Constraint(FacetSummary1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.facetSummary();
				}
				return QueryConstraints.facetSummary(
					ctx.args.depth.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitFacetSummary2Constraint(FacetSummary2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitFacetSummaryConstraint(ctx, ctx.args.depth, ctx.args.filter, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitFacetSummary3Constraint(FacetSummary3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitFacetSummaryConstraint(ctx, ctx.args.depth, null, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitFacetSummary4Constraint(FacetSummary4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitFacetSummaryConstraint(ctx, ctx.args.depth, null, null, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitFacetSummary5Constraint(FacetSummary5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitFacetSummaryConstraint(ctx, null, ctx.args.filter, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitFacetSummary6Constraint(FacetSummary6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitFacetSummaryConstraint(ctx, null, null, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitFacetSummary7Constraint(FacetSummary7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitFacetSummaryConstraint(ctx, null, null, null, ctx.args.requirements)
		);
	}

	private RequireConstraint visitFacetSummaryConstraint(@Nonnull RequireConstraintContext ctx,
	                                                      @Nullable ValueTokenContext depthArg,
	                                                      @Nullable FacetSummaryFilterArgsContext filterArg,
	                                                      @Nullable FacetSummaryOrderArgsContext orderArg,
	                                                      @Nullable FacetSummaryRequirementsArgsContext requirementsArg) {
		final FacetStatisticsDepth depth = ofNullable(depthArg)
			.map(it -> it.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class))
			.orElse(FacetStatisticsDepth.COUNTS);

		final FilterConstraint filterBy1 = ofNullable(filterArg)
			.map(filter -> filter.filterBy)
			.map(c -> (FilterConstraint) visitChildConstraint(this.filterConstraintVisitor, c, FilterBy.class, FilterGroupBy.class))
			.orElse(null);
		final FilterGroupBy filterBy2 = ofNullable(filterArg)
			.flatMap(filter -> ofNullable(filter.filterGroupBy))
			.map(c -> visitChildConstraint(this.filterConstraintVisitor, c, FilterGroupBy.class))
			.orElse(null);
		if (filterArg != null && filterArg.filterGroupBy != null) {
			Assert.isTrue(
				filterArg.filterBy.getStart().getText().equals("filterBy"),
				() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `filterGroupBy` constraints.")
			);
		}

		final OrderConstraint orderBy1 = ofNullable(orderArg)
			.map(order -> order.orderBy)
			.map(c -> (OrderConstraint) visitChildConstraint(this.orderConstraintVisitor, c, OrderBy.class, OrderGroupBy.class))
			.orElse(null);
		final OrderGroupBy orderBy2 = ofNullable(orderArg)
			.flatMap(order -> ofNullable(order.orderGroupBy))
			.map(c -> visitChildConstraint(this.orderConstraintVisitor, c, OrderGroupBy.class))
			.orElse(null);
		if (orderArg != null && orderArg.orderGroupBy != null) {
			Assert.isTrue(
				orderArg.orderBy.getStart().getText().equals("orderBy"),
				() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `orderGroupBy` constraints.")
			);
		}

		return QueryConstraints.facetSummary(
			depth,
			filterBy1 instanceof FilterBy f ? f : null,
			filterBy1 instanceof FilterGroupBy f ? f : filterBy2,
			orderBy1 instanceof OrderBy o ? o : null,
			orderBy1 instanceof OrderGroupBy o ? o : orderBy2,
			parseFacetSummaryRequirementsArgs(requirementsArg)
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryOfReference1Constraint(FacetSummaryOfReference1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.facetSummaryOfReference(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public RequireConstraint visitFacetSummaryOfReference2Constraint(FacetSummaryOfReference2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String referenceName = ctx.args.referenceName.accept(this.stringValueTokenVisitor).asString();
				ConstraintArguments.requireName(referenceName, "referenceName");
				final FacetStatisticsDepth depth = ofNullable(ctx.args.depth)
					.map(it -> it.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class))
					.orElse(FacetStatisticsDepth.COUNTS);

				final FilterConstraint filterBy1 = ofNullable(ctx.args.filter)
					.map(filter -> filter.filterBy)
					.map(c -> (FilterConstraint) visitChildConstraint(this.filterConstraintVisitor, c, FilterBy.class, FilterGroupBy.class))
					.orElse(null);
				final FilterGroupBy filterBy2 = ofNullable(ctx.args.filter)
					.flatMap(filter -> ofNullable(filter.filterGroupBy))
					.map(c -> visitChildConstraint(this.filterConstraintVisitor, c, FilterGroupBy.class))
					.orElse(null);
				if (ctx.args.filter != null && ctx.args.filter.filterGroupBy != null) {
					Assert.isTrue(
						ctx.args.filter.filterBy.getStart().getText().equals("filterBy"),
						() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `filterGroupBy` constraints.")
					);
				}

				final OrderConstraint orderBy1 = ofNullable(ctx.args.order)
					.map(order -> order.orderBy)
					.map(c -> (OrderConstraint) visitChildConstraint(this.orderConstraintVisitor, c, OrderBy.class, OrderGroupBy.class))
					.orElse(null);
				final OrderGroupBy orderBy2 = ofNullable(ctx.args.order)
					.flatMap(order -> ofNullable(order.orderGroupBy))
					.map(c -> visitChildConstraint(this.orderConstraintVisitor, c, OrderGroupBy.class))
					.orElse(null);
				if (ctx.args.order != null && ctx.args.order.orderGroupBy != null) {
					Assert.isTrue(
						ctx.args.order.orderBy.getStart().getText().equals("orderBy"),
						() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `orderGroupBy` constraints.")
					);
				}

				return QueryConstraints.facetSummaryOfReference(
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
	public RequireConstraint visitReferenceSummary1Constraint(ReferenceSummary1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.referenceSummary();
				}
				return QueryConstraints.referenceSummary(
					ctx.args.depth.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitReferenceSummary2Constraint(ReferenceSummary2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, ctx.args.depth, ctx.args.filter, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummary3Constraint(ReferenceSummary3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, ctx.args.depth, null, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummary4Constraint(ReferenceSummary4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, ctx.args.depth, null, null, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummary5Constraint(ReferenceSummary5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, null, ctx.args.filter, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummary6Constraint(ReferenceSummary6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, null, null, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummary7Constraint(ReferenceSummary7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, null, null, null, ctx.args.requirements)
		);
	}

	private RequireConstraint visitReferenceSummaryConstraint(@Nonnull RequireConstraintContext ctx,
	                                                          @Nullable ValueTokenContext depthArg,
	                                                          @Nullable FacetSummaryFilterArgsContext filterArg,
	                                                          @Nullable FacetSummaryOrderArgsContext orderArg,
	                                                          @Nullable ReferenceSummaryRequirementsArgsContext requirementsArg) {
		final FacetStatisticsDepth depth = ofNullable(depthArg)
			.map(it -> it.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class))
			.orElse(FacetStatisticsDepth.COUNTS);

		final FilterConstraint filterBy1 = ofNullable(filterArg)
			.map(filter -> filter.filterBy)
			.map(c -> (FilterConstraint) visitChildConstraint(this.filterConstraintVisitor, c, FilterBy.class, FilterGroupBy.class))
			.orElse(null);
		final FilterGroupBy filterBy2 = ofNullable(filterArg)
			.flatMap(filter -> ofNullable(filter.filterGroupBy))
			.map(c -> visitChildConstraint(this.filterConstraintVisitor, c, FilterGroupBy.class))
			.orElse(null);
		if (filterArg != null && filterArg.filterGroupBy != null) {
			Assert.isTrue(
				filterArg.filterBy.getStart().getText().equals("filterBy"),
				() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `filterGroupBy` constraints.")
			);
		}

		final OrderConstraint orderBy1 = ofNullable(orderArg)
			.map(order -> order.orderBy)
			.map(c -> (OrderConstraint) visitChildConstraint(this.orderConstraintVisitor, c, OrderBy.class, OrderGroupBy.class))
			.orElse(null);
		final OrderGroupBy orderBy2 = ofNullable(orderArg)
			.flatMap(order -> ofNullable(order.orderGroupBy))
			.map(c -> visitChildConstraint(this.orderConstraintVisitor, c, OrderGroupBy.class))
			.orElse(null);
		if (orderArg != null && orderArg.orderGroupBy != null) {
			Assert.isTrue(
				orderArg.orderBy.getStart().getText().equals("orderBy"),
				() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `orderGroupBy` constraints.")
			);
		}

		return new ReferenceSummary(
			depth == null ? FacetStatisticsDepth.COUNTS : depth,
			filterBy1 instanceof FilterBy f ? f : null,
			filterBy1 instanceof FilterGroupBy f ? f : filterBy2,
			orderBy1 instanceof OrderBy o ? o : null,
			orderBy1 instanceof OrderGroupBy o ? o : orderBy2,
			parseReferenceSummaryRequirementsArgs(requirementsArg)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms1Constraint(ReferenceSummaryWithHistograms1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.referenceSummary();
				}
				return QueryConstraints.referenceSummary(
					ctx.args.depth.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms2Constraint(ReferenceSummaryWithHistograms2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, ctx.args.depth, ctx.args.filter, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms3Constraint(ReferenceSummaryWithHistograms3ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, ctx.args.depth, null, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms4Constraint(ReferenceSummaryWithHistograms4ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, ctx.args.depth, null, null, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms5Constraint(ReferenceSummaryWithHistograms5ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, null, ctx.args.filter, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms6Constraint(ReferenceSummaryWithHistograms6ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, null, null, ctx.args.order, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryWithHistograms7Constraint(ReferenceSummaryWithHistograms7ConstraintContext ctx) {
		return parse(
			ctx,
			() -> visitReferenceSummaryConstraint(ctx, null, null, null, ctx.args.requirements)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryOfReference1Constraint(ReferenceSummaryOfReference1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.referenceSummaryOfReference(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryOfReference2Constraint(ReferenceSummaryOfReference2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> buildReferenceSummaryOfReference(ctx, ctx.args)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryOfReferenceWithHistograms1Constraint(ReferenceSummaryOfReferenceWithHistograms1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.referenceSummaryOfReference(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public RequireConstraint visitReferenceSummaryOfReferenceWithHistograms2Constraint(ReferenceSummaryOfReferenceWithHistograms2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> buildReferenceSummaryOfReference(ctx, ctx.args)
		);
	}

	/**
	 * Shared builder for {@code referenceSummaryOfReference} and its {@code ...WithHistograms} alias.
	 * Both accept identical {@link ReferenceSummaryOfReference2ArgsContext} arguments — the alias only
	 * affects the EvitaQL string representation via
	 * {@link ReferenceSummaryOfReference#getSuffixIfApplied()}.
	 */
	@Nonnull
	private ReferenceSummaryOfReference buildReferenceSummaryOfReference(
		@Nonnull RequireConstraintContext ctx,
		@Nonnull ReferenceSummaryOfReference2ArgsContext args
	) {
		final String referenceName = args.referenceName.accept(this.stringValueTokenVisitor).asString();
		ConstraintArguments.requireName(referenceName, "referenceName");
		final FacetStatisticsDepth depth = ofNullable(args.depth)
			.map(it -> it.accept(this.facetStatisticsDepthValueTokenVisitor).asEnum(FacetStatisticsDepth.class))
			.orElse(FacetStatisticsDepth.COUNTS);

		final FilterConstraint filterBy1 = ofNullable(args.filter)
			.map(filter -> filter.filterBy)
			.map(c -> (FilterConstraint) visitChildConstraint(this.filterConstraintVisitor, c, FilterBy.class, FilterGroupBy.class))
			.orElse(null);
		final FilterGroupBy filterBy2 = ofNullable(args.filter)
			.flatMap(filter -> ofNullable(filter.filterGroupBy))
			.map(c -> visitChildConstraint(this.filterConstraintVisitor, c, FilterGroupBy.class))
			.orElse(null);
		if (args.filter != null && args.filter.filterGroupBy != null) {
			Assert.isTrue(
				args.filter.filterBy.getStart().getText().equals("filterBy"),
				() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `filterGroupBy` constraints.")
			);
		}

		final OrderConstraint orderBy1 = ofNullable(args.order)
			.map(order -> order.orderBy)
			.map(c -> (OrderConstraint) visitChildConstraint(this.orderConstraintVisitor, c, OrderBy.class, OrderGroupBy.class))
			.orElse(null);
		final OrderGroupBy orderBy2 = ofNullable(args.order)
			.flatMap(order -> ofNullable(order.orderGroupBy))
			.map(c -> visitChildConstraint(this.orderConstraintVisitor, c, OrderGroupBy.class))
			.orElse(null);
		if (args.order != null && args.order.orderGroupBy != null) {
			Assert.isTrue(
				args.order.orderBy.getStart().getText().equals("orderBy"),
				() -> new EvitaSyntaxException(ctx, "Cannot pass 2 `orderGroupBy` constraints.")
			);
		}

		return new ReferenceSummaryOfReference(
			referenceName,
			depth == null ? FacetStatisticsDepth.COUNTS : depth,
			filterBy1 instanceof FilterBy f ? f : null,
			filterBy1 instanceof FilterGroupBy f ? f : filterBy2,
			orderBy1 instanceof OrderBy o ? o : null,
			orderBy1 instanceof OrderGroupBy o ? o : orderBy2,
			parseReferenceSummaryRequirementsArgs(args.requirements)
		);
	}

	@Override
	public RequireConstraint visitHistogramStatistics1Constraint(HistogramStatistics1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> buildHistogramStatistics(ctx, ctx.args.requestedBucketCount, null, ctx.args.values)
		);
	}

	@Override
	public RequireConstraint visitHistogramStatistics2Constraint(HistogramStatistics2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> buildHistogramStatistics(
				ctx,
				ctx.args.requestedBucketCount,
				visitChildConstraint(ctx.args.entityFetch, EntityFetch.class),
				ctx.args.values
			)
		);
	}

	/**
	 * Common builder for {@link ReferenceHistogramStatistics}. The variadic value list may carry an
	 * optional {@link HistogramBehavior} enum as its first element (matching the in-memory constructor
	 * shape shared with {@link AttributeHistogram}), followed by histogram index names. An empty bound list
	 * omits the requirement. A leading null in an explicit argument sequence stands for the default behaviour.
	 */
	@Nullable
	private ReferenceHistogramStatistics buildHistogramStatistics(
		@Nonnull RequireConstraintContext ctx,
		@Nonnull ValueTokenContext requestedBucketCountArg,
		@Nullable EntityFetch entityFetch,
		@Nonnull VariadicValueTokensContext valuesArg
	) {
		final int requestedBucketCount = requestedBucketCountArg.accept(this.intValueTokenVisitor).asRequiredInt();

		final LinkedList<Serializable> args = Arrays.stream(valuesArg
				.accept(this.attributeHistogramArgValueTokenVisitor)
				.asSerializableArray())
			.collect(Collectors.toCollection(LinkedList::new));

		final HistogramBehavior behavior;
		final boolean hasNullBehavior = consumeLeadingNullEnumDefault(valuesArg, args);
		final Serializable firstArgument = args.peekFirst();
		if (firstArgument instanceof HistogramBehavior) {
			behavior = castArgument(ctx, args.pop(), HistogramBehavior.class);
		} else if (firstArgument instanceof EnumWrapper enumWrapper && enumWrapper.canBeMappedTo(HistogramBehavior.class)) {
			behavior = castArgument(ctx, args.pop(), EnumWrapper.class).toEnum(HistogramBehavior.class);
		} else {
			behavior = null;
		}

		final String[] indexNames = parseVariadicTail(ctx, args,
			EvitaQLValueTokenVisitor.withAllowedTypes(String.class), hasNullBehavior || behavior != null)
			.asStringArray();

		return QueryConstraints.histogramStatistics(requestedBucketCount, behavior, entityFetch, indexNames);
	}

	@Override
	public RequireConstraint visitFacetGroupsConjunctionConstraint(FacetGroupsConjunctionConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.facetGroupsConjunction(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.facetGroupRelationLevel == null ?
					null : ctx.args.facetGroupRelationLevel.accept(this.facetGroupRelationLevelValueTokenVisitor).asEnum(FacetGroupRelationLevel.class),
				ctx.args.filterConstraint() == null ?
					null : (FilterBy) ctx.args.filterConstraint().accept(this.filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsDisjunctionConstraint(FacetGroupsDisjunctionConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.facetGroupsDisjunction(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.facetGroupRelationLevel == null ?
					null : ctx.args.facetGroupRelationLevel.accept(this.facetGroupRelationLevelValueTokenVisitor).asEnum(FacetGroupRelationLevel.class),
				ctx.args.filterConstraint() == null ?
					null : (FilterBy) ctx.args.filterConstraint().accept(this.filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsNegationConstraint(FacetGroupsNegationConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.facetGroupsNegation(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.facetGroupRelationLevel == null ?
					null : ctx.args.facetGroupRelationLevel.accept(this.facetGroupRelationLevelValueTokenVisitor).asEnum(FacetGroupRelationLevel.class),
				ctx.args.filterConstraint() == null ?
					null : (FilterBy) ctx.args.filterConstraint().accept(this.filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitFacetGroupsExclusivityConstraint(FacetGroupsExclusivityConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.facetGroupsExclusivity(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.facetGroupRelationLevel == null ?
					null : ctx.args.facetGroupRelationLevel.accept(this.facetGroupRelationLevelValueTokenVisitor).asEnum(FacetGroupRelationLevel.class),
				ctx.args.filterConstraint() == null ?
					null : (FilterBy) ctx.args.filterConstraint().accept(this.filterConstraintVisitor)
			)
		);
	}

	@Override
	public RequireConstraint visitFacetCalculationRulesConstraint(FacetCalculationRulesConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.facetCalculationRules(
				ctx.args.facetsWithSameGroup.accept(this.facetRelationTypeValueTokenVisitor).asEnum(FacetRelationType.class),
				ctx.args.facetsWithDifferentGroups.accept(this.facetRelationTypeValueTokenVisitor).asEnum(FacetRelationType.class)
			)
		);
	}

	@Override
	public RequireConstraint visitAttributeHistogramConstraint(AttributeHistogramConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final int requestedBucketCount =
					ctx.args.requestedBucketCount.accept(this.intValueTokenVisitor).asRequiredInt();

				final LinkedList<Serializable> args = Arrays.stream(ctx.args.values
						.accept(this.attributeHistogramArgValueTokenVisitor)
						.asSerializableArray())
					.collect(Collectors.toCollection(LinkedList::new));

				final HistogramBehavior behavior;
				final boolean hasNullBehavior = consumeLeadingNullEnumDefault(ctx.args.values, args);
				final Serializable secondArgument = args.peekFirst();
				if (secondArgument instanceof HistogramBehavior) {
					behavior = castArgument(ctx, args.pop(), HistogramBehavior.class);
				} else if (secondArgument instanceof EnumWrapper enumWrapper && enumWrapper.canBeMappedTo(HistogramBehavior.class)) {
					behavior = castArgument(ctx, args.pop(), EnumWrapper.class).toEnum(HistogramBehavior.class);
				} else {
					behavior = null;
				}

				final String[] attributesNames = parseVariadicTail(ctx, args,
					EvitaQLValueTokenVisitor.withAllowedTypes(String.class), hasNullBehavior || behavior != null)
					.asStringArray();

				return QueryConstraints.attributeHistogram(
					requestedBucketCount,
					behavior,
					attributesNames
				);
			}
		);
	}

	@Override
	public RequireConstraint visitPriceHistogramConstraint(PriceHistogramConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.priceHistogram(
				ctx.args.requestedBucketCount.accept(this.intValueTokenVisitor).asRequiredInt(),
				ctx.args.behaviour != null ? ctx.args.behaviour.accept(this.histogramBehaviorValueTokenVisitor).asEnum(HistogramBehavior.class) : null
			)
		);
	}

	@Override
	public RequireConstraint visitHierarchyDistanceConstraint(HierarchyDistanceConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.distance(ctx.args.value.accept(this.intValueTokenVisitor).asInt())
		);
	}

	@Override
	public RequireConstraint visitHierarchyLevelConstraint(HierarchyLevelConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.level(ctx.args.value.accept(this.intValueTokenVisitor).asInt())
		);
	}

	@Override
	public RequireConstraint visitHierarchyNodeConstraint(HierarchyNodeConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.node(visitChildConstraint(this.filterConstraintVisitor, ctx.args.filter,
				FilterBy.class))
		);
	}

	@Override
	public RequireConstraint visitHierarchyStopAtConstraint(HierarchyStopAtConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.stopAt(visitChildConstraint(ctx.args.requirement,
				HierarchyStopAtRequireConstraint.class))
		);
	}

	@Override
	public RequireConstraint visitHierarchyStatisticsConstraint(HierarchyStatisticsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.statistics();
				}
				final LinkedList<Serializable> settings = Arrays.stream(ctx.args.settings
						.accept(this.statisticsArgValueTokenVisitor)
						.asSerializableArray())
					.collect(Collectors.toCollection(LinkedList::new));
				final boolean hasNullBase = consumeLeadingNullEnumDefault(ctx.args.settings, settings);
				final Serializable first = hasNullBase ? null : settings.peekFirst();
				final StatisticsBase base;
				final boolean hasBase;
				if (first instanceof StatisticsBase statisticsBase) {
					base = statisticsBase;
					hasBase = true;
				} else if (first instanceof EnumWrapper wrapper && wrapper.canBeMappedTo(StatisticsBase.class)) {
					base = wrapper.toEnum(StatisticsBase.class);
					hasBase = true;
				} else {
					base = StatisticsBase.WITHOUT_USER_FILTER;
					hasBase = hasNullBase;
				}
				if (hasBase && !hasNullBase) {
					settings.removeFirst();
				}
				return QueryConstraints.statistics(base, parseVariadicTail(ctx, settings,
					EvitaQLValueTokenVisitor.withAllowedTypes(StatisticsType.class, EnumWrapper.class), hasBase)
					.asEnumArray(StatisticsType.class));
			}
		);
	}

	/**
	 * Interprets a leading scalar null as the optional enum's default, the meaning a null enum has in the Java
	 * factories. This applies only to explicit argument sequences; null items inside a bound list keep their list
	 * meaning.
	 *
	 * @param ctx argument syntax; only an explicit sequence of arguments can carry a leading null enum
	 * @param arguments remaining arguments, from which the default enum placeholder is consumed
	 * @return true when a leading null selected the optional enum's default
	 */
	private boolean consumeLeadingNullEnumDefault(
		@Nonnull VariadicValueTokensContext ctx,
		@Nonnull Deque<?> arguments
	) {
		if (ctx instanceof ExplicitVariadicValueTokensContext && !arguments.isEmpty()
			&& arguments.peekFirst() == null) {
			arguments.removeFirst();
			return true;
		}
		return false;
	}

	@Override
	public RequireConstraint visitHierarchyFromRootConstraint(HierarchyFromRootConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(this.stringValueTokenVisitor).asString();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> (RequireConstraint) visitChildConstraint(c,
						EntityFetch.class, HierarchyOutputRequireConstraint.class))
					.filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return QueryConstraints.fromRoot(outputName);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return QueryConstraints.fromRoot(
						outputName,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return QueryConstraints.fromRoot(
					outputName,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyFromNodeConstraint(HierarchyFromNodeConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(this.stringValueTokenVisitor).asString();
				final HierarchyNode node = visitChildConstraint(ctx.args.node, HierarchyNode.class);
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> (RequireConstraint) visitChildConstraint(c,
						EntityFetch.class, HierarchyOutputRequireConstraint.class))
					.filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return QueryConstraints.fromNode(
						outputName,
						node
					);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return QueryConstraints.fromNode(
						outputName,
						node,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return QueryConstraints.fromNode(
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
	public RequireConstraint visitHierarchyChildrenConstraint(HierarchyChildrenConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(this.stringValueTokenVisitor).asString();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> (RequireConstraint) visitChildConstraint(c,
						EntityFetch.class, HierarchyOutputRequireConstraint.class))
					.filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return QueryConstraints.children(outputName);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return QueryConstraints.children(
						outputName,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return QueryConstraints.children(
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
		return parse(ctx, () -> QueryConstraints.siblings());
	}

	@Override
	public RequireConstraint visitBasicHierarchySiblingsConstraint(BasicHierarchySiblingsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> (RequireConstraint) visitChildConstraint(c,
						EntityFetch.class, HierarchyOutputRequireConstraint.class))
					.filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return QueryConstraints.siblings();
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return QueryConstraints.siblings(
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return QueryConstraints.siblings(
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitFullHierarchySiblingsConstraint(FullHierarchySiblingsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(this.stringValueTokenVisitor).asString();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> (RequireConstraint) visitChildConstraint(c,
						EntityFetch.class, HierarchyOutputRequireConstraint.class))
					.filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return QueryConstraints.siblings(outputName);
				}
				if (requirements.peekFirst() instanceof EntityFetch) {
					return QueryConstraints.siblings(
						outputName,
						(EntityFetch) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}
				return QueryConstraints.siblings(
					outputName,
					requirements.stream()
						.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
						.toArray(HierarchyOutputRequireConstraint[]::new)
				);
			}
		);
	}

	@Override
	public RequireConstraint visitHierarchyParentsConstraint(HierarchyParentsConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String outputName = ctx.args.outputName.accept(this.stringValueTokenVisitor).asString();
				final Deque<RequireConstraint> requirements = ctx.args.requirements
					.stream()
					.map(c -> (RequireConstraint) visitChildConstraint(c,
						EntityFetch.class, HierarchySiblings.class, HierarchyOutputRequireConstraint.class))
					.filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedList::new));

				if (requirements.isEmpty()) {
					return QueryConstraints.parents(outputName);
				}

				if (requirements.peekFirst() instanceof EntityFetch) {
					final EntityFetch entityFetch = (EntityFetch) requirements.pop();

					if (requirements.peekFirst() instanceof HierarchySiblings) {
						final HierarchySiblings siblings = (HierarchySiblings) requirements.pop();

						return QueryConstraints.parents(
							outputName,
							entityFetch,
							siblings,
							requirements.stream()
								.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
								.toArray(HierarchyOutputRequireConstraint[]::new)
						);
					}

					return QueryConstraints.parents(
						outputName,
						entityFetch,
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}

				if (requirements.peekFirst() instanceof HierarchySiblings) {
					return QueryConstraints.parents(
						outputName,
						(HierarchySiblings) requirements.pop(),
						requirements.stream()
							.map(it -> visitChildConstraint(ctx, it, HierarchyOutputRequireConstraint.class))
							.toArray(HierarchyOutputRequireConstraint[]::new)
					);
				}

				return QueryConstraints.parents(
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
			() -> QueryConstraints.hierarchyOfSelf(
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
			() -> QueryConstraints.hierarchyOfSelf(
				visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class),
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
			() -> QueryConstraints.hierarchyOfReference(
				// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
				ctx.args.referenceName.accept(this.stringValueTokenVisitor).asString(),
				EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitBasicHierarchyOfReferenceWithBehaviourConstraint(BasicHierarchyOfReferenceWithBehaviourConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.hierarchyOfReference(
				// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
				ctx.args.referenceName.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.emptyHierarchicalEntityBehaviour
					.accept(this.emptyHierarchicalEntityBehaviourValueTokenVisitor)
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
			() -> QueryConstraints.hierarchyOfReference(
				// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
				ctx.args.referenceName.accept(this.stringValueTokenVisitor).asString(),
				EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
				visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class),
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitFullHierarchyOfReferenceWithBehaviourConstraint(FullHierarchyOfReferenceWithBehaviourConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.hierarchyOfReference(
				// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
				ctx.args.referenceName.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.emptyHierarchicalEntityBehaviour
					.accept(this.emptyHierarchicalEntityBehaviourValueTokenVisitor)
					.asEnum(EmptyHierarchicalEntityBehaviour.class),
				visitChildConstraint(this.orderConstraintVisitor, ctx.args.orderBy, OrderBy.class),
				ctx.args.requirements
					.stream()
					.map(c -> visitChildConstraint(c, HierarchyRequireConstraint.class))
					.toArray(HierarchyRequireConstraint[]::new)
			)
		);
	}

	@Override
	public RequireConstraint visitQueryTelemetryConstraint(QueryTelemetryConstraintContext ctx) {
		return parse(
			ctx,
			() -> ctx.args == null ?
				// `queryTelemetry()` - timings only, and the overwhelmingly common form
				QueryConstraints.queryTelemetry() :
				QueryConstraints.queryTelemetry(
					ctx.args.value
						.accept(this.queryTelemetryContentValueTokenVisitor)
						.asEnum(QueryTelemetryContent.class)
				)
		);
	}

	@Nullable
	private Serializable getManagedReferencesBehaviour(@Nullable ValueTokenContext managedReferencesBehaviour) {
		return managedReferencesBehaviour == null || managedReferencesBehaviour.isEmpty() ?
			ManagedReferencesBehaviour.ANY :
			managedReferencesBehaviour
				.accept(this.managedReferenceBehaviourValueTokenVisitor)
				.asSerializable();
	}

	@Nullable
	private ManagedReferencesBehaviour getManagedReferencesBehaviourAsEnum(@Nullable ValueTokenContext managedReferencesBehaviour) {
		return managedReferencesBehaviour == null || managedReferencesBehaviour.isEmpty() ?
			ManagedReferencesBehaviour.ANY :
			managedReferencesBehaviour
				.accept(this.managedReferenceBehaviourValueTokenVisitor)
				.asEnum(ManagedReferencesBehaviour.class);
	}

	/**
	 * Resolves the optional leading {@link HierarchyParentsBehaviour} argument of `hierarchyContent`, falling back to
	 * {@link HierarchyContent#DEFAULT_PARENTS_BEHAVIOUR} when the caller omitted it.
	 *
	 * Unlike the managed-references behaviour of `referenceContent`, this position is unambiguous - no classifier can
	 * stand in it - so a single accessor suffices and no `Serializable`-typed sibling is needed to tell the two apart.
	 * The {@link EnumWrapper} an unqualified literal parses into is unwrapped by
	 * {@link io.evitadb.api.query.parser.Value#asEnum(Class)}.
	 *
	 * @param parentsBehaviour the value token holding the behaviour, may be null when the argument was omitted
	 * @return the parsed behaviour, or null when a bound null selects the factory's default
	 */
	@Nullable
	private HierarchyParentsBehaviour getParentsBehaviour(@Nullable ValueTokenContext parentsBehaviour) {
		return parentsBehaviour == null || parentsBehaviour.isEmpty() ?
			HierarchyContent.DEFAULT_PARENTS_BEHAVIOUR :
			parentsBehaviour
				.accept(this.hierarchyParentsBehaviourValueTokenVisitor)
				.asEnum(HierarchyParentsBehaviour.class);
	}

	/**
	 * Parses the variadic requirements list of {@link ReferenceSummary} /
	 * {@link ReferenceSummaryOfReference}. Each element must resolve to an {@link EntityFetch},
	 * {@link EntityGroupFetch}, or {@link ReferenceHistogramStatistics}, including when a child is omitted.
	 */
	@Nonnull
	private RequireConstraint[] parseReferenceSummaryRequirementsArgs(@Nullable ReferenceSummaryRequirementsArgsContext ctx) {
		if (ctx == null) {
			return new RequireConstraint[0];
		}
		return ctx.requirements.stream()
			.map(rc -> (RequireConstraint) visitChildConstraint(rc,
				EntityFetch.class, EntityGroupFetch.class, ReferenceHistogramStatistics.class))
			.filter(Objects::nonNull)
			.toArray(RequireConstraint[]::new);
	}

	@Nonnull
	private EntityFetchRequire[] parseFacetSummaryRequirementsArgs(@Nullable FacetSummaryRequirementsArgsContext ctx) {
		if (ctx == null) {
			return new EntityFetchRequire[0];
		}
		if (ctx.requirement != null) {
			final EntityFetchRequire requirement = visitChildConstraint(ctx.requirement, EntityFetchRequire.class);
			if (requirement instanceof final EntityFetch facetEntityRequirement) {
				return new EntityFetchRequire[]{facetEntityRequirement};
			} else if (requirement instanceof final EntityGroupFetch groupEntityRequirement) {
				return new EntityFetchRequire[]{groupEntityRequirement};
			} else {
				throw new EvitaSyntaxException(ctx, "Unsupported requirement constraint.");
			}
		}
		return new EntityFetchRequire[]{
			visitChildConstraint(ctx.facetEntityRequirement, EntityFetch.class),
			visitChildConstraint(ctx.groupEntityRequirement, EntityGroupFetch.class)
		};
	}

	@Override
	public RequireConstraint visitRequireInScopeConstraint(RequireInScopeConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.inScope(
				ctx.args.scope.accept(this.scopeValueTokenVisitor).asEnum(Scope.class),
				ctx.args.requireConstraints
					.stream()
					.map(fc -> visitChildConstraint(fc, RequireConstraint.class))
					.toArray(RequireConstraint[]::new)
			)
		);
	}

	/**
	 * Parses optional page spacing while preserving type validation for omitted children.
	 *
	 * @param ctx page syntax with a third child
	 * @return parsed spacing, or null when all gaps were omitted
	 */
	@Nullable
	private Spacing parseSpacing(@Nonnull PageConstraintContext ctx) {
		Assert.isTrue(
			ctx.args.requireConstraint().getStart().getText().equals("spacing"),
			() -> new EvitaSyntaxException(ctx, "Only `spacing` is accepted as third parameter of `page` "
				+ "require constraint!")
		);
		return visitChildConstraint(ctx.args.requireConstraint(), Spacing.class);
	}

}
