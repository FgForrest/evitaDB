EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(103885)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContent(
        				"parameterValues",
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