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

  public static final String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.EvitaSessionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getGetCatalogSchemaMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogSchema",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getGetCatalogSchemaMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> getGetCatalogSchemaMethod;
    if ((getGetCatalogSchemaMethod = EvitaSessionServiceGrpc.getGetCatalogSchemaMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getGetCatalogSchemaMethod = EvitaSessionServiceGrpc.getGetCatalogSchemaMethod) == null) {
          EvitaSessionServiceGrpc.getGetCatalogSchemaMethod = getGetCatalogSchemaMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogSchema"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
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
      com.google.protobuf.Empty> getCloseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Close",
      requestType = com.google.protobuf.Empty.class,
      responseType = com.google.protobuf.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      com.google.protobuf.Empty> getCloseMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, com.google.protobuf.Empty> getCloseMethod;
    if ((getCloseMethod = EvitaSessionServiceGrpc.getCloseMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getCloseMethod = EvitaSessionServiceGrpc.getCloseMethod) == null) {
          EvitaSessionServiceGrpc.getCloseMethod = getCloseMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, com.google.protobuf.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Close"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("Close"))
              .build();
        }
      }
    }
    return getCloseMethod;
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

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse> getOpenTransactionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "OpenTransaction",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse> getOpenTransactionMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse> getOpenTransactionMethod;
    if ((getOpenTransactionMethod = EvitaSessionServiceGrpc.getOpenTransactionMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getOpenTransactionMethod = EvitaSessionServiceGrpc.getOpenTransactionMethod) == null) {
          EvitaSessionServiceGrpc.getOpenTransactionMethod = getOpenTransactionMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "OpenTransaction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("OpenTransaction"))
              .build();
        }
      }
    }
    return getOpenTransactionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest,
      com.google.protobuf.Empty> getCloseTransactionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CloseTransaction",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest.class,
      responseType = com.google.protobuf.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest,
      com.google.protobuf.Empty> getCloseTransactionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest, com.google.protobuf.Empty> getCloseTransactionMethod;
    if ((getCloseTransactionMethod = EvitaSessionServiceGrpc.getCloseTransactionMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getCloseTransactionMethod = EvitaSessionServiceGrpc.getCloseTransactionMethod) == null) {
          EvitaSessionServiceGrpc.getCloseTransactionMethod = getCloseTransactionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest, com.google.protobuf.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CloseTransaction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("CloseTransaction"))
              .build();
        }
      }
    }
    return getCloseTransactionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse> getRegisterChangeDataCaptureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterChangeDataCapture",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse> getRegisterChangeDataCaptureMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest, io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse> getRegisterChangeDataCaptureMethod;
    if ((getRegisterChangeDataCaptureMethod = EvitaSessionServiceGrpc.getRegisterChangeDataCaptureMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getRegisterChangeDataCaptureMethod = EvitaSessionServiceGrpc.getRegisterChangeDataCaptureMethod) == null) {
          EvitaSessionServiceGrpc.getRegisterChangeDataCaptureMethod = getRegisterChangeDataCaptureMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest, io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterChangeDataCapture"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("RegisterChangeDataCapture"))
              .build();
        }
      }
    }
    return getRegisterChangeDataCaptureMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest,
      com.google.protobuf.Empty> getUnregisterChangeDataCaptureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnregisterChangeDataCapture",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest.class,
      responseType = com.google.protobuf.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest,
      com.google.protobuf.Empty> getUnregisterChangeDataCaptureMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest, com.google.protobuf.Empty> getUnregisterChangeDataCaptureMethod;
    if ((getUnregisterChangeDataCaptureMethod = EvitaSessionServiceGrpc.getUnregisterChangeDataCaptureMethod) == null) {
      synchronized (EvitaSessionServiceGrpc.class) {
        if ((getUnregisterChangeDataCaptureMethod = EvitaSessionServiceGrpc.getUnregisterChangeDataCaptureMethod) == null) {
          EvitaSessionServiceGrpc.getUnregisterChangeDataCaptureMethod = getUnregisterChangeDataCaptureMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest, com.google.protobuf.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnregisterChangeDataCapture"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaSessionServiceMethodDescriptorSupplier("UnregisterChangeDataCapture"))
              .build();
        }
      }
    }
    return getUnregisterChangeDataCaptureMethod;
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
  public static abstract class EvitaSessionServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Procedure that returns the current (the one on which the used session operates) catalog schema.
     * </pre>
     */
    public void getCatalogSchema(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogSchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the current state of the catalog.
     * </pre>
     */
    public void getCatalogState(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogStateMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the schema of a specific entity type.
     * </pre>
     */
    public void getEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the list of all entity types.
     * </pre>
     */
    public void getAllEntityTypes(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAllEntityTypesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that changes the state of the catalog to ALIVE and closes the session.
     * </pre>
     */
    public void goLiveAndClose(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGoLiveAndCloseMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes the session.
     * </pre>
     */
    public void close(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCloseMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns zero or one entity.
     * </pre>
     */
    public void queryOne(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryOneMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a list of entities.
     * </pre>
     */
    public void queryList(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryListMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that executes passed parametrised query and returns a data chunk with computed extra results.
     * </pre>
     */
    public void query(io.evitadb.externalApi.grpc.generated.GrpcQueryRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcQueryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQueryMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that find entity by passed entity type and primary key and return it by specified richness by passed parametrised require query part.
     * </pre>
     */
    public void getEntity(io.evitadb.externalApi.grpc.generated.GrpcEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and return its updated version.
     * </pre>
     */
    public void updateCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateCatalogSchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the catalog schema and returns it.
     * </pre>
     */
    public void updateAndFetchCatalogSchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateAndFetchCatalogSchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that defines the schema of a new entity type and return it.
     * </pre>
     */
    public void defineEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDefineEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and return its updated version.
     * </pre>
     */
    public void updateEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that updates the schema of an existing entity type and returns it.
     * </pre>
     */
    public void updateAndFetchEntitySchema(io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateAndFetchEntitySchemaMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity collection.
     * </pre>
     */
    public void deleteCollection(io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteCollectionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that renames an entity collection.
     * </pre>
     */
    public void renameCollection(io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRenameCollectionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that replaces an entity collection.
     * </pre>
     */
    public void replaceCollection(io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplaceCollectionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that returns the size of an entity collection.
     * </pre>
     */
    public void getEntityCollectionSize(io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetEntityCollectionSizeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that upserts (inserts/updates) an entity and returns it with required richness.
     * </pre>
     */
    public void upsertEntity(io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpsertEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and returns it with required richness.
     * </pre>
     */
    public void deleteEntity(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEntityMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes an entity and its hierarchy and returns the root entity with required richness.
     * </pre>
     */
    public void deleteEntityAndItsHierarchy(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEntityAndItsHierarchyMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that deletes all entities that match the sent query and returns their bodies.
     * </pre>
     */
    public void deleteEntities(io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteEntitiesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that opens a transaction.
     * </pre>
     */
    public void openTransaction(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getOpenTransactionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes a transaction.
     * </pre>
     */
    public void closeTransaction(io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCloseTransactionMethod(), responseObserver);
    }

    /**
     */
    public void registerChangeDataCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterChangeDataCaptureMethod(), responseObserver);
    }

    /**
     */
    public void unregisterChangeDataCapture(io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnregisterChangeDataCaptureMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getGetCatalogSchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse>(
                  this, METHODID_GET_CATALOG_SCHEMA)))
          .addMethod(
            getGetCatalogStateMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse>(
                  this, METHODID_GET_CATALOG_STATE)))
          .addMethod(
            getGetEntitySchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse>(
                  this, METHODID_GET_ENTITY_SCHEMA)))
          .addMethod(
            getGetAllEntityTypesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse>(
                  this, METHODID_GET_ALL_ENTITY_TYPES)))
          .addMethod(
            getGoLiveAndCloseMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseResponse>(
                  this, METHODID_GO_LIVE_AND_CLOSE)))
          .addMethod(
            getCloseMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                com.google.protobuf.Empty>(
                  this, METHODID_CLOSE)))
          .addMethod(
            getQueryOneMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
                io.evitadb.externalApi.grpc.generated.GrpcQueryOneResponse>(
                  this, METHODID_QUERY_ONE)))
          .addMethod(
            getQueryListMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
                io.evitadb.externalApi.grpc.generated.GrpcQueryListResponse>(
                  this, METHODID_QUERY_LIST)))
          .addMethod(
            getQueryMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcQueryRequest,
                io.evitadb.externalApi.grpc.generated.GrpcQueryResponse>(
                  this, METHODID_QUERY)))
          .addMethod(
            getGetEntityMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEntityRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEntityResponse>(
                  this, METHODID_GET_ENTITY)))
          .addMethod(
            getUpdateCatalogSchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
                io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaResponse>(
                  this, METHODID_UPDATE_CATALOG_SCHEMA)))
          .addMethod(
            getUpdateAndFetchCatalogSchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest,
                io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchCatalogSchemaResponse>(
                  this, METHODID_UPDATE_AND_FETCH_CATALOG_SCHEMA)))
          .addMethod(
            getDefineEntitySchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDefineEntitySchemaResponse>(
                  this, METHODID_DEFINE_ENTITY_SCHEMA)))
          .addMethod(
            getUpdateEntitySchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
                io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaResponse>(
                  this, METHODID_UPDATE_ENTITY_SCHEMA)))
          .addMethod(
            getUpdateAndFetchEntitySchemaMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest,
                io.evitadb.externalApi.grpc.generated.GrpcUpdateAndFetchEntitySchemaResponse>(
                  this, METHODID_UPDATE_AND_FETCH_ENTITY_SCHEMA)))
          .addMethod(
            getDeleteCollectionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteCollectionResponse>(
                  this, METHODID_DELETE_COLLECTION)))
          .addMethod(
            getRenameCollectionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRenameCollectionResponse>(
                  this, METHODID_RENAME_COLLECTION)))
          .addMethod(
            getReplaceCollectionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcReplaceCollectionResponse>(
                  this, METHODID_REPLACE_COLLECTION)))
          .addMethod(
            getGetEntityCollectionSizeMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionSizeResponse>(
                  this, METHODID_GET_ENTITY_COLLECTION_SIZE)))
          .addMethod(
            getUpsertEntityMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityRequest,
                io.evitadb.externalApi.grpc.generated.GrpcUpsertEntityResponse>(
                  this, METHODID_UPSERT_ENTITY)))
          .addMethod(
            getDeleteEntityMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityResponse>(
                  this, METHODID_DELETE_ENTITY)))
          .addMethod(
            getDeleteEntityAndItsHierarchyMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse>(
                  this, METHODID_DELETE_ENTITY_AND_ITS_HIERARCHY)))
          .addMethod(
            getDeleteEntitiesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteEntitiesResponse>(
                  this, METHODID_DELETE_ENTITIES)))
          .addMethod(
            getOpenTransactionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse>(
                  this, METHODID_OPEN_TRANSACTION)))
          .addMethod(
            getCloseTransactionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest,
                com.google.protobuf.Empty>(
                  this, METHODID_CLOSE_TRANSACTION)))
          .addMethod(
            getRegisterChangeDataCaptureMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse>(
                  this, METHODID_REGISTER_CHANGE_DATA_CAPTURE)))
          .addMethod(
            getUnregisterChangeDataCaptureMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest,
                com.google.protobuf.Empty>(
                  this, METHODID_UNREGISTER_CHANGE_DATA_CAPTURE)))
          .build();
    }
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
  public static final class EvitaSessionServiceStub extends io.grpc.stub.AbstractAsyncStub<EvitaSessionServiceStub> {
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
    public void getCatalogSchema(com.google.protobuf.Empty request,
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
     * Procedure that closes the session.
     * </pre>
     */
    public void close(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCloseMethod(), getCallOptions()), request, responseObserver);
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
     * Procedure that opens a transaction.
     * </pre>
     */
    public void openTransaction(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getOpenTransactionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure that closes a transaction.
     * </pre>
     */
    public void closeTransaction(io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCloseTransactionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void registerChangeDataCapture(io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getRegisterChangeDataCaptureMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void unregisterChangeDataCapture(io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnregisterChangeDataCaptureMethod(), getCallOptions()), request, responseObserver);
    }
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
  public static final class EvitaSessionServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<EvitaSessionServiceBlockingStub> {
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
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse getCatalogSchema(com.google.protobuf.Empty request) {
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
     * Procedure that closes the session.
     * </pre>
     */
    public com.google.protobuf.Empty close(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCloseMethod(), getCallOptions(), request);
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
     * Procedure that opens a transaction.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse openTransaction(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getOpenTransactionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure that closes a transaction.
     * </pre>
     */
    public com.google.protobuf.Empty closeTransaction(io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCloseTransactionMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse> registerChangeDataCapture(
        io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getRegisterChangeDataCaptureMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.google.protobuf.Empty unregisterChangeDataCapture(io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnregisterChangeDataCaptureMethod(), getCallOptions(), request);
    }
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
  public static final class EvitaSessionServiceFutureStub extends io.grpc.stub.AbstractFutureStub<EvitaSessionServiceFutureStub> {
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
        com.google.protobuf.Empty request) {
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
     * Procedure that closes the session.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> close(
        com.google.protobuf.Empty request) {
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
     * Procedure that opens a transaction.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse> openTransaction(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getOpenTransactionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure that closes a transaction.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> closeTransaction(
        io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCloseTransactionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> unregisterChangeDataCapture(
        io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnregisterChangeDataCaptureMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CATALOG_SCHEMA = 0;
  private static final int METHODID_GET_CATALOG_STATE = 1;
  private static final int METHODID_GET_ENTITY_SCHEMA = 2;
  private static final int METHODID_GET_ALL_ENTITY_TYPES = 3;
  private static final int METHODID_GO_LIVE_AND_CLOSE = 4;
  private static final int METHODID_CLOSE = 5;
  private static final int METHODID_QUERY_ONE = 6;
  private static final int METHODID_QUERY_LIST = 7;
  private static final int METHODID_QUERY = 8;
  private static final int METHODID_GET_ENTITY = 9;
  private static final int METHODID_UPDATE_CATALOG_SCHEMA = 10;
  private static final int METHODID_UPDATE_AND_FETCH_CATALOG_SCHEMA = 11;
  private static final int METHODID_DEFINE_ENTITY_SCHEMA = 12;
  private static final int METHODID_UPDATE_ENTITY_SCHEMA = 13;
  private static final int METHODID_UPDATE_AND_FETCH_ENTITY_SCHEMA = 14;
  private static final int METHODID_DELETE_COLLECTION = 15;
  private static final int METHODID_RENAME_COLLECTION = 16;
  private static final int METHODID_REPLACE_COLLECTION = 17;
  private static final int METHODID_GET_ENTITY_COLLECTION_SIZE = 18;
  private static final int METHODID_UPSERT_ENTITY = 19;
  private static final int METHODID_DELETE_ENTITY = 20;
  private static final int METHODID_DELETE_ENTITY_AND_ITS_HIERARCHY = 21;
  private static final int METHODID_DELETE_ENTITIES = 22;
  private static final int METHODID_OPEN_TRANSACTION = 23;
  private static final int METHODID_CLOSE_TRANSACTION = 24;
  private static final int METHODID_REGISTER_CHANGE_DATA_CAPTURE = 25;
  private static final int METHODID_UNREGISTER_CHANGE_DATA_CAPTURE = 26;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final EvitaSessionServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(EvitaSessionServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_CATALOG_SCHEMA:
          serviceImpl.getCatalogSchema((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaResponse>) responseObserver);
          break;
        case METHODID_GET_CATALOG_STATE:
          serviceImpl.getCatalogState((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogStateResponse>) responseObserver);
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
        case METHODID_CLOSE:
          serviceImpl.close((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
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
        case METHODID_OPEN_TRANSACTION:
          serviceImpl.openTransaction((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse>) responseObserver);
          break;
        case METHODID_CLOSE_TRANSACTION:
          serviceImpl.closeTransaction((io.evitadb.externalApi.grpc.generated.GrpcCloseTransactionRequest) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
          break;
        case METHODID_REGISTER_CHANGE_DATA_CAPTURE:
          serviceImpl.registerChangeDataCapture((io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeDataCaptureResponse>) responseObserver);
          break;
        case METHODID_UNREGISTER_CHANGE_DATA_CAPTURE:
          serviceImpl.unregisterChangeDataCapture((io.evitadb.externalApi.grpc.generated.GrpcUnregisterChangeDataCaptureRequest) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
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
    private final String methodName;

    EvitaSessionServiceMethodDescriptorSupplier(String methodName) {
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
              .addMethod(getGetEntitySchemaMethod())
              .addMethod(getGetAllEntityTypesMethod())
              .addMethod(getGoLiveAndCloseMethod())
              .addMethod(getCloseMethod())
              .addMethod(getQueryOneMethod())
              .addMethod(getQueryListMethod())
              .addMethod(getQueryMethod())
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
              .addMethod(getOpenTransactionMethod())
              .addMethod(getCloseTransactionMethod())
              .addMethod(getRegisterChangeDataCaptureMethod())
              .addMethod(getUnregisterChangeDataCaptureMethod())
              .build();
        }
      }
    }
    return result;
  }
}
