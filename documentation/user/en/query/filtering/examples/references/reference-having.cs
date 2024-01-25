EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"relatedProducts",
        			AttributeEquals("category", "alternativeProduct")
        		)
        	),
        	Require(
        		EntityFetch(
        			ReferenceContentWithAttributes(
        				"relatedProducts",
        				AttributeContent("category")
        			)
        		)
        	)
        )
	)
);