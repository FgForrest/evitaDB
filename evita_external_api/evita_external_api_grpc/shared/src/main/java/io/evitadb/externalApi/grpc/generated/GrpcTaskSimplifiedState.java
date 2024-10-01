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
// source: GrpcEnums.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * State aggregates the possible states of a task into a simple enumeration.
 * </pre>
 *
 * Protobuf enum {@code io.evitadb.externalApi.grpc.generated.GrpcTaskSimplifiedState}
 */
public enum GrpcTaskSimplifiedState
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <pre>
   **
   * Task is waiting in the queue to be executed.
   * </pre>
   *
   * <code>TASK_QUEUED = 0;</code>
   */
  TASK_QUEUED(0),
  /**
   * <pre>
   **
   * Task is currently running.
   * </pre>
   *
   * <code>TASK_RUNNING = 1;</code>
   */
  TASK_RUNNING(1),
  /**
   * <pre>
   **
   * Task has finished successfully.
   * </pre>
   *
   * <code>TASK_FINISHED = 2;</code>
   */
  TASK_FINISHED(2),
  /**
   * <pre>
   **
   * Task has failed.
   * </pre>
   *
   * <code>TASK_FAILED = 3;</code>
   */
  TASK_FAILED(3),
  UNRECOGNIZED(-1),
  ;

  /**
   * <pre>
   **
   * Task is waiting in the queue to be executed.
   * </pre>
   *
   * <code>TASK_QUEUED = 0;</code>
   */
  public static final int TASK_QUEUED_VALUE = 0;
  /**
   * <pre>
   **
   * Task is currently running.
   * </pre>
   *
   * <code>TASK_RUNNING = 1;</code>
   */
  public static final int TASK_RUNNING_VALUE = 1;
  /**
   * <pre>
   **
   * Task has finished successfully.
   * </pre>
   *
   * <code>TASK_FINISHED = 2;</code>
   */
  public static final int TASK_FINISHED_VALUE = 2;
  /**
   * <pre>
   **
   * Task has failed.
   * </pre>
   *
   * <code>TASK_FAILED = 3;</code>
   */
  public static final int TASK_FAILED_VALUE = 3;


  public final int getNumber() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalArgumentException(
          "Can't get the number of an unknown enum value.");
    }
    return value;
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   * @deprecated Use {@link #forNumber(int)} instead.
   */
  @java.lang.Deprecated
  public static GrpcTaskSimplifiedState valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static GrpcTaskSimplifiedState forNumber(int value) {
    switch (value) {
      case 0: return TASK_QUEUED;
      case 1: return TASK_RUNNING;
      case 2: return TASK_FINISHED;
      case 3: return TASK_FAILED;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<GrpcTaskSimplifiedState>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      GrpcTaskSimplifiedState> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<GrpcTaskSimplifiedState>() {
          public GrpcTaskSimplifiedState findValueByNumber(int number) {
            return GrpcTaskSimplifiedState.forNumber(number);
          }
        };

  public final com.google.protobuf.Descriptors.EnumValueDescriptor
      getValueDescriptor() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalStateException(
          "Can't get the descriptor of an unrecognized enum value.");
    }
    return getDescriptor().getValues().get(ordinal());
  }
  public final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptorForType() {
    return getDescriptor();
  }
  public static final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor().getEnumTypes().get(27);
  }

  private static final GrpcTaskSimplifiedState[] VALUES = values();

  public static GrpcTaskSimplifiedState valueOf(
      com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
    if (desc.getType() != getDescriptor()) {
      throw new java.lang.IllegalArgumentException(
        "EnumValueDescriptor is not for this type.");
    }
    if (desc.getIndex() == -1) {
      return UNRECOGNIZED;
    }
    return VALUES[desc.getIndex()];
  }

  private final int value;

  private GrpcTaskSimplifiedState(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:io.evitadb.externalApi.grpc.generated.GrpcTaskSimplifiedState)
}

