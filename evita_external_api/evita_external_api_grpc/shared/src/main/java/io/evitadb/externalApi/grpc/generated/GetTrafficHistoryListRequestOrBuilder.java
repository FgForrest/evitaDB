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
// source: GrpcEvitaTrafficRecordingAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GetTrafficHistoryListRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The limit of records to return
   * </pre>
   *
   * <code>int32 limit = 1;</code>
   * @return The limit.
   */
  int getLimit();

  /**
   * <pre>
   * The criteria of the traffic recording, allows to define constraints on the returned records
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingCaptureCriteria criteria = 2;</code>
   * @return Whether the criteria field is set.
   */
  boolean hasCriteria();
  /**
   * <pre>
   * The criteria of the traffic recording, allows to define constraints on the returned records
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingCaptureCriteria criteria = 2;</code>
   * @return The criteria.
   */
  io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingCaptureCriteria getCriteria();
  /**
   * <pre>
   * The criteria of the traffic recording, allows to define constraints on the returned records
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingCaptureCriteria criteria = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingCaptureCriteriaOrBuilder getCriteriaOrBuilder();
}
