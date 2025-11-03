// open a read-only session to access the catalog
try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
	// retrieve change history from the catalog
	final ChangeCapturePublisher<ChangeCatalogCapture> changePublisher =
		session.registerChangeCatalogCapture(
			ChangeCatalogCaptureRequest.builder()
				// capture both schema and data changes
				.criteria(
					// capture all schema changes
					ChangeCatalogCaptureCriteria.builder()
						.infrastructureArea()
						.build(),
					// capture entity data changes in Product entity collection only
					ChangeCatalogCaptureCriteria.builder()
						.dataArea(
							filterBy -> filterBy.entityType("Product")
								.containerType(ContainerType.ENTITY)
								.build()
						)
						.build()
				)
				// include full mutation bodies
				.content(ChangeCaptureContent.BODY)
				.build()
		);
}