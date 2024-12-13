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
// source: GrpcTrafficRecording.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcTrafficSourceQueryContainerOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The unique identifier of the source query
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return Whether the sourceQueryId field is set.
   */
  boolean hasSourceQueryId();
  /**
   * <pre>
   * The unique identifier of the source query
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return The sourceQueryId.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getSourceQueryId();
  /**
   * <pre>
   * The unique identifier of the source query
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSourceQueryIdOrBuilder();

  /**
   * <pre>
   * unparsed, raw source query in particular format
   * </pre>
   *
   * <code>string sourceQuery = 2;</code>
   * @return The sourceQuery.
   */
  java.lang.String getSourceQuery();
  /**
   * <pre>
   * unparsed, raw source query in particular format
   * </pre>
   *
   * <code>string sourceQuery = 2;</code>
   * @return The bytes for sourceQuery.
   */
  com.google.protobuf.ByteString
      getSourceQueryBytes();

  /**
   * <pre>
   * type of the query (e.g. GraphQL, REST, etc.)
   * </pre>
   *
   * <code>string queryType = 3;</code>
   * @return The queryType.
   */
  java.lang.String getQueryType();
  /**
   * <pre>
   * type of the query (e.g. GraphQL, REST, etc.)
   * </pre>
   *
   * <code>string queryType = 3;</code>
   * @return The bytes for queryType.
   */
  com.google.protobuf.ByteString
      getQueryTypeBytes();
}
