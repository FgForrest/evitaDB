EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("code", "samsung-galaxy-watch-4")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContent(
        				"groups",
        				EntityFetch(
        					AttributeContent("code"),
        					ReferenceContent(
        						"tags",
        						EntityFetch(
        							AttributeContent("code"),
        							ReferenceContent("categories")
        						)
        					)
        				)
        			)
        		)
        	)
        )
	)
);