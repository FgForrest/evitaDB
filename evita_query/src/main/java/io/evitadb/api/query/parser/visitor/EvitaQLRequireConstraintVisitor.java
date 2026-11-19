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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
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
	/**
	 * Parses a leading value that may be a reference name or a managed-references behaviour.
	 */
	@Nonnull
	protected final EvitaQLValueTokenVisitor managedReferenceBehaviourValueTokenVisitor =
		EvitaQLValueTokenVisitor.withAllowedTypes(
			ManagedReferencesBehaviour.class, String.class, String[].class, Iterable.class
		);

	/**
	 * Parses a lone attribute-name argument, which can supply the entire list.
	 */
	@Nonnull
	private final EvitaQLValueTokenVisitor attributeNamesValueTokenVisitor =
		EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Object[].class, Iterable.class);
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
	/**
	 * Parses a list of strings. A bound array or collection is the whole list only when it is bound to a lone
	 * parameter, so it is not an allowed item of a longer sequence.
	 */
	@Nonnull
	protected final EvitaQLValueTokenVisitor stringValueListTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		String.class
	);
	protected final EvitaQLValueTokenVisitor scopeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(Scope.class);

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

	/**
	 * Parses reference content for all reference names.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitAllRefsReferenceContentConstraint(
		@Nonnull AllRefsReferenceContentConstraintContext ctx
	) {
		return parse(ctx, () -> {
			if (ctx.args == null) {
				return new ReferenceContent();
			}
			return parseAllRefsReferenceContent(
				ctx.args.managedReferencesBehaviour, false,
				ctx.args.requirement, ctx.args.entityRequirement, ctx.args.groupEntityRequirement
			);
		});
	}

	/**
	 * Parses variadic reference names and their fetch requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitMultipleRefsReferenceContentConstraint(
		@Nonnull MultipleRefsReferenceContentConstraintContext ctx
	) {
		return parse(ctx, () -> {
			final ValueTokenContext leading = ctx.args.managedReferencesBehaviour;
			final Object firstArg = leading == null ? null :
				leading.accept(this.managedReferenceBehaviourValueTokenVisitor).getActualValue();
			final ManagedReferencesBehaviour behaviour = leading == null || firstArg instanceof String
				? ManagedReferencesBehaviour.ANY
				: toManagedReferencesBehaviour(leading, firstArg);
			final String[] classifiers = ctx.args.classifiers.accept(this.stringValueListTokenVisitor).asStringArray();
			final String[] referenceNames = firstArg instanceof String name
				? ArrayUtils.mergeArrays(new String[]{name}, classifiers)
				: classifiers;
			ConstraintArguments.requireNonNullItems(referenceNames, "referenceNames");

			final ReferenceContentRequirements requirements = parseReferenceContentRequirements(
				false, ctx.args.requirement, ctx.args.entityRequirement, ctx.args.groupEntityRequirement
			);
			return new ReferenceContent(
				behaviour, referenceNames, requirements.entityFetch(), requirements.entityGroupFetch(),
				requirements.chunk()
			);
		});
	}

	/**
	 * Parses a single reference name with an optional managed-references behaviour.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent1Constraint(
		@Nonnull SingleRefReferenceContent1ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, null, false
		));
	}

	/**
	 * Parses a single reference name and multiple fetch or chunking requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent2Constraint(
		@Nonnull SingleRefReferenceContent2ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, null, false, ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered reference content with an optional fetch or chunking requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent3Constraint(
		@Nonnull SingleRefReferenceContent3ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, null, false, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered reference content with multiple fetch or chunking requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent4Constraint(
		@Nonnull SingleRefReferenceContent4ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, null, false,
			ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses ordered reference content with an optional fetch or chunking requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent5Constraint(
		@Nonnull SingleRefReferenceContent5ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, ctx.args.orderBy, false, ctx.args.requirement
		));
	}

	/**
	 * Parses ordered reference content with multiple fetch or chunking requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent6Constraint(
		@Nonnull SingleRefReferenceContent6ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, ctx.args.orderBy, false,
			ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered and ordered reference content with an optional fetch or chunking requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent7Constraint(
		@Nonnull SingleRefReferenceContent7ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, ctx.args.orderBy, false, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered and ordered reference content with multiple fetch or chunking requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContent8Constraint(
		@Nonnull SingleRefReferenceContent8ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, ctx.args.orderBy, false,
			ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses all reference names with attributes and at most one child requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitAllRefsWithAttributesReferenceContent1Constraint(
		@Nonnull AllRefsWithAttributesReferenceContent1ConstraintContext ctx
	) {
		return parse(ctx, () -> {
			if (ctx.args == null) {
				return new ReferenceContent(AttributeContent.ALL_ATTRIBUTES);
			}
			return parseAllRefsReferenceContent(ctx.args.managedReferencesBehaviour, true, ctx.args.requirement);
		});
	}

	/**
	 * Parses all reference names with attributes and two child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitAllRefsWithAttributesReferenceContent2Constraint(
		@Nonnull AllRefsWithAttributesReferenceContent2ConstraintContext ctx
	) {
		return parse(ctx, () -> parseAllRefsReferenceContent(
			ctx.args.managedReferencesBehaviour, true, ctx.args.requirement1, ctx.args.requirement2
		));
	}

	/**
	 * Parses all reference names with attributes and three or four child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitAllRefsWithAttributesReferenceContent3Constraint(
		@Nonnull AllRefsWithAttributesReferenceContent3ConstraintContext ctx
	) {
		return parse(ctx, () -> parseAllRefsReferenceContent(
			ctx.args.managedReferencesBehaviour, true,
			ctx.args.attributeContent, ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses reference content with attributes and a single child requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes0Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes0ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, null, true, ctx.args.requirement
		));
	}

	/**
	 * Parses the scalar overloads for a reference name and optional attribute names.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes1Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes1ConstraintContext ctx
	) {
		return parse(ctx, () -> {
			final ValueTokenContext first = ctx.args.first;
			final List<ValueTokenContext> values = ctx.args.values;
			if (values.isEmpty()) {
				return QueryConstraints.referenceContentWithAttributes(parseReferenceName(first));
			}

			final Object firstArg = first.accept(this.managedReferenceBehaviourValueTokenVisitor).getActualValue();
			if (firstArg instanceof String name) {
				final String referenceName = validateName(first, name, "referenceName");
				return QueryConstraints.referenceContentWithAttributes(
					referenceName, parseReferenceAttributeNames(values)
				);
			}

			final ManagedReferencesBehaviour behaviour = toManagedReferencesBehaviour(first, firstArg);
			final String referenceName = parseReferenceName(values.get(0));
			return values.size() == 1
				? QueryConstraints.referenceContentWithAttributes(behaviour, referenceName)
				: QueryConstraints.referenceContentWithAttributes(
					behaviour, referenceName, parseReferenceAttributeNames(values.subList(1, values.size()))
				);
		});
	}

	/**
	 * Parses reference content with attributes and two child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes2Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes2ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, null, true, ctx.args.requirement1, ctx.args.requirement2
		));
	}

	/**
	 * Parses reference content with attributes and three or four child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes3Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes3ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, null, true,
			ctx.args.attributeContent, ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered reference content with attributes and an optional child requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes4Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes4ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, null, true, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered reference content with attributes and two child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes5Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes5ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, null, true, ctx.args.requirement1, ctx.args.requirement2
		));
	}

	/**
	 * Parses filtered reference content with attributes and three or four child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes6Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes6ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, null, true,
			ctx.args.attributeContent, ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses ordered reference content with attributes and an optional child requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes7Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes7ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, ctx.args.orderBy, true, ctx.args.requirement
		));
	}

	/**
	 * Parses ordered reference content with attributes and two child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes8Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes8ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, ctx.args.orderBy, true, ctx.args.requirement1, ctx.args.requirement2
		));
	}

	/**
	 * Parses ordered reference content with attributes and three or four child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes9Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes9ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			null, ctx.args.orderBy, true,
			ctx.args.attributeContent, ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered and ordered reference content with attributes and an optional child requirement.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes10Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes10ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, ctx.args.orderBy, true, ctx.args.requirement
		));
	}

	/**
	 * Parses filtered and ordered reference content with attributes and two child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes11Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes11ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, ctx.args.orderBy, true, ctx.args.requirement1, ctx.args.requirement2
		));
	}

	/**
	 * Parses filtered and ordered reference content with attributes and three or four child requirements.
	 *
	 * @param ctx reference requirement syntax
	 * @return parsed reference content requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitSingleRefReferenceContentWithAttributes12Constraint(
		@Nonnull SingleRefReferenceContentWithAttributes12ConstraintContext ctx
	) {
		return parse(ctx, () -> parseSingleReferenceContent(
			ctx.args.managedReferencesBehaviour, ctx.args.classifier,
			ctx.args.filterBy, ctx.args.orderBy, true,
			ctx.args.attributeContent, ctx.args.entityRequirement, ctx.args.groupEntityRequirement, ctx.args.requirement
		));
	}

	/**
	 * Parses the leading values followed by the filter, order, and children of a single-reference requirement.
	 *
	 * @param behaviourArg optional managed-references behaviour
	 * @param classifier reference name token
	 * @param filterArg optional reference filter
	 * @param orderArg optional reference order
	 * @param withAttributes whether to fetch reference attributes
	 * @param children the child requirement contexts in query order
	 * @return parsed reference content requirement
	 */
	@Nonnull
	private ReferenceContent parseSingleReferenceContent(
		@Nullable ValueTokenContext behaviourArg,
		@Nonnull ValueTokenContext classifier,
		@Nullable FilterConstraintContext filterArg,
		@Nullable OrderConstraintContext orderArg,
		boolean withAttributes,
		@Nonnull RequireConstraintContext... children
	) {
		final ManagedReferencesBehaviour behaviour;
		if (behaviourArg == null) {
			behaviour = ManagedReferencesBehaviour.ANY;
		} else {
			final Object firstArg = behaviourArg.accept(this.managedReferenceBehaviourValueTokenVisitor)
				.getActualValue();
			if (firstArg instanceof String) {
				throw new EvitaSyntaxException(
					classifier, "Unexpected second value `" + classifier.getText()
						+ "`: a single reference name is required before these constraints."
				);
			}
			behaviour = toManagedReferencesBehaviour(behaviourArg, firstArg);
		}
		final String referenceName = parseReferenceName(classifier);
		final FilterBy filterBy = filterArg == null ? null :
			visitChildConstraint(this.filterConstraintVisitor, filterArg, FilterBy.class);
		final OrderBy orderBy = orderArg == null ? null :
			visitChildConstraint(this.orderConstraintVisitor, orderArg, OrderBy.class);
		final ReferenceContentRequirements requirements = parseReferenceContentRequirements(withAttributes, children);
		return new ReferenceContent(
			behaviour, referenceName, filterBy, orderBy, requirements.attributes(),
			requirements.entityFetch(), requirements.entityGroupFetch(), requirements.chunk()
		);
	}

	/**
	 * Parses the behaviour and child requirements of an all-references requirement.
	 *
	 * @param behaviourArg optional managed-references behaviour
	 * @param withAttributes whether to fetch reference attributes
	 * @param children the child requirement contexts in query order
	 * @return parsed reference content requirement
	 */
	@Nonnull
	private ReferenceContent parseAllRefsReferenceContent(
		@Nullable ValueTokenContext behaviourArg,
		boolean withAttributes,
		@Nonnull RequireConstraintContext... children
	) {
		final ManagedReferencesBehaviour behaviour = behaviourArg == null ? ManagedReferencesBehaviour.ANY :
			toManagedReferencesBehaviour(
				behaviourArg, behaviourArg.accept(this.managedReferenceBehaviourValueTokenVisitor).getActualValue()
			);
		final ReferenceContentRequirements requirements = parseReferenceContentRequirements(withAttributes, children);
		return new ReferenceContent(
			behaviour, requirements.attributes(), requirements.entityFetch(), requirements.entityGroupFetch(),
			requirements.chunk()
		);
	}

	/**
	 * Visits each present child in query order. Factory overloads place attribute content, entity fetch,
	 * group fetch and chunking in that order, with at most one of each kind.
	 *
	 * @param withAttributes whether attribute content is supported and defaults to all attributes
	 * @param children child contexts in query order, including nulls for absent grammar arguments
	 * @return the visited child requirements
	 */
	@Nonnull
	private ReferenceContentRequirements parseReferenceContentRequirements(
		boolean withAttributes,
		@Nonnull RequireConstraintContext... children
	) {
		AttributeContent attributes = null;
		EntityFetch entityFetch = null;
		EntityGroupFetch entityGroupFetch = null;
		ChunkingRequireConstraint chunk = null;
		for (final RequireConstraintContext child : children) {
			if (child == null) {
				continue;
			}
			final String childName = child.getStart().getText();
			if (chunk != null) {
				throw new EvitaSyntaxException(
					child, "Unexpected requirement `" + childName + "` after paging constraint `"
						+ chunk.getName() + "`."
				);
			}
			if (child instanceof AttributeContentConstraintContext && withAttributes
				&& attributes == null && entityFetch == null && entityGroupFetch == null) {
				attributes = visitChildConstraint(child, AttributeContent.class);
			} else if (child instanceof EntityFetchConstraintContext
				&& entityFetch == null && entityGroupFetch == null) {
				entityFetch = visitChildConstraint(child, EntityFetch.class);
			} else if (child instanceof EntityGroupFetchConstraintContext && entityGroupFetch == null) {
				entityGroupFetch = visitChildConstraint(child, EntityGroupFetch.class);
			} else if (child instanceof PageConstraintContext || child instanceof StripConstraintContext) {
				chunk = visitChildConstraint(child, ChunkingRequireConstraint.class);
			} else {
				throw new EvitaSyntaxException(
					child, "Unexpected requirement `" + childName + "` in reference content."
				);
			}
		}
		return new ReferenceContentRequirements(
			withAttributes && attributes == null ? AttributeContent.ALL_ATTRIBUTES : attributes,
			entityFetch, entityGroupFetch, chunk
		);
	}

	/**
	 * Resolves the leading value as a managed-references behaviour.
	 *
	 * @param arg syntax of the behaviour value
	 * @param value visited argument, including null for the default
	 * @return behaviour selected by the argument
	 */
	@Nonnull
	private static ManagedReferencesBehaviour toManagedReferencesBehaviour(
		@Nonnull ValueTokenContext arg,
		@Nullable Object value
	) {
		if (value == null) {
			return ManagedReferencesBehaviour.ANY;
		} else if (value instanceof ManagedReferencesBehaviour behaviour) {
			return behaviour;
		} else if (value instanceof EnumWrapper wrapper && wrapper.canBeMappedTo(ManagedReferencesBehaviour.class)) {
			return wrapper.toEnum(ManagedReferencesBehaviour.class);
		}
		throw new EvitaSyntaxException(
			arg, "Unexpected argument `" + arg.getText() + "`: expected a managed-references behaviour."
		);
	}

	/**
	 * Visits a value token and validates it as a required reference name.
	 *
	 * @param classifier reference name token
	 * @return parsed reference name
	 */
	@Nonnull
	private String parseReferenceName(@Nonnull ValueTokenContext classifier) {
		return validateName(classifier, classifier.accept(this.stringValueTokenVisitor).asString(), "referenceName");
	}

	/**
	 * Applies the Java factory's name validation and reports an invalid name at the value token.
	 *
	 * @param arg syntax of the name
	 * @param name parsed name
	 * @param argumentName name of the Java factory parameter
	 * @return validated name
	 */
	@Nonnull
	private static String validateName(
		@Nonnull ValueTokenContext arg,
		@Nullable String name,
		@Nonnull String argumentName
	) {
		try {
			return ConstraintArguments.requireName(name, argumentName);
		} catch (EvitaInvalidUsageException ex) {
			throw new EvitaSyntaxException(arg, ex.getPublicMessage());
		}
	}

	/**
	 * Parses the attribute names after the reference name. A lone parameter can bind the entire list.
	 *
	 * @param values attribute name tokens in query order
	 * @return selected attribute names
	 */
	@Nonnull
	private String[] parseReferenceAttributeNames(@Nonnull List<ValueTokenContext> values) {
		if (values.size() == 1) {
			final ValueTokenContext arg = values.get(0);
			final Object value = arg.accept(this.attributeNamesValueTokenVisitor).getActualValue();
			final String[] names = this.stringValueListTokenVisitor
				.parseVariadicArguments(arg, value).asStringArray();
			try {
				ConstraintArguments.requireNonNullItems(names, "attributeNames");
			} catch (EvitaInvalidUsageException ex) {
				throw new EvitaSyntaxException(arg, ex.getPublicMessage());
			}
			return names;
		}
		final String[] names = new String[values.size()];
		for (int i = 0; i < names.length; i++) {
			final ValueTokenContext arg = values.get(i);
			names[i] = validateName(arg, arg.accept(this.stringValueTokenVisitor).asString(), "attributeName");
		}
		return names;
	}

	/**
	 * The parsed children of a reference content requirement.
	 *
	 * @param attributes reference attribute selection, or null for plain reference content
	 * @param entityFetch referenced entity fetch, when requested
	 * @param entityGroupFetch referenced group entity fetch, when requested
	 * @param chunk paging constraint, when requested
	 */
	private record ReferenceContentRequirements(
		@Nullable AttributeContent attributes,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ChunkingRequireConstraint chunk
	) {}

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
						ctx.args.classifier.accept(this.stringValueTokenVisitor).asString()
					);
				} else {
					return QueryConstraints.accompanyingPriceContent(
						ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
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

	/**
	 * Parses histogram statistics without an entity fetch.
	 *
	 * @param ctx histogram statistics constraint
	 * @return parsed histogram statistics, or null when no index names are provided
	 */
	@Nullable
	@Override
	public RequireConstraint visitHistogramStatistics1Constraint(@Nonnull HistogramStatistics1ConstraintContext ctx) {
		return parse(
			ctx,
			() -> buildHistogramStatistics(ctx, ctx.args.requestedBucketCount, null, null, ctx.args.values, null)
		);
	}

	/**
	 * Parses histogram statistics with an entity fetch on either side of the index names.
	 *
	 * @param ctx histogram statistics constraint
	 * @return parsed histogram statistics, or null when no index names are provided
	 */
	@Nullable
	@Override
	public RequireConstraint visitHistogramStatistics2Constraint(@Nonnull HistogramStatistics2ConstraintContext ctx) {
		return parse(
			ctx,
			() -> buildHistogramStatistics(
				ctx, ctx.args.requestedBucketCount, ctx.args.behavior, ctx.args.fetchBeforeValues,
				ctx.args.values, ctx.args.fetchAfterValues
			)
		);
	}

	/**
	 * Common builder for {@link ReferenceHistogramStatistics}. The variadic value list may carry an
	 * optional {@link HistogramBehavior} enum as its first element (matching the in-memory constructor
	 * shape shared with {@link AttributeHistogram}), followed by histogram index names. The behavior can also
	 * precede a fetch that comes before the index names. An empty bound list omits the requirement. A leading null
	 * in an explicit argument sequence stands for the default behavior.
	 * The arguments are visited in the order they appear in the query, because each positional parameter takes the
	 * next bound value when it is visited.
	 *
	 * @param ctx histogram statistics constraint
	 * @param requestedBucketCountArg requested bucket count token
	 * @param behaviorArg optional behavior token preceding the fetch
	 * @param fetchBeforeValuesArg optional fetch preceding the value list
	 * @param valuesArg optional behavior followed by histogram index names
	 * @param fetchAfterValuesArg optional fetch following the value list
	 * @return the histogram statistics requirement, or null when no index names are provided
	 */
	@Nullable
	private ReferenceHistogramStatistics buildHistogramStatistics(
		@Nonnull RequireConstraintContext ctx,
		@Nonnull ValueTokenContext requestedBucketCountArg,
		@Nullable ValueTokenContext behaviorArg,
		@Nullable RequireConstraintContext fetchBeforeValuesArg,
		@Nonnull VariadicValueTokensContext valuesArg,
		@Nullable RequireConstraintContext fetchAfterValuesArg
	) {
		final int requestedBucketCount = requestedBucketCountArg.accept(this.intValueTokenVisitor).asRequiredInt();
		final HistogramBehavior explicitBehavior = behaviorArg == null ? null :
			behaviorArg.accept(this.histogramBehaviorValueTokenVisitor).asEnum(HistogramBehavior.class);
		final EntityFetch fetchBeforeValues = fetchBeforeValuesArg == null ?
			null : visitChildConstraint(fetchBeforeValuesArg, EntityFetch.class);

		final LinkedList<Serializable> args = Arrays.stream(valuesArg
				.accept(this.attributeHistogramArgValueTokenVisitor)
				.asSerializableArray())
			.collect(Collectors.toCollection(LinkedList::new));

		final HistogramBehavior behavior;
		if (behaviorArg != null) {
			behavior = explicitBehavior;
		} else {
			consumeLeadingNullEnumDefault(valuesArg, args);
			final Serializable firstArgument = args.peekFirst();
			if (firstArgument instanceof HistogramBehavior histogramBehavior) {
				behavior = histogramBehavior;
				args.pop();
			} else if (firstArgument instanceof EnumWrapper enumWrapper &&
				enumWrapper.canBeMappedTo(HistogramBehavior.class)) {
				behavior = enumWrapper.toEnum(HistogramBehavior.class);
				args.pop();
			} else {
				behavior = null;
			}
		}

		final String[] indexNames = parseVariadicTail(ctx, args, this.stringValueTokenVisitor).asStringArray();
		final EntityFetch entityFetch = fetchAfterValuesArg == null ?
			fetchBeforeValues : visitChildConstraint(fetchAfterValuesArg, EntityFetch.class);

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

	/**
	 * Parses the histogram bucket count, optional behavior and attribute names.
	 *
	 * @param ctx attribute histogram constraint
	 * @return the histogram requirement, or null when no attribute names are provided
	 */
	@Nullable
	@Override
	public RequireConstraint visitAttributeHistogramConstraint(@Nonnull AttributeHistogramConstraintContext ctx) {
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
				consumeLeadingNullEnumDefault(ctx.args.values, args);
				final Serializable secondArgument = args.peekFirst();
				if (secondArgument instanceof HistogramBehavior histogramBehavior) {
					behavior = histogramBehavior;
					args.pop();
				} else if (secondArgument instanceof EnumWrapper enumWrapper &&
					enumWrapper.canBeMappedTo(HistogramBehavior.class)) {
					behavior = enumWrapper.toEnum(HistogramBehavior.class);
					args.pop();
				} else {
					behavior = null;
				}

				final String[] attributesNames = parseVariadicTail(ctx, args, this.stringValueTokenVisitor)
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

	/**
	 * Parses the optional statistics base and the remaining statistics types.
	 *
	 * @param ctx hierarchy statistics constraint
	 * @return the statistics requirement
	 */
	@Nonnull
	@Override
	public RequireConstraint visitHierarchyStatisticsConstraint(@Nonnull HierarchyStatisticsConstraintContext ctx) {
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
				if (first instanceof StatisticsBase statisticsBase) {
					settings.removeFirst();
					base = statisticsBase;
				} else if (first instanceof EnumWrapper wrapper && wrapper.canBeMappedTo(StatisticsBase.class)) {
					settings.removeFirst();
					base = wrapper.toEnum(StatisticsBase.class);
				} else {
					base = StatisticsBase.WITHOUT_USER_FILTER;
				}
				return QueryConstraints.statistics(base, parseVariadicTail(ctx, settings,
					EvitaQLValueTokenVisitor.withAllowedTypes(StatisticsType.class, EnumWrapper.class))
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
