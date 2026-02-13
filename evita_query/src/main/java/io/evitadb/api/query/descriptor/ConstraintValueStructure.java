/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.descriptor;

/**
 * Defines the high-level parameter structure of a constraint, indicating what kind of arguments it accepts. This
 * metadata enables external API builders (GraphQL, REST) to generate appropriate input types and validate arguments
 * at API schema generation time.
 *
 * **Purpose and Usage**
 *
 * Every constraint has a creator (constructor or factory method) with parameters. The structure of these parameters
 * determines how the constraint should be represented in external APIs:
 * - **Boolean flag**: Constraint with no parameters (enable/disable)
 * - **Single value**: Constraint with one primitive parameter
 * - **Range tuple**: Constraint with exactly two parameters (from, to)
 * - **Child list**: Constraint with child constraints only
 * - **Complex object**: Constraint with multiple named parameters or mixed value/child parameters
 *
 * **Automatic Derivation**
 *
 * The value structure is automatically derived by {@link ConstraintCreator} based on the creator's parameter list:
 * 1. No parameters → `{@link #NONE}`
 * 2. Single value parameter → `{@link #PRIMITIVE}`
 * 3. Exactly two value parameters named "from" and "to" → `{@link #RANGE}`
 * 4. Only child parameters → `{@link #CONTAINER}`
 * 5. Mixed parameters or multiple values → `{@link #COMPLEX}`
 *
 * **API Schema Generation**
 *
 * External API builders use this enum to:
 * - **GraphQL**: Generate appropriate input types (scalar, range object, list, complex object)
 * - **REST**: Determine parameter encoding (query param, path param, JSON body)
 * - **Documentation**: Show correct syntax examples in user guides
 *
 * **Example Mappings**
 *
 * - `entityLocaleEquals(Locale.ENGLISH)` → `{@link #PRIMITIVE}` (single locale value)
 * - `attributeBetween("price", 100, 200)` → `{@link #RANGE}` (from=100, to=200)
 * - `and(equals(...), between(...))` → `{@link #CONTAINER}` (list of child constraints)
 * - `referenceContent("brand", entityFetch(...), filterBy(...))` → `{@link #COMPLEX}` (name + children)
 *
 * **Related Classes**
 *
 * - `{@link ConstraintCreator}` - Derives value structure from creator parameters in `{@link #valueStructure()}`
 * method
 * - `{@link io.evitadb.api.query.descriptor.annotation.Value}` - Marks value parameters
 * - `{@link io.evitadb.api.query.descriptor.annotation.Child}` - Marks child constraint parameters
 * - `{@link io.evitadb.api.query.descriptor.annotation.Classifier}` - Marks name/identifier parameters (treated as
 * values)
 * - `{@link io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder}` - Uses value
 * structure to generate GraphQL/REST schemas
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public enum ConstraintValueStructure {

	/**
	 * Constraint has no parameters and can be represented as a boolean flag to enable/disable.
	 *
	 * **Detection Logic:**
	 * Creator has no value parameters and no child parameters.
	 *
	 * **Typical Constraints:**
	 * - `hierarchyWithinRoot()` - filters entities at hierarchy root
	 * - `entityPrimaryKeyNatural()` - orders by primary key
	 * - `priceContent()` - fetches all prices (no parameters)
	 *
	 * **API Representation:**
	 * - **GraphQL**: Boolean field (e.g., `priceContent: true`)
	 * - **REST**: Query parameter flag (e.g., `priceContent=true`)
	 * - **EvitaQL**: Constraint name with no arguments (e.g., `priceContent()`)
	 *
	 * **Note:**
	 * Some constraints may have optional parameters in other creator variants, resulting in different structures.
	 */
	NONE,
	/**
	 * Constraint has a single primitive value parameter (string, number, date, enum, etc.).
	 *
	 * **Detection Logic:**
	 * Creator has exactly one value parameter (including classifiers) and no child parameters.
	 *
	 * **Typical Constraints:**
	 * - `entityLocaleEquals(Locale.ENGLISH)` - single locale value
	 * - `entityPrimaryKeyInSet(1, 2, 3)` - single varargs array of primary keys
	 * - `attributeEquals("code", "abc")` - classifier + value (treated as two parameters, so might be COMPLEX)
	 *
	 * **API Representation:**
	 * - **GraphQL**: Scalar input field (e.g., `locale: "en_US"`)
	 * - **REST**: Query parameter or path segment (e.g., `locale=en_US`)
	 * - **EvitaQL**: Single argument (e.g., `entityLocaleEquals('en')`)
	 *
	 * **Note:**
	 * Classifiers (attribute names, reference names) count as value parameters for structure determination.
	 */
	PRIMITIVE,
	/**
	 * Constraint has exactly two value parameters named "from" and "to", representing a range.
	 *
	 * **Detection Logic:**
	 * Creator has exactly two value parameters with names matching `{@link ConstraintCreator#RANGE_FROM_VALUE_PARAMETER}`
	 * ("from") and `{@link ConstraintCreator#RANGE_TO_VALUE_PARAMETER}` ("to"), and no child parameters.
	 *
	 * **Typical Constraints:**
	 * - `priceBetween(100, 200)` - price range
	 * - `attributeBetween("priority", 1, 10)` - attribute range (includes classifier, so COMPLEX)
	 *
	 * **API Representation:**
	 * - **GraphQL**: Range input object (e.g., `{ from: 100, to: 200 }`)
	 * - **REST**: Separate query parameters (e.g., `priceFrom=100&priceTo=200`)
	 * - **EvitaQL**: Two arguments (e.g., `priceBetween(100, 200)`)
	 *
	 * **Special Case:**
	 * This structure is a special case that would otherwise be classified as `{@link #COMPLEX}`. The explicit range
	 * pattern enables better API ergonomics and validation.
	 */
	RANGE,
	/**
	 * Constraint has only child constraint parameters (no value parameters).
	 *
	 * **Detection Logic:**
	 * Creator has at least one child parameter and no value parameters (no classifiers, no primitive values).
	 *
	 * **Typical Constraints:**
	 * - `and(equals(...), between(...))` - logical combinator with child constraints
	 * - `filterBy(entityPrimaryKeyInSet(...), attributeEquals(...))` - container for filter constraints
	 * - `orderBy(attributeNatural("name"))` - container for order constraints
	 * - `entityFetch(attributeContent(), priceContent())` - container for requirements
	 *
	 * **API Representation:**
	 * - **GraphQL**: List of nested input objects (e.g., `and: [{ equals: {...} }, { between: {...} }]`)
	 * - **REST**: Array of constraint objects in JSON body
	 * - **EvitaQL**: Comma-separated child constraints (e.g., `and(equals(...), between(...))`)
	 *
	 * **Usage Pattern:**
	 * This is the most common structure for logical combinators and top-level containers.
	 */
	CONTAINER,
	/**
	 * Constraint has a mix of value and child parameters, or multiple value parameters (that don't form a range).
	 *
	 * **Detection Logic:**
	 * Creator has:
	 * - Both value and child parameters, OR
	 * - Multiple value parameters that don't match the range pattern (not exactly "from" and "to")
	 *
	 * **Typical Constraints:**
	 * - `attributeEquals("code", "abc")` - classifier (attribute name) + value
	 * - `attributeBetween("priority", 1, 10)` - classifier + from + to (three parameters)
	 * - `referenceContent("brand", entityFetch(...))` - classifier + child constraint
	 * - `hierarchyWithin("categories", 1, filterBy(...))` - classifier + value + child
	 *
	 * **API Representation:**
	 * - **GraphQL**: Complex input object with named fields (e.g., `{ attributeName: "code", value: "abc" }`)
	 * - **REST**: JSON object with multiple properties
	 * - **EvitaQL**: Positional arguments (e.g., `attributeEquals('code', 'abc')`)
	 *
	 * **Usage Pattern:**
	 * This is the most flexible structure, covering all constraints that don't fit the simpler patterns. API
	 * builders must examine the actual parameter list to generate appropriate input types.
	 */
	COMPLEX
}
