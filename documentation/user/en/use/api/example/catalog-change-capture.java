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

    // subscribe one or more subscribers to the same publisher
	changePublisher.subscribe(
	    new Flow.Subscriber<>() {
	        private Flow.Subscription subscription;

	        @Override
	        public void onSubscribe(Flow.Subscription subscription) {
	            this.subscription = subscription;
	            // request the first item
	            subscription.request(1);
	        }

	        @Override
	        public void onNext(ChangeCatalogCapture item) {
	            System.out.println("Catalog CDC event: " + item);
	            // request the next item
	            subscription.request(1);
	        }

	        @Override
	        public void onError(Throwable onError) {
	            System.err.println(
					"Error in catalog CDC subscription: " + onError.getMessage()
	            );
	        }

	        @Override
	        public void onComplete() {
	            System.out.println("Catalog CDC subscription completed.");
	        }
	    }
	);
}