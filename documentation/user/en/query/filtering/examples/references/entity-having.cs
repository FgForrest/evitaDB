EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"brand",
        			EntityHaving(
        				AttributeEquals("code", "apple")
        			)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContent(
        				"brand",
        				EntityFetch(
        					AttributeContent("code")
        				)
        			)
        		)
        	)
        )
	)
);