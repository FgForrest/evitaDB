```json
[
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
          "primaryKey" : 66480,
          "parent" : 66468,
          "attributes" : {
            "code" : "christmas-electronics"
          }
        }
      },
      {
        "entity" : {
          "primaryKey" : 66481,
          "parent" : 66468,
          "attributes" : {
            "code" : "smart-wearable"
          }
        },
        "children" : [
          {
            "entity" : {
              "primaryKey" : 66486,
              "parent" : 66481,
              "attributes" : {
                "code" : "smartwatches"
              }
            }
          },
          {
            "entity" : {
              "primaryKey" : 66487,
              "parent" : 66481,
              "attributes" : {
                "code" : "smartglasses"
              }
            }
          },
          {
            "entity" : {
              "primaryKey" : 108126,
              "parent" : 66481,
              "attributes" : {
                "code" : "bands"
              }
            }
          }
        ]
      },
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
      },
      {
        "entity" : {
          "primaryKey" : 66483,
          "parent" : 66468,
          "attributes" : {
            "code" : "monitors"
          }
        }
      },
      {
        "entity" : {
          "primaryKey" : 66484,
          "parent" : 66468,
          "attributes" : {
            "code" : "keyboards"
          }
        },
        "children" : [
          {
            "entity" : {
              "primaryKey" : 66492,
              "parent" : 66484,
              "attributes" : {
                "code" : "cz-keyboards"
              }
            }
          }
        ]
      },
      {
        "entity" : {
          "primaryKey" : 66537,
          "parent" : 66468,
          "attributes" : {
            "code" : "mouses"
          }
        }
      }
    ]
  }
]
```