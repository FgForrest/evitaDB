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

package io.evitadb.api.functional.facet;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;

/**
 * This test verifies whether entities can be filtered by facets.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
// TOBEDONE: deprecated - remove all facetSummary tests when FacetSummary constraint is removed (https://github.com/FgForrest/evitaDB/issues/538)
@Slf4j
@Tag(CONTRACT)
@Tag(FACET)
@Tag(FILTER)
public abstract class AbstractEntityByFacetFilteringFunctionalTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_ORDER = "order";
	private static final String THOUSAND_PRODUCTS_WITH_FACETS = "ThousandsProductsWithFacets";
	private static final String ATTRIBUTE_TRANSIENT = "transient";
	private static final int SEED = 40;
	private static final String EMPTY_COLLECTION_ENTITY = "someCollectionWithoutEntities";
	private final static int[] STORE_ORDER;
	private static final int STORE_COUNT = 12;
	/**
	 * A small hand-made data set exercising the facet relation settings on reference shapes the generated data set does
	 * not have. Products of {@link #ENTITY_SHAPED_PRODUCT} carry three faceted references:
	 *
	 * - {@link #REF_LABEL} - to labels of type {@link #ENTITY_LABEL}, grouped by {@link #ENTITY_LABEL_GROUP}, a type
	 *   not managed by evitaDB. Labels 1 and 2 belong to group {@link #LABEL_GROUP_A}, label 3 to group
	 *   {@link #LABEL_GROUP_B}, labels 4 and 5 belong to no group.
	 * - {@link #REF_TAG} - to tags of type {@link #ENTITY_TAG}, grouped by the managed type {@link #ENTITY_TAG_GROUP},
	 *   whose attribute {@link #ATTRIBUTE_NOTE} is not filterable. Tag {@link #GROUPED_TAG} belongs to group
	 *   {@link #TAG_GROUP}, tag {@link #UNGROUPED_TAG} belongs to no group. Each tag carries the filterable attribute
	 *   {@link #ATTRIBUTE_CODE} - `tag1` and `tag2`.
	 * - {@link #REF_SOURCE} - to sources of type {@link #ENTITY_SOURCE}, a type not managed by evitaDB, without any
	 *   group. Each reference carries the filterable reference attribute {@link #ATTRIBUTE_CHANNEL}, whose value is
	 *   decided by the source - see {@link #SOURCE_CHANNELS}.
	 * - {@link #REF_MARK} - to marks of type {@link #ENTITY_MARK}, a type not managed by evitaDB, grouped by the managed
	 *   type {@link #ENTITY_MARK_GROUP}, whose collection holds no entity at all. Its attribute {@link #ATTRIBUTE_CODE}
	 *   is filterable, its attribute {@link #ATTRIBUTE_NOTE} is not. Product 1 references mark {@link #UNGROUPED_MARK},
	 *   which belongs to no group.
	 *
	 * The labels and tags themselves are managed entities, so that the options can be selected in `facetHaving`
	 * by anything, while the sources can be selected only by what the products themselves store about them.
	 *
	 * | product | labels | tags | sources |
	 * |---------|--------|------|---------|
	 * | 1       | 1      | 1    | 1       |
	 * | 2       | 1, 3   | 2    | 2       |
	 * | 3       | 2      |      | 1       |
	 * | 4       | 3      | 1, 2 |         |
	 * | 5       | 4      |      | 2       |
	 * | 6       | 1, 4   | 1    | 1, 2    |
	 * | 7       | 5      | 2    |         |
	 * | 8       | 3, 4   |      | 1       |
	 * | 9       |        | 1    | 2       |
	 * | 10      | 2, 5   |      |         |
	 * | 11      | 1, 4   | 2    |         |
	 * | 12      |        |      | 1       |
	 */
	private static final String FACET_RELATION_SHAPES = "FacetRelationShapes";
	private static final String ENTITY_SHAPED_PRODUCT = "shapedProduct";
	private static final String ENTITY_LABEL = "shapedLabel";
	private static final String ENTITY_LABEL_GROUP = "externalLabelGroup";
	private static final String ENTITY_TAG = "shapedTag";
	private static final String ENTITY_TAG_GROUP = "tagGroup";
	private static final String REF_LABEL = "label";
	private static final String REF_TAG = "tag";
	private static final String ENTITY_SOURCE = "externalSource";
	private static final String REF_SOURCE = "source";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_CHANNEL = "channel";
	private static final String ENTITY_MARK = "externalMark";
	private static final String ENTITY_MARK_GROUP = "emptyMarkGroup";
	private static final String REF_MARK = "mark";
	private static final int UNGROUPED_MARK = 1;
	private static final int LABEL_GROUP_A = 10;
	private static final int LABEL_GROUP_B = 20;
	/**
	 * A label group no label belongs to.
	 */
	private static final int MISSING_LABEL_GROUP = 30;
	/**
	 * The group of each label, indexed by the label primary key minus one; NULL for a label without a group.
	 */
	private static final Integer[] LABEL_GROUPS = {LABEL_GROUP_A, LABEL_GROUP_A, LABEL_GROUP_B, null, null};
	private static final int TAG_GROUP = 1;
	private static final int GROUPED_TAG = 1;
	private static final int UNGROUPED_TAG = 2;
	/**
	 * A tag no product references.
	 */
	private static final int MISSING_TAG = 3;
	/**
	 * The labels of each product of {@link #FACET_RELATION_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SHAPED_PRODUCT_LABELS = {
		{1}, {1, 3}, {2}, {3}, {4}, {1, 4}, {5}, {3, 4}, {}, {2, 5}, {1, 4}, {}
	};
	/**
	 * The tags of each product of {@link #FACET_RELATION_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SHAPED_PRODUCT_TAGS = {
		{GROUPED_TAG}, {UNGROUPED_TAG}, {}, {GROUPED_TAG, UNGROUPED_TAG}, {}, {GROUPED_TAG}, {UNGROUPED_TAG}, {},
		{GROUPED_TAG}, {}, {UNGROUPED_TAG}, {}
	};
	/**
	 * The sources of each product of {@link #FACET_RELATION_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SHAPED_PRODUCT_SOURCES = {
		{1}, {2}, {1}, {}, {2}, {1, 2}, {}, {1}, {2}, {}, {}, {1}
	};
	/**
	 * The {@link #ATTRIBUTE_CHANNEL} every reference to a source carries, indexed by the source primary key minus one.
	 */
	private static final String[] SOURCE_CHANNELS = {"web", "shop"};
	/**
	 * A source no product references.
	 */
	private static final int MISSING_SOURCE = 3;
	/**
	 * A small hand-made data set exercising the reference summary over both scopes. Products of
	 * {@link #ENTITY_SCOPED_PRODUCT} reference tags of type {@link #ENTITY_SCOPED_TAG} by {@link #REF_TAG}, which is
	 * indexed and faceted in every scope. The tags are grouped by the managed type {@link #ENTITY_SCOPED_TAG_GROUP},
	 * whose entities - the groups 100, 200 and 300 - are all live, so the type has no index of the archived scope. Its
	 * attribute {@link #ATTRIBUTE_CODE} is filterable in every scope, its attribute {@link #ATTRIBUTE_NOTE} is not
	 * filterable at all. The tags themselves are all live as well, with the same two attributes. Tags 10, 11, 12 and
	 * 13 belong to group 100, tag 20 to group 200 and tag 30 to group 300.
	 *
	 * | product | scope    | tags   |
	 * |---------|----------|--------|
	 * | 1       | LIVE     | 10     |
	 * | 2       | LIVE     | 11     |
	 * | 3       | ARCHIVED | 20, 11 |
	 * | 4       | ARCHIVED | 20     |
	 * | 5       | LIVE     | 20, 11 |
	 * | 6       | LIVE     | 20     |
	 * | 7       | ARCHIVED | 10     |
	 * | 8       | ARCHIVED | 11     |
	 * | 9       | LIVE     | 12     |
	 * | 10      | ARCHIVED | 12, 20 |
	 * | 11      | LIVE     | 30, 10 |
	 * | 12      | ARCHIVED | 30, 20 |
	 * | 13      | ARCHIVED | 13     |
	 */
	private static final String FACET_SCOPE_SHAPES = "FacetScopeShapes";
	private static final String ENTITY_SCOPED_PRODUCT = "scopedProduct";
	private static final String ENTITY_SCOPED_TAG = "scopedTag";
	private static final String ENTITY_SCOPED_TAG_GROUP = "scopedTagGroup";
	/**
	 * The tags of the {@link #FACET_SCOPE_SHAPES} data set.
	 */
	private static final int[] SCOPED_TAGS = {10, 11, 12, 13, 20, 30};
	/**
	 * The group of each tag of {@link #SCOPED_TAGS}, at the same index.
	 */
	private static final int[] SCOPED_TAG_GROUPS = {100, 100, 100, 100, 200, 300};
	/**
	 * The tags of each product of {@link #FACET_SCOPE_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SCOPED_PRODUCT_TAGS = {
		{10}, {11}, {20, 11}, {20}, {20, 11}, {20}, {10}, {11}, {12}, {12, 20}, {30, 10}, {30, 20}, {13}
	};
	/**
	 * The scope of each product of {@link #FACET_SCOPE_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final Scope[] SCOPED_PRODUCT_SCOPES = {
		Scope.LIVE, Scope.LIVE, Scope.ARCHIVED, Scope.ARCHIVED, Scope.LIVE, Scope.LIVE, Scope.ARCHIVED, Scope.ARCHIVED,
		Scope.LIVE, Scope.ARCHIVED, Scope.LIVE, Scope.ARCHIVED, Scope.ARCHIVED
	};
	/**
	 * A small hand-made data set exercising facets whose group is a property of the reference rather than of the
	 * facet: products of {@link #ENTITY_GROUPING_PRODUCT} reference tags of type {@link #ENTITY_GROUPING_TAG} by
	 * {@link #REF_TAG}, indexed and faceted in every scope and grouped by the managed type
	 * {@link #ENTITY_GROUPING_TAG_GROUP} (groups 100, 200 and 300, all live). Tag 13 is referenced by archived products
	 * only, tag 40 without a group, tag 50 in group 100 by one product and in group 200 by others, and tag 60 in group
	 * 300 by one product and without a group by another.
	 *
	 * | product | scope    | tags (group)          |
	 * |---------|----------|-----------------------|
	 * | 1       | LIVE     | 10 (100)              |
	 * | 2       | LIVE     | 11 (100), 40 (-)      |
	 * | 3       | LIVE     | 20 (200)              |
	 * | 4       | LIVE     | 10 (100), 20 (200)    |
	 * | 5       | ARCHIVED | 13 (100)              |
	 * | 6       | ARCHIVED | 13 (100), 20 (200)    |
	 * | 7       | LIVE     | 50 (100)              |
	 * | 8       | LIVE     | 50 (200), 10 (100)    |
	 * | 9       | LIVE     | 60 (300)              |
	 * | 10      | LIVE     | 60 (-), 20 (200)      |
	 * | 11      | ARCHIVED | 50 (200)              |
	 * | 12      | LIVE     | 40 (-), 10 (100)      |
	 */
	private static final String FACET_GROUPING_SHAPES = "FacetGroupingShapes";
	private static final String ENTITY_GROUPING_PRODUCT = "groupingProduct";
	private static final String ENTITY_GROUPING_TAG = "groupingTag";
	private static final String ENTITY_GROUPING_TAG_GROUP = "groupingTagGroup";
	/**
	 * The tags of the {@link #FACET_GROUPING_SHAPES} data set; a primary key missing here belongs to no tag entity.
	 */
	private static final int[] GROUPING_TAGS = {10, 11, 13, 20, 40, 50, 60};
	/**
	 * The groups of the {@link #FACET_GROUPING_SHAPES} data set.
	 */
	private static final int[] GROUPING_TAG_GROUPS = {100, 200, 300};
	/**
	 * The marker of a reference without a group in {@link #GROUPING_PRODUCT_TAG_GROUPS}.
	 */
	private static final int NO_GROUP = 0;
	/**
	 * The tags of each product of {@link #FACET_GROUPING_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] GROUPING_PRODUCT_TAGS = {
		{10}, {11, 40}, {20}, {10, 20}, {13}, {13, 20}, {50}, {50, 10}, {60}, {60, 20}, {50}, {40, 10}
	};
	/**
	 * The group of each reference of {@link #GROUPING_PRODUCT_TAGS}, at the same position, {@link #NO_GROUP} for a
	 * reference without a group.
	 */
	private static final int[][] GROUPING_PRODUCT_TAG_GROUPS = {
		{100}, {100, NO_GROUP}, {200}, {100, 200}, {100}, {100, 200}, {100}, {200, 100}, {300}, {NO_GROUP, 200}, {200},
		{NO_GROUP, 100}
	};
	/**
	 * The scope of each product of {@link #FACET_GROUPING_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final Scope[] GROUPING_PRODUCT_SCOPES = {
		Scope.LIVE, Scope.LIVE, Scope.LIVE, Scope.LIVE, Scope.ARCHIVED, Scope.ARCHIVED, Scope.LIVE, Scope.LIVE,
		Scope.LIVE, Scope.LIVE, Scope.ARCHIVED, Scope.LIVE
	};
	/**
	 * A small hand-made data set exercising two faceted references with the very same layout of facets and groups:
	 * live products of {@link #ENTITY_TWIN_PRODUCT} reference tags of type {@link #ENTITY_TWIN_TAG} both by
	 * {@link #REF_TAG} and by {@link #REF_TAG_COPY}, each grouped by the managed type {@link #ENTITY_TWIN_TAG_GROUP}
	 * (groups 100, 200 and 300). Tag 10 is referenced in group 100 by one product and in group 200 by another.
	 *
	 * | product | tags (group) of both references        |
	 * |---------|----------------------------------------|
	 * | 1       | 10 (100), 30 (300), 40 (300), 50 (100) |
	 * | 2       | 10 (200), 30 (300), 50 (100)           |
	 * | 3       | 20 (200)                               |
	 */
	private static final String FACET_TWIN_REFERENCE_SHAPES = "FacetTwinReferenceShapes";
	private static final String ENTITY_TWIN_PRODUCT = "twinProduct";
	private static final String ENTITY_TWIN_TAG = "twinTag";
	private static final String ENTITY_TWIN_TAG_GROUP = "twinTagGroup";
	private static final String REF_TAG_COPY = "tagCopy";
	/**
	 * The tags of the {@link #FACET_TWIN_REFERENCE_SHAPES} data set.
	 */
	private static final int[] TWIN_TAGS = {10, 20, 30, 40, 50};
	/**
	 * The groups of the {@link #FACET_TWIN_REFERENCE_SHAPES} data set.
	 */
	private static final int[] TWIN_TAG_GROUPS = {100, 200, 300};
	/**
	 * The tags each product of {@link #FACET_TWIN_REFERENCE_SHAPES} references by both references, indexed by the
	 * product primary key minus one.
	 */
	private static final int[][] TWIN_PRODUCT_TAGS = {{10, 30, 40, 50}, {10, 30, 50}, {20}};
	/**
	 * The group of each reference of {@link #TWIN_PRODUCT_TAGS}, at the same position.
	 */
	private static final int[][] TWIN_PRODUCT_TAG_GROUPS = {{100, 300, 300, 100}, {200, 300, 100}, {200}};

	static {
		STORE_ORDER = new int[STORE_COUNT];
		for (int i = 1; i <= STORE_COUNT; i++) {
			STORE_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), STORE_ORDER, STORE_COUNT);
	}

	/**
	 * Encapsulates all parameters needed to compute a facet summary. Uses a builder pattern
	 * to replace the previous telescoping overloads of `computeFacetSummary` with named,
	 * readable parameter assignments.
	 *
	 * Required fields are passed via the builder constructor; optional fields have setter methods.
	 *
	 * @param session the evitaDB session to use for entity lookups
	 * @param schema the entity schema describing references and facets
	 * @param entities the full list of product entities to compute facets from
	 * @param query the query containing filter/require constraints
	 * @param statisticsDepthSupplier function returning the statistics depth per reference name
	 * @param parameterGroupMapping mapping from parameter facet ID to its group ID
	 * @param entityFilter optional predicate to pre-filter entities (e.g. by price availability)
	 * @param referencePredicate optional predicate to filter individual references
	 * @param facetSorterFactory optional factory for facet sorting comparators per reference name
	 * @param facetGroupSorterFactory optional factory for facet group sorting comparators per reference name
	 * @param allowedReferenceNames optional supplier restricting which reference types appear in the summary
	 * @param facetEntityRequirementSupplier optional function providing entity fetch requirements for facet entities
	 * @param groupEntityRequirementSupplier optional function providing entity fetch requirements for group entities
	 * @param selectedFacetProvider optional function providing pre-selected facet IDs per reference name
	 * @param selectedEntitiesPredicate optional predicate for additional entity filtering (e.g. user filter criteria)
	 */
	private record FacetSummaryComputationParams(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<SealedEntity> entities,
		@Nonnull Query query,
		@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
		@Nonnull Map<Integer, Integer> parameterGroupMapping,
		@Nullable Predicate<SealedEntity> entityFilter,
		@Nullable Predicate<ReferenceContract> referencePredicate,
		@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nullable Supplier<Set<String>> allowedReferenceNames,
		@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier,
		@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier,
		@Nullable Function<String, int[]> selectedFacetProvider,
		@Nullable Predicate<SealedEntity> selectedEntitiesPredicate
	) {

		/**
		 * Builder for constructing [FacetSummaryComputationParams] instances. Required parameters
		 * are passed in the constructor; optional parameters are set via fluent setter methods.
		 */
		static class Builder {
			private final EvitaSessionContract session;
			private final EntitySchemaContract schema;
			private final List<SealedEntity> entities;
			private final Query query;
			private final Function<String, FacetStatisticsDepth> statisticsDepthSupplier;
			private final Map<Integer, Integer> parameterGroupMapping;
			private Predicate<SealedEntity> entityFilter;
			private Predicate<ReferenceContract> referencePredicate;
			private Function<String, Comparator<FacetStatistics>> facetSorterFactory;
			private Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory;
			private Supplier<Set<String>> allowedReferenceNames;
			private Function<String, EntityFetch> facetEntityRequirementSupplier;
			private Function<String, EntityGroupFetch> groupEntityRequirementSupplier;
			private Function<String, int[]> selectedFacetProvider;
			private Predicate<SealedEntity> selectedEntitiesPredicate;

			Builder(
				@Nonnull EvitaSessionContract session,
				@Nonnull EntitySchemaContract schema,
				@Nonnull List<SealedEntity> entities,
				@Nonnull Query query,
				@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
				@Nonnull Map<Integer, Integer> parameterGroupMapping
			) {
				this.session = session;
				this.schema = schema;
				this.entities = entities;
				this.query = query;
				this.statisticsDepthSupplier = statisticsDepthSupplier;
				this.parameterGroupMapping = parameterGroupMapping;
			}

			@Nonnull
			Builder entityFilter(@Nullable Predicate<SealedEntity> entityFilter) {
				this.entityFilter = entityFilter;
				return this;
			}

			@Nonnull
			Builder referencePredicate(@Nullable Predicate<ReferenceContract> referencePredicate) {
				this.referencePredicate = referencePredicate;
				return this;
			}

			@Nonnull
			Builder facetSorterFactory(@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory) {
				this.facetSorterFactory = facetSorterFactory;
				return this;
			}

			@Nonnull
			Builder facetGroupSorterFactory(
					@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory
			) {
				this.facetGroupSorterFactory = facetGroupSorterFactory;
				return this;
			}

			@Nonnull
			Builder allowedReferenceNames(@Nullable Supplier<Set<String>> allowedReferenceNames) {
				this.allowedReferenceNames = allowedReferenceNames;
				return this;
			}

			@Nonnull
			Builder facetEntityRequirementSupplier(@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier) {
				this.facetEntityRequirementSupplier = facetEntityRequirementSupplier;
				return this;
			}

			@Nonnull
			Builder groupEntityRequirementSupplier(@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier) {
				this.groupEntityRequirementSupplier = groupEntityRequirementSupplier;
				return this;
			}

			@Nonnull
			Builder selectedFacetProvider(@Nullable Function<String, int[]> selectedFacetProvider) {
				this.selectedFacetProvider = selectedFacetProvider;
				return this;
			}

			@Nonnull
			Builder selectedEntitiesPredicate(@Nullable Predicate<SealedEntity> selectedEntitiesPredicate) {
				this.selectedEntitiesPredicate = selectedEntitiesPredicate;
				return this;
			}

			@Nonnull
			FacetSummaryComputationParams build() {
				return new FacetSummaryComputationParams(
					this.session, this.schema, this.entities, this.query,
					this.statisticsDepthSupplier, this.parameterGroupMapping,
					this.entityFilter, this.referencePredicate,
					this.facetSorterFactory, this.facetGroupSorterFactory,
					this.allowedReferenceNames,
					this.facetEntityRequirementSupplier, this.groupEntityRequirementSupplier,
					this.selectedFacetProvider, this.selectedEntitiesPredicate
				);
			}
		}
	}

	/**
	 * Computes a facet summary by streaming through entities and grouping their references
	 * into facet group statistics. Supports filtering, sorting, impact computation, and
	 * entity/group enrichment based on the provided parameters.
	 *
	 * @param params the computation parameters encapsulating all inputs
	 * @return a [FacetSummaryWithResultCount] containing the computed facet summary and
	 *         the count of entities matching the facet filter
	 */
	private static FacetSummaryWithResultCount computeFacetSummary(
		@Nonnull FacetSummaryComputationParams params
	) {
		// this context allows us to create facet filtering predicates in correct way
		final FacetComputationalContext fcc = new FacetComputationalContext(
			params.schema(), params.query(), params.parameterGroupMapping(), params.selectedFacetProvider()
		);

		// filter entities by mandatory predicate
		final List<SealedEntity> filteredEntities = ofNullable(params.entityFilter())
			.map(it -> params.entities().stream().filter(it))
			.orElseGet(params.entities()::stream)
			.toList();

		// collect set of faceted reference types
		final Set<String> facetedEntities = params.schema().getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFaceted)
			.map(ReferenceSchemaContract::getReferencedEntityType)
			.collect(Collectors.toSet());

		// group facets by their entity type / group
		final Predicate<ReferenceContract> referencePredicate = params.referencePredicate();
		final Map<GroupReference, Map<ReferenceKey, Integer>> groupedFacets = filteredEntities
			.stream()
			.flatMap(it -> it.getReferences().stream())
			// filter out references by provided predicate
			.filter(it -> referencePredicate == null || referencePredicate.test(it))
			// filter out not faceted entity types
			.filter(it -> facetedEntities.contains(it.getReferenceName()))
			.collect(
				groupingBy(
					// create referenced entity type + referenced entity group id key
					it -> new GroupReference(
						params.schema().getReference(it.getReferenceName()).orElseThrow(),
						it.getGroup().map(EntityReferenceContract::getPrimaryKey).orElse(null)
					),
					TreeMap::new,
					// compute facet count
					groupingBy(
						ReferenceContract::getReferenceKey,
						() -> new TreeMap<>(ReferenceKey.GENERIC_COMPARATOR),
						summingInt(facet -> 1)
					)
				)
			);

		// group facets by their entity type / group and compute sum per group
		final Map<GroupReference, Integer> groupCount = filteredEntities
			.stream()
			.flatMap(entity -> entity.getReferences()
				.stream()
				// filter out references by provided predicate
				.filter(it -> referencePredicate == null || referencePredicate.test(it))
				.map(it ->
					new GroupReferenceWithEntityId(
						it.getReferenceName(),
						it.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null),
						entity.getPrimaryKeyOrThrowException()
					)
				))
			.distinct()
			.collect(
				groupingBy(
					entry -> new GroupReference(
						params.schema().getReference(entry.referenceName()).orElseThrow(),
						entry.groupId()
					),
					TreeMap::new,
					summingInt(entry -> 1)
				)
			);


		// filter entities by facets in input query (even if part of user filter) - use AND for different entity types, and
		// OR for facet ids
		final List<SealedEntity> filteredEntitiesIncludingUserFilter =
			params.selectedEntitiesPredicate() == null ?
				filteredEntities.stream().toList() :
				filteredEntities.stream().filter(params.selectedEntitiesPredicate()).toList();
		final Set<Integer> facetFilteredEntityIds = filteredEntitiesIncludingUserFilter
			.stream()
			.filter(fcc.createBaseFacetPredicate())
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		// if there facet group negation - invert the facet counts
		if (fcc.isAnyFacetGroupNegated(WITH_DIFFERENT_FACETS_IN_GROUP)) {
			groupedFacets.entrySet()
				.stream()
				.filter(it -> fcc.isFacetGroupNegated(it.getKey(), WITH_DIFFERENT_FACETS_IN_GROUP))
				.forEach(it ->
					// invert the results
					it.getValue()
						.entrySet()
						.forEach(facetCount -> facetCount.setValue(filteredEntitiesIncludingUserFilter.size() - facetCount.getValue()))
				);
		}

		final Map<String, Comparator<FacetStatistics>> cachedComparators = new HashMap<>();
		final Map<String, Comparator<FacetGroupStatistics>> cachedGroupComparators = new HashMap<>();

		return new FacetSummaryWithResultCount(
			facetFilteredEntityIds.size(),
			new FacetSummary(
				groupedFacets
					.entrySet()
					.stream()
					.filter(grouped -> Optional.ofNullable(params.allowedReferenceNames())
						.map(it -> it.get().contains(grouped.getKey().referenceSchema().getName()))
						.orElse(true))
					.map(it -> {
							final ReferenceSchemaContract referenceSchema = it.getKey().referenceSchema();
							return new FacetGroupStatistics(
								referenceSchema,
								ofNullable(it.getKey().groupId())
									.map(gId -> {
										final String entityType = Objects.requireNonNull(referenceSchema.getReferencedGroupType());
										final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(params.groupEntityRequirementSupplier())
											.map(supplier -> supplier.apply(referenceSchema.getName()))
											.orElse(null);
										if (groupEntityRequirement == null) {
											return new EntityReference(entityType, gId);
										}
										return params.session().getEntity(entityType, gId, groupEntityRequirement.getRequirements()).orElseThrow();
									})
									.orElse(null),
								groupCount.get(it.getKey()),
								it.getValue()
									.entrySet()
									.stream()
									.map(facet -> {
										// compute whether facet was part of input filter by
										final boolean requested = fcc.wasFacetRequested(facet.getKey());

										// fetch facet entity
										final EntityClassifier facetEntity;
										final String facetEntityType = referenceSchema.getReferencedEntityType();
										final int facetPrimaryKey = facet.getKey().primaryKey();
										final EntityFetch facetEntityRequirement = Optional.ofNullable(params.facetEntityRequirementSupplier())
											.map(supplier -> supplier.apply(referenceSchema.getName()))
											.orElse(null);
										if (facetEntityRequirement == null) {
											facetEntity = new EntityReference(facetEntityType, facetPrimaryKey);
										} else {
											facetEntity = params.session().getEntity(
													facetEntityType, facetPrimaryKey, facetEntityRequirement.getRequirements()
												).orElseThrow();
										}

										final FacetStatisticsDepth statisticsDepth = Optional.ofNullable(
											params.statisticsDepthSupplier().apply(referenceSchema.getName())
										).orElseThrow();

										// create facet statistics
										return new FacetStatistics(
											facetEntity,
											requested,
											facet.getValue(),
											statisticsDepth == FacetStatisticsDepth.IMPACT ?
												computeImpact(filteredEntitiesIncludingUserFilter, facetFilteredEntityIds, facet.getKey(), fcc) : null
										);
									})
									.sorted((o1, o2) -> compareFacet(
										referenceSchema.getName(), params.facetSorterFactory(), cachedComparators, o1, o2
									))
									.collect(toList())
							);
						}
					)
					.sorted((o1, o2) -> compareFacetGroup(params.facetGroupSorterFactory(), cachedGroupComparators, o1, o2))
					.collect(toList())
			)
		);
	}

	private static int compareFacet(
		@Nonnull String referenceName,
		@Nonnull Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nonnull Map<String, Comparator<FacetStatistics>> cachedGroupComparators,
		@Nonnull FacetStatistics o1,
		@Nonnull FacetStatistics o2
	) {
		final Comparator<FacetStatistics> comparator = cachedGroupComparators.computeIfAbsent(
			referenceName,
			theReferenceName -> ofNullable(facetSorterFactory)
				.map(it -> it.apply(theReferenceName))
				.orElseGet(() -> Comparator.comparing((FacetStatistics o12) -> o12.getFacetEntity().getPrimaryKey()))
		);
		return comparator.compare(o1, o2);
	}

	private static int compareFacetGroup(
		@Nonnull Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nonnull Map<String, Comparator<FacetGroupStatistics>> cachedGroupComparators,
		@Nonnull FacetGroupStatistics o1,
		@Nonnull FacetGroupStatistics o2
	) {
		final int referenceCmp = o1.getReferenceName().compareTo(o2.getReferenceName());
		if (referenceCmp == 0 && (o1.getGroupEntity() != null || o2.getGroupEntity() != null)) {
			if (o1.getGroupEntity() == null) {
				return 1;
			} else if (o2.getGroupEntity() == null) {
				return -1;
			} else {
				final Comparator<FacetGroupStatistics> comparator = cachedGroupComparators.computeIfAbsent(
					o1.getReferenceName(),
					theReferenceName -> ofNullable(facetGroupSorterFactory)
						.map(it -> it.apply(theReferenceName))
						.orElseGet(() -> Comparator.comparing((FacetGroupStatistics o12) -> o12.getGroupEntity().getPrimaryKey()))
				);
				return comparator.compare(o1, o2);
			}
		} else {
			return referenceCmp;
		}
	}

	@Nonnull
	private static RequestImpact computeImpact(
		@Nonnull List<SealedEntity> filteredEntities,
		@Nonnull Set<Integer> filteredEntityIds,
		@Nonnull ReferenceKey facet,
		@Nonnull FacetComputationalContext fcc
	) {
		// on already filtered entities
		final Predicate<? super SealedEntity> newPredicate = fcc.createTestFacetPredicate(facet);
		final Set<Integer> newResult = filteredEntities.stream()
			// apply newly created predicate with added current facet query
			.filter(newPredicate)
			// we need only primary keys
			.map(EntityContract::getPrimaryKey)
			// in set
			.collect(Collectors.toSet());

		final int difference = newResult.size() - filteredEntityIds.size();
		return new RequestImpact(
			// compute difference with base result
			difference,
			// pass new result count
			newResult.size(),
			// calculate has sense
			!newResult.isEmpty() && (
				// if there is difference
				(
					difference != 0 ||
						filteredEntities.stream()
							.anyMatch(fcc.createBaseFacetPredicateWithoutGroupOfFacet(facet))
				)
			)
		);
	}

	private static boolean isWithinHierarchy(
			Hierarchy categoryHierarchy, ReferenceContract category, int requestedCategoryId
	) {
		final int categoryId = category.getReferencedPrimaryKey();
		final String categoryIdAsString = String.valueOf(categoryId);
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
		// has parent node or requested category id
		return Objects.equals(requestedCategoryId, categoryId) ||
			parentItems
				.stream()
				.anyMatch(it -> Objects.equals(String.valueOf(requestedCategoryId), it.getCode()));
	}

	/**
	 * Creates a function that returns the given `ids` for the specified `entityType` and
	 * an empty int array for all other reference names. Used as a `selectedFacetProvider`
	 * when computing facet summaries with pre-selected hierarchical facets.
	 *
	 * @param entityType the reference name for which `ids` should be returned
	 * @param ids the facet IDs to return for the matching entity type
	 * @return a function mapping reference names to selected facet ID arrays
	 */
	@Nonnull
	private static Function<String, int[]> selectedFacetProviderFor(
		@Nonnull String entityType,
		@Nonnull int[] ids
	) {
		return referenceName -> entityType.equals(referenceName) ? ids : ArrayUtils.EMPTY_INT_ARRAY;
	}

	/**
	 * Tests whether a given entity's category references are within the specified hierarchy subtree
	 * rooted at `hierarchyRoot`, while excluding the subtree rooted at `excludedNodeId`.
	 *
	 * @param categoryHierarchy the hierarchy structure to navigate
	 * @param hierarchyRoot the root category ID of the subtree to include
	 * @param excludedNodeId the category ID whose subtree should be excluded
	 * @return a predicate that tests whether a sealed entity is within the hierarchy subtree
	 *         while not being under the excluded node
	 */
	@Nonnull
	private static Predicate<SealedEntity> isWithinHierarchyExcluding(
		@Nonnull Hierarchy categoryHierarchy,
		int hierarchyRoot,
		int excludedNodeId
	) {
		return sealedEntity -> sealedEntity.getReferences(Entities.CATEGORY)
			.stream()
			.anyMatch(it -> {
				final Set<Integer> parentItems = categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
					.stream()
					.map(theParent -> Integer.parseInt(theParent.getCode()))
					.collect(Collectors.toSet());
				return (it.getReferencedPrimaryKey() == hierarchyRoot || parentItems.contains(hierarchyRoot)) &&
					!(it.getReferencedPrimaryKey() == excludedNodeId || parentItems.contains(excludedNodeId));
			});
	}

	@Nonnull
	private static Integer[] getParametersWithDifferentGroups(
			List<SealedEntity> originalProductEntities, Set<Integer> groups
	) {
		final SealedEntity exampleProduct = originalProductEntities
			.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER)
				.stream()
				.filter(x -> x.getGroup().isPresent())
				.map(x -> x.getGroup().get().getPrimaryKey())
				.distinct()
				.count() >= 2
			)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
			"There is no product with two references to parameters in different groups!"
		));
		return exampleProduct.getReferences(Entities.PARAMETER)
			.stream()
			.filter(it -> it.getGroup().isPresent())
			.filter(it -> groups.add(it.getGroup().get().getPrimaryKey()))
			.map(ReferenceContract::getReferencedPrimaryKey)
			.toArray(Integer[]::new);
	}

	@Nonnull
	private static Integer[] getParametersWithSameGroup(List<SealedEntity> originalProductEntities, Set<Integer> groups) {
		return originalProductEntities
			.stream()
			.map(it -> {
				final Integer groupWithMultipleItems = it.getReferences(Entities.PARAMETER)
					.stream()
					.filter(x -> x.getGroup().isPresent())
					.collect(groupingBy(x -> x.getGroup().orElseThrow().getPrimaryKey(), Collectors.counting()))
					.entrySet()
					.stream()
					.filter(x -> x.getValue() > 2L)
					.map(Entry::getKey)
					.findFirst()
					.orElse(null);
				if (groupWithMultipleItems == null) {
					return null;
				} else {
					groups.add(groupWithMultipleItems);
					return it.getReferences(Entities.PARAMETER)
						.stream()
						.filter(x -> x.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(-1).equals(groupWithMultipleItems))
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toArray(Integer[]::new);
				}
			})
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
			"There is no product with two references to parameters in same group!"
		));
	}

	private static Set<Integer> getGroupsWithGaps(List<SealedEntity> originalProductEntities) {
		final Set<Integer> allGroupsPresent = originalProductEntities.stream()
			.flatMap(it -> it.getReferences(Entities.PARAMETER).stream())
			.filter(it -> it.getGroup().isPresent())
			.map(it -> it.getGroup().get().getPrimaryKey())
			.collect(Collectors.toSet());
		final Set<Integer> groupsWithGaps = new HashSet<>();
		for (SealedEntity product : originalProductEntities) {
			final Set<Integer> groupsPresentOnProduct = product.getReferences(Entities.PARAMETER).stream()
				.filter(it -> it.getGroup().isPresent())
				.map(it -> it.getGroup().get().getPrimaryKey())
				.collect(Collectors.toSet());
			allGroupsPresent
				.stream()
				.filter(it -> !groupsWithGaps.contains(it) && !groupsPresentOnProduct.contains(it))
				.forEach(groupsWithGaps::add);
		}
		return groupsWithGaps;
	}

	private static Integer[] getParametersInGroups(List<SealedEntity> originalProductEntities, Set<Integer> groups) {
		return originalProductEntities.stream()
			.flatMap(it -> it.getReferences(Entities.PARAMETER).stream())
			.filter(it -> it.getGroup().isPresent())
			.filter(it -> groups.contains(it.getGroup().get().getPrimaryKey()))
			.map(ReferenceContract::getReferencedPrimaryKey)
			.distinct()
			.toArray(Integer[]::new);
	}

	@Nullable
	@DataSet(value = THOUSAND_PRODUCTS_WITH_FACETS, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			final AtomicInteger index = new AtomicInteger();
			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.registerValueGenerator(
					Entities.STORE, ATTRIBUTE_ORDER,
					faker -> {
						final int ix = index.incrementAndGet();
						final int position = ArrayUtils.indexOf(ix, STORE_ORDER);
						return position == 0 ? Predecessor.HEAD : new Predecessor(STORE_ORDER[position - 1]);
					}
				).build();

			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(
									ATTRIBUTE_ORDER, Predecessor.class,
									AttributeSchemaEditor::sortable
								).updateVia(session);
							return schemaBuilder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(STORE_COUNT)
				.forEach(session::upsertEntity);

			final List<EntityReferenceContract> storedParameterGroups = dataGenerator.generateEntities(
					dataGenerator.getSampleParameterGroupSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(15)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReferenceContract> storedParameters = dataGenerator.generateEntities(
					dataGenerator.getSampleParameterSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(200)
				.map(session::upsertEntity)
				.toList();

			session.defineEntitySchema(EMPTY_COLLECTION_ENTITY)
				.withGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
				.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
				.updateVia(session);

			final SealedEntitySchema productSchema = dataGenerator.getSampleProductSchema(
				session,
				schemaBuilder -> {
					schemaBuilder
						.withReferenceToEntity(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							Entities.STORE, Entities.STORE, Cardinality.ZERO_OR_MORE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							EMPTY_COLLECTION_ENTITY, EMPTY_COLLECTION_ENTITY, Cardinality.ZERO_OR_MORE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							Entities.PARAMETER, Entities.PARAMETER,
							Cardinality.ZERO_OR_MORE,
							thatIs -> makeReferenceIndexed(thatIs).faceted()
								.withAttribute(ATTRIBUTE_TRANSIENT, Boolean.class, AttributeSchemaEditor::filterable)
								.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
						);
				}
			);
			final List<EntityReferenceContract> storedProducts = dataGenerator.generateEntities(
					productSchema,
					randomEntityPicker,
					SEED
				)
				.limit(1000)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				tuple(
					"originalProductEntities",
					storedProducts.stream()
						.map(it -> session.getEntity(
							it.getType(), it.getPrimaryKey(),
							attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll(), priceContentAll()
						).orElse(null))
						.collect(toList())
				),
				tuple(
					"parameterIndex",
					storedParameters.stream()
						.collect(
							toMap(
								EntityReferenceContract::getPrimaryKey,
								it -> session.getEntity(
								it.getType(), it.getPrimaryKeyOrThrowException(),
								attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()
							).orElse(null)
							)
						)
				),
				tuple(
					"parameterGroupIndex",
					storedParameterGroups.stream()
						.collect(
							toMap(
								EntityReferenceContract::getPrimaryKey,
								it -> session.getEntity(
								it.getType(), it.getPrimaryKeyOrThrowException(),
								attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()
							).orElse(null)
							)
						)
				),
				tuple(
					"categoryHierarchy",
					dataGenerator.getHierarchy(Entities.CATEGORY)
				),
				tuple(
					"productSchema",
					productSchema
				),
				tuple(
					"parameterGroupMapping",
					dataGenerator.getParameterIndex().get(Entities.PARAMETER)
				)
			);
		});
	}

	/**
	 * Configures the provided ReferenceSchemaBuilder to be indexed.
	 *
	 * @param whichIs the ReferenceSchemaBuilder instance to be configured as indexed
	 * @return the configured ReferenceSchemaBuilder instance
	 */
	@Nonnull
	protected abstract ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs);

	/**
	 * Configures the provided ReferenceSchemaBuilder to be indexed in every scope, the same way
	 * {@link #makeReferenceIndexed(ReferenceSchemaBuilder)} configures it for the default scope.
	 *
	 * @param whichIs the ReferenceSchemaBuilder instance to be configured as indexed
	 * @return the configured ReferenceSchemaBuilder instance
	 */
	@Nonnull
	protected ReferenceSchemaBuilder makeReferenceIndexedInEveryScope(@Nonnull ReferenceSchemaBuilder whichIs) {
		return whichIs.indexedForFilteringInScope(Scope.values());
	}

	/**
	* @deprecated Use {@link #shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesUsingReferenceSummary}
	* instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should throw exception when accessing localized attributes on fetched entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntities(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								facetSummary(
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesOnExplicitReferenceUsingReferenceSummary}
	* instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should throw exception when accessing localized attributes on fetched entities using explicit reference")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesOnExplicitReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								facetSummaryOfReference(
									Entities.PARAMETER,
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldNotReturnReferenceSummaryForMissingReferencesOnProduct} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should not return facet summary for missing references on product")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldNotReturnFacetSummaryForMissingReferencesOnProduct(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							not(referenceHaving(Entities.BRAND))
						),
						require(
							page(1, 1),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(referenceContent(Entities.BRAND)),
							facetSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.COUNTS
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertTrue(result.getRecordData().get(0).getReferences(Entities.BRAND).isEmpty());
				assertNull(result.getExtraResult(FacetSummary.class).getFacetGroupStatistics(Entities.BRAND));
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnEmptyReferenceSummaryForEmptyCollection} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should return empty facet summary for empty collection")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test()
	void shouldReturnEmptyFacetSummaryForEmptyCollection(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetSummaryOfReference(
								EMPTY_COLLECTION_ENTITY,
								FacetStatisticsDepth.COUNTS,
								filterBy(attributeEquals(ATTRIBUTE_NAME, "anything"))
							)
						)
					),
					EntityReference.class
				);

				final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
				assertNotNull(facetSummary);
				assertTrue(facetSummary.getReferenceStatistics().isEmpty());
				return null;
			}
		);
	}

	@DisplayName("Should return products matching random facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest()
	@MethodSource("returnRandomSeed")
	void shouldReturnProductsWithSpecifiedFacetInEntireSet(
		long seed,
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random rnd = new Random(seed);
				for (String entityType : new String[]{Entities.CATEGORY, Entities.BRAND, Entities.STORE}) {
					final int entityCount = session.getEntityCollectionSize(entityType);
					// for each entity execute 100 pseudo random queries
					final int numberOfSelectedFacets = 1 + rnd.nextInt(5);
					final Integer[] facetIds = new Integer[numberOfSelectedFacets];
					for (int j = 0; j < numberOfSelectedFacets; j++) {
						final int primaryKey = rnd.nextInt(entityCount - 1) + 1;
						facetIds[j] = primaryKey;
					}

					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								userFilter(
									facetHaving(entityType, entityPrimaryKeyInSet(facetIds))
								)
							),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);

					final Set<Integer> selectedIdsAsSet = new HashSet<>(Arrays.asList(facetIds));
					assertResultIs(
						"Querying " + entityType + " facets: " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> sealedEntity
							.getReferences(entityType)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.anyMatch(selectedIdsAsSet::contains),
						result.getRecordData()
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Should return products matching group AND combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupAndCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] parameters = getParametersWithDifferentGroups(originalProductEntities, new HashSet<>());
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.allMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching group OR combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupOrCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] parameters = getParametersWithDifferentGroups(originalProductEntities, groups);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsDisjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groups.toArray(new Integer[0]))))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.anyMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return the same products whichever level a negation is declared at")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSameProductsForNegationDeclaredAtEitherLevel(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> groups = getGroupsWithGaps(originalProductEntities);
				final Integer[] parameters = getParametersInGroups(originalProductEntities, groups);
				final Integer[] groupIds = groups.toArray(new Integer[0]);

				// negating each facet and combining with AND is the same set as negating the group's own
				// disjunction - `!a && !b` is `!(a || b)` - so the declared level cannot change the answer
				final EvitaResponse<EntityReference> withinGroup = session.query(
					negationQuery(parameters, groupIds, FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP),
					EntityReference.class
				);
				final EvitaResponse<EntityReference> betweenGroups = session.query(
					negationQuery(parameters, groupIds, FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS),
					EntityReference.class
				);

				assertEquals(betweenGroups.getTotalRecordCount(), withinGroup.getTotalRecordCount());
				assertFalse(withinGroup.getRecordData().isEmpty());
				assertEquals(
					betweenGroups.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					withinGroup.getRecordData().stream().map(EntityReference::getPrimaryKey).toList()
				);
				return null;
			}
		);
	}

	/**
	 * Builds the query used by {@link #shouldReturnSameProductsForNegationDeclaredAtEitherLevel} - the selected
	 * parameter facets with their groups negated at the requested relation level.
	 *
	 * @param parameters facet primary keys to select in the user filter
	 * @param groupIds   primary keys of the groups the negation applies to
	 * @param level      level the negation is declared at
	 * @return the query to execute
	 */
	@Nonnull
	private static Query negationQuery(
		@Nonnull Integer[] parameters,
		@Nonnull Integer[] groupIds,
		@Nonnull FacetGroupRelationLevel level
	) {
		return query(
			collection(Entities.PRODUCT),
			filterBy(
				userFilter(
					facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
				)
			),
			require(
				page(1, Integer.MAX_VALUE),
				facetGroupsNegation(Entities.PARAMETER, level, filterBy(entityPrimaryKeyInSet(groupIds)))
			)
		);
	}

	@DisplayName("Should return products matching group NOT combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupNotCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> groups = getGroupsWithGaps(originalProductEntities);
				final Integer[] parameters = getParametersInGroups(originalProductEntities, groups);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsNegation(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groups.toArray(new Integer[0]))))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.noneMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the negated option witness over the {@link #THOUSAND_PRODUCTS_WITH_FACETS} data set. Each row
	 * is a label, the reference, the number of options selected in it, whether the selected options belong to a group,
	 * and the factory of the negation requirement, which receives the group of the first selected option (NULL for an
	 * option without a group).
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> negatedOptionRows() {
		return Stream.of(
			Arguments.of(
				"brand, within group", Entities.BRAND, 1, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.BRAND)
			),
			Arguments.of(
				"brand, between groups", Entities.BRAND, 1, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.BRAND, WITH_DIFFERENT_GROUPS)
			),
			Arguments.of(
				"store, two options", Entities.STORE, 2, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.STORE)
			),
			Arguments.of(
				"store, between groups", Entities.STORE, 1, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.STORE, WITH_DIFFERENT_GROUPS)
			),
			Arguments.of(
				"parameter, no group filter", Entities.PARAMETER, 1, true,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.PARAMETER)
			),
			Arguments.of(
				"parameter, group filter, between groups", Entities.PARAMETER, 1, true,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(
					Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(group))
				)
			)
		);
	}

	/**
	 * Checks that a negated option selected in `facetHaving` excludes the products referencing it from the result
	 * whether the option belongs to a group or not, and at whichever level the negation is declared. The expected
	 * products are the ones referencing none of the selected options; when a single option is selected, the result
	 * must also be as large as the count the reference summary predicts for that option.
	 *
	 * The selected options are those with the lowest primary keys any product references.
	 *
	 * @param label                   the row label, used in the test name only
	 * @param referenceName           the reference the options are selected in
	 * @param optionCount             the number of options to select
	 * @param grouped                 whether the selected options belong to a group
	 * @param relationFactory         creates the negation requirement from the group of the first selected option
	 * @param evita                   the engine instance provided by the test extension
	 * @param originalProductEntities the products of the data set
	 */
	@DisplayName("Should return the products the reference summary counts for a negated option")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest(name = "{0}")
	@MethodSource("negatedOptionRows")
	void shouldReturnProductsReferenceSummaryCountsForNegatedOption(
		@Nonnull String label,
		@Nonnull String referenceName,
		int optionCount,
		boolean grouped,
		@Nonnull Function<Integer, RequireConstraint> relationFactory,
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		final int[] optionIds = originalProductEntities.stream()
			.flatMap(it -> it.getReferences(referenceName).stream())
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.distinct()
			.sorted()
			.limit(optionCount)
			.toArray();
		assertEquals(optionCount, optionIds.length, "the data set must reference enough options");
		final Integer groupId = originalProductEntities.stream()
			.flatMap(it -> it.getReferences(referenceName).stream())
			.filter(it -> it.getReferencedPrimaryKey() == optionIds[0])
			.findFirst()
			.flatMap(ReferenceContract::getGroup)
			.map(GroupEntityReference::getPrimaryKey)
			.orElse(null);
		assertEquals(grouped, groupId != null, "the selected option must " + (grouped ? "" : "not ") + "have a group");

		final Set<Integer> selectedIds = Arrays.stream(optionIds).boxed().collect(Collectors.toSet());
		final Predicate<SealedEntity> referencesNoSelectedOption = product -> product.getReferences(referenceName)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.noneMatch(selectedIds::contains);
		final long expectedCount = originalProductEntities.stream().filter(referencesNoSelectedOption).count();
		assertTrue(
			expectedCount > 0 && expectedCount < originalProductEntities.size(),
			"the negation must exclude some products and keep others"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final RequireConstraint relation = relationFactory.apply(groupId);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(referenceName, entityPrimaryKeyInSet(Arrays.stream(optionIds).boxed().toArray(Integer[]::new)))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							relation
						)
					),
					EntityReference.class
				);
				assertResultIs(
					"Querying " + referenceName + " options " + Arrays.toString(optionIds) + " negated",
					originalProductEntities,
					referencesNoSelectedOption,
					result.getRecordData()
				);

				if (optionIds.length == 1) {
					final EvitaResponse<EntityReference> withSummary = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								userFilter(
									facetHaving(referenceName, entityPrimaryKeyInSet(optionIds[0]))
								)
							),
							require(
								page(1, 1),
								referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS),
								relation
							)
						),
						EntityReference.class
					);
					assertEquals(expectedCount, withSummary.getTotalRecordCount());
					assertEquals(
						expectedCount,
						facetCountOf(withSummary, referenceName, groupId, optionIds[0]),
						"the reference summary must count the products the negated option leaves in the result"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Builds the small hand-made data set described on {@link #FACET_RELATION_SHAPES}.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FACET_RELATION_SHAPES, destroyAfterClass = true)
	void setUpFacetRelationShapes(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TAG_GROUP)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withAttribute(ATTRIBUTE_NOTE, String.class)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_TAG_GROUP, TAG_GROUP)
						.setAttribute(ATTRIBUTE_CODE, "tagGroup")
						.setAttribute(ATTRIBUTE_NOTE, "not filterable")
				);
				session.defineEntitySchema(ENTITY_MARK_GROUP)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withAttribute(ATTRIBUTE_NOTE, String.class)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_LABEL)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				for (int labelId = 1; labelId <= LABEL_GROUPS.length; labelId++) {
					session.upsertEntity(session.createNewEntity(ENTITY_LABEL, labelId));
				}
				session.defineEntitySchema(ENTITY_TAG)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				for (final int tagId : new int[]{GROUPED_TAG, UNGROUPED_TAG}) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_TAG, tagId).setAttribute(ATTRIBUTE_CODE, "tag" + tagId)
					);
				}
				session.defineEntitySchema(ENTITY_SHAPED_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_LABEL, ENTITY_LABEL, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted().withGroupType(ENTITY_LABEL_GROUP)
					)
					.withReferenceToEntity(
						REF_TAG, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted().withGroupTypeRelatedToEntity(ENTITY_TAG_GROUP)
					)
					.withReferenceTo(
						REF_SOURCE, ENTITY_SOURCE, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted()
							.withAttribute(ATTRIBUTE_CHANNEL, String.class, AttributeSchemaEditor::filterable)
					)
					.withReferenceTo(
						REF_MARK, ENTITY_MARK, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted().withGroupTypeRelatedToEntity(ENTITY_MARK_GROUP)
					)
					.updateVia(session);
				for (int pk = 1; pk <= SHAPED_PRODUCT_LABELS.length; pk++) {
					final EntityBuilder product = session.createNewEntity(ENTITY_SHAPED_PRODUCT, pk);
					for (final int labelId : SHAPED_PRODUCT_LABELS[pk - 1]) {
						final Integer labelGroup = LABEL_GROUPS[labelId - 1];
						product.setReference(
							REF_LABEL, labelId,
							labelGroup == null ? null : whichIs -> whichIs.setGroup(labelGroup)
						);
					}
					for (final int tagId : SHAPED_PRODUCT_TAGS[pk - 1]) {
						product.setReference(
							REF_TAG, tagId,
							tagId == GROUPED_TAG ? whichIs -> whichIs.setGroup(TAG_GROUP) : null
						);
					}
					for (final int sourceId : SHAPED_PRODUCT_SOURCES[pk - 1]) {
						product.setReference(
							REF_SOURCE, sourceId,
							whichIs -> whichIs.setAttribute(ATTRIBUTE_CHANNEL, SOURCE_CHANNELS[sourceId - 1])
						);
					}
					if (pk == 1) {
						product.setReference(REF_MARK, UNGROUPED_MARK);
					}
					session.upsertEntity(product);
				}
			}
		);
	}

	/**
	 * Builds the small hand-made data set described on {@link #FACET_SCOPE_SHAPES}.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FACET_SCOPE_SHAPES, destroyAfterClass = true)
	void setUpFacetScopeShapes(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_SCOPED_TAG_GROUP)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(Scope.values()))
					.withAttribute(ATTRIBUTE_NOTE, String.class)
					.updateVia(session);
				for (final int groupId : IntStream.of(SCOPED_TAG_GROUPS).distinct().toArray()) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_SCOPED_TAG_GROUP, groupId)
							.setAttribute(ATTRIBUTE_CODE, "group" + groupId)
							.setAttribute(ATTRIBUTE_NOTE, "not filterable")
					);
				}
				session.defineEntitySchema(ENTITY_SCOPED_TAG)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(Scope.values()))
					.withAttribute(ATTRIBUTE_NOTE, String.class)
					.updateVia(session);
				for (final int tagId : SCOPED_TAGS) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_SCOPED_TAG, tagId)
							.setAttribute(ATTRIBUTE_CODE, "tag" + tagId)
							.setAttribute(ATTRIBUTE_NOTE, "not filterable")
					);
				}
				session.defineEntitySchema(ENTITY_SCOPED_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_TAG, ENTITY_SCOPED_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexedInEveryScope(whichIs)
							.facetedInScope(Scope.values())
							.withGroupTypeRelatedToEntity(ENTITY_SCOPED_TAG_GROUP)
					)
					.updateVia(session);
				for (int pk = 1; pk <= SCOPED_PRODUCT_TAGS.length; pk++) {
					final EntityBuilder product = session.createNewEntity(ENTITY_SCOPED_PRODUCT, pk);
					for (final int tagId : SCOPED_PRODUCT_TAGS[pk - 1]) {
						product.setReference(REF_TAG, tagId, whichIs -> whichIs.setGroup(scopedTagGroupOf(tagId)));
					}
					session.upsertEntity(product);
					if (SCOPED_PRODUCT_SCOPES[pk - 1] == Scope.ARCHIVED) {
						session.archiveEntity(ENTITY_SCOPED_PRODUCT, pk);
					}
				}
			}
		);
	}

	/**
	 * Returns the group of the passed tag of the {@link #FACET_SCOPE_SHAPES} data set.
	 *
	 * @param tagId the tag
	 * @return the group of the tag
	 */
	private static int scopedTagGroupOf(int tagId) {
		return SCOPED_TAG_GROUPS[ArrayUtils.indexOf(tagId, SCOPED_TAGS)];
	}

	/**
	 * Builds the small hand-made data set described on {@link #FACET_GROUPING_SHAPES}.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FACET_GROUPING_SHAPES, destroyAfterClass = true)
	void setUpFacetGroupingShapes(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_GROUPING_TAG_GROUP)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				for (final int groupId : GROUPING_TAG_GROUPS) {
					session.upsertEntity(session.createNewEntity(ENTITY_GROUPING_TAG_GROUP, groupId));
				}
				session.defineEntitySchema(ENTITY_GROUPING_TAG)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				for (final int tagId : GROUPING_TAGS) {
					session.upsertEntity(session.createNewEntity(ENTITY_GROUPING_TAG, tagId));
				}
				session.defineEntitySchema(ENTITY_GROUPING_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_TAG, ENTITY_GROUPING_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexedInEveryScope(whichIs)
							.facetedInScope(Scope.values())
							.withGroupTypeRelatedToEntity(ENTITY_GROUPING_TAG_GROUP)
					)
					.updateVia(session);
				for (int pk = 1; pk <= GROUPING_PRODUCT_TAGS.length; pk++) {
					final EntityBuilder product = session.createNewEntity(ENTITY_GROUPING_PRODUCT, pk);
					for (int i = 0; i < GROUPING_PRODUCT_TAGS[pk - 1].length; i++) {
						final int groupId = GROUPING_PRODUCT_TAG_GROUPS[pk - 1][i];
						product.setReference(
							REF_TAG, GROUPING_PRODUCT_TAGS[pk - 1][i],
							groupId == NO_GROUP ? null : whichIs -> whichIs.setGroup(groupId)
						);
					}
					session.upsertEntity(product);
					if (GROUPING_PRODUCT_SCOPES[pk - 1] == Scope.ARCHIVED) {
						session.archiveEntity(ENTITY_GROUPING_PRODUCT, pk);
					}
				}
			}
		);
	}

	/**
	 * Builds the small hand-made data set described on {@link #FACET_TWIN_REFERENCE_SHAPES}.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FACET_TWIN_REFERENCE_SHAPES, destroyAfterClass = true)
	void setUpFacetTwinReferenceShapes(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TWIN_TAG_GROUP)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				for (final int groupId : TWIN_TAG_GROUPS) {
					session.upsertEntity(session.createNewEntity(ENTITY_TWIN_TAG_GROUP, groupId));
				}
				session.defineEntitySchema(ENTITY_TWIN_TAG)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				for (final int tagId : TWIN_TAGS) {
					session.upsertEntity(session.createNewEntity(ENTITY_TWIN_TAG, tagId));
				}
				final EntitySchemaBuilder productSchema = session.defineEntitySchema(ENTITY_TWIN_PRODUCT)
					.withoutGeneratedPrimaryKey();
				for (final String referenceName : new String[]{REF_TAG, REF_TAG_COPY}) {
					productSchema.withReferenceToEntity(
						referenceName, ENTITY_TWIN_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexedInEveryScope(whichIs)
							.facetedInScope(Scope.values())
							.withGroupTypeRelatedToEntity(ENTITY_TWIN_TAG_GROUP)
					);
				}
				productSchema.updateVia(session);
				for (int pk = 1; pk <= TWIN_PRODUCT_TAGS.length; pk++) {
					final EntityBuilder product = session.createNewEntity(ENTITY_TWIN_PRODUCT, pk);
					for (int i = 0; i < TWIN_PRODUCT_TAGS[pk - 1].length; i++) {
						final int groupId = TWIN_PRODUCT_TAG_GROUPS[pk - 1][i];
						for (final String referenceName : new String[]{REF_TAG, REF_TAG_COPY}) {
							product.setReference(
								referenceName, TWIN_PRODUCT_TAGS[pk - 1][i], whichIs -> whichIs.setGroup(groupId)
							);
						}
					}
					session.upsertEntity(product);
				}
			}
		);
	}

	/**
	 * Returns the rows of the relation witness over the {@link #FACET_RELATION_SHAPES} data set, whose label groups
	 * are not managed by evitaDB and whose label reference mixes grouped options with options without a group. Each
	 * row is a label, the selected labels, the relation requirement, and the primary keys of the products the query
	 * returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> labelRelationRows() {
		return Stream.of(
			Arguments.of(
				"unmanaged group, negation", new int[]{1},
				facetGroupsNegation(REF_LABEL),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, group filter, between groups", new int[]{3},
				facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))),
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"ungrouped option of a mixed reference", new int[]{4},
				facetGroupsNegation(REF_LABEL),
				shapedProductsWithLabels(false, 4)
			),
			Arguments.of(
				"disjunction across ungrouped and grouped", new int[]{3, 4},
				facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS),
				shapedProductsWithLabels(true, 3, 4)
			),
			// a group filter cannot match an option without a group, so the option stays a positive selection
			Arguments.of(
				"ungrouped option, group filter", new int[]{4},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 4)
			),
			Arguments.of(
				"unmanaged group, primary key filter", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key filter in a logical container", new int[]{1},
				facetGroupsNegation(
					REF_LABEL,
					filterBy(or(entityPrimaryKeyInSet(LABEL_GROUP_A), entityPrimaryKeyInSet(MISSING_LABEL_GROUP)))
				),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key filter in a negation", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(not(entityPrimaryKeyInSet(LABEL_GROUP_B)))),
				shapedProductsWithLabels(false, 1)
			),
			// the primary key ranges need nothing but the primary keys of the groups either, and each of them is run
			// once over the group it matches and once over a group it does not match
			Arguments.of(
				"unmanaged group, primary key between", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyBetween(LABEL_GROUP_A, LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key between, other group", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyBetween(LABEL_GROUP_A, LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 3)
			),
			Arguments.of(
				"unmanaged group, primary key greater than", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThan(LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"unmanaged group, primary key greater than, other group", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThan(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"unmanaged group, primary key greater than or equal", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThanEquals(LABEL_GROUP_B))),
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"unmanaged group, primary key greater than or equal, other group", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThanEquals(LABEL_GROUP_B))),
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"unmanaged group, primary key less than", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThan(LABEL_GROUP_B))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key less than, other group", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThan(LABEL_GROUP_B))),
				shapedProductsWithLabels(true, 3)
			),
			Arguments.of(
				"unmanaged group, primary key less than or equal", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThanEquals(LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key less than or equal, other group", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThanEquals(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 3)
			)
		);
	}

	/**
	 * Checks that the relation settings of a reference whose groups are not managed by evitaDB, and which mixes grouped
	 * options with options without a group, decide the query result the same way as for any other reference. When a
	 * single label is selected, the result must also be as large as the count the reference summary predicts for it.
	 *
	 * @param label    the row label, used in the test name only
	 * @param labelIds the selected labels
	 * @param relation the relation requirement of the label reference
	 * @param expected the primary keys of the products the query returns, ascending
	 * @param evita    the engine instance provided by the test extension
	 */
	@DisplayName("Should apply the relation settings to unmanaged groups and to options without a group")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("labelRelationRows")
	void shouldApplyRelationToUnmanagedGroupsAndOptionsWithoutGroup(
		@Nonnull String label,
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint relation,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the relation must exclude some products and keep others"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					shapedLabelQuery(labelIds, relation, null),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);

				if (labelIds.length == 1) {
					final EvitaResponse<EntityReference> withSummary = session.query(
						shapedLabelQuery(
							labelIds, relation, referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.COUNTS)
						),
						EntityReference.class
					);
					assertEquals(expected.length, withSummary.getTotalRecordCount());
					assertEquals(
						expected.length,
						facetCountOf(withSummary, REF_LABEL, LABEL_GROUPS[labelIds[0] - 1], labelIds[0]),
						"the reference summary must count the products the option leaves in the result"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the relation precedence witness over the {@link #FACET_RELATION_SHAPES} data set. Each row is
	 * a label, the reference the options are selected in, the selected options, the relation requirements - the
	 * request-wide defaults of `facetCalculationRules` and the relations declared for the reference - and the primary
	 * keys of the products the query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> relationPrecedenceRows() {
		final RequireConstraint disjunctionEverywhere = facetCalculationRules(
			FacetRelationType.DISJUNCTION, FacetRelationType.DISJUNCTION
		);
		final RequireConstraint negationBetweenGroups = facetCalculationRules(
			FacetRelationType.DISJUNCTION, FacetRelationType.NEGATION
		);
		final RequireConstraint conjunctionEverywhere = facetCalculationRules(
			FacetRelationType.CONJUNCTION, FacetRelationType.CONJUNCTION
		);
		final RequireConstraint negationOfGroupA = facetGroupsNegation(
			REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))
		);
		final RequireConstraint disjunctionOfGroupB = facetGroupsDisjunction(
			REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))
		);
		return Stream.of(
			Arguments.of(
				"explicit negation over default disjunction, option without a group", REF_SOURCE, new int[]{1},
				new RequireConstraint[]{disjunctionEverywhere, facetGroupsNegation(REF_SOURCE, WITH_DIFFERENT_GROUPS)},
				shapedProductsWithSources(false, 1)
			),
			Arguments.of(
				"explicit negation over default disjunction, group filter", REF_LABEL, new int[]{3},
				new RequireConstraint[]{
					disjunctionEverywhere,
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
				},
				shapedProductsWithLabels(false, 3)
			),
			// the group filter does not match the selected group, so the default decides it
			Arguments.of(
				"explicit negation of another group, default disjunction", REF_LABEL, new int[]{1},
				new RequireConstraint[]{
					disjunctionEverywhere,
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
				},
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"explicit conjunction between groups over default disjunction", REF_LABEL, new int[]{1, 3},
				new RequireConstraint[]{
					disjunctionEverywhere, facetGroupsConjunction(REF_LABEL, WITH_DIFFERENT_GROUPS)
				},
				shapedProductsWithAllLabels(1, 3)
			),
			Arguments.of(
				"explicit disjunction between groups over default negation", REF_LABEL, new int[]{3},
				new RequireConstraint[]{
					negationBetweenGroups,
					facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
				},
				shapedProductsWithLabels(true, 3)
			),
			Arguments.of(
				"explicit disjunction within a group over default conjunction", REF_LABEL, new int[]{1, 2},
				new RequireConstraint[]{
					conjunctionEverywhere, facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP)
				},
				shapedProductsWithLabels(true, 1, 2)
			),
			// of two explicit relations matching one group, negation is the one that applies
			Arguments.of(
				"explicit negation and disjunction of one group", REF_LABEL, new int[]{3},
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS),
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS)
				},
				shapedProductsWithLabels(false, 3)
			),
			// the defaults alone still apply
			Arguments.of(
				"default negation between groups", REF_LABEL, new int[]{3},
				new RequireConstraint[]{negationBetweenGroups},
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"default disjunction between groups", REF_LABEL, new int[]{1, 3},
				new RequireConstraint[]{disjunctionEverywhere},
				shapedProductsWithLabels(true, 1, 3)
			),
			Arguments.of(
				"default conjunction between groups", REF_LABEL, new int[]{1, 3},
				new RequireConstraint[]{conjunctionEverywhere},
				shapedProductsWithAllLabels(1, 3)
			),
			// a negated group subtracts its options from whatever the other groups select, disjunctive ones included,
			// whichever of the options is selected first
			Arguments.of(
				"explicit negation of a group and disjunction of another, negated option first", REF_LABEL,
				new int[]{1, 3},
				new RequireConstraint[]{negationOfGroupA, disjunctionOfGroupB},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			Arguments.of(
				"explicit negation of a group and disjunction of another, disjunctive option first", REF_LABEL,
				new int[]{3, 1},
				new RequireConstraint[]{negationOfGroupA, disjunctionOfGroupB},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			Arguments.of(
				"explicit negation of a group and disjunctive options without a group, negated option first",
				REF_LABEL, new int[]{1, 4},
				new RequireConstraint[]{negationOfGroupA, facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS)},
				shapedProductsWithLabelsExcept(new int[]{4}, 1)
			),
			Arguments.of(
				"explicit negation of a group and disjunctive options without a group, disjunctive option first",
				REF_LABEL, new int[]{4, 1},
				new RequireConstraint[]{negationOfGroupA, facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS)},
				shapedProductsWithLabelsExcept(new int[]{4}, 1)
			),
			Arguments.of(
				"explicit negation of a group and default conjunction of another, negated option first", REF_LABEL,
				new int[]{1, 3},
				new RequireConstraint[]{negationOfGroupA},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			Arguments.of(
				"explicit negation of a group and default conjunction of another, conjunctive option first", REF_LABEL,
				new int[]{3, 1},
				new RequireConstraint[]{negationOfGroupA},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			Arguments.of(
				"default negation within groups and explicit disjunction of another, negated option first", REF_LABEL,
				new int[]{1, 3},
				new RequireConstraint[]{
					facetCalculationRules(FacetRelationType.NEGATION, FacetRelationType.CONJUNCTION), disjunctionOfGroupB
				},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			Arguments.of(
				"default negation within groups and explicit disjunction of another, disjunctive option first",
				REF_LABEL, new int[]{3, 1},
				new RequireConstraint[]{
					facetCalculationRules(FacetRelationType.NEGATION, FacetRelationType.CONJUNCTION), disjunctionOfGroupB
				},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			// group A declares no relation of its own, so the default negation between groups negates it
			Arguments.of(
				"default negation between groups and explicit disjunction of another, negated option first", REF_LABEL,
				new int[]{1, 3},
				new RequireConstraint[]{negationBetweenGroups, disjunctionOfGroupB},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			),
			Arguments.of(
				"default negation between groups and explicit disjunction of another, disjunctive option first",
				REF_LABEL, new int[]{3, 1},
				new RequireConstraint[]{negationBetweenGroups, disjunctionOfGroupB},
				shapedProductsWithLabelsExcept(new int[]{3}, 1)
			)
		);
	}

	/**
	 * Checks that a relation declared for a reference decides the groups it matches whatever the request-wide default
	 * of `facetCalculationRules` says, that the default decides the groups no declared relation matches, and that the
	 * reference summary predicts the same products the query returns. A single selected option is checked against the
	 * count the summary computes for it, the last of several selected options against the impact the summary predicts
	 * for adding it to the others.
	 *
	 * @param label         the row label, used in the test name only
	 * @param referenceName the reference the options are selected in
	 * @param optionIds     the selected options
	 * @param relations     the relation requirements
	 * @param expected      the primary keys of the products the query returns, ascending
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should let a declared relation take precedence over the default one")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("relationPrecedenceRows")
	void shouldLetDeclaredRelationTakePrecedenceOverDefault(
		@Nonnull String label,
		@Nonnull String referenceName,
		@Nonnull int[] optionIds,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the relation must exclude some products and keep others"
		);
		final int lastOptionId = optionIds[optionIds.length - 1];
		final Integer lastGroupId = REF_LABEL.equals(referenceName) ? LABEL_GROUPS[lastOptionId - 1] : null;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					shapedOptionQuery(referenceName, optionIds, relations, null),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);

				if (optionIds.length == 1) {
					final EvitaResponse<EntityReference> withSummary = session.query(
						shapedOptionQuery(
							referenceName, optionIds, relations,
							referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS)
						),
						EntityReference.class
					);
					assertEquals(
						expected.length,
						facetStatisticsOf(withSummary, referenceName, lastGroupId, lastOptionId).getCount(),
						"the reference summary must count the products the option leaves in the result"
					);
				} else {
					final EvitaResponse<EntityReference> withSummary = session.query(
						shapedOptionQuery(
							referenceName, Arrays.copyOf(optionIds, optionIds.length - 1), relations,
							referenceSummaryOfReference(referenceName, FacetStatisticsDepth.IMPACT)
						),
						EntityReference.class
					);
					final RequestImpact impact = facetStatisticsOf(
						withSummary, referenceName, lastGroupId, lastOptionId
					).getImpact();
					assertNotNull(impact, "the reference summary must predict the impact of the option");
					assertEquals(
						expected.length,
						impact.matchCount(),
						"the reference summary must predict the products adding the option leaves in the result"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the relation setups of the label reference of the {@link #FACET_RELATION_SHAPES} data set whose reference
	 * summary must predict the query result exactly. Each row is a label, the relation requirements and the sources
	 * selected next to the labels. Exclusivity is left out on purpose - it changes only the summary, which then
	 * predicts a selection replacing the selection of the option's reference, see
	 * {@link #shouldPredictExclusiveOptionAsReplacingSelectionOfItsReferenceOnly}.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> predictedSelectionRows() {
		final RequireConstraint disjunctionEverywhere = facetCalculationRules(
			FacetRelationType.DISJUNCTION, FacetRelationType.DISJUNCTION
		);
		final RequireConstraint negationOfGroupA = facetGroupsNegation(
			REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))
		);
		final RequireConstraint disjunctionOfGroupB = facetGroupsDisjunction(
			REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))
		);
		final int[] noSource = new int[0];
		return Stream.of(
			Arguments.of("system defaults", new RequireConstraint[0], noSource),
			Arguments.of("negation of every group", new RequireConstraint[]{facetGroupsNegation(REF_LABEL)}, noSource),
			Arguments.of("negation of group A", new RequireConstraint[]{negationOfGroupA}, noSource),
			Arguments.of(
				"negation of group A within the group",
				new RequireConstraint[]{
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))
				},
				noSource
			),
			Arguments.of(
				"negation of group A, disjunction of group B",
				new RequireConstraint[]{negationOfGroupA, disjunctionOfGroupB},
				noSource
			),
			// the selection of another reference stays a separate part of the user filter, joined with the labels by
			// the conjunction of the user filter
			Arguments.of(
				"negation of group A, disjunction of group B, a source selected as well",
				new RequireConstraint[]{negationOfGroupA, disjunctionOfGroupB},
				new int[]{1}
			),
			Arguments.of(
				"negation of group A, disjunction of the other groups",
				new RequireConstraint[]{negationOfGroupA, facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS)},
				noSource
			),
			Arguments.of(
				"negation of group A, conjunction of group B, default disjunction of the options without a group",
				new RequireConstraint[]{
					disjunctionEverywhere, negationOfGroupA,
					facetGroupsConjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
				},
				noSource
			),
			Arguments.of(
				"default negation within groups, disjunction of group B",
				new RequireConstraint[]{
					facetCalculationRules(FacetRelationType.NEGATION, FacetRelationType.CONJUNCTION), disjunctionOfGroupB
				},
				noSource
			),
			Arguments.of(
				"default negation between groups, disjunction of group B",
				new RequireConstraint[]{
					facetCalculationRules(FacetRelationType.DISJUNCTION, FacetRelationType.NEGATION), disjunctionOfGroupB
				},
				noSource
			),
			Arguments.of("default disjunction between groups", new RequireConstraint[]{disjunctionEverywhere}, noSource),
			Arguments.of(
				"default disjunction between groups, conjunction of group A",
				new RequireConstraint[]{
					disjunctionEverywhere,
					facetGroupsConjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))
				},
				noSource
			),
			Arguments.of(
				"conjunction within group A",
				new RequireConstraint[]{
					facetGroupsConjunction(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))
				},
				noSource
			),
			// the relation between groups applies between the groups of one reference only, the selections of the
			// labels and of the sources are joined by the conjunction of the user filter whatever it is
			Arguments.of(
				"default disjunction between groups, a source selected as well",
				new RequireConstraint[]{disjunctionEverywhere},
				new int[]{1}
			),
			Arguments.of(
				"default disjunction between groups, conjunction of group A, a source selected as well",
				new RequireConstraint[]{
					disjunctionEverywhere,
					facetGroupsConjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))
				},
				new int[]{1}
			),
			Arguments.of(
				"disjunction between the groups of sources, a source selected as well",
				new RequireConstraint[]{facetGroupsDisjunction(REF_SOURCE, WITH_DIFFERENT_GROUPS)},
				new int[]{1}
			),
			Arguments.of(
				"disjunction between the groups of labels and of sources, a source selected as well",
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS),
					facetGroupsDisjunction(REF_SOURCE, WITH_DIFFERENT_GROUPS)
				},
				new int[]{1}
			)
		);
	}

	/**
	 * Checks that the reference summary predicts exactly what the query returns, whatever the relations of the label
	 * groups are: the count of each label with no label selected equals the size of the result selecting the label
	 * alone, and the impact of each label and each source not selected yet, predicted for every selection of one or
	 * two labels - and of no label when a source is selected - equals the size of the result selecting the option
	 * along with them. The oracle is the engine's own result, so the check holds for any relation setup the result
	 * honours. The impact of an option of the reference the user filter selects no option of is predicted along with
	 * the impact of an option joining the selection of its own reference. The summary of the tag reference is computed
	 * in the same query, because one formula generator serves the summaries of all references and must not hand the
	 * shape built for one of them to another. The count is defined for no selection at all, so it is checked only when
	 * no source is selected either.
	 *
	 * @param label     the row label, used in the test name only
	 * @param relations the relation requirements
	 * @param sourceIds the sources selected next to the labels, possibly none
	 * @param evita     the engine instance provided by the test extension
	 */
	@DisplayName("Should predict the result of every extended selection in the reference summary")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("predictedSelectionRows")
	void shouldPredictResultOfEveryExtendedSelection(
		@Nonnull String label,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] sourceIds,
		Evita evita
	) {
		assertPredictedResultOfEveryExtendedSelection(relations, sourceIds, UserFilterPlacement.ALONE, evita);
	}

	/**
	 * Returns the relation setups of {@link #predictedSelectionRows()}, each with the user filter in either place a
	 * `not` container puts it into - negated by the user, or next to a negated constraint. Each row is a label, the
	 * relation requirements, the sources selected next to the labels and the place of the user filter.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> predictedSelectionInNotContainerRows() {
		return predictedSelectionRows()
			.flatMap(
				row -> Stream.of(UserFilterPlacement.NEGATED, UserFilterPlacement.NEXT_TO_NEGATED_CONSTRAINT)
					.map(placement -> {
						final Object[] arguments = row.get();
						return Arguments.of(
							arguments[0] + ", " + placement.getDescription(), arguments[1], arguments[2], placement
						);
					})
			);
	}

	/**
	 * Checks what {@link #shouldPredictResultOfEveryExtendedSelection} checks, with the user filter in a `not`
	 * container: the option joins the user filter the way it joins a user filter nothing negates, and the `not`
	 * container then applies to the extended user filter, so the impact equals the result of the query selecting the
	 * option inside the same container.
	 *
	 * @param label     the row label, used in the test name only
	 * @param relations the relation requirements
	 * @param sourceIds the sources selected next to the labels, possibly none
	 * @param placement the place of the user filter in the filter
	 * @param evita     the engine instance provided by the test extension
	 */
	@DisplayName("Should predict the result of every extended selection of a user filter in a not container")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("predictedSelectionInNotContainerRows")
	void shouldPredictResultOfEveryExtendedSelectionOfUserFilterInNotContainer(
		@Nonnull String label,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] sourceIds,
		@Nonnull UserFilterPlacement placement,
		Evita evita
	) {
		assertPredictedResultOfEveryExtendedSelection(relations, sourceIds, placement, evita);
	}

	/**
	 * Asserts that the reference summary predicts exactly what the query returns for the relation setup - see
	 * {@link #shouldPredictResultOfEveryExtendedSelection} for what is compared.
	 *
	 * @param relations the relation requirements
	 * @param sourceIds the sources selected next to the labels, possibly none
	 * @param placement the place of the user filter in the filter
	 * @param evita     the engine instance provided by the test extension
	 */
	private static void assertPredictedResultOfEveryExtendedSelection(
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] sourceIds,
		@Nonnull UserFilterPlacement placement,
		@Nonnull Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<String> disagreements = new ArrayList<>(16);
				final int labelCount = LABEL_GROUPS.length;

				if (sourceIds.length == 0 && placement == UserFilterPlacement.ALONE) {
					final EvitaResponse<EntityReference> withoutSelection = session.query(
						shapedLabelSelectionQuery(
							new int[0], sourceIds, relations, placement,
							referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.COUNTS),
							referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.COUNTS)
						),
						EntityReference.class
					);
					for (int labelId = 1; labelId <= labelCount; labelId++) {
						final int count = facetCountOf(withoutSelection, REF_LABEL, LABEL_GROUPS[labelId - 1], labelId);
						final int resultSize = shapedLabelSelectionSize(
							session, new int[]{labelId}, sourceIds, relations, placement
						);
						if (count != resultSize) {
							disagreements.add(
								"no selection, label " + labelId + ": count " + count + ", result " + resultSize
							);
						}
					}
				}

				final int sourceCount = SOURCE_CHANNELS.length;
				final List<int[]> selections = new ArrayList<>(labelCount * labelCount + 1);
				if (sourceIds.length > 0) {
					selections.add(new int[0]);
				}
				for (int first = 1; first <= labelCount; first++) {
					selections.add(new int[]{first});
					for (int second = first + 1; second <= labelCount; second++) {
						selections.add(new int[]{first, second});
					}
				}
				for (final int[] selection : selections) {
					final EvitaResponse<EntityReference> withSummary = session.query(
						shapedLabelSelectionQuery(
							selection, sourceIds, relations, placement,
							referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.IMPACT),
							referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.IMPACT),
							referenceSummaryOfReference(REF_SOURCE, FacetStatisticsDepth.IMPACT)
						),
						EntityReference.class
					);
					for (int labelId = 1; labelId <= labelCount; labelId++) {
						if (ArrayUtils.indexOf(labelId, selection) >= 0) {
							continue;
						}
						final RequestImpact impact = facetStatisticsOf(
							withSummary, REF_LABEL, LABEL_GROUPS[labelId - 1], labelId
						).getImpact();
						final int resultSize = shapedLabelSelectionSize(
							session, ArrayUtils.insertIntIntoArrayOnIndex(labelId, selection, selection.length),
							sourceIds, relations, placement
						);
						if (impact == null || impact.matchCount() != resultSize) {
							disagreements.add(
								"selection " + Arrays.toString(selection) + " + label " + labelId + ": impact " +
									(impact == null ? "none" : impact.matchCount()) + ", result " + resultSize
							);
						}
					}
					for (int sourceId = 1; sourceId <= sourceCount; sourceId++) {
						if (ArrayUtils.indexOf(sourceId, sourceIds) >= 0) {
							continue;
						}
						final RequestImpact impact = facetStatisticsOf(withSummary, REF_SOURCE, null, sourceId).getImpact();
						final int resultSize = shapedLabelSelectionSize(
							session, selection, ArrayUtils.insertIntIntoArrayOnIndex(sourceId, sourceIds, sourceIds.length),
							relations, placement
						);
						if (impact == null || impact.matchCount() != resultSize) {
							disagreements.add(
								"selection " + Arrays.toString(selection) + ", sources " + Arrays.toString(sourceIds) +
									" + source " + sourceId + ": impact " +
									(impact == null ? "none" : impact.matchCount()) + ", result " + resultSize
							);
						}
					}
				}

				assertTrue(
					disagreements.isEmpty(),
					() -> "the reference summary must predict the result:\n" + String.join("\n", disagreements)
				);
				return null;
			}
		);
	}

	/**
	 * Builds the query selecting the passed labels and sources of the {@link #FACET_RELATION_SHAPES} data set in the
	 * user filter placed as requested, or selecting nothing when neither is passed.
	 *
	 * @param labelIds  the selected labels, possibly none
	 * @param sourceIds the selected sources, possibly none
	 * @param relations the relation requirements
	 * @param placement the place of the user filter in the filter
	 * @param summaries the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query shapedLabelSelectionQuery(
		@Nonnull int[] labelIds,
		@Nonnull int[] sourceIds,
		@Nonnull RequireConstraint[] relations,
		@Nonnull UserFilterPlacement placement,
		@Nonnull RequireConstraint... summaries
	) {
		return query(
			collection(ENTITY_SHAPED_PRODUCT),
			labelIds.length == 0 && sourceIds.length == 0 ?
				null :
				filterBy(
					placement.place(
						userFilter(
							Stream.of(
									labelIds.length == 0 ?
										null :
										facetHaving(REF_LABEL, entityPrimaryKeyInSet(Arrays.stream(labelIds).boxed().toArray(Integer[]::new))),
									sourceIds.length == 0 ?
										null :
										facetHaving(REF_SOURCE, entityPrimaryKeyInSet(Arrays.stream(sourceIds).boxed().toArray(Integer[]::new)))
								)
								.filter(Objects::nonNull)
								.toArray(FilterConstraint[]::new)
						)
					)
				),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, SHAPED_PRODUCT_LABELS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					},
					summaries,
					relations
				)
			)
		);
	}

	/**
	 * Returns the number of products of the {@link #FACET_RELATION_SHAPES} data set the query selecting the passed
	 * labels and sources returns.
	 *
	 * @param session   the session to query in
	 * @param labelIds  the selected labels
	 * @param sourceIds the selected sources, possibly none
	 * @param relations the relation requirements
	 * @param placement the place of the user filter in the filter
	 * @return the number of the returned products
	 */
	private static int shapedLabelSelectionSize(
		@Nonnull EvitaSessionContract session,
		@Nonnull int[] labelIds,
		@Nonnull int[] sourceIds,
		@Nonnull RequireConstraint[] relations,
		@Nonnull UserFilterPlacement placement
	) {
		return session.query(shapedLabelSelectionQuery(labelIds, sourceIds, relations, placement), EntityReference.class)
			.getTotalRecordCount();
	}

	/**
	 * The place of the user filter in the filter of the queries of {@link #shapedLabelSelectionQuery}.
	 */
	@RequiredArgsConstructor
	enum UserFilterPlacement {
		/**
		 * The user filter is the only filter constraint.
		 */
		ALONE("the user filter alone"),
		/**
		 * The user filter is negated by the user - `not(userFilter(...))`.
		 */
		NEGATED("the user filter negated"),
		/**
		 * The user filter is next to a negated constraint - `not(...), userFilter(...)` - and so the set the negated
		 * constraint is subtracted from. The excluded product references label 1, the grouped tag and both sources.
		 */
		NEXT_TO_NEGATED_CONSTRAINT("the user filter next to a negated constraint");

		/**
		 * The description of the placement used in the test names.
		 */
		@Getter private final String description;

		/**
		 * Places the user filter into the filter.
		 *
		 * @param userFilter the user filter
		 * @return the filter constraints holding the user filter
		 */
		@Nonnull
		FilterConstraint[] place(@Nonnull FilterConstraint userFilter) {
			return switch (this) {
				case ALONE -> new FilterConstraint[]{userFilter};
				case NEGATED -> new FilterConstraint[]{not(userFilter)};
				case NEXT_TO_NEGATED_CONSTRAINT -> new FilterConstraint[]{not(entityPrimaryKeyInSet(6)), userFilter};
			};
		}

	}

	/**
	 * Returns the rows of the witness of options of a reference the user filter selects no option of, over the
	 * {@link #FACET_RELATION_SHAPES} data set. Each row is a label, the relation requirements, the constraints of the
	 * user filter, the reference and the option whose impact is predicted, and the primary keys of the products the
	 * query returns when it selects the option next to the user filter, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> otherReferenceOptionRows() {
		final RequireConstraint[] disjunctionEverywhere = {
			facetCalculationRules(FacetRelationType.DISJUNCTION, FacetRelationType.DISJUNCTION)
		};
		final FilterConstraint[] label1 = {facetHaving(REF_LABEL, entityPrimaryKeyInSet(1))};
		final FilterConstraint[] source2 = {facetHaving(REF_SOURCE, entityPrimaryKeyInSet(2))};
		final int[] productsWithLabel1AndSource2 = intersectionOf(
			shapedProductsWithLabels(true, 1), shapedProductsWithSources(true, 2)
		);
		return Stream.of(
			// the relation between groups applies between the groups of one reference only, the options of different
			// references are combined the way the user filter combines its constraints - by logical AND
			Arguments.of(
				"default disjunction between groups, a source option next to a label", disjunctionEverywhere, label1,
				REF_SOURCE, 2, productsWithLabel1AndSource2
			),
			Arguments.of(
				"disjunction between the groups of sources, a source option next to a label",
				new RequireConstraint[]{facetGroupsDisjunction(REF_SOURCE, WITH_DIFFERENT_GROUPS)}, label1,
				REF_SOURCE, 2, productsWithLabel1AndSource2
			),
			Arguments.of(
				"default disjunction between groups, a label option next to a source", disjunctionEverywhere, source2,
				REF_LABEL, 1, productsWithLabel1AndSource2
			),
			Arguments.of(
				"disjunction between the groups of labels, a label option next to a source",
				new RequireConstraint[]{facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS)}, source2,
				REF_LABEL, 1, productsWithLabel1AndSource2
			),
			// an `or` the user wrote is a single constraint of the user filter, the option must not join it
			Arguments.of(
				"default disjunction between groups, a source option next to an or of a label and a product",
				disjunctionEverywhere,
				new FilterConstraint[]{or(facetHaving(REF_LABEL, entityPrimaryKeyInSet(1)), entityPrimaryKeyInSet(9))},
				REF_SOURCE, 2,
				intersectionOf(
					IntStream.concat(IntStream.of(shapedProductsWithLabels(true, 1)), IntStream.of(9)).sorted().toArray(),
					shapedProductsWithSources(true, 2)
				)
			),
			// the selection of a reference mixing conjunctive and disjunctive groups is a union of the groups, the
			// option of another reference must not join its conjunctive part
			Arguments.of(
				"disjunction of group B, default conjunction of the labels without a group, a source option",
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
				},
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(3, 4))},
				REF_SOURCE, 2,
				intersectionOf(shapedProductsWithLabels(true, 3, 4), shapedProductsWithSources(true, 2))
			)
		);
	}

	/**
	 * Checks that the reference summary predicts the impact of an option of a reference the user filter selects no
	 * option of as the user filter AND the option, whatever relation between groups the requirements set - the
	 * relation applies between the groups of one reference, and the result combines the constraints of the user
	 * filter, the selections of different references included, by logical AND.
	 *
	 * @param label         the row label, used in the test name only
	 * @param relations     the relation requirements
	 * @param selection     the constraints of the user filter
	 * @param referenceName the reference of the option whose impact is predicted
	 * @param optionId      the option whose impact is predicted
	 * @param expected      the primary keys of the products the query selecting the option returns, ascending
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should combine an option of another reference with the user filter by conjunction")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("otherReferenceOptionRows")
	void shouldCombineOptionOfAnotherReferenceByConjunction(
		@Nonnull String label,
		@Nonnull RequireConstraint[] relations,
		@Nonnull FilterConstraint[] selection,
		@Nonnull String referenceName,
		int optionId,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(expected.length > 0, "the extended selection must keep some products");
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					shapedFilterQuery(
						ArrayUtils.mergeArrays(
							selection,
							new FilterConstraint[]{facetHaving(referenceName, entityPrimaryKeyInSet(optionId))}
						),
						relations
					),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);

				final EvitaResponse<EntityReference> withSummary = session.query(
					shapedFilterQuery(
						selection, relations, referenceSummaryOfReference(referenceName, FacetStatisticsDepth.IMPACT)
					),
					EntityReference.class
				);
				final RequestImpact impact = facetStatisticsOf(
					withSummary, referenceName, REF_LABEL.equals(referenceName) ? LABEL_GROUPS[optionId - 1] : null, optionId
				).getImpact();
				assertNotNull(impact, "the reference summary must predict the impact of the option");
				assertEquals(
					expected.length,
					impact.matchCount(),
					"the reference summary must predict the products adding the option leaves in the result"
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the witness of options of a group exclusive with the other groups of its reference, over the
	 * {@link #FACET_RELATION_SHAPES} data set. Selecting such an option deselects the options of the other groups of
	 * its reference, and only of its reference. Each row is a label, the relation requirements, the constraints of the
	 * user filter, the reference, group and primary key of the option whose impact is predicted, the constraints of the
	 * user filter of the query selecting the option, and the primary keys of the products that query returns, computed
	 * from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> exclusiveOptionRows() {
		final RequireConstraint[] exclusivityOfTags = {facetGroupsExclusivity(REF_TAG, WITH_DIFFERENT_GROUPS)};
		final RequireConstraint[] exclusivityOfLabels = {facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_GROUPS)};
		final FilterConstraint label1 = facetHaving(REF_LABEL, entityPrimaryKeyInSet(1));
		final FilterConstraint source1 = facetHaving(REF_SOURCE, entityPrimaryKeyInSet(1));
		final int[] productsWithSource1 = shapedProductsWithSources(true, 1);
		return Stream.of(
			// the selections of other references and the other constraints of the user filter stay
			Arguments.of(
				"exclusivity between the groups of tags, a grouped tag option next to a label", exclusivityOfTags,
				new FilterConstraint[]{label1}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{label1, facetHaving(REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG))},
				intersectionOf(shapedProductsWithLabels(true, 1), shapedProductsWithTag(GROUPED_TAG))
			),
			Arguments.of(
				"exclusivity between the groups of tags, a tag option without a group next to a label", exclusivityOfTags,
				new FilterConstraint[]{label1}, REF_TAG, null, UNGROUPED_TAG,
				new FilterConstraint[]{label1, facetHaving(REF_TAG, entityPrimaryKeyInSet(UNGROUPED_TAG))},
				intersectionOf(shapedProductsWithLabels(true, 1), shapedProductsWithTag(UNGROUPED_TAG))
			),
			Arguments.of(
				"exclusivity between the groups of tags, a tag option next to a constraint selecting no option",
				exclusivityOfTags, new FilterConstraint[]{entityPrimaryKeyInSet(1, 2, 3, 4)}, REF_TAG, TAG_GROUP,
				GROUPED_TAG,
				new FilterConstraint[]{
					entityPrimaryKeyInSet(1, 2, 3, 4), facetHaving(REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG))
				},
				intersectionOf(new int[]{1, 2, 3, 4}, shapedProductsWithTag(GROUPED_TAG))
			),
			// the options of the other groups of the option's own reference are deselected
			Arguments.of(
				"exclusivity between the groups of labels, a label option replacing a label of another group",
				exclusivityOfLabels, new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(3))},
				REF_LABEL, LABEL_GROUP_A, 1, new FilterConstraint[]{label1}, shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"exclusivity between the groups of labels, a label option replacing a label of another group next to " +
					"a source",
				exclusivityOfLabels, new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(3)), source1},
				REF_LABEL, LABEL_GROUP_A, 1, new FilterConstraint[]{label1, source1},
				intersectionOf(shapedProductsWithLabels(true, 1), productsWithSource1)
			),
			Arguments.of(
				"exclusivity of group A, negation of group B, a label option replacing a negated label next to a source",
				new RequireConstraint[]{
					facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))),
					facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))
				},
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(3)), source1},
				REF_LABEL, LABEL_GROUP_A, 1, new FilterConstraint[]{label1, source1},
				intersectionOf(shapedProductsWithLabels(true, 1), productsWithSource1)
			),
			// an option of a selected group joins the options of its group, the other groups are deselected
			Arguments.of(
				"exclusivity between the groups of labels, a label option of a selected group",
				exclusivityOfLabels, new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3))},
				REF_LABEL, LABEL_GROUP_A, 2, new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 2))},
				shapedProductsWithLabels(true, 1, 2)
			),
			Arguments.of(
				"exclusivity between the groups of labels, a label option of a selected group next to a source",
				exclusivityOfLabels,
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3)), source1},
				REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 2)), source1},
				intersectionOf(shapedProductsWithLabels(true, 1, 2), productsWithSource1)
			),
			Arguments.of(
				"exclusivity within and between the groups of labels, a label option of a selected group next to a source",
				new RequireConstraint[]{
					facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP),
					facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_GROUPS)
				},
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3)), source1},
				REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(2)), source1},
				intersectionOf(shapedProductsWithLabels(true, 2), productsWithSource1)
			),
			// exclusivity within a group deselects the other options of the group only
			Arguments.of(
				"exclusivity within the groups of labels, a label option of a selected group next to a source",
				new RequireConstraint[]{facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP)},
				new FilterConstraint[]{label1, source1},
				REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{facetHaving(REF_LABEL, entityPrimaryKeyInSet(2)), source1},
				intersectionOf(shapedProductsWithLabels(true, 2), productsWithSource1)
			)
		);
	}

	/**
	 * Checks that the reference summary predicts the impact of an option of a group exclusive with other groups as the
	 * result of the query selecting it: the options of the other groups of the option's reference are deselected, while
	 * the selections of other references and the other constraints of the user filter stay.
	 *
	 * @param label           the row label, used in the test name only
	 * @param relations       the relation requirements
	 * @param selection       the constraints of the user filter
	 * @param referenceName   the reference of the option whose impact is predicted
	 * @param groupId         the group of the option, NULL for an option without a group
	 * @param optionId        the option whose impact is predicted
	 * @param optionSelection the constraints of the user filter of the query selecting the option
	 * @param expected        the primary keys of the products the query selecting the option returns, ascending
	 * @param evita           the engine instance provided by the test extension
	 */
	@DisplayName("Should predict an exclusive option as replacing the selection of its reference only")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("exclusiveOptionRows")
	void shouldPredictExclusiveOptionAsReplacingSelectionOfItsReferenceOnly(
		@Nonnull String label,
		@Nonnull RequireConstraint[] relations,
		@Nonnull FilterConstraint[] selection,
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int optionId,
		@Nonnull FilterConstraint[] optionSelection,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(expected.length > 0, "the selection of the option must keep some products");
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					shapedFilterQuery(optionSelection, relations), EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);

				final EvitaResponse<EntityReference> withSummary = session.query(
					shapedFilterQuery(
						selection, relations, referenceSummaryOfReference(referenceName, FacetStatisticsDepth.IMPACT)
					),
					EntityReference.class
				);
				final RequestImpact impact = facetStatisticsOf(withSummary, referenceName, groupId, optionId).getImpact();
				assertNotNull(impact, "the reference summary must predict the impact of the option");
				assertEquals(
					expected.length,
					impact.matchCount(),
					"the reference summary must predict the products selecting the option leaves in the result"
				);
				assertEquals(
					expected.length - withSummary.getTotalRecordCount(),
					impact.difference(),
					"the reference summary must predict the difference selecting the option makes"
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the witness of a user filter placed in a `not` container, over the
	 * {@link #FACET_RELATION_SHAPES} data set - either negated by the user, `not(userFilter(...))`, or next to a
	 * negated constraint, `not(...), userFilter(...)`, which makes the user filter the set the negated constraint is
	 * subtracted from. Selecting an option adds it to the user filter exactly as when nothing negates anything, and
	 * the `not` container then applies to the extended user filter. Each row is a label, the relation requirements,
	 * the filter constraints of the query, the reference, group and primary key of the option whose impact is
	 * predicted, the filter constraints of the query selecting the option, the filter constraints of the query
	 * selecting the option alone in its group, the filter constraints without the user filter, and the primary keys of
	 * the products the query selecting the option returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> negatedUserFilterRows() {
		final RequireConstraint[] defaults = new RequireConstraint[0];
		final RequireConstraint[] disjunctionOfLabelGroups = {facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS)};
		final RequireConstraint[] negationOfGroupB = {
			facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
		};
		final RequireConstraint[] exclusivityOfLabelGroups = {facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_GROUPS)};
		final FilterConstraint label1 = facetHaving(REF_LABEL, entityPrimaryKeyInSet(1));
		final FilterConstraint label3 = facetHaving(REF_LABEL, entityPrimaryKeyInSet(3));
		final FilterConstraint labels1And2 = facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 2));
		final FilterConstraint labels1And3 = facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3));
		final FilterConstraint label2 = facetHaving(REF_LABEL, entityPrimaryKeyInSet(2));
		final FilterConstraint tag = facetHaving(REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG));
		final FilterConstraint source2 = facetHaving(REF_SOURCE, entityPrimaryKeyInSet(2));
		final FilterConstraint notProduct1 = not(entityPrimaryKeyInSet(1));
		final FilterConstraint[] noConstraint = new FilterConstraint[0];
		final int[] products1 = shapedProductsWithLabels(true, 1);
		final int[] products3 = shapedProductsWithLabels(true, 3);
		final int[] productsWithTag = shapedProductsWithTag(GROUPED_TAG);
		final int[] withoutProduct1 = differenceOf(shapedProducts(), new int[]{1});
		return Stream.of(
			// the user filter negated by the user: the option joins the user filter, the negation applies to the result
			Arguments.of(
				"system defaults, a tag option in a negated user filter selecting a label", defaults,
				new FilterConstraint[]{not(userFilter(label1))}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{not(userFilter(label1, tag))}, null, noConstraint,
				differenceOf(shapedProducts(), intersectionOf(products1, productsWithTag))
			),
			Arguments.of(
				"disjunction between the groups of tags, a tag option in a negated user filter selecting a label",
				new RequireConstraint[]{facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS)},
				new FilterConstraint[]{not(userFilter(label1))}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{not(userFilter(label1, tag))}, null, noConstraint,
				differenceOf(shapedProducts(), intersectionOf(products1, productsWithTag))
			),
			Arguments.of(
				"negation of the tags, a tag option in a negated user filter selecting a label",
				new RequireConstraint[]{facetGroupsNegation(REF_TAG)},
				new FilterConstraint[]{not(userFilter(label1))}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{not(userFilter(label1, tag))}, null, noConstraint,
				differenceOf(shapedProducts(), differenceOf(products1, productsWithTag))
			),
			Arguments.of(
				"exclusivity between the groups of tags, a tag option in a negated user filter selecting a label",
				new RequireConstraint[]{facetGroupsExclusivity(REF_TAG, WITH_DIFFERENT_GROUPS)},
				new FilterConstraint[]{not(userFilter(label1))}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{not(userFilter(label1, tag))}, null, noConstraint,
				differenceOf(shapedProducts(), intersectionOf(products1, productsWithTag))
			),
			Arguments.of(
				"system defaults, a source option in a negated user filter selecting a label", defaults,
				new FilterConstraint[]{not(userFilter(label1))}, REF_SOURCE, null, 2,
				new FilterConstraint[]{not(userFilter(label1, source2))}, null, noConstraint,
				differenceOf(shapedProducts(), intersectionOf(products1, shapedProductsWithSources(true, 2)))
			),
			Arguments.of(
				"system defaults, a label option of another group in a negated user filter", defaults,
				new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{not(userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3))))}, null,
				noConstraint, differenceOf(shapedProducts(), intersectionOf(products1, products3))
			),
			Arguments.of(
				"disjunction between the groups of labels, a label option of another group in a negated user filter",
				disjunctionOfLabelGroups, new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{not(userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3))))}, null,
				noConstraint, differenceOf(shapedProducts(), shapedProductsWithLabels(true, 1, 3))
			),
			Arguments.of(
				"negation of group B, a label option of group B in a negated user filter",
				negationOfGroupB, new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{not(userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3))))}, null,
				noConstraint, differenceOf(shapedProducts(), differenceOf(products1, products3))
			),
			Arguments.of(
				"exclusivity between the groups of labels, a label option of another group in a negated user filter",
				exclusivityOfLabelGroups, new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{not(userFilter(label3))}, null, noConstraint,
				differenceOf(shapedProducts(), products3)
			),
			Arguments.of(
				"system defaults, a label option of the selected group in a negated user filter", defaults,
				new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{not(userFilter(labels1And2))}, new FilterConstraint[]{not(userFilter(label2))},
				noConstraint, differenceOf(shapedProducts(), shapedProductsWithLabels(true, 1, 2))
			),
			Arguments.of(
				"conjunction within group A, a label option of the selected group in a negated user filter",
				new RequireConstraint[]{
					facetGroupsConjunction(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))
				},
				new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{not(userFilter(labels1And2))}, new FilterConstraint[]{not(userFilter(label2))},
				noConstraint, differenceOf(shapedProducts(), shapedProductsWithAllLabels(1, 2))
			),
			Arguments.of(
				"exclusivity within the groups of labels, a label option of the selected group in a negated user filter",
				new RequireConstraint[]{facetGroupsExclusivity(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP)},
				new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{not(userFilter(label2))}, null, noConstraint,
				differenceOf(shapedProducts(), shapedProductsWithLabels(true, 2))
			),
			Arguments.of(
				"exclusivity between the groups of labels, a label option of a selected group in a negated user filter",
				exclusivityOfLabelGroups, new FilterConstraint[]{not(userFilter(labels1And3))},
				REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{not(userFilter(labels1And2))}, new FilterConstraint[]{not(userFilter(label2))},
				noConstraint, differenceOf(shapedProducts(), shapedProductsWithLabels(true, 1, 2))
			),
			// a user filter selecting only negated groups subtracts them inside the negated user filter
			Arguments.of(
				"negation of group A, a label option of the selected group in a negated user filter",
				new RequireConstraint[]{facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A)))},
				new FilterConstraint[]{not(userFilter(label1))}, REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{not(userFilter(labels1And2))}, new FilterConstraint[]{not(userFilter(label2))},
				noConstraint, shapedProductsWithLabels(true, 1, 2)
			),
			// the user filter next to a negated constraint is the set the constraint is subtracted from
			Arguments.of(
				"system defaults, a tag option in a user filter next to a negated constraint", defaults,
				new FilterConstraint[]{notProduct1, userFilter(label1)}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{notProduct1, userFilter(label1, tag)}, null,
				new FilterConstraint[]{notProduct1},
				intersectionOf(withoutProduct1, intersectionOf(products1, productsWithTag))
			),
			Arguments.of(
				"negation of the tags, a tag option in a user filter next to a negated constraint",
				new RequireConstraint[]{facetGroupsNegation(REF_TAG)},
				new FilterConstraint[]{notProduct1, userFilter(label1)}, REF_TAG, TAG_GROUP, GROUPED_TAG,
				new FilterConstraint[]{notProduct1, userFilter(label1, tag)}, null,
				new FilterConstraint[]{notProduct1},
				intersectionOf(withoutProduct1, differenceOf(products1, productsWithTag))
			),
			Arguments.of(
				"disjunction between the groups of labels, a label option of another group in a user filter next to a " +
					"negated constraint",
				disjunctionOfLabelGroups, new FilterConstraint[]{notProduct1, userFilter(label1)},
				REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{notProduct1, userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3)))}, null,
				new FilterConstraint[]{notProduct1},
				intersectionOf(withoutProduct1, shapedProductsWithLabels(true, 1, 3))
			),
			Arguments.of(
				"negation of group B, a label option of group B in a user filter next to a negated constraint",
				negationOfGroupB, new FilterConstraint[]{notProduct1, userFilter(label1)}, REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{notProduct1, userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(1, 3)))}, null,
				new FilterConstraint[]{notProduct1},
				intersectionOf(withoutProduct1, differenceOf(products1, products3))
			),
			Arguments.of(
				"exclusivity between the groups of labels, a label option of another group in a user filter next to a " +
					"negated constraint",
				exclusivityOfLabelGroups, new FilterConstraint[]{notProduct1, userFilter(label1)},
				REF_LABEL, LABEL_GROUP_B, 3,
				new FilterConstraint[]{notProduct1, userFilter(label3)}, null,
				new FilterConstraint[]{notProduct1}, intersectionOf(withoutProduct1, products3)
			),
			Arguments.of(
				"system defaults, a label option of the selected group in a user filter next to a negated constraint",
				defaults, new FilterConstraint[]{notProduct1, userFilter(label1)}, REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{notProduct1, userFilter(labels1And2)},
				new FilterConstraint[]{notProduct1, userFilter(label2)},
				new FilterConstraint[]{notProduct1},
				intersectionOf(withoutProduct1, shapedProductsWithLabels(true, 1, 2))
			),
			Arguments.of(
				"exclusivity between the groups of labels, a label option of a selected group in a user filter next to " +
					"a negated constraint",
				exclusivityOfLabelGroups, new FilterConstraint[]{notProduct1, userFilter(labels1And3)},
				REF_LABEL, LABEL_GROUP_A, 2,
				new FilterConstraint[]{notProduct1, userFilter(labels1And2)},
				new FilterConstraint[]{notProduct1, userFilter(label2)},
				new FilterConstraint[]{notProduct1},
				intersectionOf(withoutProduct1, shapedProductsWithLabels(true, 1, 2))
			)
		);
	}

	/**
	 * Checks that the reference summary predicts the impact of an option whose user filter sits in a `not` container
	 * as the result of the query selecting it - the option joins the user filter the way it joins a user filter nothing
	 * negates, and the `not` container applies to the extended user filter. The match count, the difference and
	 * whether the selection makes sense are predicted - it does when the result is not empty and either changes the
	 * current result or keeps some products with the option selected alone in its group - and the count of the option
	 * does not depend on the user filter at all, so it equals the count of the query without it.
	 *
	 * @param label           the row label, used in the test name only
	 * @param relations       the relation requirements
	 * @param filter          the filter constraints of the query
	 * @param referenceName   the reference of the option whose impact is predicted
	 * @param groupId         the group of the option, NULL for an option without a group
	 * @param optionId        the option whose impact is predicted
	 * @param optionFilter    the filter constraints of the query selecting the option
	 * @param aloneFilter     the filter constraints of the query selecting the option alone in its group, NULL when
	 *                        they are the `optionFilter`
	 * @param mandatoryFilter the filter constraints of the query without the user filter, possibly none
	 * @param expected        the primary keys of the products the query selecting the option returns, ascending
	 * @param evita           the engine instance provided by the test extension
	 */
	@DisplayName("Should predict an option of a user filter in a not container as the result selecting it")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("negatedUserFilterRows")
	void shouldPredictOptionOfUserFilterInNotContainer(
		@Nonnull String label,
		@Nonnull RequireConstraint[] relations,
		@Nonnull FilterConstraint[] filter,
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int optionId,
		@Nonnull FilterConstraint[] optionFilter,
		@Nullable FilterConstraint[] aloneFilter,
		@Nonnull FilterConstraint[] mandatoryFilter,
		@Nonnull int[] expected,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					shapedTopLevelFilterQuery(optionFilter, relations), EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);
				final int aloneSize = aloneFilter == null ?
					expected.length :
					session.query(shapedTopLevelFilterQuery(aloneFilter, relations), EntityReference.class)
						.getTotalRecordCount();
				final EvitaResponse<EntityReference> withoutUserFilter = session.query(
					shapedTopLevelFilterQuery(
						mandatoryFilter, relations, referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS)
					),
					EntityReference.class
				);

				final EvitaResponse<EntityReference> withSummary = session.query(
					shapedTopLevelFilterQuery(
						filter, relations, referenceSummaryOfReference(referenceName, FacetStatisticsDepth.IMPACT)
					),
					EntityReference.class
				);
				final int currentSize = withSummary.getTotalRecordCount();
				final FacetStatistics statistics = facetStatisticsOf(withSummary, referenceName, groupId, optionId);
				final RequestImpact impact = statistics.getImpact();
				assertNotNull(impact, "the reference summary must predict the impact of the option");
				assertEquals(
					expected.length,
					impact.matchCount(),
					"the reference summary must predict the products selecting the option leaves in the result"
				);
				assertEquals(
					expected.length - currentSize,
					impact.difference(),
					"the reference summary must predict the difference selecting the option makes"
				);
				assertEquals(
					expected.length > 0 && (expected.length != currentSize || aloneSize > 0),
					impact.hasSense(),
					"the reference summary must predict whether selecting the option makes sense"
				);
				assertEquals(
					facetCountOf(withoutUserFilter, referenceName, groupId, optionId),
					statistics.getCount(),
					"the count of the option must not depend on the user filter"
				);
				assertEquals(
					groupStatisticsOf(withoutUserFilter, referenceName, groupId).getCount(),
					groupStatisticsOf(withSummary, referenceName, groupId).getCount(),
					"the count of the group of the option must not depend on the user filter"
				);
				return null;
			}
		);
	}

	/**
	 * Returns the statistics of the passed group the reference summary of the passed response computes.
	 *
	 * @param response      the response carrying the reference summary
	 * @param referenceName the reference the group belongs to
	 * @param groupId       the group, NULL for the options without a group
	 * @return the statistics of the group
	 */
	@Nonnull
	private static ReferenceGroupStatistics groupStatisticsOf(
		@Nonnull EvitaResponse<EntityReference> response,
		@Nonnull String referenceName,
		@Nullable Integer groupId
	) {
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, "the reference summary must be computed");
		final ReferenceGroupStatistics groupStatistics = groupId == null ?
			summary.getReferenceGroupStatistics(referenceName) :
			summary.getReferenceGroupStatistics(referenceName, groupId);
		assertNotNull(groupStatistics, "the group " + groupId + " of `" + referenceName + "` must have statistics");
		return groupStatistics;
	}

	/**
	 * Builds the query of the {@link #FACET_RELATION_SHAPES} data set filtered by the passed constraints.
	 *
	 * @param filterConstraints the filter constraints of the query, possibly none
	 * @param relations         the relation requirements
	 * @param summaries         the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query shapedTopLevelFilterQuery(
		@Nonnull FilterConstraint[] filterConstraints,
		@Nonnull RequireConstraint[] relations,
		@Nonnull RequireConstraint... summaries
	) {
		return query(
			collection(ENTITY_SHAPED_PRODUCT),
			filterConstraints.length == 0 ? null : filterBy(filterConstraints),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, SHAPED_PRODUCT_LABELS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					},
					summaries,
					relations
				)
			)
		);
	}

	/**
	 * Returns the primary keys of all products of the {@link #FACET_RELATION_SHAPES} data set.
	 *
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProducts() {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_LABELS.length).toArray();
	}

	/**
	 * Returns the primary keys present in the first passed ascending array and not in the second.
	 *
	 * @param first  the ascending primary keys to keep
	 * @param second the ascending primary keys to remove
	 * @return the ascending primary keys of the first array missing in the second
	 */
	@Nonnull
	private static int[] differenceOf(@Nonnull int[] first, @Nonnull int[] second) {
		return IntStream.of(first).filter(pk -> ArrayUtils.indexOf(pk, second) < 0).toArray();
	}

	/**
	 * Returns the selections of tags of the {@link #FACET_SCOPE_SHAPES} data set made separately for each scope. Each
	 * row is a label, the tags selected in the live scope, the tags selected in the archived scope, and whether each
	 * scope has a user filter of its own - `inScope(scope, userFilter(facetHaving(...)))` - rather than sharing one
	 * user filter holding `inScope(scope, facetHaving(...))` for each scope.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> scopedSelectionRows() {
		final int[] none = new int[0];
		return Stream.of(
			Arguments.of(
				"group 100 selected in the live scope, group 200 in the archived scope", new int[]{10}, new int[]{20}, false
			),
			Arguments.of(
				"group 200 selected in the live scope, group 100 in the archived scope", new int[]{20}, new int[]{10}, false
			),
			Arguments.of(
				"group 100 selected by the user filter of the live scope, group 200 by the one of the archived scope",
				new int[]{10}, new int[]{20}, true
			),
			Arguments.of(
				"group 200 selected by the user filter of the live scope, group 100 by the one of the archived scope",
				new int[]{20}, new int[]{10}, true
			),
			Arguments.of("group 100 selected in both scopes", new int[]{10}, new int[]{10}, false),
			Arguments.of("group 100 selected in the live scope only", new int[]{10}, none, false),
			Arguments.of("group 100 selected in the archived scope only", none, new int[]{10}, false),
			Arguments.of(
				"groups 100 and 200 selected in the live scope, group 300 in the archived scope",
				new int[]{10, 20}, new int[]{30}, false
			)
		);
	}

	/**
	 * Checks that the reference summary predicts the impact of every tag not selected yet as the result of the query
	 * selecting it in the selection of every scope - the tag joins the selection each scope makes on its own, so the
	 * group of the tag being selected in one scope must not change how the tag joins the selection of another scope.
	 * The oracle is the engine's own result, for the match count, the difference and whether the selection makes
	 * sense: it does when the result is not empty and either changes the current result or keeps some products with
	 * the tag selected alone in its group.
	 *
	 * @param label                 the row label, used in the test name only
	 * @param liveTags              the tags selected in the live scope
	 * @param archivedTags          the tags selected in the archived scope
	 * @param userFilterOfEachScope whether each scope has a user filter of its own
	 * @param evita                 the engine instance provided by the test extension
	 */
	@DisplayName("Should predict the option joining the selection of every scope")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("scopedSelectionRows")
	void shouldPredictOptionJoiningSelectionOfEveryScope(
		@Nonnull String label,
		@Nonnull int[] liveTags,
		@Nonnull int[] archivedTags,
		boolean userFilterOfEachScope,
		Evita evita
	) {
		assertOptionsPredictedInEveryScope(
			liveTags, archivedTags, userFilterOfEachScope, new RequireConstraint[0], evita
		);
	}

	/**
	 * Returns the rows of {@link #scopedSelectionRows()} under group relations that differ between the group 100 the
	 * archived-only tag 13 belongs to and the facets without a group: the group 100 joins the other groups by
	 * disjunction, or is subtracted from them, while the facets without a group keep the default conjunction. In the
	 * live scope, which references no tag 13, selecting it adds a facet without a group, whose term has no entities.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> scopedSelectionRowsUnderGroupRelations() {
		final List<Arguments> rows = scopedSelectionRows().toList();
		return Stream.of(
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(100)))
				},
				new RequireConstraint[]{
					facetGroupsNegation(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(100)))
				}
			)
			.flatMap(
				relations -> rows.stream().map(
					row -> {
						final Object[] arguments = row.get();
						return Arguments.of(
							relations[0].getClass().getSimpleName() + " of group 100, " + arguments[0],
							arguments[1], arguments[2], arguments[3], relations
						);
					}
				)
			);
	}

	/**
	 * Checks the prediction of {@link #shouldPredictOptionJoiningSelectionOfEveryScope} under group relations that
	 * tell a facet of the group 100 from a facet without a group. A tag no entity of a scope references is a facet
	 * without a group in that scope - selecting it there follows the relations of the facets without a group, not the
	 * relations of the group it has in the other scope.
	 *
	 * @param label                 the row label, used in the test name only
	 * @param liveTags              the tags selected in the live scope
	 * @param archivedTags          the tags selected in the archived scope
	 * @param userFilterOfEachScope whether each scope has a user filter of its own
	 * @param relations             the relation requirements
	 * @param evita                 the engine instance provided by the test extension
	 */
	@DisplayName("Should predict the option joining the selection of every scope under group relations")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("scopedSelectionRowsUnderGroupRelations")
	void shouldPredictOptionJoiningSelectionOfEveryScopeUnderGroupRelations(
		@Nonnull String label,
		@Nonnull int[] liveTags,
		@Nonnull int[] archivedTags,
		boolean userFilterOfEachScope,
		@Nonnull RequireConstraint[] relations,
		Evita evita
	) {
		assertOptionsPredictedInEveryScope(liveTags, archivedTags, userFilterOfEachScope, relations, evita);
	}

	/**
	 * Asserts that the reference summary predicts the impact of every tag not selected yet as the result of the query
	 * selecting it in the selection of every scope - see {@link #shouldPredictOptionJoiningSelectionOfEveryScope}.
	 *
	 * @param liveTags              the tags selected in the live scope
	 * @param archivedTags          the tags selected in the archived scope
	 * @param userFilterOfEachScope whether each scope has a user filter of its own
	 * @param relations             the relation requirements
	 * @param evita                 the engine instance provided by the test extension
	 */
	private static void assertOptionsPredictedInEveryScope(
		@Nonnull int[] liveTags,
		@Nonnull int[] archivedTags,
		boolean userFilterOfEachScope,
		@Nonnull RequireConstraint[] relations,
		@Nonnull Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<String> disagreements = new ArrayList<>(16);
				final EvitaResponse<EntityReference> withSummary = session.query(
					scopedTagSelectionQuery(
						liveTags, archivedTags, userFilterOfEachScope, relations,
						referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.IMPACT)
					),
					EntityReference.class
				);
				final int currentSize = withSummary.getTotalRecordCount();
				for (final int tagId : SCOPED_TAGS) {
					if (ArrayUtils.indexOf(tagId, liveTags) >= 0 || ArrayUtils.indexOf(tagId, archivedTags) >= 0) {
						continue;
					}
					final int groupId = scopedTagGroupOf(tagId);
					final RequestImpact impact = facetStatisticsOf(withSummary, REF_TAG, groupId, tagId).getImpact();
					final int resultSize = session.query(
						scopedTagSelectionQuery(
							ArrayUtils.insertIntIntoArrayOnIndex(tagId, liveTags, liveTags.length),
							ArrayUtils.insertIntIntoArrayOnIndex(tagId, archivedTags, archivedTags.length),
							userFilterOfEachScope, relations
						),
						EntityReference.class
					).getTotalRecordCount();
					final int[] liveTagsOfOtherGroups = IntStream.of(liveTags)
						.filter(it -> scopedTagGroupOf(it) != groupId)
						.toArray();
					final int[] archivedTagsOfOtherGroups = IntStream.of(archivedTags)
						.filter(it -> scopedTagGroupOf(it) != groupId)
						.toArray();
					final int aloneSize = session.query(
						scopedTagSelectionQuery(
							ArrayUtils.insertIntIntoArrayOnIndex(tagId, liveTagsOfOtherGroups, liveTagsOfOtherGroups.length),
							ArrayUtils.insertIntIntoArrayOnIndex(
								tagId, archivedTagsOfOtherGroups, archivedTagsOfOtherGroups.length
							),
							userFilterOfEachScope, relations
						),
						EntityReference.class
					).getTotalRecordCount();
					final boolean hasSense = resultSize > 0 && (resultSize != currentSize || aloneSize > 0);
					if (impact == null || impact.matchCount() != resultSize ||
						impact.difference() != resultSize - currentSize || impact.hasSense() != hasSense) {
						disagreements.add(
							"tag " + tagId + ": impact " +
								(impact == null ?
									"none" :
									impact.matchCount() + " (difference " + impact.difference() + ", has sense " +
										impact.hasSense() + ")") +
								", result " + resultSize + " (difference " + (resultSize - currentSize) + ", has sense " +
								hasSense + ")"
						);
					}
				}
				assertTrue(
					disagreements.isEmpty(),
					() -> "the reference summary must predict the result:\n" + String.join("\n", disagreements)
				);
				return null;
			}
		);
	}

	/**
	 * Builds the query of the {@link #FACET_SCOPE_SHAPES} data set over both scopes selecting the passed tags in each
	 * of them.
	 *
	 * @param liveTags              the tags selected in the live scope, possibly none
	 * @param archivedTags          the tags selected in the archived scope, possibly none
	 * @param userFilterOfEachScope whether each scope has a user filter of its own
	 * @param relations             the relation requirements
	 * @param summaries             the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query scopedTagSelectionQuery(
		@Nonnull int[] liveTags,
		@Nonnull int[] archivedTags,
		boolean userFilterOfEachScope,
		@Nonnull RequireConstraint[] relations,
		@Nonnull RequireConstraint... summaries
	) {
		final FilterConstraint[] scopeSelections = Stream.of(Scope.LIVE, Scope.ARCHIVED)
			.map(scope -> {
				final int[] tags = scope == Scope.LIVE ? liveTags : archivedTags;
				if (tags.length == 0) {
					return null;
				}
				final FilterConstraint selection = facetHaving(
					REF_TAG, entityPrimaryKeyInSet(Arrays.stream(tags).boxed().toArray(Integer[]::new))
				);
				return userFilterOfEachScope ? inScope(scope, userFilter(selection)) : inScope(scope, selection);
			})
			.filter(Objects::nonNull)
			.toArray(FilterConstraint[]::new);
		return query(
			collection(ENTITY_SCOPED_PRODUCT),
			filterBy(
				ArrayUtils.mergeArrays(
					new FilterConstraint[]{scope(Scope.LIVE, Scope.ARCHIVED)},
					userFilterOfEachScope ? scopeSelections : new FilterConstraint[]{userFilter(scopeSelections)}
				)
			),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, SCOPED_PRODUCT_TAGS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					},
					summaries,
					relations
				)
			)
		);
	}

	/**
	 * Returns the rows of selections over the {@link #FACET_SCOPE_SHAPES} data set naming a tag no product of the
	 * searched scope references - tag 13 is referenced by an archived product only, tag 99 by no product at all. Each
	 * row is a label, the filter constraints, the relation requirements, and the primary keys of the products the query
	 * returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> unreferencedSelectedFacetRows() {
		final RequireConstraint[] defaults = new RequireConstraint[0];
		final RequireConstraint[] disjunctionOfGroup100 = {
			facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(100)))
		};
		final FilterConstraint live = scope(Scope.LIVE);
		final FilterConstraint both = scope(Scope.LIVE, Scope.ARCHIVED);
		return Stream.of(
			Arguments.of(
				"live scope, tag 13 next to tag 20",
				new FilterConstraint[]{live, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13)))},
				defaults, new int[0]
			),
			Arguments.of(
				"live scope, tag 99 next to tag 20",
				new FilterConstraint[]{live, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 99)))},
				defaults, new int[0]
			),
			Arguments.of(
				"live scope, tag 13 next to tag 20 inside a live scope container",
				new FilterConstraint[]{
					live, userFilter(inScope(Scope.LIVE, facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13))))
				},
				defaults, new int[0]
			),
			Arguments.of(
				"live scope, tag 13 alone",
				new FilterConstraint[]{live, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(13)))},
				defaults, new int[0]
			),
			Arguments.of(
				"live scope, tag 99 alone, every group negated",
				new FilterConstraint[]{live, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(99)))},
				new RequireConstraint[]{facetGroupsNegation(REF_TAG)}, new int[]{1, 2, 5, 6, 9, 11}
			),
			Arguments.of(
				"live scope, tag 99 next to tag 20, disjunction between every group",
				new FilterConstraint[]{live, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 99)))},
				new RequireConstraint[]{facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS)}, new int[]{5, 6}
			),
			Arguments.of(
				"live scope, tag 13 next to tag 20, disjunction of group 100 only",
				new FilterConstraint[]{live, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13)))},
				disjunctionOfGroup100, new int[0]
			),
			Arguments.of(
				"live scope, tag 13 named by a filter other than a plain primary key set",
				new FilterConstraint[]{
					live,
					userFilter(facetHaving(REF_TAG, or(entityPrimaryKeyInSet(20), entityPrimaryKeyInSet(13))))
				},
				defaults, new int[]{5, 6}
			),
			Arguments.of(
				"both scopes, tag 13 next to tag 20, disjunction of group 100, one selection for both scopes",
				new FilterConstraint[]{both, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13)))},
				disjunctionOfGroup100, new int[]{3, 4, 5, 6, 10, 12, 13}
			),
			Arguments.of(
				"both scopes, tag 13 next to tag 20, disjunction of group 100, a selection in each scope",
				new FilterConstraint[]{
					both,
					userFilter(
						inScope(Scope.LIVE, facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13))),
						inScope(Scope.ARCHIVED, facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13)))
					)
				},
				disjunctionOfGroup100, new int[]{3, 4, 10, 12, 13}
			),
			Arguments.of(
				"both scopes, tag 13 next to tag 20, defaults",
				new FilterConstraint[]{both, userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(20, 13)))},
				defaults, new int[0]
			)
		);
	}

	/**
	 * Checks that a selected facet no product of the searched scope references is a facet without a group: the group
	 * is a property of a reference, and the scope holds no reference to the facet to take one from. Its term holds no
	 * product, joins the facets without a group and follows their relations - not the relations of the group the facet
	 * has in another scope. So a selection made for both scopes at once keeps the group the archived scope gives tag
	 * 13, while the same selection made for each scope separately treats the tag as a facet without a group in the live
	 * scope. Only a filter that is exactly a primary key set selects an unreferenced facet.
	 *
	 * @param label     the row label, used in the test name only
	 * @param filter    the constraints of the filter
	 * @param relations the relation requirements
	 * @param expected  the primary keys of the products the query returns, ascending
	 * @param evita     the engine instance provided by the test extension
	 */
	@DisplayName("Should treat a selected facet the searched scope does not reference as a facet without a group")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("unreferencedSelectedFacetRows")
	void shouldTreatSelectedFacetUnreferencedInScopeAsFacetWithoutGroup(
		@Nonnull String label,
		@Nonnull FilterConstraint[] filter,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] expected,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(ENTITY_SCOPED_PRODUCT),
						filterBy(filter),
						require(
							ArrayUtils.mergeArrays(
								new RequireConstraint[]{
									page(1, SCOPED_PRODUCT_TAGS.length),
									debug(
										DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS,
										DebugMode.VERIFY_POSSIBLE_CACHING_TREES
									)
								},
								relations
							)
						)
					),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of selections over the {@link #FACET_GROUPING_SHAPES} data set of the tags referenced under
	 * several groups - tag 50 in groups 100 and 200, tag 60 in group 300 and without a group. Each row is a label, the
	 * selected tags, the relation requirements, and the primary keys of the live products the query returns, computed
	 * from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> facetReferencedUnderSeveralGroupsRows() {
		final RequireConstraint[] defaults = new RequireConstraint[0];
		final RequireConstraint[] disjunctionBetweenGroups = {facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS)};
		return Stream.of(
			Arguments.of("tag 60 alone", new int[]{60}, defaults, new int[0]),
			Arguments.of(
				"tag 60 alone, disjunction between groups", new int[]{60}, disjunctionBetweenGroups, new int[]{9, 10}
			),
			Arguments.of(
				"tags 40 and 60, disjunction of group 300",
				new int[]{40, 60},
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(300)))
				},
				new int[]{2, 9, 10, 12}
			),
			Arguments.of("tags 40 and 60", new int[]{40, 60}, defaults, new int[0]),
			Arguments.of("tag 50 alone", new int[]{50}, defaults, new int[0]),
			Arguments.of(
				"tag 50 alone, disjunction between groups", new int[]{50}, disjunctionBetweenGroups, new int[]{7, 8}
			),
			Arguments.of("tags 10 and 50", new int[]{10, 50}, defaults, new int[]{8})
		);
	}

	/**
	 * Checks that a selected facet takes part in every group it is referenced under, including the facets without
	 * a group: the group is a property of a reference, so tag 60, referenced in group 300 by product 9 and without
	 * a group by product 10, has a term in both, and the terms are composed by the relations of their groups.
	 *
	 * @param label        the row label, used in the test name only
	 * @param selectedTags the selected tags
	 * @param relations    the relation requirements
	 * @param expected     the primary keys of the products the query returns, ascending
	 * @param evita        the engine instance provided by the test extension
	 */
	@DisplayName("Should select a facet in every group it is referenced under")
	@UseDataSet(FACET_GROUPING_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("facetReferencedUnderSeveralGroupsRows")
	void shouldSelectFacetInEveryGroupItIsReferencedUnder(
		@Nonnull String label,
		@Nonnull int[] selectedTags,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] expected,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					groupingTagSelectionQuery(
						new Scope[]{Scope.LIVE}, new FilterConstraint[0], selectedTags, relations
					),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the reference summary witness over the {@link #FACET_GROUPING_SHAPES} data set. Each row is
	 * a label, the requested scopes, the selected tags - each referenced under a single group, so that the selection
	 * alone in the groups of an option is the selection without the tags of those groups - whether the selection is
	 * made in each scope separately - `inScope(scope, facetHaving(...))` for every requested scope - and the relation
	 * requirements.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> facetGroupingSummaryRows() {
		final List<Arguments> rows = new ArrayList<>(40);
		final Map<String, RequireConstraint[]> relations = new LinkedHashMap<>();
		relations.put("defaults", new RequireConstraint[0]);
		relations.put(
			"disjunction between groups",
			new RequireConstraint[]{facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS)}
		);
		relations.put(
			"disjunction of group 300",
			new RequireConstraint[]{
				facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(300)))
			}
		);
		relations.put(
			"negation of group 200",
			new RequireConstraint[]{
				facetGroupsNegation(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(200)))
			}
		);
		final Map<String, Scope[]> scopes = new LinkedHashMap<>();
		scopes.put("live scope", new Scope[]{Scope.LIVE});
		scopes.put("both scopes", new Scope[]{Scope.LIVE, Scope.ARCHIVED});
		final int[][] selections = {{}, {10}, {20}, {40}, {10, 20}};
		for (final Entry<String, Scope[]> scope : scopes.entrySet()) {
			for (final int[] selection : selections) {
				for (final Entry<String, RequireConstraint[]> relation : relations.entrySet()) {
					rows.add(
						Arguments.of(
							scope.getKey() + ", selection " + Arrays.toString(selection) + ", " + relation.getKey(),
							scope.getValue(), selection, false, relation.getValue()
						)
					);
					if (scope.getValue().length > 1 && selection.length > 0) {
						rows.add(
							Arguments.of(
								scope.getKey() + ", selection " + Arrays.toString(selection) + " in each scope, " +
									relation.getKey(),
								scope.getValue(), selection, true, relation.getValue()
							)
						);
					}
				}
			}
		}
		return rows.stream();
	}

	/**
	 * Checks that the reference summary predicts the selection of a facet the way the result composes it when the facet
	 * is referenced under several groups, without a group included, or by an archived product only: every entry of the
	 * facet - one for each group it is listed in - predicts the result of selecting the facet, which selects it in
	 * all of its groups. The count must equal the result of selecting the facet alone, the impact the result of adding
	 * it to the selection, and an entry is listed exactly when that count is not zero. A selection made in each scope
	 * separately composes the facet in each scope by the groups that scope gives it - a scope holding no reference to
	 * it makes it a facet without a group; the count drops the selection and so stands for the facet selected in the
	 * whole query. The oracle is the engine's own result.
	 *
	 * @param label            the row label, used in the test name only
	 * @param scopes           the requested scopes
	 * @param selection        the selected tags
	 * @param selectionByScope whether the selection is made in each scope separately
	 * @param relations        the relation requirements
	 * @param evita            the engine instance provided by the test extension
	 */
	@DisplayName("Should predict the selection of a facet in every group it is referenced under")
	@UseDataSet(FACET_GROUPING_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("facetGroupingSummaryRows")
	void shouldPredictSelectionOfFacetInEveryGroupItIsReferencedUnder(
		@Nonnull String label,
		@Nonnull Scope[] scopes,
		@Nonnull int[] selection,
		boolean selectionByScope,
		@Nonnull RequireConstraint[] relations,
		Evita evita
	) {
		final FilterConstraint[] nothing = new FilterConstraint[0];
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<String> disagreements = new ArrayList<>(16);
				final ToIntFunction<int[]> resultSize = tags -> session.query(
					groupingTagSelectionQuery(scopes, nothing, tags, selectionByScope, relations), EntityReference.class
				).getTotalRecordCount();
				final EvitaResponse<EntityReference> withSummary = session.query(
					groupingTagSelectionQuery(
						scopes, nothing, selection, selectionByScope, relations,
						referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.IMPACT)
					),
					EntityReference.class
				);
				final int currentSize = withSummary.getTotalRecordCount();
				final ReferenceSummary summary = withSummary.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary, "the reference summary must be computed");
				for (final int tagId : GROUPING_TAGS) {
					final Set<Integer> groups = groupingTagGroupsOf(tagId, scopes);
					final int aloneSize = session.query(
						groupingTagSelectionQuery(scopes, nothing, new int[]{tagId}, false, relations),
						EntityReference.class
					).getTotalRecordCount();
					for (final Integer groupId : groups) {
						final ReferenceGroupStatistics groupStatistics = groupId == null ?
							summary.getReferenceGroupStatistics(REF_TAG) :
							summary.getReferenceGroupStatistics(REF_TAG, groupId);
						final FacetStatistics statistics = groupStatistics == null ?
							null : groupStatistics.getFacetStatistics(tagId);
						final String entry = "tag " + tagId + " in group " + groupId + ": ";
						if (statistics == null) {
							if (aloneSize > 0) {
								disagreements.add(entry + "missing, selecting it alone returns " + aloneSize);
							}
							continue;
						}
						if (statistics.getCount() != aloneSize) {
							disagreements.add(
								entry + "count " + statistics.getCount() + ", selecting it alone returns " + aloneSize
							);
						}
						if (ArrayUtils.indexOf(tagId, selection) >= 0) {
							continue;
						}
						final RequestImpact impact = statistics.getImpact();
						final int extendedSize = resultSize.applyAsInt(
							ArrayUtils.insertIntIntoArrayOnIndex(tagId, selection, selection.length)
						);
						// the option alone in its groups - in each scope by the groups that scope gives the tags when
						// the selection is made in each scope separately
						final Scope[][] selectionScopes = selectionByScope ?
							Arrays.stream(scopes).map(it -> new Scope[]{it}).toArray(Scope[][]::new) :
							new Scope[][]{scopes};
						final FilterConstraint[] aloneSelections = new FilterConstraint[selectionScopes.length];
						for (int i = 0; i < selectionScopes.length; i++) {
							final Scope[] selectionScope = selectionScopes[i];
							final Set<Integer> optionGroups = effectiveGroupingTagGroupsOf(tagId, selectionScope);
							final int[] selectionOfOtherGroups = IntStream.of(selection)
								.filter(
									it -> effectiveGroupingTagGroupsOf(it, selectionScope)
										.stream()
										.noneMatch(optionGroups::contains)
								)
								.toArray();
							final FilterConstraint aloneSelection = facetHaving(
								REF_TAG,
								entityPrimaryKeyInSet(
									IntStream.concat(IntStream.of(selectionOfOtherGroups), IntStream.of(tagId))
										.boxed()
										.toArray(Integer[]::new)
								)
							);
							aloneSelections[i] = selectionByScope ?
								inScope(selectionScope[0], aloneSelection) : aloneSelection;
						}
						final int extendedAloneSize = session.query(
							query(
								collection(ENTITY_GROUPING_PRODUCT),
								filterBy(scope(scopes), userFilter(aloneSelections)),
								require(
									ArrayUtils.mergeArrays(
										new RequireConstraint[]{page(1, GROUPING_PRODUCT_TAGS.length)}, relations
									)
								)
							),
							EntityReference.class
						).getTotalRecordCount();
						final boolean hasSense = extendedSize > 0 &&
							(extendedSize != currentSize || extendedAloneSize > 0);
						if (impact == null || impact.matchCount() != extendedSize ||
							impact.difference() != extendedSize - currentSize || impact.hasSense() != hasSense) {
							disagreements.add(
								entry + "impact " +
									(impact == null ?
										"none" :
										impact.matchCount() + " (difference " + impact.difference() + ", has sense " +
											impact.hasSense() + ")") +
									", result " + extendedSize + " (difference " + (extendedSize - currentSize) +
									", has sense " + hasSense + ")"
							);
						}
					}
				}
				assertTrue(
					disagreements.isEmpty(),
					() -> "the reference summary must predict the result:\n" + String.join("\n", disagreements)
				);
				return null;
			}
		);
	}

	/**
	 * Returns the groups the passed tag takes part in when selected in the passed scopes of the
	 * {@link #FACET_GROUPING_SHAPES} data set - the groups their products reference it under, or no group when none of
	 * them references it.
	 *
	 * @param tagId  the tag
	 * @param scopes the scopes of the products
	 * @return the groups, NULL standing for the facets without a group; never empty
	 */
	@Nonnull
	private static Set<Integer> effectiveGroupingTagGroupsOf(int tagId, @Nonnull Scope[] scopes) {
		final Set<Integer> groups = groupingTagGroupsOf(tagId, scopes);
		return groups.isEmpty() ? Collections.singleton(null) : groups;
	}

	/**
	 * Returns the groups the products of the passed scopes of the {@link #FACET_GROUPING_SHAPES} data set reference
	 * the passed tag under.
	 *
	 * @param tagId  the tag
	 * @param scopes the scopes of the products
	 * @return the groups, NULL standing for the references without a group
	 */
	@Nonnull
	private static Set<Integer> groupingTagGroupsOf(int tagId, @Nonnull Scope[] scopes) {
		final Set<Integer> groups = new LinkedHashSet<>(4);
		for (int i = 0; i < GROUPING_PRODUCT_TAGS.length; i++) {
			if (ArrayUtils.indexOf(GROUPING_PRODUCT_SCOPES[i], scopes) < 0) {
				continue;
			}
			for (int j = 0; j < GROUPING_PRODUCT_TAGS[i].length; j++) {
				if (GROUPING_PRODUCT_TAGS[i][j] == tagId) {
					final int groupId = GROUPING_PRODUCT_TAG_GROUPS[i][j];
					groups.add(groupId == NO_GROUP ? null : groupId);
				}
			}
		}
		return groups;
	}

	/**
	 * Returns the rows of selections over the {@link #FACET_GROUPING_SHAPES} data set whose facets some searched index
	 * does not know while another one, or the global index of the scope, does. Each row is a label, the requested
	 * scopes, the constraints of the filter next to the selection, the selected tags, the relation requirements, and
	 * the primary keys of the products the query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> facetKnownToAnotherIndexRows() {
		final Scope[] both = {Scope.LIVE, Scope.ARCHIVED};
		final FilterConstraint[] nothing = new FilterConstraint[0];
		final RequireConstraint[] defaults = new RequireConstraint[0];
		final RequireConstraint[] conjunctionWithinGroups = {
			facetGroupsConjunction(REF_TAG, WITH_DIFFERENT_FACETS_IN_GROUP)
		};
		return Stream.of(
			Arguments.of(
				"archived-only tag next to a live index holding ungrouped tags", both, nothing, new int[]{13}, defaults,
				new int[]{5, 6}
			),
			Arguments.of(
				"archived-only tag joining a live tag of its group", both, nothing, new int[]{10, 13}, defaults,
				new int[]{1, 4, 5, 6, 8, 12}
			),
			Arguments.of(
				"tag unknown to the reduced index of tag 20, which holds ungrouped tags, conjunction within groups",
				new Scope[]{Scope.LIVE}, new FilterConstraint[]{referenceHaving(REF_TAG, entityPrimaryKeyInSet(20))},
				new int[]{10, 50}, conjunctionWithinGroups, new int[0]
			),
			Arguments.of(
				"tag unknown to the reduced index of tag 50, which holds no ungrouped tag, conjunction within groups",
				new Scope[]{Scope.LIVE}, new FilterConstraint[]{referenceHaving(REF_TAG, entityPrimaryKeyInSet(50))},
				new int[]{10, 11}, conjunctionWithinGroups, new int[0]
			),
			Arguments.of(
				"tag unknown to the reduced index of tag 50, which holds no ungrouped tag, defaults",
				new Scope[]{Scope.LIVE}, new FilterConstraint[]{referenceHaving(REF_TAG, entityPrimaryKeyInSet(50))},
				new int[]{10, 11}, defaults, new int[]{8}
			)
		);
	}

	/**
	 * Checks that a selected facet some searched index does not know keeps the groups the scope gives it: the facets of
	 * a selection are looked up in each searched index, and an index that does not know a facet the scope references
	 * must neither drop the facet's term from its group nor turn it into a term without a group. Every alternative
	 * plan of the query - the global indexes or the reduced indexes of a referenced tag - must return the same
	 * products.
	 *
	 * @param label        the row label, used in the test name only
	 * @param scopes       the requested scopes
	 * @param constraints  the constraints of the filter next to the selection
	 * @param selectedTags the selected tags
	 * @param relations    the relation requirements
	 * @param expected     the primary keys of the products the query returns, ascending
	 * @param evita        the engine instance provided by the test extension
	 */
	@DisplayName("Should keep the group of a selected facet unknown to a searched index")
	@UseDataSet(FACET_GROUPING_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("facetKnownToAnotherIndexRows")
	void shouldKeepGroupOfSelectedFacetUnknownToSearchedIndex(
		@Nonnull String label,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint[] constraints,
		@Nonnull int[] selectedTags,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] expected,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					groupingTagSelectionQuery(scopes, constraints, selectedTags, relations),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);
				return null;
			}
		);
	}

	/**
	 * Builds the query of the {@link #FACET_GROUPING_SHAPES} data set selecting the passed tags in the user filter.
	 *
	 * @param scopes       the requested scopes
	 * @param constraints  the constraints of the filter next to the user filter
	 * @param selectedTags the selected tags, possibly none
	 * @param relations    the relation requirements
	 * @param summaries    the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query groupingTagSelectionQuery(
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint[] constraints,
		@Nonnull int[] selectedTags,
		@Nonnull RequireConstraint[] relations,
		@Nonnull RequireConstraint... summaries
	) {
		return groupingTagSelectionQuery(scopes, constraints, selectedTags, false, relations, summaries);
	}

	/**
	 * Builds the query of the {@link #FACET_GROUPING_SHAPES} data set selecting the passed tags in the user filter,
	 * possibly in each requested scope separately.
	 *
	 * @param scopes           the requested scopes
	 * @param constraints      the constraints of the filter next to the user filter
	 * @param selectedTags     the selected tags, possibly none
	 * @param selectionByScope whether the tags are selected in each scope separately
	 * @param relations        the relation requirements
	 * @param summaries        the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query groupingTagSelectionQuery(
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint[] constraints,
		@Nonnull int[] selectedTags,
		boolean selectionByScope,
		@Nonnull RequireConstraint[] relations,
		@Nonnull RequireConstraint... summaries
	) {
		final FilterConstraint selection = selectedTags.length == 0 ?
			null :
			facetHaving(REF_TAG, entityPrimaryKeyInSet(Arrays.stream(selectedTags).boxed().toArray(Integer[]::new)));
		return query(
			collection(ENTITY_GROUPING_PRODUCT),
			filterBy(
				ArrayUtils.mergeArrays(
					new FilterConstraint[]{scope(scopes)},
					constraints,
					selection == null ?
						new FilterConstraint[0] :
						new FilterConstraint[]{
							selectionByScope ?
								userFilter(
									Arrays.stream(scopes)
										.map(scope -> inScope(scope, selection))
										.toArray(FilterConstraint[]::new)
								) :
								userFilter(selection)
						}
				)
			),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, GROUPING_PRODUCT_TAGS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					},
					summaries,
					relations
				)
			)
		);
	}

	/**
	 * Returns the rows of the reference summary witness over the {@link #FACET_TWIN_REFERENCE_SHAPES} data set, whose
	 * two references share the layout of facets and groups and differ only in the relations a row declares for them.
	 * Each row is a label, the tags selected by {@link #REF_TAG} - each referenced under a single group - and the
	 * relation requirements.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> twinReferenceSummaryRows() {
		return Stream.of(
			Arguments.of("no selection, defaults", new int[0], new RequireConstraint[0]),
			Arguments.of(
				"no selection, group 200 of the copy disjunctive",
				new int[0],
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_TAG_COPY, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(200)))
				}
			),
			Arguments.of(
				"no selection, group 200 of the tag disjunctive",
				new int[0],
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(200)))
				}
			),
			Arguments.of(
				"tags 20 and 30 selected, groups 100 and 200 disjunctive, conjunction within group 100, " +
					"group 300 negated",
				new int[]{20, 30},
				new RequireConstraint[]{
					facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(100, 200))),
					facetGroupsConjunction(
						REF_TAG, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(100))
					),
					facetGroupsNegation(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(300)))
				}
			),
			Arguments.of(
				"tags 40 and 50 selected, group 300 negated with conjunction within",
				new int[]{40, 50},
				new RequireConstraint[]{
					facetGroupsNegation(REF_TAG, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(300))),
					facetGroupsConjunction(REF_TAG, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(300)))
				}
			)
		);
	}

	/**
	 * Checks that the reference summaries of two references with the same layout of facets and groups each predict
	 * the selection the result makes for its own reference, under its own relations: every entry of a facet - one for
	 * each group it is listed in - must have the count of the result selecting the facet alone and the impact and
	 * has-sense of the result adding it to the selection, and an entry is listed exactly when that count is not zero.
	 * The has-sense of an option that changes nothing is the result of the option alone in each of its groups, next to
	 * the selection of the other groups. The oracle is the engine's own result.
	 *
	 * @param label     the row label, used in the test name only
	 * @param selection the tags selected by {@link #REF_TAG}
	 * @param relations the relation requirements
	 * @param evita     the engine instance provided by the test extension
	 */
	@DisplayName("Should predict the selection of a facet of either of two references with the same layout")
	@UseDataSet(FACET_TWIN_REFERENCE_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("twinReferenceSummaryRows")
	void shouldPredictSelectionOfFacetOfEitherReferenceWithSameLayout(
		@Nonnull String label,
		@Nonnull int[] selection,
		@Nonnull RequireConstraint[] relations,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<String> disagreements = new ArrayList<>(16);
				final ToIntFunction<Map<String, int[]>> resultSize = tags -> session.query(
					twinTagSelectionQuery(tags, relations), EntityReference.class
				).getTotalRecordCount();
				final EvitaResponse<EntityReference> withSummary = session.query(
					twinTagSelectionQuery(
						Map.of(REF_TAG, selection), relations,
						referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.IMPACT),
						referenceSummaryOfReference(REF_TAG_COPY, FacetStatisticsDepth.IMPACT)
					),
					EntityReference.class
				);
				final int currentSize = withSummary.getTotalRecordCount();
				final ReferenceSummary summary = withSummary.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary, "the reference summary must be computed");
				for (final String referenceName : new String[]{REF_TAG, REF_TAG_COPY}) {
					final int[] selectionOfReference = REF_TAG.equals(referenceName) ? selection : new int[0];
					for (final int tagId : TWIN_TAGS) {
						final Set<Integer> groups = twinTagGroupsOf(tagId);
						final int aloneSize = resultSize.applyAsInt(Map.of(referenceName, new int[]{tagId}));
						for (final Integer groupId : groups) {
							final ReferenceGroupStatistics groupStatistics =
								summary.getReferenceGroupStatistics(referenceName, groupId);
							final FacetStatistics statistics = groupStatistics == null ?
								null : groupStatistics.getFacetStatistics(tagId);
							final String entry = referenceName + " tag " + tagId + " in group " + groupId + ": ";
							if (statistics == null) {
								if (aloneSize > 0) {
									disagreements.add(entry + "missing, selecting it alone returns " + aloneSize);
								}
								continue;
							}
							if (statistics.getCount() != aloneSize) {
								disagreements.add(
									entry + "count " + statistics.getCount() + ", selecting it alone returns " +
										aloneSize
								);
							}
							if (ArrayUtils.indexOf(tagId, selectionOfReference) >= 0) {
								continue;
							}
							final Map<String, int[]> extendedSelection = new LinkedHashMap<>(4);
							extendedSelection.put(REF_TAG, selection);
							extendedSelection.merge(
								referenceName, new int[]{tagId},
								(selected, added) -> ArrayUtils.mergeArrays(selected, added)
							);
							final int extendedSize = resultSize.applyAsInt(extendedSelection);
							// the option alone in each of its groups, next to the selection of the other groups
							final Map<String, int[]> aloneSelection = new LinkedHashMap<>(extendedSelection);
							aloneSelection.put(
								referenceName,
								IntStream.concat(
									IntStream.of(selectionOfReference)
										.filter(it -> twinTagGroupsOf(it).stream().noneMatch(groups::contains)),
									IntStream.of(tagId)
								).toArray()
							);
							final int extendedAloneSize = resultSize.applyAsInt(aloneSelection);
							final boolean hasSense = extendedSize > 0 &&
								(extendedSize != currentSize || extendedAloneSize > 0);
							final RequestImpact impact = statistics.getImpact();
							if (impact == null || impact.matchCount() != extendedSize ||
								impact.difference() != extendedSize - currentSize || impact.hasSense() != hasSense) {
								disagreements.add(
									entry + "impact " +
										(impact == null ?
											"none" :
											impact.matchCount() + " (difference " + impact.difference() +
												", has sense " + impact.hasSense() + ")") +
										", result " + extendedSize + " (difference " + (extendedSize - currentSize) +
										", has sense " + hasSense + ")"
								);
							}
						}
					}
				}
				assertTrue(
					disagreements.isEmpty(),
					() -> "the reference summaries must predict the result:\n" + String.join("\n", disagreements)
				);
				return null;
			}
		);
	}

	/**
	 * Returns the groups the products of the {@link #FACET_TWIN_REFERENCE_SHAPES} data set reference the passed tag
	 * under - the same for both references.
	 *
	 * @param tagId the tag
	 * @return the groups
	 */
	@Nonnull
	private static Set<Integer> twinTagGroupsOf(int tagId) {
		final Set<Integer> groups = new LinkedHashSet<>(4);
		for (int i = 0; i < TWIN_PRODUCT_TAGS.length; i++) {
			for (int j = 0; j < TWIN_PRODUCT_TAGS[i].length; j++) {
				if (TWIN_PRODUCT_TAGS[i][j] == tagId) {
					groups.add(TWIN_PRODUCT_TAG_GROUPS[i][j]);
				}
			}
		}
		return groups;
	}

	/**
	 * Builds the query of the {@link #FACET_TWIN_REFERENCE_SHAPES} data set selecting the passed tags of each reference
	 * in the user filter.
	 *
	 * @param selection the selected tags of each reference, possibly none
	 * @param relations the relation requirements
	 * @param summaries the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query twinTagSelectionQuery(
		@Nonnull Map<String, int[]> selection,
		@Nonnull RequireConstraint[] relations,
		@Nonnull RequireConstraint... summaries
	) {
		final FilterConstraint[] facetSelections = Stream.of(REF_TAG, REF_TAG_COPY)
			.filter(referenceName -> selection.getOrDefault(referenceName, new int[0]).length > 0)
			.map(
				referenceName -> facetHaving(
					referenceName,
					entityPrimaryKeyInSet(Arrays.stream(selection.get(referenceName)).boxed().toArray(Integer[]::new))
				)
			)
			.toArray(FilterConstraint[]::new);
		return query(
			collection(ENTITY_TWIN_PRODUCT),
			filterBy(scope(Scope.LIVE), facetSelections.length == 0 ? null : userFilter(facetSelections)),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, TWIN_PRODUCT_TAGS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					},
					summaries,
					relations
				)
			)
		);
	}

	/**
	 * Builds the query of the {@link #FACET_RELATION_SHAPES} data set whose user filter holds the passed constraints.
	 *
	 * @param userFilterConstraints the constraints of the user filter
	 * @param relations             the relation requirements
	 * @param summaries             the reference summary requirements
	 * @return the query
	 */
	@Nonnull
	private static Query shapedFilterQuery(
		@Nonnull FilterConstraint[] userFilterConstraints,
		@Nonnull RequireConstraint[] relations,
		@Nonnull RequireConstraint... summaries
	) {
		return query(
			collection(ENTITY_SHAPED_PRODUCT),
			filterBy(userFilter(userFilterConstraints)),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, SHAPED_PRODUCT_LABELS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					},
					summaries,
					relations
				)
			)
		);
	}

	/**
	 * Returns the primary keys present in both passed ascending arrays.
	 *
	 * @param first  the first ascending primary keys
	 * @param second the second ascending primary keys
	 * @return the ascending primary keys present in both
	 */
	@Nonnull
	private static int[] intersectionOf(@Nonnull int[] first, @Nonnull int[] second) {
		return IntStream.of(first).filter(pk -> ArrayUtils.indexOf(pk, second) >= 0).toArray();
	}

	/**
	 * Returns the rows of the default negation witness over the {@link #FACET_RELATION_SHAPES} data set. Each row is a
	 * label, the selected labels, the relation requirements - the request-wide defaults of `facetCalculationRules`,
	 * possibly with relations declared for the label reference - the declared relations the requirements are
	 * equivalent to (NULL when the row claims no equivalence), the labels whose own count the reference summary
	 * computes as a negation, and the primary keys of the products the query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> defaultNegationRows() {
		final RequireConstraint negationWithinGroup = facetCalculationRules(
			FacetRelationType.NEGATION, FacetRelationType.CONJUNCTION
		);
		final RequireConstraint negationBetweenGroups = facetCalculationRules(
			FacetRelationType.DISJUNCTION, FacetRelationType.NEGATION
		);
		final RequireConstraint[] negationOfEveryGroup = {facetGroupsNegation(REF_LABEL)};
		final RequireConstraint conjunctionWithinFirstGroup = facetGroupsConjunction(
			REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))
		);
		return Stream.of(
			// with the other level at its system default, a negation is the same set at either level
			Arguments.of(
				"default negation within a group, one option", new int[]{3},
				new RequireConstraint[]{negationWithinGroup}, negationOfEveryGroup, new int[]{3},
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"default negation within a group, options of two groups", new int[]{1, 3},
				new RequireConstraint[]{negationWithinGroup}, negationOfEveryGroup, new int[]{1, 3},
				shapedProductsWithLabels(false, 1, 3)
			),
			Arguments.of(
				"default negation between groups, one option", new int[]{3},
				new RequireConstraint[]{negationBetweenGroups}, negationOfEveryGroup, new int[]{3},
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"default negation between groups, options of two groups", new int[]{1, 3},
				new RequireConstraint[]{negationBetweenGroups}, negationOfEveryGroup, new int[]{1, 3},
				shapedProductsWithLabels(false, 1, 3)
			),
			// a group declaring its own relation within the group is not decided by the default there, so the default
			// negation does not negate it among the other groups either
			Arguments.of(
				"default negation within a group, a group declaring its own relation", new int[]{1, 3},
				new RequireConstraint[]{negationWithinGroup, conjunctionWithinFirstGroup},
				new RequireConstraint[]{
					conjunctionWithinFirstGroup,
					facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B)))
				},
				new int[]{3},
				IntStream.of(shapedProductsWithLabels(true, 1))
					.filter(pk -> ArrayUtils.indexOf(pk, shapedProductsWithLabels(true, 3)) < 0)
					.toArray()
			),
			// a negation at both levels negates the groups just as at one of them
			Arguments.of(
				"default negation at both levels, one option", new int[]{3},
				new RequireConstraint[]{facetCalculationRules(FacetRelationType.NEGATION, FacetRelationType.NEGATION)},
				negationOfEveryGroup, new int[]{3},
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"default negation at both levels, options of two groups", new int[]{1, 3},
				new RequireConstraint[]{facetCalculationRules(FacetRelationType.NEGATION, FacetRelationType.NEGATION)},
				negationOfEveryGroup, new int[]{1, 3},
				shapedProductsWithLabels(false, 1, 3)
			),
			// with the level within the groups away from its system default, the default negation between groups still
			// negates every group
			Arguments.of(
				"exclusivity within a group, default negation between groups, one option", new int[]{3},
				new RequireConstraint[]{facetCalculationRules(FacetRelationType.EXCLUSIVITY, FacetRelationType.NEGATION)},
				null, new int[]{3},
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"exclusivity within a group, default negation between groups, options of two groups", new int[]{1, 3},
				new RequireConstraint[]{facetCalculationRules(FacetRelationType.EXCLUSIVITY, FacetRelationType.NEGATION)},
				null, new int[]{1, 3},
				shapedProductsWithLabels(false, 1, 3)
			),
			Arguments.of(
				"conjunction within a group, default negation between groups, one option", new int[]{3},
				new RequireConstraint[]{facetCalculationRules(FacetRelationType.CONJUNCTION, FacetRelationType.NEGATION)},
				null, new int[]{3},
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"conjunction within a group, default negation between groups, options of two groups", new int[]{1, 3},
				new RequireConstraint[]{facetCalculationRules(FacetRelationType.CONJUNCTION, FacetRelationType.NEGATION)},
				null, new int[]{1, 3},
				shapedProductsWithLabels(false, 1, 3)
			)
		);
	}

	/**
	 * Checks that a negation the request-wide defaults of `facetCalculationRules` set at one level, while the other
	 * level stays at its system default, selects the same products as a declared negation of every group, whichever
	 * level carries it, and that the reference summary predicts them: the count of each option with no option selected,
	 * and the count or impact of the last option with the others selected. A default negation between groups negates
	 * every group whatever the default within them is, in the result and in the summary alike.
	 *
	 * @param label              the row label, used in the test name only
	 * @param labelIds           the selected labels
	 * @param relations          the relation requirements
	 * @param declaredEquivalent the declared relations selecting the same products, NULL when the row claims none
	 * @param negatedLabelIds    the labels whose count with no option selected is a negation
	 * @param expected           the primary keys of the products the query returns, ascending
	 * @param evita              the engine instance provided by the test extension
	 */
	@DisplayName("Should apply a default negation the same way at either level")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("defaultNegationRows")
	void shouldApplyDefaultNegationAtEitherLevel(
		@Nonnull String label,
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint[] relations,
		@Nullable RequireConstraint[] declaredEquivalent,
		@Nonnull int[] negatedLabelIds,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the relation must exclude some products and keep others"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertLabelSelectionPredicted(session, labelIds, relations, negatedLabelIds, expected);
				if (declaredEquivalent != null) {
					assertLabelSelectionPredicted(session, labelIds, declaredEquivalent, negatedLabelIds, expected);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the default negation within groups that cannot take effect, over the
	 * {@link #FACET_RELATION_SHAPES} data set. Each row is a label, the default relation between groups that keeps the
	 * negation from taking effect, the selected labels (none when the query selects nothing), and whether the query
	 * requests the reference summary of the label reference.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> ineffectiveDefaultNegationRows() {
		return Stream.of(FacetRelationType.DISJUNCTION, FacetRelationType.EXCLUSIVITY)
			.flatMap(
				groupRelation -> Stream.of(new int[0], new int[]{3}, new int[]{1, 3})
					.flatMap(labelIds -> Stream.of(false, true).map(
						withSummary -> Arguments.of(
							"negation within a group, " + groupRelation.name().toLowerCase() + " between groups, " +
								(labelIds.length == 0 ? "no option" : "options " + Arrays.toString(labelIds)) +
								(withSummary ? ", with summary" : ", without summary"),
							groupRelation, labelIds, withSummary
						)
					))
			);
	}

	/**
	 * Checks that a default negation within groups combined with a default relation between groups other than
	 * a conjunction or a negation - which leaves no relation that could negate a group - makes the query fail with
	 * a client error naming the requirement and both of its arguments, whether the query selects any option and
	 * whether it requests the reference summary or not.
	 *
	 * @param label         the row label, used in the test name only
	 * @param groupRelation the default relation between groups
	 * @param labelIds      the selected labels, empty when the query selects none
	 * @param withSummary   whether the query requests the reference summary of the label reference
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should refuse a default negation within groups that cannot take effect")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("ineffectiveDefaultNegationRows")
	void shouldRefuseDefaultNegationWithinGroupsThatCannotTakeEffect(
		@Nonnull String label,
		@Nonnull FacetRelationType groupRelation,
		@Nonnull int[] labelIds,
		boolean withSummary,
		Evita evita
	) {
		final RequireConstraint[] relations = {facetCalculationRules(FacetRelationType.NEGATION, groupRelation)};
		final RequireConstraint summary = withSummary ?
			referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.IMPACT) : null;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInvalidUsageException exception = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> session.query(
						labelIds.length == 0 ?
							query(
								collection(ENTITY_SHAPED_PRODUCT),
								require(ArrayUtils.mergeArrays(new RequireConstraint[]{summary}, relations))
							) :
							shapedOptionQuery(REF_LABEL, labelIds, relations, summary),
						EntityReference.class
					)
				);
				for (final String fragment : new String[]{"facetCalculationRules", "NEGATION", groupRelation.name()}) {
					assertTrue(
						exception.getMessage().contains(fragment),
						"the message `" + exception.getMessage() + "` must contain `" + fragment + "`"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the witness of negations declared at both levels over the {@link #FACET_RELATION_SHAPES} data
	 * set, each with a group filter of its own. Each row is a label, the selected labels, the relation requirements,
	 * the declared relations the requirements are equivalent to (NULL when the row claims no equivalence), the labels
	 * whose own count the reference summary computes as a negation, and the primary keys of the products the query
	 * returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> twoLevelNegationRows() {
		final RequireConstraint groupAWithin = facetGroupsNegation(
			REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))
		);
		final RequireConstraint groupBBetween = facetGroupsNegation(
			REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))
		);
		final RequireConstraint groupABetween = facetGroupsNegation(
			REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))
		);
		final RequireConstraint groupBWithin = facetGroupsNegation(
			REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))
		);
		final RequireConstraint[] bothGroupsInOneDeclaration = {
			facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A, LABEL_GROUP_B)))
		};
		return Stream.of(
			// a negation applies at both levels whichever level declares it, so a group matching either filter is
			// negated, and the declaration of the other group changes nothing about it
			Arguments.of(
				"group A within, group B between, option of group A", new int[]{1},
				new RequireConstraint[]{groupAWithin, groupBBetween}, new RequireConstraint[]{groupAWithin},
				new int[]{1}, shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"group A within, group B between, option of group B", new int[]{3},
				new RequireConstraint[]{groupAWithin, groupBBetween}, new RequireConstraint[]{groupBBetween},
				new int[]{3}, shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"group A within, group B between, options of both groups", new int[]{1, 3},
				new RequireConstraint[]{groupAWithin, groupBBetween}, bothGroupsInOneDeclaration,
				new int[]{1, 3}, shapedProductsWithLabels(false, 1, 3)
			),
			Arguments.of(
				"group A between, group B within, option of group A", new int[]{1},
				new RequireConstraint[]{groupABetween, groupBWithin}, new RequireConstraint[]{groupABetween},
				new int[]{1}, shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"group A between, group B within, option of group B", new int[]{3},
				new RequireConstraint[]{groupABetween, groupBWithin}, new RequireConstraint[]{groupBWithin},
				new int[]{3}, shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"group A between, group B within, options of both groups", new int[]{1, 3},
				new RequireConstraint[]{groupABetween, groupBWithin}, bothGroupsInOneDeclaration,
				new int[]{1, 3}, shapedProductsWithLabels(false, 1, 3)
			),
			// a single declaration negates the group it matches and leaves the other one a positive selection
			Arguments.of(
				"group A within alone, option of group A", new int[]{1},
				new RequireConstraint[]{groupAWithin}, null,
				new int[]{1}, shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"group A within alone, option of group B", new int[]{3},
				new RequireConstraint[]{groupAWithin}, null,
				new int[0], shapedProductsWithLabels(true, 3)
			)
		);
	}

	/**
	 * Checks that negations declared at the two levels, each with a group filter of its own, negate every group either
	 * filter matches - in the query result and in the reference summary alike - and select the same products as a
	 * single declaration negating the same groups.
	 *
	 * @param label              the row label, used in the test name only
	 * @param labelIds           the selected labels
	 * @param relations          the relation requirements
	 * @param declaredEquivalent the declared relations selecting the same products, NULL when the row claims none
	 * @param negatedLabelIds    the labels whose count with no option selected is a negation
	 * @param expected           the primary keys of the products the query returns, ascending
	 * @param evita              the engine instance provided by the test extension
	 */
	@DisplayName("Should negate a group matching the negation declared at either level")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("twoLevelNegationRows")
	void shouldNegateGroupMatchingNegationDeclaredAtEitherLevel(
		@Nonnull String label,
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint[] relations,
		@Nullable RequireConstraint[] declaredEquivalent,
		@Nonnull int[] negatedLabelIds,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the relation must exclude some products and keep others"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertLabelSelectionPredicted(session, labelIds, relations, negatedLabelIds, expected);
				if (declaredEquivalent != null) {
					assertLabelSelectionPredicted(session, labelIds, declaredEquivalent, negatedLabelIds, expected);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of negations declared at both levels, one of which has a group filter that cannot be evaluated,
	 * over the {@link #FACET_RELATION_SHAPES} data set. Each row is a label and the two relation requirements. Every
	 * row is run with and without the reference summary of the label reference.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> twoLevelUnevaluableNegationRows() {
		final FilterBy byLabelGroupCode = filterBy(attributeEquals(ATTRIBUTE_CODE, "anything"));
		return Stream.of(
			// the negation without a filter decides every group, so nothing asks about the other filter
			Arguments.of(
				"negation of every group within, unevaluable filter between",
				new RequireConstraint[]{
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP),
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS, byLabelGroupCode)
				}
			),
			Arguments.of(
				"unevaluable filter within, negation of every group between",
				new RequireConstraint[]{
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, byLabelGroupCode),
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS)
				}
			),
			Arguments.of(
				"evaluable filter within, unevaluable filter between",
				new RequireConstraint[]{
					facetGroupsNegation(
						REF_LABEL, WITH_DIFFERENT_FACETS_IN_GROUP, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))
					),
					facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS, byLabelGroupCode)
				}
			)
		)
			.flatMap(row -> Stream.of(false, true).map(withSummary -> {
				final Object[] arguments = row.get();
				return Arguments.of(
					arguments[0] + (withSummary ? ", with summary" : ", without summary"), arguments[1], withSummary
				);
			}));
	}

	/**
	 * Checks that a group filter which cannot be evaluated fails the query when it belongs to one of two negations
	 * declared at different levels, even when the negation of the other level decides every group on its own.
	 *
	 * @param label       the row label, used in the test name only
	 * @param relations   the two relation requirements
	 * @param withSummary whether the query requests the reference summary of the label reference
	 * @param evita       the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query whose negation of either level has a filter that cannot be evaluated")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("twoLevelUnevaluableNegationRows")
	void shouldFailQueryWhoseNegationOfEitherLevelCannotBeEvaluated(
		@Nonnull String label,
		@Nonnull RequireConstraint[] relations,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Throwable exception = assertThrowsExactly(
					EntityNotManagedException.class,
					() -> session.query(
						shapedOptionQuery(
							REF_LABEL, new int[]{1}, relations,
							withSummary ? referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.COUNTS) : null
						),
						EntityReference.class
					)
				);
				assertTrue(
					exception.getMessage().contains("`" + ENTITY_LABEL_GROUP + "`"),
					"the message `" + exception.getMessage() + "` must name the group type"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that selecting the passed labels under the passed relations returns the expected products, and that the
	 * reference summary predicts them - the count of each label with no label selected, which is the products without
	 * the label for a negated one and with it otherwise, and the count (one label) or impact (several labels) of the
	 * last label with the others selected.
	 *
	 * @param session         the session to query in
	 * @param labelIds        the selected labels
	 * @param relations       the relation requirements
	 * @param negatedLabelIds the labels whose count with no label selected is a negation
	 * @param expected        the primary keys of the products the query returns, ascending
	 */
	private static void assertLabelSelectionPredicted(
		@Nonnull EvitaSessionContract session,
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint[] relations,
		@Nonnull int[] negatedLabelIds,
		@Nonnull int[] expected
	) {
		final EvitaResponse<EntityReference> result = session.query(
			shapedOptionQuery(REF_LABEL, labelIds, relations, null),
			EntityReference.class
		);
		assertArrayEquals(
			expected,
			result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
		);

		// with no label selected, the summary counts each label on its own - the path without a user filter
		final EvitaResponse<EntityReference> withoutSelection = session.query(
			query(
				collection(ENTITY_SHAPED_PRODUCT),
				require(
					ArrayUtils.mergeArrays(
						new RequireConstraint[]{
							page(1, SHAPED_PRODUCT_LABELS.length),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.COUNTS)
						},
						relations
					)
				)
			),
			EntityReference.class
		);
		for (final int labelId : labelIds) {
			final boolean negated = ArrayUtils.indexOf(labelId, negatedLabelIds) >= 0;
			assertEquals(
				shapedProductsWithLabels(!negated, labelId).length,
				facetCountOf(withoutSelection, REF_LABEL, LABEL_GROUPS[labelId - 1], labelId),
				"the reference summary must count the products selecting the label " + labelId + " alone returns"
			);
		}

		final int lastLabelId = labelIds[labelIds.length - 1];
		if (labelIds.length == 1) {
			final EvitaResponse<EntityReference> withSummary = session.query(
				shapedOptionQuery(
					REF_LABEL, labelIds, relations, referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.COUNTS)
				),
				EntityReference.class
			);
			assertEquals(
				expected.length,
				facetCountOf(withSummary, REF_LABEL, LABEL_GROUPS[lastLabelId - 1], lastLabelId),
				"the reference summary must count the products the label leaves in the result"
			);
		} else {
			final EvitaResponse<EntityReference> withSummary = session.query(
				shapedOptionQuery(
					REF_LABEL, Arrays.copyOf(labelIds, labelIds.length - 1), relations,
					referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.IMPACT)
				),
				EntityReference.class
			);
			final RequestImpact impact = facetStatisticsOf(
				withSummary, REF_LABEL, LABEL_GROUPS[lastLabelId - 1], lastLabelId
			).getImpact();
			assertNotNull(impact, "the reference summary must predict the impact of the label");
			assertEquals(
				expected.length,
				impact.matchCount(),
				"the reference summary must predict the products adding the label leaves in the result"
			);
		}
	}

	/**
	 * Returns the rows of the group filters that cannot be evaluated, over the {@link #FACET_RELATION_SHAPES} data set.
	 * Each row is a label, the reference the option is selected in, the selected option, the relation requirement with
	 * the group filter - which may name another reference - the exact type of the exception the query must fail with,
	 * and the fragments its message must contain. Every row is run with and without the reference summary of the
	 * reference the option is selected in.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> unevaluableGroupFilterRows() {
		final FilterBy byLabelGroupCode = filterBy(attributeEquals(ATTRIBUTE_CODE, "anything"));
		final FilterBy byTagGroupNote = filterBy(attributeEquals(ATTRIBUTE_NOTE, "anything"));
		final FilterBy byMarkGroupNote = filterBy(attributeEquals(ATTRIBUTE_NOTE, "anything"));
		return Stream.of(
			Arguments.of(
				"unmanaged group, attribute filter", REF_LABEL, 1,
				facetGroupsNegation(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"unmanaged group, attribute filter, ungrouped selection", REF_LABEL, 4,
				facetGroupsNegation(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"unmanaged group, attribute filter of disjunction, ungrouped selection", REF_LABEL, 4,
				facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"managed group, non-filterable attribute", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			Arguments.of(
				"managed group, non-filterable attribute, ungrouped selection", REF_TAG, UNGROUPED_TAG,
				facetGroupsNegation(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			// the query result never asks about exclusivity, only the reference summary does
			Arguments.of(
				"unmanaged group, attribute filter of exclusivity", REF_LABEL, 1,
				facetGroupsExclusivity(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"unmanaged group, attribute filter of exclusivity, ungrouped selection", REF_LABEL, 4,
				facetGroupsExclusivity(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"managed group, non-filterable attribute of exclusivity", REF_TAG, GROUPED_TAG,
				facetGroupsExclusivity(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			// the relation names a reference other than the one selected and summarized, so nothing asks about it
			Arguments.of(
				"unmanaged group, attribute filter of a reference not selected", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"managed group, non-filterable attribute of a reference not selected", REF_LABEL, 1,
				facetGroupsConjunction(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			// the group type holds no entity, so there is no index the filter could be looked up in
			Arguments.of(
				"managed group without entities, non-filterable attribute", REF_MARK, UNGROUPED_MARK,
				facetGroupsNegation(REF_MARK, byMarkGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			Arguments.of(
				"managed group without entities, non-filterable attribute of a reference not selected", REF_TAG,
				GROUPED_TAG,
				facetGroupsExclusivity(REF_MARK, byMarkGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			)
		)
			.flatMap(row -> Stream.of(false, true).map(withSummary -> {
				final Object[] arguments = row.get();
				final Object[] withArm = Arrays.copyOf(arguments, arguments.length + 1);
				withArm[0] = arguments[0] + (withSummary ? ", with summary" : ", without summary");
				withArm[arguments.length] = withSummary;
				return Arguments.of(withArm);
			}));
	}

	/**
	 * Checks that a group filter which cannot be evaluated - one asking an unmanaged group type about anything but its
	 * primary keys, or using an attribute the managed group type cannot filter by - makes the query fail with a client
	 * error, whether the selected option belongs to a group or not, whether the reference summary is requested or
	 * not, and whether anything in the query asks about the relation of that reference at all.
	 *
	 * @param label            the row label, used in the test name only
	 * @param referenceName    the reference the option is selected in
	 * @param optionId         the selected option
	 * @param relation         the relation requirement with the group filter
	 * @param expectedType     the exact type of the exception the query must fail with
	 * @param messageFragments the fragments the exception message must contain
	 * @param withSummary      whether the query requests the reference summary of the reference
	 * @param evita            the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query whose group filter cannot be evaluated")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("unevaluableGroupFilterRows")
	void shouldFailQueryWhoseGroupFilterCannotBeEvaluated(
		@Nonnull String label,
		@Nonnull String referenceName,
		int optionId,
		@Nonnull RequireConstraint relation,
		@Nonnull Class<? extends Throwable> expectedType,
		@Nonnull String[] messageFragments,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Throwable exception = assertThrowsExactly(
					expectedType,
					() -> session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(referenceName, entityPrimaryKeyInSet(optionId)))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								relation,
								withSummary ? referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				for (final String fragment : messageFragments) {
					assertTrue(
						exception.getMessage().contains(fragment),
						"the message `" + exception.getMessage() + "` must contain `" + fragment + "`"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the group filters that can be evaluated although nothing in the query result asks about them,
	 * over the {@link #FACET_RELATION_SHAPES} data set. Each row is a label, the reference the option is selected in,
	 * the selected option, the relation requirement with the group filter, and the primary keys of the products the
	 * query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> evaluableUnaskedGroupFilterRows() {
		return Stream.of(
			Arguments.of(
				"managed group, primary key filter of exclusivity", REF_TAG, GROUPED_TAG,
				facetGroupsExclusivity(REF_TAG, filterBy(entityPrimaryKeyInSet(TAG_GROUP))),
				shapedProductsWithTag(GROUPED_TAG)
			),
			Arguments.of(
				"unmanaged group, primary key filter of exclusivity", REF_LABEL, 1,
				facetGroupsExclusivity(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"unmanaged group, primary key filter of a reference not selected", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))),
				shapedProductsWithTag(GROUPED_TAG)
			),
			Arguments.of(
				"managed group without entities, filterable attribute", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_MARK, filterBy(attributeEquals(ATTRIBUTE_CODE, "anything"))),
				shapedProductsWithTag(GROUPED_TAG)
			)
		);
	}

	/**
	 * Checks that a group filter which can be evaluated leaves the query result and the reference summary intact when
	 * the query result itself never asks about the relation it belongs to - for exclusivity, or for a reference other
	 * than the selected one.
	 *
	 * @param label         the row label, used in the test name only
	 * @param referenceName the reference the option is selected in
	 * @param optionId      the selected option
	 * @param relation      the relation requirement with the group filter
	 * @param expected      the primary keys of the products the query returns, ascending
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should accept an evaluable group filter the query result does not ask about")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("evaluableUnaskedGroupFilterRows")
	void shouldAcceptEvaluableGroupFilterTheResultDoesNotAskAbout(
		@Nonnull String label,
		@Nonnull String referenceName,
		int optionId,
		@Nonnull RequireConstraint relation,
		@Nonnull int[] expected,
		Evita evita
	) {
		final Integer groupId = REF_LABEL.equals(referenceName) ?
			LABEL_GROUPS[optionId - 1] : (optionId == GROUPED_TAG ? TAG_GROUP : null);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final boolean withSummary : new boolean[]{false, true}) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(referenceName, entityPrimaryKeyInSet(optionId)))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
								relation,
								withSummary ? referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					);
					assertArrayEquals(
						expected,
						result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
					);
					if (withSummary) {
						assertEquals(
							expected.length,
							facetCountOf(result, referenceName, groupId, optionId),
							"the reference summary must count the products the option leaves in the result"
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the group filters restricted to the archived scope of the {@link #FACET_SCOPE_SHAPES} data
	 * set, whose group type holds no archived entity and so has no index of that scope. Each row is a label, the
	 * relation requirement with the group filter, whether the query selects a tag, and whether it requests the
	 * reference summary of the tags.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> groupFilterOfScopeWithoutGroupIndexRows() {
		final FilterBy byArchivedNote = filterBy(inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_NOTE, "anything")));
		return Stream.of(
				Arguments.of("negation", facetGroupsNegation(REF_TAG, byArchivedNote)),
				Arguments.of("negation between groups", facetGroupsNegation(REF_TAG, WITH_DIFFERENT_GROUPS, byArchivedNote)),
				Arguments.of("conjunction", facetGroupsConjunction(REF_TAG, byArchivedNote)),
				Arguments.of(
					"disjunction between groups", facetGroupsDisjunction(REF_TAG, WITH_DIFFERENT_GROUPS, byArchivedNote)
				),
				Arguments.of("exclusivity", facetGroupsExclusivity(REF_TAG, byArchivedNote))
			)
			.flatMap(
				row -> Stream.of(
					Arguments.of(row.get()[0] + ", no selection", row.get()[1], false, false),
					Arguments.of(row.get()[0] + ", a tag selected", row.get()[1], true, false),
					Arguments.of(row.get()[0] + ", a tag selected, with summary", row.get()[1], true, true)
				)
			);
	}

	/**
	 * Checks that a group filter which cannot be evaluated in one of the requested scopes makes the query fail with
	 * a client error even when the group type has no index of that scope, while it has one of another requested
	 * scope - the filter is checked in every requested scope, not only in those the group type holds entities of.
	 *
	 * @param label         the row label, used in the test name only
	 * @param relation      the relation requirement with the group filter
	 * @param withSelection whether the query selects a tag
	 * @param withSummary   whether the query requests the reference summary of the tags
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query whose group filter cannot be evaluated in a scope without group index")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("groupFilterOfScopeWithoutGroupIndexRows")
	void shouldFailQueryWhoseGroupFilterCannotBeEvaluatedInScopeWithoutGroupIndex(
		@Nonnull String label,
		@Nonnull RequireConstraint relation,
		boolean withSelection,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AttributeNotFilterableException exception = assertThrowsExactly(
					AttributeNotFilterableException.class,
					() -> session.query(
						query(
							collection(ENTITY_SCOPED_PRODUCT),
							filterBy(
								scope(Scope.LIVE, Scope.ARCHIVED),
								withSelection ? userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(10))) : null
							),
							require(
								page(1, SCOPED_PRODUCT_TAGS.length),
								relation,
								withSummary ? referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				assertTrue(
					exception.getMessage().contains("`" + ATTRIBUTE_NOTE + "`"),
					"the message `" + exception.getMessage() + "` must name the attribute"
				);
				return null;
			}
		);
	}

	/**
	 * Checks that a group filter restricted to a requested scope the group type has no index of is accepted when it
	 * can be evaluated there - checking the filter in that scope must not refuse a filter the group schema allows.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DisplayName("Should accept an evaluable group filter of a scope without group index")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@Test
	void shouldAcceptEvaluableGroupFilterOfScopeWithoutGroupIndex(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertDoesNotThrow(
					() -> session.query(
						query(
							collection(ENTITY_SCOPED_PRODUCT),
							filterBy(
								scope(Scope.LIVE, Scope.ARCHIVED),
								userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(10)))
							),
							require(
								page(1, SCOPED_PRODUCT_TAGS.length),
								facetGroupsNegation(
									REF_TAG, filterBy(inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_CODE, "group100")))
								),
								referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.COUNTS)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the nested filters restricted to the archived scope of the {@link #FACET_SCOPE_SHAPES} data
	 * set, each looking into an entity type that holds no archived entity and so has no index of that scope, while the
	 * query requests both scopes. Each row is a label and the query, whose nested filter asks the attribute
	 * {@link #ATTRIBUTE_NOTE}, which is not filterable.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> nestedFilterOfScopeWithoutIndexRows() {
		final FilterConstraint archivedNote = inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_NOTE, "anything"));
		return Stream.of(
			Arguments.of(
				"group filter of the reference summary",
				scopedProductSummaryQuery(
					referenceSummaryOfReference(
						REF_TAG, FacetStatisticsDepth.COUNTS, (FilterBy) null, filterGroupBy(archivedNote)
					)
				)
			),
			Arguments.of(
				"option filter of the reference summary",
				scopedProductSummaryQuery(
					referenceSummaryOfReference(
						REF_TAG, FacetStatisticsDepth.COUNTS, filterBy(archivedNote), (FilterGroupBy) null
					)
				)
			),
			Arguments.of(
				"entity filter of a result segment",
				query(
					collection(ENTITY_SCOPED_TAG_GROUP),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
					orderBy(
						segments(
							segment(entityHaving(archivedNote), orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)))
						)
					),
					require(page(1, SCOPED_TAGS.length))
				)
			)
		);
	}

	/**
	 * Checks that a nested filter which cannot be evaluated in one of the requested scopes makes the query fail with
	 * a client error even when the entity type it looks into has no index of that scope - the filter is checked in
	 * every requested scope, not only in those the entity type holds entities of.
	 *
	 * @param label the row label, used in the test name only
	 * @param query the query with the nested filter
	 * @param evita the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query whose nested filter cannot be evaluated in a scope without index")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("nestedFilterOfScopeWithoutIndexRows")
	void shouldFailQueryWhoseNestedFilterCannotBeEvaluatedInScopeWithoutIndex(
		@Nonnull String label,
		@Nonnull Query query,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AttributeNotFilterableException exception = assertThrowsExactly(
					AttributeNotFilterableException.class,
					() -> session.query(query, EntityReference.class)
				);
				assertTrue(
					exception.getMessage().contains("`" + ATTRIBUTE_NOTE + "`"),
					"the message `" + exception.getMessage() + "` must name the attribute"
				);
				return null;
			}
		);
	}

	/**
	 * Checks that a nested filter restricted to a requested scope the entity type it looks into has no index of is
	 * accepted when it can be evaluated there - checking the filter in that scope must not refuse a filter the schema
	 * allows, and the filter keeps restricting that scope only, so the live tags stay in the summary.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DisplayName("Should accept an evaluable nested filter of a scope without index")
	@UseDataSet(FACET_SCOPE_SHAPES)
	@Test
	void shouldAcceptEvaluableNestedFilterOfScopeWithoutIndex(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final FilterConstraint archivedCode = inScope(Scope.ARCHIVED, attributeEquals(ATTRIBUTE_CODE, "tag10"));
				final ReferenceSummary summary = session.query(
					scopedProductSummaryQuery(
						referenceSummaryOfReference(
							REF_TAG, FacetStatisticsDepth.COUNTS, filterBy(archivedCode), (FilterGroupBy) null
						)
					),
					EntityReference.class
				).getExtraResult(ReferenceSummary.class);
				assertNotNull(summary, "the reference summary must be computed");
				assertNotNull(
					summary.getReferenceGroupStatistics(REF_TAG, scopedTagGroupOf(10)),
					"a filter of the archived scope must not restrict the live tags"
				);
				return null;
			}
		);
	}

	/**
	 * Builds the query of the {@link #FACET_SCOPE_SHAPES} data set over both scopes requesting the passed reference
	 * summary.
	 *
	 * @param summary the reference summary requirement
	 * @return the query
	 */
	@Nonnull
	private static Query scopedProductSummaryQuery(@Nonnull RequireConstraint summary) {
		return query(
			collection(ENTITY_SCOPED_PRODUCT),
			filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
			require(page(1, SCOPED_PRODUCT_TAGS.length), summary)
		);
	}

	/**
	 * Returns the rows of the facet relation constraints naming a reference the entity does not have, over the
	 * {@link #FACET_RELATION_SHAPES} data set. Each row is a label, the relation requirement, whether the query selects
	 * an option of {@link #REF_TAG} in `facetHaving`, and whether it requests the reference summary of
	 * {@link #REF_TAG}.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> missingReferenceRelationRows() {
		final String missingReference = "missingReference";
		final FilterBy byCode = filterBy(attributeEquals(ATTRIBUTE_CODE, "anything"));
		return Stream.<RequireConstraint>of(
				facetGroupsConjunction(missingReference), facetGroupsConjunction(missingReference, byCode),
				facetGroupsDisjunction(missingReference, WITH_DIFFERENT_GROUPS),
				facetGroupsDisjunction(missingReference, WITH_DIFFERENT_GROUPS, byCode),
				facetGroupsNegation(missingReference), facetGroupsNegation(missingReference, byCode),
				facetGroupsExclusivity(missingReference), facetGroupsExclusivity(missingReference, byCode)
			)
			.flatMap(
				relation -> Stream.of(
					Arguments.of(relation + ", selection and summary", relation, true, true),
					Arguments.of(relation + ", selection without summary", relation, true, false),
					Arguments.of(relation + ", no selection and no summary", relation, false, false)
				)
			);
	}

	/**
	 * Checks that a facet relation constraint naming a reference the entity does not have makes the query fail with
	 * {@link ReferenceNotFoundException}, as `referenceContent` and `referenceSummaryOfReference` naming such
	 * a reference do - with or without a group filter, and whether the query selects any option or requests
	 * the reference summary or not.
	 *
	 * @param label       the row label, used in the test name only
	 * @param relation    the relation requirement naming the missing reference
	 * @param withOption  whether the query selects an option of {@link #REF_TAG} in `facetHaving`
	 * @param withSummary whether the query requests the reference summary of {@link #REF_TAG}
	 * @param evita       the engine instance provided by the test extension
	 */
	@DisplayName("Should refuse a facet relation constraint naming a reference the entity does not have")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("missingReferenceRelationRows")
	void shouldRefuseRelationConstraintOfMissingReference(
		@Nonnull String label,
		@Nonnull RequireConstraint relation,
		boolean withOption,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ReferenceNotFoundException exception = assertThrowsExactly(
					ReferenceNotFoundException.class,
					() -> session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							withOption ?
								filterBy(userFilter(facetHaving(REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG)))) : null,
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								relation,
								withSummary ? referenceSummaryOfReference(REF_TAG, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				for (final String fragment : new String[]{"`missingReference`", "`" + ENTITY_SHAPED_PRODUCT + "`"}) {
					assertTrue(
						exception.getMessage().contains(fragment),
						"the message `" + exception.getMessage() + "` must contain `" + fragment + "`"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the option selections over the {@link #FACET_RELATION_SHAPES} data set, on the reference to
	 * the unmanaged source type and - as a control - on the reference to the managed tag type. Each row is a label,
	 * the reference the option is selected in, the selecting constraint of `facetHaving`, the option the selection
	 * resolves to, its group, the relation requirement of the reference (NULL for the defaults), and the primary keys
	 * of the products the query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> optionSelectionRows() {
		final int[] withGroupedTag = shapedProductsWithTag(GROUPED_TAG);
		return Stream.of(
			Arguments.of(
				"unmanaged option, primary key", REF_SOURCE, entityPrimaryKeyInSet(1), 1, null, null,
				shapedProductsWithSources(true, 1)
			),
			Arguments.of(
				"unmanaged option, primary key, negation", REF_SOURCE, entityPrimaryKeyInSet(1), 1, null,
				facetGroupsNegation(REF_SOURCE),
				shapedProductsWithSources(false, 1)
			),
			Arguments.of(
				"unmanaged option, primary key in a logical container", REF_SOURCE,
				or(entityPrimaryKeyInSet(2), entityPrimaryKeyInSet(MISSING_SOURCE)), 2, null, null,
				shapedProductsWithSources(true, 2)
			),
			Arguments.of(
				"unmanaged option, primary key in a negation", REF_SOURCE, not(entityPrimaryKeyInSet(1)), 2, null, null,
				shapedProductsWithSources(true, 2)
			),
			// a reference attribute is stored by the products themselves, so it needs nothing of the referenced entity
			Arguments.of(
				"unmanaged option, reference attribute", REF_SOURCE, attributeEquals(ATTRIBUTE_CHANNEL, "shop"), 2, null,
				null,
				shapedProductsWithSources(true, 2)
			),
			Arguments.of(
				"managed option, primary key", REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG), GROUPED_TAG, TAG_GROUP,
				null,
				withGroupedTag
			),
			Arguments.of(
				"managed option, primary key, negation", REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG), GROUPED_TAG,
				TAG_GROUP, facetGroupsNegation(REF_TAG),
				IntStream.rangeClosed(1, SHAPED_PRODUCT_TAGS.length)
					.filter(pk -> ArrayUtils.indexOf(pk, withGroupedTag) < 0)
					.toArray()
			),
			Arguments.of(
				"managed option, primary key in a logical container", REF_TAG,
				or(entityPrimaryKeyInSet(GROUPED_TAG), entityPrimaryKeyInSet(MISSING_TAG)), GROUPED_TAG, TAG_GROUP,
				null,
				withGroupedTag
			),
			Arguments.of(
				"managed option, entity attribute", REF_TAG, entityHaving(attributeEquals(ATTRIBUTE_CODE, "tag1")),
				GROUPED_TAG, TAG_GROUP, null,
				withGroupedTag
			)
		);
	}

	/**
	 * Checks that `facetHaving` selects the options of a reference to an entity type evitaDB does not manage by what
	 * the products store about them - the primary keys, also inside logical containers, and the reference attributes -
	 * exactly as it selects the options of a reference to a managed type, and that the reference summary counts the
	 * selected option as many products as the query returns, also when the option is negated.
	 *
	 * @param label         the row label, used in the test name only
	 * @param referenceName the reference the option is selected in
	 * @param selection     the selecting constraint of `facetHaving`
	 * @param optionId      the option the selection resolves to
	 * @param groupId       the group of the option, NULL for an option without a group
	 * @param relation      the relation requirement of the reference, NULL for the defaults
	 * @param expected      the primary keys of the products the query returns, ascending
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should select options whether the referenced entity type is managed or not")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("optionSelectionRows")
	void shouldSelectOptionsWhetherReferencedTypeIsManagedOrNot(
		@Nonnull String label,
		@Nonnull String referenceName,
		@Nonnull FilterConstraint selection,
		int optionId,
		@Nullable Integer groupId,
		@Nullable RequireConstraint relation,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the selection must exclude some products and keep others"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final boolean withSummary : new boolean[]{false, true}) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(referenceName, selection))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
								relation,
								withSummary ? referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					);
					assertArrayEquals(
						expected,
						result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
					);
					if (withSummary) {
						assertEquals(
							expected.length,
							facetCountOf(result, referenceName, groupId, optionId),
							"the reference summary must count the products the option leaves in the result"
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the option selections over the {@link #FACET_RELATION_SHAPES} data set that ask the unmanaged
	 * source type about its own data, which evitaDB does not have. Each row is a label and the selecting constraint
	 * of `facetHaving`; every row is run with and without the reference summary of the reference.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> unevaluableUnmanagedOptionSelectionRows() {
		final FilterConstraint byEntityCode = entityHaving(attributeEquals(ATTRIBUTE_CODE, "anything"));
		return Stream.of(
			Arguments.of("entity attribute", new FilterConstraint[]{byEntityCode}),
			Arguments.of(
				"entity attribute in a logical container",
				new FilterConstraint[]{or(entityPrimaryKeyInSet(1), byEntityCode)}
			),
			Arguments.of(
				"entity attribute beside a primary key",
				new FilterConstraint[]{entityPrimaryKeyInSet(1), byEntityCode}
			)
		)
			.flatMap(row -> Stream.of(false, true).map(withSummary -> Arguments.of(
				row.get()[0] + (withSummary ? ", with summary" : ", without summary"), row.get()[1], withSummary
			)));
	}

	/**
	 * Checks that `facetHaving` asking a referenced entity type evitaDB does not manage about the data of the entities
	 * themselves makes the query fail with the client error naming that type, whether the reference summary is
	 * requested or not.
	 *
	 * @param label       the row label, used in the test name only
	 * @param selection   the selecting constraints of `facetHaving`
	 * @param withSummary whether the query requests the reference summary of the reference
	 * @param evita       the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query asking an unmanaged referenced entity type about its own data")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("unevaluableUnmanagedOptionSelectionRows")
	void shouldFailQueryAskingUnmanagedReferencedTypeAboutItsData(
		@Nonnull String label,
		@Nonnull FilterConstraint[] selection,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityNotManagedException exception = assertThrowsExactly(
					EntityNotManagedException.class,
					() -> session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(REF_SOURCE, selection))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								withSummary ? referenceSummaryOfReference(REF_SOURCE, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				assertTrue(
					exception.getMessage().contains("`" + ENTITY_SOURCE + "`"),
					"the message `" + exception.getMessage() + "` must name the unmanaged type"
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the group filter declared for a reference without any group type: the relation requirement,
	 * the name of its constraint, whether the reference summary is requested and whether an option of the reference is
	 * selected.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> groupFilterOfReferenceWithoutGroupTypeRows() {
		return Stream.concat(
			Stream.of(false, true)
				.flatMap(withSummary -> Stream.of(
					Arguments.of(
						"facetGroupsNegation" + (withSummary ? ", with summary" : ", without summary"),
						facetGroupsNegation(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1))), "facetGroupsNegation",
						withSummary, true
					),
					Arguments.of(
						"facetGroupsDisjunction" + (withSummary ? ", with summary" : ", without summary"),
						facetGroupsDisjunction(Entities.BRAND, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1))),
						"facetGroupsDisjunction", withSummary, true
					),
					// the query result never asks about exclusivity, only the reference summary does
					Arguments.of(
						"facetGroupsExclusivity" + (withSummary ? ", with summary" : ", without summary"),
						facetGroupsExclusivity(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1))),
						"facetGroupsExclusivity", withSummary, true
					)
				)),
			// nothing in the query asks about the relations of the reference
			Stream.of(
				Arguments.of(
					"facetGroupsConjunction, no option selected, without summary",
					facetGroupsConjunction(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1))),
					"facetGroupsConjunction", false, false
				)
			)
		);
	}

	/**
	 * Checks that a group filter declared for a reference that has no group type at all - its options never belong to
	 * any group - makes the query fail with a client error naming the reference and the relation constraint, whether
	 * the reference summary is requested or not, and whether an option of the reference is selected or not.
	 *
	 * @param label          the row label, used in the test name only
	 * @param relation       the relation requirement with the group filter
	 * @param constraintName the name of the relation constraint
	 * @param withSummary    whether the query requests the reference summary of the reference
	 * @param selected       whether the query selects an option of the reference in its user filter
	 * @param evita          the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query with a group filter of a reference without group type")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest(name = "{0}")
	@MethodSource("groupFilterOfReferenceWithoutGroupTypeRows")
	void shouldFailQueryWithGroupFilterOfReferenceWithoutGroupType(
		@Nonnull String label,
		@Nonnull RequireConstraint relation,
		@Nonnull String constraintName,
		boolean withSummary,
		boolean selected,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInvalidUsageException exception = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							selected ? filterBy(userFilter(facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)))) : null,
							require(
								page(1, 20),
								relation,
								withSummary ? referenceSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				final String message = exception.getMessage();
				assertTrue(message.contains("`" + Entities.BRAND + "`"), message);
				assertTrue(message.contains("`" + constraintName + "`"), message);
				assertTrue(message.contains("has no group type"), message);
				return null;
			}
		);
	}

	/**
	 * Builds the query selecting the passed labels of the {@link #FACET_RELATION_SHAPES} data set in the user filter.
	 *
	 * @param labelIds the selected labels
	 * @param relation the relation requirement of the label reference
	 * @param summary  the reference summary requirement, NULL when no summary is requested
	 * @return the query
	 */
	@Nonnull
	private static Query shapedLabelQuery(
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint relation,
		@Nullable RequireConstraint summary
	) {
		return query(
			collection(ENTITY_SHAPED_PRODUCT),
			filterBy(userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(Arrays.stream(labelIds).boxed().toArray(Integer[]::new))))),
			require(
				page(1, SHAPED_PRODUCT_LABELS.length),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
				relation,
				summary
			)
		);
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference any of the
	 * passed labels, or none of them, as the fixture table states.
	 *
	 * @param referencingAny `true` for the products referencing any of the labels, `false` for those referencing none
	 * @param labelIds       the labels
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithLabels(boolean referencingAny, int... labelIds) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_LABELS.length)
			.filter(pk -> Arrays.stream(SHAPED_PRODUCT_LABELS[pk - 1])
				.anyMatch(labelId -> ArrayUtils.indexOf(labelId, labelIds) >= 0) == referencingAny)
			.toArray();
	}

	/**
	 * Builds the query selecting the passed options of a reference of the {@link #FACET_RELATION_SHAPES} data set in the
	 * user filter.
	 *
	 * @param referenceName the reference the options are selected in
	 * @param optionIds     the selected options
	 * @param relations     the relation requirements
	 * @param summary       the reference summary requirement, NULL when no summary is requested
	 * @return the query
	 */
	@Nonnull
	private static Query shapedOptionQuery(
		@Nonnull String referenceName,
		@Nonnull int[] optionIds,
		@Nonnull RequireConstraint[] relations,
		@Nullable RequireConstraint summary
	) {
		return query(
			collection(ENTITY_SHAPED_PRODUCT),
			filterBy(
				userFilter(
					facetHaving(referenceName, entityPrimaryKeyInSet(Arrays.stream(optionIds).boxed().toArray(Integer[]::new)))
				)
			),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(1, SHAPED_PRODUCT_LABELS.length),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						summary
					},
					relations
				)
			)
		);
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference all of the
	 * passed labels, as the fixture table states.
	 *
	 * @param labelIds the labels
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithAllLabels(int... labelIds) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_LABELS.length)
			.filter(pk -> Arrays.stream(labelIds)
				.allMatch(labelId -> ArrayUtils.indexOf(labelId, SHAPED_PRODUCT_LABELS[pk - 1]) >= 0))
			.toArray();
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference any of the
	 * passed labels and none of the excluded ones, as the fixture table states.
	 *
	 * @param labelIds         the labels the products reference any of
	 * @param excludedLabelIds the labels the products reference none of
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithLabelsExcept(@Nonnull int[] labelIds, int... excludedLabelIds) {
		final int[] excludedProducts = shapedProductsWithLabels(true, excludedLabelIds);
		return IntStream.of(shapedProductsWithLabels(true, labelIds))
			.filter(pk -> ArrayUtils.indexOf(pk, excludedProducts) < 0)
			.toArray();
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference the passed
	 * tag, as the fixture table states.
	 *
	 * @param tagId the tag
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithTag(int tagId) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_TAGS.length)
			.filter(pk -> ArrayUtils.indexOf(tagId, SHAPED_PRODUCT_TAGS[pk - 1]) >= 0)
			.toArray();
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference any of the
	 * passed sources, or none of them, as the fixture table states.
	 *
	 * @param referencingAny `true` for the products referencing any of the sources, `false` for those referencing none
	 * @param sourceIds      the sources
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithSources(boolean referencingAny, int... sourceIds) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_SOURCES.length)
			.filter(pk -> Arrays.stream(SHAPED_PRODUCT_SOURCES[pk - 1])
				.anyMatch(sourceId -> ArrayUtils.indexOf(sourceId, sourceIds) >= 0) == referencingAny)
			.toArray();
	}

	/**
	 * Returns the count the reference summary of the passed response computes for the passed option.
	 *
	 * @param response      the response carrying the reference summary
	 * @param referenceName the reference the option belongs to
	 * @param groupId       the group of the option, NULL for an option without a group
	 * @param optionId      the option
	 * @return the count of the option
	 */
	private static int facetCountOf(
		@Nonnull EvitaResponse<EntityReference> response,
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int optionId
	) {
		return facetStatisticsOf(response, referenceName, groupId, optionId).getCount();
	}

	/**
	 * Returns the statistics the reference summary of the passed response computes for the passed option.
	 *
	 * @param response      the response carrying the reference summary
	 * @param referenceName the reference the option belongs to
	 * @param groupId       the group of the option, NULL for an option without a group
	 * @param optionId      the option
	 * @return the statistics of the option
	 */
	@Nonnull
	private static FacetStatistics facetStatisticsOf(
		@Nonnull EvitaResponse<EntityReference> response,
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int optionId
	) {
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, "the reference summary must be computed");
		final ReferenceGroupStatistics groupStatistics = groupId == null ?
			summary.getReferenceGroupStatistics(referenceName) :
			summary.getReferenceGroupStatistics(referenceName, groupId);
		assertNotNull(groupStatistics, "the group " + groupId + " of `" + referenceName + "` must have statistics");
		final FacetStatistics facetStatistics = groupStatistics.getFacetStatistics(optionId);
		assertNotNull(facetStatistics, "the option " + optionId + " of `" + referenceName + "` must have statistics");
		return facetStatistics;
	}

	@DisplayName("Should return products matching AND combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetAndCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] parameters = getParametersWithSameGroup(originalProductEntities, groups);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groups.toArray(new Integer[0]))))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.allMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching AND combination of facet using the attribute filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingSpecifiedFacetAndCombinationDefinedByGroupAttributeFilterInEntireSetBy(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterGroupIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] parameters = getParametersWithSameGroup(originalProductEntities, groups);
				final String[] groupCodes = groups.stream()
					.map(parameterGroupIndex::get)
					.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
					.toArray(String[]::new);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsConjunction(Entities.PARAMETER, filterBy(attributeInSet(ATTRIBUTE_CODE, groupCodes)))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.allMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching OR combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetOrCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] parameters = getParametersWithSameGroup(originalProductEntities, new HashSet<>());
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.anyMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching random facet within hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest()
	@MethodSource("returnRandomSeed")
	void shouldReturnProductsWithSpecifiedFacetInHierarchyTree(
		long seed,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random rnd = new Random(seed);
				final int categoryCount = session.getEntityCollectionSize(Entities.CATEGORY);
				for (String entityType : new String[]{Entities.CATEGORY, Entities.BRAND, Entities.STORE}) {
					final int entityCount = session.getEntityCollectionSize(entityType);
					final int numberOfSelectedFacets = rnd.nextInt(entityCount - 1) + 1;
					final Integer[] facetIds = new Integer[numberOfSelectedFacets];
					for (int j = 0; j < numberOfSelectedFacets; j++) {
						int primaryKey;
						do {
							primaryKey = rnd.nextInt(entityCount - 1) + 1;
						} while (ArrayUtils.contains(facetIds, primaryKey));
						facetIds[j] = primaryKey;
					}

					final int hierarchyRoot = rnd.nextInt(categoryCount - 1) + 1;
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(hierarchyRoot)),
									userFilter(
										facetHaving(entityType, entityPrimaryKeyInSet(facetIds))
									)
								)
							),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);

					final Set<Integer> selectedIdsAsSet = new HashSet<>(Arrays.asList(facetIds));
					assertResultIs(
						"Querying " + entityType + " facets in hierarchy root " + hierarchyRoot + ": " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> {
							// is within requested hierarchy
							final boolean isWithinHierarchy = sealedEntity.getReferences(Entities.CATEGORY)
								.stream()
								.anyMatch(it -> it.getReferencedPrimaryKey() == hierarchyRoot ||
									categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.anyMatch(catId -> hierarchyRoot == Integer.parseInt(catId.getCode()))
								);
							// has the facet
							final boolean hasFacet = sealedEntity.getReferences(entityType)
								.stream()
								.map(ReferenceContract::getReferencedPrimaryKey)
								.anyMatch(selectedIdsAsSet::contains);
							return isWithinHierarchy && hasFacet;
						},
						result.getRecordData()
					);
				}
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithHierarchicalFacetSubTreeUsingReferenceSummary} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching hierarchical facet including all its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (HierarchyItem rootItem : categoryHierarchy.getRootItems()) {
					final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
					final Integer[] facetIds = new Integer[]{hierarchyRoot};

					final Query query = query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								userFilter(
									facetHaving(
										Entities.CATEGORY,
										entityPrimaryKeyInSet(facetIds),
										includingChildren()
									)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetSummary(FacetStatisticsDepth.IMPACT)
						)
					);

					final EvitaResponse<EntityReference> result = session.query(
						query,
						EntityReference.class
					);

					assertResultIs(
						"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> {
							// is within requested hierarchy
							return sealedEntity.getReferences(Entities.CATEGORY)
								.stream()
								.anyMatch(it -> it.getReferencedPrimaryKey() == hierarchyRoot ||
									categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.anyMatch(catId -> hierarchyRoot == Integer.parseInt(catId.getCode()))
								);
						},
						result.getRecordData()
					);

					final int[] selectedIds = Stream.concat(
							Arrays.stream(facetIds),
							categoryHierarchy.getAllChildItems(rootItem.getCode())
								.stream()
								.map(it -> Integer.valueOf(it.getCode()))
						)
						.sorted()
						.mapToInt(Integer::intValue)
						.toArray();

					final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

					final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
						new FacetSummaryComputationParams.Builder(
							session, productSchema, originalProductEntities,
							query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
						)
							.selectedFacetProvider(referenceName -> {
								if (Entities.CATEGORY.equals(referenceName)) {
									return selectedIds;
								} else {
									return ArrayUtils.EMPTY_INT_ARRAY;
								}
							})
							.build()
					);

					assertFacetSummary(expectedSummary, actualFacetSummary);
				}
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithPartialHierarchicalFacetSubTreeUsingReferenceSummary} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching hierarchical facet including some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithPartialHierarchicalFacetSubTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenHaving(
										entityPrimaryKeyInSet(7)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSomeUsingReferenceSummary} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching hierarchical facet excluding some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSome(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenExcept(
										entityPrimaryKeyInSet(9)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSet} instead. Remove this method once FacetSummary is
	* removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					).build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithPriceFilter} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set when price filter is set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithPriceFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryWithImpactForPriceAndFacetInUserFilter} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with impact when both price filter and facet selection are in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryWithImpactForPriceAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryWithImpactForAttributeInUserFilter} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with impact when attribute filter is in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryWithImpactForAttributeInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedEntitiesPredicate(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryWithImpactForPriceAttributeAndFacetInUserFilter} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with impact when price, attribute and facet filters coexist in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryWithImpactForPriceAttributeAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
							facetHaving(Entities.STORE, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							)
							&& ofNullable((BigDecimal) product.getAttribute(ATTRIBUTE_QUANTITY))
								.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
								.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnSortedReferenceStatisticsByPredecessorAttribute} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return sorted facet statistics by predecessor attribute")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSortedFacetStatisticsByPredecessorAttribute(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(Locale.ENGLISH)
					),
					require(
						page(1, Integer.MAX_VALUE),
						facetSummaryOfReference(
							Entities.STORE,
							FacetStatisticsDepth.COUNTS,
							orderBy(
								attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
							),
							entityFetch(
								attributeContentAll()
							)
						),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);
				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, Collections.emptyMap()
					)
						.entityFilter(sealedEntity -> sealedEntity.getLocales().contains(Locale.ENGLISH))
						.facetSorterFactory(refName -> {
							if (Entities.STORE.equals(refName)) {
								return Comparator.comparingInt(
								o -> ArrayUtils.indexOf(o.getFacetEntity().getPrimaryKeyOrThrowException(), STORE_ORDER)
							);
							} else {
								return null;
							}
						})
						.allowedReferenceNames(() -> Set.of(Entities.STORE))
						.facetEntityRequirementSupplier(refName -> entityFetch(attributeContentAll()))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithFacetMatchingConditionInEntireSetUsingReferenceSummary} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching facets identified by filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithFacetMatchingConditionInEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		Map<Integer, SealedEntity> parameterIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(
								Entities.PARAMETER,
								attributeEqualsFalse(ATTRIBUTE_TRANSIENT),
								entityHaving(attributeLessThanEquals(ATTRIBUTE_CODE, "C"))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);
				final int[] selectedFacets = parameterIndex.values()
					.stream()
					.filter(it -> it.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("C") < 0)
					.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
					.filter(
						facetId -> originalProductEntities.stream()
							.anyMatch(
								it -> it.getReference(Entities.PARAMETER, facetId)
									.map(ref -> Boolean.FALSE.equals(ref.getAttribute(ATTRIBUTE_TRANSIENT, Boolean.class)))
									.orElse(false)
							)
					)
					.toArray();

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.selectedFacetProvider(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return selectedFacets;
							} else {
								return new int[0];
							}
						})
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForFilteredSet} instead. Remove this method once FacetSummary is
	* removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForFacetFilteredSet} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for facet filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForFacetFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
							.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTree} instead. Remove this method once FacetSummary
	* is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] excludedSubTrees = {2, 10};
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								excluding(entityPrimaryKeyInSet(excludedSubTrees))
							),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5, 6, 7, 8)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8, 9))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);
				final Set<Integer> excluded = new HashSet<>(Arrays.asList(excludedSubTrees));

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> {
								final int categoryId = category.getReferencedPrimaryKey();
								final String categoryIdAsString = String.valueOf(categoryId);
								final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
								return
									// is not directly excluded node
									!excluded.contains(categoryId) &&
										// has no excluded parent node
										parentItems
											.stream()
											.map(it -> Integer.parseInt(it.getCode()))
											.noneMatch(excluded::contains) &&
										// has parent node 1
										(
											Objects.equals(1, categoryId) ||
												parentItems
													.stream()
													.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
										);
							}))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTreeWithStatistics} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatistics} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int allParametersWithinOneGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 3, 11
				);
				final int parametersInDifferentGroupsResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 2, 3, 11
				);
				assertTrue(
					parametersInDifferentGroupsResult < allParametersWithinOneGroupResult,
					"When parameter from different group is selected - result count must decrease."
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation}
	* instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for hierarchy with statistics and inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT),
						facetGroupsConjunction(Entities.STORE)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics " +
		"with inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] facets = getParametersWithSameGroup(originalProductEntities, groups);

				assertEquals(1, groups.size(), "There should be only one group.");
				assertTrue(facets.length > 1, "There should be at least two facets.");

				final int singleParameterSelectedResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromSameGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When selecting multiple parameters from same group it should increase the result"
				);
				final Integer groupId = groups.iterator().next();
				final int singleParameterSelectedResultInverted = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0]
				);
				final int twoParametersFromSameGroupResultInverted = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResultInverted < singleParameterSelectedResultInverted,
					"When certain parameter group relation is inverted to AND, " +
					"selecting multiple parameters from it should decrease the result"
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics " +
		"with inverted facet group relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] facets = Arrays.stream(getParametersWithDifferentGroups(originalProductEntities, new HashSet<>()))
					.limit(2)
					.toArray(Integer[]::new);
				final Integer[] groups = Arrays.stream(facets)
					.map(parameterGroupMapping::get)
					.distinct()
					.toArray(Integer[]::new);

				assertEquals(2, facets.length, "Number of facets must be exactly two.");
				assertEquals(facets.length, groups.length, "Number of facets and groups must be equal.");

				final int singleParameterSelectedResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromDifferentGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResult < singleParameterSelectedResult,
					"When selecting multiple facets from their groups should decrease the result"
				);
				final int singleParameterSelectedResultWithOr = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets[0]
				);
				final int twoParametersFromDifferentGroupResultWithOr = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResultWithOr > singleParameterSelectedResultWithOr,
					"When certain parameter group relation is inverted to OR, " +
					"selecting multiple facets from their groups should increase the result"
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics " +
		"with negated meaning of group")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int facetId = 3;
				final int singleParameterSelectedResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facetId
				);
				final int twoParametersFromSameGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsNegation(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(parameterGroupMapping.get(facetId)))),
					facetId
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When same parameter query is inverted to negative fashion, it must return more results"
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithGroupEntities} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with group entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithGroupEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.groupEntityRequirementSupplier(__ -> groupEntityRequirement)
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> true,
					groupEntity -> !groupEntity.getAttributeNames().isEmpty() &&
						(groupEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							groupEntity.getAttribute(ATTRIBUTE_CODE) != null)
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithFacetEntities} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with facet entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFacetEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(__ -> facetEntityRequirement)
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty() &&
						(facetEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null),
					groupEntity -> true
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithParameterEntities} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with parameter entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithParameterEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntitySetWithFacetEntitiesForParameters} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with facet entities for parameters")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntitySetWithFacetEntitiesForParameters(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.COUNTS),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacets} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with filtered and ordered facets")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFilteredAndOrderedFacets(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (Entities.PARAMETER.equals(reference.getReferenceName())) {
								final SealedEntity parameter = parameterIndex.get(reference.getReferencedPrimaryKey());
								return parameter.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
									parameter.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null;
							} else {
								return true;
							}
						})
						.facetSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterIndex.get(o1.getFacetEntity().getPrimaryKey());
									final SealedEntity parameter2 = parameterIndex.get(o2.getFacetEntity().getPrimaryKey());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getFacetEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacetGroups} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with filtered and ordered facet groups")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFilteredAndOrderedFacetGroups(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterGroupIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterGroupBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderGroupBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (reference.getReferenceKey().referenceName().equals(Entities.PARAMETER)) {
								return reference.getGroup()
									.map(groupRef -> parameterGroupIndex.get(groupRef.getPrimaryKey()))
									.map(group -> group.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
										group.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
									.orElse(false);
							} else {
								return true;
							}
						})
						.facetGroupSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterGroupIndex.get(o1.getGroupEntity().getPrimaryKeyOrThrowException());
									final SealedEntity parameter2 = parameterGroupIndex.get(o2.getGroupEntity().getPrimaryKeyOrThrowException());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getGroupEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with complex entity requirements with only default " +
		"requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						facetSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint carries no entity fetch of its own and inherits none
						// from the generic summary, so its reference comes back as a bare entity reference
						.facetEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_CODE) != null,
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntitiesAreBareReferences(actualFacetSummary, Entities.CATEGORY);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirements} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with complex entity requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithComplexEntityRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						facetSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)),
							entityGroupFetch()
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint governs its reference entirely - only the attributes
						// it asks for itself reach it, the generic summary's `code` is not united into them
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE));
							}
							return entityFetch(attributeContent(ATTRIBUTE_CODE));
						})
						.groupEntityRequirementSupplier(referenceName -> referenceName.equals(Entities.CATEGORY)
							? entityGroupFetch()
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> {
						if (facetEntity.getType().equals(Entities.CATEGORY)) {
							return facetEntity.getAttributeNames().size() == 1 &&
								facetEntity.getAttribute(ATTRIBUTE_NAME) != null;
						}
						return facetEntity.getAttributeNames().size() == 1 &&
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null;
					},
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntityAttributeNames(actualFacetSummary, Entities.CATEGORY, Set.of(ATTRIBUTE_NAME));

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSetUsingReferenceSummary} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching group EXCLUSIVE combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetGroupsExclusivity(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(1))),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final Predicate<SealedEntity> entityPredicate = sealedEntity -> sealedEntity
					.getReferences(Entities.PARAMETER)
					.stream()
					.filter(ref -> parameterIndex.contains(ref.getReferencedPrimaryKey()))
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.distinct()
					.count() == 2;

				assertResultIs(
					"Querying " + Entities.PRODUCT + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					entityPredicate,
					result.getRecordData()
				);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsUsingDifferentDefaultFacetRulesUsingReferenceSummary} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products using different default facet rules")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingDifferentDefaultFacetRules(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetCalculationRules(
							FacetRelationType.CONJUNCTION,
							FacetRelationType.EXCLUSIVITY
						),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	/**
	 * Asserts facet summary against expected without full entity data asserts.
	 *
	 * @deprecated Use {@link #assertReferenceSummary} instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummary(@Nonnull FacetSummaryWithResultCount expectedSummary,
	                                @Nullable FacetSummary actualFacetSummary) {
		assertFacetSummary(
			expectedSummary,
			actualFacetSummary,
			__ -> true,
			__ -> true
		);
	}

	/**
	 * Asserts facet summary against expected with full entity data asserts.
	 *
	 * @deprecated Use {@link #assertReferenceSummary} instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummary(
		@Nonnull FacetSummaryWithResultCount expectedSummary,
		@Nullable FacetSummary actualFacetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		assertFacetSummary(
			expectedSummary, actualFacetSummary, facetEntitiesAssertFunction, groupEntitiesAssertFunction,
			__ -> "", __ -> ""
		);
	}

	/**
	 * Asserts facet summary against expected with full entity data asserts.
	 *
	 * @deprecated Use {@link #assertReferenceSummary} instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummary(
		@Nonnull FacetSummaryWithResultCount expectedSummary,
		@Nullable FacetSummary actualFacetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction,
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		assertNotNull(actualFacetSummary);
		assertFalse(actualFacetSummary.getReferenceStatistics().isEmpty());
		assertEquals(
			new FacetSummaryToStringWrapper(expectedSummary.facetSummary(), groupRenderer, facetRenderer),
			new FacetSummaryToStringWrapper(actualFacetSummary, groupRenderer, facetRenderer),
			"Filtered entity count: " + expectedSummary.entityCount()
		);
		assertFacetSummaryEntities(
			actualFacetSummary,
			facetEntitiesAssertFunction,
			groupEntitiesAssertFunction
		);
	}

	/**
	 * Checks all group and facet entities and verifies them. This method expects that both actual facet summary and
	 * expected facet summary are equal.
	 * @deprecated Use {@link #assertReferenceSummaryEntities} instead. Remove this method once FacetSummary is removed.
	 *
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummaryEntities(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		facetSummary.getReferenceStatistics().forEach(actualFacetGroupStatistics -> {
			if (actualFacetGroupStatistics.getGroupEntity() != null &&
				actualFacetGroupStatistics.getGroupEntity() instanceof final SealedEntity groupEntity) {
				assertTrue(groupEntitiesAssertFunction.apply(groupEntity));
			}
			actualFacetGroupStatistics.getFacetStatistics().forEach(actualFacetStatistics -> {
				if (actualFacetStatistics.getFacetEntity() instanceof final SealedEntity facetEntity) {
					assertTrue(facetEntitiesAssertFunction.apply(facetEntity));
				}
			});
		});
	}

	/**
	 * Asserts that every facet entity the summary holds for the passed reference came back as a **bare entity
	 * reference**. A reference-specific summary constraint governs the reference it names entirely, so a reference
	 * whose constraint carries no `entityFetch` of its own fetches no body - not even the one the generic summary
	 * written beside it asks for.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the reference whose facet entities are examined
	 */
	private static void assertFacetEntitiesAreBareReferences(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		int examinedFacets = 0;
		for (final ReferenceGroupStatistics groupStatistics : summary.getReferenceStatistics()) {
			if (!referenceName.equals(groupStatistics.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facetStatistics : groupStatistics.getFacetStatistics()) {
				assertInstanceOf(
					EntityReference.class,
					facetStatistics.getFacetEntity(),
					"Facet entity of reference `" + referenceName + "` inherited the entity fetch of the generic summary!"
				);
				examinedFacets++;
			}
		}
		assertTrue(examinedFacets > 0, "Summary holds no facet for reference `" + referenceName + "`!");
	}

	/**
	 * Asserts that every facet entity the summary holds for the passed reference carries exactly the passed
	 * attributes - neither more (the generic summary's attributes are not united into the reference-specific fetch)
	 * nor fewer (the reference-specific fetch reaches the facet entity untouched).
	 *
	 * @param summary                 summary returned by the query
	 * @param referenceName           name of the reference whose facet entities are examined
	 * @param expectedAttributeNames  the attribute names the facet entities are expected to carry
	 */
	private static void assertFacetEntityAttributeNames(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName,
		@Nonnull Set<String> expectedAttributeNames
	) {
		int examinedFacets = 0;
		for (final ReferenceGroupStatistics groupStatistics : summary.getReferenceStatistics()) {
			if (!referenceName.equals(groupStatistics.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facetStatistics : groupStatistics.getFacetStatistics()) {
				final SealedEntity facetEntity = assertInstanceOf(
					SealedEntity.class,
					facetStatistics.getFacetEntity(),
					"The body of the `" + referenceName + "` facet entity was not fetched!"
				);
				assertEquals(
					expectedAttributeNames,
					facetEntity.getAttributeNames(),
					"Facet entity of reference `" + referenceName + "` does not carry exactly the attributes its own " +
						"summary constraint asked for!"
				);
				examinedFacets++;
			}
		}
		assertTrue(examinedFacets > 0, "Summary holds no facet for reference `" + referenceName + "`!");
	}

	/**
	 * Simplification method that executes query with facet computation and returns how many record matches the query
	 * that filters over input parameter facet ids.
	 * @deprecated Use {@link #queryParameterReferences} instead. Remove this method once FacetSummary is removed.
	 *
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private int queryParameterFacets(
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		EvitaSessionContract session,
		RequireConstraint additionalRequirement,
		Integer... facetIds
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				and(
					userFilter(
						facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(facetIds))
					)
				)
			),
			require(
				page(1, Integer.MAX_VALUE),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
				facetSummary(FacetStatisticsDepth.IMPACT),
				additionalRequirement
			)
		);
		final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
		final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

		final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
			new FacetSummaryComputationParams.Builder(
				session, productSchema, originalProductEntities,
				query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
			).build()
		);

		assertEquals(expectedSummary.entityCount(), result.getTotalRecordCount());
		assertFacetSummary(expectedSummary, actualFacetSummary);

		return result.getTotalRecordCount();
	}


	private record GroupReferenceWithEntityId(
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int entityId
	) {
	}

	/**
	 * @deprecated Use {@link #ReferenceSummaryWithResultCount} instead. Remove this record once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private record FacetSummaryWithResultCount(int entityCount, FacetSummary facetSummary) {
	}

	/**
	 * @deprecated Use {@link #ReferenceSummaryToStringWrapper} instead. Remove this record once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private record FacetSummaryToStringWrapper(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {

		@Nonnull
		@Override
		public String toString() {
			//noinspection unchecked
			return this.facetSummary.prettyPrint(
				(Function<ReferenceGroupStatistics, String>) (Function<?, ?>) this.groupRenderer, this.facetRenderer
			);
		}

	}


	// region Reference summary tests (using referenceSummary constraint)

	/**
	 * Wrapper around {@link #computeFacetSummary(FacetSummaryComputationParams)} that returns
	 * {@link ReferenceSummaryWithResultCount} instead of {@link FacetSummaryWithResultCount}.
	 * The intermediate {@link FacetSummary} is repackaged into a strict {@link ReferenceSummary}
	 * instance and every {@link FacetGroupStatistics} element is rebuilt as a plain
	 * {@link ReferenceGroupStatistics} — {@link ReferenceGroupStatistics#equals} is
	 * {@code getClass()}-strict, and the canonical {@code referenceSummary()} constraint emits
	 * plain {@link ReferenceGroupStatistics}, so the expected side must match that runtime type.
	 */
	private static ReferenceSummaryWithResultCount computeReferenceSummary(
		@Nonnull FacetSummaryComputationParams params
	) {
		final FacetSummaryWithResultCount result = computeFacetSummary(params);
		final Collection<ReferenceGroupStatistics> rebuilt = result.facetSummary()
			.getReferenceStatistics()
			.stream()
			.map(src -> new ReferenceGroupStatistics(
				src.getReferenceName(),
				src.getGroupEntity(),
				src.getCount(),
				src.getFacetStatistics()
					.stream()
					.collect(
						toMap(
							fs -> fs.getFacetEntity().getPrimaryKey(),
							Function.identity(),
							(a, b) -> {
								throw new IllegalStateException(
									"Duplicate facet statistics for primary key " +
										a.getFacetEntity().getPrimaryKey()
								);
							},
							LinkedHashMap::new
						)
					)
			))
			.toList();
		return new ReferenceSummaryWithResultCount(result.entityCount(), new ReferenceSummary(rebuilt));
	}

	/**
	 * Asserts reference summary against expected without full entity data asserts.
	 */
	private void assertReferenceSummary(@Nonnull ReferenceSummaryWithResultCount expectedSummary,
	                                    @Nullable ReferenceSummary actualReferenceSummary) {
		assertReferenceSummary(
			expectedSummary,
			actualReferenceSummary,
			__ -> true,
			__ -> true
		);
	}

	/**
	 * Asserts reference summary against expected with full entity data asserts.
	 */
	private void assertReferenceSummary(
		@Nonnull ReferenceSummaryWithResultCount expectedSummary,
		@Nullable ReferenceSummary actualReferenceSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		assertReferenceSummary(
			expectedSummary, actualReferenceSummary, facetEntitiesAssertFunction, groupEntitiesAssertFunction,
			__ -> "", __ -> ""
		);
	}

	/**
	 * Asserts reference summary against expected with full entity data asserts.
	 */
	private void assertReferenceSummary(
		@Nonnull ReferenceSummaryWithResultCount expectedSummary,
		@Nullable ReferenceSummary actualReferenceSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction,
		@Nonnull Function<ReferenceGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		assertNotNull(actualReferenceSummary);
		assertFalse(actualReferenceSummary.getReferenceStatistics().isEmpty());
		assertEquals(
			new ReferenceSummaryToStringWrapper(expectedSummary.referenceSummary(), groupRenderer, facetRenderer),
			new ReferenceSummaryToStringWrapper(actualReferenceSummary, groupRenderer, facetRenderer),
			"Filtered entity count: " + expectedSummary.entityCount()
		);
		assertReferenceSummaryEntities(
			actualReferenceSummary,
			facetEntitiesAssertFunction,
			groupEntitiesAssertFunction
		);
	}

	/**
	 * Checks all group and facet entities and verifies them. This method expects that both actual reference summary and
	 * expected reference summary are equal.
	 */
	private void assertReferenceSummaryEntities(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		referenceSummary.getReferenceStatistics().forEach(actualReferenceGroupStatistics -> {
			if (actualReferenceGroupStatistics.getGroupEntity() != null &&
				actualReferenceGroupStatistics.getGroupEntity() instanceof final SealedEntity groupEntity) {
				assertTrue(groupEntitiesAssertFunction.apply(groupEntity));
			}
			actualReferenceGroupStatistics.getFacetStatistics().forEach(actualFacetStatistics -> {
				if (actualFacetStatistics.getFacetEntity() instanceof final SealedEntity facetEntity) {
					assertTrue(facetEntitiesAssertFunction.apply(facetEntity));
				}
			});
		});
	}

	/**
	 * Simplification method that executes query with reference summary computation and returns how many record matches
	 * the query that filters over input parameter facet ids.
	 */
	private int queryParameterReferences(
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		EvitaSessionContract session,
		RequireConstraint additionalRequirement,
		Integer... facetIds
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				and(
					userFilter(
						facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(facetIds))
					)
				)
			),
			require(
				page(1, Integer.MAX_VALUE),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
				referenceSummary(FacetStatisticsDepth.IMPACT),
				additionalRequirement
			)
		);
		final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
		final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

		final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
			new FacetSummaryComputationParams.Builder(
				session, productSchema, originalProductEntities,
				query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
			).build()
		);

		assertEquals(expectedSummary.entityCount(), result.getTotalRecordCount());
		assertReferenceSummary(expectedSummary, actualReferenceSummary);

		return result.getTotalRecordCount();
	}

	@DisplayName("Should throw exception when accessing localized attributes on fetched entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesUsingReferenceSummary(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								referenceSummary(
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing localized attributes on fetched entities using explicit reference")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesOnExplicitReferenceUsingReferenceSummary(
			Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								referenceSummaryOfReference(
									Entities.PARAMETER,
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should not return reference summary for missing references on product")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldNotReturnReferenceSummaryForMissingReferencesOnProduct(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							not(referenceHaving(Entities.BRAND))
						),
						require(
							page(1, 1),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(referenceContent(Entities.BRAND)),
							referenceSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.COUNTS
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertTrue(result.getRecordData().get(0).getReferences(Entities.BRAND).isEmpty());
				assertNull(result.getExtraResult(ReferenceSummary.class).getReferenceGroupStatistics(Entities.BRAND));
				return null;
			}
		);
	}

	@DisplayName("Should return empty reference summary for empty collection")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test()
	void shouldReturnEmptyReferenceSummaryForEmptyCollection(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							referenceSummaryOfReference(
								EMPTY_COLLECTION_ENTITY,
								FacetStatisticsDepth.COUNTS,
								filterBy(attributeEquals(ATTRIBUTE_NAME, "anything"))
							)
						)
					),
					EntityReference.class
				);

				final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(referenceSummary);
				assertTrue(referenceSummary.getReferenceStatistics().isEmpty());
				return null;
			}
		);
	}

	/**
	 * Checks that a filter of the reference summary the schema of the referenced entity type cannot evaluate fails the
	 * query with a client error even when the referenced collection holds no entity - the filter is checked against
	 * the schema whether the entities exist or not.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the reference summary whose filter cannot be evaluated over an empty collection")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldFailReferenceSummaryWhoseFilterCannotBeEvaluatedOverEmptyCollection(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrowsExactly(
					ReferenceNotFoundException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReference(
									EMPTY_COLLECTION_ENTITY,
									FacetStatisticsDepth.COUNTS,
									filterBy(
										referenceHaving(Entities.PARAMETER, filterBy(entityHaving(entityPrimaryKeyInSet(1))))
									)
								)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet including all its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (HierarchyItem rootItem : categoryHierarchy.getRootItems()) {
					final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
					final Integer[] facetIds = new Integer[]{hierarchyRoot};

					final Query query = query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								userFilter(
									facetHaving(
										Entities.CATEGORY,
										entityPrimaryKeyInSet(facetIds),
										includingChildren()
									)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							referenceSummary(FacetStatisticsDepth.IMPACT)
						)
					);

					final EvitaResponse<EntityReference> result = session.query(
						query,
						EntityReference.class
					);

					assertResultIs(
						"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> {
							// is within requested hierarchy
							return sealedEntity.getReferences(Entities.CATEGORY)
								.stream()
								.anyMatch(it -> it.getReferencedPrimaryKey() == hierarchyRoot ||
									categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.anyMatch(catId -> hierarchyRoot == Integer.parseInt(catId.getCode()))
								);
						},
						result.getRecordData()
					);

					final int[] selectedIds = Stream.concat(
							Arrays.stream(facetIds),
							categoryHierarchy.getAllChildItems(rootItem.getCode())
								.stream()
								.map(it -> Integer.valueOf(it.getCode()))
						)
						.sorted()
						.mapToInt(Integer::intValue)
						.toArray();

					final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

					final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
						new FacetSummaryComputationParams.Builder(
							session, productSchema, originalProductEntities,
							query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
						)
							.selectedFacetProvider(referenceName -> {
								if (Entities.CATEGORY.equals(referenceName)) {
									return selectedIds;
								} else {
									return ArrayUtils.EMPTY_INT_ARRAY;
								}
							})
							.build()
					);

					assertReferenceSummary(expectedSummary, actualReferenceSummary);
				}
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet including some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithPartialHierarchicalFacetSubTreeUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenHaving(
										entityPrimaryKeyInSet(7)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet excluding some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSomeUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenExcept(
										entityPrimaryKeyInSet(9)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
			}
		);
	}

	@DisplayName("Should return reference summary for entire set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					).build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set when price filter is set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithPriceFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with impact when both price filter and facet selection are in user " +
		"filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryWithImpactForPriceAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with impact when attribute filter is in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryWithImpactForAttributeInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedEntitiesPredicate(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with impact when price, attribute and facet filters coexist in user " +
		"filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryWithImpactForPriceAttributeAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
							facetHaving(Entities.STORE, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							)
							&& ofNullable((BigDecimal) product.getAttribute(ATTRIBUTE_QUANTITY))
								.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
								.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return sorted reference statistics by predecessor attribute")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSortedReferenceStatisticsByPredecessorAttribute(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(Locale.ENGLISH)
					),
					require(
						page(1, Integer.MAX_VALUE),
						referenceSummaryOfReference(
							Entities.STORE,
							FacetStatisticsDepth.COUNTS,
							orderBy(
								attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
							),
							entityFetch(
								attributeContentAll()
							)
						),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);
				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, Collections.emptyMap()
					)
						.entityFilter(sealedEntity -> sealedEntity.getLocales().contains(Locale.ENGLISH))
						.facetSorterFactory(refName -> {
							if (Entities.STORE.equals(refName)) {
								return Comparator.comparingInt(
								o -> ArrayUtils.indexOf(o.getFacetEntity().getPrimaryKeyOrThrowException(), STORE_ORDER)
							);
							} else {
								return null;
							}
						})
						.allowedReferenceNames(() -> Set.of(Entities.STORE))
						.facetEntityRequirementSupplier(refName -> entityFetch(attributeContentAll()))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products matching facets identified by filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithFacetMatchingConditionInEntireSetUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		Map<Integer, SealedEntity> parameterIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(
								Entities.PARAMETER,
								attributeEqualsFalse(ATTRIBUTE_TRANSIENT),
								entityHaving(attributeLessThanEquals(ATTRIBUTE_CODE, "C"))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);
				final int[] selectedFacets = parameterIndex.values()
					.stream()
					.filter(it -> it.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("C") < 0)
					.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
					.filter(
						facetId -> originalProductEntities.stream()
							.anyMatch(
								it -> it.getReference(Entities.PARAMETER, facetId)
									.map(ref -> Boolean.FALSE.equals(ref.getAttribute(ATTRIBUTE_TRANSIENT, Boolean.class)))
									.orElse(false)
							)
					)
					.toArray();

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.selectedFacetProvider(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return selectedFacets;
							} else {
								return new int[0];
							}
						})
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for facet filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForFacetFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
							.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] excludedSubTrees = {2, 10};
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								excluding(entityPrimaryKeyInSet(excludedSubTrees))
							),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5, 6, 7, 8)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8, 9))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);
				final Set<Integer> excluded = new HashSet<>(Arrays.asList(excludedSubTrees));

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> {
								final int categoryId = category.getReferencedPrimaryKey();
								final String categoryIdAsString = String.valueOf(categoryId);
								final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
								return
									// is not directly excluded node
									!excluded.contains(categoryId) &&
										// has no excluded parent node
										parentItems
											.stream()
											.map(it -> Integer.parseInt(it.getCode()))
											.noneMatch(excluded::contains) &&
										// has parent node 1
										(
											Objects.equals(1, categoryId) ||
												parentItems
													.stream()
													.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
										);
							}))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int allParametersWithinOneGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 3, 11
				);
				final int parametersInDifferentGroupsResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 2, 3, 11
				);
				assertTrue(
					parametersInDifferentGroupsResult < allParametersWithinOneGroupResult,
					"When parameter from different group is selected - result count must decrease."
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for hierarchy with statistics and inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT),
						facetGroupsConjunction(Entities.STORE)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics " +
		"with inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] facets = getParametersWithSameGroup(originalProductEntities, groups);

				assertEquals(1, groups.size(), "There should be only one group.");
				assertTrue(facets.length > 1, "There should be at least two facets.");

				final int singleParameterSelectedResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromSameGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When selecting multiple parameters from same group it should increase the result"
				);
				final Integer groupId = groups.iterator().next();
				final int singleParameterSelectedResultInverted = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0]
				);
				final int twoParametersFromSameGroupResultInverted = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResultInverted < singleParameterSelectedResultInverted,
					"When certain parameter group relation is inverted to AND, " +
					"selecting multiple parameters from it should decrease the result"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics " +
		"with inverted facet group relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] facets = Arrays.stream(getParametersWithDifferentGroups(originalProductEntities, new HashSet<>()))
					.limit(2)
					.toArray(Integer[]::new);
				final Integer[] groups = Arrays.stream(facets)
					.map(parameterGroupMapping::get)
					.distinct()
					.toArray(Integer[]::new);

				assertEquals(2, facets.length, "Number of facets must be exactly two.");
				assertEquals(facets.length, groups.length, "Number of facets and groups must be equal.");

				final int singleParameterSelectedResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromDifferentGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResult < singleParameterSelectedResult,
					"When selecting multiple facets from their groups should decrease the result"
				);
				final int singleParameterSelectedResultWithOr = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets[0]
				);
				final int twoParametersFromDifferentGroupResultWithOr = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResultWithOr > singleParameterSelectedResultWithOr,
					"When certain parameter group relation is inverted to OR, " +
					"selecting multiple facets from their groups should increase the result"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics " +
		"with negated meaning of group")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int facetId = 3;
				final int singleParameterSelectedResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facetId
				);
				final int twoParametersFromSameGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsNegation(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(parameterGroupMapping.get(facetId)))),
					facetId
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When same parameter query is inverted to negative fashion, it must return more results"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with group entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithGroupEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.groupEntityRequirementSupplier(__ -> groupEntityRequirement)
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> true,
					groupEntity -> !groupEntity.getAttributeNames().isEmpty() &&
						(groupEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							groupEntity.getAttribute(ATTRIBUTE_CODE) != null)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with facet entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithFacetEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(__ -> facetEntityRequirement)
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty() &&
						(facetEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null),
					groupEntity -> true
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with parameter entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithParameterEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with facet entities for parameters")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntitySetWithFacetEntitiesForParameters(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.COUNTS),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with filtered and ordered facets")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacets(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (Entities.PARAMETER.equals(reference.getReferenceName())) {
								final SealedEntity parameter = parameterIndex.get(reference.getReferencedPrimaryKey());
								return parameter.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
									parameter.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null;
							} else {
								return true;
							}
						})
						.facetSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterIndex.get(o1.getFacetEntity().getPrimaryKey());
									final SealedEntity parameter2 = parameterIndex.get(o2.getFacetEntity().getPrimaryKey());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getFacetEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with filtered and ordered facet groups")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacetGroups(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterGroupIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterGroupBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderGroupBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (reference.getReferenceKey().referenceName().equals(Entities.PARAMETER)) {
								return reference.getGroup()
									.map(groupRef -> parameterGroupIndex.get(groupRef.getPrimaryKey()))
									.map(group -> group.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
										group.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
									.orElse(false);
							} else {
								return true;
							}
						})
						.facetGroupSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterGroupIndex.get(o1.getGroupEntity().getPrimaryKeyOrThrowException());
									final SealedEntity parameter2 = parameterGroupIndex.get(o2.getGroupEntity().getPrimaryKeyOrThrowException());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getGroupEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with complex entity requirements with only default " +
		"requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						referenceSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint carries no entity fetch of its own and inherits none
						// from the generic summary, so its reference comes back as a bare entity reference
						.facetEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_CODE) != null,
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntitiesAreBareReferences(actualReferenceSummary, Entities.CATEGORY);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with complex entity requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						referenceSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)),
							entityGroupFetch()
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint governs its reference entirely - only the attributes
						// it asks for itself reach it, the generic summary's `code` is not united into them
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE));
							}
							return entityFetch(attributeContent(ATTRIBUTE_CODE));
						})
						.groupEntityRequirementSupplier(referenceName -> referenceName.equals(Entities.CATEGORY)
							? entityGroupFetch()
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> {
						if (facetEntity.getType().equals(Entities.CATEGORY)) {
							return facetEntity.getAttributeNames().size() == 1 &&
								facetEntity.getAttribute(ATTRIBUTE_NAME) != null;
						}
						return facetEntity.getAttributeNames().size() == 1 &&
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null;
					},
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntityAttributeNames(actualReferenceSummary, Entities.CATEGORY, Set.of(ATTRIBUTE_NAME));

				return null;
			}
		);
	}

	@DisplayName("Should return products matching group EXCLUSIVE combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSetUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetGroupsExclusivity(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(1))),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final Predicate<SealedEntity> entityPredicate = sealedEntity -> sealedEntity
					.getReferences(Entities.PARAMETER)
					.stream()
					.filter(ref -> parameterIndex.contains(ref.getReferencedPrimaryKey()))
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.distinct()
					.count() == 2;

				assertResultIs(
					"Querying " + Entities.PRODUCT + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					entityPredicate,
					result.getRecordData()
				);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products using different default facet rules")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingDifferentDefaultFacetRulesUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetCalculationRules(
							FacetRelationType.CONJUNCTION,
							FacetRelationType.EXCLUSIVITY
						),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);

				return null;
			}
		);
	}

	// endregion

	/**
	 * Wraps a computed reference summary together with the count of entities matching the filter.
	 */
	private record ReferenceSummaryWithResultCount(int entityCount, ReferenceSummary referenceSummary) {
	}

	/**
	 * Helper wrapper that enables comparing reference summaries via their pretty-printed string representation.
	 */
	private record ReferenceSummaryToStringWrapper(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull Function<ReferenceGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.referenceSummary.prettyPrint(this.groupRenderer, this.facetRenderer);
		}

	}
}
