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

package io.evitadb.api.query.descriptor.annotation;

import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Marks a creator parameter as the classifier that identifies the target of the constraint (attribute name, reference
 * name, entity type, etc.). The classifier determines what data the constraint operates on.
 *
 * **Purpose**
 *
 * Classifiers are used throughout evitaDB constraints to specify:
 * - Which attribute to filter/sort by (e.g., `attributeEquals("code", "PHONE")` - `"code"` is the classifier)
 * - Which reference to navigate (e.g., `referenceContent("brand")` - `"brand"` is the classifier)
 * - Which entity type to query (e.g., `entityFetch("product")` - `"product"` is the classifier)
 *
 * The classifier is typically the first parameter in a constraint and must be a non-nullable, non-array value.
 *
 * **Parameter Requirements**
 *
 * A parameter annotated with `@Classifier` must:
 * - Have a type supported by evitaDB as a classifier (currently `String`)
 * - NOT be nullable (must not have `@Nullable` annotation)
 * - NOT be an array type
 * - Be unique within the creator (only one classifier parameter per creator)
 *
 * **Mutual Exclusivity with Implicit Classifiers**
 *
 * A creator can have only one classifier mechanism:
 * - Either a `@Classifier` parameter
 * - OR `{@link Creator#silentImplicitClassifier()} = true`
 * - OR `{@link Creator#implicitClassifier()}` with a non-empty value
 *
 * If a classifier cannot be parameterized (e.g., it's always a fixed value or determined by context), use one of the
 * implicit classifier options on the `@Creator` annotation instead.
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(name = "equals", ...)
 * public class AttributeEquals extends ... {
 *
 * @Creator
 * public AttributeEquals(@Classifier String attributeName, @Value Serializable attributeValue) {
 * super(attributeName, attributeValue);
 * }
 * }
 * ```
 *
 * In this example, `attributeName` is the classifier that identifies which attribute to compare. When the constraint
 * is used in a query like `attributeEquals("code", "ABC")`, the string `"code"` is the classifier value.
 *
 * **Processing**
 *
 * During startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` validates classifier parameters and
 * creates a `{@link io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor}` for runtime
 * use.
 *
 * External API builders (GraphQL, REST) use the classifier information to:
 * - Generate schema-specific query fields (e.g., separate fields for each attribute name)
 * - Validate that referenced classifiers (attribute names, reference names) exist in the entity schema
 * - Build constraint keys in the format `{propertyType}{classifier}{fullName}` for non-generic constraints
 *
 * **Related Annotations**
 *
 * - `{@link Value}` - for primitive/serializable value parameters
 * - `{@link Child}` - for nested constraint parameters of the same type
 * - `{@link AdditionalChild}` - for nested constraint parameters of a different type
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @see Creator
 * @see ConstraintDefinition
 * @see Value
 * @see Child
 * @see AdditionalChild
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Classifier {
}
