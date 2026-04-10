/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Concrete implementation of {@link HistogramExpressionTrigger} that extends
 * {@link AbstractExpressionIndexTrigger} with histogram-specific metadata: the histogram index name
 * and value resolution.
 *
 * Supports three trigger modes (inherited from the abstract base):
 *
 * - **Cross-entity with condition** -- condition expression is non-null, FilterBy is pre-translated
 * - **Local-only with condition** -- condition expression is non-null, no FilterBy
 * - **Unconditional** -- condition expression is null, `evaluate()` returns `true` unconditionally
 *
 * This class is immutable and thread-safe.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DefaultHistogramExpressionTrigger extends AbstractExpressionIndexTrigger
	implements HistogramExpressionTrigger {

	/**
	 * Name of the histogram index from {@link io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition}.
	 * Used as the key in the `histogramIndexes` map on `ReducedGroupEntityIndex` and `ReferencedTypeEntityIndex`
	 * to identify which histogram FilterIndex to write to.
	 */
	@Nonnull private final String histogramIndexName;
	/**
	 * Pre-built metadata describing how to locate the source attribute value for the bucketed histogram computation.
	 * Built at schema load time by {@link HistogramValueDescriptorFactory} — encapsulates the value source
	 * classification, source entity type, attribute name, array-type flag, and optional default value.
	 */
	@Nonnull private final HistogramValueDescriptor valueResolution;

	/**
	 * Creates a new histogram trigger for cross-entity evaluation with condition expression.
	 *
	 * @param ownerEntityType          entity type owning the reference
	 * @param referenceName            name of the reference carrying the expression
	 * @param scope                    scope this trigger applies to
	 * @param mutatedEntityType        entity type whose mutations fire this trigger
	 * @param dependencyType           cross-entity dependency classification
	 * @param dependentReferenceName   reference on the target entity whose attributes are read
	 * @param dependentAttributes      attribute names on the mutated entity
	 * @param localEntityAttributes    entity-level attribute names the expression reads locally
	 * @param localReferenceAttributes reference-level attribute names the expression reads locally
	 * @param localAssociatedData      associated data names the expression reads locally
	 * @param usesParent               whether the expression reads the entity's parent
	 * @param expression               the parsed condition expression AST
	 * @param proxyDescriptor          pre-built proxy descriptor from {@link ExpressionProxyFactory}
	 * @param filterByConstraint       pre-translated FilterBy from the condition expression
	 * @param histogramIndexName       the name identifying the histogram index
	 * @param valueResolution          pre-built value resolution metadata
	 */
	public DefaultHistogramExpressionTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nullable String dependentReferenceName,
		@Nonnull Set<String> dependentAttributes,
		@Nonnull Set<String> localEntityAttributes,
		@Nonnull Set<String> localReferenceAttributes,
		@Nonnull Set<String> localAssociatedData,
		boolean usesParent,
		@Nullable Expression expression,
		@Nullable ExpressionProxyDescriptor proxyDescriptor,
		@Nonnull FilterBy filterByConstraint,
		@Nonnull String histogramIndexName,
		@Nonnull HistogramValueDescriptor valueResolution
	) {
		super(
			ownerEntityType, referenceName, scope,
			mutatedEntityType, dependencyType, dependentReferenceName,
			dependentAttributes,
			localEntityAttributes, localReferenceAttributes, localAssociatedData,
			usesParent, expression, proxyDescriptor, filterByConstraint
		);
		this.histogramIndexName = histogramIndexName;
		this.valueResolution = valueResolution;
	}

	/**
	 * Creates a new histogram trigger for local-only evaluation (no cross-entity dependency).
	 *
	 * @param ownerEntityType          entity type owning the reference
	 * @param referenceName            name of the reference carrying the expression
	 * @param scope                    scope this trigger applies to
	 * @param localEntityAttributes    entity-level attribute names the expression reads locally
	 * @param localReferenceAttributes reference-level attribute names the expression reads locally
	 * @param localAssociatedData      associated data names the expression reads locally
	 * @param usesParent               whether the expression reads the entity's parent
	 * @param expression               the parsed condition expression AST
	 * @param proxyDescriptor          pre-built proxy descriptor
	 * @param histogramIndexName       the name identifying the histogram index
	 * @param valueResolution          pre-built value resolution metadata
	 */
	public DefaultHistogramExpressionTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Set<String> localEntityAttributes,
		@Nonnull Set<String> localReferenceAttributes,
		@Nonnull Set<String> localAssociatedData,
		boolean usesParent,
		@Nullable Expression expression,
		@Nullable ExpressionProxyDescriptor proxyDescriptor,
		@Nonnull String histogramIndexName,
		@Nonnull HistogramValueDescriptor valueResolution
	) {
		super(
			ownerEntityType, referenceName, scope,
			localEntityAttributes, localReferenceAttributes, localAssociatedData,
			usesParent, expression, proxyDescriptor
		);
		this.histogramIndexName = histogramIndexName;
		this.valueResolution = valueResolution;
	}

	/**
	 * Creates a new unconditional histogram trigger for cross-entity evaluation. Used when
	 * `bucketedPartially` is null for a scope -- only value-dependency triggers are created.
	 *
	 * @param ownerEntityType        entity type owning the reference
	 * @param referenceName          name of the reference
	 * @param scope                  scope this trigger applies to
	 * @param mutatedEntityType      entity type whose mutations fire this trigger
	 * @param dependencyType         cross-entity dependency classification
	 * @param dependentReferenceName reference on the target entity whose attributes are read
	 * @param dependentAttributes    attribute names on the mutated entity
	 * @param histogramIndexName     the name identifying the histogram index
	 * @param valueResolution        pre-built value resolution metadata
	 */
	public DefaultHistogramExpressionTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nullable String dependentReferenceName,
		@Nonnull Set<String> dependentAttributes,
		@Nonnull String histogramIndexName,
		@Nonnull HistogramValueDescriptor valueResolution
	) {
		super(
			ownerEntityType, referenceName, scope,
			mutatedEntityType, dependencyType, dependentReferenceName,
			dependentAttributes
		);
		this.histogramIndexName = histogramIndexName;
		this.valueResolution = valueResolution;
	}

	/**
	 * Creates a new unconditional histogram trigger for local-only evaluation. Used when
	 * `bucketedPartially` is null for a scope and no cross-entity dependencies exist.
	 *
	 * @param ownerEntityType    entity type owning the reference
	 * @param referenceName      name of the reference
	 * @param scope              scope this trigger applies to
	 * @param histogramIndexName the name identifying the histogram index
	 * @param valueResolution    pre-built value resolution metadata
	 */
	public DefaultHistogramExpressionTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String histogramIndexName,
		@Nonnull HistogramValueDescriptor valueResolution
	) {
		super(
			ownerEntityType, referenceName, scope,
			Set.of(), Set.of(), Set.of(),
			false, null, null
		);
		this.histogramIndexName = histogramIndexName;
		this.valueResolution = valueResolution;
	}

	@Nonnull
	@Override
	public String getHistogramIndexName() {
		return this.histogramIndexName;
	}

	@Nonnull
	@Override
	public HistogramValueDescriptor getValueDescriptor() {
		return this.valueResolution;
	}

}
