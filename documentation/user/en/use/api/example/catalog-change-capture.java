/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

// open a read-only session to access the catalog
try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
    // retrieve change history from the catalog
    final ChangeCapturePublisher<ChangeCatalogCapture> changePublisher = session.registerChangeCatalogCapture(
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
	        public void onError(Throwable throwable) {
	            System.err.println("Error in catalog CDC subscription: " + throwable.getMessage());
	        }

	        @Override
	        public void onComplete() {
	            System.out.println("Catalog CDC subscription completed.");
	        }
	    }
	);
}
