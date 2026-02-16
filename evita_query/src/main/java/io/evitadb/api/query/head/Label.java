/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.query.head;


import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `label` constraint allows attaching a key-value metadata pair to a query. Labels serve as custom tags
 * that follow the query through the execution pipeline and are persisted in various diagnostic outputs.
 *
 * A query can be tagged with multiple labels by including multiple `label` constraints in the query header.
 * Each label consists of a {@link String} name (key) and a {@link Comparable} value (which must be one of
 * the evitaDB-supported data types).
 *
 * **Label Destinations:**
 * - **Execution traces**: Labels are appended to OpenTelemetry execution traces generated for the query,
 *   enabling correlation of traces with business context (page URLs, feature names, user actions)
 * - **Traffic recordings**: Labels are recorded with the query in traffic traces and can be used to look up
 *   and filter queries during traffic inspection or traffic replay for analysis and debugging
 *
 * Labels are particularly useful for identifying the origin or purpose of a query when analyzing performance
 * metrics, debugging production issues, or replaying recorded traffic for testing.
 *
 * Example:
 *
 * ```evitaql
 * query(
 *    head(
 *       collection('Product'),
 *       label('query-name', 'List all products'),
 *       label('page-url', '/products'),
 *       label('user-action', 'browse-catalog')
 *    )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/header/header#label)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@ConstraintDefinition(
	name = "label",
	shortDescription = "Constraint allows to pass a key-value pair to the query. The label will be attached to recorded query and can be used for filtering in traffic inspection.",
	userDocsLink = "/documentation/query/header/header#label"
)
public class Label extends AbstractHeadConstraintLeaf implements GenericConstraint<HeadConstraint> {
	@Serial private static final long serialVersionUID = -7618002411866828589L;
	/**
	 * Reusable empty array constant to avoid repeated allocations.
	 */
	public static final Label[] EMPTY_ARRAY = new Label[0];
	/**
	 * Allows to identify a root query that is a source for this one.
	 */
	public static final String LABEL_SOURCE_QUERY = "source-query";
	/**
	 * Allows to define type of the source query that is a root query for this one.
	 */
	public static final String LABEL_SOURCE_TYPE = "source-type";

	public Label(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Comparable<T> & Serializable> Label(@Nullable String name, @Nullable T value) {
		super(new Serializable[]{name, value});
		Assert.isTrue(
			value == null || EvitaDataTypes.isSupportedType(value.getClass()),
			"Unsupported type of label value: " + (value == null ? "N/A" : value.getClass().getName())
		);
	}

	/**
	 * Returns the name of the label.
	 */
	@AliasForParameter("name")
	@Nonnull
	public String getLabelName() {
		final Serializable[] args = getArguments();
		Assert.isTrue(args.length > 0, "Label name is missing");
		return (String) args[0];
	}

	/**
	 * Returns the value of the label.
	 */
	@AliasForParameter("value")
	@Nonnull
	public Serializable getLabelValue() {
		final Serializable[] args = getArguments();
		Assert.isTrue(args.length > 1, "Label value is missing");
		return args[1];
	}

	/**
	 * Returns `true` if the label has exactly two arguments: a {@link String} name and a {@link Comparable} value.
	 */
	@Override
	public boolean isApplicable() {
		final Serializable[] arguments = getArguments();
		return arguments.length == 2 && arguments[0] instanceof String && arguments[1] instanceof Comparable<?>;
	}

	@Nonnull
	@Override
	public HeadConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Label(newArguments);
	}

}