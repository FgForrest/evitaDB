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
 * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
 * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
 * evitaDB's implementation a called on an instance of EvitaSessionContract.
 * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
 * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
 * be fetched by specifying a complex queries.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class EvitaSessionServiceGrpc {

  private EvitaSessionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.EvitaSessionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getGetCatalogSchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogSchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getGetCatalogSchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getGetCatalogSchemaMethod;
    if ((getGetCatalogSchemaMethod = EvitaSessionServiceGrpc.getGetCatalogSchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetCatalogSchemaMethod = EvitaSessionServiceGrpc.getGetCatalogSchemaMethod) == null) {
          EvitaSessionServiceGrpc.getGetCatalogSchemaMethod = getGetCatalogSchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogSchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetCatalogSchema"))
              .build();
        }
      }
    }
    return getGetCatalogSchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> getGetCatalogStateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogState",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> getGetCatalogStateMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> getGetCatalogStateMethod;
    if ((getGetCatalogStateMethod = EvitaSessionServiceGrpc.getGetCatalogStateMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetCatalogStateMethod = EvitaSessionServiceGrpc.getGetCatalogStateMethod) == null) {
          EvitaSessionServiceGrpc.getGetCatalogStateMethod = getGetCatalogStateMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogState"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetCatalogState"))
              .build();
        }
      }
    }
    return getGetCatalogStateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse> getGetCatalogVersionAtMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogVersionAt",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse> getGetCatalogVersionAtMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest, io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse> getGetCatalogVersionAtMethod;
    if ((getGetCatalogVersionAtMethod = EvitaSessionServiceGrpc.getGetCatalogVersionAtMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetCatalogVersionAtMethod = EvitaSessionServiceGrpc.getGetCatalogVersionAtMethod) == null) {
          EvitaSessionServiceGrpc.getGetCatalogVersionAtMethod = getGetCatalogVersionAtMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest, io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogVersionAt"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetCatalogVersionAt"))
              .build();
        }
      }
    }
    return getGetCatalogVersionAtMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest,
      io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse> getGetMutationsHistoryPageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMutationsHistoryPage",
      requestType = io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest,
      io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse> getGetMutationsHistoryPageMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest, io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse> getGetMutationsHistoryPageMethod;
    if ((getGetMutationsHistoryPageMethod = EvitaSessionServiceGrpc.getGetMutationsHistoryPageMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetMutationsHistoryPageMethod = EvitaSessionServiceGrpc.getGetMutationsHistoryPageMethod) == null) {
          EvitaSessionServiceGrpc.getGetMutationsHistoryPageMethod = getGetMutationsHistoryPageMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest, io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMutationsHistoryPage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetMutationsHistoryPage"))
              .build();
        }
      }
    }
    return getGetMutationsHistoryPageMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest,
      io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse> getGetMutationsHistoryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMutationsHistory",
      requestType = io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest,
      io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse> getGetMutationsHistoryMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest, io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse> getGetMutationsHistoryMethod;
    if ((getGetMutationsHistoryMethod = EvitaSessionServiceGrpc.getGetMutationsHistoryMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetMutationsHistoryMethod = EvitaSessionServiceGrpc.getGetMutationsHistoryMethod) == null) {
          EvitaSessionServiceGrpc.getGetMutationsHistoryMethod = getGetMutationsHistoryMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest, io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMutationsHistory"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetMutationsHistory"))
              .build();
        }
      }
    }
    return getGetMutationsHistoryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> getGetEntitySchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetEntitySchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> getGetEntitySchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> getGetEntitySchemaMethod;
    if ((getGetEntitySchemaMethod = EvitaSessionServiceGrpc.getGetEntitySchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetEntitySchemaMethod = EvitaSessionServiceGrpc.getGetEntitySchemaMethod) == null) {
          EvitaSessionServiceGrpc.getGetEntitySchemaMethod = getGetEntitySchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetEntitySchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetEntitySchema"))
              .build();
        }
      }
    }
    return getGetEntitySchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> getGetAllEntityTypesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAllEntityTypes",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> getGetAllEntityTypesMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> getGetAllEntityTypesMethod;
    if ((getGetAllEntityTypesMethod = EvitaSessionServiceGrpc.getGetAllEntityTypesMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetAllEntityTypesMethod = EvitaSessionServiceGrpc.getGetAllEntityTypesMethod) == null) {
          EvitaSessionServiceGrpc.getGetAllEntityTypesMethod = getGetAllEntityTypesMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAllEntityTypes"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetAllEntityTypes"))
              .build();
        }
      }
    }
    return getGetAllEntityTypesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> getGoLiveAndCloseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GoLiveAndClose",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> getGoLiveAndCloseMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> getGoLiveAndCloseMethod;
    if ((getGoLiveAndCloseMethod = EvitaSessionServiceGrpc.getGoLiveAndCloseMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGoLiveAndCloseMethod = EvitaSessionServiceGrpc.getGoLiveAndCloseMethod) == null) {
          EvitaSessionServiceGrpc.getGoLiveAndCloseMethod = getGoLiveAndCloseMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GoLiveAndClose"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GoLiveAndClose"))
              .build();
        }
      }
    }
    return getGoLiveAndCloseMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse> getGoLiveAndCloseWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GoLiveAndCloseWithProgress",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse> getGoLiveAndCloseWithProgressMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse> getGoLiveAndCloseWithProgressMethod;
    if ((getGoLiveAndCloseWithProgressMethod = EvitaSessionServiceGrpc.getGoLiveAndCloseWithProgressMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGoLiveAndCloseWithProgressMethod = EvitaSessionServiceGrpc.getGoLiveAndCloseWithProgressMethod) == null) {
          EvitaSessionServiceGrpc.getGoLiveAndCloseWithProgressMethod = getGoLiveAndCloseWithProgressMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GoLiveAndCloseWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GoLiveAndCloseWithProgress"))
              .build();
        }
      }
    }
    return getGoLiveAndCloseWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse> getBackupCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BackupCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse> getBackupCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse> getBackupCatalogMethod;
    if ((getBackupCatalogMethod = EvitaSessionServiceGrpc.getBackupCatalogMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getBackupCatalogMethod = EvitaSessionServiceGrpc.getBackupCatalogMethod) == null) {
          EvitaSessionServiceGrpc.getBackupCatalogMethod = getBackupCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BackupCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("BackupCatalog"))
              .build();
        }
      }
    }
    return getBackupCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse> getFullBackupCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FullBackupCatalog",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse> getFullBackupCatalogMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse> getFullBackupCatalogMethod;
    if ((getFullBackupCatalogMethod = EvitaSessionServiceGrpc.getFullBackupCatalogMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getFullBackupCatalogMethod = EvitaSessionServiceGrpc.getFullBackupCatalogMethod) == null) {
          EvitaSessionServiceGrpc.getFullBackupCatalogMethod = getFullBackupCatalogMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FullBackupCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("FullBackupCatalog"))
              .build();
        }
      }
    }
    return getFullBackupCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCloseResponse> getCloseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Close",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcCloseRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCloseResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCloseResponse> getCloseMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseRequest, io.evitadb.externalApi.grpc.generated.GrpcCloseResponse> getCloseMethod;
    if ((getCloseMethod = EvitaSessionServiceGrpc.getCloseMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getCloseMethod = EvitaSessionServiceGrpc.getCloseMethod) == null) {
          EvitaSessionServiceGrpc.getCloseMethod = getCloseMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcCloseRequest, io.evitadb.externalApi.grpc.generated.GrpcCloseResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Close"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCloseRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCloseResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("Close"))
              .build();
        }
      }
    }
    return getCloseMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse> getCloseWithProgressMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CloseWithProgress",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse> getCloseWithProgressMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest, io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse> getCloseWithProgressMethod;
    if ((getCloseWithProgressMethod = EvitaSessionServiceGrpc.getCloseWithProgressMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getCloseWithProgressMethod = EvitaSessionServiceGrpc.getCloseWithProgressMethod) == null) {
          EvitaSessionServiceGrpc.getCloseWithProgressMethod = getCloseWithProgressMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest, io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CloseWithProgress"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("CloseWithProgress"))
              .build();
        }
      }
    }
    return getCloseWithProgressMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> getQueryOneMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QueryOne",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcQueryRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> getQueryOneMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> getQueryOneMethod;
    if ((getQueryOneMethod = EvitaSessionServiceGrpc.getQueryOneMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getQueryOneMethod = EvitaSessionServiceGrpc.getQueryOneMethod) == null) {
          EvitaSessionServiceGrpc.getQueryOneMethod = getQueryOneMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QueryOne"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("QueryOne"))
              .build();
        }
      }
    }
    return getQueryOneMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> getQueryListMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QueryList",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcQueryRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> getQueryListMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> getQueryListMethod;
    if ((getQueryListMethod = EvitaSessionServiceGrpc.getQueryListMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getQueryListMethod = EvitaSessionServiceGrpc.getQueryListMethod) == null) {
          EvitaSessionServiceGrpc.getQueryListMethod = getQueryListMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QueryList"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("QueryList"))
              .build();
        }
      }
    }
    return getQueryListMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> getQueryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Query",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcQueryRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcQueryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> getQueryMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> getQueryMethod;
    if ((getQueryMethod = EvitaSessionServiceGrpc.getQueryMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getQueryMethod = EvitaSessionServiceGrpc.getQueryMethod) == null) {
          EvitaSessionServiceGrpc.getQueryMethod = getQueryMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcQueryRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Query"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("Query"))
              .build();
        }
      }
    }
    return getQueryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> getQueryOneUnsafeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QueryOneUnsafe",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> getQueryOneUnsafeMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> getQueryOneUnsafeMethod;
    if ((getQueryOneUnsafeMethod = EvitaSessionServiceGrpc.getQueryOneUnsafeMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getQueryOneUnsafeMethod = EvitaSessionServiceGrpc.getQueryOneUnsafeMethod) == null) {
          EvitaSessionServiceGrpc.getQueryOneUnsafeMethod = getQueryOneUnsafeMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QueryOneUnsafe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("QueryOneUnsafe"))
              .build();
        }
      }
    }
    return getQueryOneUnsafeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> getQueryListUnsafeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QueryListUnsafe",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> getQueryListUnsafeMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> getQueryListUnsafeMethod;
    if ((getQueryListUnsafeMethod = EvitaSessionServiceGrpc.getQueryListUnsafeMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getQueryListUnsafeMethod = EvitaSessionServiceGrpc.getQueryListUnsafeMethod) == null) {
          EvitaSessionServiceGrpc.getQueryListUnsafeMethod = getQueryListUnsafeMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QueryListUnsafe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("QueryListUnsafe"))
              .build();
        }
      }
    }
    return getQueryListUnsafeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> getQueryUnsafeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QueryUnsafe",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcQueryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> getQueryUnsafeMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> getQueryUnsafeMethod;
    if ((getQueryUnsafeMethod = EvitaSessionServiceGrpc.getQueryUnsafeMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getQueryUnsafeMethod = EvitaSessionServiceGrpc.getQueryUnsafeMethod) == null) {
          EvitaSessionServiceGrpc.getQueryUnsafeMethod = getQueryUnsafeMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest, io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QueryUnsafe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcQueryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("QueryUnsafe"))
              .build();
        }
      }
    }
    return getQueryUnsafeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> getGetEntityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetEntity",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEntityRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> getGetEntityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> getGetEntityMethod;
    if ((getGetEntityMethod = EvitaSessionServiceGrpc.getGetEntityMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetEntityMethod = EvitaSessionServiceGrpc.getGetEntityMethod) == null) {
          EvitaSessionServiceGrpc.getGetEntityMethod = getGetEntityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcEntityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetEntity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetEntity"))
              .build();
        }
      }
    }
    return getGetEntityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> getUpdateCatalogSchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateCatalogSchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> getUpdateCatalogSchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> getUpdateCatalogSchemaMethod;
    if ((getUpdateCatalogSchemaMethod = EvitaSessionServiceGrpc.getUpdateCatalogSchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getUpdateCatalogSchemaMethod = EvitaSessionServiceGrpc.getUpdateCatalogSchemaMethod) == null) {
          EvitaSessionServiceGrpc.getUpdateCatalogSchemaMethod = getUpdateCatalogSchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateCatalogSchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("UpdateCatalogSchema"))
              .build();
        }
      }
    }
    return getUpdateCatalogSchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> getUpdateAndFetchCatalogSchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateAndFetchCatalogSchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> getUpdateAndFetchCatalogSchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> getUpdateAndFetchCatalogSchemaMethod;
    if ((getUpdateAndFetchCatalogSchemaMethod = EvitaSessionServiceGrpc.getUpdateAndFetchCatalogSchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getUpdateAndFetchCatalogSchemaMethod = EvitaSessionServiceGrpc.getUpdateAndFetchCatalogSchemaMethod) == null) {
          EvitaSessionServiceGrpc.getUpdateAndFetchCatalogSchemaMethod = getUpdateAndFetchCatalogSchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateAndFetchCatalogSchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("UpdateAndFetchCatalogSchema"))
              .build();
        }
      }
    }
    return getUpdateAndFetchCatalogSchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> getDefineEntitySchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DefineEntitySchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> getDefineEntitySchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> getDefineEntitySchemaMethod;
    if ((getDefineEntitySchemaMethod = EvitaSessionServiceGrpc.getDefineEntitySchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getDefineEntitySchemaMethod = EvitaSessionServiceGrpc.getDefineEntitySchemaMethod) == null) {
          EvitaSessionServiceGrpc.getDefineEntitySchemaMethod = getDefineEntitySchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DefineEntitySchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("DefineEntitySchema"))
              .build();
        }
      }
    }
    return getDefineEntitySchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> getUpdateEntitySchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateEntitySchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> getUpdateEntitySchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> getUpdateEntitySchemaMethod;
    if ((getUpdateEntitySchemaMethod = EvitaSessionServiceGrpc.getUpdateEntitySchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getUpdateEntitySchemaMethod = EvitaSessionServiceGrpc.getUpdateEntitySchemaMethod) == null) {
          EvitaSessionServiceGrpc.getUpdateEntitySchemaMethod = getUpdateEntitySchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateEntitySchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("UpdateEntitySchema"))
              .build();
        }
      }
    }
    return getUpdateEntitySchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> getUpdateAndFetchEntitySchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateAndFetchEntitySchema",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> getUpdateAndFetchEntitySchemaMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> getUpdateAndFetchEntitySchemaMethod;
    if ((getUpdateAndFetchEntitySchemaMethod = EvitaSessionServiceGrpc.getUpdateAndFetchEntitySchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getUpdateAndFetchEntitySchemaMethod = EvitaSessionServiceGrpc.getUpdateAndFetchEntitySchemaMethod) == null) {
          EvitaSessionServiceGrpc.getUpdateAndFetchEntitySchemaMethod = getUpdateAndFetchEntitySchemaMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest, io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateAndFetchEntitySchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("UpdateAndFetchEntitySchema"))
              .build();
        }
      }
    }
    return getUpdateAndFetchEntitySchemaMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> getDeleteCollectionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteCollection",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> getDeleteCollectionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> getDeleteCollectionMethod;
    if ((getDeleteCollectionMethod = EvitaSessionServiceGrpc.getDeleteCollectionMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getDeleteCollectionMethod = EvitaSessionServiceGrpc.getDeleteCollectionMethod) == null) {
          EvitaSessionServiceGrpc.getDeleteCollectionMethod = getDeleteCollectionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteCollection"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("DeleteCollection"))
              .build();
        }
      }
    }
    return getDeleteCollectionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> getRenameCollectionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RenameCollection",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> getRenameCollectionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest, io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> getRenameCollectionMethod;
    if ((getRenameCollectionMethod = EvitaSessionServiceGrpc.getRenameCollectionMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getRenameCollectionMethod = EvitaSessionServiceGrpc.getRenameCollectionMethod) == null) {
          EvitaSessionServiceGrpc.getRenameCollectionMethod = getRenameCollectionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest, io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RenameCollection"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("RenameCollection"))
              .build();
        }
      }
    }
    return getRenameCollectionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> getReplaceCollectionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReplaceCollection",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> getReplaceCollectionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest, io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> getReplaceCollectionMethod;
    if ((getReplaceCollectionMethod = EvitaSessionServiceGrpc.getReplaceCollectionMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getReplaceCollectionMethod = EvitaSessionServiceGrpc.getReplaceCollectionMethod) == null) {
          EvitaSessionServiceGrpc.getReplaceCollectionMethod = getReplaceCollectionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest, io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReplaceCollection"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("ReplaceCollection"))
              .build();
        }
      }
    }
    return getReplaceCollectionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> getGetEntityCollectionSizeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetEntityCollectionSize",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> getGetEntityCollectionSizeMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest, io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> getGetEntityCollectionSizeMethod;
    if ((getGetEntityCollectionSizeMethod = EvitaSessionServiceGrpc.getGetEntityCollectionSizeMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetEntityCollectionSizeMethod = EvitaSessionServiceGrpc.getGetEntityCollectionSizeMethod) == null) {
          EvitaSessionServiceGrpc.getGetEntityCollectionSizeMethod = getGetEntityCollectionSizeMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest, io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetEntityCollectionSize"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetEntityCollectionSize"))
              .build();
        }
      }
    }
    return getGetEntityCollectionSizeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> getUpsertEntityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpsertEntity",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> getUpsertEntityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> getUpsertEntityMethod;
    if ((getUpsertEntityMethod = EvitaSessionServiceGrpc.getUpsertEntityMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getUpsertEntityMethod = EvitaSessionServiceGrpc.getUpsertEntityMethod) == null) {
          EvitaSessionServiceGrpc.getUpsertEntityMethod = getUpsertEntityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpsertEntity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("UpsertEntity"))
              .build();
        }
      }
    }
    return getUpsertEntityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> getDeleteEntityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteEntity",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> getDeleteEntityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> getDeleteEntityMethod;
    if ((getDeleteEntityMethod = EvitaSessionServiceGrpc.getDeleteEntityMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getDeleteEntityMethod = EvitaSessionServiceGrpc.getDeleteEntityMethod) == null) {
          EvitaSessionServiceGrpc.getDeleteEntityMethod = getDeleteEntityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteEntity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("DeleteEntity"))
              .build();
        }
      }
    }
    return getDeleteEntityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> getDeleteEntityAndItsHierarchyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteEntityAndItsHierarchy",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> getDeleteEntityAndItsHierarchyMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> getDeleteEntityAndItsHierarchyMethod;
    if ((getDeleteEntityAndItsHierarchyMethod = EvitaSessionServiceGrpc.getDeleteEntityAndItsHierarchyMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getDeleteEntityAndItsHierarchyMethod = EvitaSessionServiceGrpc.getDeleteEntityAndItsHierarchyMethod) == null) {
          EvitaSessionServiceGrpc.getDeleteEntityAndItsHierarchyMethod = getDeleteEntityAndItsHierarchyMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteEntityAndItsHierarchy"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("DeleteEntityAndItsHierarchy"))
              .build();
        }
      }
    }
    return getDeleteEntityAndItsHierarchyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> getDeleteEntitiesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteEntities",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> getDeleteEntitiesMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> getDeleteEntitiesMethod;
    if ((getDeleteEntitiesMethod = EvitaSessionServiceGrpc.getDeleteEntitiesMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getDeleteEntitiesMethod = EvitaSessionServiceGrpc.getDeleteEntitiesMethod) == null) {
          EvitaSessionServiceGrpc.getDeleteEntitiesMethod = getDeleteEntitiesMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteEntities"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("DeleteEntities"))
              .build();
        }
      }
    }
    return getDeleteEntitiesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse> getArchiveEntityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ArchiveEntity",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse> getArchiveEntityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse> getArchiveEntityMethod;
    if ((getArchiveEntityMethod = EvitaSessionServiceGrpc.getArchiveEntityMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getArchiveEntityMethod = EvitaSessionServiceGrpc.getArchiveEntityMethod) == null) {
          EvitaSessionServiceGrpc.getArchiveEntityMethod = getArchiveEntityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ArchiveEntity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("ArchiveEntity"))
              .build();
        }
      }
    }
    return getArchiveEntityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse> getRestoreEntityMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RestoreEntity",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse> getRestoreEntityMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse> getRestoreEntityMethod;
    if ((getRestoreEntityMethod = EvitaSessionServiceGrpc.getRestoreEntityMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getRestoreEntityMethod = EvitaSessionServiceGrpc.getRestoreEntityMethod) == null) {
          EvitaSessionServiceGrpc.getRestoreEntityMethod = getRestoreEntityMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RestoreEntity"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("RestoreEntity"))
              .build();
        }
      }
    }
    return getRestoreEntityMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse> getGetTransactionIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTransactionId",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse> getGetTransactionIdMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse> getGetTransactionIdMethod;
    if ((getGetTransactionIdMethod = EvitaSessionServiceGrpc.getGetTransactionIdMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetTransactionIdMethod = EvitaSessionServiceGrpc.getGetTransactionIdMethod) == null) {
          EvitaSessionServiceGrpc.getGetTransactionIdMethod = getGetTransactionIdMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTransactionId"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("GetTransactionId"))
              .build();
        }
      }
    }
    return getGetTransactionIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse> getRegisterChangeCatalogCaptureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterChangeCatalogCapture",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse> getRegisterChangeCatalogCaptureMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest, io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse> getRegisterChangeCatalogCaptureMethod;
    if ((getRegisterChangeCatalogCaptureMethod = EvitaSessionServiceGrpc.getRegisterChangeCatalogCaptureMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getRegisterChangeCatalogCaptureMethod = EvitaSessionServiceGrpc.getRegisterChangeCatalogCaptureMethod) == null) {
          EvitaSessionServiceGrpc.getRegisterChangeCatalogCaptureMethod = getRegisterChangeCatalogCaptureMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest, io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterChangeCatalogCapture"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("RegisterChangeCatalogCapture"))
              .build();
        }
      }
    }
    return getRegisterChangeCatalogCaptureMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EvitaSessionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceStub>() {
        @java.lang.Override
        public EvitaSessionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaSessionServiceStub(channel, callOptions);
        }
      };
    return EvitaSessionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static EvitaSessionServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceBlockingV2Stub>() {
        @java.lang.Override
        public EvitaSessionServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaSessionServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return EvitaSessionServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EvitaSessionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceBlockingStub>() {
        @java.lang.Override
        public EvitaSessionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaSessionServiceBlockingStub(channel, callOptions);
        }
      };
    return EvitaSessionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EvitaSessionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaSessionServiceFutureStub>() {
        @java.lang.Override
        public EvitaSessionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaSessionServiceFutureStub(channel, callOptions);
        }
      };
    return EvitaSessionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
   * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
   * evitaDB's implementation a called on an instance of EvitaSessionContract.
   * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
   * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
   * be fetched by specifying a complex queries.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Procedure that returns the current (the one on which the used session operates) catalog schema.
     * </pre>
     */
    default void getCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogSchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the current state of the catalog.
     * </pre>
     */
    default void getCatalogState(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogStateMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the version of the catalog at a specific moment in time.
     * </pre>
     */
    default void getCatalogVersionAt(io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogVersionAtMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns requested page of past mutations in reversed order that match the request criteria.
     * </pre>
     */
    default void getMutationsHistoryPage(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMutationsHistoryPageMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past mutations in reversed order that match the request criteria.
     * </pre>
     */
    default void getMutationsHistory(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMutationsHistoryMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the schema of a specific entity type.
     * </pre>
     */
    default void getEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the list of all entity types.
     * </pre>
     */
    default void getAllEntityTypes(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAllEntityTypesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session.
     * </pre>
     */
    default void goLiveAndClose(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGoLiveAndCloseMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session opening a stream that listens
     * to updates of go live procedure.
     * </pre>
     */
    default void goLiveAndCloseWithProgress(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGoLiveAndCloseWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    default void backupCatalog(io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getBackupCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    default void fullBackupCatalog(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFullBackupCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes the session.
     * </pre>
     */
    default void close(io.evitadb.externalApi.grpc.generated.GrpcCloseRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCloseResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCloseMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes the session opening a stream that listens to transaction processing phases.
     * </pre>
     */
    default void closeWithProgress(io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCloseWithProgressMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns zero or one entity.
     * </pre>
     */
    default void queryOne(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryOneMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a list of entities.
     * </pre>
     */
    default void queryList(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryListMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a data chunk with computed extra results.
     * </pre>
     */
    default void query(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns zero or one entity.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    default void queryOneUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryOneUnsafeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a list of entities.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    default void queryListUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryListUnsafeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a data chunk with computed extra results.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    default void queryUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryUnsafeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that find entity by passed entity type and primary key and return it by specified richness by passed parametrised require query part.
     * </pre>
     */
    default void getEntity(io.evitadb.externalApi.grpc.generated.GrpcEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and return its updated version.
     * </pre>
     */
    default void updateCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateCatalogSchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and returns it.
     * </pre>
     */
    default void updateAndFetchCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateAndFetchCatalogSchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that defines the schema of a new entity type and return it.
     * </pre>
     */
    default void defineEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDefineEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and return its updated version.
     * </pre>
     */
    default void updateEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and returns it.
     * </pre>
     */
    default void updateAndFetchEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateAndFetchEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity collection.
     * </pre>
     */
    default void deleteCollection(io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteCollectionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that renames an entity collection.
     * </pre>
     */
    default void renameCollection(io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRenameCollectionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that replaces an entity collection.
     * </pre>
     */
    default void replaceCollection(io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplaceCollectionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the size of an entity collection.
     * </pre>
     */
    default void getEntityCollectionSize(io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEntityCollectionSizeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that upserts (inserts/updates) an entity and returns it with required richness.
     * </pre>
     */
    default void upsertEntity(io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpsertEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and returns it with required richness.
     * </pre>
     */
    default void deleteEntity(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and its hierarchy and returns the root entity with required richness.
     * </pre>
     */
    default void deleteEntityAndItsHierarchy(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEntityAndItsHierarchyMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes all entities that match the sent query and returns their bodies.
     * </pre>
     */
    default void deleteEntities(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEntitiesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that archives an entity and returns it with required richness.
     * </pre>
     */
    default void archiveEntity(io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getArchiveEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that restores an entity and returns it with required richness.
     * </pre>
     */
    default void restoreEntity(io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRestoreEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that opens a transaction.
     * </pre>
     */
    default void getTransactionId(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTransactionIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that registers a change capture.
     * </pre>
     */
    default void registerChangeCatalogCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterChangeCatalogCaptureMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service EvitaSessionService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
   * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
   * evitaDB's implementation a called on an instance of EvitaSessionContract.
   * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
   * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
   * be fetched by specifying a complex queries.
   * </pre>
   */
  public static abstract class EvitaSessionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EvitaSessionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service EvitaSessionService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
   * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
   * evitaDB's implementation a called on an instance of EvitaSessionContract.
   * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
   * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
   * be fetched by specifying a complex queries.
   * </pre>
   */
  public static final class EvitaSessionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<EvitaSessionServiceStub> {
    private EvitaSessionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaSessionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaSessionServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns the current (the one on which the used session operates) catalog schema.
     * </pre>
     */
    public void getCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogSchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the current state of the catalog.
     * </pre>
     */
    public void getCatalogState(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogStateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the version of the catalog at a specific moment in time.
     * </pre>
     */
    public void getCatalogVersionAt(io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogVersionAtMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns requested page of past mutations in reversed order that match the request criteria.
     * </pre>
     */
    public void getMutationsHistoryPage(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMutationsHistoryPageMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past mutations in reversed order that match the request criteria.
     * </pre>
     */
    public void getMutationsHistory(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetMutationsHistoryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the schema of a specific entity type.
     * </pre>
     */
    public void getEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetEntitySchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the list of all entity types.
     * </pre>
     */
    public void getAllEntityTypes(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetAllEntityTypesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session.
     * </pre>
     */
    public void goLiveAndClose(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGoLiveAndCloseMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session opening a stream that listens
     * to updates of go live procedure.
     * </pre>
     */
    public void goLiveAndCloseWithProgress(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGoLiveAndCloseWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public void backupCatalog(io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getBackupCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public void fullBackupCatalog(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getFullBackupCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes the session.
     * </pre>
     */
    public void close(io.evitadb.externalApi.grpc.generated.GrpcCloseRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCloseResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCloseMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes the session opening a stream that listens to transaction processing phases.
     * </pre>
     */
    public void closeWithProgress(io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getCloseWithProgressMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns zero or one entity.
     * </pre>
     */
    public void queryOne(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQueryOneMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a list of entities.
     * </pre>
     */
    public void queryList(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQueryListMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a data chunk with computed extra results.
     * </pre>
     */
    public void query(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQueryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns zero or one entity.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public void queryOneUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQueryOneUnsafeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a list of entities.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public void queryListUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQueryListUnsafeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a data chunk with computed extra results.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public void queryUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQueryUnsafeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that find entity by passed entity type and primary key and return it by specified richness by passed parametrised require query part.
     * </pre>
     */
    public void getEntity(io.evitadb.externalApi.grpc.generated.GrpcEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetEntityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and return its updated version.
     * </pre>
     */
    public void updateCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateCatalogSchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and returns it.
     * </pre>
     */
    public void updateAndFetchCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateAndFetchCatalogSchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that defines the schema of a new entity type and return it.
     * </pre>
     */
    public void defineEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDefineEntitySchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and return its updated version.
     * </pre>
     */
    public void updateEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateEntitySchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and returns it.
     * </pre>
     */
    public void updateAndFetchEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateAndFetchEntitySchemaMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity collection.
     * </pre>
     */
    public void deleteCollection(io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteCollectionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that renames an entity collection.
     * </pre>
     */
    public void renameCollection(io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRenameCollectionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that replaces an entity collection.
     * </pre>
     */
    public void replaceCollection(io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReplaceCollectionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the size of an entity collection.
     * </pre>
     */
    public void getEntityCollectionSize(io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetEntityCollectionSizeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that upserts (inserts/updates) an entity and returns it with required richness.
     * </pre>
     */
    public void upsertEntity(io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpsertEntityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and returns it with required richness.
     * </pre>
     */
    public void deleteEntity(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteEntityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and its hierarchy and returns the root entity with required richness.
     * </pre>
     */
    public void deleteEntityAndItsHierarchy(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteEntityAndItsHierarchyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes all entities that match the sent query and returns their bodies.
     * </pre>
     */
    public void deleteEntities(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteEntitiesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that archives an entity and returns it with required richness.
     * </pre>
     */
    public void archiveEntity(io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getArchiveEntityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that restores an entity and returns it with required richness.
     * </pre>
     */
    public void restoreEntity(io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRestoreEntityMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that opens a transaction.
     * </pre>
     */
    public void getTransactionId(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTransactionIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that registers a change capture.
     * </pre>
     */
    public void registerChangeCatalogCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getRegisterChangeCatalogCaptureMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service EvitaSessionService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
   * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
   * evitaDB's implementation a called on an instance of EvitaSessionContract.
   * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
   * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
   * be fetched by specifying a complex queries.
   * </pre>
   */
  public static final class EvitaSessionServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<EvitaSessionServiceBlockingV2Stub> {
    private EvitaSessionServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaSessionServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaSessionServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns the current (the one on which the used session operates) catalog schema.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse getCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetCatalogSchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the current state of the catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse getCatalogState(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetCatalogStateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the version of the catalog at a specific moment in time.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse getCatalogVersionAt(io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetCatalogVersionAtMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns requested page of past mutations in reversed order that match the request criteria.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse getMutationsHistoryPage(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetMutationsHistoryPageMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past mutations in reversed order that match the request criteria.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse>
        getMutationsHistory(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getGetMutationsHistoryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the schema of a specific entity type.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse getEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the list of all entity types.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse getAllEntityTypes(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetAllEntityTypesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse goLiveAndClose(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGoLiveAndCloseMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session opening a stream that listens
     * to updates of go live procedure.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse>
        goLiveAndCloseWithProgress(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getGoLiveAndCloseWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse backupCatalog(io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getBackupCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse fullBackupCatalog(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getFullBackupCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that closes the session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCloseResponse close(io.evitadb.externalApi.grpc.generated.GrpcCloseRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCloseMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that closes the session opening a stream that listens to transaction processing phases.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse>
        closeWithProgress(io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getCloseWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns zero or one entity.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse queryOne(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQueryOneMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a list of entities.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse queryList(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQueryListMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a data chunk with computed extra results.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryResponse query(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQueryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns zero or one entity.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse queryOneUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQueryOneUnsafeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a list of entities.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse queryListUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQueryListUnsafeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a data chunk with computed extra results.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryResponse queryUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getQueryUnsafeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that find entity by passed entity type and primary key and return it by specified richness by passed parametrised require query part.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityResponse getEntity(io.evitadb.externalApi.grpc.generated.GrpcEntityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and return its updated version.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse updateCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateCatalogSchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and returns it.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse updateAndFetchCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateAndFetchCatalogSchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that defines the schema of a new entity type and return it.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse defineEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDefineEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and return its updated version.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse updateEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and returns it.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse updateAndFetchEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpdateAndFetchEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse deleteCollection(io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteCollectionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that renames an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse renameCollection(io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRenameCollectionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that replaces an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse replaceCollection(io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getReplaceCollectionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the size of an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse getEntityCollectionSize(io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetEntityCollectionSizeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that upserts (inserts/updates) an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse upsertEntity(io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUpsertEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse deleteEntity(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and its hierarchy and returns the root entity with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse deleteEntityAndItsHierarchy(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteEntityAndItsHierarchyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes all entities that match the sent query and returns their bodies.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse deleteEntities(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeleteEntitiesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that archives an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse archiveEntity(io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getArchiveEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that restores an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse restoreEntity(io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRestoreEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that opens a transaction.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse getTransactionId(com.google.protobuf.Empty request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getGetTransactionIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that registers a change capture.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse>
        registerChangeCatalogCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getRegisterChangeCatalogCaptureMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service EvitaSessionService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
   * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
   * evitaDB's implementation a called on an instance of EvitaSessionContract.
   * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
   * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
   * be fetched by specifying a complex queries.
   * </pre>
   */
  public static final class EvitaSessionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EvitaSessionServiceBlockingStub> {
    private EvitaSessionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaSessionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaSessionServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns the current (the one on which the used session operates) catalog schema.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse getCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogSchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the current state of the catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse getCatalogState(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogStateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the version of the catalog at a specific moment in time.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse getCatalogVersionAt(io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogVersionAtMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns requested page of past mutations in reversed order that match the request criteria.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse getMutationsHistoryPage(io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMutationsHistoryPageMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns stream of all past mutations in reversed order that match the request criteria.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse> getMutationsHistory(
        io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetMutationsHistoryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the schema of a specific entity type.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse getEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the list of all entity types.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse getAllEntityTypes(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetAllEntityTypesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse goLiveAndClose(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGoLiveAndCloseMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session opening a stream that listens
     * to updates of go live procedure.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse> goLiveAndCloseWithProgress(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGoLiveAndCloseWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse backupCatalog(io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getBackupCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse fullBackupCatalog(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getFullBackupCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that closes the session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCloseResponse close(io.evitadb.externalApi.grpc.generated.GrpcCloseRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCloseMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that closes the session opening a stream that listens to transaction processing phases.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse> closeWithProgress(
        io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getCloseWithProgressMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns zero or one entity.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse queryOne(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQueryOneMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a list of entities.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse queryList(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQueryListMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a data chunk with computed extra results.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryResponse query(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQueryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns zero or one entity.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse queryOneUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQueryOneUnsafeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a list of entities.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse queryListUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQueryListUnsafeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a data chunk with computed extra results.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcQueryResponse queryUnsafe(io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQueryUnsafeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that find entity by passed entity type and primary key and return it by specified richness by passed parametrised require query part.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityResponse getEntity(io.evitadb.externalApi.grpc.generated.GrpcEntityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and return its updated version.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse updateCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateCatalogSchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and returns it.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse updateAndFetchCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateAndFetchCatalogSchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that defines the schema of a new entity type and return it.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse defineEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDefineEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and return its updated version.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse updateEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and returns it.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse updateAndFetchEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateAndFetchEntitySchemaMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse deleteCollection(io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteCollectionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that renames an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse renameCollection(io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRenameCollectionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that replaces an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse replaceCollection(io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReplaceCollectionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that returns the size of an entity collection.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse getEntityCollectionSize(io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetEntityCollectionSizeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that upserts (inserts/updates) an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse upsertEntity(io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpsertEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse deleteEntity(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and its hierarchy and returns the root entity with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse deleteEntityAndItsHierarchy(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteEntityAndItsHierarchyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that deletes all entities that match the sent query and returns their bodies.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse deleteEntities(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteEntitiesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that archives an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse archiveEntity(io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getArchiveEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that restores an entity and returns it with required richness.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse restoreEntity(io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRestoreEntityMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that opens a transaction.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse getTransactionId(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTransactionIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that registers a change capture.
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse> registerChangeCatalogCapture(
        io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getRegisterChangeCatalogCaptureMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service EvitaSessionService.
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB's catalog by usage of a before created session.
   * By specifying its UUID and the name of a catalog to which it corresponds to it's possible to execute methods that in
   * evitaDB's implementation a called on an instance of EvitaSessionContract.
   * Main purpose of this service is to provide a way to manipulate with stored entity collections and their schemas. That
   * includes their creating, updating and deleting. Same operations could be done with entities, which in addition could
   * be fetched by specifying a complex queries.
   * </pre>
   */
  public static final class EvitaSessionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<EvitaSessionServiceFutureStub> {
    private EvitaSessionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaSessionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaSessionServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure that returns the current (the one on which the used session operates) catalog schema.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getCatalogSchema(
        io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogSchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns the current state of the catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> getCatalogState(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogStateMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns the version of the catalog at a specific moment in time.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse> getCatalogVersionAt(
        io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogVersionAtMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns requested page of past mutations in reversed order that match the request criteria.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse> getMutationsHistoryPage(
        io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMutationsHistoryPageMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns the schema of a specific entity type.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> getEntitySchema(
        io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetEntitySchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns the list of all entity types.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> getAllEntityTypes(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetAllEntityTypesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> goLiveAndClose(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGoLiveAndCloseMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse> backupCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getBackupCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to backup an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse> fullBackupCatalog(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getFullBackupCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that closes the session.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCloseResponse> close(
        io.evitadb.externalApi.grpc.generated.GrpcCloseRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCloseMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns zero or one entity.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> queryOne(
        io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQueryOneMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a list of entities.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> queryList(
        io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQueryListMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a data chunk with computed extra results.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> query(
        io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQueryMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns zero or one entity.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> queryOneUnsafe(
        io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQueryOneUnsafeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a list of entities.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> queryListUnsafe(
        io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQueryListUnsafeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that executes passed query with embedded variables and returns a data chunk with computed extra results.
     * Do not use in your applications! This method is unsafe and should be used only for internal purposes.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> queryUnsafe(
        io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQueryUnsafeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that find entity by passed entity type and primary key and return it by specified richness by passed parametrised require query part.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> getEntity(
        io.evitadb.externalApi.grpc.generated.GrpcEntityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetEntityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and return its updated version.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> updateCatalogSchema(
        io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateCatalogSchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and returns it.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> updateAndFetchCatalogSchema(
        io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateAndFetchCatalogSchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that defines the schema of a new entity type and return it.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> defineEntitySchema(
        io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDefineEntitySchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and return its updated version.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> updateEntitySchema(
        io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateEntitySchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and returns it.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> updateAndFetchEntitySchema(
        io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateAndFetchEntitySchemaMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity collection.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> deleteCollection(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteCollectionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that renames an entity collection.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> renameCollection(
        io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRenameCollectionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that replaces an entity collection.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> replaceCollection(
        io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReplaceCollectionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that returns the size of an entity collection.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> getEntityCollectionSize(
        io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetEntityCollectionSizeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that upserts (inserts/updates) an entity and returns it with required richness.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> upsertEntity(
        io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpsertEntityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and returns it with required richness.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> deleteEntity(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteEntityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and its hierarchy and returns the root entity with required richness.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> deleteEntityAndItsHierarchy(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteEntityAndItsHierarchyMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that deletes all entities that match the sent query and returns their bodies.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> deleteEntities(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteEntitiesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that archives an entity and returns it with required richness.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse> archiveEntity(
        io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getArchiveEntityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that restores an entity and returns it with required richness.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse> restoreEntity(
        io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRestoreEntityMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that opens a transaction.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse> getTransactionId(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTransactionIdMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CATALOG_SCHEMA = 0;
  private static final int METHODID_GET_CATALOG_STATE = 1;
  private static final int METHODID_GET_CATALOG_VERSION_AT = 2;
  private static final int METHODID_GET_MUTATIONS_HISTORY_PAGE = 3;
  private static final int METHODID_GET_MUTATIONS_HISTORY = 4;
  private static final int METHODID_GET_ENTITY_SCHEMA = 5;
  private static final int METHODID_GET_ALL_ENTITY_TYPES = 6;
  private static final int METHODID_GO_LIVE_AND_CLOSE = 7;
  private static final int METHODID_GO_LIVE_AND_CLOSE_WITH_PROGRESS = 8;
  private static final int METHODID_BACKUP_CATALOG = 9;
  private static final int METHODID_FULL_BACKUP_CATALOG = 10;
  private static final int METHODID_CLOSE = 11;
  private static final int METHODID_CLOSE_WITH_PROGRESS = 12;
  private static final int METHODID_QUERY_ONE = 13;
  private static final int METHODID_QUERY_LIST = 14;
  private static final int METHODID_QUERY = 15;
  private static final int METHODID_QUERY_ONE_UNSAFE = 16;
  private static final int METHODID_QUERY_LIST_UNSAFE = 17;
  private static final int METHODID_QUERY_UNSAFE = 18;
  private static final int METHODID_GET_ENTITY = 19;
  private static final int METHODID_UPDATE_CATALOG_SCHEMA = 20;
  private static final int METHODID_UPDATE_AND_FETCH_CATALOG_SCHEMA = 21;
  private static final int METHODID_DEFINE_ENTITY_SCHEMA = 22;
  private static final int METHODID_UPDATE_ENTITY_SCHEMA = 23;
  private static final int METHODID_UPDATE_AND_FETCH_ENTITY_SCHEMA = 24;
  private static final int METHODID_DELETE_COLLECTION = 25;
  private static final int METHODID_RENAME_COLLECTION = 26;
  private static final int METHODID_REPLACE_COLLECTION = 27;
  private static final int METHODID_GET_ENTITY_COLLECTION_SIZE = 28;
  private static final int METHODID_UPSERT_ENTITY = 29;
  private static final int METHODID_DELETE_ENTITY = 30;
  private static final int METHODID_DELETE_ENTITY_AND_ITS_HIERARCHY = 31;
  private static final int METHODID_DELETE_ENTITIES = 32;
  private static final int METHODID_ARCHIVE_ENTITY = 33;
  private static final int METHODID_RESTORE_ENTITY = 34;
  private static final int METHODID_GET_TRANSACTION_ID = 35;
  private static final int METHODID_REGISTER_CHANGE_CATALOG_CAPTURE = 36;

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
        case METHODID_GET_CATALOG_SCHEMA:
          serviceImpl.getCatalogSchema((io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse>) responseObserver);
          break;
        case METHODID_GET_CATALOG_STATE:
          serviceImpl.getCatalogState((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse>) responseObserver);
          break;
        case METHODID_GET_CATALOG_VERSION_AT:
          serviceImpl.getCatalogVersionAt((io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse>) responseObserver);
          break;
        case METHODID_GET_MUTATIONS_HISTORY_PAGE:
          serviceImpl.getMutationsHistoryPage((io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse>) responseObserver);
          break;
        case METHODID_GET_MUTATIONS_HISTORY:
          serviceImpl.getMutationsHistory((io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse>) responseObserver);
          break;
        case METHODID_GET_ENTITY_SCHEMA:
          serviceImpl.getEntitySchema((io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse>) responseObserver);
          break;
        case METHODID_GET_ALL_ENTITY_TYPES:
          serviceImpl.getAllEntityTypes((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse>) responseObserver);
          break;
        case METHODID_GO_LIVE_AND_CLOSE:
          serviceImpl.goLiveAndClose((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse>) responseObserver);
          break;
        case METHODID_GO_LIVE_AND_CLOSE_WITH_PROGRESS:
          serviceImpl.goLiveAndCloseWithProgress((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse>) responseObserver);
          break;
        case METHODID_BACKUP_CATALOG:
          serviceImpl.backupCatalog((io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse>) responseObserver);
          break;
        case METHODID_FULL_BACKUP_CATALOG:
          serviceImpl.fullBackupCatalog((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse>) responseObserver);
          break;
        case METHODID_CLOSE:
          serviceImpl.close((io.evitadb.externalApi.grpc.generated.GrpcCloseRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCloseResponse>) responseObserver);
          break;
        case METHODID_CLOSE_WITH_PROGRESS:
          serviceImpl.closeWithProgress((io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse>) responseObserver);
          break;
        case METHODID_QUERY_ONE:
          serviceImpl.queryOne((io.evitadb.externalApi.grpc.generated.GrpcQueryRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>) responseObserver);
          break;
        case METHODID_QUERY_LIST:
          serviceImpl.queryList((io.evitadb.externalApi.grpc.generated.GrpcQueryRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>) responseObserver);
          break;
        case METHODID_QUERY:
          serviceImpl.query((io.evitadb.externalApi.grpc.generated.GrpcQueryRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>) responseObserver);
          break;
        case METHODID_QUERY_ONE_UNSAFE:
          serviceImpl.queryOneUnsafe((io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>) responseObserver);
          break;
        case METHODID_QUERY_LIST_UNSAFE:
          serviceImpl.queryListUnsafe((io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>) responseObserver);
          break;
        case METHODID_QUERY_UNSAFE:
          serviceImpl.queryUnsafe((io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>) responseObserver);
          break;
        case METHODID_GET_ENTITY:
          serviceImpl.getEntity((io.evitadb.externalApi.grpc.generated.GrpcEntityRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityResponse>) responseObserver);
          break;
        case METHODID_UPDATE_CATALOG_SCHEMA:
          serviceImpl.updateCatalogSchema((io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse>) responseObserver);
          break;
        case METHODID_UPDATE_AND_FETCH_CATALOG_SCHEMA:
          serviceImpl.updateAndFetchCatalogSchema((io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse>) responseObserver);
          break;
        case METHODID_DEFINE_ENTITY_SCHEMA:
          serviceImpl.defineEntitySchema((io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse>) responseObserver);
          break;
        case METHODID_UPDATE_ENTITY_SCHEMA:
          serviceImpl.updateEntitySchema((io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse>) responseObserver);
          break;
        case METHODID_UPDATE_AND_FETCH_ENTITY_SCHEMA:
          serviceImpl.updateAndFetchEntitySchema((io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse>) responseObserver);
          break;
        case METHODID_DELETE_COLLECTION:
          serviceImpl.deleteCollection((io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse>) responseObserver);
          break;
        case METHODID_RENAME_COLLECTION:
          serviceImpl.renameCollection((io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse>) responseObserver);
          break;
        case METHODID_REPLACE_COLLECTION:
          serviceImpl.replaceCollection((io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse>) responseObserver);
          break;
        case METHODID_GET_ENTITY_COLLECTION_SIZE:
          serviceImpl.getEntityCollectionSize((io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse>) responseObserver);
          break;
        case METHODID_UPSERT_ENTITY:
          serviceImpl.upsertEntity((io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse>) responseObserver);
          break;
        case METHODID_DELETE_ENTITY:
          serviceImpl.deleteEntity((io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse>) responseObserver);
          break;
        case METHODID_DELETE_ENTITY_AND_ITS_HIERARCHY:
          serviceImpl.deleteEntityAndItsHierarchy((io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse>) responseObserver);
          break;
        case METHODID_DELETE_ENTITIES:
          serviceImpl.deleteEntities((io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse>) responseObserver);
          break;
        case METHODID_ARCHIVE_ENTITY:
          serviceImpl.archiveEntity((io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse>) responseObserver);
          break;
        case METHODID_RESTORE_ENTITY:
          serviceImpl.restoreEntity((io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse>) responseObserver);
          break;
        case METHODID_GET_TRANSACTION_ID:
          serviceImpl.getTransactionId((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse>) responseObserver);
          break;
        case METHODID_REGISTER_CHANGE_CATALOG_CAPTURE:
          serviceImpl.registerChangeCatalogCapture((io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse>) responseObserver);
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
          getGetCatalogSchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcGetCatalogSchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse>(
                service, METHODID_GET_CATALOG_SCHEMA)))
        .addMethod(
          getGetCatalogStateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse>(
                service, METHODID_GET_CATALOG_STATE)))
        .addMethod(
          getGetCatalogVersionAtMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtRequest,
              io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse>(
                service, METHODID_GET_CATALOG_VERSION_AT)))
        .addMethod(
          getGetMutationsHistoryPageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest,
              io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageResponse>(
                service, METHODID_GET_MUTATIONS_HISTORY_PAGE)))
        .addMethod(
          getGetMutationsHistoryMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest,
              io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse>(
                service, METHODID_GET_MUTATIONS_HISTORY)))
        .addMethod(
          getGetEntitySchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse>(
                service, METHODID_GET_ENTITY_SCHEMA)))
        .addMethod(
          getGetAllEntityTypesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse>(
                service, METHODID_GET_ALL_ENTITY_TYPES)))
        .addMethod(
          getGoLiveAndCloseMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse>(
                service, METHODID_GO_LIVE_AND_CLOSE)))
        .addMethod(
          getGoLiveAndCloseWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse>(
                service, METHODID_GO_LIVE_AND_CLOSE_WITH_PROGRESS)))
        .addMethod(
          getBackupCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest,
              io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogResponse>(
                service, METHODID_BACKUP_CATALOG)))
        .addMethod(
          getFullBackupCatalogMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcFullBackupCatalogResponse>(
                service, METHODID_FULL_BACKUP_CATALOG)))
        .addMethod(
          getCloseMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcCloseRequest,
              io.evitadb.externalApi.grpc.generated.GrpcCloseResponse>(
                service, METHODID_CLOSE)))
        .addMethod(
          getCloseWithProgressMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressRequest,
              io.evitadb.externalApi.grpc.generated.GrpcCloseWithProgressResponse>(
                service, METHODID_CLOSE_WITH_PROGRESS)))
        .addMethod(
          getQueryOneMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
              io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>(
                service, METHODID_QUERY_ONE)))
        .addMethod(
          getQueryListMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
              io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>(
                service, METHODID_QUERY_LIST)))
        .addMethod(
          getQueryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
              io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>(
                service, METHODID_QUERY)))
        .addMethod(
          getQueryOneUnsafeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
              io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>(
                service, METHODID_QUERY_ONE_UNSAFE)))
        .addMethod(
          getQueryListUnsafeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
              io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>(
                service, METHODID_QUERY_LIST_UNSAFE)))
        .addMethod(
          getQueryUnsafeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcQueryUnsafeRequest,
              io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>(
                service, METHODID_QUERY_UNSAFE)))
        .addMethod(
          getGetEntityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEntityRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEntityResponse>(
                service, METHODID_GET_ENTITY)))
        .addMethod(
          getUpdateCatalogSchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse>(
                service, METHODID_UPDATE_CATALOG_SCHEMA)))
        .addMethod(
          getUpdateAndFetchCatalogSchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse>(
                service, METHODID_UPDATE_AND_FETCH_CATALOG_SCHEMA)))
        .addMethod(
          getDefineEntitySchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse>(
                service, METHODID_DEFINE_ENTITY_SCHEMA)))
        .addMethod(
          getUpdateEntitySchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse>(
                service, METHODID_UPDATE_ENTITY_SCHEMA)))
        .addMethod(
          getUpdateAndFetchEntitySchemaMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
              io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse>(
                service, METHODID_UPDATE_AND_FETCH_ENTITY_SCHEMA)))
        .addMethod(
          getDeleteCollectionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse>(
                service, METHODID_DELETE_COLLECTION)))
        .addMethod(
          getRenameCollectionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse>(
                service, METHODID_RENAME_COLLECTION)))
        .addMethod(
          getReplaceCollectionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest,
              io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse>(
                service, METHODID_REPLACE_COLLECTION)))
        .addMethod(
          getGetEntityCollectionSizeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest,
              io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse>(
                service, METHODID_GET_ENTITY_COLLECTION_SIZE)))
        .addMethod(
          getUpsertEntityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest,
              io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse>(
                service, METHODID_UPSERT_ENTITY)))
        .addMethod(
          getDeleteEntityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse>(
                service, METHODID_DELETE_ENTITY)))
        .addMethod(
          getDeleteEntityAndItsHierarchyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse>(
                service, METHODID_DELETE_ENTITY_AND_ITS_HIERARCHY)))
        .addMethod(
          getDeleteEntitiesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest,
              io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse>(
                service, METHODID_DELETE_ENTITIES)))
        .addMethod(
          getArchiveEntityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityRequest,
              io.evitadb.externalApi.grpc.generated.GrpcArchiveEntityResponse>(
                service, METHODID_ARCHIVE_ENTITY)))
        .addMethod(
          getRestoreEntityMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityRequest,
              io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse>(
                service, METHODID_RESTORE_ENTITY)))
        .addMethod(
          getGetTransactionIdMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              io.evitadb.externalApi.grpc.generated.GrpcTransactionResponse>(
                service, METHODID_GET_TRANSACTION_ID)))
        .addMethod(
          getRegisterChangeCatalogCaptureMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest,
              io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse>(
                service, METHODID_REGISTER_CHANGE_CATALOG_CAPTURE)))
        .build();
  }

  private static abstract class EvitaSessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EvitaSessionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EvitaSessionService");
    }
  }

  private static final class EvitaSessionServiceFileDescriptorSupplier
      extends EvitaSessionServiceBaseDescriptorSupplier {
    EvitaSessionServiceFileDescriptorSupplier() {}
  }

  private static final class EvitaSessionServiceMethodDescriptorSupplier
      extends EvitaSessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EvitaSessionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (EvitaSessionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EvitaSessionServiceFileDescriptorSupplier())
              .addMethod(getGetCatalogSchemaMethod())
              .addMethod(getGetCatalogStateMethod())
              .addMethod(getGetCatalogVersionAtMethod())
              .addMethod(getGetMutationsHistoryPageMethod())
              .addMethod(getGetMutationsHistoryMethod())
              .addMethod(getGetEntitySchemaMethod())
              .addMethod(getGetAllEntityTypesMethod())
              .addMethod(getGoLiveAndCloseMethod())
              .addMethod(getGoLiveAndCloseWithProgressMethod())
              .addMethod(getBackupCatalogMethod())
              .addMethod(getFullBackupCatalogMethod())
              .addMethod(getCloseMethod())
              .addMethod(getCloseWithProgressMethod())
              .addMethod(getQueryOneMethod())
              .addMethod(getQueryListMethod())
              .addMethod(getQueryMethod())
              .addMethod(getQueryOneUnsafeMethod())
              .addMethod(getQueryListUnsafeMethod())
              .addMethod(getQueryUnsafeMethod())
              .addMethod(getGetEntityMethod())
              .addMethod(getUpdateCatalogSchemaMethod())
              .addMethod(getUpdateAndFetchCatalogSchemaMethod())
              .addMethod(getDefineEntitySchemaMethod())
              .addMethod(getUpdateEntitySchemaMethod())
              .addMethod(getUpdateAndFetchEntitySchemaMethod())
              .addMethod(getDeleteCollectionMethod())
              .addMethod(getRenameCollectionMethod())
              .addMethod(getReplaceCollectionMethod())
              .addMethod(getGetEntityCollectionSizeMethod())
              .addMethod(getUpsertEntityMethod())
              .addMethod(getDeleteEntityMethod())
              .addMethod(getDeleteEntityAndItsHierarchyMethod())
              .addMethod(getDeleteEntitiesMethod())
              .addMethod(getArchiveEntityMethod())
              .addMethod(getRestoreEntityMethod())
              .addMethod(getGetTransactionIdMethod())
              .addMethod(getRegisterChangeCatalogCaptureMethod())
              .build();
        }
      }
    }
    return result;
  }
}
