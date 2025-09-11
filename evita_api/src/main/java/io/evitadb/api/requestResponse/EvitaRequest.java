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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static java.util.Optional.ofNullable;

/**
 * Evita request serves as simple DTO that streamlines and caches access to the input {@link Query}.
 *
 * {@link EvitaRequest} is internal class (Evita accepts simple {@link Query} object -
 * see {@link EvitaSessionContract#query(Query, Class)}) that envelopes the input query. Evita request
 * can be used to implement methods that extract crucial information from the input query and cache those extracted
 * information to avoid paying parsing costs twice in single request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see EvitaSessionContract#query(Query, Class)
 * @see EvitaResponse examples in super class
 */
public class EvitaRequest {
	private static final ConditionalGap[] EMPTY_GAPS = new ConditionalGap[0];

	@Getter private final Query query;
	@Getter private final OffsetDateTime alignedNow;
	private final String entityType;
	private final Locale implicitLocale;
	@Getter private final Class<?> expectedType;
	@Nullable private Label[] labels;
	@Nullable private int[] primaryKeys;
	private boolean localeExamined;
	@Nullable private Locale locale;
	@Nullable private Boolean requiredLocales;
	@Nullable private Set<Locale> requiredLocaleSet;
	private QueryPriceMode queryPriceMode;
	private Boolean priceValidInTimeSet;
	@Nullable private OffsetDateTime priceValidInTime;
	private Boolean requiresEntity;
	@Nullable private Boolean requiresParent;
	@Nullable private HierarchyContent parentContent;
	@Nullable private EntityFetch entityRequirement;
	@Nullable private Boolean entityAttributes;
	@Nullable private Set<String> entityAttributeSet;
	@Nullable private Boolean entityAssociatedData;
	@Nullable private Set<String> entityAssociatedDataSet;
	@Nullable private Boolean entityReference;
	@Nullable private PriceContentMode entityPrices;
	private Boolean currencySet;
	@Nullable private Currency currency;
	private Boolean requiresPriceLists;
	private String[] priceLists;
	private String[] additionalPriceLists;
	private String[] defaultAccompanyingPricePriceLists;
	@Nullable private AccompanyingPrice[] accompanyingPrices;
	@Nullable private Integer start;
	@Nullable private ConditionalGap[] conditionalGaps;
	@Nullable private Map<String, HierarchyFilterConstraint> hierarchyWithin;
	@Nullable private Boolean requiredWithinHierarchy;
	@Nullable private Boolean requiresHierarchyStatistics;
	@Nullable private Boolean requiresHierarchyParents;
	@Nullable private Integer limit;
	@Nullable private EvitaRequest.ResultForm resultForm;
	@Nullable private FacetRelationType defaultFacetRelationType;
	@Nullable private FacetRelationType defaultGroupRelationType;
	@Nullable private Map<String, FacetFilterBy> facetGroupConjunction;
	@Nullable private Map<String, FacetFilterBy> facetGroupDisjunction;
	@Nullable private Map<String, FacetFilterBy> facetGroupNegation;
	@Nullable private Map<String, FacetFilterBy> facetGroupExclusivity;
	private Boolean queryTelemetryRequested;
	@Nullable private EnumSet<DebugMode> debugModes;
	@Nullable private Scope[] scopesAsArray;
	@Nullable private Set<Scope> scopes;
	@Nullable private Map<String, RequirementContext> entityFetchRequirements;
	@Nullable private RequirementContext defaultReferenceRequirement;
	@Nullable private Function<String, ChunkTransformer> referenceChunkTransformer;

	/**
	 * Parses the requirement context from the passed {@link ReferenceContent} and {@link AttributeContent}.
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
							page.getSpacing()
								.stream()
								.flatMap(it -> Arrays.stream(it.getGaps()))
								.map(it -> new ConditionalGap(it.getSize(), it.getOnPage()))
								.toArray(ConditionalGap[]::new)
						);
					} else if (chunking instanceof Strip strip) {
						return new StripTransformer(strip);
					} else {
						throw new EvitaInvalidUsageException("Unsupported chunking type: " + chunking.getClass().getSimpleName());
					}
				})
				.orElse(NoTransformer.INSTANCE)
		);
	}

	public EvitaRequest(
		@Nonnull Query query,
		@Nonnull OffsetDateTime alignedNow,
		@Nonnull Class<?> expectedType,
		@Nullable String entityTypeByExpectedType
	) {
		final Collection header = query.getCollection();
		this.entityType = ofNullable(header).map(Collection::getEntityType).orElse(entityTypeByExpectedType);
		this.query = query;
		this.alignedNow = alignedNow;
		this.implicitLocale = null;
		this.expectedType = expectedType;
	}

	public EvitaRequest(@Nonnull EvitaRequest evitaRequest, @Nonnull Locale implicitLocale) {
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
		this.requiresEntity = evitaRequest.requiresEntity;
		this.requiresParent = evitaRequest.requiresParent;
		this.parentContent = evitaRequest.parentContent;
		this.entityRequirement = evitaRequest.entityRequirement;
		this.expectedType = evitaRequest.expectedType;
		this.debugModes = evitaRequest.debugModes;
		this.scopes = evitaRequest.scopes;
		this.scopesAsArray = evitaRequest.scopesAsArray;
	}

	public EvitaRequest(
		@Nonnull EvitaRequest evitaRequest,
		@Nullable String entityType,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nonnull EntityFetchRequire requirements
	) {
		this.requiresEntity = true;
		this.entityRequirement = new EntityFetch(requirements.getRequirements());
		this.entityType = entityType;
		this.query = entityType == null ?
			Query.query(
				evitaRequest.query.getHead() == null ?
					null :
					ConstraintCloneVisitor.clone(
						evitaRequest.query.getHead(),
						(constraintCloneVisitor, constraint) -> constraint instanceof Collection ? null : constraint
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
						(constraintCloneVisitor, constraint) -> constraint instanceof Collection ? collection(entityType) : constraint
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
		if (Arrays.stream(requirements.getRequirements()).anyMatch(DataInLocales.class::isInstance)) {
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
			final List<OffsetDateTime> validitySpan = QueryUtils.findConstraints(filterBy, PriceValidIn.class)
				.stream()
				.map(it -> it.getTheMoment(this::getAlignedNow))
				.distinct()
				.toList();
			Assert.isTrue(
				validitySpan.size() <= 1,
				"Query can not contain more than one price validity constraints!"
			);
			if (validitySpan.isEmpty()) {
				this.priceValidInTimeSet = evitaRequest.priceValidInTimeSet;
				this.priceValidInTime = evitaRequest.priceValidInTime;
			} else {
				this.priceValidInTimeSet = true;
				this.priceValidInTime = validitySpan.get(0);
			}

			// prefer currencies from the current filter
			final List<Currency> currenciesFound = QueryUtils.findConstraints(filterBy, PriceInCurrency.class)
				.stream()
				.map(PriceInCurrency::getCurrency)
				.distinct()
				.toList();
			Assert.isTrue(
				currenciesFound.size() <= 1,
				"Query can not contain more than one currency filtering constraints!"
			);
			if (currenciesFound.isEmpty()) {
				this.currencySet = evitaRequest.currencySet;
				this.currency = evitaRequest.currency;
			} else {
				this.currency = currenciesFound.get(0);
				this.currencySet = true;
			}

			// prefer price lists from the current filter
			final List<PriceInPriceLists> priceInPriceLists = QueryUtils.findConstraints(filterBy, PriceInPriceLists.class);
			Assert.isTrue(
				priceInPriceLists.size() <= 1,
				"Query can not contain more than one price in price lists filter constraints!"
			);
			if (priceInPriceLists.isEmpty()) {
				this.priceLists = evitaRequest.priceLists;
				this.requiresPriceLists = evitaRequest.requiresPriceLists;
			} else {
				final Optional<PriceInPriceLists> pricesInPriceList = Optional.of(priceInPriceLists.get(0));
				this.priceLists = pricesInPriceList
					.map(PriceInPriceLists::getPriceLists)
					.orElse(new String[0]);
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
		this.expectedType = evitaRequest.expectedType;
		this.debugModes = evitaRequest.debugModes;
		this.scopes = evitaRequest.scopes;
		this.scopesAsArray = evitaRequest.scopesAsArray;
	}

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
					(constraintCloneVisitor, constraint) -> constraint instanceof Collection ? collection(entityType) : constraint
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
	 * Returns type of the entity this query targets. Allows to choose proper {@link EntityCollectionContract}.
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
	 * Returns implicit locale that might be derived from the globally unique attribute if the entity is matched
	 * particularly by it.
	 */
	@Nullable
	public Locale getImplicitLocale() {
		return this.implicitLocale;
	}

	/**
	 * Returns locale of the entity that is being requested. If locale is not explicitly set in the query it falls back
	 * to {@link #getImplicitLocale()}.
	 */
	@Nullable
	public Locale getRequiredOrImplicitLocale() {
		return ofNullable(getLocale()).orElseGet(this::getImplicitLocale);
	}

	/**
	 * Returns set of locales if requirement {@link DataInLocales} is present in the query. If not it falls back to
	 * {@link EntityLocaleEquals} (check {@link DataInLocales} docs).
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nullable
	public Set<Locale> getRequiredLocales() {
		if (this.requiredLocales == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(this.query, EntityFetch.class, SeparateEntityContentRequireContainer.class);
			if (entityFetch == null) {
				this.requiredLocales = true;
				final Locale theLocale = getLocale();
				if (theLocale != null) {
					this.requiredLocaleSet = Set.of(theLocale);
				}
			} else {
				final DataInLocales dataRequirement = QueryUtils.findConstraint(
					entityFetch, DataInLocales.class, SeparateEntityContentRequireContainer.class
				);
				if (dataRequirement != null) {
					this.requiredLocaleSet = Arrays.stream(dataRequirement.getLocales())
						.filter(Objects::nonNull)
						.collect(Collectors.toSet());
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
	 * Returns set of primary keys that are required by the query in {@link EntityPrimaryKeyInSet} query.
	 * If there is no such query empty array is returned in the result.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nonnull
	public int[] getPrimaryKeys() {
		if (this.primaryKeys == null) {
			this.primaryKeys = ofNullable(QueryUtils.findFilter(this.query, EntityPrimaryKeyInSet.class, SeparateEntityContentRequireContainer.class))
				.map(EntityPrimaryKeyInSet::getPrimaryKeys)
				.orElse(ArrayUtils.EMPTY_INT_ARRAY);
		}
		return this.primaryKeys;
	}

	/**
	 * Method will determine if at least entity body is required for main entities.
	 */
	public boolean isRequiresEntity() {
		if (this.requiresEntity == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(this.query, EntityFetch.class, SeparateEntityContentRequireContainer.class);
			this.requiresEntity = entityFetch != null;
			this.entityRequirement = entityFetch;
		}
		return this.requiresEntity;
	}

	/**
	 * Method will find all requirement specifying richness of main entities. The constraints inside
	 * {@link SeparateEntityContentRequireContainer} implementing of same type are ignored because they relate to the different entity context.
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
				this.parentContent = QueryUtils.findConstraint(entityFetch, HierarchyContent.class, SeparateEntityContentRequireContainer.class);
				this.requiresParent = this.parentContent != null;
			}
		}
		return this.requiresParent;
	}

	/**
	 * Method will find all requirement specifying richness of main entities. The constraints inside
	 * {@link SeparateEntityContentRequireContainer} implementing of same type are ignored because they relate to the
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
	 * Returns TRUE if requirement {@link AttributeContent} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresEntityAttributes() {
		if (this.entityAttributes == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.entityAttributes = false;
				this.entityAttributeSet = Collections.emptySet();
			} else {
				final AttributeContent requiresAttributeContent = QueryUtils.findConstraint(entityFetch, AttributeContent.class, SeparateEntityContentRequireContainer.class);
				this.entityAttributes = requiresAttributeContent != null;
				this.entityAttributeSet = requiresAttributeContent != null ?
					Arrays.stream(requiresAttributeContent.getAttributeNames()).collect(Collectors.toSet()) :
					Collections.emptySet();
			}
		}
		return this.entityAttributes;
	}

	/**
	 * Returns set of attribute names that were requested in the query. The set is empty if none is requested
	 * which means - all attributes is ought to be returned.
	 */
	@Nonnull
	public Set<String> getEntityAttributeSet() {
		if (this.entityAttributeSet == null) {
			isRequiresEntityAttributes();
		}
		return this.entityAttributeSet;
	}

	/**
	 * Returns TRUE if requirement {@link AssociatedDataContent} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresEntityAssociatedData() {
		if (this.entityAssociatedData == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.entityAssociatedData = false;
				this.entityAssociatedDataSet = Collections.emptySet();
			} else {
				final AssociatedDataContent requiresAssociatedDataContent = QueryUtils.findConstraint(entityFetch, AssociatedDataContent.class, SeparateEntityContentRequireContainer.class);
				this.entityAssociatedData = requiresAssociatedDataContent != null;
				this.entityAssociatedDataSet = requiresAssociatedDataContent != null ?
					Arrays.stream(requiresAssociatedDataContent.getAssociatedDataNames()).collect(Collectors.toSet()) :
					Collections.emptySet();
			}
		}
		return this.entityAssociatedData;
	}

	/**
	 * Returns set of associated data names that were requested in the query. The set is empty if none is requested
	 * which means - all associated data is ought to be returned.
	 */
	@Nonnull
	public Set<String> getEntityAssociatedDataSet() {
		if (this.entityAssociatedDataSet == null) {
			isRequiresEntityAssociatedData();
		}
		return this.entityAssociatedDataSet;
	}

	/**
	 * Returns TRUE if requirement {@link ReferenceContent} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresEntityReferences() {
		if (this.entityReference == null) {
			getReferenceEntityFetch();
		}
		return this.entityReference;
	}

	/**
	 * Returns {@link PriceContentMode} if requirement {@link PriceContent} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nonnull
	public PriceContentMode getRequiresEntityPrices() {
		if (this.entityPrices == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(this.query, EntityFetch.class, SeparateEntityContentRequireContainer.class);
			if (entityFetch == null) {
				this.entityPrices = PriceContentMode.NONE;
				this.additionalPriceLists = ArrayUtils.EMPTY_STRING_ARRAY;
			} else {
				final Optional<PriceContent> priceContentRequirement = ofNullable(QueryUtils.findConstraint(entityFetch, PriceContent.class, SeparateEntityContentRequireContainer.class));
				this.entityPrices = priceContentRequirement
					.map(PriceContent::getFetchMode)
					.orElse(PriceContentMode.NONE);
				final String[] theDefaultAccompaniedPriceLists = this.getDefaultAccompanyingPricePriceLists();
				this.accompanyingPrices = QueryUtils.findConstraints(entityFetch, AccompanyingPriceContent.class, SeparateEntityContentRequireContainer.class)
					.stream()
					.map(it -> {
						final String priceName = it.getAccompanyingPriceName().orElse(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE);
						if (it.getPriceLists().length == 0) {
							Assert.isTrue(
								!ArrayUtils.isEmptyOrItsValuesNull(theDefaultAccompaniedPriceLists),
								"Default accompanying price lists must be defined in the query if no accompanying price name and no price lists are specified in the query!"
							);
							return new AccompanyingPrice(priceName, theDefaultAccompaniedPriceLists);
						} else {
							return new AccompanyingPrice(priceName, it.getPriceLists());
						}
					})
					.toArray(AccompanyingPrice[]::new);
				this.additionalPriceLists = theDefaultAccompaniedPriceLists.length == 0 && this.accompanyingPrices.length == 0 ?
					priceContentRequirement
						.map(PriceContent::getAdditionalPriceListsToFetch)
						.orElse(ArrayUtils.EMPTY_STRING_ARRAY) :
					// default accompanying price lists are always fetched, so we can merge them with additional price lists
					Stream.concat(
							Stream.concat(
								Arrays.stream(theDefaultAccompaniedPriceLists), Arrays.stream(
									priceContentRequirement
										.map(PriceContent::getAdditionalPriceListsToFetch)
										.orElse(ArrayUtils.EMPTY_STRING_ARRAY)
								)
							),
							Arrays.stream(this.accompanyingPrices)
								.flatMap(accompanyingPrice -> Arrays.stream(accompanyingPrice.priceListPriority())
								)
						)
						.distinct()
						.toArray(String[]::new);
			}
		}
		return this.entityPrices;
	}

	/**
	 * Retrieves an array of accompanying prices. If the accompanying prices have not
	 * been initialized, it triggers the loading of entity prices.
	 *
	 * @return an array of AccompanyingPrice objects representing the accompanying prices.
	 *         The returned array is non-null.
	 */
	@Nonnull
	public AccompanyingPrice[] getAccompanyingPrices() {
		if (this.accompanyingPrices == null) {
			getRequiresEntityPrices();
		}
		return this.accompanyingPrices;
	}

	/**
	 * Returns array of price list ids if requirement {@link DefaultAccompanyingPriceLists} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nonnull
	public String[] getDefaultAccompanyingPricePriceLists() {
		if (this.defaultAccompanyingPricePriceLists == null) {
			this.defaultAccompanyingPricePriceLists = ofNullable(QueryUtils.findRequire(this.query, DefaultAccompanyingPriceLists.class, SeparateEntityContentRequireContainer.class))
				.map(DefaultAccompanyingPriceLists::getPriceLists)
				.orElse(ArrayUtils.EMPTY_STRING_ARRAY);
		}
		return this.defaultAccompanyingPricePriceLists;
	}

	/**
	 * Returns array of price list ids if requirement {@link PriceContent} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nonnull
	public String[] getFetchesAdditionalPriceLists() {
		if (this.additionalPriceLists == null) {
			getRequiresEntityPrices();
		}
		return this.additionalPriceLists;
	}

	/**
	 * Returns TRUE if any {@link PriceInPriceLists} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isRequiresPriceLists() {
		if (this.requiresPriceLists == null) {
			final List<PriceInPriceLists> priceInPriceLists = QueryUtils.findFilters(this.query, PriceInPriceLists.class);
			Assert.isTrue(
				priceInPriceLists.size() <= 1,
				"Query can not contain more than one price in price lists filter constraints!"
			);
			final Optional<PriceInPriceLists> pricesInPriceList = priceInPriceLists.isEmpty() ?
				Optional.empty() : Optional.of(priceInPriceLists.get(0));
			this.priceLists = pricesInPriceList
				.map(PriceInPriceLists::getPriceLists)
				.orElse(new String[0]);
			this.requiresPriceLists = pricesInPriceList.isPresent();
		}
		return this.requiresPriceLists;
	}

	/**
	 * Returns array of price list ids if filter {@link PriceInPriceLists} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nonnull
	public String[] getRequiresPriceLists() {
		if (this.priceLists == null) {
			isRequiresPriceLists();
		}
		return this.priceLists;
	}

	/**
	 * Returns set of price list ids if requirement {@link PriceInCurrency} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nullable
	public Currency getRequiresCurrency() {
		if (this.currencySet == null) {
			final List<Currency> currenciesFound = QueryUtils.findFilters(this.query, PriceInCurrency.class)
				.stream()
				.map(PriceInCurrency::getCurrency)
				.distinct()
				.toList();
			Assert.isTrue(
				currenciesFound.size() <= 1,
				"Query can not contain more than one currency filtering constraints!"
			);
			this.currency = currenciesFound.isEmpty() ? null : currenciesFound.get(0);
			this.currencySet = true;
		}
		return this.currency;
	}

	/**
	 * Returns price valid in datetime if requirement {@link io.evitadb.api.query.filter.PriceValidIn} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nullable
	public OffsetDateTime getRequiresPriceValidIn() {
		if (this.priceValidInTimeSet == null) {
			final List<OffsetDateTime> validitySpan = QueryUtils.findFilters(this.query, PriceValidIn.class)
				.stream()
				.map(it -> it.getTheMoment(this::getAlignedNow))
				.distinct()
				.toList();
			Assert.isTrue(
				validitySpan.size() <= 1,
				"Query can not contain more than one price validity constraints!"
			);
			this.priceValidInTime = validitySpan.isEmpty() ? null : validitySpan.get(0);
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
			final Optional<FacetCalculationRules> customRules = ofNullable(QueryUtils.findRequire(this.query, FacetCalculationRules.class));
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
			final Optional<FacetCalculationRules> customRules = ofNullable(QueryUtils.findRequire(this.query, FacetCalculationRules.class));
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
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * joined by conjunction (AND) instead of default disjunction (OR).
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupConjunction(@Nonnull String referenceName) {
		if (this.facetGroupConjunction == null) {
			this.facetGroupConjunction = new HashMap<>();
			QueryUtils.findRequires(this.query, FacetGroupsConjunction.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupConjunction.put(reqReferenceName, new FacetFilterBy(it.getFacetGroups().orElse(null)));
				});
		}
		return ofNullable(this.facetGroupConjunction.get(referenceName));
	}

	/**
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * joined with other facet groups by disjunction (OR) instead of default conjunction (AND).
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupDisjunction(@Nonnull String referenceName) {
		if (this.facetGroupDisjunction == null) {
			this.facetGroupDisjunction = new HashMap<>();
			QueryUtils.findRequires(this.query, FacetGroupsDisjunction.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupDisjunction.put(reqReferenceName, new FacetFilterBy(it.getFacetGroups().orElse(null)));
				});
		}
		return ofNullable(this.facetGroupDisjunction.get(referenceName));
	}

	/**
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * joined by negation (AND NOT) instead of default disjunction (OR).
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupNegation(@Nonnull String referenceName) {
		if (this.facetGroupNegation == null) {
			this.facetGroupNegation = new HashMap<>();
			QueryUtils.findRequires(this.query, FacetGroupsNegation.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupNegation.put(reqReferenceName, new FacetFilterBy(it.getFacetGroups().orElse(null)));
				});
		}
		return ofNullable(this.facetGroupNegation.get(referenceName));
	}

	/**
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * calculated in exclusive fashion (no other facet from same group is selected).
	 */
	@Nonnull
	public Optional<FacetFilterBy> getFacetGroupExclusivity(@Nonnull String referenceName) {
		if (this.facetGroupExclusivity == null) {
			this.facetGroupExclusivity = new HashMap<>();
			QueryUtils.findRequires(this.query, FacetGroupsExclusivity.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupExclusivity.put(reqReferenceName, new FacetFilterBy(it.getFacetGroups().orElse(null)));
				});
		}
		return ofNullable(this.facetGroupExclusivity.get(referenceName));
	}

	/**
	 * Returns TRUE if requirement {@link QueryTelemetry} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isQueryTelemetryRequested() {
		if (this.queryTelemetryRequested == null) {
			this.queryTelemetryRequested = QueryUtils.findRequire(this.query, QueryTelemetry.class) != null;
		}
		return this.queryTelemetryRequested;
	}

	/**
	 * Returns true if passed {@link DebugMode} is enabled in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
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
	 * Returns default requirements for reference content.
	 */
	@Nullable
	public RequirementContext getDefaultReferenceRequirement() {
		getReferenceEntityFetch();
		return this.defaultReferenceRequirement;
	}

	/**
	 * Returns requested referenced entity requirements from the input query.
	 * Allows traversing through the object relational graph in unlimited depth.
	 */
	@Nonnull
	public Map<String, RequirementContext> getReferenceEntityFetch() {
		if (this.entityFetchRequirements == null) {
			this.entityFetchRequirements = ofNullable(getEntityRequirement())
				.map(
					entityRequirement -> {
						final List<ReferenceContent> referenceContent = QueryUtils.findConstraints(entityRequirement, ReferenceContent.class, SeparateEntityContentRequireContainer.class);
						this.entityReference = !referenceContent.isEmpty();
						this.defaultReferenceRequirement = referenceContent
							.stream()
							.filter(it -> ArrayUtils.isEmpty(it.getReferenceNames()))
							.map(it -> getRequirementContext(it, it.getAttributeContent().orElse(null)))
							.findFirst()
							.orElse(null);

						return referenceContent
							.stream()
							.flatMap(it ->
								Arrays
									.stream(it.getReferenceNames())
									.map(
										entityType -> new SimpleEntry<>(
											entityType,
											getRequirementContext(it, it.getAttributeContent().orElse(null))
										)
									)
							)
							.collect(
								Collectors.toMap(
									SimpleEntry::getKey,
									SimpleEntry::getValue
								)
							);
					}
				).orElseGet(() -> {
					this.entityReference = false;
					return Collections.emptyMap();
				});
		}
		return this.entityFetchRequirements;
	}

	/**
	 * Returns transformation function that wraps list of references into appropriate implementation of the chunk
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
	 * Returns {@link HierarchyWithin} query
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
					.forEach(it -> this.hierarchyWithin.put(it.getReferenceName().orElse(null), it));
			}
			this.requiredWithinHierarchy = true;
		}
		return this.hierarchyWithin == null ? null : this.hierarchyWithin.get(referenceName);
	}

	/**
	 * Method creates copy of this request with changed `entityType` and entity `requirements`. The copy will share
	 * already resolved and memoized values of this request except those that relate to the changed entity type and
	 * requirements.
	 */
	@Nonnull
	public EvitaRequest deriveCopyWith(@Nullable String entityType, @Nonnull EntityFetchRequire requirements) {
		return new EvitaRequest(
			this,
			entityType,
			null, null,
			requirements
		);
	}

	/**
	 * Method creates copy of this request with changed `entityType` and entity `requirements`. The copy will share
	 * already resolved and memoized values of this request except those that relate to the changed entity type and
	 * requirements.
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
	 * Method creates copy of this request with changed `entityType` and `filterConstraint`. The copy will share
	 * already resolved and memoized values of this request except those that relate to the changed entity type and
	 * the filtering constraints.
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
	 * If there is no pagination in the input query, first page with size of 20 records is used as default.
	 */
	private void initPagination() {
		final Optional<Page> page = ofNullable(QueryUtils.findRequire(this.query, Page.class, SeparateEntityContentRequireContainer.class));
		final Optional<Strip> strip = ofNullable(QueryUtils.findRequire(this.query, Strip.class, SeparateEntityContentRequireContainer.class));
		if (page.isPresent()) {
			final Page thePage = page.get();
			this.limit = thePage.getPageSize();
			this.start = thePage.getPageNumber();
			this.conditionalGaps = thePage.getSpacing()
				.stream()
				.flatMap(it -> Arrays.stream(it.getGaps()))
				.map(it -> new ConditionalGap(it.getSize(), it.getOnPage()))
				.toArray(ConditionalGap[]::new);
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
	 * 2. STRIP_LIST: Represents a continuous list format where results are displayed in a single strip.
	 */
	public enum ResultForm {
		PAGINATED_LIST, STRIP_LIST
	}

	/**
	 * Simple DTO that allows collection of {@link ReferenceContent} inner constraints related to fetching the entity
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
		 * @return an AttributeRequest instance containing the set of attribute names and whether any attributes are required
		 */
		@Nonnull
		public AttributeRequest attributeRequest() {
			return new AttributeRequest(
				this.attributeContent == null ? Collections.emptySet() : this.attributeContent.getAttributeNamesAsSet(),
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
				this.entityFetch != null || this.entityGroupFetch != null || this.filterBy != null || this.orderBy != null;
		}

	}

	/**
	 * Attribute request DTO contains information about all attribute names that has been requested for the particular
	 * reference.
	 *
	 * @param attributeSet             Contains information about all attribute names that has been fetched / requested for the entity.
	 * @param requiresEntityAttributes Contains true if any of the attributes of the entity has been fetched / requested.
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
	 * Wraps the information whether the facet group was altered by a refinement constraint and if so, whether
	 * filterBy constraint was provided or not.
	 *
	 * @param filterBy filterBy constraint that was provided by the refinement constraint
	 */
	public record FacetFilterBy(
		@Nullable FilterBy filterBy
	) {

	}

	/**
	 * Represents a ConditionalGap with a specified size and an associated expression.
	 *
	 * This record is used to encapsulate the information of a gap, primarily its size
	 * and the condition or expression that determines some dynamic property or behavior
	 * related to the gap.
	 *
	 * Fields:
	 * - size: The size of the gap.
	 * - expression: The condition that needs to be satisfied for the gap to be applied.
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
	private static class ScopeEnforcer implements BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> {
		private final EntityScope enforcedScope;
		private boolean scopeFound;

		@Nullable
		@Override
		public Constraint<?> apply(ConstraintCloneVisitor constraintCloneVisitor, Constraint<?> constraint) {
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
}
