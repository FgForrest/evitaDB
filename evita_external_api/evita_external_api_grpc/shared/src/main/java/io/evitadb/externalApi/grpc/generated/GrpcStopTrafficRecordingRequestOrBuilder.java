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
// source: GrpcEvitaTrafficRecordingAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcStopTrafficRecordingRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The ID of the task that started the recording
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid taskStatusId = 1;</code>
   * @return Whether the taskStatusId field is set.
   */
  boolean hasTaskStatusId();
  /**
   * <pre>
   * The ID of the task that started the recording
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid taskStatusId = 1;</code>
   * @return The taskStatusId.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getTaskStatusId();
  /**
   * <pre>
   * The ID of the task that started the recording
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid taskStatusId = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getTaskStatusIdOrBuilder();
}
