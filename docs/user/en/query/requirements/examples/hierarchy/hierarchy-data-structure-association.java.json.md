```json
{
  "selfHierarchy" : {
    "directChildren" : [
      {
        "entity" : {
          "primaryKey" : 66482,
          "parent" : 66468,
          "attributes" : {
            "code" : "audio"
          }
        },
        "children" : [
          {
            "entity" : {
              "primaryKey" : 66488,
              "parent" : 66482,
              "attributes" : {
                "code" : "wireless-headphones"
              }
            }
          },
          {
            "entity" : {
              "primaryKey" : 66489,
              "parent" : 66482,
              "attributes" : {
                "code" : "wired-heaphones"
              }
            }
          },
          {
            "entity" : {
              "primaryKey" : 66490,
              "parent" : 66482,
              "attributes" : {
                "code" : "microphones"
              }
            }
          },
          {
            "entity" : {
              "primaryKey" : 66491,
              "parent" : 66482,
              "attributes" : {
                "code" : "repro"
              }
            }
          }
        ]
      }
    ],
    "directParent" : [
      {
        "entity" : {
          "primaryKey" : 66468,
          "attributes" : {
            "code" : "accessories"
          }
        },
        "children" : [
          {
            "entity" : {
              "primaryKey" : 66482,
              "parent" : 66468,
              "attributes" : {
                "code" : "audio"
              }
            }
          }
        ]
      }
    ]
  }
}
```