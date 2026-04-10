EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"brand",
        			GroupHaving(
        				AttributeEquals("code", "TODO JNO")
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
        				),
        				EntityGroupFetch(
        					AttributeContent("code")
        				)
        			)
        		)
        	)
        )
	)
);