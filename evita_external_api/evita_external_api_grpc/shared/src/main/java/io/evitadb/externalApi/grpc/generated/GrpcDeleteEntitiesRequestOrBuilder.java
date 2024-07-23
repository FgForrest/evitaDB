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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcDeleteEntitiesRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The string part of the parametrised query.
   * </pre>
   *
   * <code>string query = 1;</code>
   * @return The query.
   */
  java.lang.String getQuery();
  /**
   * <pre>
   * The string part of the parametrised query.
   * </pre>
   *
   * <code>string query = 1;</code>
   * @return The bytes for query.
   */
  com.google.protobuf.ByteString
      getQueryBytes();

  /**
   * <pre>
   * The positional query parameters.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryParam positionalQueryParams = 2;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcQueryParam>
      getPositionalQueryParamsList();
  /**
   * <pre>
   * The positional query parameters.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryParam positionalQueryParams = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryParam getPositionalQueryParams(int index);
  /**
   * <pre>
   * The positional query parameters.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryParam positionalQueryParams = 2;</code>
   */
  int getPositionalQueryParamsCount();
  /**
   * <pre>
   * The positional query parameters.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryParam positionalQueryParams = 2;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcQueryParamOrBuilder>
      getPositionalQueryParamsOrBuilderList();
  /**
   * <pre>
   * The positional query parameters.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryParam positionalQueryParams = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryParamOrBuilder getPositionalQueryParamsOrBuilder(
      int index);

  /**
   * <pre>
   * The named query parameters.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcQueryParam&gt; namedQueryParams = 3;</code>
   */
  int getNamedQueryParamsCount();
  /**
   * <pre>
   * The named query parameters.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcQueryParam&gt; namedQueryParams = 3;</code>
   */
  boolean containsNamedQueryParams(
      java.lang.String key);
  /**
   * Use {@link #getNamedQueryParamsMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcQueryParam>
  getNamedQueryParams();
  /**
   * <pre>
   * The named query parameters.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcQueryParam&gt; namedQueryParams = 3;</code>
   */
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcQueryParam>
  getNamedQueryParamsMap();
  /**
   * <pre>
   * The named query parameters.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcQueryParam&gt; namedQueryParams = 3;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcQueryParam getNamedQueryParamsOrDefault(
      java.lang.String key,
      io.evitadb.externalApi.grpc.generated.GrpcQueryParam defaultValue);
  /**
   * <pre>
   * The named query parameters.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcQueryParam&gt; namedQueryParams = 3;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcQueryParam getNamedQueryParamsOrThrow(
      java.lang.String key);
}
