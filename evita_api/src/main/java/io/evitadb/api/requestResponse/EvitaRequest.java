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

package io.evitadb.api.requestResponse;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.*;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.require;
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
	private static final int[] EMPTY_INTS = new int[0];
	@Getter private final Query query;
	@Getter private final OffsetDateTime alignedNow;
	private final String entityType;
	private final Locale implicitLocale;
	private int[] primaryKeys;
	private boolean localeExamined;
	private Locale locale;
	private Boolean requiredLocales;
	private Set<Locale> requiredLocaleSet;
	private QueryPriceMode queryPriceMode;
	private Boolean priceValidInTimeSet;
	private OffsetDateTime priceValidInTime;
	private Boolean requiresEntity;
	private Boolean requiresParent;
	private HierarchyContent parentContent;
	private EntityFetch entityRequirement;
	private Boolean entityAttributes;
	private Set<String> entityAttributeSet;
	private Boolean entityAssociatedData;
	private Set<String> entityAssociatedDataSet;
	private Boolean entityReference;
	private Set<String> entityReferenceSet;
	private PriceContentMode entityPrices;
	private Boolean currencySet;
	private Currency currency;
	private Boolean requiresPriceLists;
	private String[] priceLists;
	private String[] additionalPriceLists;
	private Integer firstRecordOffset;
	private Map<String, HierarchyFilterConstraint> hierarchyWithin;
	private Boolean requiredWithinHierarchy;
	private Boolean requiresHierarchyStatistics;
	private Boolean requiresHierarchyParents;
	private Integer limit;
	private EvitaRequest.ResultForm resultForm;
	private Map<String, FilterBy> facetGroupConjunction;
	private Map<String, FilterBy> facetGroupDisjunction;
	private Map<String, FilterBy> facetGroupNegation;
	private Boolean queryTelemetryRequested;
	private EnumSet<DebugMode> debugModes;
	private Map<String, RequirementContext> entityFetchRequirements;

	public EvitaRequest(@Nonnull Query query, @Nonnull OffsetDateTime alignedNow) {
		final Collection header = query.getCollection();
		this.entityType = ofNullable(header).map(Collection::getEntityType).orElse(null);
		this.query = query;
		this.alignedNow = alignedNow;
		this.implicitLocale = null;
	}

	public EvitaRequest(@Nonnull EvitaRequest evitaRequest, @Nonnull Locale implicitLocale) {
		this.entityType = evitaRequest.entityType;
		this.query = evitaRequest.query;
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
		this.entityReferenceSet = evitaRequest.entityReferenceSet;
		this.entityPrices = evitaRequest.entityPrices;
		this.currencySet = evitaRequest.currencySet;
		this.currency = evitaRequest.currency;
		this.requiresPriceLists = evitaRequest.requiresPriceLists;
		this.additionalPriceLists = evitaRequest.additionalPriceLists;
		this.priceLists = evitaRequest.priceLists;
		this.firstRecordOffset = evitaRequest.firstRecordOffset;
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
	}

	public EvitaRequest(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull String entityType,
		@Nonnull EntityFetchRequire requirements) {

		this.requiresEntity = true;
		this.entityRequirement = new EntityFetch(requirements.getRequirements());
		this.entityType = entityType;
		this.query = Query.query(
			collection(entityType),
			evitaRequest.query.getFilterBy(),
			evitaRequest.query.getOrderBy(),
			require(this.entityRequirement)
		);
		this.alignedNow = evitaRequest.alignedNow;
		this.implicitLocale = evitaRequest.implicitLocale;
		this.primaryKeys = evitaRequest.primaryKeys;
		this.localeExamined = evitaRequest.localeExamined;
		this.locale = evitaRequest.locale;
		this.requiredLocales = evitaRequest.requiredLocales;
		this.requiredLocaleSet = evitaRequest.requiredLocaleSet;
		this.queryPriceMode = evitaRequest.queryPriceMode;
		this.priceValidInTimeSet = evitaRequest.priceValidInTimeSet;
		this.priceValidInTime = evitaRequest.priceValidInTime;
		this.requiresParent = null;
		this.parentContent = null;
		this.entityAttributes = null;
		this.entityAttributeSet = null;
		this.entityAssociatedData = null;
		this.entityAssociatedDataSet = null;
		this.entityReference = null;
		this.entityReferenceSet = null;
		this.entityPrices = evitaRequest.entityPrices;
		this.currencySet = evitaRequest.currencySet;
		this.currency = evitaRequest.currency;
		this.requiresPriceLists = evitaRequest.requiresPriceLists;
		this.additionalPriceLists = evitaRequest.additionalPriceLists;
		this.priceLists = evitaRequest.priceLists;
		this.firstRecordOffset = evitaRequest.firstRecordOffset;
		this.hierarchyWithin = evitaRequest.hierarchyWithin;
		this.requiredWithinHierarchy = evitaRequest.requiredWithinHierarchy;
		this.requiresHierarchyStatistics = evitaRequest.requiresHierarchyStatistics;
		this.requiresHierarchyParents = evitaRequest.requiresHierarchyParents;
		this.limit = evitaRequest.limit;
		this.resultForm = evitaRequest.resultForm;
		this.facetGroupConjunction = evitaRequest.facetGroupConjunction;
		this.facetGroupDisjunction = evitaRequest.facetGroupDisjunction;
		this.facetGroupNegation = evitaRequest.facetGroupNegation;
	}

	public EvitaRequest(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull String entityType,
		@Nonnull FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {

		this.requiresEntity = true;
		this.entityRequirement = evitaRequest.entityRequirement;
		this.entityType = entityType;
		this.query = Query.query(
			collection(entityType),
			filterBy,
			orderBy,
			require(this.entityRequirement)
		);
		this.alignedNow = evitaRequest.getAlignedNow();
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.primaryKeys = null;
		this.queryPriceMode = evitaRequest.getQueryPriceMode();
		this.priceValidInTimeSet = true;
		this.priceValidInTime = evitaRequest.getRequiresPriceValidIn();
		this.currencySet = true;
		this.currency = evitaRequest.getRequiresCurrency();
		this.requiresPriceLists = evitaRequest.isRequiresPriceLists();
		this.priceLists = evitaRequest.getRequiresPriceLists();
		this.additionalPriceLists = evitaRequest.getFetchesAdditionalPriceLists();
		this.localeExamined = true;
		this.locale = evitaRequest.getLocale();
		this.requiredLocales = null;
		this.requiredLocaleSet = null;
		this.requiresParent = null;
		this.parentContent = null;
		this.entityAttributes = null;
		this.entityAttributeSet = null;
		this.entityAssociatedData = null;
		this.entityAssociatedDataSet = null;
		this.entityReference = null;
		this.entityReferenceSet = null;
		this.entityPrices = null;
		this.firstRecordOffset = null;
		this.hierarchyWithin = null;
		this.requiredWithinHierarchy = null;
		this.requiresHierarchyStatistics = null;
		this.requiresHierarchyParents = null;
		this.limit = null;
		this.resultForm = null;
		this.facetGroupConjunction = null;
		this.facetGroupDisjunction = null;
		this.facetGroupNegation = null;
	}

	/**
	 * Returns true if query targets specific entity type.
	 */
	public boolean isEntityTypeRequested() {
		return entityType != null;
	}

	/**
	 * Returns type of the entity this query targets. Allows to choose proper {@link EntityCollectionContract}.
	 */
	@Nullable
	public String getEntityType() {
		return entityType;
	}

	/**
	 * Returns type of the entity this query targets. Allows to choose proper {@link EntityCollectionContract}.
	 */
	@Nonnull
	public String getEntityTypeOrThrowException(@Nonnull String purpose) {
		final Collection header = query.getCollection();
		return ofNullable(header)
			.map(Collection::getEntityType)
			.orElseThrow(() -> new EntityCollectionRequiredException(purpose));
	}

	/**
	 * Returns locale of the entity that is being requested.
	 */
	@Nullable
	public Locale getLocale() {
		if (!this.localeExamined) {
			this.localeExamined = true;
			this.locale = ofNullable(QueryUtils.findFilter(query, EntityLocaleEquals.class))
				.map(EntityLocaleEquals::getLocale)
				.orElse(null);
		}
		return this.locale;
	}

	/**
	 * Returns implicit locale that might be derived from the globally unique attribute if the entity is matched
	 * particularly by it.
	 */
	public Locale getImplicitLocale() {
		return implicitLocale;
	}

	/**
	 * Returns set of locales if requirement {@link DataInLocales} is present in the query. If not it falls back to
	 * {@link EntityLocaleEquals} (check {@link DataInLocales} docs).
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nullable
	public Set<Locale> getRequiredLocales() {
		if (this.requiredLocales == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(query, EntityFetch.class, SeparateEntityContentRequireContainer.class);
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
			this.queryPriceMode = ofNullable(QueryUtils.findRequire(query, PriceType.class))
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
		if (primaryKeys == null) {
			primaryKeys = ofNullable(QueryUtils.findFilter(query, EntityPrimaryKeyInSet.class, SeparateEntityContentRequireContainer.class))
				.map(EntityPrimaryKeyInSet::getPrimaryKeys)
				.orElse(EMPTY_INTS);
		}
		return primaryKeys;
	}

	/**
	 * Method will determine if at least entity body is required for main entities.
	 */
	public boolean isRequiresEntity() {
		if (this.requiresEntity == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(query, EntityFetch.class, SeparateEntityContentRequireContainer.class);
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
		if (entityAttributes == null) {
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
		return entityAttributes;
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
		if (entityAssociatedData == null) {
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
		return entityAssociatedData;
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
		if (entityReference == null) {
			final EntityFetch entityFetch = getEntityRequirement();
			if (entityFetch == null) {
				this.entityReference = false;
				this.entityReferenceSet = Collections.emptySet();
			} else {
				final List<ReferenceContent> requiresReference = QueryUtils.findConstraints(entityFetch, ReferenceContent.class, SeparateEntityContentRequireContainer.class);
				this.entityReference = !requiresReference.isEmpty();
				this.entityReferenceSet = requiresReference.isEmpty() ?
					Collections.emptySet() :
					requiresReference
						.stream()
						.flatMap(it -> Arrays.stream(it.getReferenceNames()))
						.collect(Collectors.toSet());
			}
		}
		return entityReference;
	}

	/**
	 * Returns set of reference names that were requested in the query. The set is empty if none is requested
	 * which means - all references ought to be returned.
	 */
	@Nonnull
	public Set<String> getEntityReferenceSet() {
		if (this.entityReferenceSet == null) {
			isRequiresEntityReferences();
		}
		return this.entityReferenceSet;
	}

	/**
	 * Returns {@link PriceContentMode} if requirement {@link PriceContent} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	@Nonnull
	public PriceContentMode getRequiresEntityPrices() {
		if (this.entityPrices == null) {
			final EntityFetch entityFetch = QueryUtils.findRequire(query, EntityFetch.class, SeparateEntityContentRequireContainer.class);
			if (entityFetch == null) {
				this.entityPrices = PriceContentMode.NONE;
				this.additionalPriceLists = PriceContent.EMPTY_PRICE_LISTS;
			} else {
				final Optional<PriceContent> priceContentRequirement = ofNullable(QueryUtils.findConstraint(entityFetch, PriceContent.class, SeparateEntityContentRequireContainer.class));
				this.entityPrices = priceContentRequirement
					.map(PriceContent::getFetchMode)
					.orElse(PriceContentMode.NONE);
				this.additionalPriceLists = priceContentRequirement
					.map(PriceContent::getAdditionalPriceListsToFetch)
					.orElse(PriceContent.EMPTY_PRICE_LISTS);
			}
		}
		return this.entityPrices;
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
			final PriceInPriceLists pricesInPriceList = QueryUtils.findFilter(query, PriceInPriceLists.class);
			this.priceLists = ofNullable(pricesInPriceList)
				.map(PriceInPriceLists::getPriceLists)
				.orElse(new String[0]);
			this.requiresPriceLists = pricesInPriceList != null;
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
			this.currency = ofNullable(QueryUtils.findFilter(query, PriceInCurrency.class))
				.map(PriceInCurrency::getCurrency)
				.orElse(null);
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
			this.priceValidInTime = ofNullable(QueryUtils.findFilter(query, PriceValidIn.class))
				.map(it -> ofNullable(it.getTheMoment()).orElse(alignedNow))
				.orElse(null);
			this.priceValidInTimeSet = true;
		}
		return this.priceValidInTime;
	}

	/**
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * joined by conjunction (AND) instead of default disjunction (OR).
	 */
	@Nonnull
	public Optional<FilterBy> getFacetGroupConjunction(@Nonnull String referenceName) {
		if (this.facetGroupConjunction == null) {
			this.facetGroupConjunction = new HashMap<>();
			QueryUtils.findRequires(query, FacetGroupsConjunction.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupConjunction.put(reqReferenceName, it.getFacetGroups());
				});
		}
		return ofNullable(this.facetGroupConjunction.get(referenceName));
	}

	/**
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * joined with other facet groups by disjunction (OR) instead of default conjunction (AND).
	 */
	@Nonnull
	public Optional<FilterBy> getFacetGroupDisjunction(@Nonnull String referenceName) {
		if (this.facetGroupDisjunction == null) {
			this.facetGroupDisjunction = new HashMap<>();
			QueryUtils.findRequires(query, FacetGroupsDisjunction.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupDisjunction.put(reqReferenceName, it.getFacetGroups());
				});
		}
		return ofNullable(this.facetGroupDisjunction.get(referenceName));
	}

	/**
	 * Returns filter by representing group entity primary keys of `referenceName` facets, that are requested to be
	 * joined by negation (AND NOT) instead of default disjunction (OR).
	 */
	@Nonnull
	public Optional<FilterBy> getFacetGroupNegation(@Nonnull String referenceName) {
		if (this.facetGroupNegation == null) {
			this.facetGroupNegation = new HashMap<>();
			QueryUtils.findRequires(query, FacetGroupsNegation.class)
				.forEach(it -> {
					final String reqReferenceName = it.getReferenceName();
					this.facetGroupNegation.put(reqReferenceName, it.getFacetGroups());
				});
		}
		return ofNullable(this.facetGroupNegation.get(referenceName));
	}

	/**
	 * Returns TRUE if requirement {@link QueryTelemetry} is present in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isQueryTelemetryRequested() {
		if (queryTelemetryRequested == null) {
			this.queryTelemetryRequested = QueryUtils.findRequire(query, QueryTelemetry.class) != null;
		}
		return queryTelemetryRequested;
	}

	/**
	 * Returns true if passed {@link DebugMode} is enabled in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isDebugModeEnabled(@Nonnull DebugMode debugMode) {
		if (debugModes == null) {
			this.debugModes = ofNullable(QueryUtils.findRequire(query, Debug.class))
				.map(Debug::getDebugMode)
				.orElseGet(() -> EnumSet.noneOf(DebugMode.class));
		}
		return debugModes.contains(debugMode);
	}

	/**
	 * Returns count of records required in the result (i.e. number of records on a single page).
	 */
	public int getLimit() {
		if (limit == null) {
			initPagination();
		}
		return limit;
	}

	/**
	 * Returns requested record offset of the records required in the result.
	 * Offset is automatically reset to zero if requested offset exceeds the total available record count.
	 */
	public int getFirstRecordOffset(int totalRecordCount) {
		if (firstRecordOffset == null) {
			initPagination();
		}
		return firstRecordOffset >= totalRecordCount ? 0 : firstRecordOffset;
	}

	/**
	 * Returns requested referenced entity requirements from the input query.
	 * Allows traversing through the object relational graph in unlimited depth.
	 */
	@Nonnull
	public Map<String, RequirementContext> getReferenceEntityFetch() {
		if (entityFetchRequirements == null) {
			entityFetchRequirements = ofNullable(getEntityRequirement())
				.map(entityRequirement -> QueryUtils.findConstraints(entityRequirement, ReferenceContent.class, SeparateEntityContentRequireContainer.class)
					.stream()
					.flatMap(it ->
						Arrays
							.stream(it.getReferenceNames())
							.map(entityType -> new SimpleEntry<>(
									entityType,
									new RequirementContext(
										it.getEntityRequirement(),
										it.getGroupEntityRequirement(),
										it.getFilterBy(),
										it.getOrderBy()
									)
								)
							)
					)
					.collect(
						Collectors.toMap(
							SimpleEntry::getKey,
							SimpleEntry::getValue
						)
					)
				).orElse(Collections.emptyMap());
		}
		return entityFetchRequirements;
	}

	/**
	 * Returns {@link HierarchyWithin} query
	 */
	@Nullable
	public HierarchyFilterConstraint getHierarchyWithin(@Nullable String referenceName) {
		if (requiredWithinHierarchy == null) {
			if (query.getFilterBy() == null) {
				hierarchyWithin = Collections.emptyMap();
			} else {
				hierarchyWithin = new HashMap<>();
				QueryUtils.findConstraints(
						query.getFilterBy(),
						HierarchyFilterConstraint.class
					)
					.forEach(it -> hierarchyWithin.put(it.getReferenceName().orElse(null), it));
			}
			requiredWithinHierarchy = true;
		}
		return hierarchyWithin.get(referenceName);
	}

	/**
	 * Method creates requested implementation of {@link DataChunk} with results.
	 */
	@Nonnull
	public <T extends Serializable> DataChunk<T> createDataChunk(int totalRecordCount, List<T> data) {
		if (firstRecordOffset == null) {
			initPagination();
		}
		return switch (resultForm) {
			case PAGINATED_LIST ->
				new PaginatedList<>(limit == 0 ? 1 : (firstRecordOffset + limit) / limit, limit, totalRecordCount, data);
			case STRIP_LIST -> new StripList<>(firstRecordOffset, limit, totalRecordCount, data);
		};
	}

	/**
	 * Method creates copy of this request with changed `entityType` and entity `requirements`. The copy will share
	 * already resolved and memoized values of this request except those that relate to the changed entity type and
	 * requirements.
	 */
	@Nonnull
	public EvitaRequest deriveCopyWith(@Nonnull String entityType, @Nonnull EntityFetchRequire requirements) {
		return new EvitaRequest(
			this,
			entityType, requirements
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
		@Nonnull FilterBy filterConstraint,
		@Nullable OrderBy orderConstraint
		) {
		return new EvitaRequest(
			this,
			entityType, filterConstraint, orderConstraint
		);
	}

	/**
	 * Internal method that consults input query and initializes pagination information.
	 * If there is no pagination in the input query, first page with size of 20 records is used as default.
	 */
	private void initPagination() {
		final Optional<Page> page = ofNullable(QueryUtils.findRequire(query, Page.class));
		final Optional<Strip> strip = ofNullable(QueryUtils.findRequire(query, Strip.class));
		if (page.isPresent()) {
			limit = page.get().getPageSize();
			firstRecordOffset = PaginatedList.getFirstItemNumberForPage(page.get().getPageNumber(), limit);
			resultForm = EvitaRequest.ResultForm.PAGINATED_LIST;
		} else if (strip.isPresent()) {
			limit = strip.get().getLimit();
			firstRecordOffset = strip.get().getOffset();
			resultForm = EvitaRequest.ResultForm.STRIP_LIST;
		} else {
			limit = 20;
			firstRecordOffset = 0;
			resultForm = EvitaRequest.ResultForm.PAGINATED_LIST;
		}
	}

	private enum ResultForm {
		PAGINATED_LIST, STRIP_LIST
	}

	/**
	 * Simple DTO that allows collection of {@link ReferenceContent} inner constraints related to fetching the entity
	 * and group entity for fast access in this evita request instance.
	 *
	 * @param entityFetch      requirements related to fetching related entity
	 * @param entityGroupFetch requirements related to fetching related entity group
	 * @param filterBy         filtering constraints for entities
	 * @param orderBy          ordering constraints for entities
	 */
	public record RequirementContext(
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
	}

}
