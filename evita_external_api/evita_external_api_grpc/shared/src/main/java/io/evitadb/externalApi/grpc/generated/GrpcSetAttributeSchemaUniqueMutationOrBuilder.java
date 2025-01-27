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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcAttributeSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcSetAttributeSchemaUniqueMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaUniqueMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the attribute the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the attribute the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * deprecated in favor of `uniqueInScopes`
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeUniquenessType unique = 2 [deprecated = true];</code>
   * @deprecated
   * @return The enum numeric value on the wire for unique.
   */
  @java.lang.Deprecated int getUniqueValue();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * deprecated in favor of `uniqueInScopes`
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeUniquenessType unique = 2 [deprecated = true];</code>
   * @deprecated
   * @return The unique.
   */
  @java.lang.Deprecated io.evitadb.externalApi.grpc.generated.GrpcAttributeUniquenessType getUnique();

  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 13;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType>
      getUniqueInScopesList();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 13;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType getUniqueInScopes(int index);
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 13;</code>
   */
  int getUniqueInScopesCount();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 13;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessTypeOrBuilder>
      getUniqueInScopesOrBuilderList();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 13;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessTypeOrBuilder getUniqueInScopesOrBuilder(
      int index);
}
