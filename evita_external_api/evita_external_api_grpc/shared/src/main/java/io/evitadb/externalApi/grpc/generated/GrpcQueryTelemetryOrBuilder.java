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
// source: GrpcExtraResults.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcQueryTelemetryOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Phase of the query processing.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPhase operation = 1;</code>
   * @return The enum numeric value on the wire for operation.
   */
  int getOperationValue();
  /**
   * <pre>
   * Phase of the query processing.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPhase operation = 1;</code>
   * @return The operation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryPhase getOperation();

  /**
   * <pre>
   * Date and time of the start of this step in nanoseconds.
   * </pre>
   *
   * <code>int64 start = 2;</code>
   * @return The start.
   */
  long getStart();

  /**
   * <pre>
   * Internal steps of this telemetry step (operation decomposition).
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry steps = 3;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry>
      getStepsList();
  /**
   * <pre>
   * Internal steps of this telemetry step (operation decomposition).
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry steps = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry getSteps(int index);
  /**
   * <pre>
   * Internal steps of this telemetry step (operation decomposition).
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry steps = 3;</code>
   */
  int getStepsCount();
  /**
   * <pre>
   * Internal steps of this telemetry step (operation decomposition).
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry steps = 3;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetryOrBuilder>
      getStepsOrBuilderList();
  /**
   * <pre>
   * Internal steps of this telemetry step (operation decomposition).
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry steps = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetryOrBuilder getStepsOrBuilder(
      int index);

  /**
   * <pre>
   * Arguments of the processing phase.
   * </pre>
   *
   * <code>repeated string arguments = 4;</code>
   * @return A list containing the arguments.
   */
  java.util.List<java.lang.String>
      getArgumentsList();
  /**
   * <pre>
   * Arguments of the processing phase.
   * </pre>
   *
   * <code>repeated string arguments = 4;</code>
   * @return The count of arguments.
   */
  int getArgumentsCount();
  /**
   * <pre>
   * Arguments of the processing phase.
   * </pre>
   *
   * <code>repeated string arguments = 4;</code>
   * @param index The index of the element to return.
   * @return The arguments at the given index.
   */
  java.lang.String getArguments(int index);
  /**
   * <pre>
   * Arguments of the processing phase.
   * </pre>
   *
   * <code>repeated string arguments = 4;</code>
   * @param index The index of the value to return.
   * @return The bytes of the arguments at the given index.
   */
  com.google.protobuf.ByteString
      getArgumentsBytes(int index);

  /**
   * <pre>
   * Duration in nanoseconds.
   * </pre>
   *
   * <code>int64 spentTime = 5;</code>
   * @return The spentTime.
   */
  long getSpentTime();
}
