// open a read-only session to access the catalog
try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
	// retrieve change history from the catalog
	final ChangeCapturePublisher<ChangeCatalogCapture> changePublisher =
		session.registerChangeCatalogCapture(
			ChangeCatalogCaptureRequest.builder()
				// capture both schema and data changes
				.criteria(
					// capture entity data changes in Product entity collection only
					ChangeCatalogCaptureCriteria.builder()
						.dataArea(
							filterBy -> filterBy.entityType("Product")
								.entityPrimaryKey(745)
								.containerType(ContainerType.ATTRIBUTE)
								.containerName("quantityOnStock")
								.build()
						)
						.build()
				)
				// include full mutation bodies
				.content(ChangeCaptureContent.BODY)
				.build()
		);
}