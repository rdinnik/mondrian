/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2003-2010 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.xmla;

import mondrian.calc.ResultStyle;
import mondrian.olap.*;
import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.rolap.*;
import mondrian.rolap.agg.CellRequest;
import mondrian.spi.CatalogLocator;
import mondrian.spi.Dialect;
import mondrian.xmla.impl.DefaultSaxWriter;
import static mondrian.xmla.XmlaConstants.*;

import static org.olap4j.metadata.XmlaConstants.*;

import org.apache.log4j.Logger;
import org.xml.sax.SAXException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.io.*;

/**
 * An <code>XmlaHandler</code> responds to XML for Analysis (XML/A) requests.
 *
 * @author jhyde, Gang Chen
 * @version $Id$
 * @since 27 April, 2003
 */
public class XmlaHandler {
    private static final Logger LOGGER = Logger.getLogger(XmlaHandler.class);

    private final Map<String, DataSourcesConfig.DataSource> dataSourcesMap;
    private final CatalogLocator catalogLocator;
    private final String prefix;

    private enum SetType {
        ROW_SET,
        MD_DATA_SET
    }

    private static final String EMPTY_ROW_SET_XML_SCHEMA =
        computeEmptyXsd(SetType.ROW_SET);

    private static final String MD_DATA_SET_XML_SCHEMA =
        computeXsd(SetType.MD_DATA_SET);

    private static final String EMPTY_MD_DATA_SET_XML_SCHEMA =
        computeEmptyXsd(SetType.MD_DATA_SET);

    private static final String NS_XML_SQL =
        "urn:schemas-microsoft-com:xml-sql";

    //
    // Some xml schema data types.
    //
    public static final String XSD_BOOLEAN = "xsd:boolean";
    public static final String XSD_STRING = "xsd:string";
    public static final String XSD_UNSIGNED_INT = "xsd:unsignedInt";

    public static final String XSD_BYTE = "xsd:byte";
    public static final byte XSD_BYTE_MAX_INCLUSIVE = 127;
    public static final byte XSD_BYTE_MIN_INCLUSIVE = -128;

    public static final String XSD_SHORT = "xsd:short";
    public static final short XSD_SHORT_MAX_INCLUSIVE = 32767;
    public static final short XSD_SHORT_MIN_INCLUSIVE = -32768;

    public static final String XSD_INT = "xsd:int";
    public static final int XSD_INT_MAX_INCLUSIVE = 2147483647;
    public static final int XSD_INT_MIN_INCLUSIVE = -2147483648;

    public static final String XSD_LONG = "xsd:long";
    public static final long XSD_LONG_MAX_INCLUSIVE = 9223372036854775807L;
    public static final long XSD_LONG_MIN_INCLUSIVE = -9223372036854775808L;

    // xsd:double: IEEE 64-bit floating-point
    public static final String XSD_DOUBLE = "xsd:double";

    // xsd:decimal: Decimal numbers (BigDecimal)
    public static final String XSD_DECIMAL = "xsd:decimal";

    // xsd:integer: Signed integers of arbitrary length (BigInteger)
    public static final String XSD_INTEGER = "xsd:integer";

    public static boolean isValidXsdInt(long l) {
        return (l <= XSD_INT_MAX_INCLUSIVE) && (l >= XSD_INT_MIN_INCLUSIVE);
    }

    /**
     * Takes a DataType String (null, Integer, Numeric or non-null)
     * and Value Object (Integer, Double, String, other) and
     * canonicalizes them to XSD data type and corresponding object.
     * <p>
     * If the input DataType is Integer, then it attempts to return
     * an XSD_INT with value java.lang.Integer (and failing that an
     * XSD_LONG (java.lang.Long) or XSD_INTEGER (java.math.BigInteger)).
     * Worst case is the value loses precision with any integral
     * representation and must be returned as a decimal type (Double
     * or java.math.BigDecimal).
     * <p>
     * If the input DataType is Decimal, then it attempts to return
     * an XSD_DOUBLE with value java.lang.Double (and failing that an
     * XSD_DECIMAL (java.math.BigDecimal)).
     */
    static class ValueInfo {

        /**
         * Returns XSD_INT, XSD_DOUBLE, XSD_STRING or null.
         *
         * @param dataType null, Integer, Numeric or non-null.
         * @return Returns the suggested XSD type for a given datatype
         */
        static String getValueTypeHint(final String dataType) {
            if (dataType != null) {
                return (dataType.equals("Integer"))
                    ? XSD_INT
                    : ((dataType.equals("Numeric"))
                        ? XSD_DOUBLE
                        : XSD_STRING);
            } else {
                return null;
            }
        }

        String valueType;
        Object value;
        boolean isDecimal;

        ValueInfo(final String dataType, final Object inputValue) {
            final String valueTypeHint = getValueTypeHint(dataType);

            // This is a hint: should it be a string, integer or decimal type.
            // In the following, if the hint is integer, then there is
            // an attempt that the value types
            // be XSD_INT, XST_LONG, or XSD_INTEGER (but they could turn
            // out to be XSD_DOUBLE or XSD_DECIMAL if precision is loss
            // with the integral formats). It the hint is a decimal type
            // (double, float, decimal), then a XSD_DOUBLE or XSD_DECIMAL
            // is returned.
            if (valueTypeHint != null) {
                // The value type is a hint. If the value can be
                // converted to the data type without precision loss, ok;
                // otherwise value data type must be adjusted.

                if (valueTypeHint.equals(XSD_STRING)) {
                    // For String types, nothing to do.
                    this.valueType = valueTypeHint;
                    this.value = inputValue;
                    this.isDecimal = false;

                } else if (valueTypeHint.equals(XSD_INT)) {
                    // If valueTypeHint is XSD_INT, then see if value can be
                    // converted to (first choice) integer, (second choice),
                    // long and (last choice) BigInteger - otherwise must
                    // use double/decimal.

                    // Most of the time value ought to be an Integer so
                    // try it first
                    if (inputValue instanceof Integer) {
                        // For integer, its already the right type
                        this.valueType = valueTypeHint;
                        this.value = inputValue;
                        this.isDecimal = false;

                    } else if (inputValue instanceof Byte) {
                        this.valueType = valueTypeHint;
                        this.value = inputValue;
                        this.isDecimal = false;

                    } else if (inputValue instanceof Short) {
                        this.valueType = valueTypeHint;
                        this.value = inputValue;
                        this.isDecimal = false;

                    } else if (inputValue instanceof Long) {
                        // See if it can be an integer or long
                        long lval = (Long) inputValue;
                        setValueAndType(lval);

                    } else if (inputValue instanceof BigInteger) {
                        BigInteger bi = (BigInteger) inputValue;
                        // See if it can be an integer or long
                        long lval = bi.longValue();
                        if (bi.equals(BigInteger.valueOf(lval))) {
                            // It can be converted from BigInteger to long
                            // without loss of precision.
                            setValueAndType(lval);
                        } else {
                            // It can not be converted to a long.
                            this.valueType = XSD_INTEGER;
                            this.value = inputValue;
                            this.isDecimal = false;
                        }

                    } else if (inputValue instanceof Float) {
                        Float f = (Float) inputValue;
                        // See if it can be an integer or long
                        long lval = f.longValue();
                        if (f.equals(new Float(lval))) {
                            // It can be converted from double to long
                            // without loss of precision.
                            setValueAndType(lval);

                        } else {
                            // It can not be converted to a long.
                            this.valueType = XSD_DOUBLE;
                            this.value = inputValue;
                            this.isDecimal = true;
                        }

                    } else if (inputValue instanceof Double) {
                        Double d = (Double) inputValue;
                        // See if it can be an integer or long
                        long lval = d.longValue();
                        if (d.equals(new Double(lval))) {
                            // It can be converted from double to long
                            // without loss of precision.
                            setValueAndType(lval);

                        } else {
                            // It can not be converted to a long.
                            this.valueType = XSD_DOUBLE;
                            this.value = inputValue;
                            this.isDecimal = true;
                        }

                    } else if (inputValue instanceof BigDecimal) {
                        // See if it can be an integer or long
                        BigDecimal bd = (BigDecimal) inputValue;
                        try {
                            // Can it be converted to a long
                            // Throws ArithmeticException on conversion failure.
                            // The following line is only available in
                            // Java5 and above:
                            //long lval = bd.longValueExact();
                            long lval = bd.longValue();

                            setValueAndType(lval);
                        } catch (ArithmeticException ex) {
                            // No, it can not be converted to long

                            try {
                                // Can it be an integer
                                BigInteger bi = bd.toBigIntegerExact();
                                this.valueType = XSD_INTEGER;
                                this.value = bi;
                                this.isDecimal = false;
                            } catch (ArithmeticException ex1) {
                                // OK, its a decimal
                                this.valueType = XSD_DECIMAL;
                                this.value = inputValue;
                                this.isDecimal = true;
                            }
                        }

                    } else if (inputValue instanceof Number) {
                        // Don't know what Number type we have here.
                        // Note: this could result in precision loss.
                        this.value = ((Number) inputValue).longValue();
                        this.valueType = valueTypeHint;
                        this.isDecimal = false;

                    } else {
                        // Who knows what we are dealing with,
                        // hope for the best?!?
                        this.valueType = valueTypeHint;
                        this.value = inputValue;
                        this.isDecimal = false;
                    }

                } else if (valueTypeHint.equals(XSD_DOUBLE)) {
                    // The desired type is double.

                    // Most of the time value ought to be an Double so
                    // try it first
                    if (inputValue instanceof Double) {
                        // For Double, its already the right type
                        this.valueType = valueTypeHint;
                        this.value = inputValue;
                        this.isDecimal = true;

                    } else if (inputValue instanceof Byte
                        || inputValue instanceof Short
                        || inputValue instanceof Integer
                        || inputValue instanceof Long)
                    {
                        // Convert from byte/short/integer/long to double
                        this.value = ((Number) inputValue).doubleValue();
                        this.valueType = valueTypeHint;
                        this.isDecimal = true;

                    } else if (inputValue instanceof Float) {
                        this.value = inputValue;
                        this.valueType = valueTypeHint;
                        this.isDecimal = true;

                    } else if (inputValue instanceof BigDecimal) {
                        BigDecimal bd = (BigDecimal) inputValue;
                        double dval = bd.doubleValue();
                        // make with same scale as Double
                        try {
                            BigDecimal bd2 =
                                Util.makeBigDecimalFromDouble(dval);
                            // Can it be a double
                            // Must use compareTo - see BigDecimal.equals
                            if (bd.compareTo(bd2) == 0) {
                                this.valueType = XSD_DOUBLE;
                                this.value = dval;
                            } else {
                                this.valueType = XSD_DECIMAL;
                                this.value = inputValue;
                            }
                        } catch (NumberFormatException ex) {
                            this.valueType = XSD_DECIMAL;
                            this.value = inputValue;
                        }
                        this.isDecimal = true;

                    } else if (inputValue instanceof BigInteger) {
                        // What should be done here? Convert ot BigDecimal
                        // and see if it can be a double or not?
                        // See if there is loss of precision in the convertion?
                        // Don't know. For now, just keep it a integral
                        // value.
                        BigInteger bi = (BigInteger) inputValue;
                        // See if it can be an integer or long
                        long lval = bi.longValue();
                        if (bi.equals(BigInteger.valueOf(lval))) {
                            // It can be converted from BigInteger to long
                            // without loss of precision.
                            setValueAndType(lval);
                        } else {
                            // It can not be converted to a long.
                            this.valueType = XSD_INTEGER;
                            this.value = inputValue;
                            this.isDecimal = true;
                        }

                    } else if (inputValue instanceof Number) {
                        // Don't know what Number type we have here.
                        // Note: this could result in precision loss.
                        this.value = ((Number) inputValue).doubleValue();
                        this.valueType = valueTypeHint;
                        this.isDecimal = true;

                    } else {
                        // Who knows what we are dealing with,
                        // hope for the best?!?
                        this.valueType = valueTypeHint;
                        this.value = inputValue;
                        this.isDecimal = true;
                    }
                }
            } else {
                // There is no valueType "hint", so just get it from the value.
                if (inputValue instanceof String) {
                    this.valueType = XSD_STRING;
                    this.value = inputValue;
                    this.isDecimal = false;

                } else if (inputValue instanceof Integer) {
                    this.valueType = XSD_INT;
                    this.value = inputValue;
                    this.isDecimal = false;

                } else if (inputValue instanceof Byte) {
                    Byte b = (Byte) inputValue;
                    this.valueType = XSD_INT;
                    this.value = b.intValue();
                    this.isDecimal = false;

                } else if (inputValue instanceof Short) {
                    Short s = (Short) inputValue;
                    this.valueType = XSD_INT;
                    this.value = s.intValue();
                    this.isDecimal = false;

                } else if (inputValue instanceof Long) {
                    // See if it can be an integer or long
                    setValueAndType((Long) inputValue);

                } else if (inputValue instanceof BigInteger) {
                    BigInteger bi = (BigInteger) inputValue;
                    // See if it can be an integer or long
                    long lval = bi.longValue();
                    if (bi.equals(BigInteger.valueOf(lval))) {
                        // It can be converted from BigInteger to long
                        // without loss of precision.
                        setValueAndType(lval);
                    } else {
                        // It can not be converted to a long.
                        this.valueType = XSD_INTEGER;
                        this.value = inputValue;
                        this.isDecimal = false;
                    }

                } else if (inputValue instanceof Float) {
                    this.valueType = XSD_DOUBLE;
                    this.value = inputValue;
                    this.isDecimal = true;

                } else if (inputValue instanceof Double) {
                    this.valueType = XSD_DOUBLE;
                    this.value = inputValue;
                    this.isDecimal = true;

                } else if (inputValue instanceof BigDecimal) {
                    // See if it can be a double
                    BigDecimal bd = (BigDecimal) inputValue;
                    double dval = bd.doubleValue();
                    // make with same scale as Double
                    try {
                        BigDecimal bd2 =
                                Util.makeBigDecimalFromDouble(dval);
                        // Can it be a double
                        // Must use compareTo - see BigDecimal.equals
                        if (bd.compareTo(bd2) == 0) {
                            this.valueType = XSD_DOUBLE;
                            this.value = dval;
                        } else {
                            this.valueType = XSD_DECIMAL;
                            this.value = inputValue;
                        }
                    } catch (NumberFormatException ex) {
                        this.valueType = XSD_DECIMAL;
                        this.value = inputValue;
                    }
                    this.isDecimal = true;

                } else if (inputValue instanceof Number) {
                    // Don't know what Number type we have here.
                    // Note: this could result in precision loss.
                    this.value = ((Number) inputValue).longValue();
                    this.valueType = XSD_LONG;
                    this.isDecimal = false;

                } else {
                    // Who knows what we are dealing with,
                    // hope for the best?!?
                    this.valueType = XSD_STRING;
                    this.value = inputValue;
                    this.isDecimal = false;
                }
            }
        }
        private void setValueAndType(long lval) {
            if (! isValidXsdInt(lval)) {
                // No, it can not be a integer, must be a long
                this.valueType = XSD_LONG;
                this.value = lval;
            } else {
                // Its an integer.
                this.valueType = XSD_INT;
                this.value = (int) lval;
            }
            this.isDecimal = false;
        }
    }



    private static String computeXsd(SetType setType) {
        final StringWriter sw = new StringWriter();
        SaxWriter writer = new DefaultSaxWriter(new PrintWriter(sw), 3);
        writeDatasetXmlSchema(writer, setType);
        writer.flush();
        return sw.toString();
    }

    private static String computeEmptyXsd(SetType setType) {
        final StringWriter sw = new StringWriter();
        SaxWriter writer = new DefaultSaxWriter(new PrintWriter(sw), 3);
        writeEmptyDatasetXmlSchema(writer, setType);
        writer.flush();
        return sw.toString();
    }

    private static interface QueryResult {
        public void unparse(SaxWriter res) throws SAXException;
    }

    /**
     * Creates an <code>XmlaHandler</code>.
     *
     * @param dataSources Data sources
     * @param catalogLocator Catalog locator
     * @param prefix XML Namespace. Typical value is "xmla", but a value of
     *   "cxmla" works around an Internet Explorer 7 bug
     */
    public XmlaHandler(
        DataSourcesConfig.DataSources dataSources,
        CatalogLocator catalogLocator,
        String prefix)
    {
        this.catalogLocator = catalogLocator;
        assert prefix != null;
        this.prefix = prefix;
        Map<String, DataSourcesConfig.DataSource> map =
            new HashMap<String, DataSourcesConfig.DataSource>();
        if (dataSources != null) {
            for (DataSourcesConfig.DataSource ds : dataSources.dataSources) {
                if (map.containsKey(ds.getDataSourceName())) {
                    // This is not an XmlaException
                    throw Util.newError(
                        "duplicated data source name '"
                        + ds.getDataSourceName() + "'");
                }
                // Set parent pointers.
                for (DataSourcesConfig.Catalog catalog : ds.catalogs.catalogs) {
                    catalog.setDataSource(ds);
                }
                map.put(ds.getDataSourceName(), ds);
            }
        }
        dataSourcesMap = Collections.unmodifiableMap(map);
    }

    public Map<String, DataSourcesConfig.DataSource> getDataSourceEntries() {
        return dataSourcesMap;
    }

    /**
     * Processes a request.
     *
     * @param request  XML request, for example, "<SOAP-ENV:Envelope ...>".
     * @param response Destination for response
     * @throws XmlaException on error
     */
    public void process(XmlaRequest request, XmlaResponse response)
        throws XmlaException
    {
        Method method = request.getMethod();
        long start = System.currentTimeMillis();

        switch (method) {
        case DISCOVER:
            discover(request, response);
            break;
        case EXECUTE:
            execute(request, response);
            break;
        default:
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_METHOD_CODE,
                HSB_BAD_METHOD_FAULT_FS,
                new IllegalArgumentException(
                    "Unsupported XML/A method: " + method));
        }
        if (LOGGER.isDebugEnabled()) {
            long end = System.currentTimeMillis();
            LOGGER.debug("XmlaHandler.process: time = " + (end - start));
            LOGGER.debug("XmlaHandler.process: " + Util.printMemory());
        }
    }

    private void checkFormat(XmlaRequest request) throws XmlaException {
        // Check response's rowset format in request
        final Map<String, String> properties = request.getProperties();
        if (request.isDrillThrough()) {
            Format format = getFormat(request, null);
            if (format != Format.Tabular) {
                throw new XmlaException(
                    CLIENT_FAULT_FC,
                    HSB_DRILL_THROUGH_FORMAT_CODE,
                    HSB_DRILL_THROUGH_FORMAT_FAULT_FS,
                    new UnsupportedOperationException(
                        "<Format>: only 'Tabular' allowed when drilling "
                        + "through"));
            }
        } else {
            final String formatName =
                properties.get(PropertyDefinition.Format.name());
            if (formatName != null) {
                Format format = getFormat(request, null);
                if (format != Format.Multidimensional
                    && format != Format.Tabular)
                {
                    throw new UnsupportedOperationException(
                        "<Format>: only 'Multidimensional', 'Tabular' "
                        + "currently supported");
                }
            }
            final String axisFormatName =
                properties.get(PropertyDefinition.AxisFormat.name());
            if (axisFormatName != null) {
                AxisFormat axisFormat = Util.lookup(
                    AxisFormat.class, axisFormatName, null);

                if (axisFormat != AxisFormat.TupleFormat) {
                    throw new UnsupportedOperationException(
                        "<AxisFormat>: only 'TupleFormat' currently supported");
                }
            }
        }
    }

    private void execute(
        XmlaRequest request,
        XmlaResponse response)
        throws XmlaException
    {
        final Map<String, String> properties = request.getProperties();

        // Default responseMimeType is SOAP.
        Enumeration.ResponseMimeType responseMimeType =
            getResponseMimeType(request);

        // Default value is SchemaData, or Data for JSON responses.
        final String contentName =
            properties.get(PropertyDefinition.Content.name());
        Content content = Util.lookup(
            Content.class,
            contentName,
            responseMimeType == Enumeration.ResponseMimeType.JSON
                ? Content.Data
                : Content.DEFAULT);

        // Handle execute
        QueryResult result;
        if (request.isDrillThrough()) {
            result = executeDrillThroughQuery(request);
        } else {
            result = executeQuery(request);
        }

        SaxWriter writer = response.getWriter();
        writer.startDocument();

        writer.startElement(
            prefix + ":ExecuteResponse",
            "xmlns:" + prefix, NS_XMLA);
        writer.startElement(prefix + ":return");
        boolean rowset =
            request.isDrillThrough()
            || Format.Tabular.name().equals(
                request.getProperties().get(
                    PropertyDefinition.Format.name()));
        writer.startElement(
            "root",
            "xmlns",
            result == null
            ? NS_XMLA_EMPTY
            : rowset
            ? NS_XMLA_ROWSET
            : NS_XMLA_MDDATASET,
            "xmlns:xsi", NS_XSI,
            "xmlns:xsd", NS_XSD,
            "xmlns:EX", NS_XMLA_EX);

        if ((content == Content.Schema)
            || (content == Content.SchemaData))
        {
            if (result != null) {
                if (result instanceof MDDataSet_Tabular) {
                    MDDataSet_Tabular tabResult = (MDDataSet_Tabular) result;
                    tabResult.metadata(writer);
                } else if (rowset) {
                    ((TabularRowSet) result).metadata(writer);
                } else {
                    writer.verbatim(MD_DATA_SET_XML_SCHEMA);
                }
            } else {
                if (rowset) {
                    writer.verbatim(EMPTY_ROW_SET_XML_SCHEMA);
                } else {
                    writer.verbatim(EMPTY_MD_DATA_SET_XML_SCHEMA);
                }
            }
        }

        try {
            switch (content) {
            case Data:
            case SchemaData:
            case DataOmitDefaultSlicer:
            case DataIncludeDefaultSlicer:
                if (result != null) {
                    result.unparse(writer);
                }
                break;
            }
        } catch (XmlaException xex) {
            throw xex;
        } catch (Throwable t) {
            throw new XmlaException(
                SERVER_FAULT_FC,
                HSB_EXECUTE_UNPARSE_CODE,
                HSB_EXECUTE_UNPARSE_FAULT_FS,
                t);
        } finally {
            writer.endElement(); // root
            writer.endElement(); // return
            writer.endElement(); // ExecuteResponse
        }

        writer.endDocument();
    }

    /**
     * Computes the XML Schema for a dataset.
     *
     * @param writer SAX writer
     * @param settype rowset or dataset?
     * @see RowsetDefinition#writeRowsetXmlSchema(SaxWriter)
     */
    static void writeDatasetXmlSchema(SaxWriter writer, SetType settype) {
        String setNsXmla =
            (settype == SetType.ROW_SET)
            ? NS_XMLA_ROWSET
            : NS_XMLA_MDDATASET;

        writer.startElement(
            "xsd:schema",
            "xmlns:xsd", NS_XSD,
            "targetNamespace", setNsXmla,
            "xmlns", setNsXmla,
            "xmlns:xsi", NS_XSI,
            "xmlns:sql", NS_XML_SQL,
            "elementFormDefault", "qualified");

        // MemberType

        writer.startElement(
            "xsd:complexType",
            "name", "MemberType");
        writer.startElement("xsd:sequence");
        writer.element(
            "xsd:element",
            "name", "UName",
            "type", XSD_STRING);
        writer.element(
            "xsd:element",
            "name", "Caption",
            "type", XSD_STRING);
        writer.element(
            "xsd:element",
            "name", "LName",
            "type", XSD_STRING);
        writer.element(
            "xsd:element",
            "name", "LNum",
            "type", XSD_UNSIGNED_INT);
        writer.element(
            "xsd:element",
            "name", "DisplayInfo",
            "type", XSD_UNSIGNED_INT);
        writer.startElement(
            "xsd:sequence",
            "maxOccurs", "unbounded",
            "minOccurs", 0);
        writer.element(
            "xsd:any",
            "processContents", "lax",
            "maxOccurs", "unbounded");
        writer.endElement(); // xsd:sequence
        writer.endElement(); // xsd:sequence
        writer.element(
            "xsd:attribute",
                "name", "Hierarchy",
                "type", XSD_STRING);
        writer.endElement(); // xsd:complexType name="MemberType"

        // PropType

        writer.startElement(
            "xsd:complexType",
            "name", "PropType");
        writer.element(
            "xsd:attribute",
            "name", "name",
            "type", XSD_STRING);
        writer.endElement(); // xsd:complexType name="PropType"

        // TupleType

        writer.startElement(
            "xsd:complexType",
            "name", "TupleType");
        writer.startElement(
            "xsd:sequence",
            "maxOccurs", "unbounded");
        writer.element(
            "xsd:element",
            "name", "Member",
            "type", "MemberType");
        writer.endElement(); // xsd:sequence
        writer.endElement(); // xsd:complexType name="TupleType"

        // MembersType

        writer.startElement(
            "xsd:complexType",
            "name", "MembersType");
        writer.startElement(
            "xsd:sequence",
            "maxOccurs", "unbounded");
        writer.element(
            "xsd:element",
            "name", "Member",
            "type", "MemberType");
        writer.endElement(); // xsd:sequence
        writer.element(
            "xsd:attribute",
            "name", "Hierarchy",
            "type", XSD_STRING);
        writer.endElement(); // xsd:complexType

        // TuplesType

        writer.startElement(
            "xsd:complexType",
            "name", "TuplesType");
        writer.startElement(
            "xsd:sequence",
            "maxOccurs", "unbounded");
        writer.element(
            "xsd:element",
            "name", "Tuple",
            "type", "TupleType");
        writer.endElement(); // xsd:sequence
        writer.endElement(); // xsd:complexType

        // CrossProductType

        writer.startElement(
            "xsd:complexType",
            "name", "CrossProductType");
        writer.startElement("xsd:sequence");
        writer.startElement(
            "xsd:choice",
            "minOccurs", 0,
            "maxOccurs", "unbounded");
        writer.element(
            "xsd:element",
            "name", "Members",
            "type", "MembersType");
        writer.element(
            "xsd:element",
            "name", "Tuples",
            "type", "TuplesType");
        writer.endElement(); // xsd:choice
        writer.endElement(); // xsd:sequence
        writer.element(
            "xsd:attribute",
            "name", "Size",
            "type", XSD_UNSIGNED_INT);
        writer.endElement(); // xsd:complexType

        // OlapInfo

        writer.startElement(
            "xsd:complexType",
            "name", "OlapInfo");
        writer.startElement("xsd:sequence");

        { // <CubeInfo>
            writer.startElement(
                "xsd:element",
                "name", "CubeInfo");
            writer.startElement("xsd:complexType");
            writer.startElement("xsd:sequence");

            { // <Cube>
                writer.startElement(
                    "xsd:element",
                    "name", "Cube",
                    "maxOccurs", "unbounded");
                writer.startElement("xsd:complexType");
                writer.startElement("xsd:sequence");

                writer.element(
                    "xsd:element",
                    "name", "CubeName",
                    "type", XSD_STRING);

                writer.endElement(); // xsd:sequence
                writer.endElement(); // xsd:complexType
                writer.endElement(); // xsd:element name=Cube
            }

            writer.endElement(); // xsd:sequence
            writer.endElement(); // xsd:complexType
            writer.endElement(); // xsd:element name=CubeInfo
        }

        { // <AxesInfo>
            writer.startElement(
                "xsd:element",
                "name", "AxesInfo");
            writer.startElement("xsd:complexType");
            writer.startElement("xsd:sequence");
            { // <AxisInfo>
                writer.startElement(
                    "xsd:element",
                    "name", "AxisInfo",
                    "maxOccurs", "unbounded");
                writer.startElement("xsd:complexType");
                writer.startElement("xsd:sequence");

                { // <HierarchyInfo>
                    writer.startElement(
                        "xsd:element",
                        "name", "HierarchyInfo",
                        "minOccurs", 0,
                        "maxOccurs", "unbounded");
                    writer.startElement("xsd:complexType");
                    writer.startElement("xsd:sequence");
                    writer.startElement(
                        "xsd:sequence",
                        "maxOccurs", "unbounded");
                    writer.element(
                        "xsd:element",
                        "name", "UName",
                        "type", "PropType");
                    writer.element(
                        "xsd:element",
                        "name", "Caption",
                        "type", "PropType");
                    writer.element(
                        "xsd:element",
                        "name", "LName",
                        "type", "PropType");
                    writer.element(
                        "xsd:element",
                        "name", "LNum",
                        "type", "PropType");
                    writer.element(
                        "xsd:element",
                        "name", "DisplayInfo",
                        "type", "PropType",
                        "minOccurs", 0,
                        "maxOccurs", "unbounded");
                    if (false) writer.element(
                        "xsd:element",
                        "name", "PARENT_MEMBER_NAME",
                        "type", "PropType",
                        "minOccurs", 0,
                        "maxOccurs", "unbounded");
                    writer.endElement(); // xsd:sequence

                    // This is the Depth element for JPivot??
                    writer.startElement("xsd:sequence");
                    writer.element(
                        "xsd:any",
                        "processContents", "lax",
                        "minOccurs", 0,
                        "maxOccurs", "unbounded");
                    writer.endElement(); // xsd:sequence

                    writer.endElement(); // xsd:sequence
                    writer.element(
                        "xsd:attribute",
                        "name", "name",
                        "type", XSD_STRING,
                        "use", "required");
                    writer.endElement(); // xsd:complexType
                    writer.endElement(); // xsd:element name=HierarchyInfo
                }
                writer.endElement(); // xsd:sequence
                writer.element(
                    "xsd:attribute",
                    "name", "name",
                    "type", XSD_STRING);
                writer.endElement(); // xsd:complexType
                writer.endElement(); // xsd:element name=AxisInfo
            }
            writer.endElement(); // xsd:sequence
            writer.endElement(); // xsd:complexType
            writer.endElement(); // xsd:element name=AxesInfo
        }

        // CellInfo

        { // <CellInfo>
            writer.startElement(
                "xsd:element",
                "name", "CellInfo");
            writer.startElement("xsd:complexType");
            writer.startElement("xsd:sequence");
            writer.startElement(
                "xsd:sequence",
                "minOccurs", 0,
                "maxOccurs", "unbounded");
            writer.startElement("xsd:choice");
            writer.element(
                "xsd:element",
                "name", "Value",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "FmtValue",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "BackColor",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "ForeColor",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "FontName",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "FontSize",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "FontFlags",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "FormatString",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "NonEmptyBehavior",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "SolveOrder",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "Updateable",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "Visible",
                "type", "PropType");
            writer.element(
                "xsd:element",
                "name", "Expression",
                "type", "PropType");
            writer.endElement(); // xsd:choice
            writer.endElement(); // xsd:sequence
            writer.startElement(
                "xsd:sequence",
                "maxOccurs", "unbounded",
                "minOccurs", 0);
            writer.element(
                "xsd:any",
                "processContents", "lax",
                "maxOccurs", "unbounded");
            writer.endElement(); // xsd:sequence
            writer.endElement(); // xsd:sequence
            writer.endElement(); // xsd:complexType
            writer.endElement(); // xsd:element name=CellInfo
        }

        writer.endElement(); // xsd:sequence
        writer.endElement(); // xsd:complexType

        // Axes

        writer.startElement(
            "xsd:complexType",
            "name", "Axes");
        writer.startElement(
            "xsd:sequence",
            "maxOccurs", "unbounded");
        { // <Axis>
            writer.startElement(
                "xsd:element",
                "name", "Axis");
            writer.startElement("xsd:complexType");
            writer.startElement(
                "xsd:choice",
                "minOccurs", 0,
                "maxOccurs", "unbounded");
            writer.element(
                "xsd:element",
                "name", "CrossProduct",
                "type", "CrossProductType");
            writer.element(
                "xsd:element",
                "name", "Tuples",
                "type", "TuplesType");
            writer.element(
                "xsd:element",
                "name", "Members",
                "type", "MembersType");
            writer.endElement(); // xsd:choice
            writer.element(
                "xsd:attribute",
                "name", "name",
                "type", XSD_STRING);
            writer.endElement(); // xsd:complexType
        }
        writer.endElement(); // xsd:element
        writer.endElement(); // xsd:sequence
        writer.endElement(); // xsd:complexType

        // CellData

        writer.startElement(
            "xsd:complexType",
            "name", "CellData");
        writer.startElement("xsd:sequence");
        { // <Cell>
            writer.startElement(
                "xsd:element",
                "name", "Cell",
                "minOccurs", 0,
                "maxOccurs", "unbounded");
            writer.startElement("xsd:complexType");
            writer.startElement(
                "xsd:sequence",
                "maxOccurs", "unbounded");
            writer.startElement("xsd:choice");
            writer.element(
                "xsd:element",
                "name", "Value");
            writer.element(
                "xsd:element",
                "name", "FmtValue",
                "type", XSD_STRING);
            writer.element(
                "xsd:element",
                "name", "BackColor",
                "type", XSD_UNSIGNED_INT);
            writer.element(
                "xsd:element",
                "name", "ForeColor",
                "type", XSD_UNSIGNED_INT);
            writer.element(
                "xsd:element",
                "name", "FontName",
                "type", XSD_STRING);
            writer.element(
                "xsd:element",
                "name", "FontSize",
                "type", "xsd:unsignedShort");
            writer.element(
                "xsd:element",
                "name", "FontFlags",
                "type", XSD_UNSIGNED_INT);
            writer.element(
                "xsd:element",
                "name", "FormatString",
                "type", XSD_STRING);
            writer.element(
                "xsd:element",
                "name", "NonEmptyBehavior",
                "type", "xsd:unsignedShort");
            writer.element(
                "xsd:element",
                "name", "SolveOrder",
                "type", XSD_UNSIGNED_INT);
            writer.element(
                "xsd:element",
                "name", "Updateable",
                "type", XSD_UNSIGNED_INT);
            writer.element(
                "xsd:element",
                "name", "Visible",
                "type", XSD_UNSIGNED_INT);
            writer.element(
                "xsd:element",
                "name", "Expression",
                "type", XSD_STRING);
            writer.endElement(); // xsd:choice
            writer.endElement(); // xsd:sequence
            writer.element(
                "xsd:attribute",
                "name", "CellOrdinal",
                "type", XSD_UNSIGNED_INT,
                "use", "required");
            writer.endElement(); // xsd:complexType
            writer.endElement(); // xsd:element name=Cell
        }
        writer.endElement(); // xsd:sequence
        writer.endElement(); // xsd:complexType

        { // <root>
            writer.startElement(
                "xsd:element",
                "name", "root");
            writer.startElement("xsd:complexType");
            writer.startElement(
                "xsd:sequence",
                "maxOccurs", "unbounded");
            writer.element(
                "xsd:element",
                "name", "OlapInfo",
                "type", "OlapInfo");
            writer.element(
                "xsd:element",
                "name", "Axes",
                "type", "Axes");
            writer.element(
                "xsd:element",
                "name", "CellData",
                "type", "CellData");
            writer.endElement(); // xsd:sequence
            writer.endElement(); // xsd:complexType
            writer.endElement(); // xsd:element name=root
        }

        writer.endElement(); // xsd:schema
    }

    static void writeEmptyDatasetXmlSchema(SaxWriter writer, SetType setType) {
        String setNsXmla = NS_XMLA_ROWSET;
        writer.startElement(
            "xsd:schema",
            "xmlns:xsd", NS_XSD,
            "targetNamespace", setNsXmla,
            "xmlns", setNsXmla,
            "xmlns:xsi", NS_XSI,
            "xmlns:sql", NS_XML_SQL,
            "elementFormDefault", "qualified");

        writer.element(
            "xsd:element",
            "name", "root");

        writer.endElement(); // xsd:schema
    }

    private QueryResult executeDrillThroughQuery(XmlaRequest request)
        throws XmlaException
    {
        checkFormat(request);

        DataSourcesConfig.DataSource ds = getDataSource(request);
        DataSourcesConfig.Catalog dsCatalog = getCatalog(request, ds, true);
        String roleName = request.getRoleName();
        Role role = request.getRole();

        final Connection connection = getConnection(dsCatalog, role, roleName);

        final String statement = request.getStatement();
        final QueryPart parseTree = connection.parseStatement(statement);
        final DrillThrough drillThrough = (DrillThrough) parseTree;
        final Query query = drillThrough.getQuery();
        query.setResultStyle(ResultStyle.LIST);
        final Result result = connection.execute(query);
        // cell [0, 0] in a 2-dimensional query, [0, 0, 0] in 3 dimensions, etc.
        final int[] coords = new int[result.getAxes().length];
        Cell dtCell = result.getCell(coords);

        if (!dtCell.canDrillThrough()) {
            throw new XmlaException(
                SERVER_FAULT_FC,
                HSB_DRILL_THROUGH_NOT_ALLOWED_CODE,
                HSB_DRILL_THROUGH_NOT_ALLOWED_FAULT_FS,
                Util.newError("Cannot do DrillThrough operation on the cell"));
        }

        try {
            final Map<String, String> properties = request.getProperties();
            String tabFields =
                properties.get(PropertyDefinition.TableFields.name());
            if (tabFields != null && tabFields.length() == 0) {
                tabFields = null;
            }
            final String advancedFlag =
                properties.get(PropertyDefinition.AdvancedFlag.name());
            final boolean advanced = Boolean.parseBoolean(advancedFlag);
            if (advanced) {
                return executeDrillThroughAdvanced(connection, result);
            } else {
                int count = -1;
                if (MondrianProperties.instance().EnableTotalCount
                    .booleanValue())
                {
                    count = dtCell.getDrillThroughCount();
                }
                SqlStatement stmt2 =
                    ((RolapCell) dtCell).drillThroughInternal(
                        drillThrough.getMaxRowCount(),
                        Math.max(drillThrough.getFirstRowOrdinal(), 1),
                        tabFields,
                        true,
                        LOGGER);
                return new TabularRowSet(stmt2, count);
            }
        } catch (XmlaException xex) {
            throw xex;
        } catch (SQLException sqle) {
            throw new XmlaException(
                SERVER_FAULT_FC,
                HSB_DRILL_THROUGH_SQL_CODE,
                HSB_DRILL_THROUGH_SQL_FAULT_FS,
                Util.newError(sqle, "Error in drill through"));
        } catch (RuntimeException e) {
            throw new XmlaException(
                SERVER_FAULT_FC,
                HSB_DRILL_THROUGH_SQL_CODE,
                HSB_DRILL_THROUGH_SQL_FAULT_FS,
                e);
        }
    }

    private QueryResult executeDrillThroughAdvanced(
        Connection connection,
        Result result)
        throws SQLException
    {
        java.sql.Connection sqlConn = null;
        Statement stmt = null;
        try {
            final Axis axis = result.getAxes()[0];
            final Position position = axis.getPositions().get(0);
            Member[] members = position.toArray(
                new Member[position.size()]);

            final CellRequest cellRequest =
                RolapAggregationManager.makeRequest(members);
            List<MondrianDef.Relation> relationList =
                new ArrayList<MondrianDef.Relation>();
            final RolapStar.Table factTable =
                cellRequest.getMeasure().getStar().getFactTable();
            MondrianDef.Relation relation = factTable.getRelation();
            relationList.add(relation);

            for (RolapStar.Table table : factTable.getChildren()) {
                relationList.add(table.getRelation());
            }
            List<String> truncatedTableList = new ArrayList<String>();
            sqlConn = connection.getDataSource().getConnection();
            stmt = sqlConn.createStatement();
            List<List<String>> fields = new ArrayList<List<String>>();

            Map<String, List<String>> tableFieldMap =
                new HashMap<String, List<String>>();
            for (MondrianDef.Relation relation1 : relationList) {
                final String tableName = relation1.toString();
                List<String> fieldNameList = new ArrayList<String>();
                Dialect dialect =
                    ((RolapSchema) connection.getSchema()).getDialect();
                // FIXME: Include schema name, if specified.
                // FIXME: Deal with relations that are not tables.
                final StringBuilder buf = new StringBuilder();
                buf.append("SELECT * FROM ");
                dialect.quoteIdentifier(buf, tableName);
                buf.append(" WHERE 1=2");
                String sql = buf.toString();
                ResultSet rs = stmt.executeQuery(sql);
                ResultSetMetaData rsMeta = rs.getMetaData();
                for (int j = 1; j <= rsMeta.getColumnCount(); j++) {
                    // FIXME: In some JDBC drivers,
                    // ResultSetMetaData.getColumnName(int) does strange
                    // things with aliased columns. See MONDRIAN-654
                    // http://jira.pentaho.com/browse/MONDRIAN-654 for
                    // details. Therefore, we don't want to use that
                    // method. It seems harmless here, but I'd still
                    // like to phase out use of getColumnName. After
                    // PhysTable is introduced (coming in mondrian-4.0)
                    // we should be able to just use its column list.
                    String colName = rsMeta.getColumnName(j);
                    boolean colNameExists = false;
                    for (List<String> prvField : fields) {
                        if (prvField.contains(colName)) {
                            colNameExists = true;
                            break;
                        }
                    }
                    if (!colNameExists) {
                        fieldNameList.add(colName);
                    }
                }
                fields.add(fieldNameList);
                String truncatedTableName =
                    tableName.substring(tableName.lastIndexOf(".") + 1);
                truncatedTableList.add(truncatedTableName);
                tableFieldMap.put(truncatedTableName, fieldNameList);
            }
            return new TabularRowSet(tableFieldMap, truncatedTableList);
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {
                }
            }
            if (sqlConn != null) {
                try {
                    sqlConn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    static class Column {
        private final String name;
        private final String encodedName;
        private final String xsdType;

        Column(String name, int type) {
            this.name = name;

            // replace invalid XML element name, like " ", with "_x0020_" in
            // column headers, otherwise will generate a badly-formatted xml
            // doc.
            this.encodedName = XmlaUtil.encodeElementName(name);
            this.xsdType = sqlToXsdType(type);
        }
    }

    static class TabularRowSet implements QueryResult {
        private final List<Column> columns = new ArrayList<Column>();
        private final List<Object[]> rows;
        private int totalCount;

        /**
         * Creates a TabularRowSet based upon a SQL statement result.
         *
         * <p>Closes the SqlStatement when it is done.
         *
         * @param stmt SqlStatement
         * @param totalCount Total number of rows. If >= 0, writes the
         *   "totalCount" attribute into the XMLA response.
         */
        public TabularRowSet(
            SqlStatement stmt,
            int totalCount)
        {
            this.totalCount = totalCount;
            ResultSet rs = stmt.getResultSet();
            try {
                ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();

                // populate column defs
                for (int i = 0; i < columnCount; i++) {
                    columns.add(
                        new Column(
                            md.getColumnLabel(i + 1),
                            md.getColumnType(i + 1)));
                }

                // Populate data; assume that SqlStatement is already positioned
                // on first row (or isDone() is true), and assume that the
                // number of rows returned is limited.
                rows = new ArrayList<Object[]>();
                final List<SqlStatement.Accessor> accessors =
                    stmt.getAccessors();
                if (!stmt.isDone()) {
                    do {
                        Object[] row = new Object[columnCount];
                        for (int i = 0; i < columnCount; i++) {
                            row[i] = accessors.get(i).get();
                        }
                        rows.add(row);
                    } while (rs.next());
                }
            } catch (SQLException e) {
                throw stmt.handle(e);
            } finally {
                stmt.close();
            }
        }

        /**
         * Alternate constructor for advanced drill-through.
         *
         * @param tableFieldMap Map from table name to a list of the names of
         *      the fields in the table
         * @param tableList List of table names
         */
        public TabularRowSet(
            Map<String, List<String>> tableFieldMap, List<String> tableList)
        {
            for (String tableName : tableList) {
                List<String> fieldNames = tableFieldMap.get(tableName);
                for (String fieldName : fieldNames) {
                    columns.add(
                        new Column(
                            tableName + "." + fieldName,
                            Types.VARCHAR)); // don't know the real type
                }
            }

            rows = new ArrayList<Object[]>();
            Object[] row = new Object[columns.size()];
            for (int k = 0; k < row.length; k++) {
                row[k] = k;
            }
            rows.add(row);
        }

        public void unparse(SaxWriter writer) throws SAXException {
            // write total count row if enabled
            if (totalCount >= 0) {
                String countStr = Integer.toString(totalCount);
                writer.startElement("row");
                for (Column column : columns) {
                    writer.startElement(column.encodedName);
                    writer.characters(countStr);
                    writer.endElement();
                }
                writer.endElement(); // row
            }

            for (Object[] row : rows) {
                writer.startElement("row");
                for (int i = 0; i < row.length; i++) {
                    writer.startElement(columns.get(i).encodedName);
                    Object value = row[i];
                    if (value == null) {
                        writer.characters("null");
                    } else {
                        String valueString = value.toString();
                        if (value instanceof Number) {
                            valueString =
                                XmlaUtil.normalizeNumericString(valueString);
                        }
                        writer.characters(valueString);
                    }
                    writer.endElement();
                }
                writer.endElement(); // row
            }
        }

        /**
         * Writes the tabular drillthrough schema
         *
         * @param writer Writer
         */
        public void metadata(SaxWriter writer) {
            writer.startElement(
                "xsd:schema",
                "xmlns:xsd", NS_XSD,
                "targetNamespace", NS_XMLA_ROWSET,
                "xmlns", NS_XMLA_ROWSET,
                "xmlns:xsi", NS_XSI,
                "xmlns:sql", NS_XML_SQL,
                "elementFormDefault", "qualified");

            { // <root>
                writer.startElement(
                    "xsd:element",
                    "name", "root");
                writer.startElement("xsd:complexType");
                writer.startElement("xsd:sequence");
                writer.element(
                    "xsd:element",
                    "maxOccurs", "unbounded",
                    "minOccurs", 0,
                    "name", "row",
                    "type", "row");
                writer.endElement(); // xsd:sequence
                writer.endElement(); // xsd:complexType
                writer.endElement(); // xsd:element name=root
            }

            { // xsd:simpleType name="uuid"
                writer.startElement(
                    "xsd:simpleType",
                        "name", "uuid");
                writer.startElement(
                    "xsd:restriction",
                    "base", XSD_STRING);
                writer.element(
                    "xsd:pattern",
                    "value", RowsetDefinition.UUID_PATTERN);
                writer.endElement(); // xsd:restriction
                writer.endElement(); // xsd:simpleType
            }

            { // xsd:complexType name="row"
                writer.startElement(
                    "xsd:complexType",
                    "name", "row");
                writer.startElement("xsd:sequence");
                for (Column column : columns) {
                    writer.element(
                        "xsd:element",
                        "minOccurs", 0,
                        "name", column.encodedName,
                        "sql:field", column.name,
                        "type", column.xsdType);
                }

                writer.endElement(); // xsd:sequence
                writer.endElement(); // xsd:complexType
            }
            writer.endElement(); // xsd:schema
        }
    }

    /**
     * Converts a SQL type to XSD type.
     *
     * @param sqlType SQL type
     * @return XSD type
     */
    private static String sqlToXsdType(int sqlType) {
        switch (sqlType) {
        // Integer
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.SMALLINT:
        case Types.TINYINT:
            return XSD_INTEGER;
        case Types.NUMERIC:
            return XSD_DECIMAL;
            // Real
        case Types.DOUBLE:
        case Types.FLOAT:
            return XSD_DOUBLE;
            // Date and time
        case Types.TIME:
        case Types.TIMESTAMP:
        case Types.DATE:
            return XSD_STRING;
            // Other
        default:
            return XSD_STRING;
        }
    }

    private QueryResult executeQuery(XmlaRequest request)
        throws XmlaException
    {
        final String statement = request.getStatement();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("mdx: \"" + statement + "\"");
        }

        if ((statement == null) || (statement.length() == 0)) {
            return null;
        } else {
            checkFormat(request);

            DataSourcesConfig.DataSource ds = getDataSource(request);
            DataSourcesConfig.Catalog dsCatalog = getCatalog(request, ds, true);
            String roleName = request.getRoleName();
            Role role = request.getRole();

            final Connection connection =
                getConnection(dsCatalog, role, roleName);

            final Query query;
            try {
                query = connection.parseQuery(statement);
                query.setResultStyle(ResultStyle.LIST);
            } catch (XmlaException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new XmlaException(
                    CLIENT_FAULT_FC,
                    HSB_PARSE_QUERY_CODE,
                    HSB_PARSE_QUERY_FAULT_FS,
                    ex);
            }
            final Result result;
            try {
                result = connection.execute(query);
            } catch (XmlaException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new XmlaException(
                    SERVER_FAULT_FC,
                    HSB_EXECUTE_QUERY_CODE,
                    HSB_EXECUTE_QUERY_FAULT_FS,
                    ex);
            }

            final Format format = getFormat(request, null);
            final Content content = getContent(request);
            final Enumeration.ResponseMimeType responseMimeType =
                getResponseMimeType(request);
            if (format == Format.Multidimensional) {
                return new MDDataSet_Multidimensional(
                    result,
                    content != Content.DataIncludeDefaultSlicer,
                    responseMimeType == Enumeration.ResponseMimeType.JSON);
            } else {
                return new MDDataSet_Tabular(result);
            }
        }
    }

    private static Format getFormat(
        XmlaRequest request,
        Format defaultValue)
    {
        final String formatName =
            request.getProperties().get(
                PropertyDefinition.Format.name());
        return Util.lookup(
            Format.class,
            formatName, defaultValue);
    }

    private static Content getContent(XmlaRequest request) {
        final String contentName =
            request.getProperties().get(
                PropertyDefinition.Content.name());
        return Util.lookup(
            Content.class,
            contentName,
            Content.DEFAULT);
    }

    private static Enumeration.ResponseMimeType getResponseMimeType(
        XmlaRequest request)
    {
        Enumeration.ResponseMimeType mimeType =
            Enumeration.ResponseMimeType.MAP.get(
                request.getProperties().get(
                    PropertyDefinition.ResponseMimeType.name()));
        if (mimeType == null) {
            mimeType = Enumeration.ResponseMimeType.SOAP;
        }
        return mimeType;
    }

    static abstract class MDDataSet implements QueryResult {
        protected final Result result;

        protected static final String[] cellProps = new String[] {
            "Value",
            "FmtValue",
            "FormatString"};

        protected static final Property[] cellPropLongs = {
            Property.VALUE,
            Property.FORMATTED_VALUE,
            Property.FORMAT_STRING};

        protected static final String[] defaultProps = new String[] {
            "UName",
            "Caption",
            "LName",
            "LNum",
            "DisplayInfo",
            // Not in spec nor generated by SQL Server
//            "Depth"
            };
        protected static final Map<String, String> longPropNames =
            new HashMap<String, String>();

        static {
            longPropNames.put("UName", Property.MEMBER_UNIQUE_NAME.name);
            longPropNames.put("Caption", Property.MEMBER_CAPTION.name);
            longPropNames.put("LName", Property.LEVEL_UNIQUE_NAME.name);
            longPropNames.put("LNum", Property.LEVEL_NUMBER.name);
            longPropNames.put("DisplayInfo", Property.DISPLAY_INFO.name);
        }

        protected MDDataSet(Result result) {
            this.result = result;
        }
    }

    static class MDDataSet_Multidimensional extends MDDataSet {
        private List<Hierarchy> slicerAxisHierarchies;
        private final boolean omitDefaultSlicerInfo;
        private final boolean json;

        protected MDDataSet_Multidimensional(
            Result result,
            boolean omitDefaultSlicerInfo,
            boolean json)
        {
            super(result);
            this.omitDefaultSlicerInfo = omitDefaultSlicerInfo;
            this.json = json;
        }

        public void unparse(SaxWriter writer) throws SAXException {
            olapInfo(writer);
            axes(writer);
            cellData(writer);
        }

        private void olapInfo(SaxWriter writer) {
            // What are all of the cube's hierachies
            Cube cube = result.getQuery().getCube();

            writer.startElement("OlapInfo");
            writer.startElement("CubeInfo");
            writer.startElement("Cube");
            writer.textElement("CubeName", cube.getName());
            writer.endElement();
            writer.endElement(); // CubeInfo

            // create AxesInfo for axes
            // -----------
            writer.startSequence("AxesInfo", "AxisInfo");
            final Axis[] axes = result.getAxes();
            final QueryAxis[] queryAxes = result.getQuery().getAxes();
            //axisInfo(writer, result.getSlicerAxis(), "SlicerAxis");
            List<Hierarchy> axisHierarchyList = new ArrayList<Hierarchy>();
            for (int i = 0; i < axes.length; i++) {
                List<Hierarchy> hiers =
                    axisInfo(writer, axes[i], queryAxes[i], "Axis" + i);
                axisHierarchyList.addAll(hiers);
            }
            ///////////////////////////////////////////////
            // create AxesInfo for slicer axes
            //
            List<Hierarchy> hierarchies;
            final QueryAxis slicerQueryAxis = result.getQuery().getSlicerAxis();
            if (omitDefaultSlicerInfo) {
                Axis slicerAxis = result.getSlicerAxis();
                // only add slicer axis element to response
                // if something is on the slicer
                if (slicerAxis.getPositions().get(0).size() > 0) {
                    hierarchies =
                        axisInfo(
                            writer, slicerAxis, slicerQueryAxis, "SlicerAxis");
                } else {
                    hierarchies = new ArrayList<Hierarchy>();
                }
            } else {
                // The slicer axes contains the default hierarchy
                // of each dimension not seen on another axis.
                List<Dimension> unseenDimensionList =
                new ArrayList<Dimension>(Arrays.asList(cube.getDimensions()));
                for (Hierarchy hier1 : axisHierarchyList) {
                    unseenDimensionList.remove(hier1.getDimension());
                }
                hierarchies = new ArrayList<Hierarchy>();
                for (Dimension dimension : unseenDimensionList) {
                    hierarchies.add(dimension.getHierarchy());
                }
                writer.startElement(
                    "AxisInfo",
                    "name", "SlicerAxis");
                writeHierarchyInfo(
                    writer, hierarchies, getProps(slicerQueryAxis));
                writer.endElement(); // AxisInfo
            }
            slicerAxisHierarchies = hierarchies;
            //
            ///////////////////////////////////////////////

            writer.endSequence(); // AxesInfo

            // -----------
            writer.startElement("CellInfo");
            cellProperty(writer, Property.VALUE, true, "Value");
            cellProperty(writer, Property.FORMATTED_VALUE, true, "FmtValue");
            cellProperty(writer, Property.FORMAT_STRING, true, "FormatString");
            cellProperty(writer, Property.LANGUAGE, false, "Language");
            cellProperty(writer, Property.BACK_COLOR, false, "BackColor");
            cellProperty(writer, Property.FORE_COLOR, false, "ForeColor");
            cellProperty(writer, Property.FONT_FLAGS, false, "FontFlags");
            writer.endElement(); // CellInfo
            // -----------
            writer.endElement(); // OlapInfo
        }

        private void cellProperty(
            SaxWriter writer,
            Property cellProperty,
            boolean evenEmpty,
            String elementName)
        {
            if (shouldReturnCellProperty(cellProperty, evenEmpty)) {
                writer.element(
                    elementName,
                    "name", cellProperty.name);
            }
        }

        private List<Hierarchy> axisInfo(
            SaxWriter writer,
            Axis axis,
            QueryAxis queryAxis,
            String axisName)
        {
            writer.startElement(
                "AxisInfo",
                "name", axisName);

            List<Hierarchy> hierarchies;
            Iterator<Position> it = axis.getPositions().iterator();
            if (it.hasNext()) {
                final Position position = it.next();
                hierarchies = new ArrayList<Hierarchy>();
                for (Member member : position) {
                    hierarchies.add(member.getHierarchy());
                }
            } else {
                hierarchies = Collections.emptyList();
                //final QueryAxis queryAxis = this.result.getQuery().axes[i];
                // TODO:
            }
            String[] props = getProps(queryAxis);
            writeHierarchyInfo(writer, hierarchies, props);

            writer.endElement(); // AxisInfo

            return hierarchies;
        }

        private void writeHierarchyInfo(
            SaxWriter writer,
            List<Hierarchy> hierarchies,
            String[] props)
        {
            writer.startSequence(null, "HierarchyInfo");
            for (Hierarchy hierarchy : hierarchies) {
                writer.startElement(
                    "HierarchyInfo",
                    "name", hierarchy.getName());
                for (final String prop : props) {
                    final String encodedProp = XmlaUtil.encodeElementName(prop);
                    writer.element(
                        encodedProp, getAttributes(encodedProp, hierarchy));
                }
                writer.endElement(); // HierarchyInfo
            }
            writer.endSequence(); // "HierarchyInfo"
        }

        private Object[] getAttributes(String prop, Hierarchy hierarchy) {
            String actualPropName = getPropertyName(prop);
            List<Object> values = new ArrayList<Object>();
            values.add("name");
            values.add(
                hierarchy.getUniqueName() + "." + Util.quoteMdxIdentifier(
                    actualPropName));
            if (longPropNames.get(prop) == null) {
                //Adding type attribute to the optional properties
                values.add("type");
                values.add(getXsdType(actualPropName));
            }
            return values.toArray();
        }

        private String getXsdType(String prop) {
            final Property property = Property.lookup(prop, false);
            if (property != null) {
                Property.Datatype datatype = property.getType();
                switch (datatype) {
                case TYPE_NUMERIC:
                    return RowsetDefinition.Type.UnsignedInteger.columnType;
                case TYPE_BOOLEAN:
                    return RowsetDefinition.Type.Boolean.columnType;
                }
            }
            return RowsetDefinition.Type.String.columnType;
        }

        private String getPropertyName(String prop) {
            String actualPropertyName = longPropNames.get(prop);
            if (actualPropertyName == null) {
                return prop;
            }
            return actualPropertyName;
        }

        private void axes(SaxWriter writer) {
            writer.startSequence("Axes", "Axis");
            //axis(writer, result.getSlicerAxis(), "SlicerAxis");
            final Axis[] axes = result.getAxes();
            final QueryAxis[] queryAxes = result.getQuery().getAxes();
            for (int i = 0; i < axes.length; i++) {
                final String[] props = getProps(queryAxes[i]);
                axis(writer, axes[i], props, "Axis" + i);
            }

            ////////////////////////////////////////////
            // now generate SlicerAxis information
            //
            if (omitDefaultSlicerInfo) {
                final QueryAxis slicerQueryAxis =
                    result.getQuery().getSlicerAxis();
                Axis slicerAxis = result.getSlicerAxis();
                // only add slicer axis element to response
                // if something is on the slicer
                if (slicerAxis.getPositions().get(0).size() > 0) {
                    axis(
                        writer,
                        result.getSlicerAxis(),
                        getProps(slicerQueryAxis),
                        "SlicerAxis");
                }
            } else {
                List<Hierarchy> hierarchies = slicerAxisHierarchies;
                writer.startElement(
                    "Axis",
                    "name", "SlicerAxis");
                writer.startSequence("Tuples", "Tuple");
                writer.startSequence("Tuple", "Member");

                Map<String, Integer> memberMap = new HashMap<String, Integer>();
                Member positionMember;
                Axis slicerAxis = result.getSlicerAxis();
                if (slicerAxis.getPositions() != null
                    && slicerAxis.getPositions().size() > 0)
                {
                    final Position pos0 = slicerAxis.getPositions().get(0);
                    int i = 0;
                    for (Member member : pos0) {
                        memberMap.put(member.getHierarchy().getName(), i++);
                    }
                }

                final QueryAxis slicerQueryAxis =
                    result.getQuery().getSlicerAxis();
                final List<Member> slicerMembers =
                    result.getSlicerAxis().getPositions().get(0);
                for (Hierarchy hierarchy : hierarchies) {
                    // Find which member is on the slicer.
                    // If it's not explicitly there, use the default member.
                    Member member = hierarchy.getDefaultMember();
                    final Integer indexPosition =
                        memberMap.get(hierarchy.getName());
                    if (indexPosition != null) {
                        positionMember =
                            slicerAxis.getPositions().get(0).get(indexPosition);
                    } else {
                        positionMember = null;
                    }
                    for (Member slicerMember : slicerMembers) {
                        if (slicerMember.getHierarchy().equals(hierarchy)) {
                            member = slicerMember;
                            break;
                        }
                    }

                    if (member != null) {
                        if (positionMember != null) {
                            writeMember(
                                writer, positionMember, null,
                                slicerAxis.getPositions().get(0), indexPosition,
                                getProps(slicerQueryAxis));
                        } else {
                            slicerAxis(
                                writer, member, getProps(slicerQueryAxis));
                        }
                    } else {
                        LOGGER.warn(
                            "Can not create SlicerAxis: "
                            + "null default member for Hierarchy "
                            + hierarchy.getUniqueName());
                    }
                }
                writer.endSequence(); // Tuple
                writer.endSequence(); // Tuples
                writer.endElement(); // Axis
            }

            //
            ////////////////////////////////////////////

            writer.endSequence(); // Axes
        }

        private String[] getProps(QueryAxis queryAxis) {
            if (queryAxis == null) {
                return defaultProps;
            }
            Id[] dimensionProperties = queryAxis.getDimensionProperties();
            if (dimensionProperties.length == 0) {
                return defaultProps;
            }
            String[] props =
                new String[defaultProps.length + dimensionProperties.length];
            System.arraycopy(defaultProps, 0, props, 0, defaultProps.length);
            for (int i = 0; i < dimensionProperties.length; i++) {
                // If a property is compound [Foo].[Bar], use only the last
                // segment "Bar".
                final List<Id.Segment> segmentList =
                    dimensionProperties[i].getSegments();
                props[defaultProps.length + i] =
                    segmentList.get(segmentList.size() - 1).name;
            }
            return props;
        }

        private void axis(
            SaxWriter writer,
            Axis axis,
            String[] props,
            String axisName)
        {
            writer.startElement(
                "Axis",
                "name", axisName);
            writer.startSequence("Tuples", "Tuple");

            List<Position> positions = axis.getPositions();
            Iterator<Position> pit = positions.iterator();
            Position prevPosition = null;
            Position position = pit.hasNext() ? pit.next() : null;
            Position nextPosition = pit.hasNext() ? pit.next() : null;
            while (position != null) {
                writer.startSequence("Tuple", "Member");
                int k = 0;
                for (Member member : position) {
                    writeMember(
                        writer, member, prevPosition, nextPosition, k++, props);
                }
                writer.endSequence(); // Tuple
                prevPosition = position;
                position = nextPosition;
                nextPosition = pit.hasNext() ? pit.next() : null;
            }
            writer.endSequence(); // Tuples
            writer.endElement(); // Axis
        }

        private void writeMember(
            SaxWriter writer,
            Member member,
            Position prevPosition,
            Position nextPosition,
            int k,
            String[] props)
        {
            writer.startElement(
                "Member",
                "Hierarchy", member.getHierarchy().getName());
            for (String prop : props) {
                Object value;
                String propLong = longPropNames.get(prop);
                if (propLong == null) {
                    propLong = prop;
                }
                if (propLong.equals(Property.DISPLAY_INFO.name)) {
                    Integer childrenCard = (Integer) member
                      .getPropertyValue(Property.CHILDREN_CARDINALITY.name);
                    value = calculateDisplayInfo(
                        prevPosition,
                        nextPosition,
                        member, k, childrenCard);
                } else if (propLong.equals(Property.DEPTH.name)) {
                    value = member.getDepth();
                } else {
                    value = member.getPropertyValue(propLong);
                }
                if (value != null) {
                    writer.textElement(XmlaUtil.encodeElementName(prop), value);
                }
            }

            writer.endElement(); // Member
        }

        private void slicerAxis(
            SaxWriter writer, Member member, String[] props)
        {
            writer.startElement(
                "Member",
                "Hierarchy", member.getHierarchy().getName());
            for (String prop : props) {
                Object value;
                String propLong = longPropNames.get(prop);
                if (propLong == null) {
                    propLong = prop;
                }
                if (propLong.equals(Property.DISPLAY_INFO.name)) {
                    Integer childrenCard =
                        (Integer) member.getPropertyValue(
                            Property.CHILDREN_CARDINALITY.name);
                    // NOTE: don't know if this is correct for
                    // SlicerAxis
                    int displayInfo = 0xffff & childrenCard;
/*
                    int displayInfo =
                        calculateDisplayInfo((j == 0 ? null : positions[j - 1]),
                          (j + 1 == positions.length ? null : positions[j + 1]),
                          member, k, childrenCard.intValue());
*/
                    value = displayInfo;
                } else if (propLong.equals(Property.DEPTH.name)) {
                    value = member.getDepth();
                } else {
                    value = member.getPropertyValue(propLong);
                }
                if (value != null) {
                    writer.textElement(prop, value);
                }
            }
            writer.endElement(); // Member
        }

        private int calculateDisplayInfo(
            Position prevPosition, Position nextPosition,
            Member currentMember, int memberOrdinal, int childrenCount)
        {
            int displayInfo = 0xffff & childrenCount;

            if (nextPosition != null) {
                String currentUName = currentMember.getUniqueName();
                Member nextMember = nextPosition.get(memberOrdinal);
                String nextParentUName = nextMember.getParentUniqueName();
                if (currentUName.equals(nextParentUName)) {
                    displayInfo |= 0x10000;
                }
            }
            if (prevPosition != null) {
                String currentParentUName = currentMember.getParentUniqueName();
                Member prevMember = prevPosition.get(memberOrdinal);
                String prevParentUName = prevMember.getParentUniqueName();
                if (currentParentUName != null
                    && currentParentUName.equals(prevParentUName))
                {
                    displayInfo |= 0x20000;
                }
            }
            return displayInfo;
        }

        private void cellData(SaxWriter writer) {
            writer.startSequence("CellData", "Cell");
            final int axisCount = result.getAxes().length;
            int[] pos = new int[axisCount];
            int[] cellOrdinal = new int[] {0};

            Evaluator evaluator = RolapUtil.createEvaluator(result.getQuery());
            int axisOrdinal = axisCount - 1;
            recurse(writer, pos, axisOrdinal, evaluator, cellOrdinal);

            writer.endSequence(); // CellData
        }

        private void recurse(
            SaxWriter writer, int[] pos,
            int axisOrdinal, Evaluator evaluator, int[] cellOrdinal)
        {
            if (axisOrdinal < 0) {
                emitCell(writer, pos, evaluator, cellOrdinal[0]++);

            } else {
                Axis axis = result.getAxes()[axisOrdinal];
                List<Position> positions = axis.getPositions();
                int i = 0;
                for (Position position : positions) {
                    pos[axisOrdinal] = i;
                    evaluator.setContext(position);
                    recurse(
                        writer, pos, axisOrdinal - 1, evaluator, cellOrdinal);
                    i++;
                }
            }
        }

        private void emitCell(
            SaxWriter writer, int[] pos,
            Evaluator evaluator, int ordinal)
        {
            Cell cell = result.getCell(pos);
            if (cell.isNull() && ordinal != 0) {
                // Ignore null cell like MS AS, except for Oth ordinal
                return;
            }

            writer.startElement(
                "Cell",
                "CellOrdinal", ordinal);
            for (int i = 0; i < cellProps.length; i++) {
                Property cellPropLong = cellPropLongs[i];
                Object value = cell.getPropertyValue(cellPropLong.name);
                if (value == null) {
                    continue;
                }
                if (!shouldReturnCellProperty(cellPropLong, true)) {
                    continue;
                }

                if (!json && cellPropLong == Property.VALUE) {
                    if (cell.isNull()) {
                        // Return cell without value as in case of AS2005
                        continue;
                    }
                    final String dataType = (String)
                        evaluator.getProperty(
                            Property.DATATYPE.getName(), null);
                    final ValueInfo vi = new ValueInfo(dataType, value);
                    final String valueType = vi.valueType;
                    final String valueString;
                    if (vi.isDecimal) {
                        valueString =
                            XmlaUtil.normalizeNumericString(
                                vi.value.toString());
                    } else {
                        valueString = vi.value.toString();
                    }

                    writer.startElement(
                        cellProps[i],
                        "xsi:type", valueType);
                    writer.characters(valueString);
                    writer.endElement();
                } else {
                    writer.textElement(cellProps[i], value);
                }
            }
            writer.endElement(); // Cell
        }

        /**
         * Returns whether we should return a cell property in the XMLA result.
         *
         * @param cellProperty Cell property definition
         * @param evenEmpty Whether to return even if cell has no properties
         * @return Whether to return cell property in XMLA result
         */
        private boolean shouldReturnCellProperty(
            Property cellProperty,
            boolean evenEmpty)
        {
            Query query = result.getQuery();
            return
                (evenEmpty
                 && query.isCellPropertyEmpty())
                || query.hasCellProperty(cellProperty.name);
        }
    }

    static abstract class ColumnHandler {
        protected final String name;
        protected final String encodedName;

        protected ColumnHandler(String name) {
            this.name = name;
            this.encodedName = XmlaUtil.encodeElementName(this.name);
        }

        abstract void write(SaxWriter writer, Cell cell, Member[] members);
        abstract void metadata(SaxWriter writer);
    }


    /**
     * Callback to handle one column, representing the combination of a
     * level and a property (e.g. [Store].[Store State].[MEMBER_UNIQUE_NAME])
     * in a flattened dataset.
     */
    static class CellColumnHandler extends ColumnHandler {

        CellColumnHandler(String name) {
            super(name);
        }

        public void metadata(SaxWriter writer) {
            writer.element(
                "xsd:element",
                "minOccurs", 0,
                "name", encodedName,
                "sql:field", name);
        }

        public void write(
            SaxWriter writer, Cell cell, Member[] members)
        {
            if (cell.isNull()) {
                return;
            }
            Object value = cell.getValue();
/*
            String valueString = value.toString();
            String valueType = deduceValueType(cell, value);

            writer.startElement(encodedName,
                "xsi:type", valueType});
            if (value instanceof Number) {
                valueString = XmlaUtil.normalizeNumericString(valueString);
            }
            writer.characters(valueString);
            writer.endElement();
*/
            final String dataType = (String)
                    cell.getPropertyValue(Property.DATATYPE.getName());

            final ValueInfo vi = new ValueInfo(dataType, value);
            final String valueType = vi.valueType;
            value = vi.value;
            boolean isDecimal = vi.isDecimal;

            String valueString = value.toString();

            writer.startElement(
                encodedName,
                "xsi:type", valueType);
            if (isDecimal) {
                valueString = XmlaUtil.normalizeNumericString(valueString);
            }
            writer.characters(valueString);
            writer.endElement();
        }
    }

    /**
     * Callback to handle one column, representing the combination of a
     * level and a property (e.g. [Store].[Store State].[MEMBER_UNIQUE_NAME])
     * in a flattened dataset.
     */
    static class MemberColumnHandler extends ColumnHandler {
        private final String property;
        private final Level level;
        private final int memberOrdinal;

        public MemberColumnHandler(
            String property, Level level, int memberOrdinal)
        {
            super(
                level.getUniqueName() + "."
                + Util.quoteMdxIdentifier(property));
            this.property = property;
            this.level = level;
            this.memberOrdinal = memberOrdinal;
        }

        public void metadata(SaxWriter writer) {
            writer.element(
                "xsd:element",
                "minOccurs", 0,
                "name", encodedName,
                "sql:field", name,
                "type", XSD_STRING);
        }

        public void write(
            SaxWriter writer, Cell cell, Member[] members)
        {
            Member member = members[memberOrdinal];
            final int depth = level.getDepth();
            if (member.getDepth() < depth) {
                // This column deals with a level below the current member.
                // There is no value to write.
                return;
            }
            while (member.getDepth() > depth) {
                member = member.getParentMember();
            }
            final Object propertyValue = member.getPropertyValue(property);
            if (propertyValue == null) {
                return;
            }

            writer.startElement(encodedName);
            writer.characters(propertyValue.toString());
            writer.endElement();
        }
    }

    static class MDDataSet_Tabular extends MDDataSet {
        private final boolean empty;
        private final int[] pos;
        private final int axisCount;
        private int cellOrdinal;

        private static final Id[] MemberCaptionIdArray = {
            new Id(
                new Id.Segment(
                    Property.MEMBER_CAPTION.name,
                    Id.Quoting.QUOTED))
        };
        private final Member[] members;
        private final ColumnHandler[] columnHandlers;

        public MDDataSet_Tabular(Result result) {
            super(result);
            final Axis[] axes = result.getAxes();
            axisCount = axes.length;
            pos = new int[axisCount];

            // Count dimensions, and deduce list of levels which appear on
            // non-COLUMNS axes.
            boolean empty = false;
            int dimensionCount = 0;
            for (int i = axes.length - 1; i > 0; i--) {
                Axis axis = axes[i];
                if (axis.getPositions().size() == 0) {
                    // If any axis is empty, the whole data set is empty.
                    empty = true;
                    continue;
                }
                dimensionCount += axis.getPositions().get(0).size();
            }
            this.empty = empty;

            // Build a list of the lowest level used on each non-COLUMNS axis.
            Level[] levels = new Level[dimensionCount];
            List<ColumnHandler> columnHandlerList =
                new ArrayList<ColumnHandler>();
            int memberOrdinal = 0;
            if (!empty) {
                for (int i = axes.length - 1; i > 0; i--) {
                    final Axis axis = axes[i];
                    final QueryAxis queryAxis = result.getQuery().getAxes()[i];
                    final int z0 = memberOrdinal; // save ordinal so can rewind
                    final List<Position> positions = axis.getPositions();
                    int jj = 0;
                    for (Position position : positions) {
                        memberOrdinal = z0; // rewind to start
                        for (Member member : position) {
                            if (jj == 0
                                || member.getLevel().getDepth()
                                > levels[memberOrdinal].getDepth())
                            {
                                levels[memberOrdinal] = member.getLevel();
                            }
                            memberOrdinal++;
                        }
                        jj++;
                    }

                    // Now we know the lowest levels on this axis, add
                    // properties.
                    Id[] dimProps = queryAxis.getDimensionProperties();
                    if (dimProps.length == 0) {
                        dimProps = MemberCaptionIdArray;
                    }
                    for (int j = z0; j < memberOrdinal; j++) {
                        Level level = levels[j];
                        for (int k = 0; k <= level.getDepth(); k++) {
                            final Level level2 =
                                    level.getHierarchy().getLevels()[k];
                            if (level2.isAll()) {
                                continue;
                            }
                            for (Id dimProp : dimProps) {
                                columnHandlerList.add(
                                    new MemberColumnHandler(
                                        dimProp.toStringArray()[0],
                                        level2,
                                        j));
                            }
                        }
                    }
                }
            }
            this.members = new Member[memberOrdinal + 1];

            // Deduce the list of column headings.
            if (axes.length > 0) {
                Axis columnsAxis = axes[0];
                for (Position position : columnsAxis.getPositions()) {
                    String name = null;
                    int j = 0;
                    for (Member member : position) {
                        if (j == 0) {
                            name = member.getUniqueName();
                        } else {
                            name = name + "." + member.getUniqueName();
                        }
                        j++;
                    }
                    columnHandlerList.add(
                        new CellColumnHandler(name));
                }
            }

            this.columnHandlers =
                columnHandlerList.toArray(
                    new ColumnHandler[columnHandlerList.size()]);
        }

        public void metadata(SaxWriter writer) {
            // ADOMD wants a XSD even a void one.
//            if (empty) {
//                return;
//            }

            writer.startElement(
                "xsd:schema",
                "xmlns:xsd", NS_XSD,
                "targetNamespace", NS_XMLA_ROWSET,
                "xmlns", NS_XMLA_ROWSET,
                "xmlns:xsi", NS_XSI,
                "xmlns:sql", NS_XML_SQL,
                "elementFormDefault", "qualified");

            { // <root>
                writer.startElement(
                    "xsd:element",
                    "name", "root");
                writer.startElement("xsd:complexType");
                writer.startElement("xsd:sequence");
                writer.element(
                    "xsd:element",
                    "maxOccurs", "unbounded",
                    "minOccurs", 0,
                    "name", "row",
                    "type", "row");
                writer.endElement(); // xsd:sequence
                writer.endElement(); // xsd:complexType
                writer.endElement(); // xsd:element name=root
            }

            { // xsd:simpleType name="uuid"
                writer.startElement(
                    "xsd:simpleType",
                    "name", "uuid");
                writer.startElement(
                    "xsd:restriction",
                    "base", XSD_STRING);
                writer.element(
                    "xsd:pattern",
                    "value", RowsetDefinition.UUID_PATTERN);
                writer.endElement(); // xsd:restriction
                writer.endElement(); // xsd:simpleType
            }

            { // xsd:complexType name="row"
                writer.startElement(
                    "xsd:complexType",
                    "name", "row");
                writer.startElement("xsd:sequence");
                for (ColumnHandler columnHandler : columnHandlers) {
                    columnHandler.metadata(writer);
                }
                writer.endElement(); // xsd:sequence
                writer.endElement(); // xsd:complexType
            }
            writer.endElement(); // xsd:schema
        }

        public void unparse(SaxWriter writer) throws SAXException {
            if (empty) {
                return;
            }
            cellData(writer);
        }

        private void cellData(SaxWriter writer) throws SAXException {
            cellOrdinal = 0;
            iterate(writer);
        }

        /**
         * Iterates over the resust writing tabular rows.
         *
         * @param writer Writer
         * @throws org.xml.sax.SAXException on error
         */
        private void iterate(SaxWriter writer) throws SAXException {
            switch (axisCount) {
            case 0:
                // For MDX like: SELECT FROM Sales
                emitCell(writer, result.getCell(pos));
                return;
            default:
//                throw new SAXException("Too many axes: " + axisCount);
                iterate(writer, axisCount - 1, 0);
                break;
            }
        }

        private void iterate(SaxWriter writer, int axis, final int xxx) {
            final List<Position> positions =
                result.getAxes()[axis].getPositions();
            int axisLength = axis == 0 ? 1 : positions.size();

            for (int i = 0; i < axisLength; i++) {
                final Position position = positions.get(i);
                int ho = xxx;
                for (int j = 0;
                     j < position.size() && ho < members.length;
                     j++, ho++)
                {
                    members[ho] = position.get(j);
                }

                ++cellOrdinal;
                Util.discard(cellOrdinal);

                if (axis >= 2) {
                    iterate(writer, axis - 1, ho);
                } else {
                    writer.startElement("row");//abrimos la fila
                    pos[axis] = i; //coordenadas: fila i
                    pos[0] = 0; //coordenadas (0,i): columna 0
                    for (ColumnHandler columnHandler : columnHandlers) {
                        if (columnHandler instanceof MemberColumnHandler) {
                            columnHandler.write(writer, null, members);
                        } else if (columnHandler instanceof CellColumnHandler) {
                            columnHandler.write(
                                writer, result.getCell(pos), null);
                            pos[0]++;// next col.
                        }
                    }
                    writer.endElement();//cerramos la fila
                }
            }
        }

        private void emitCell(SaxWriter writer, Cell cell) {
            ++cellOrdinal;
            Util.discard(cellOrdinal);

            // Ignore empty cells.
            final Object cellValue = cell.getValue();
            if (cellValue == null) {
                return;
            }

            writer.startElement("row");
            for (ColumnHandler columnHandler : columnHandlers) {
                columnHandler.write(writer, cell, members);
            }
            writer.endElement();
        }
    }

    private void discover(XmlaRequest request, XmlaResponse response)
        throws XmlaException
    {
        final RowsetDefinition rowsetDefinition =
            RowsetDefinition.valueOf(request.getRequestType());
        Rowset rowset = rowsetDefinition.getRowset(request, this);

        Format format = getFormat(request, Format.Tabular);
        if (format != Format.Tabular) {
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_DISCOVER_FORMAT_CODE,
                HSB_DISCOVER_FORMAT_FAULT_FS,
                new UnsupportedOperationException(
                    "<Format>: only 'Tabular' allowed in Discover method "
                    + "type"));
        }
        final Content content = getContent(request);

        SaxWriter writer = response.getWriter();
        writer.startDocument();

        writer.startElement(
            prefix + ":DiscoverResponse",
            "xmlns:" + prefix, NS_XMLA);
        writer.startElement(prefix + ":return");
        writer.startElement(
            "root",
            "xmlns", NS_XMLA_ROWSET,
            "xmlns:xsi", NS_XSI,
            "xmlns:xsd", NS_XSD,
            "xmlns:EX", NS_XMLA_EX);

        if ((content == Content.Schema)
            || (content == Content.SchemaData))
        {
            rowset.rowsetDefinition.writeRowsetXmlSchema(writer);
        }

        try {
            if ((content == Content.Data)
                || (content == Content.SchemaData))
            {
                rowset.unparse(response);
            }
        } catch (XmlaException xex) {
            throw xex;
        } catch (Throwable t) {
            throw new XmlaException(
                SERVER_FAULT_FC,
                HSB_DISCOVER_UNPARSE_CODE,
                HSB_DISCOVER_UNPARSE_FAULT_FS,
                t);
        } finally {
            // keep the tags balanced, even if there's an error
            writer.endElement();
            writer.endElement();
            writer.endElement();
        }

        writer.endDocument();
    }

    /**
     * Gets a Connection given a catalog (and implicitly the catalog's data
     * source) and a user role.
     *
     * @param catalog Catalog
     * @param role User role
     * @param roleName User role name
     * @return Connection
     * @throws XmlaException If error occurs
     */
    protected Connection getConnection(
        final DataSourcesConfig.Catalog catalog,
        final Role role,
        final String roleName)
        throws XmlaException
    {
        DataSourcesConfig.DataSource ds = catalog.getDataSource();

        Util.PropertyList connectProperties =
            Util.parseConnectString(catalog.getDataSourceInfo());

        String catalogUrl = catalogLocator.locate(catalog.definition);

        if (LOGGER.isDebugEnabled()) {
            if (catalogUrl == null) {
                LOGGER.debug("XmlaHandler.getConnection: catalogUrl is null");
            } else {
                LOGGER.debug(
                    "XmlaHandler.getConnection: catalogUrl=" + catalogUrl);
            }
        }

        connectProperties.put(
            RolapConnectionProperties.Catalog.name(), catalogUrl);

        // Checking access
        if (!DataSourcesConfig.DataSource.AUTH_MODE_UNAUTHENTICATED
            .equalsIgnoreCase(
                ds.getAuthenticationMode())
            && (role == null)
            && (roleName == null))
        {
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_ACCESS_DENIED_CODE,
                HSB_ACCESS_DENIED_FAULT_FS,
                new SecurityException(
                    "Access denied for data source needing authentication"));
        }

        // Role in request overrides role in connect string, if present.
        if (roleName != null) {
            connectProperties.put(
                RolapConnectionProperties.Role.name(), roleName);
        }

        RolapConnection conn = (RolapConnection) DriverManager.getConnection(
                connectProperties, null);

        if (role != null) {
            conn.setRole(role);
        }

        if (LOGGER.isDebugEnabled()) {
            if (conn == null) {
                LOGGER.debug(
                    "XmlaHandler.getConnection: returning connection null");
            } else {
                LOGGER.debug(
                    "XmlaHandler.getConnection: returning connection not null");
            }
        }
        return conn;
    }

    /**
     * Returns the DataSource associated with the request property or null if
     * one was not specified.
     *
     * @param request Request
     * @return DataSource for this request
     * @throws XmlaException If error occurs
     */
    public DataSourcesConfig.DataSource getDataSource(XmlaRequest request)
        throws XmlaException
    {
        Map<String, String> properties = request.getProperties();
        final String dataSourceInfo =
            properties.get(PropertyDefinition.DataSourceInfo.name());
        if (!dataSourcesMap.containsKey(dataSourceInfo)) {
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_CONNECTION_DATA_SOURCE_CODE,
                HSB_CONNECTION_DATA_SOURCE_FAULT_FS,
                Util.newError(
                    "no data source is configured with name '"
                    + dataSourceInfo + "'"));
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "XmlaHandler.getDataSource: dataSourceInfo="
                + dataSourceInfo);
        }

        final DataSourcesConfig.DataSource ds =
            dataSourcesMap.get(dataSourceInfo);
        if (LOGGER.isDebugEnabled()) {
            if (ds == null) {
                // TODO: this if a failure situation
                LOGGER.debug("XmlaHandler.getDataSource: ds is null");
            } else {
                LOGGER.debug(
                    "XmlaHandler.getDataSource: ds.dataSourceInfo="
                    + ds.getDataSourceInfo());
            }
        }
        return ds;
    }

    /**
     * Get the DataSourcesConfig.Catalog with the given catalog name from the
     * DataSource's catalogs if there is a match and otherwise return null.
     *
     * @param ds DataSource
     * @param catalogName Catalog name
     * @return DataSourcesConfig.Catalog or null
     */
    public DataSourcesConfig.Catalog getCatalog(
        DataSourcesConfig.DataSource ds,
        String catalogName)
    {
        DataSourcesConfig.Catalog[] catalogs = ds.catalogs.catalogs;
        if (catalogName == null) {
            // if there is no catalog name - its optional and there is
            // only one, then return it.
            if (catalogs.length == 1) {
                return catalogs[0];
            }
        } else {
            for (DataSourcesConfig.Catalog dsCatalog : catalogs) {
                if (catalogName.equals(dsCatalog.name)) {
                    return dsCatalog;
                }
            }
        }
        return null;
    }

    /**
     * Get array of DataSourcesConfig.Catalog returning only one entry if the
     * catalog was specified as a property in the request or all catalogs
     * associated with the Datasource if there was no catalog property.
     *
     * @param request Request
     * @param ds DataSource
     * @return Array of DataSourcesConfig.Catalog
     */
    public DataSourcesConfig.Catalog[] getCatalogs(
        XmlaRequest request,
        DataSourcesConfig.DataSource ds)
    {
        Map<String, String> properties = request.getProperties();
        final String catalogName =
            properties.get(PropertyDefinition.Catalog.name());
        if (catalogName != null) {
            DataSourcesConfig.Catalog dsCatalog = getCatalog(ds, catalogName);
            return new DataSourcesConfig.Catalog[] { dsCatalog };
        } else {
            // no catalog specified in Properties so return them all
            return ds.catalogs.catalogs;
        }
    }

    /**
     * Returns the DataSourcesConfig.Catalog associated with the
     * catalog name that is part of the request properties or
     * null if there is no catalog with that name.
     *
     * @param request Request
     * @param ds DataSource
     * @param required Whether to throw an error if catalog name is not
     * specified
     *
     * @return DataSourcesConfig Catalog or null
     * @throws XmlaException If error occurs
     */
    public DataSourcesConfig.Catalog getCatalog(
        XmlaRequest request,
        DataSourcesConfig.DataSource ds,
        boolean required)
        throws XmlaException
    {
        Map<String, String> properties = request.getProperties();
        final String catalogName =
            properties.get(PropertyDefinition.Catalog.name());
        DataSourcesConfig.Catalog dsCatalog = getCatalog(ds, catalogName);
        if (dsCatalog == null) {
            if (catalogName == null) {
                if (required) {
                    throw new XmlaException(
                        CLIENT_FAULT_FC,
                        HSB_CONNECTION_DATA_SOURCE_CODE,
                        HSB_CONNECTION_DATA_SOURCE_FAULT_FS,
                        Util.newError("catalog not specified"));
                }
                return null;
            }
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_CONNECTION_DATA_SOURCE_CODE,
                HSB_CONNECTION_DATA_SOURCE_FAULT_FS,
                Util.newError("no catalog named '" + catalogName + "'"));
        }
        return dsCatalog;
    }

    public static void main(String[] args) {
        for (RowsetDefinition def : RowsetDefinition.values()) {
            System.out.print("    " + def.name() + "(");
            int k = 0;
            for (RowsetDefinition.Column column : def.columnDefinitions) {
                if (k++ == 0) {
                    System.out.println();
                } else {
                    System.out.println(",");
                }
                System.out.print(
                    "        new MetadataColumn(\"" + column.name + "\")");
            }
            System.out.println("),");
        }
    }
}

// End XmlaHandler.java
