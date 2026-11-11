// Written by SampleCardDocumentTest (jvmTest) from Gate A's sample through portal-ingest's ED0 flag. Do not edit:
// run :portal-compose-render:jvmTest -Ped0.updateGolden=true and review the diff.
package dev.keliver.portal.compose.render

internal const val SAMPLE_CARD_DOCUMENT: String = """{
    "kind": "dev.keliver.portal.document.DocNode.Widget",
    "handle": -14,
    "type": "dev.keliver.compose.foundation.layout.Column#4",
    "props": {
        "verticalArrangement": {
            "kind": "dev.keliver.portal.document.PropValue.Lit",
            "tag": "c",
            "s": "{\"sp\":8}"
        }
    },
    "modifiers": {
        "0:fillMaxWidth#9": {
            "kind": "dev.keliver.portal.document.PropValue.Lit",
            "tag": "b",
            "b": true
        },
        "1:padding#1": {
            "kind": "dev.keliver.portal.document.PropValue.Lit",
            "tag": "b",
            "b": true
        },
        "1:padding#1.all": {
            "kind": "dev.keliver.portal.document.PropValue.Lit",
            "tag": "c",
            "s": "16"
        }
    },
    "children": [
        {
            "kind": "dev.keliver.portal.document.DocNode.Widget",
            "handle": -8,
            "type": "dev.keliver.compose.foundation.layout.Row#3",
            "props": {
                "verticalAlignment": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "0.0"
                }
            },
            "modifiers": {
                "0:fillMaxWidth#9": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "1:shadow#25": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "1:shadow#25.elevation": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "1"
                },
                "1:shadow#25.shape": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "{\"rr\":[12,12,12,12]}"
                },
                "1:shadow#25.clip": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": false
                },
                "2:clip#23": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "2:clip#23.shape": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "{\"rr\":[12,12,12,12]}"
                },
                "3:background#18": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "3:background#18.color": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "4293390591"
                },
                "4:border#19": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "4:border#19.width": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "1"
                },
                "4:border#19.color": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "4293783021"
                },
                "4:border#19.shape": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "{\"rr\":[12,12,12,12]}"
                },
                "5:clickable#20": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "5:clickable#20.onClick": {
                    "kind": "dev.keliver.portal.document.PropValue.Action",
                    "name": "onOpen"
                },
                "6:padding#2": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "6:padding#2.horizontal": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "16"
                },
                "6:padding#2.vertical": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "12"
                }
            },
            "children": [
                {
                    "kind": "dev.keliver.portal.document.DocNode.Widget",
                    "handle": -2,
                    "type": "dev.keliver.compose.foundation.layout.Box#1",
                    "props": {
                        "contentAlignment": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "c",
                            "s": "[0.0,0.0]"
                        }
                    },
                    "modifiers": {
                        "0:size#5": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "b",
                            "b": true
                        },
                        "0:size#5.size": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "c",
                            "s": "36"
                        },
                        "1:clip#23": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "b",
                            "b": true
                        },
                        "1:clip#23.shape": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "c",
                            "s": "{\"rr\":[8,8,8,8]}"
                        },
                        "2:background#18": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "b",
                            "b": true
                        },
                        "2:background#18.color": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "c",
                            "s": "4282492574"
                        }
                    },
                    "children": [
                        {
                            "kind": "dev.keliver.portal.document.DocNode.Widget",
                            "handle": -1,
                            "type": "dev.keliver.compose.material3.Icon#7",
                            "props": {
                                "imageVector": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "\"Filled.Search\""
                                },
                                "contentDescription": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "null"
                                },
                                "tint": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "4294967295"
                                }
                            },
                            "modifiers": {
                                "0:size#5": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "b",
                                    "b": true
                                },
                                "0:size#5.size": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "22"
                                }
                            }
                        }
                    ]
                },
                {
                    "kind": "dev.keliver.portal.document.DocNode.Widget",
                    "handle": -3,
                    "type": "dev.keliver.compose.foundation.layout.Spacer#5",
                    "modifiers": {
                        "0:width#7": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "b",
                            "b": true
                        },
                        "0:width#7.width": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "c",
                            "s": "12"
                        }
                    }
                },
                {
                    "kind": "dev.keliver.portal.document.DocNode.Widget",
                    "handle": -6,
                    "type": "dev.keliver.compose.foundation.layout.Column#4",
                    "modifiers": {
                        "0:weight#12": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "b",
                            "b": true
                        },
                        "0:weight#12.weight": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "d",
                            "d": 1.0
                        }
                    },
                    "children": [
                        {
                            "kind": "dev.keliver.portal.document.DocNode.Widget",
                            "handle": -4,
                            "type": "dev.keliver.compose.material3.Text#6",
                            "props": {
                                "text": {
                                    "kind": "dev.keliver.portal.document.PropValue.Bind",
                                    "field": "title"
                                },
                                "color": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "4280229663"
                                },
                                "fontSize": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "\"16sp\""
                                },
                                "fontWeight": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "600"
                                },
                                "overflow": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "2"
                                },
                                "maxLines": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "i",
                                    "i": 1
                                }
                            }
                        },
                        {
                            "kind": "dev.keliver.portal.document.DocNode.Widget",
                            "handle": -5,
                            "type": "dev.keliver.compose.material3.Text#6",
                            "props": {
                                "text": {
                                    "kind": "dev.keliver.portal.document.PropValue.Bind",
                                    "field": "rate"
                                },
                                "color": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "{\"ref\":\"colorScheme.primary\"}"
                                },
                                "fontSize": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "\"12sp\""
                                },
                                "style": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "{\"ref\":\"LocalTextStyle\",\"set\":{\"letterSpacing\":\"1sp\"}}"
                                }
                            }
                        }
                    ]
                },
                {
                    "kind": "dev.keliver.portal.document.DocNode.Widget",
                    "handle": -7,
                    "type": "dev.keliver.compose.material3.Icon#7",
                    "props": {
                        "imageVector": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "c",
                            "s": "\"Filled.ChevronRight\""
                        },
                        "contentDescription": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "s",
                            "s": "Open"
                        }
                    }
                }
            ]
        },
        {
            "kind": "dev.keliver.portal.document.DocNode.Widget",
            "handle": -12,
            "type": "dev.keliver.compose.foundation.layout.FlowRow#9",
            "props": {
                "horizontalArrangement": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "{\"sp\":6}"
                },
                "verticalArrangement": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "{\"sp\":6}"
                }
            },
            "children": [
                {
                    "kind": "dev.keliver.portal.document.DocNode.Widget",
                    "handle": -11,
                    "type": "Repeat",
                    "props": {
                        "items": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "s",
                            "s": "tags"
                        },
                        "item": {
                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                            "tag": "s",
                            "s": "tag"
                        }
                    },
                    "children": [
                        {
                            "kind": "dev.keliver.portal.document.DocNode.Widget",
                            "handle": -10,
                            "type": "dev.keliver.compose.foundation.layout.Box#1",
                            "modifiers": {
                                "0:clip#23": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "b",
                                    "b": true
                                },
                                "0:clip#23.shape": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "{\"rr\":[\"50%\",\"50%\",\"50%\",\"50%\"]}"
                                },
                                "1:background#18": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "b",
                                    "b": true
                                },
                                "1:background#18.color": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "{\"ref\":\"colorScheme.secondaryContainer\"}"
                                },
                                "2:padding#2": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "b",
                                    "b": true
                                },
                                "2:padding#2.horizontal": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "10"
                                },
                                "2:padding#2.vertical": {
                                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                                    "tag": "c",
                                    "s": "4"
                                }
                            },
                            "children": [
                                {
                                    "kind": "dev.keliver.portal.document.DocNode.Widget",
                                    "handle": -9,
                                    "type": "dev.keliver.compose.material3.Text#6",
                                    "props": {
                                        "text": {
                                            "kind": "dev.keliver.portal.document.PropValue.Bind",
                                            "field": "tag"
                                        },
                                        "fontSize": {
                                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                                            "tag": "c",
                                            "s": "\"12sp\""
                                        },
                                        "textAlign": {
                                            "kind": "dev.keliver.portal.document.PropValue.Lit",
                                            "tag": "c",
                                            "s": "3"
                                        }
                                    }
                                }
                            ]
                        }
                    ]
                }
            ]
        },
        {
            "kind": "dev.keliver.portal.document.DocNode.Widget",
            "handle": -13,
            "type": "dev.keliver.compose.foundation.layout.Spacer#5",
            "modifiers": {
                "0:height#8": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "b",
                    "b": true
                },
                "0:height#8.height": {
                    "kind": "dev.keliver.portal.document.PropValue.Lit",
                    "tag": "c",
                    "s": "4"
                }
            }
        }
    ]
}"""
