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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GetMutationsHistoryPageRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The page number starting with 1
   * </pre>
   *
   * <code>int32 page = 1;</code>
   * @return The page.
   */
  int getPage();

  /**
   * <pre>
   * The size of the page to return
   * </pre>
   *
   * <code>int32 pageSize = 2;</code>
   * @return The pageSize.
   */
  int getPageSize();

  /**
   * <pre>
   * Starting point for the search (catalog version)
   * </pre>
   *
   * <code>int64 sinceVersion = 3;</code>
   * @return The sinceVersion.
   */
  long getSinceVersion();

  /**
   * <pre>
   * Starting point for the search (index of the mutation within catalog version)
   * </pre>
   *
   * <code>int32 sinceIndex = 4;</code>
   * @return The sinceIndex.
   */
  int getSinceIndex();

  /**
   * <pre>
   * The criteria of the capture, allows to define constraints on the returned mutations
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria criteria = 5;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria>
      getCriteriaList();
  /**
   * <pre>
   * The criteria of the capture, allows to define constraints on the returned mutations
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria criteria = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria getCriteria(int index);
  /**
   * <pre>
   * The criteria of the capture, allows to define constraints on the returned mutations
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria criteria = 5;</code>
   */
  int getCriteriaCount();
  /**
   * <pre>
   * The criteria of the capture, allows to define constraints on the returned mutations
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria criteria = 5;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteriaOrBuilder>
      getCriteriaOrBuilderList();
  /**
   * <pre>
   * The criteria of the capture, allows to define constraints on the returned mutations
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteria criteria = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureCriteriaOrBuilder getCriteriaOrBuilder(
      int index);

  /**
   * <pre>
   * The scope of the returned data - either header of the mutation, or the whole mutation
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureContent content = 6;</code>
   * @return The enum numeric value on the wire for content.
   */
  int getContentValue();
  /**
   * <pre>
   * The scope of the returned data - either header of the mutation, or the whole mutation
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureContent content = 6;</code>
   * @return The content.
   */
  io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureContent getContent();
}
