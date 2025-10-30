// register for system-wide change capture
final ChangeCapturePublisher<ChangeSystemCapture> changePublisher =
	evita.registerSystemChangeCapture(
	    // subscribe to all system changes
	    ChangeSystemCaptureRequest.builder()
	        // with all available content
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
        public void onNext(ChangeSystemCapture item) {
            System.out.println("System CDC event: " + item);
            // request the next item
            subscription.request(1);
        }

        @Override
        public void onError(Throwable onError) {
            System.err.println(
				"Error in system CDC subscription: " + onError.getMessage()
            );
        }

        @Override
        public void onComplete() {
            System.out.println("System CDC subscription completed.");
        }
    }
);