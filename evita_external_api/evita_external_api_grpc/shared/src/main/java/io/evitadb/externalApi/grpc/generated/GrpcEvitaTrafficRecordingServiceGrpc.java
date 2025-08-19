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

package io.evitadb.externalApi.grpc.generated;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class GrpcEvitaTrafficRecordingServiceGrpc {

  private GrpcEvitaTrafficRecordingServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getGetTrafficRecordingHistoryListMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTrafficRecordingHistoryList",
      requestType = io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getGetTrafficRecordingHistoryListMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getGetTrafficRecordingHistoryListMethod;
    if ((getGetTrafficRecordingHistoryListMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryListMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getGetTrafficRecordingHistoryListMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryListMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryListMethod = getGetTrafficRecordingHistoryListMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTrafficRecordingHistoryList"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("GetTrafficRecordingHistoryList"))
              .build();
        }
      }
    }
    return getGetTrafficRecordingHistoryListMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getGetTrafficRecordingHistoryListReversedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTrafficRecordingHistoryListReversed",
      requestType = io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getGetTrafficRecordingHistoryListReversedMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getGetTrafficRecordingHistoryListReversedMethod;
    if ((getGetTrafficRecordingHistoryListReversedMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryListReversedMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getGetTrafficRecordingHistoryListReversedMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryListReversedMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryListReversedMethod = getGetTrafficRecordingHistoryListReversedMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTrafficRecordingHistoryListReversed"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("GetTrafficRecordingHistoryListReversed"))
              .build();
        }
      }
    }
    return getGetTrafficRecordingHistoryListReversedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse> getGetTrafficRecordingHistoryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTrafficRecordingHistory",
      requestType = io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse> getGetTrafficRecordingHistoryMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse> getGetTrafficRecordingHistoryMethod;
    if ((getGetTrafficRecordingHistoryMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getGetTrafficRecordingHistoryMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingHistoryMethod = getGetTrafficRecordingHistoryMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTrafficRecordingHistory"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("GetTrafficRecordingHistory"))
              .build();
        }
      }
    }
    return getGetTrafficRecordingHistoryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse> getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTrafficRecordingLabelsNamesOrderedByCardinality",
      requestType = io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse> getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse> getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod;
    if ((getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod = getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTrafficRecordingLabelsNamesOrderedByCardinality"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("GetTrafficRecordingLabelsNamesOrderedByCardinality"))
              .build();
        }
      }
    }
    return getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse> getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTrafficRecordingLabelValuesOrderedByCardinality",
      requestType = io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse> getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse> getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod;
    if ((getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod = GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod = getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTrafficRecordingLabelValuesOrderedByCardinality"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("GetTrafficRecordingLabelValuesOrderedByCardinality"))
              .build();
        }
      }
    }
    return getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> getStartTrafficRecordingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StartTrafficRecording",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> getStartTrafficRecordingMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> getStartTrafficRecordingMethod;
    if ((getStartTrafficRecordingMethod = GrpcEvitaTrafficRecordingServiceGrpc.getStartTrafficRecordingMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getStartTrafficRecordingMethod = GrpcEvitaTrafficRecordingServiceGrpc.getStartTrafficRecordingMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getStartTrafficRecordingMethod = getStartTrafficRecordingMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StartTrafficRecording"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("StartTrafficRecording"))
              .build();
        }
      }
    }
    return getStartTrafficRecordingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> getStopTrafficRecordingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StopTrafficRecording",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest,
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> getStopTrafficRecordingMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> getStopTrafficRecordingMethod;
    if ((getStopTrafficRecordingMethod = GrpcEvitaTrafficRecordingServiceGrpc.getStopTrafficRecordingMethod) == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        if ((getStopTrafficRecordingMethod = GrpcEvitaTrafficRecordingServiceGrpc.getStopTrafficRecordingMethod) == null) {
          GrpcEvitaTrafficRecordingServiceGrpc.getStopTrafficRecordingMethod = getStopTrafficRecordingMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StopTrafficRecording"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier("StopTrafficRecording"))
              .build();
        }
      }
    }
    return getStopTrafficRecordingMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static GrpcEvitaTrafficRecordingServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceStub>() {
        @java.lang.Override
        public GrpcEvitaTrafficRecordingServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcEvitaTrafficRecordingServiceStub(channel, callOptions);
        }
      };
    return GrpcEvitaTrafficRecordingServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static GrpcEvitaTrafficRecordingServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceBlockingV2Stub>() {
        @java.lang.Override
        public GrpcEvitaTrafficRecordingServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcEvitaTrafficRecordingServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return GrpcEvitaTrafficRecordingServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static GrpcEvitaTrafficRecordingServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceBlockingStub>() {
        @java.lang.Override
        public GrpcEvitaTrafficRecordingServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcEvitaTrafficRecordingServiceBlockingStub(channel, callOptions);
        }
      };
    return GrpcEvitaTrafficRecordingServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static GrpcEvitaTrafficRecordingServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcEvitaTrafficRecordingServiceFutureStub>() {
        @java.lang.Override
        public GrpcEvitaTrafficRecordingServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcEvitaTrafficRecordingServiceFutureStub(channel, callOptions);
        }
      };
    return GrpcEvitaTrafficRecordingServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the oldest sessions to the newest,
     * traffic records within the session are ordered from the oldest to the newest.
     * </pre>
     */
    default void getTrafficRecordingHistoryList(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTrafficRecordingHistoryListMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    default void getTrafficRecordingHistoryListReversed(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTrafficRecordingHistoryListReversedMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past traffic records that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    default void getTrafficRecordingHistory(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTrafficRecordingHistoryMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique labels names ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    default void getTrafficRecordingLabelsNamesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique label values ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    default void getTrafficRecordingLabelValuesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that starts the traffic recording for the given criteria and settings
     * </pre>
     */
    default void startTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartTrafficRecordingMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that stops the traffic recording
     * </pre>
     */
    default void stopTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopTrafficRecordingMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service GrpcEvitaTrafficRecordingService.
   */
  public static abstract class GrpcEvitaTrafficRecordingServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return GrpcEvitaTrafficRecordingServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service GrpcEvitaTrafficRecordingService.
   */
  public static final class GrpcEvitaTrafficRecordingServiceStub
      extends io.grpc.stub.AbstractAsyncStub<GrpcEvitaTrafficRecordingServiceStub> {
    private GrpcEvitaTrafficRecordingServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcEvitaTrafficRecordingServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcEvitaTrafficRecordingServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the oldest sessions to the newest,
     * traffic records within the session are ordered from the oldest to the newest.
     * </pre>
     */
    public void getTrafficRecordingHistoryList(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTrafficRecordingHistoryListMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    public void getTrafficRecordingHistoryListReversed(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTrafficRecordingHistoryListReversedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past traffic records that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    public void getTrafficRecordingHistory(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetTrafficRecordingHistoryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique labels names ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public void getTrafficRecordingLabelsNamesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique label values ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public void getTrafficRecordingLabelValuesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that starts the traffic recording for the given criteria and settings
     * </pre>
     */
    public void startTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartTrafficRecordingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that stops the traffic recording
     * </pre>
     */
    public void stopTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopTrafficRecordingMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service GrpcEvitaTrafficRecordingService.
   */
  public static final class GrpcEvitaTrafficRecordingServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<GrpcEvitaTrafficRecordingServiceBlockingV2Stub> {
    private GrpcEvitaTrafficRecordingServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcEvitaTrafficRecordingServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcEvitaTrafficRecordingServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the oldest sessions to the newest,
     * traffic records within the session are ordered from the oldest to the newest.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getTrafficRecordingHistoryList(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTrafficRecordingHistoryListMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getTrafficRecordingHistoryListReversed(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTrafficRecordingHistoryListReversedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past traffic records that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse>
        getTrafficRecordingHistory(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getGetTrafficRecordingHistoryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique labels names ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse getTrafficRecordingLabelsNamesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique label values ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse getTrafficRecordingLabelValuesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that starts the traffic recording for the given criteria and settings
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse startTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStartTrafficRecordingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that stops the traffic recording
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse stopTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStopTrafficRecordingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service GrpcEvitaTrafficRecordingService.
   */
  public static final class GrpcEvitaTrafficRecordingServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<GrpcEvitaTrafficRecordingServiceBlockingStub> {
    private GrpcEvitaTrafficRecordingServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcEvitaTrafficRecordingServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcEvitaTrafficRecordingServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the oldest sessions to the newest,
     * traffic records within the session are ordered from the oldest to the newest.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getTrafficRecordingHistoryList(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTrafficRecordingHistoryListMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getTrafficRecordingHistoryListReversed(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTrafficRecordingHistoryListReversedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past traffic records that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse> getTrafficRecordingHistory(
        io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetTrafficRecordingHistoryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique labels names ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse getTrafficRecordingLabelsNamesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique label values ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse getTrafficRecordingLabelValuesOrderedByCardinality(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that starts the traffic recording for the given criteria and settings
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse startTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartTrafficRecordingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that stops the traffic recording
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse stopTrafficRecording(io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopTrafficRecordingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service GrpcEvitaTrafficRecordingService.
   */
  public static final class GrpcEvitaTrafficRecordingServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<GrpcEvitaTrafficRecordingServiceFutureStub> {
    private GrpcEvitaTrafficRecordingServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcEvitaTrafficRecordingServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcEvitaTrafficRecordingServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the oldest sessions to the newest,
     * traffic records within the session are ordered from the oldest to the newest.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getTrafficRecordingHistoryList(
        io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTrafficRecordingHistoryListMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns requested list of past traffic records with limited size that match the request criteria.
     * Order of the returned records is from the newest sessions to the oldest,
     * traffic records within the session are ordered from the newest to the oldest.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse> getTrafficRecordingHistoryListReversed(
        io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTrafficRecordingHistoryListReversedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique labels names ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse> getTrafficRecordingLabelsNamesOrderedByCardinality(
        io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure returns a list of top unique label values ordered by cardinality of their values present in the traffic recording.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse> getTrafficRecordingLabelValuesOrderedByCardinality(
        io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that starts the traffic recording for the given criteria and settings
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> startTrafficRecording(
        io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartTrafficRecordingMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that stops the traffic recording
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse> stopTrafficRecording(
        io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopTrafficRecordingMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_TRAFFIC_RECORDING_HISTORY_LIST = 0;
  private static final int METHODID_GET_TRAFFIC_RECORDING_HISTORY_LIST_REVERSED = 1;
  private static final int METHODID_GET_TRAFFIC_RECORDING_HISTORY = 2;
  private static final int METHODID_GET_TRAFFIC_RECORDING_LABELS_NAMES_ORDERED_BY_CARDINALITY = 3;
  private static final int METHODID_GET_TRAFFIC_RECORDING_LABEL_VALUES_ORDERED_BY_CARDINALITY = 4;
  private static final int METHODID_START_TRAFFIC_RECORDING = 5;
  private static final int METHODID_STOP_TRAFFIC_RECORDING = 6;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_TRAFFIC_RECORDING_HISTORY_LIST:
          serviceImpl.getTrafficRecordingHistoryList((io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse>) responseObserver);
          break;
        case METHODID_GET_TRAFFIC_RECORDING_HISTORY_LIST_REVERSED:
          serviceImpl.getTrafficRecordingHistoryListReversed((io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse>) responseObserver);
          break;
        case METHODID_GET_TRAFFIC_RECORDING_HISTORY:
          serviceImpl.getTrafficRecordingHistory((io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse>) responseObserver);
          break;
        case METHODID_GET_TRAFFIC_RECORDING_LABELS_NAMES_ORDERED_BY_CARDINALITY:
          serviceImpl.getTrafficRecordingLabelsNamesOrderedByCardinality((io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse>) responseObserver);
          break;
        case METHODID_GET_TRAFFIC_RECORDING_LABEL_VALUES_ORDERED_BY_CARDINALITY:
          serviceImpl.getTrafficRecordingLabelValuesOrderedByCardinality((io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse>) responseObserver);
          break;
        case METHODID_START_TRAFFIC_RECORDING:
          serviceImpl.startTrafficRecording((io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse>) responseObserver);
          break;
        case METHODID_STOP_TRAFFIC_RECORDING:
          serviceImpl.stopTrafficRecording((io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetTrafficRecordingHistoryListMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse>(
                service, METHODID_GET_TRAFFIC_RECORDING_HISTORY_LIST)))
        .addMethod(
          getGetTrafficRecordingHistoryListReversedMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse>(
                service, METHODID_GET_TRAFFIC_RECORDING_HISTORY_LIST_REVERSED)))
        .addMethod(
          getGetTrafficRecordingHistoryMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryResponse>(
                service, METHODID_GET_TRAFFIC_RECORDING_HISTORY)))
        .addMethod(
          getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesResponse>(
                service, METHODID_GET_TRAFFIC_RECORDING_LABELS_NAMES_ORDERED_BY_CARDINALITY)))
        .addMethod(
          getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingValuesNamesResponse>(
                service, METHODID_GET_TRAFFIC_RECORDING_LABEL_VALUES_ORDERED_BY_CARDINALITY)))
        .addMethod(
          getStartTrafficRecordingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcStartTrafficRecordingRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse>(
                service, METHODID_START_TRAFFIC_RECORDING)))
        .addMethod(
          getStopTrafficRecordingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcStopTrafficRecordingRequest,
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingStatusResponse>(
                service, METHODID_STOP_TRAFFIC_RECORDING)))
        .build();
  }

  private static abstract class GrpcEvitaTrafficRecordingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    GrpcEvitaTrafficRecordingServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("GrpcEvitaTrafficRecordingService");
    }
  }

  private static final class GrpcEvitaTrafficRecordingServiceFileDescriptorSupplier
      extends GrpcEvitaTrafficRecordingServiceBaseDescriptorSupplier {
    GrpcEvitaTrafficRecordingServiceFileDescriptorSupplier() {}
  }

  private static final class GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier
      extends GrpcEvitaTrafficRecordingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    GrpcEvitaTrafficRecordingServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (GrpcEvitaTrafficRecordingServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new GrpcEvitaTrafficRecordingServiceFileDescriptorSupplier())
              .addMethod(getGetTrafficRecordingHistoryListMethod())
              .addMethod(getGetTrafficRecordingHistoryListReversedMethod())
              .addMethod(getGetTrafficRecordingHistoryMethod())
              .addMethod(getGetTrafficRecordingLabelsNamesOrderedByCardinalityMethod())
              .addMethod(getGetTrafficRecordingLabelValuesOrderedByCardinalityMethod())
              .addMethod(getStartTrafficRecordingMethod())
              .addMethod(getStopTrafficRecordingMethod())
              .build();
        }
      }
    }
    return result;
  }
}
