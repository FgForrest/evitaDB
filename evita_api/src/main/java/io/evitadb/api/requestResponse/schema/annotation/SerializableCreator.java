/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a constructor as the canonical creator for serialization and deserialization purposes.
 *
 * This annotation is used by the
 * {@link io.evitadb.externalApi.api.resolver.mutation.MutationConverter} framework to identify
 * which constructor should be used when converting mutation objects to and from their external
 * representations (JSON, gRPC, GraphQL, etc.). The annotated constructor's parameters define the
 * serialization contract - each parameter name must correspond to an accessible field or getter
 * method in the class.
 *
 * **Purpose and Usage**
 *
 * Schema mutation classes often provide multiple constructors for different use cases:
 * - Convenience constructors with simpler parameters (e.g., boolean flags instead of arrays)
 * - Backward-compatibility constructors with deprecated parameters
 * - Builder pattern constructors with optional parameters
 *
 * The `@SerializableCreator` annotation designates which constructor represents the complete,
 * canonical form that should be used for serialization. This constructor typically:
 * - Accepts the most detailed parameter types (e.g., `ScopedAttributeUniquenessType[]` instead
 *   of `AttributeUniquenessType`)
 * - Handles all scopes and configuration options explicitly
 * - Maps 1:1 with the external API schema definition
 *
 * **When to Use**
 *
 * Use `@SerializableCreator` when:
 * - The class has multiple public constructors and one should be preferred for serialization
 * - The class is a mutation, DTO, or data transfer object exposed via external APIs
 * - You need to control the serialization contract independently from convenience constructors
 *
 * **Fallback Behavior**
 *
 * If no constructor is annotated with `@SerializableCreator`, the converter will:
 * 1. Use the only public constructor if exactly one exists
 * 2. Throw an exception if multiple constructors exist (ambiguous case)
 *
 * **Example**
 *
 * ```java
 * public class SetAttributeSchemaUniqueMutation {
 *     // Convenience constructor for simple use cases
 *     public SetAttributeSchemaUniqueMutation(String name, AttributeUniquenessType unique) {
 *         this(name, new ScopedAttributeUniquenessType[]{
 *             new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, unique)
 *         });
 *     }
 *
 *     // Canonical constructor for serialization - handles all scopes
 *     {@literal @}SerializableCreator
 *     public SetAttributeSchemaUniqueMutation(String name,
 *                                             ScopedAttributeUniquenessType[] uniqueInScopes) {
 *         this.name = name;
 *         this.uniqueInScopes = uniqueInScopes;
 *     }
 * }
 * ```
 *
 * **Related Classes**
 *
 * - {@link io.evitadb.externalApi.api.resolver.mutation.MutationConverter} - uses this annotation
 *   to resolve constructors
 * - Schema mutation classes in packages:
 *   - `io.evitadb.api.requestResponse.schema.mutation.attribute`
 *   - `io.evitadb.api.requestResponse.schema.mutation.reference`
 *   - `io.evitadb.api.requestResponse.schema.mutation.catalog`
 *   - `io.evitadb.api.requestResponse.schema.mutation.entity`
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR})
public @interface SerializableCreator {
}
