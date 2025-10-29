// open a read-only session to access the catalog
try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
    // retrieve change history from the catalog
    final Stream<ChangeCatalogCapture> changeStream = session.getMutationsHistory(
        ChangeCatalogCaptureRequest.builder()
            // capture both schema and data changes
            .criteria(
                // capture all schema changes
                ChangeCatalogCaptureCriteria.builder()
                    .schemaArea()
                    .build(),
                // capture all data changes
                ChangeCatalogCaptureCriteria.builder()
                    .dataArea()
                    .build()
            )
            // include full mutation bodies
            .content(ChangeCaptureContent.BODY)
            .build()
    );

    // process the change stream
    changeStream.forEach(capture -> {
        System.out.println(
            "Catalog CDC event [version=" + capture.version() +
            ", index=" + capture.index() +
            ", area=" + capture.area() +
            ", entityType=" + capture.entityType() +
            ", operation=" + capture.operation() +
            "]"
        );
    });
}
