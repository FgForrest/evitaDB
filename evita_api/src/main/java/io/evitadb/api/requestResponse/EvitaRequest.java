/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.requestResponse.chunk.PageTransformer;
import io.evitadb.api.requestResponse.chunk.StripTransformer;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Evita request serves as simple DTO that streamlines and caches access to the input {@link Query}.
 *
 * {@link EvitaRequest} is internal class (Evita accepts simple
 * {@link Query} object -
 * see {@link EvitaSessionContract#query(Query, Class)}) that
 * envelopes the input query. Evita request can be used to implement
 * methods that extract crucial information from the input query and
 * cache those extracted information to avoid paying parsing costs
 * twice in single request.
 *
 * Besides caching, the request also **normalizes** the entity fetch: the content requirements the client wrote side
 * by side are folded once, by {@link EntityFetchRequire#combineDuplicateRequirements()}, so that every per-kind
 * accessor below sees at most one requirement of each kind and can keep looking it up with a single-result lookup.
 * `getQuery()` is deliberately left out of that normalization and keeps the client's query verbatim, because
 * traffic recording and query printing have to reproduce what was actually sent.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see EvitaSessionContract#query(Query, Class)
 * @see EvitaResponse examples in super class
 */
public class EvitaRequest {
	private static final ConditionalGap[] EMPTY_GAPS = new ConditionalGap[0];

	/**
	 * The query as the client wrote it, never the reduced form. Duplicate content requirements are folded only into
	 * {@link #getEntityRequirement()}, so that traffic recording and query printing reproduce the original input.
	 */
	@Getter private final Query query;
	@Getter private final OffsetDateTime alignedNow;
	@Nullable private final String entityType;
	@Nullable private final Locale implicitLocale;
	@Getter private final Class<?> expectedType;
	@Nullable private Label[] labels;
	@Nullable private int[] primaryKeys;
	private boolean localeExamined;
	@Nullable private Locale locale;
	@Nullable private Boolean requiredLocales;
	@Nullable private Set<Locale> requiredLocaleSet;
	@Nullable private QueryPriceMode queryPriceMode;
	@Nullable private Boolean priceValidInTimeSet;
	@Nullable private OffsetDateTime priceValidInTime;
	@Nullable private Boolean requiresEntity;
	@Nullable private Boolean requiresParent;
	@Nullable private HierarchyContent parentContent;
	@Nullable private EntityFetch entityRequirement;
	@Nullable private Boolean entityAttributes;
	@Nullable private Set<String> entityAttributeSet;
	@Nullable private Boolean entityAssociatedData;
	@Nullable private Set<String> entityAssociatedDataSet;
	@Nullable private Boolean entityReference;
	@Nullable private PriceContentMode entityPrices;
	@Nullable private Boolean currencySet;
	@Nullable private Currency currency;
	@Nullable private Boolean requiresPriceLists;
	@Nullable private String[] priceLists;
	@Nullable private String[] additionalPriceLists;
	@Nullable private String[] defaultAccompanyingPricePriceLists;
	@Nullable private AccompanyingPrice[] accompanyingPrices;
	@Nullable private Integer start;
	@Nullable private ConditionalGap[] conditionalGaps;
	@Nullable private Map<String, List<HierarchyFilterConstraint>> hierarchyWithin;
	@Nullable private Boolean requiredWithinHierarchy;
	@Nullable private Boolean requiresHierarchyStatistics;
	@Nullable private Boolean requiresHierarchyParents;
	@Nullable private Integer limit;
	@Nullable private EvitaRequest.ResultForm resultForm;
	@Nullable private FacetRelationType defaultFacetRelationType;
	@Nullable private FacetRelationType defaultGroupRelationType;
	@Nullable private Map<FacetGroupRelationKey, FacetFilterBy> facetGroupConjunction;
	@Nullable private Map<FacetGroupRelationKey, FacetFilterBy> facetGroupDisjunction;
	@Nullable private Map<FacetGroupRelationKey, FacetFilterBy> facetGroupNegation;
	@Nullable private Map<FacetGroupRelationKey, FacetFilterBy> facetGroupExclusivity;
	@Nullable private Boolean queryTelemetryRequested;
	@Nullable private Boolean queryTelemetryPlanRequested;
	@Nullable private Boolean priceHistogramRequested;
	@Nullable private EnumSet<DebugMode> debugModes;
	@Nullable private Scope[] scopesAsArray;
	@Nullable private Set<Scope> scopes;
	@Nullable private Map<String, RequirementContext> entityFetchRequirements;
	@Nullable private Map<ReferenceContentKey, RequirementContext> namedEntityFetchRequirements;
	@Nullable private RequirementContext defaultReferenceRequirement;
	@Nullable private Function<String, ChunkTransformer> referenceChunkTransformer;

	/**
	 * Converts a {@link Spacing} constraint into an array of
	 * {@link ConditionalGap} instances. Returns {@link #EMPTY_GAPS}
	 * when the spacing is null or contains no gaps.
	 *
	 * @param spacing the spacing constraint, or null if absent
	 * @return array of conditional gaps derived from the spacing
	 */
	@Nonnull
	private static ConditionalGap[] convertSpacingToGaps(@Nullable Spacing spacing) {
		if (spacing == null) {
			return EMPTY_GAPS;
		}
		final SpacingGap[] gaps = spacing.getGaps();
		if (gaps.length == 0) {
			return EMPTY_GAPS;
		}
		final ConditionalGap[] result = new ConditionalGap[gaps.length];
		for (int i = 0; i < gaps.length; i++) {
			final SpacingGap gap = gaps[i];
			result[i] = new ConditionalGap(gap.getSize(), gap.getOnPage());
		}
		return result;
	}

	/**
	 * Parses the requirement context from the passed
	 * {@link ReferenceContent} and {@link AttributeContent}.
	 */
	@Nonnull
	private static RequirementContext getRequirementContext(
		@Nonnull ReferenceContent referenceContent,
		@Nullable AttributeContent attributeContent
	) {
		return new RequirementContext(
			referenceContent.getManagedReferencesBehaviour(),
			attributeContent,
			referenceContent.getEntityRequirement().orElse(null),
			referenceContent.getGroupEntityRequirement().orElse(null),
			referenceContent.getFilterBy().orElse(null),
			referenceContent.getOrderBy().orElse(null),
			referenceContent.getChunking()
				.map(chunking -> {
					if (chunking instanceof Page page) {
						return new PageTransformer(
							page,
							convertSpacingToGaps(page.getSpacing().orElse(null))
						);
					} else if (chunking instanceof Strip strip) {
						return new StripTransformer(strip);
					} else {
						throw new EvitaInvalidUsageException(
							"Unsupported chunking type: " +
								chunking.getClass().getSimpleName()
						);
					}
				})
				.orElse(NoTransformer.INSTANCE)
		);
	}

	/**
	 * Primary constructor that creates a new evita request from
	 * the given query, current time, expected result type, and
	 * optional entity type derived from the expected type.
	 *
	 * @param query                    the input query
	 * @param alignedNow               the current aligned time
	 * @param expectedType             the expected result type
	 * @param entityTypeByExpectedType optional entity type name
	 */
	public EvitaRequest(
		@Nonnull Query query,
		@Nonnull OffsetDateTime alignedNow,
		@Nonnull Class<?> expectedType,
		@Nullable String entityTypeByExpectedType
	) {
		final Collection header = query.getCollection();
		this.entityType = ofNullable(header)
			.map(Collection::getEntityType)
			.orElse(entityTypeByExpectedType);
		this.query = query;
		this.alignedNow = alignedNow;
		this.implicitLocale = null;
		this.expectedType = expectedType;
	}

	/**
	 * Copy constructor that creates a new request from an existing
	 * one with an additional implicit locale. All memoized values
	 * are preserved from the original request.
	 *
	 * @param evitaRequest   the original request to copy from
	 * @param implicitLocale the implicit locale to set
	 */
	public EvitaRequest(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull Locale implicitLocale
	) {
		this.entityType = evitaRequest.entityType;
		this.query = evitaRequest.query;
		this.labels = evitaRequest.labels;
		this.alignedNow = evitaRequest.alignedNow;
		this.implicitLocale = implicitLocale;
		this.primaryKeys = evitaRequest.primaryKeys;
		this.localeExamined = evitaRequest.localeExamined;
		this.locale = evitaRequest.locale;
		this.requiredLocales = evitaRequest.requiredLocales;
		this.requiredLocaleSet = evitaRequest.requiredLocaleSet;
		this.queryPriceMode = evitaRequest.queryPriceMode;
		this.priceValidInTimeSet = evitaRequest.priceValidInTimeSet;
		this.priceValidInTime = evitaRequest.priceValidInTime;
		this.entityAttributes = evitaRequest.entityAttributes;
		this.entityAttributeSet = evitaRequest.entityAttributeSet;
		this.entityAssociatedData = evitaRequest.entityAssociatedData;
		this.entityAssociatedDataSet = evitaRequest.entityAssociatedDataSet;
		this.entityReference = evitaRequest.entityReference;
		this.entityFetchRequirements = evitaRequest.entityFetchRequirements;
		this.namedEntityFetchRequirements = evitaRequest.namedEntityFetchRequirements;
		this.defaultReferenceRequirement = evitaRequest.defaultReferenceRequirement;
		this.entityPrices = evitaRequest.entityPrices;
		this.currencySet = evitaRequest.currencySet;
		this.currency = evitaRequest.currency;
		this.requiresPriceLists = evitaRequest.requiresPriceLists;
		this.additionalPriceLists = evitaRequest.additionalPriceLists;
		this.defaultAccompanyingPricePriceLists = evitaRequest.defaultAccompanyingPricePriceLists;
		this.accompanyingPrices = evitaRequest.accompanyingPrices;
		this.priceLists = evitaRequest.priceLists;
		this.start = evitaRequest.start;
		this.conditionalGaps = evitaRequest.conditionalGaps;
		this.hierarchyWithin = evitaRequest.hierarchyWithin;
		this.requiredWithinHierarchy = evitaRequest.requiredWithinHierarchy;
		this.requiresHierarchyStatistics = evitaRequest.requiresHierarchyStatistics;
		this.requiresHierarchyParents = evitaRequest.requiresHierarchyParents;
		this.limit = evitaRequest.limit;
		this.resultForm = evitaRequest.resultForm;
		this.facetGroupConjunction = evitaRequest.facetGroupConjunction;
		this.facetGroupDisjunction = evitaRequest.facetGroupDisjunction;
		this.facetGroupNegation = evitaRequest.facetGroupNegation;
		this.facetGroupExclusivity = evitaRequest.facetGroupExclusivity;
		this.requiresEntity = evitaRequest.requiresEntity;
		this.requiresParent = evitaRequest.requiresParent;
		this.parentContent = evitaRequest.parentContent;
		this.entityRequirement = evitaRequest.entityRequirement;
		this.expectedType = evitaRequest.expectedType;
		this.debugModes = evitaRequest.debugModes;
		this.scopes = evitaRequest.scopes;
		this.scopesAsArray = evitaRequest.scopesAsArray;
	}

	/**
	 * Derived copy constructor that creates a new request from an
	 * existing one with a different entity type, optional filter/order
	 * constraints, and entity fetch requirements. Memoized values
	 * that depend on the changed parts are reset.
	 *
	 * @param evitaRequest the original request to derive from
	 * @param entityType   the new entity type (may be null)
	 * @param filterBy     optional filter constraints override
	 * @param orderBy      optional order constraints override
	 * @param requirements the entity fetch requirements; they are reduced by
	 *                     {@link EntityFetchRequire#combineDuplicateRequirements()} before they are stored and before
	 *                     they are written into the derived query, so the derived request is described by at most one
	 *                     requirement of each kind
	 * @throws EvitaInvalidUsageException when two of the passed requirements address the same thing but contradict
	 *                                    each other
	 */
	public EvitaRequest(
		@Nonnull EvitaRequest evitaRequest,
		@Nullable String entityType,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nonnull EntityFetchRequire requirements
	) {
		this.requiresEntity = true;
		// the fetch is reduced before it is stored and before it is written into the derived query, so that every
		// consumer - including the nested query built below - sees at most one requirement of each kind
		this.entityRequirement = new EntityFetch(requirements.getRequirements())
			.combineDuplicateRequirements();
		this.entityType = entityType;
		this.query = entityType == null ?
			Query.query(
				evitaRequest.query.getHead() == null ?
					null :
					ConstraintCloneVisitor.clone(
						evitaRequest.query.getHead(),
						(constraintCloneVisitor, constraint) ->
							constraint instanceof Collection ? null : constraint
					),
				filterBy == null ? evitaRequest.query.getFilterBy() : filterBy,
				orderBy == null ? evitaRequest.query.getOrderBy() : orderBy,
				require(this.entityRequirement)
			) :
			Query.query(
				evitaRequest.query.getHead() == null ?
					null :
					ConstraintCloneVisitor.clone(
						evitaRequest.query.getHead(),
						(constraintCloneVisitor, constraint) ->
							constraint instanceof Collection ?
								collection(entityType) : constraint
					),
				filterBy == null ? evitaRequest.query.getFilterBy() : filterBy,
				orderBy == null ? evitaRequest.query.getOrderBy() : orderBy,
				require(this.entityRequirement)
			);
		this.labels = evitaRequest.labels;
		this.alignedNow = evitaRequest.alignedNow;
		this.implicitLocale = evitaRequest.implicitLocale;
		this.primaryKeys = evitaRequest.primaryKeys;
		if (filterBy != null) {
			this.localeExamined = true;
			this.locale = ofNullable(QueryUtils.findConstraint(filterBy, EntityLocaleEquals.class))
				.map(EntityLocaleEquals::getLocale)
				.orElseGet(evitaRequest::getLocale);
		} else {
			this.localeExamined = evitaRequest.localeExamined;
			this.locale = evitaRequest.locale;
		}
		boolean hasDataInLocales = false;
		for (final EntityContentRequire req : requirements.getRequirements()) {
			if (req instanceof DataInLocales) {
				hasDataInLocales = true;
				break;
			}
		}
		if (hasDataInLocales) {
			this.requiredLocales = null;
			this.requiredLocaleSet = null;
		} else {
			this.requiredLocales = evitaRequest.requiredLocales;
			this.requiredLocaleSet = this.locale == null ?
				evitaRequest.requiredLocaleSet : Set.of(this.locale);
		}
		this.queryPriceMode = ofNullable(QueryUtils.findRequire(this.query, PriceType.class))
			.map(PriceType::getQueryPriceMode)
			.orElse(evitaRequest.queryPriceMode);
		if (filterBy != null) {
			// prefer valid in from the current filter
			final List<PriceValidIn> priceValidInConstraints =
				QueryUtils.findConstraints(filterBy, PriceValidIn.class);
			OffsetDateTime foundValidIn = null;
			boolean foundAnyValidIn = false;
			for (final PriceValidIn pvi : priceValidInConstraints) {
				final OffsetDateTime moment = pvi.getTheMoment(this::getAlignedNow);
				if (!foundAnyValidIn) {
					foundValidIn = moment;
					foundAnyValidIn = true;
				} else {
					Assert.isTrue(
						Objects.equals(foundValidIn, moment),
						"Query can not contain more than one price validity constraints!"
					);
				}
			}
			if (!foundAnyValidIn) {
				this.priceValidInTimeSet = evitaRequest.priceValidInTimeSet;
				this.priceValidInTime = evitaRequest.priceValidInTime;
			} else {
				this.priceValidInTimeSet = true;
				this.priceValidInTime = foundValidIn;
			}

			// prefer currencies from the current filter
			final List<PriceInCurrency> currencyConstraints =
				QueryUtils.findConstraints(filterBy, PriceInCurrency.class);
			Currency foundCurrency = null;
			boolean foundAnyCurrency = false;
			for (final PriceInCurrency pic : currencyConstraints) {
				final Currency curr = pic.getCurrency();
				if (!foundAnyCurrency) {
					foundCurrency = curr;
					foundAnyCurrency = true;
				} else {
					Assert.isTrue(
						Objects.equals(foundCurrency, curr),
						"Query can not contain more than one currency filtering constraints!"
					);
				}
			}
			if (!foundAnyCurrency) {
				this.currencySet = evitaRequest.currencySet;
				this.currency = evitaRequest.currency;
			} else {
				this.currency = foundCurrency;
				this.currencySet = true;
			}

			// prefer price lists from the current filter
			final List<PriceInPriceLists> priceInPriceLists =
				QueryUtils.findConstraints(filterBy, PriceInPriceLists.class);
			Assert.isTrue(
				priceInPriceLists.size() <= 1,
				"Query can not contain more than one price in price lists filter constraints!"
			);
			if (priceInPriceLists.isEmpty()) {
				this.priceLists = evitaRequest.priceLists;
				this.requiresPriceLists = evitaRequest.requiresPriceLists;
			} else {
				this.priceLists = priceInPriceLists.get(0).getPriceLists();
				this.requiresPriceLists = true;
			}
		} else {
			this.priceValidInTimeSet = evitaRequest.priceValidInTimeSet;
			this.priceValidInTime = evitaRequest.priceValidInTime;
			this.currencySet = evitaRequest.currencySet;
			this.currency = evitaRequest.currency;
			this.priceLists = evitaRequest.priceLists;
			this.requiresPriceLists = evitaRequest.requiresPriceLists;
		}
		this.requiresParent = null;
		this.parentContent = null;
		this.entityAttributes = null;
		this.entityAttributeSet = null;
		this.entityAssociatedData = null;
		this.entityAssociatedDataSet = null;
		this.entityReference = null;
		this.entityFetchRequirements = null;
		this.namedEntityFetchRequirements = null;
		this.defaultReferenceRequirement = null;
		this.entityPrices = null;
		this.accompanyingPrices = null;
		this.defaultAccompanyingPricePriceLists = evitaRequest.defaultAccompanyingPricePriceLists;
		this.additionalPriceLists = evitaRequest.additionalPriceLists;
		this.start = evitaRequest.start;
		this.conditionalGaps = evitaRequest.conditionalGaps;
		this.hierarchyWithin = evitaRequest.hierarchyWithin;
		this.requiredWithinHierarchy = evitaRequest.requiredWithinHierarchy;
		this.requiresHierarchyStatistics = evitaRequest.requiresHierarchyStatistics;
		this.requiresHierarchyParents = evitaRequest.requiresHierarchyParents;
		this.limit = evitaRequest.limit;
		this.resultForm = evitaRequest.resultForm;
		this.facetGroupConjunction = evitaRequest.facetGroupConjunction;
		this.facetGroupDisjunction = evitaRequest.facetGroupDisjunction;
		this.facetGroupNegation = evitaRequest.facetGroupNegation;
		this.facetGroupExclusivity = evitaRequest.facetGroupExclusivity;
		this.expectedType = evitaRequest.expectedType;
		this.debugModes = evitaRequest.debugModes;

		final EntityScope overriddenScopes = QueryUtils.findFilter(
			this.query, EntityScope.class, SeparateEntityScopeContainer.class
		);
		if (overriddenScopes == null) {
			this.scopes = evitaRequest.scopes;
			this.scopesAsArray = evitaRequest.scopesAsArray;
		} else {
			this.scopes = overriddenScopes.getScope();
			this.scopesAsArray = this.scopes.toArray(Scope[]::new);
		}
	}

	/**
	 * Derived copy constructor that creates a new request from an
	 * existing one with a different entity type, optional
	 * filter/order constraints, locale, and scopes. Most memoized
	 * values are reset except those explicitly copied from the
	 * original request.
	 *
	 * @param evitaRequest the original request to derive from
	 * @param entityType   the new entity type
	 * @param filterBy     optional filter constraints override
	 * @param orderBy      optional order constraints override
	 * @param locale       optional locale override
	 * @param scopes       optional scopes override
	 */
	public EvitaRequest(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull String entityType,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable Locale locale,
		@Nullable Set<Scope> scopes
	) {

		this.requiresEntity = true;
		this.entityRequirement = evitaRequest.entityRequirement;
		this.entityType = entityType;
		this.query = Query.query(
			evitaRequest.query.getHead() == null ?
				null :
				ConstraintCloneVisitor.clone(
					evitaRequest.query.getHead(),
					(constraintCloneVisitor, constraint) ->
						constraint instanceof Collection ?
							collection(entityType) : constraint
				),
			filterBy,
			orderBy,
			require(this.entityRequirement)
		);
		this.alignedNow = evitaRequest.getAlignedNow();
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.primaryKeys = null;
		this.labels = null;
		this.queryPriceMode = evitaRequest.getQueryPriceMode();
		this.priceValidInTimeSet = true;
		this.priceValidInTime = evitaRequest.getRequiresPriceValidIn();
		this.currencySet = true;
		this.currency = evitaRequest.getRequiresCurrency();
		this.requiresPriceLists = evitaRequest.isRequiresPriceLists();
		this.priceLists = evitaRequest.getRequiresPriceLists();
		this.additionalPriceLists = evitaRequest.getFetchesAdditionalPriceLists();
		this.defaultAccompanyingPricePriceLists = evitaRequest.getDefaultAccompanyingPricePriceLists();
		this.accompanyingPrices = null;
		this.localeExamined = true;
		this.locale = locale == null ? evitaRequest.getLocale() : locale;
		this.requiredLocales = null;
		this.requiredLocaleSet = null;
		this.requiresParent = null;
		this.parentContent = null;
		this.entityAttributes = null;
		this.entityAttributeSet = null;
		this.entityAssociatedData = null;
		this.entityAssociatedDataSet = null;
		this.entityReference = null;
		this.entityFetchRequirements = null;
		this.namedEntityFetchRequirements = null;
		this.defaultReferenceRequirement = null;
		this.entityPrices = null;
		this.start = null;
		this.conditionalGaps = null;
		this.hierarchyWithin = null;
		this.requiredWithinHierarchy = null;
		this.requiresHierarchyStatistics = null;
		this.requiresHierarchyParents = null;
		this.limit = null;
		this.resultForm = null;
		this.facetGroupConjunction = null;
		this.facetGroupDisjunction = null;
		this.facetGroupNegation = null;
		this.expectedType = evitaRequest.expectedType;
		this.debugModes = null;
		this.queryTelemetryRequested = evitaRequest.queryTelemetryRequested;
		this.queryTelemetryPlanRequested = evitaRequest.queryTelemetryPlanRequested;
		this.priceHistogramRequested = evitaRequest.priceHistogramRequested;
		this.scopes = scopes;
		this.scopesAsArray = this.scopes == null ?
			null : this.scopes.toArray(Scope[]::new);
	}

	/**
	 * Returns true if query targets specific entity type.
	 */
	public boolean isEntityTypeRequested() {
		return this.entityType != null;
	}

	/**
	 * Returns type of the entity this query targets. Allows to choose
	 * proper {@link EntityCollectionContract}.
	 */
	@Nullable
	public String getEntityType() {
		return this.entityType;
	}

	/**
	 * Returns array of labels associated with the query.
	 */
	@Nonnull
	public Label[] getLabels() {
		if (this.labels == null) {
			this.labels = ofNullable(this.query.getHead())
				.map(it -> QueryUtils.findConstraints(it, Label.class).toArray(Label[]::new))
				.orElse(Label.EMPTY_ARRAY);
		}
		return this.labels;
	}

	/**
	 * Returns locale of the entity that is being requested.
	 */
	@Nullable
	public Locale getLocale() {
		if (!this.localeExamined) {
			this.localeExamined = true;
			this.locale = ofNullable(QueryUtils.findFilter(this.query, EntityLocaleEquals.class))
				.map(EntityLocaleEquals::getLocale)
				.orElse(null);
		}
		return this.locale;
	}

	/**
	 * Returns implicit locale that might be derived from the globally
	 * unique attribute if the entity is matched particularly by it.
	 */
	@Nullable
	public Locale getImplicitLocale() {
		return this.implicitLocale;
	}

	/**
	 * Returns locale of the entity that is being requested. If locale
	 * is not explicitly set in the query it falls back to
	 * {@link #getImplicitLocale()}.
	 */
	@Nullable
	public Locale getRequiredOrImplicitLocale() {
		return ofNullable(getLocale()).orElseGet(this::getImplicitLocale);
	}

	/**
	 * Returns set of locales if requirement {@link DataInLocales} is
	 * present in the query. If not it falls back to
	 * {@link EntityLocaleEquals} (check {@link DataInLocales} docs).
	 * Accessor method caches the found result so that consecutive
	 * calls of this method are pretty fast.
	 *
	 * The lookup runs against the **reduced** fetch returned by {@link #getEntityRequirement()}, which is what lets
	 * a query name several `dataInLocales` requirements side by side — they are folded into one before this method
	 * looks for it.
	 *
	 * @return set of locales the localized data should be materialised in, empty when every locale is requested,
	 *         NULL when the query names neither a {@link DataInLocales} requirement nor a locale
	 * @throws EvitaInvalidUsageException when two content requirements of the same kind contradict each other
	 */
	@Nullable
	public Set<Locale> getRequiredLocales() {
		if (this.requiredLocales == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.requiredLocales = true;
				final Locale theLocale = getLocale();
				if (theLocale != null) {
					this.requiredLocaleSet = Set.of(theLocale);
				}
			} else {
				final DataInLocales dataRequirement =
					QueryUtils.findConstraint(
						entityFetch, DataInLocales.class,
						SeparateEntityContentRequireContainer.class
					);
				if (dataRequirement != null) {
					final Locale[] locales = dataRequirement.getLocales();
					final Set<Locale> localeSet = CollectionUtils.createHashSet(locales.length);
					for (final Locale loc : locales) {
						if (loc != null) {
							localeSet.add(loc);
						}
					}
					this.requiredLocaleSet = localeSet;
				} else {
					final Locale theLocale = getLocale();
					if (theLocale != null) {
						this.requiredLocaleSet = Set.of(theLocale);
					}
				}
				this.requiredLocales = true;
			}
		}
		return this.requiredLocaleSet;
	}

	/**
	 * Returns query price mode of the current query.
	 */
	@Nonnull
	public QueryPriceMode getQueryPriceMode() {
		if (this.queryPriceMode == null) {
			this.queryPriceMode = ofNullable(QueryUtils.findRequire(this.query, PriceType.class))
				.map(PriceType::getQueryPriceMode)
				.orElse(QueryPriceMode.WITH_TAX);
		}
		return this.queryPriceMode;
	}

	/**
	 * Returns set of primary keys that are required by the query in
	 * {@link EntityPrimaryKeyInSet} query. If there is no such query
	 * empty array is returned in the result. Accessor method caches
	 * the found result so that consecutive calls of this method are
	 * pretty fast.
	 */
	@Nonnull
	public int[] getPrimaryKeys() {
		if (this.primaryKeys == null) {
			this.primaryKeys = ofNullable(
				QueryUtils.findFilter(
					this.query,
					EntityPrimaryKeyInSet.class,
					SeparateEntityScopeContainer.class
				))
				.map(EntityPrimaryKeyInSet::getPrimaryKeys)
				.orElse(ArrayUtils.EMPTY_INT_ARRAY);
		}
		return this.primaryKeys;
	}

	/**
	 * Method will determine if at least entity body is required for main entities.
	 *
	 * This is the method that actually performs the reduction: on its first call it finds the `entityFetch` of the
	 * query, folds its duplicate content requirements into one requirement per kind and memoizes the result, which
	 * {@link #getEntityRequirement()} then merely hands out. A caller that only ever asks this question therefore
	 * pays for the reduction, and sees its refusal, just like a caller of the requirement getter.
	 *
	 * @return true when the query fetches entity bodies for the main entities
	 * @throws EvitaInvalidUsageException when two content requirements of the same kind contradict each other; the
	 *                                    memoized fields stay unassigned in that case, so every later call re-attempts
	 *                                    the reduction and fails the same way instead of answering from a partial state
	 */
	public boolean isRequiresEntity() {
		if (this.requiresEntity == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(
				this.query, EntityFetch.class,
				SeparateEntityContentRequireContainer.class
			);
			// the reduction is applied first - when it refuses a pair of contradicting requirements neither field is
			// assigned and the next call re-attempts it and fails the same way instead of returning a partial answer
			this.entityRequirement = entityFetch == null ?
				null : entityFetch.combineDuplicateRequirements();
			this.requiresEntity = entityFetch != null;
		}
		return this.requiresEntity;
	}

	/**
	 * Method will find all requirement specifying richness of main entities. The constraints inside
	 * {@link SeparateEntityContentRequireContainer} implementations
	 * of the same type are ignored because they relate to the
	 * different entity context.
	 *
	 * The returned fetch is **reduced** - duplicate content requirements of the same kind that the client wrote side
	 * by side are folded into a single requirement each by
	 * {@link EntityFetchRequire#combineDuplicateRequirements()}, and irreconcilable siblings are refused with an
	 * {@link EvitaInvalidUsageException}. `getQuery()` keeps the original, unreduced query so that traffic
	 * recording and query printing reproduce what the client actually sent.
	 *
	 * Because of that reduction the per-kind getters below may keep looking the requirement up with
	 * `QueryUtils.findConstraint`, which accepts a single result only - a
	 * `MoreThanSingleResultException` raised from one of them means the reduction did not happen or did not cover
	 * that kind, not that the query was invalid.
	 *
	 * @return the reduced entity fetch requirement or NULL when the query does not fetch entity bodies
	 * @throws EvitaInvalidUsageException when two content requirements of the same kind contradict each other
	 */
	@Nullable
	public EntityFetch getEntityRequirement() {
		if (this.requiresEntity == null) {
			isRequiresEntity();
		}
		return this.entityRequirement;
	}

	/**
	 * Method will determine if parent body is required for main entities.
	 */
	public boolean isRequiresParent() {
		if (this.requiresParent == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.parentContent = null;
				this.requiresParent = false;
			} else {
				this.parentContent = QueryUtils.findConstraint(
					entityFetch, HierarchyContent.class,
					SeparateEntityContentRequireContainer.class
				);
				this.requiresParent = this.parentContent != null;
			}
		}
		return this.requiresParent;
	}

	/**
	 * Method will find all requirement specifying richness of main entities. The constraints inside
	 * {@link SeparateEntityContentRequireContainer} implementations
	 * of the same type are ignored because they relate to the
	 * different entity context.
	 */
	@Nullable
	public HierarchyContent getHierarchyContent() {
		if (this.requiresParent == null) {
			isRequiresParent();
		}
		return this.parentContent;
	}

	/**
	 * Returns TRUE if requirement {@link AttributeContent} is present
	 * in the query. Accessor method caches the found result so that
	 * consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresEntityAttributes() {
		if (this.entityAttributes == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.entityAttributes = false;
				this.entityAttributeSet = Collections.emptySet();
			} else {
				final AttributeContent requiresAttributeContent =
					QueryUtils.findConstraint(
						entityFetch,
						AttributeContent.class,
						SeparateEntityContentRequireContainer.class
					);
				this.entityAttributes = requiresAttributeContent != null;
				if (requiresAttributeContent != null) {
					final String[] names = requiresAttributeContent.getAttributeNames();
					final Set<String> set = CollectionUtils.createHashSet(names.length);
					Collections.addAll(set, names);
					this.entityAttributeSet = set;
				} else {
					this.entityAttributeSet = Collections.emptySet();
				}
			}
		}
		return this.entityAttributes;
	}

	/**
	 * Returns set of attribute names that were requested in the query.
	 * The set is empty if none is requested
	 * which means - all attributes are to be returned.
	 */
	@Nonnull
	public Set<String> getEntityAttributeSet() {
		if (this.entityAttributeSet == null) {
			isRequiresEntityAttributes();
		}
		return this.entityAttributeSet;
	}

	/**
	 * Returns TRUE if requirement {@link AssociatedDataContent} is
	 * present in the query. Accessor method caches the found result
	 * so that consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresEntityAssociatedData() {
		if (this.entityAssociatedData == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.entityAssociatedData = false;
				this.entityAssociatedDataSet = Collections.emptySet();
			} else {
				final AssociatedDataContent requiresAssociatedDataContent =
					QueryUtils.findConstraint(
						entityFetch,
						AssociatedDataContent.class,
						SeparateEntityContentRequireContainer.class
					);
				this.entityAssociatedData = requiresAssociatedDataContent != null;
				if (requiresAssociatedDataContent != null) {
					final String[] names = requiresAssociatedDataContent.getAssociatedDataNames();
					final Set<String> set = CollectionUtils.createHashSet(names.length);
					Collections.addAll(set, names);
					this.entityAssociatedDataSet = set;
				} else {
					this.entityAssociatedDataSet = Collections.emptySet();
				}
			}
		}
		return this.entityAssociatedData;
	}

	/**
	 * Returns set of associated data names that were requested in the
	 * query. The set is empty if none is requested
	 * which means - all associated data are to be returned.
	 */
	@Nonnull
	public Set<String> getEntityAssociatedDataSet() {
		if (this.entityAssociatedDataSet == null) {
			isRequiresEntityAssociatedData();
		}
		return this.entityAssociatedDataSet;
	}

	/**
	 * Returns TRUE if requirement {@link ReferenceContent} is present
	 * in the query. Accessor method caches the found result so that
	 * consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresEntityReferences() {
		if (this.entityReference == null) {
			getReferenceEntityFetch();
		}
		return this.entityReference;
	}

	/**
	 * Returns {@link PriceContentMode} if requirement
	 * {@link PriceContent} is present in the query. Accessor method
	 * caches the found result so that consecutive calls of this
	 * method are pretty fast.
	 *
	 * The lookup runs against the **reduced** fetch returned by {@link #getEntityRequirement()}, so a query may name
	 * several `priceContent` requirements and several `accompanyingPriceContent` requirements for one price - they
	 * are folded before this method looks for them.
	 *
	 * @return the price content mode requested for the main entities, {@link PriceContentMode#NONE} when the query
	 *         fetches no prices at all
	 * @throws EvitaInvalidUsageException when two content requirements of the same kind contradict each other
	 */
	@Nonnull
	public PriceContentMode getRequiresEntityPrices() {
		if (this.entityPrices == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.entityPrices = PriceContentMode.NONE;
				this.additionalPriceLists = ArrayUtils.EMPTY_STRING_ARRAY;
				this.accompanyingPrices = AccompanyingPrice.EMPTY_ARRAY;
			} else {
				final Optional<PriceContent> priceContentRequirement =
					ofNullable(QueryUtils.findConstraint(
						entityFetch, PriceContent.class,
						SeparateEntityContentRequireContainer.class
					));
				this.entityPrices = priceContentRequirement
					.map(PriceContent::getFetchMode)
					.orElse(PriceContentMode.NONE);
				final String[] theDefaultAccompaniedPriceLists =
					this.getDefaultAccompanyingPricePriceLists();
				final List<AccompanyingPriceContent> accompanyingPriceContents =
					QueryUtils.findConstraints(
						entityFetch,
						AccompanyingPriceContent.class,
						SeparateEntityContentRequireContainer.class
					);
				if (accompanyingPriceContents.isEmpty()) {
					this.accompanyingPrices = AccompanyingPrice.EMPTY_ARRAY;
				} else {
					final AccompanyingPrice[] accompanyingResult =
						new AccompanyingPrice[accompanyingPriceContents.size()];
					for (int i = 0; i < accompanyingPriceContents.size(); i++) {
						final AccompanyingPriceContent apc = accompanyingPriceContents.get(i);
						final String priceName =
							apc.getAccompanyingPriceName()
								.orElse(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE);
						if (apc.getPriceLists().length == 0) {
							Assert.isTrue(
								!ArrayUtils.isEmptyOrItsValuesNull(theDefaultAccompaniedPriceLists),
								"Default accompanying price lists must be defined in the query " +
									"if no accompanying price name and no price lists are " +
									"specified in the query!"
							);
							accompanyingResult[i] = new AccompanyingPrice(
								priceName, theDefaultAccompaniedPriceLists
							);
						} else {
							accompanyingResult[i] = new AccompanyingPrice(
								priceName, apc.getPriceLists()
							);
						}
					}
					this.accompanyingPrices = accompanyingResult;
				}
				if (theDefaultAccompaniedPriceLists.length == 0 &&
					this.accompanyingPrices.length == 0) {
					this.additionalPriceLists = priceContentRequirement
						.map(PriceContent::getAdditionalPriceListsToFetch)
						.orElse(ArrayUtils.EMPTY_STRING_ARRAY);
				} else {
					// default accompanying price lists are always fetched,
					// so we can merge them with additional price lists
					final String[] additionalFromRequirement = priceContentRequirement
						.map(PriceContent::getAdditionalPriceListsToFetch)
						.orElse(ArrayUtils.EMPTY_STRING_ARRAY);
					int estimatedSize = theDefaultAccompaniedPriceLists.length + additionalFromRequirement.length;
					for (final AccompanyingPrice ap : this.accompanyingPrices) {
						estimatedSize += ap.priceListPriority().length;
					}
					final LinkedHashSet<String> merged =
						CollectionUtils.createLinkedHashSet(estimatedSize);
					Collections.addAll(merged, theDefaultAccompaniedPriceLists);
					Collections.addAll(merged, additionalFromRequirement);
					for (final AccompanyingPrice ap : this.accompanyingPrices) {
						Collections.addAll(merged, ap.priceListPriority());
					}
					this.additionalPriceLists = merged.toArray(String[]::new);
				}
			}
		}
		return this.entityPrices;
	}

	/**
	 * Retrieves an array of accompanying prices. If the accompanying prices have not
	 * been initialized, it triggers the loading of entity prices.
	 *
	 * @return an array of AccompanyingPrice objects representing the accompanying prices.
	 * The returned array is non-null.
	 */
	@Nonnull
	public AccompanyingPrice[] getAccompanyingPrices() {
		if (this.accompanyingPrices == null) {
			getRequiresEntityPrices();
		}
		return this.accompanyingPrices;
	}

	/**
	 * Returns array of price list ids if requirement
	 * {@link DefaultAccompanyingPriceLists} is present in the query.
	 * Accessor method caches the found result so that consecutive
	 * calls of this method are pretty fast.
	 */
	@Nonnull
	public String[] getDefaultAccompanyingPricePriceLists() {
		if (this.defaultAccompanyingPricePriceLists == null) {
			this.defaultAccompanyingPricePriceLists = ofNullable(
				QueryUtils.findRequire(
					this.query,
					DefaultAccompanyingPriceLists.class,
					SeparateEntityContentRequireContainer.class
				))
				.map(DefaultAccompanyingPriceLists::getPriceLists)
				.orElse(ArrayUtils.EMPTY_STRING_ARRAY);
		}
		return this.defaultAccompanyingPricePriceLists;
	}

	/**
	 * Returns array of price list ids if requirement
	 * {@link PriceContent} is present in the query. Accessor method
	 * caches the found result so that consecutive calls of this
	 * method are pretty fast.
	 */
	@Nonnull
	public String[] getFetchesAdditionalPriceLists() {
		if (this.additionalPriceLists == null) {
			getRequiresEntityPrices();
		}
		return this.additionalPriceLists;
	}

	/**
	 * Returns TRUE if any {@link PriceInPriceLists} is present in the
	 * query. Accessor method caches the found result so that
	 * consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresPriceLists() {
		if (this.requiresPriceLists == null) {
			final List<PriceInPriceLists> priceInPriceLists =
				QueryUtils.findFilters(this.query, PriceInPriceLists.class);
			Assert.isTrue(
				priceInPriceLists.size() <= 1,
				"Query can not contain more than one price in price lists filter constraints!"
			);
			if (priceInPriceLists.isEmpty()) {
				this.priceLists = ArrayUtils.EMPTY_STRING_ARRAY;
				this.requiresPriceLists = false;
			} else {
				this.priceLists = priceInPriceLists.get(0).getPriceLists();
				this.requiresPriceLists = true;
			}
		}
		return this.requiresPriceLists;
	}

	/**
	 * Returns array of price list ids if filter
	 * {@link PriceInPriceLists} is present in the query. Accessor
	 * method caches the found result so that consecutive calls of
	 * this method are pretty fast.
	 */
	@Nonnull
	public String[] getRequiresPriceLists() {
		if (this.priceLists == null) {
			isRequiresPriceLists();
		}
		return this.priceLists;
	}

	/**
	 * Returns set of price list ids if requirement
	 * {@link PriceInCurrency} is present in the query. Accessor
	 * method caches the found result so that consecutive calls of
	 * this method are pretty fast.
	 */
	@Nullable
	public Currency getRequiresCurrency() {
		if (this.currencySet == null) {
			final List<PriceInCurrency> currencyConstraints =
				QueryUtils.findFilters(this.query, PriceInCurrency.class);
			Currency foundCurrency = null;
			for (final PriceInCurrency pic : currencyConstraints) {
				final Currency curr = pic.getCurrency();
				if (foundCurrency == null) {
					foundCurrency = curr;
				} else {
					Assert.isTrue(
						Objects.equals(foundCurrency, curr),
						"Query can not contain more than one currency filtering constraints!"
					);
				}
			}
			this.currency = foundCurrency;
			this.currencySet = true;
		}
		return this.currency;
	}

	/**
	 * Returns price valid in datetime if requirement
	 * {@link io.evitadb.api.query.filter.PriceValidIn} is present
	 * in the query. Accessor method caches the found result so that
	 * consecutive calls of this method are pretty fast.
	 */
	@Nullable
	public OffsetDateTime getRequiresPriceValidIn() {
		if (this.priceValidInTimeSet == null) {
			final List<PriceValidIn> priceValidInConstraints = QueryUtils.findFilters(this.query, PriceValidIn.class);
			OffsetDateTime foundValidIn = null;
			boolean foundAny = false;
			for (final PriceValidIn pvi : priceValidInConstraints) {
				final OffsetDateTime moment = pvi.getTheMoment(this::getAlignedNow);
				if (!foundAny) {
					foundValidIn = moment;
					foundAny = true;
				} else {
					Assert.isTrue(
						Objects.equals(foundValidIn, moment),
						"Query can not contain more than one price validity constraints!"
					);
				}
			}
			this.priceValidInTime = foundValidIn;
			this.priceValidInTimeSet = true;
		}
		return this.priceValidInTime;
	}

	/**
	 * Retrieves the default facet relation type for the current configuration.
	 * If the default facet relation type is not already defined, it initializes the value
	 * based on the facet calculation rules found in the query. If no custom rules are provided,
	 * the default facet relation type will be set to {@link FacetRelationType#DISJUNCTION}.
	 *
	 * @return The default {@link FacetRelationType} used for facets within the same group.
	 */
	@Nonnull
	public FacetRelationType getDefaultFacetRelationType() {
		if (this.defaultFacetRelationType == null) {
			final Optional<FacetCalculationRules> customRules =
				ofNullable(QueryUtils.findRequire(
					this.query, FacetCalculationRules.class
				));
			this.defaultFacetRelationType = customRules
				.map(FacetCalculationRules::getFacetsWithSameGroupRelationType)
				.orElse(FacetRelationType.DISJUNCTION);
			this.defaultGroupRelationType = customRules
				.map(FacetCalculationRules::getFacetsWithDifferentGroupsRelationType)
				.orElse(FacetRelationType.CONJUNCTION);
		}
		return this.defaultFacetRelationType;
	}

	/**
	 * Retrieves the default group relation type for facets. This method determines the relation type
	 * applied to facets belonging to different groups. If not previously set, it evaluates custom
	 * rules from the query context.
	 * If custom rules are not provided, the default is set to {@link FacetRelationType#CONJUNCTION}.
	 *
	 * @return The default relation type for facets in different groups.
	 */
	@Nonnull
	public FacetRelationType getDefaultGroupRelationType() {
		if (this.defaultGroupRelationType == null) {
			final Optional<FacetCalculationRules> customRules =
				ofNullable(QueryUtils.findRequire(
					this.query, FacetCalculationRules.class
				));
			this.defaultFacetRelationType = customRules
				.map(FacetCalculationRules::getFacetsWithSameGroupRelationType)
				.orElse(FacetRelationType.DISJUNCTION);
			this.defaultGroupRelationType = customRules
				.map(FacetCalculationRules::getFacetsWithDifferentGroupsRelationType)
				.orElse(FacetRelationType.CONJUNCTION);
		}
		return this.defaultGroupRelationType;
	}

	/**
	 * Returns filter by representing group entity primary keys of
	 * `referenceName` facets, that are requested to be joined by
	 * conjunction (AND) instead of default disjunction (OR).
	 *
	 * The settings are keyed by the reference name **and** the {@link FacetGroupRelationLevel} the constraint
	 * declared, because the two levels are orthogonal - a relation asked for between groups says nothing about
	 * the relation between the facets inside one group.
	 *
	 * @param referenceName name of the reference the facets belong to
	 * @param level         level the relation is being asked about
	 * @return the settings declared for that reference at that level, empty when none were
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupConjunction(
		@Nonnull String referenceName,
		@Nonnull FacetGroupRelationLevel level
	) {
		if (this.facetGroupConjunction == null) {
			this.facetGroupConjunction = collectFacetGroupSettings(FacetGroupsConjunction.class);
		}
		return ofNullable(this.facetGroupConjunction.get(new FacetGroupRelationKey(referenceName, level)));
	}

	/**
	 * Returns filter by representing group entity primary keys of
	 * `referenceName` facets, that are requested to be joined with
	 * other facet groups by disjunction (OR) instead of default
	 * conjunction (AND).
	 *
	 * The settings are keyed by the reference name **and** the {@link FacetGroupRelationLevel} the constraint
	 * declared, because the two levels are orthogonal - a relation asked for between groups says nothing about
	 * the relation between the facets inside one group.
	 *
	 * @param referenceName name of the reference the facets belong to
	 * @param level         level the relation is being asked about
	 * @return the settings declared for that reference at that level, empty when none were
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupDisjunction(
		@Nonnull String referenceName,
		@Nonnull FacetGroupRelationLevel level
	) {
		if (this.facetGroupDisjunction == null) {
			this.facetGroupDisjunction = collectFacetGroupSettings(FacetGroupsDisjunction.class);
		}
		return ofNullable(this.facetGroupDisjunction.get(new FacetGroupRelationKey(referenceName, level)));
	}

	/**
	 * Returns filter by representing group entity primary keys of
	 * `referenceName` facets, that are requested to be joined by
	 * negation (AND NOT) instead of default disjunction (OR).
	 *
	 * Negation is the one relation whose level does not change the outcome, and it is therefore honoured at
	 * **either** level: by De Morgan's laws negating each facet and combining the results with AND (`!a && !b`)
	 * is the same set as negating the group's own disjunction (`!(a || b)`). A constraint declared at one level is
	 * consequently returned for the other one too, so that the engine reaches the same answer whichever level the
	 * code path asking happens to be deciding. The equivalence holds while the *other* relation stays at its
	 * system default; {@link io.evitadb.api.query.require.FacetCalculationRules} can break it, and a query that
	 * changes the defaults has to state the level it means.
	 *
	 * The three other relations are keyed by reference name **and** level, because for them the two levels are
	 * genuinely orthogonal.
	 *
	 * @param referenceName name of the reference the facets belong to
	 * @param level         level the relation is being asked about
	 * @return the settings declared for that reference at either level, empty when none were
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupNegation(
		@Nonnull String referenceName,
		@Nonnull FacetGroupRelationLevel level
	) {
		if (this.facetGroupNegation == null) {
			this.facetGroupNegation = collectFacetGroupSettings(FacetGroupsNegation.class);
		}
		final FacetFilterBy declaredAtTheLevel = this.facetGroupNegation.get(
			new FacetGroupRelationKey(referenceName, level)
		);
		if (declaredAtTheLevel != null) {
			return of(declaredAtTheLevel);
		}
		final FacetGroupRelationLevel otherLevel =
			level == FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP ?
				FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS :
				FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
		return ofNullable(this.facetGroupNegation.get(new FacetGroupRelationKey(referenceName, otherLevel)));
	}

	/**
	 * Returns filter by representing group entity primary keys of
	 * `referenceName` facets, that are requested to be calculated in
	 * exclusive fashion (no other facet from same group is selected).
	 *
	 * The settings are keyed by the reference name **and** the {@link FacetGroupRelationLevel} the constraint
	 * declared, because the two levels are orthogonal - a relation asked for between groups says nothing about
	 * the relation between the facets inside one group.
	 *
	 * @param referenceName name of the reference the facets belong to
	 * @param level         level the relation is being asked about
	 * @return the settings declared for that reference at that level, empty when none were
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupExclusivity(
		@Nonnull String referenceName,
		@Nonnull FacetGroupRelationLevel level
	) {
		if (this.facetGroupExclusivity == null) {
			this.facetGroupExclusivity = collectFacetGroupSettings(FacetGroupsExclusivity.class);
		}
		return ofNullable(this.facetGroupExclusivity.get(new FacetGroupRelationKey(referenceName, level)));
	}

	/**
	 * Collects the facet group relation settings of one relation type out of the query, keyed by the reference name
	 * and the level the constraint declared.
	 *
	 * Two constraints of one relation type may address one reference as long as they aim at different levels - that
	 * is what the levels are for. Two aiming at the same level contradict each other, because only one filter can
	 * decide which groups the relation applies to, and are refused rather than resolved by whichever the query
	 * happened to list last.
	 *
	 * @param constraintType the relation constraint class to collect
	 * @return settings of that relation type, keyed by reference name and level
	 * @throws EvitaInvalidUsageException when one reference is addressed twice at one level with different filters
	 */
	@Nonnull
	private Map<FacetGroupRelationKey, FacetFilterBy> collectFacetGroupSettings(
		@Nonnull Class<? extends FacetGroupsConstraint> constraintType
	) {
		final Map<FacetGroupRelationKey, FacetFilterBy> result = new HashMap<>();
		for (final FacetGroupsConstraint constraint : QueryUtils.findRequires(this.query, constraintType)) {
			final FacetGroupRelationKey key = new FacetGroupRelationKey(
				constraint.getReferenceName(), constraint.getFacetGroupRelationLevel()
			);
			final FacetFilterBy settings = new FacetFilterBy(constraint.getFacetGroups().orElse(null));
			final FacetFilterBy alreadyPresent = result.putIfAbsent(key, settings);
			if (alreadyPresent != null && !Objects.equals(alreadyPresent.filterBy(), settings.filterBy())) {
				final String reason = "Facet groups of reference `" + key.referenceName() + "` are addressed twice " +
					"by `" + constraintType.getSimpleName() + "` at level `" + key.level() + "` with different " +
					"group filters - state one filter for that level, or aim the second constraint at the other level";
				throw new EvitaInvalidUsageException(
					reason + ": " + alreadyPresent.filterBy() + " and " + settings.filterBy() + ".",
					reason + "."
				);
			}
		}
		return result;
	}

	/**
	 * Returns TRUE if requirement {@link QueryTelemetry} is present in
	 * the query. Accessor method caches the found result so that
	 * consecutive calls of this method are pretty fast.
	 */
	public boolean isQueryTelemetryRequested() {
		if (this.queryTelemetryRequested == null) {
			this.queryTelemetryRequested =
				QueryUtils.findRequire(this.query, QueryTelemetry.class) != null;
		}
		return this.queryTelemetryRequested;
	}

	/**
	 * Returns TRUE if requirement {@link QueryTelemetry} is present in the query **and** asks for the formula plan
	 * via {@link io.evitadb.api.query.require.QueryTelemetryContent#PLAN}. Accessor method caches the found result
	 * so that consecutive calls of this method are pretty fast.
	 *
	 * This is the single guard that keeps plan rendering off every query that did not ask for it - a query at the
	 * default {@link io.evitadb.api.query.require.QueryTelemetryContent#TIMINGS} level, i.e. plain
	 * `queryTelemetry()`, gets its timings and builds no plan structure whatsoever.
	 */
	public boolean isQueryTelemetryPlanRequested() {
		if (this.queryTelemetryPlanRequested == null) {
			final QueryTelemetry telemetry = QueryUtils.findRequire(this.query, QueryTelemetry.class);
			this.queryTelemetryPlanRequested = telemetry != null && telemetry.isPlanRequested();
		}
		return this.queryTelemetryPlanRequested;
	}

	/**
	 * Returns TRUE if requirement {@link PriceHistogram} is present in the query. Accessor method caches the
	 * found result so that consecutive calls of this method are pretty fast. Read by the filter planner (see
	 * `PriceListCompositionTerminationVisitor`) at {@code LowestPriceTerminationFormula} construction time to
	 * decide whether outer LP instances should collect the per-inner-record histogram side-output.
	 */
	public boolean isPriceHistogramRequested() {
		if (this.priceHistogramRequested == null) {
			// `findRequires` rather than `findRequire`: a duplicated requirement is decided by
			// `PriceHistogramTranslator`, which runs whatever the query filters on, and the single-result lookup
			// used here would have thrown `MoreThanSingleResultException` first - but only for queries that carry
			// a price filter, since this accessor is reached from the price filter translators alone
			this.priceHistogramRequested =
				!QueryUtils.findRequires(this.query, PriceHistogram.class).isEmpty();
		}
		return this.priceHistogramRequested;
	}

	/**
	 * Returns true if passed {@link DebugMode} is enabled in the
	 * query. Accessor method caches the found result so that
	 * consecutive calls of this method are pretty fast.
	 */
	public boolean isDebugModeEnabled(@Nonnull DebugMode debugMode) {
		if (this.debugModes == null) {
			this.debugModes = ofNullable(QueryUtils.findRequire(this.query, Debug.class))
				.map(Debug::getDebugMode)
				.orElseGet(() -> EnumSet.noneOf(DebugMode.class));
		}
		return this.debugModes.contains(debugMode);
	}

	/**
	 * Returns count of records required in the result (i.e. number of records on a single page).
	 */
	public int getLimit() {
		if (this.limit == null) {
			initPagination();
		}
		return this.limit;
	}

	/**
	 * Returns requested record offset of the records required in the result.
	 */
	public int getStart() {
		if (this.start == null) {
			initPagination();
		}
		return this.start;
	}

	/**
	 * Retrieves the current ResultForm instance. If the ResultForm is not yet initialized,
	 * this method initializes the pagination and sets up the ResultForm.
	 *
	 * @return the current ResultForm instance
	 */
	@Nonnull
	public ResultForm getResultForm() {
		if (this.resultForm == null) {
			initPagination();
		}
		return this.resultForm;
	}

	/**
	 * Retrieves an array of ConditionalGap objects based on the constraints
	 * defined in the query.
	 *
	 * If the conditionalGaps array is not already initialized, this method will
	 * initialize it by searching for constraints of type ConditionalGap in the query.
	 *
	 * @return An array of ConditionalGap objects representing the constraints
	 * found in the query. If no such constraints are found, an empty array
	 * is returned.
	 */
	@Nonnull
	public ConditionalGap[] getConditionalGaps() {
		if (this.conditionalGaps == null) {
			initPagination();
		}
		return this.conditionalGaps;
	}

	/**
	 * Returns default requirements for reference content - the context derived from the `referenceContentAll…()`
	 * requirement that names neither an instance nor any reference. There is at most one such requirement per request:
	 * duplicates are folded into one by {@link EntityFetchRequire#combineDuplicateRequirements()} before the map is
	 * built. A default requirement coexisting with reference-name specific ones is not a conflict - it is the
	 * fallback consulted by {@link #getReferenceEntityFetch()} when no specific requirement claims the reference.
	 *
	 * @return the default reference requirement or NULL when the query names every reference it wants explicitly
	 */
	@Nullable
	public RequirementContext getDefaultReferenceRequirement() {
		getReferenceEntityFetch();
		return this.defaultReferenceRequirement;
	}

	/**
	 * Returns requested referenced entity requirements from the input query, keyed by reference name.
	 * Allows traversing through the object relational graph in unlimited depth.
	 *
	 * The map holds **one requirement per reference name**. The `referenceContent` requirements of the query are
	 * folded by key first (see {@link EntityFetchRequire#combineDuplicateRequirements()}), which leaves at most one
	 * requirement per *(instance name, name set)* key; requirements whose name sets merely **overlap** survive that
	 * fold as separate requirements and are reconciled here: each one is projected onto every name it lists
	 * ({@link ReferenceContent#forReferenceName(String)}) and the projections sharing a name are folded into one
	 * through {@link ReferenceContent#combineWith(EntityContentRequire)}. So `referenceContent("a", "b")` written
	 * beside `referenceContent("b", "c")` fetches `b` with the union of both bodies, and only a genuine
	 * disagreement inside a shared name - two different `filterBy`, `orderBy` or chunking constraints - is refused
	 * with an {@link EvitaInvalidUsageException}. A reference named twice **inside one** requirement
	 * (`referenceContent("brand", "brand")`) folds with itself and claims the reference once.
	 *
	 * Requirements carrying an instance name are collected separately into {@link #getNamedReferenceEntityFetch()}
	 * and the instance-less catch-all into {@link #getDefaultReferenceRequirement()}.
	 *
	 * All three lookups are published together, once the whole query has been walked without a conflict. A refused
	 * query therefore leaves the request untouched and raises the very same usage exception on every call, instead
	 * of reporting an internal error over the remains of the first, aborted attempt.
	 *
	 * @return map of reference name to the single requirement context that applies to it
	 * @throws EvitaInvalidUsageException when two `referenceContent` requirements claiming one reference disagree on
	 *                                    the `filterBy`, `orderBy` or chunking constraint applied to it
	 */
	@Nonnull
	public Map<String, RequirementContext> getReferenceEntityFetch() {
		if (this.entityFetchRequirements == null) {
			final EntityFetch entityRequirement = getEntityRequirement();
			if (entityRequirement == null) {
				this.entityReference = false;
				this.entityFetchRequirements = Collections.emptyMap();
			} else {
				final List<ReferenceContent> referenceContent =
					QueryUtils.findConstraints(
						entityRequirement,
						ReferenceContent.class,
						SeparateEntityContentRequireContainer.class
					);

				// find default requirement (no instance name, no reference names) - after the reduction performed by
				// getEntityRequirement() there can be at most one, a second one is a programming error
				RequirementContext defaultReq = null;
				ReferenceContent defaultRefContent = null;
				for (final ReferenceContent rc : referenceContent) {
					if (rc.getInstanceName() == null &&
						ArrayUtils.isEmpty(rc.getReferenceNames())) {
						if (defaultRefContent != null) {
							throw new GenericEvitaInternalError(
								"Duplicate default reference content requirement survived the requirement " +
									"reduction: " + defaultRefContent + " and " + rc + "!",
								"Duplicate default reference content requirement found in the query!"
							);
						}
						defaultRefContent = rc;
						defaultReq = getRequirementContext(
							rc, rc.getAttributeContent().orElse(null)
						);
					}
				}
				// build the requirements maps into locals - the fields are published only once the whole loop
				// succeeded, so a refused request reproduces the very same usage exception on every call
				final Map<String, ReferenceContent> foldedPerName =
					CollectionUtils.createLinkedHashMap(referenceContent.size());
				Map<ReferenceContentKey, RequirementContext> namedResult = null;
				for (final ReferenceContent rc : referenceContent) {
					final String instanceName = rc.getInstanceName();
					if (instanceName != null) {
						// named reference
						if (namedResult == null) {
							namedResult = new TreeMap<>();
						}
						// after the reduction there can be at most one requirement per (instance, reference) key
						final RequirementContext previouslyNamed =
							namedResult.put(
								new ReferenceContentKey(instanceName, rc.getReferenceName()),
								getRequirementContext(
									rc, rc.getAttributeContent().orElse(null)
								)
							);
						if (previouslyNamed != null) {
							throw new GenericEvitaInternalError(
								"Duplicate reference content requirement for instance `" + instanceName +
									"` and reference `" + rc.getReferenceName() + "` survived the requirement " +
									"reduction: " + rc + "!",
								"Duplicate named reference content requirement found in the query!"
							);
						}
					} else {
						// unnamed reference - project the requirement onto each name it lists and fold the
						// projections sharing a name, so that requirements with overlapping name sets contribute
						// to the shared reference instead of one of them silently winning it. A name repeated
						// inside a single requirement folds with itself and claims the reference once.
						for (final String refName : rc.getReferenceNames()) {
							final ReferenceContent projection = rc.forReferenceName(refName);
							foldedPerName.compute(
								refName,
								(k, alreadyFolded) -> alreadyFolded == null
									? projection
									: alreadyFolded.combineWith(projection)
							);
						}
					}
				}
				final Map<String, RequirementContext> result =
					CollectionUtils.createHashMap(foldedPerName.size());
				for (final Map.Entry<String, ReferenceContent> entry : foldedPerName.entrySet()) {
					final ReferenceContent folded = entry.getValue();
					result.put(
						entry.getKey(),
						getRequirementContext(folded, folded.getAttributeContent().orElse(null))
					);
				}
				this.entityReference = !referenceContent.isEmpty();
				this.defaultReferenceRequirement = defaultReq;
				this.namedEntityFetchRequirements = namedResult;
				this.entityFetchRequirements = result;
			}
		}
		return this.entityFetchRequirements;
	}

	/**
	 * Returns requested referenced entity requirements with instance name from the input query.
	 * Allows traversing through the object relational graph in unlimited depth.
	 *
	 * The map holds **one requirement per (instance name, reference name) key** - requirements sharing a key are
	 * folded into one by {@link EntityFetchRequire#combineDuplicateRequirements()} before the map is built, so two
	 * aliases of the same reference stay two independent entries while two occurrences of one alias become one.
	 *
	 * @return map of the instance/reference key to the single requirement context that applies to it
	 */
	@Nonnull
	public Map<ReferenceContentKey, RequirementContext> getNamedReferenceEntityFetch() {
		if (this.entityFetchRequirements == null) {
			// initialize both maps
			getReferenceEntityFetch();
		}
		return Objects.requireNonNullElse(this.namedEntityFetchRequirements, Collections.emptyMap());
	}

	/**
	 * Returns transformation function that wraps list of references
	 * into appropriate implementation of the chunk
	 * data structure requested and expected by the client.
	 */
	@Nonnull
	public ChunkTransformer getReferenceChunkTransformer(@Nonnull String referenceName) {
		if (this.referenceChunkTransformer == null) {
			this.referenceChunkTransformer = refName -> ofNullable(getReferenceEntityFetch().get(refName))
				.map(RequirementContext::referenceChunkTransformer)
				.orElse(NoTransformer.INSTANCE);
		}
		return this.referenceChunkTransformer.apply(referenceName);
	}

	/**
	 * Returns the {@link HierarchyWithin} query restricting the hierarchy of `referenceName`, or `null` when the
	 * query restricts none. The result seeds the computation of hierarchy statistics, which can be based on exactly
	 * one hierarchy filter - a query that restricts one hierarchy by two contradicting constraints is therefore
	 * refused here rather than described by statistics matching only one of them. Note that the filter itself stays
	 * legal: only asking for the statistics of an ambiguously restricted hierarchy is not.
	 *
	 * @param referenceName the reference whose hierarchy filter is looked up, `null` for the queried entity itself
	 * @return the single hierarchy filter aimed at that target, or `null` when there is none
	 * @throws EvitaInvalidUsageException when the query restricts the target's hierarchy by two different constraints
	 */
	@Nullable
	public HierarchyFilterConstraint getHierarchyWithin(@Nullable String referenceName) {
		if (this.requiredWithinHierarchy == null) {
			if (this.query.getFilterBy() == null) {
				this.hierarchyWithin = Collections.emptyMap();
			} else {
				this.hierarchyWithin = new HashMap<>();
				QueryUtils.findConstraints(
						this.query.getFilterBy(),
						HierarchyFilterConstraint.class
					)
					.forEach(
						it -> this.hierarchyWithin
							.computeIfAbsent(it.getReferenceName().orElse(null), s -> new ArrayList<>(2))
							.add(it)
					);
			}
			this.requiredWithinHierarchy = true;
		}
		final List<HierarchyFilterConstraint> constraints = this.hierarchyWithin == null ?
			null : this.hierarchyWithin.get(referenceName);
		if (constraints == null || constraints.isEmpty()) {
			return null;
		}
		final HierarchyFilterConstraint theConstraint = constraints.get(0);
		for (int i = 1; i < constraints.size(); i++) {
			final HierarchyFilterConstraint anotherConstraint = constraints.get(i);
			if (!theConstraint.equals(anotherConstraint)) {
				// the statistics of a single hierarchy are computed from exactly one hierarchy filter, while the
				// filter planner translates every one of them - returning either would make the extra result
				// disagree with the record set it is supposed to describe
				final String reason = "Statistics of " +
					(referenceName == null ?
						"the queried entity's own hierarchy" : "the hierarchy of reference `" + referenceName + "`") +
					" are requested while the query restricts that hierarchy by two different constraints - " +
					"the statistics can be computed from only one of them";
				throw new EvitaInvalidUsageException(
					reason + ": " + theConstraint + " and " + anotherConstraint + ".", reason + "."
				);
			}
		}
		return theConstraint;
	}

	/**
	 * Method creates copy of this request with changed `entityType`
	 * and entity `requirements`. The copy will share already resolved
	 * and memoized values of this request except those that relate to
	 * the changed entity type and requirements.
	 *
	 * The passed requirements are **reduced** in the copy - duplicate content requirements of the same kind are
	 * folded into one by {@link EntityFetchRequire#combineDuplicateRequirements()}, so the derived request is
	 * described by at most one requirement of each kind. This is where a nested fetch scope (the `entityFetch`
	 * written inside a {@link ReferenceContent}, for instance) gets reduced, since the fold applied to the outer
	 * container is shallow.
	 *
	 * @param entityType   the new entity type (may be null)
	 * @param requirements the entity fetch requirements for the derived request
	 * @return a new request fetching `requirements` for `entityType`
	 * @throws EvitaInvalidUsageException when two of the passed requirements address the same thing but contradict
	 *                                    each other
	 */
	@Nonnull
	public EvitaRequest deriveCopyWith(
		@Nullable String entityType,
		@Nonnull EntityFetchRequire requirements
	) {
		return new EvitaRequest(
			this,
			entityType,
			null, null,
			requirements
		);
	}

	/**
	 * Method creates copy of this request with changed `entityType`
	 * and entity `requirements`. The copy will share already resolved
	 * and memoized values of this request except those that relate to
	 * the changed entity type and requirements.
	 *
	 * The passed requirements are **reduced** in the copy exactly as in
	 * {@link #deriveCopyWith(String, EntityFetchRequire)}.
	 *
	 * @param entityType   the new entity type (may be null)
	 * @param filterBy     optional filter constraints override
	 * @param orderBy      optional order constraints override
	 * @param requirements the entity fetch requirements for the derived request
	 * @return a new request fetching `requirements` for `entityType` under the passed filter and order
	 * @throws EvitaInvalidUsageException when two of the passed requirements address the same thing but contradict
	 *                                    each other
	 */
	@Nonnull
	public EvitaRequest deriveCopyWith(
		@Nullable String entityType,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nonnull EntityFetchRequire requirements
	) {
		return new EvitaRequest(
			this,
			entityType, filterBy, orderBy, requirements
		);
	}

	/**
	 * Method creates copy of this request with changed `entityType`
	 * and `filterConstraint`. The copy will share already resolved
	 * and memoized values of this request except those that relate
	 * to the changed entity type and the filtering constraints.
	 */
	@Nonnull
	public EvitaRequest deriveCopyWith(
		@Nonnull String entityType,
		@Nullable FilterBy filterConstraint,
		@Nullable OrderBy orderConstraint,
		@Nullable Locale locale,
		@Nonnull Set<Scope> scopes
	) {
		final EntityScope enforcedScope = scope(scopes.toArray(Scope[]::new));
		final FilterBy filterBy = filterConstraint == null ?
			filterBy(enforcedScope) :
			(FilterBy) ConstraintCloneVisitor.clone(filterConstraint, new ScopeEnforcer(enforcedScope));
		return new EvitaRequest(
			this,
			entityType,
			filterBy,
			orderConstraint,
			locale,
			scopes
		);
	}

	/**
	 * Retrieves the set of scopes associated with the current query.
	 * If the scopes have not been initialized, it attempts to find the required
	 * scopes from the query, falling back to the default scopes if none are found.
	 *
	 * @return an EnumSet of Scope objects representing the scopes for the current query
	 */
	@Nonnull
	public Set<Scope> getScopes() {
		if (this.scopes == null || this.scopesAsArray == null) {
			this.scopesAsArray = ofNullable(QueryUtils.findFilter(this.query, EntityScope.class))
				.map(it -> it.getScope().toArray(Scope[]::new))
				.orElse(Scope.DEFAULT_SCOPES);
			final EnumSet<Scope> theScopes = EnumSet.noneOf(Scope.class);
			Collections.addAll(theScopes, this.scopesAsArray);
			this.scopes = theScopes;
		}
		return this.scopes;
	}

	/**
	 * Retrieves an array representation of the scopes.
	 * Internally, it initializes the scopes by calling the getScopes() method.
	 *
	 * @return an array of Scope objects representing the initialized scopes.
	 */
	@Nonnull
	public Scope[] getScopesAsArray() {
		// init scopes
		getScopes();
		return Objects.requireNonNull(this.scopesAsArray);
	}

	/**
	 * Internal method that consults input query and initializes pagination information.
	 * If there is no pagination in the input query, first page with
	 * size of 20 records is used as default.
	 *
	 * @throws EvitaInvalidUsageException when the query carries both `page` and `strip`
	 */
	private void initPagination() {
		final Optional<Page> page = ofNullable(
			QueryUtils.findRequire(
				this.query, Page.class,
				SeparateEntityContentRequireContainer.class
			)
		);
		final Optional<Strip> strip = ofNullable(
			QueryUtils.findRequire(
				this.query, Strip.class,
				SeparateEntityContentRequireContainer.class
			)
		);
		if (page.isPresent() && strip.isPresent()) {
			// the two are irreconcilable by construction - each of them selects a different `ResultForm`, so there is
			// no combined answer to give and picking either one silently discards what the query asked for
			final String reason = "Query cannot combine `page` and `strip` - each of them selects a different form " +
				"of the result, so exactly one of them may be present";
			throw new EvitaInvalidUsageException(
				reason + ": " + page.get() + " and " + strip.get() + ".", reason + "."
			);
		}
		if (page.isPresent()) {
			final Page thePage = page.get();
			this.limit = thePage.getPageSize();
			this.start = thePage.getPageNumber();
			this.conditionalGaps = convertSpacingToGaps(thePage.getSpacing().orElse(null));
			this.resultForm = EvitaRequest.ResultForm.PAGINATED_LIST;
		} else if (strip.isPresent()) {
			final Strip theStrip = strip.get();
			this.limit = theStrip.getLimit();
			this.start = theStrip.getOffset();
			this.conditionalGaps = EMPTY_GAPS;
			this.resultForm = EvitaRequest.ResultForm.STRIP_LIST;
		} else {
			this.limit = 20;
			this.start = 1;
			this.conditionalGaps = EMPTY_GAPS;
			this.resultForm = EvitaRequest.ResultForm.PAGINATED_LIST;
		}
	}

	/**
	 * The ResultForm enum represents different formats for displaying results.
	 *
	 * It defines two possible formats:
	 *
	 * 1. PAGINATED_LIST: Represents a list format where results are divided into pages.
	 * 2. STRIP_LIST: Represents a continuous list format where results
	 * are displayed in a single strip.
	 */
	public enum ResultForm {
		PAGINATED_LIST, STRIP_LIST
	}

	/**
	 * Simple DTO that allows collection of {@link ReferenceContent}
	 * inner constraints related to fetching the entity
	 * and group entity for fast access in this evita request instance.
	 *
	 * @param managedReferencesBehaviour controls behaviour of excluding missing managed references
	 * @param attributeContent           requested attributes for the entity reference
	 * @param entityFetch                requirements related to fetching related entity
	 * @param entityGroupFetch           requirements related to fetching related entity group
	 * @param filterBy                   filtering constraints for entities
	 * @param orderBy                    ordering constraints for entities
	 */
	public record RequirementContext(
		@Nonnull ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nonnull ChunkTransformer referenceChunkTransformer
	) {

		/**
		 * Generates an AttributeRequest based on the current attributeContent.
		 * If attributeContent is null, an empty set of attributes is created.
		 * Otherwise, the attribute names from attributeContent are used.
		 *
		 * @return an AttributeRequest instance containing the set of
		 * attribute names and whether any attributes are required
		 */
		@Nonnull
		public AttributeRequest attributeRequest() {
			return new AttributeRequest(
				this.attributeContent == null ?
					Collections.emptySet() :
					this.attributeContent.getAttributeNamesAsSet(),
				this.attributeContent != null
			);
		}

		/**
		 * Returns true if the settings require initialization of referenced entities.
		 *
		 * @return true if the settings require initialization of referenced entities
		 */
		public boolean requiresInit() {
			return this.managedReferencesBehaviour != ManagedReferencesBehaviour.ANY ||
				this.entityFetch != null ||
				this.entityGroupFetch != null ||
				this.filterBy != null ||
				this.orderBy != null ||
				!(this.referenceChunkTransformer instanceof NoTransformer);
		}

		/**
		 * Extends the current attribute content requirement of the
		 * `RequirementContext` by combining it with the provided
		 * attribute content. If the current attribute content is null,
		 * the provided attribute content
		 * will be used as-is. Otherwise, it merges the existing attribute content with the new one.
		 *
		 * @param attributeContent the new {@link AttributeContent} to
		 *                         extend the current attribute content
		 *                         requirement
		 * @return a new {@link RequirementContext} instance with the
		 * updated attribute content requirement
		 * @throws NullPointerException if the provided attribute content is null
		 */
		@Nonnull
		public RequirementContext withExtendedAttributeContentRequirement(
			@Nonnull AttributeContent attributeContent
		) {
			return new RequirementContext(
				this.managedReferencesBehaviour,
				this.attributeContent == null ?
					attributeContent :
					this.attributeContent.combineWith(attributeContent),
				this.entityFetch,
				this.entityGroupFetch,
				this.filterBy,
				this.orderBy,
				this.referenceChunkTransformer
			);
		}
	}

	/**
	 * Attribute request DTO contains information about all attribute
	 * names that has been requested for the particular
	 * reference.
	 *
	 * @param attributeSet             Contains information about all
	 *                                 attribute names that has been
	 *                                 fetched / requested for the entity.
	 * @param requiresEntityAttributes Contains true if any of the
	 *                                 attributes of the entity has been
	 *                                 fetched / requested.
	 */
	public record AttributeRequest(
		@Nonnull Set<String> attributeSet,
		@Getter boolean requiresEntityAttributes
	) implements Serializable {
		/**
		 * Represents a request for no attributes to be fetched.
		 */
		public static final AttributeRequest EMPTY = new AttributeRequest(Collections.emptySet(), false);
		/**
		 * Represents a request for all attributes to be fetched.
		 */
		public static final AttributeRequest ALL = new AttributeRequest(Collections.emptySet(), true);
	}

	/**
	 * Wraps the information whether the facet group was altered by a
	 * refinement constraint and if so, whether
	 * filterBy constraint was provided or not.
	 *
	 * @param filterBy filterBy constraint that was provided by the refinement constraint
	 */
	public record FacetFilterBy(
		@Nullable FilterBy filterBy
	) {

	}

	/**
	 * Identifies the facet group relation settings of one reference at one {@link FacetGroupRelationLevel}. The two
	 * levels are orthogonal - a relation declared between groups says nothing about the relation between the facets
	 * inside a single group - so the level is part of the key rather than something the settings are read without.
	 *
	 * @param referenceName name of the reference whose facets the relation applies to
	 * @param level         level the relation was declared at
	 */
	public record FacetGroupRelationKey(
		@Nonnull String referenceName,
		@Nonnull FacetGroupRelationLevel level
	) {

	}

	/**
	 * Represents a ConditionalGap with a specified size and an
	 * associated expression.
	 *
	 * This record is used to encapsulate the information of a gap,
	 * primarily its size and the condition or expression that
	 * determines some dynamic property or behavior related to
	 * the gap.
	 *
	 * @param size       the size of the gap
	 * @param expression the condition that needs to be satisfied
	 *                   for the gap to be applied
	 */
	public record ConditionalGap(
		int size,
		@Nonnull Expression expression
	) {

	}

	/**
	 * ScopeEnforcer is a private static class that enforces a specific {@link EntityScope}
	 * and ensures it is added to filterBy constraint.
	 */
	@RequiredArgsConstructor
	private static class ScopeEnforcer implements
		BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> {
		private final EntityScope enforcedScope;
		private boolean scopeFound;

		@Nullable
		@Override
		public Constraint<?> apply(
			ConstraintCloneVisitor constraintCloneVisitor,
			Constraint<?> constraint
		) {
			if (constraint instanceof EntityScope) {
				this.scopeFound = true;
				return this.enforcedScope;
			} else if (constraint instanceof FilterBy && !this.scopeFound) {
				constraintCloneVisitor.addOnCurrentLevel(this.enforcedScope);
				return constraint;
			} else if (constraint instanceof FilterInScope fis) {
				// when the `inScope` doesn't match the enforced scope, exclude the container with its contents
				return this.enforcedScope.getScope().contains(fis.getScope()) ? fis : null;
			} else {
				return constraint;
			}
		}
	}

	/**
	 * Key allowing to distinguish different reference content requirements.
	 *
	 * @param instanceName  optional name of the reference content instance
	 * @param referenceName name of the reference
	 */
	public record ReferenceContentKey(
		@Nullable String instanceName,
		@Nonnull String referenceName
	) implements Serializable, Comparable<ReferenceContentKey> {

		private static final Comparator<ReferenceContentKey> REFERENCE_CONTENT_KEY_COMPARATOR =
			Comparator.comparing(ReferenceContentKey::referenceName)
				.thenComparing(
					ReferenceContentKey::instanceName,
					Comparator.nullsFirst(Comparator.naturalOrder())
				);

		@Override
		public int compareTo(@Nonnull ReferenceContentKey other) {
			return REFERENCE_CONTENT_KEY_COMPARATOR.compare(this, other);
		}

	}

}
