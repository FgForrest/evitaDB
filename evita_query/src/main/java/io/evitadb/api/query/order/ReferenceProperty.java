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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sorting by reference attribute is not as common as sorting by entity attributes, but it allows you to sort entities
 * that are in a particular category or have a particular brand specifically by the priority/order for that particular
 * relationship.
 *
 * To sort products related to a "Sony" brand by the `priority` attribute set on the reference, you need to use the
 * following constraint:
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "brand",
 *             entityHaving(
 *                 attributeEquals("code","sony")
 *             )
 *         )
 *     ),
 *     orderBy(
 *         referenceProperty(
 *             "brand",
 *             attributeNatural("orderInBrand", ASC)
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code"),
 *             referenceContentWithAttributes(
 *                 "brand",
 *                 attributeContent("orderInBrand")
 *             )
 *         )
 *     )
 * )
 * </pre>
 *
 * **The `referenceProperty` is implicit in requirement `referenceContent`**
 *
 * In the `orderBy` clause within the {@link io.evitadb.api.query.require.ReferenceContent} requirement,
 * the `referenceProperty` constraint is implicit and must not be repeated. All attribute order constraints
 * in `referenceContent` automatically refer to the reference attributes, unless the {@link EntityProperty} or
 * {@link EntityGroupProperty} container is used there.
 *
 * The example is based on a simple one-to-zero-or-one reference (a product can have at most one reference to a brand
 * entity). The response will only return the products that have a reference to the "Sony" brand, all of which contain the
 * `orderInBrand` attribute (since it's marked as a non-nullable attribute). Because the example is so simple, the returned
 * result can be anticipated.
 *
 * ## Behaviour of zero or one to many references ordering
 *
 * The situation is more complicated when the reference is one-to-many. What is the expected result of a query that
 * involves ordering by a property on a reference attribute? Is it wise to allow such ordering query in this case?
 *
 * We decided to allow it and bind it with the following rules:
 *
 * ### Non-hierarchical entity
 *
 * If the referenced entity is **non-hierarchical**, and the returned entity references multiple entities, only
 * the reference with the lowest primary key of the referenced entity, while also having the order property set, will be
 * used for ordering.
 *
 * ### Hierarchical entity
 *
 * If the referenced entity is **hierarchical** and the returned entity references multiple entities, the reference used
 * for ordering is the one that contains the order property and is the closest hierarchy node to the root of the filtered
 * hierarchy node.
 *
 * It sounds complicated, but it's really quite simple. If you list products of a certain category and at the same time
 * order them by a property "priority" set on the reference to the category, the first products will be those directly
 * related to the category, ordered by "priority", followed by the products of the first child category, and so on,
 * maintaining the depth-first order of the category tree.
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/reference#reference-property">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "property",
	shortDescription = "The constraint sorts returned entities or references by attribute specified on its reference in natural order.",
	userDocsLink = "/documentation/query/ordering/reference#reference-property",
	supportedIn = {ConstraintDomain.ENTITY}
)
public class ReferenceProperty extends AbstractOrderConstraintContainer implements ReferenceConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -8564699361608364992L;

	private ReferenceProperty(Serializable[] arguments, OrderConstraint... children) {
		super(arguments, children);
	}

	@Creator
	public ReferenceProperty(
		@Nonnull @Classifier String referenceName,
		@Nonnull @Child OrderConstraint... orderBy
	) {
		super(new Serializable[]{referenceName}, orderBy);
	}

	/**
	 * Returns the array of {@link OrderConstraint} elements used to define ordering constraints.
	 *
	 * @return an array of {@link OrderConstraint} elements.
	 */
	@Nonnull
	public OrderConstraint[] getOrderBy() {
		return super.getChildren();
	}

	/**
	 * Returns reference name of the facet that should be used for applying for ordering according to children constraints.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns either the {@link TraverseByEntityProperty} or {@link PickFirstByEntityProperty} constraint that is used
	 * to define sorting order for 1:N references.
	 *
	 * @return the {@link TraverseByEntityProperty} or {@link PickFirstByEntityProperty} constraint that is used
	 * to define sorting order for 1:N references.
	 */
	@Nonnull
	public Optional<ReferenceOrderingSpecification> getReferenceOrderingSpecification() {
		return Arrays.stream(getChildren())
			.filter(ReferenceOrderingSpecification.class::isInstance)
			.map(ReferenceOrderingSpecification.class::cast)
			.reduce((spec1, spec2) -> {
				throw new EvitaInvalidUsageException(
					"Duplicate one to many ordering specification found: " + spec1 + " and " + spec2 +
						". These definitions are mutually exclusive and cannot be used together."
				);
			});
	}

	/**
	 * Returns the list of {@link OrderConstraint} constraints that are used to order the entities or references
	 * by the specified reference.
	 *
	 * @return the list of {@link OrderConstraint} constraints.
	 */
	@Nonnull
	public List<OrderConstraint> getOrderConstraints() {
		return Arrays.stream(getChildren())
			.filter(it -> !(it instanceof TraverseByEntityProperty))
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceProperty(newArguments, getChildren());
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length >= 1;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new ReferenceProperty(getReferenceName(), children);
	}
}
