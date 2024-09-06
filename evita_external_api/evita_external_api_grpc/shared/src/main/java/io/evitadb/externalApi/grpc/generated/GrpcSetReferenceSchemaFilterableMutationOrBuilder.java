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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcReferenceSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcSetReferenceSchemaFilterableMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFilterableMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * Whether the index for this reference should be created and maintained allowing to filter by
   * `referenceHaving` filtering constraints. Index is also required when reference is `faceted`.
   * Do not mark reference as faceted unless you know that you'll need to filter / sort entities by this reference.
   * Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
   * the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded
   * alongside other references if requested.
   * </pre>
   *
   * <code>bool filterable = 2;</code>
   * @return The filterable.
   */
  boolean getFilterable();

  /**
   * <pre>
   * Set to true when the filterable property should be inherited from the original.
   * This property makes sense only for inherited reference attributes on reflected reference. For all other cases it
   * must be left as false. When set to TRUE the value of `filterable` field is ignored.
   * </pre>
   *
   * <code>bool inherited = 3;</code>
   * @return The inherited.
   */
  boolean getInherited();
}
