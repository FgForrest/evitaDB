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
// source: GrpcCatalogSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcModifyEntitySchemaMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcModifyEntitySchemaMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Entity type of entity schema that will be affected by passed mutations.
   * </pre>
   *
   * <code>string entityType = 1;</code>
   * @return The entityType.
   */
  java.lang.String getEntityType();
  /**
   * <pre>
   * Entity type of entity schema that will be affected by passed mutations.
   * </pre>
   *
   * <code>string entityType = 1;</code>
   * @return The bytes for entityType.
   */
  com.google.protobuf.ByteString
      getEntityTypeBytes();

  /**
   * <pre>
   * Collection of mutations that should be applied on current version of the schema.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutations = 2;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation> 
      getEntitySchemaMutationsList();
  /**
   * <pre>
   * Collection of mutations that should be applied on current version of the schema.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutations = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation getEntitySchemaMutations(int index);
  /**
   * <pre>
   * Collection of mutations that should be applied on current version of the schema.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutations = 2;</code>
   */
  int getEntitySchemaMutationsCount();
  /**
   * <pre>
   * Collection of mutations that should be applied on current version of the schema.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutations = 2;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutationOrBuilder> 
      getEntitySchemaMutationsOrBuilderList();
  /**
   * <pre>
   * Collection of mutations that should be applied on current version of the schema.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutations = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutationOrBuilder getEntitySchemaMutationsOrBuilder(
      int index);
}
