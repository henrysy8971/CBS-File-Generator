@javax.xml.bind.annotation.XmlSchema(
		namespace = "http://www.example.com/order",
		elementFormDefault = javax.xml.bind.annotation.XmlNsForm.QUALIFIED,
		xmlns = {
				@javax.xml.bind.annotation.XmlNs(prefix = "tns", namespaceURI = "http://www.example.com/order")
		}
)
package com.silverlakesymmetri.cbs.fileGenerator.dto.order;