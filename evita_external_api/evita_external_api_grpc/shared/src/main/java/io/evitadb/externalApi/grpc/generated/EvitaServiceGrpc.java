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
 * <pre>
 * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
 * a way to create sessions and catalogs, and to update the catalog.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class EvitaServiceGrpc {

  private EvitaServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.EvitaService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcReadyResponse> getIsReadyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "IsReady",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcReadyResponse> getIsReadyMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcReadyResponse> getIsReadyMethod;
    if ((getIsReadyMethod = EvitaServiceGrpc.getIsReadyMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getIsReadyMethod = EvitaServiceGrpc.getIsReadyMethod) == null) {
          EvitaServiceGrpc.getIsReadyMethod = getIsReadyMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcReadyResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IsReady"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("IsReady"))
              .build();
        }
      }
    }
    return getIsReadyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadOnlySessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateReadOnlySession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadOnlySessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadOnlySessionMethod;
    if ((getCreateReadOnlySessionMethod = EvitaServiceGrpc.getCreateReadOnlySessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateReadOnlySessionMethod = EvitaServiceGrpc.getCreateReadOnlySessionMethod) == null) {
          EvitaServiceGrpc.getCreateReadOnlySessionMethod = getCreateReadOnlySessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateReadOnlySession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateReadOnlySession"))
              .build();
        }
      }
    }
    return getCreateReadOnlySessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadWriteSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateReadWriteSession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadWriteSessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadWriteSessionMethod;
    if ((getCreateReadWriteSessionMethod = EvitaServiceGrpc.getCreateReadWriteSessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateReadWriteSessionMethod = EvitaServiceGrpc.getCreateReadWriteSessionMethod) == null) {
          EvitaServiceGrpc.getCreateReadWriteSessionMethod = getCreateReadWriteSessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateReadWriteSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateReadWriteSession"))
              .build();
        }
      }
    }
    return getCreateReadWriteSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadOnlySessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateBinaryReadOnlySession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadOnlySessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadOnlySessionMethod;
    if ((getCreateBinaryReadOnlySessionMethod = EvitaServiceGrpc.getCreateBinaryReadOnlySessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateBinaryReadOnlySessionMethod = EvitaServiceGrpc.getCreateBinaryReadOnlySessionMethod) == null) {
          EvitaServiceGrpc.getCreateBinaryReadOnlySessionMethod = getCreateBinaryReadOnlySessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateBinaryReadOnlySession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateBinaryReadOnlySession"))
              .build();
        }
      }
    }
    return getCreateBinaryReadOnlySessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadWriteSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateBinaryReadWriteSession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadWriteSessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadWriteSessionMethod;
    if ((getCreateBinaryReadWriteSessionMethod = EvitaServiceGrpc.getCreateBinaryReadWriteSessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateBinaryReadWriteSessionMethod = EvitaServiceGrpc.getCreateBinaryReadWriteSessionMethod) == null) {
          EvitaServiceGrpc.getCreateBinaryReadWriteSessionMethod = getCreateBinaryReadWriteSessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateBinaryReadWriteSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateBinaryReadWriteSession"))
              .build();
        }
      }
    }
    return getCreateBinaryReadWriteSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> getTerminateSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TerminateSession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> getTerminateSessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> getTerminateSessionMethod;
    if ((getTerminateSessionMethod = EvitaServiceGrpc.getTerminateSessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getTerminateSessionMethod = EvitaServiceGrpc.getTerminateSessionMethod) == null) {
          EvitaServiceGrpc.getTerminateSessionMethod = getTerminateSessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TerminateSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("TerminateSession"))
              .build();
        }
      }
    }
    return getTerminateSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getGetCatalogNamesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogNames",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getGetCatalogNamesMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getGetCatalogNamesMethod;
    if ((getGetCatalogNamesMethod = EvitaServiceGrpc.getGetCatalogNamesMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetCatalogNamesMethod = EvitaServiceGrpc.getGetCatalogNamesMethod) == null) {
          EvitaServiceGrpc.getGetCatalogNamesMethod = getGetCatalogNamesMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogNames"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetCatalogNames"))
              .build();
        }
      }
    }
    return getGetCatalogNamesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest,
      io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse> getGetCatalogStateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogState",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest,
      io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse> getGetCatalogStateMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest, io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse> getGetCatalogStateMethod;
    if ((getGetCatalogStateMethod = EvitaServiceGrpc.getGetCatalogStateMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetCatalogStateMethod = EvitaServiceGrpc.getGetCatalogStateMethod) == null) {
          EvitaServiceGrpc.getGetCatalogStateMethod = getGetCatalogStateMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest, io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogState"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetCatalogState"))
              .build();
        }
      }
    }
    return getGetCatalogStateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> getDefineCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DefineCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> getDefineCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> getDefineCatalogMethod;
    if ((getDefineCatalogMethod = EvitaServiceGrpc.getDefineCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDefineCatalogMethod = EvitaServiceGrpc.getDefineCatalogMethod) == null) {
          EvitaServiceGrpc.getDefineCatalogMethod = getDefineCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DefineCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DefineCatalog"))
              .build();
        }
      }
    }
    return getDefineCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> getDeleteCatalogIfExistsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteCatalogIfExists",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> getDeleteCatalogIfExistsMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> getDeleteCatalogIfExistsMethod;
    if ((getDeleteCatalogIfExistsMethod = EvitaServiceGrpc.getDeleteCatalogIfExistsMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDeleteCatalogIfExistsMethod = EvitaServiceGrpc.getDeleteCatalogIfExistsMethod) == null) {
          EvitaServiceGrpc.getDeleteCatalogIfExistsMethod = getDeleteCatalogIfExistsMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteCatalogIfExists"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DeleteCatalogIfExists"))
              .build();
        }
      }
    }
    return getDeleteCatalogIfExistsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse> getApplyMutationMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ApplyMutation",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse> getApplyMutationMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse> getApplyMutationMethod;
    if ((getApplyMutationMethod = EvitaServiceGrpc.getApplyMutationMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getApplyMutationMethod = EvitaServiceGrpc.getApplyMutationMethod) == null) {
          EvitaServiceGrpc.getApplyMutationMethod = getApplyMutationMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ApplyMutation"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ApplyMutation"))
              .build();
        }
      }
    }
    return getApplyMutationMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getApplyMutationWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ApplyMutationWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getApplyMutationWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getApplyMutationWithProgressMethod;
    if ((getApplyMutationWithProgressMethod = EvitaServiceGrpc.getApplyMutationWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getApplyMutationWithProgressMethod = EvitaServiceGrpc.getApplyMutationWithProgressMethod) == null) {
          EvitaServiceGrpc.getApplyMutationWithProgressMethod = getApplyMutationWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ApplyMutationWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ApplyMutationWithProgress"))
              .build();
        }
      }
    }
    return getApplyMutationWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> getRenameCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RenameCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> getRenameCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> getRenameCatalogMethod;
    if ((getRenameCatalogMethod = EvitaServiceGrpc.getRenameCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getRenameCatalogMethod = EvitaServiceGrpc.getRenameCatalogMethod) == null) {
          EvitaServiceGrpc.getRenameCatalogMethod = getRenameCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RenameCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("RenameCatalog"))
              .build();
        }
      }
    }
    return getRenameCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getRenameCatalogWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RenameCatalogWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getRenameCatalogWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getRenameCatalogWithProgressMethod;
    if ((getRenameCatalogWithProgressMethod = EvitaServiceGrpc.getRenameCatalogWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getRenameCatalogWithProgressMethod = EvitaServiceGrpc.getRenameCatalogWithProgressMethod) == null) {
          EvitaServiceGrpc.getRenameCatalogWithProgressMethod = getRenameCatalogWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RenameCatalogWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("RenameCatalogWithProgress"))
              .build();
        }
      }
    }
    return getRenameCatalogWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> getReplaceCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReplaceCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> getReplaceCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> getReplaceCatalogMethod;
    if ((getReplaceCatalogMethod = EvitaServiceGrpc.getReplaceCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getReplaceCatalogMethod = EvitaServiceGrpc.getReplaceCatalogMethod) == null) {
          EvitaServiceGrpc.getReplaceCatalogMethod = getReplaceCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReplaceCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ReplaceCatalog"))
              .build();
        }
      }
    }
    return getReplaceCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getReplaceCatalogWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReplaceCatalogWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getReplaceCatalogWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getReplaceCatalogWithProgressMethod;
    if ((getReplaceCatalogWithProgressMethod = EvitaServiceGrpc.getReplaceCatalogWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getReplaceCatalogWithProgressMethod = EvitaServiceGrpc.getReplaceCatalogWithProgressMethod) == null) {
          EvitaServiceGrpc.getReplaceCatalogWithProgressMethod = getReplaceCatalogWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReplaceCatalogWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ReplaceCatalogWithProgress"))
              .build();
        }
      }
    }
    return getReplaceCatalogWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse> getMakeCatalogMutableMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MakeCatalogMutable",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse> getMakeCatalogMutableMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest, io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse> getMakeCatalogMutableMethod;
    if ((getMakeCatalogMutableMethod = EvitaServiceGrpc.getMakeCatalogMutableMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getMakeCatalogMutableMethod = EvitaServiceGrpc.getMakeCatalogMutableMethod) == null) {
          EvitaServiceGrpc.getMakeCatalogMutableMethod = getMakeCatalogMutableMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest, io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MakeCatalogMutable"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("MakeCatalogMutable"))
              .build();
        }
      }
    }
    return getMakeCatalogMutableMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogMutableWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MakeCatalogMutableWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogMutableWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogMutableWithProgressMethod;
    if ((getMakeCatalogMutableWithProgressMethod = EvitaServiceGrpc.getMakeCatalogMutableWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getMakeCatalogMutableWithProgressMethod = EvitaServiceGrpc.getMakeCatalogMutableWithProgressMethod) == null) {
          EvitaServiceGrpc.getMakeCatalogMutableWithProgressMethod = getMakeCatalogMutableWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MakeCatalogMutableWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("MakeCatalogMutableWithProgress"))
              .build();
        }
      }
    }
    return getMakeCatalogMutableWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse> getMakeCatalogImmutableMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MakeCatalogImmutable",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse> getMakeCatalogImmutableMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest, io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse> getMakeCatalogImmutableMethod;
    if ((getMakeCatalogImmutableMethod = EvitaServiceGrpc.getMakeCatalogImmutableMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getMakeCatalogImmutableMethod = EvitaServiceGrpc.getMakeCatalogImmutableMethod) == null) {
          EvitaServiceGrpc.getMakeCatalogImmutableMethod = getMakeCatalogImmutableMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest, io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MakeCatalogImmutable"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("MakeCatalogImmutable"))
              .build();
        }
      }
    }
    return getMakeCatalogImmutableMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogImmutableWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MakeCatalogImmutableWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogImmutableWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogImmutableWithProgressMethod;
    if ((getMakeCatalogImmutableWithProgressMethod = EvitaServiceGrpc.getMakeCatalogImmutableWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getMakeCatalogImmutableWithProgressMethod = EvitaServiceGrpc.getMakeCatalogImmutableWithProgressMethod) == null) {
          EvitaServiceGrpc.getMakeCatalogImmutableWithProgressMethod = getMakeCatalogImmutableWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MakeCatalogImmutableWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("MakeCatalogImmutableWithProgress"))
              .build();
        }
      }
    }
    return getMakeCatalogImmutableWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest,
      io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse> getMakeCatalogAliveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MakeCatalogAlive",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest,
      io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse> getMakeCatalogAliveMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest, io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse> getMakeCatalogAliveMethod;
    if ((getMakeCatalogAliveMethod = EvitaServiceGrpc.getMakeCatalogAliveMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getMakeCatalogAliveMethod = EvitaServiceGrpc.getMakeCatalogAliveMethod) == null) {
          EvitaServiceGrpc.getMakeCatalogAliveMethod = getMakeCatalogAliveMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest, io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MakeCatalogAlive"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("MakeCatalogAlive"))
              .build();
        }
      }
    }
    return getMakeCatalogAliveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogAliveWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "MakeCatalogAliveWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogAliveWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getMakeCatalogAliveWithProgressMethod;
    if ((getMakeCatalogAliveWithProgressMethod = EvitaServiceGrpc.getMakeCatalogAliveWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getMakeCatalogAliveWithProgressMethod = EvitaServiceGrpc.getMakeCatalogAliveWithProgressMethod) == null) {
          EvitaServiceGrpc.getMakeCatalogAliveWithProgressMethod = getMakeCatalogAliveWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "MakeCatalogAliveWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("MakeCatalogAliveWithProgress"))
              .build();
        }
      }
    }
    return getMakeCatalogAliveWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse> getDuplicateCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DuplicateCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse> getDuplicateCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse> getDuplicateCatalogMethod;
    if ((getDuplicateCatalogMethod = EvitaServiceGrpc.getDuplicateCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDuplicateCatalogMethod = EvitaServiceGrpc.getDuplicateCatalogMethod) == null) {
          EvitaServiceGrpc.getDuplicateCatalogMethod = getDuplicateCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DuplicateCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DuplicateCatalog"))
              .build();
        }
      }
    }
    return getDuplicateCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getDuplicateCatalogWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DuplicateCatalogWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getDuplicateCatalogWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getDuplicateCatalogWithProgressMethod;
    if ((getDuplicateCatalogWithProgressMethod = EvitaServiceGrpc.getDuplicateCatalogWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDuplicateCatalogWithProgressMethod = EvitaServiceGrpc.getDuplicateCatalogWithProgressMethod) == null) {
          EvitaServiceGrpc.getDuplicateCatalogWithProgressMethod = getDuplicateCatalogWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DuplicateCatalogWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DuplicateCatalogWithProgress"))
              .build();
        }
      }
    }
    return getDuplicateCatalogWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse> getActivateCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ActivateCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse> getActivateCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse> getActivateCatalogMethod;
    if ((getActivateCatalogMethod = EvitaServiceGrpc.getActivateCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getActivateCatalogMethod = EvitaServiceGrpc.getActivateCatalogMethod) == null) {
          EvitaServiceGrpc.getActivateCatalogMethod = getActivateCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ActivateCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ActivateCatalog"))
              .build();
        }
      }
    }
    return getActivateCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getActivateCatalogWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ActivateCatalogWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getActivateCatalogWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getActivateCatalogWithProgressMethod;
    if ((getActivateCatalogWithProgressMethod = EvitaServiceGrpc.getActivateCatalogWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getActivateCatalogWithProgressMethod = EvitaServiceGrpc.getActivateCatalogWithProgressMethod) == null) {
          EvitaServiceGrpc.getActivateCatalogWithProgressMethod = getActivateCatalogWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ActivateCatalogWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ActivateCatalogWithProgress"))
              .build();
        }
      }
    }
    return getActivateCatalogWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse> getDeactivateCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeactivateCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse> getDeactivateCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse> getDeactivateCatalogMethod;
    if ((getDeactivateCatalogMethod = EvitaServiceGrpc.getDeactivateCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDeactivateCatalogMethod = EvitaServiceGrpc.getDeactivateCatalogMethod) == null) {
          EvitaServiceGrpc.getDeactivateCatalogMethod = getDeactivateCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeactivateCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DeactivateCatalog"))
              .build();
        }
      }
    }
    return getDeactivateCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getDeactivateCatalogWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeactivateCatalogWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getDeactivateCatalogWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> getDeactivateCatalogWithProgressMethod;
    if ((getDeactivateCatalogWithProgressMethod = EvitaServiceGrpc.getDeactivateCatalogWithProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDeactivateCatalogWithProgressMethod = EvitaServiceGrpc.getDeactivateCatalogWithProgressMethod) == null) {
          EvitaServiceGrpc.getDeactivateCatalogWithProgressMethod = getDeactivateCatalogWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeactivateCatalogWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DeactivateCatalogWithProgress"))
              .build();
        }
      }
    }
    return getDeactivateCatalogWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse> getRegisterSystemChangeCaptureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterSystemChangeCapture",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse> getRegisterSystemChangeCaptureMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest, io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse> getRegisterSystemChangeCaptureMethod;
    if ((getRegisterSystemChangeCaptureMethod = EvitaServiceGrpc.getRegisterSystemChangeCaptureMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getRegisterSystemChangeCaptureMethod = EvitaServiceGrpc.getRegisterSystemChangeCaptureMethod) == null) {
          EvitaServiceGrpc.getRegisterSystemChangeCaptureMethod = getRegisterSystemChangeCaptureMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest, io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterSystemChangeCapture"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("RegisterSystemChangeCapture"))
              .build();
        }
      }
    }
    return getRegisterSystemChangeCaptureMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest,
      io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse> getGetProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest,
      io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse> getGetProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest, io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse> getGetProgressMethod;
    if ((getGetProgressMethod = EvitaServiceGrpc.getGetProgressMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetProgressMethod = EvitaServiceGrpc.getGetProgressMethod) == null) {
          EvitaServiceGrpc.getGetProgressMethod = getGetProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest, io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetProgress"))
              .build();
        }
      }
    }
    return getGetProgressMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EvitaServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceStub>() {
        @java.lang.Override
        public EvitaServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceStub(channel, callOptions);
        }
      };
    return EvitaServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static EvitaServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceBlockingV2Stub>() {
        @java.lang.Override
        public EvitaServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return EvitaServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EvitaServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceBlockingStub>() {
        @java.lang.Override
        public EvitaServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceBlockingStub(channel, callOptions);
        }
      };
    return EvitaServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EvitaServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceFutureStub>() {
        @java.lang.Override
        public EvitaServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceFutureStub(channel, callOptions);
        }
      };
    return EvitaServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Procedure used to check readiness of the API
     * </pre>
     */
    default void isReady(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReadyResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIsReadyMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    default void createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateReadOnlySessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    default void createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateReadWriteSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    default void createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateBinaryReadOnlySessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    default void createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateBinaryReadWriteSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    default void terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTerminateSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    default void getCatalogNames(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogNamesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get state of the catalog by its name.
     * </pre>
     */
    default void getCatalogState(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogStateMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    default void defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDefineCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    default void deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteCatalogIfExistsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    default void applyMutation(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApplyMutationMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations which tracks the progress of the operation.
     * </pre>
     */
    default void applyMutationWithProgress(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getApplyMutationWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    default void renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRenameCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog with progress tracking.
     * </pre>
     */
    default void renameCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRenameCatalogWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    default void replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplaceCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog with progress tracking.
     * </pre>
     */
    default void replaceCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplaceCatalogWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable.
     * </pre>
     */
    default void makeCatalogMutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMakeCatalogMutableMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable with progress tracking.
     * </pre>
     */
    default void makeCatalogMutableWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMakeCatalogMutableWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable.
     * </pre>
     */
    default void makeCatalogImmutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMakeCatalogImmutableMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable with progress tracking.
     * </pre>
     */
    default void makeCatalogImmutableWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMakeCatalogImmutableWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive.
     * </pre>
     */
    default void makeCatalogAlive(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMakeCatalogAliveMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive with progress tracking.
     * </pre>
     */
    default void makeCatalogAliveWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMakeCatalogAliveWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog.
     * </pre>
     */
    default void duplicateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDuplicateCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog with progress tracking.
     * </pre>
     */
    default void duplicateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDuplicateCatalogWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog.
     * </pre>
     */
    default void activateCatalog(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getActivateCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog with progress tracking.
     * </pre>
     */
    default void activateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getActivateCatalogWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog.
     * </pre>
     */
    default void deactivateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeactivateCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog with progress tracking.
     * </pre>
     */
    default void deactivateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeactivateCatalogWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to register a system change capture.
     * </pre>
     */
    default void registerSystemChangeCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterSystemChangeCaptureMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to initiate progress consumption for top-level engine mutations.
     * </pre>
     */
    default void getProgress(io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetProgressMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service EvitaService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static abstract class EvitaServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EvitaServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service EvitaService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceStub
      extends io.grpc.stub.AbstractAsyncStub<EvitaServiceStub> {
    private EvitaServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to check readiness of the API
     * </pre>
     */
    public void isReady(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReadyResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIsReadyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public void createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateReadOnlySessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public void createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateReadWriteSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public void createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateBinaryReadOnlySessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public void createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateBinaryReadWriteSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public void terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTerminateSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public void getCatalogNames(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogNamesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get state of the catalog by its name.
     * </pre>
     */
    public void getCatalogState(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogStateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public void defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDefineCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public void deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteCatalogIfExistsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public void applyMutation(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getApplyMutationMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations which tracks the progress of the operation.
     * </pre>
     */
    public void applyMutationWithProgress(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getApplyMutationWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public void renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRenameCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog with progress tracking.
     * </pre>
     */
    public void renameCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getRenameCatalogWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public void replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReplaceCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog with progress tracking.
     * </pre>
     */
    public void replaceCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getReplaceCatalogWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable.
     * </pre>
     */
    public void makeCatalogMutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMakeCatalogMutableMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable with progress tracking.
     * </pre>
     */
    public void makeCatalogMutableWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getMakeCatalogMutableWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable.
     * </pre>
     */
    public void makeCatalogImmutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMakeCatalogImmutableMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable with progress tracking.
     * </pre>
     */
    public void makeCatalogImmutableWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getMakeCatalogImmutableWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive.
     * </pre>
     */
    public void makeCatalogAlive(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMakeCatalogAliveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive with progress tracking.
     * </pre>
     */
    public void makeCatalogAliveWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getMakeCatalogAliveWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog.
     * </pre>
     */
    public void duplicateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDuplicateCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog with progress tracking.
     * </pre>
     */
    public void duplicateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getDuplicateCatalogWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog.
     * </pre>
     */
    public void activateCatalog(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getActivateCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog with progress tracking.
     * </pre>
     */
    public void activateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getActivateCatalogWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog.
     * </pre>
     */
    public void deactivateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeactivateCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog with progress tracking.
     * </pre>
     */
    public void deactivateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getDeactivateCatalogWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to register a system change capture.
     * </pre>
     */
    public void registerSystemChangeCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getRegisterSystemChangeCaptureMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to initiate progress consumption for top-level engine mutations.
     * </pre>
     */
    public void getProgress(io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetProgressMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service EvitaService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<EvitaServiceBlockingV2Stub> {
    private EvitaServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to check readiness of the API
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReadyResponse isReady(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getIsReadyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateBinaryReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCreateBinaryReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getTerminateSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse getCatalogNames(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetCatalogNamesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get state of the catalog by its name.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse getCatalogState(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetCatalogStateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDefineCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteCatalogIfExistsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse applyMutation(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getApplyMutationMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations which tracks the progress of the operation.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        applyMutationWithProgress(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getApplyMutationWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRenameCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        renameCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getRenameCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReplaceCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        replaceCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getReplaceCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse makeCatalogMutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMakeCatalogMutableMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        makeCatalogMutableWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getMakeCatalogMutableWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse makeCatalogImmutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMakeCatalogImmutableMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        makeCatalogImmutableWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getMakeCatalogImmutableWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse makeCatalogAlive(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getMakeCatalogAliveMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        makeCatalogAliveWithProgress(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getMakeCatalogAliveWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse duplicateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDuplicateCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        duplicateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getDuplicateCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse activateCatalog(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getActivateCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        activateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getActivateCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse deactivateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeactivateCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog with progress tracking.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>
        deactivateCatalogWithProgress(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getDeactivateCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to register a system change capture.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse>
        registerSystemChangeCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getRegisterSystemChangeCaptureMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to initiate progress consumption for top-level engine mutations.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse>
        getProgress(io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getGetProgressMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service EvitaService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EvitaServiceBlockingStub> {
    private EvitaServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to check readiness of the API
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReadyResponse isReady(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIsReadyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateBinaryReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateBinaryReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTerminateSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse getCatalogNames(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogNamesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get state of the catalog by its name.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse getCatalogState(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogStateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDefineCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteCatalogIfExistsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse applyMutation(io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getApplyMutationMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations which tracks the progress of the operation.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> applyMutationWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getApplyMutationWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRenameCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> renameCatalogWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getRenameCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReplaceCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> replaceCatalogWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getReplaceCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse makeCatalogMutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMakeCatalogMutableMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> makeCatalogMutableWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getMakeCatalogMutableWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse makeCatalogImmutable(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMakeCatalogImmutableMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> makeCatalogImmutableWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getMakeCatalogImmutableWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse makeCatalogAlive(io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMakeCatalogAliveMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> makeCatalogAliveWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getMakeCatalogAliveWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse duplicateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDuplicateCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> duplicateCatalogWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getDuplicateCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse activateCatalog(io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getActivateCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> activateCatalogWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getActivateCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse deactivateCatalog(io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeactivateCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog with progress tracking.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse> deactivateCatalogWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getDeactivateCatalogWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to register a system change capture.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse> registerSystemChangeCapture(
        io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getRegisterSystemChangeCaptureMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to initiate progress consumption for top-level engine mutations.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse> getProgress(
        io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetProgressMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service EvitaService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<EvitaServiceFutureStub> {
    private EvitaServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to check readiness of the API
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcReadyResponse> isReady(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIsReadyMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createReadOnlySession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateReadOnlySessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createReadWriteSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateReadWriteSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createBinaryReadOnlySession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateBinaryReadOnlySessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createBinaryReadWriteSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateBinaryReadWriteSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> terminateSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTerminateSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getCatalogNames(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogNamesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get state of the catalog by its name.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse> getCatalogState(
        io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogStateMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> defineCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDefineCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> deleteCatalogIfExists(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteCatalogIfExistsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse> applyMutation(
        io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getApplyMutationMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> renameCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRenameCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> replaceCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReplaceCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog mutable.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse> makeCatalogMutable(
        io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMakeCatalogMutableMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog immutable.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse> makeCatalogImmutable(
        io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMakeCatalogImmutableMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to make a catalog alive.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse> makeCatalogAlive(
        io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMakeCatalogAliveMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to duplicate a catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse> duplicateCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDuplicateCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to activate a catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse> activateCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getActivateCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to deactivate a catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse> deactivateCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeactivateCatalogMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_IS_READY = 0;
  private static final int METHODID_CREATE_READ_ONLY_SESSION = 1;
  private static final int METHODID_CREATE_READ_WRITE_SESSION = 2;
  private static final int METHODID_CREATE_BINARY_READ_ONLY_SESSION = 3;
  private static final int METHODID_CREATE_BINARY_READ_WRITE_SESSION = 4;
  private static final int METHODID_TERMINATE_SESSION = 5;
  private static final int METHODID_GET_CATALOG_NAMES = 6;
  private static final int METHODID_GET_CATALOG_STATE = 7;
  private static final int METHODID_DEFINE_CATALOG = 8;
  private static final int METHODID_DELETE_CATALOG_IF_EXISTS = 9;
  private static final int METHODID_APPLY_MUTATION = 10;
  private static final int METHODID_APPLY_MUTATION_WITH_PROGRESS = 11;
  private static final int METHODID_RENAME_CATALOG = 12;
  private static final int METHODID_RENAME_CATALOG_WITH_PROGRESS = 13;
  private static final int METHODID_REPLACE_CATALOG = 14;
  private static final int METHODID_REPLACE_CATALOG_WITH_PROGRESS = 15;
  private static final int METHODID_MAKE_CATALOG_MUTABLE = 16;
  private static final int METHODID_MAKE_CATALOG_MUTABLE_WITH_PROGRESS = 17;
  private static final int METHODID_MAKE_CATALOG_IMMUTABLE = 18;
  private static final int METHODID_MAKE_CATALOG_IMMUTABLE_WITH_PROGRESS = 19;
  private static final int METHODID_MAKE_CATALOG_ALIVE = 20;
  private static final int METHODID_MAKE_CATALOG_ALIVE_WITH_PROGRESS = 21;
  private static final int METHODID_DUPLICATE_CATALOG = 22;
  private static final int METHODID_DUPLICATE_CATALOG_WITH_PROGRESS = 23;
  private static final int METHODID_ACTIVATE_CATALOG = 24;
  private static final int METHODID_ACTIVATE_CATALOG_WITH_PROGRESS = 25;
  private static final int METHODID_DEACTIVATE_CATALOG = 26;
  private static final int METHODID_DEACTIVATE_CATALOG_WITH_PROGRESS = 27;
  private static final int METHODID_REGISTER_SYSTEM_CHANGE_CAPTURE = 28;
  private static final int METHODID_GET_PROGRESS = 29;

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
        case METHODID_IS_READY:
          serviceImpl.isReady((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReadyResponse>) responseObserver);
          break;
        case METHODID_CREATE_READ_ONLY_SESSION:
          serviceImpl.createReadOnlySession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_CREATE_READ_WRITE_SESSION:
          serviceImpl.createReadWriteSession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_CREATE_BINARY_READ_ONLY_SESSION:
          serviceImpl.createBinaryReadOnlySession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_CREATE_BINARY_READ_WRITE_SESSION:
          serviceImpl.createBinaryReadWriteSession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_TERMINATE_SESSION:
          serviceImpl.terminateSession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse>) responseObserver);
          break;
        case METHODID_GET_CATALOG_NAMES:
          serviceImpl.getCatalogNames((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse>) responseObserver);
          break;
        case METHODID_GET_CATALOG_STATE:
          serviceImpl.getCatalogState((io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse>) responseObserver);
          break;
        case METHODID_DEFINE_CATALOG:
          serviceImpl.defineCatalog((io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse>) responseObserver);
          break;
        case METHODID_DELETE_CATALOG_IF_EXISTS:
          serviceImpl.deleteCatalogIfExists((io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>) responseObserver);
          break;
        case METHODID_APPLY_MUTATION:
          serviceImpl.applyMutation((io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse>) responseObserver);
          break;
        case METHODID_APPLY_MUTATION_WITH_PROGRESS:
          serviceImpl.applyMutationWithProgress((io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_RENAME_CATALOG:
          serviceImpl.renameCatalog((io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse>) responseObserver);
          break;
        case METHODID_RENAME_CATALOG_WITH_PROGRESS:
          serviceImpl.renameCatalogWithProgress((io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_REPLACE_CATALOG:
          serviceImpl.replaceCatalog((io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse>) responseObserver);
          break;
        case METHODID_REPLACE_CATALOG_WITH_PROGRESS:
          serviceImpl.replaceCatalogWithProgress((io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_MAKE_CATALOG_MUTABLE:
          serviceImpl.makeCatalogMutable((io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse>) responseObserver);
          break;
        case METHODID_MAKE_CATALOG_MUTABLE_WITH_PROGRESS:
          serviceImpl.makeCatalogMutableWithProgress((io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_MAKE_CATALOG_IMMUTABLE:
          serviceImpl.makeCatalogImmutable((io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse>) responseObserver);
          break;
        case METHODID_MAKE_CATALOG_IMMUTABLE_WITH_PROGRESS:
          serviceImpl.makeCatalogImmutableWithProgress((io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_MAKE_CATALOG_ALIVE:
          serviceImpl.makeCatalogAlive((io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse>) responseObserver);
          break;
        case METHODID_MAKE_CATALOG_ALIVE_WITH_PROGRESS:
          serviceImpl.makeCatalogAliveWithProgress((io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_DUPLICATE_CATALOG:
          serviceImpl.duplicateCatalog((io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse>) responseObserver);
          break;
        case METHODID_DUPLICATE_CATALOG_WITH_PROGRESS:
          serviceImpl.duplicateCatalogWithProgress((io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_ACTIVATE_CATALOG:
          serviceImpl.activateCatalog((io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse>) responseObserver);
          break;
        case METHODID_ACTIVATE_CATALOG_WITH_PROGRESS:
          serviceImpl.activateCatalogWithProgress((io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_DEACTIVATE_CATALOG:
          serviceImpl.deactivateCatalog((io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse>) responseObserver);
          break;
        case METHODID_DEACTIVATE_CATALOG_WITH_PROGRESS:
          serviceImpl.deactivateCatalogWithProgress((io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>) responseObserver);
          break;
        case METHODID_REGISTER_SYSTEM_CHANGE_CAPTURE:
          serviceImpl.registerSystemChangeCapture((io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse>) responseObserver);
          break;
        case METHODID_GET_PROGRESS:
          serviceImpl.getProgress((io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse>) responseObserver);
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
          getIsReadyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcReadyResponse>(
                service, METHODID_IS_READY)))
        .addMethod(
          getCreateReadOnlySessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                service, METHODID_CREATE_READ_ONLY_SESSION)))
        .addMethod(
          getCreateReadWriteSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                service, METHODID_CREATE_READ_WRITE_SESSION)))
        .addMethod(
          getCreateBinaryReadOnlySessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                service, METHODID_CREATE_BINARY_READ_ONLY_SESSION)))
        .addMethod(
          getCreateBinaryReadWriteSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                service, METHODID_CREATE_BINARY_READ_WRITE_SESSION)))
        .addMethod(
          getTerminateSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse>(
                service, METHODID_TERMINATE_SESSION)))
        .addMethod(
          getGetCatalogNamesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse>(
                service, METHODID_GET_CATALOG_NAMES)))
        .addMethod(
          getGetCatalogStateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest,
              io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse>(
                service, METHODID_GET_CATALOG_STATE)))
        .addMethod(
          getDefineCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse>(
                service, METHODID_DEFINE_CATALOG)))
        .addMethod(
          getDeleteCatalogIfExistsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>(
                service, METHODID_DELETE_CATALOG_IF_EXISTS)))
        .addMethod(
          getApplyMutationMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationResponse>(
                service, METHODID_APPLY_MUTATION)))
        .addMethod(
          getApplyMutationWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_APPLY_MUTATION_WITH_PROGRESS)))
        .addMethod(
          getRenameCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse>(
                service, METHODID_RENAME_CATALOG)))
        .addMethod(
          getRenameCatalogWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_RENAME_CATALOG_WITH_PROGRESS)))
        .addMethod(
          getReplaceCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse>(
                service, METHODID_REPLACE_CATALOG)))
        .addMethod(
          getReplaceCatalogWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_REPLACE_CATALOG_WITH_PROGRESS)))
        .addMethod(
          getMakeCatalogMutableMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest,
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableResponse>(
                service, METHODID_MAKE_CATALOG_MUTABLE)))
        .addMethod(
          getMakeCatalogMutableWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogMutableRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_MAKE_CATALOG_MUTABLE_WITH_PROGRESS)))
        .addMethod(
          getMakeCatalogImmutableMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest,
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableResponse>(
                service, METHODID_MAKE_CATALOG_IMMUTABLE)))
        .addMethod(
          getMakeCatalogImmutableWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogImmutableRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_MAKE_CATALOG_IMMUTABLE_WITH_PROGRESS)))
        .addMethod(
          getMakeCatalogAliveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest,
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveResponse>(
                service, METHODID_MAKE_CATALOG_ALIVE)))
        .addMethod(
          getMakeCatalogAliveWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcMakeCatalogAliveRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_MAKE_CATALOG_ALIVE_WITH_PROGRESS)))
        .addMethod(
          getDuplicateCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogResponse>(
                service, METHODID_DUPLICATE_CATALOG)))
        .addMethod(
          getDuplicateCatalogWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDuplicateCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_DUPLICATE_CATALOG_WITH_PROGRESS)))
        .addMethod(
          getActivateCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogResponse>(
                service, METHODID_ACTIVATE_CATALOG)))
        .addMethod(
          getActivateCatalogWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcActivateCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_ACTIVATE_CATALOG_WITH_PROGRESS)))
        .addMethod(
          getDeactivateCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogResponse>(
                service, METHODID_DEACTIVATE_CATALOG)))
        .addMethod(
          getDeactivateCatalogWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeactivateCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse>(
                service, METHODID_DEACTIVATE_CATALOG_WITH_PROGRESS)))
        .addMethod(
          getRegisterSystemChangeCaptureMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest,
              io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse>(
                service, METHODID_REGISTER_SYSTEM_CHANGE_CAPTURE)))
        .addMethod(
          getGetProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcGetProgressRequest,
              io.evitadb.externalApi.grpc.generated.GrpcGetProgressResponse>(
                service, METHODID_GET_PROGRESS)))
        .build();
  }

  private static abstract class EvitaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EvitaServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EvitaService");
    }
  }

  private static final class EvitaServiceFileDescriptorSupplier
      extends EvitaServiceBaseDescriptorSupplier {
    EvitaServiceFileDescriptorSupplier() {}
  }

  private static final class EvitaServiceMethodDescriptorSupplier
      extends EvitaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EvitaServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (EvitaServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EvitaServiceFileDescriptorSupplier())
              .addMethod(getIsReadyMethod())
              .addMethod(getCreateReadOnlySessionMethod())
              .addMethod(getCreateReadWriteSessionMethod())
              .addMethod(getCreateBinaryReadOnlySessionMethod())
              .addMethod(getCreateBinaryReadWriteSessionMethod())
              .addMethod(getTerminateSessionMethod())
              .addMethod(getGetCatalogNamesMethod())
              .addMethod(getGetCatalogStateMethod())
              .addMethod(getDefineCatalogMethod())
              .addMethod(getDeleteCatalogIfExistsMethod())
              .addMethod(getApplyMutationMethod())
              .addMethod(getApplyMutationWithProgressMethod())
              .addMethod(getRenameCatalogMethod())
              .addMethod(getRenameCatalogWithProgressMethod())
              .addMethod(getReplaceCatalogMethod())
              .addMethod(getReplaceCatalogWithProgressMethod())
              .addMethod(getMakeCatalogMutableMethod())
              .addMethod(getMakeCatalogMutableWithProgressMethod())
              .addMethod(getMakeCatalogImmutableMethod())
              .addMethod(getMakeCatalogImmutableWithProgressMethod())
              .addMethod(getMakeCatalogAliveMethod())
              .addMethod(getMakeCatalogAliveWithProgressMethod())
              .addMethod(getDuplicateCatalogMethod())
              .addMethod(getDuplicateCatalogWithProgressMethod())
              .addMethod(getActivateCatalogMethod())
              .addMethod(getActivateCatalogWithProgressMethod())
              .addMethod(getDeactivateCatalogMethod())
              .addMethod(getDeactivateCatalogWithProgressMethod())
              .addMethod(getRegisterSystemChangeCaptureMethod())
              .addMethod(getGetProgressMethod())
              .build();
        }
      }
    }
    return result;
  }
}
