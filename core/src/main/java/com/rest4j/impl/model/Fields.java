//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, vhudson-jaxb-ri-2.1-2 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2013.07.13 at 11:56:40 PM MSK 
//


package com.rest4j.impl.model;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for Fields complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="Fields">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence maxOccurs="unbounded" minOccurs="0">
 *         &lt;element name="simple" type="{http://rest4j.com/api-description}SimpleField" maxOccurs="unbounded" minOccurs="0"/>
 *         &lt;element name="complex" type="{http://rest4j.com/api-description}ComplexField" maxOccurs="unbounded" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "Fields", propOrder = {
    "simpleAndComplex"
})
public class Fields {

    @XmlElements({
        @XmlElement(name = "complex", type = ComplexField.class),
        @XmlElement(name = "simple", type = SimpleField.class)
    })
    protected List<Field> simpleAndComplex;

    /**
     * Gets the value of the simpleAndComplex property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the simpleAndComplex property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getSimpleAndComplex().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link ComplexField }
     * {@link SimpleField }
     * 
     * 
     */
    public List<Field> getSimpleAndComplex() {
        if (simpleAndComplex == null) {
            simpleAndComplex = new ArrayList<Field>();
        }
        return this.simpleAndComplex;
    }

}