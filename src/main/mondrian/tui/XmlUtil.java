/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2005-2005 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/

package mondrian.tui;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.TooManyListenersException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import org.xml.sax.InputSource;
import org.xml.sax.ErrorHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.SAXParseException;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.w3c.dom.DOMError;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Text;
import org.w3c.dom.Attr;
import org.w3c.dom.xpath.XPathResult;
import org.w3c.dom.xpath.XPathEvaluator;
import org.w3c.dom.xpath.XPathException;
import org.w3c.dom.xpath.XPathNSResolver;
import org.apache.xml.serialize.XMLSerializer;
import org.apache.xml.serialize.OutputFormat;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.parsers.DOMParser;
import org.apache.xerces.dom.DocumentImpl;
import org.apache.xpath.domapi.XPathEvaluatorImpl;

/** 
 * Some XML parsing, validation and transform utility methods used
 * to valiate XMLA responses.
 * 
 * @author <a>Richard M. Emberson</a>
 * @version 
 */
public class XmlUtil {

    public static final String LINE_SEP = 
            System.getProperty("line.separator", "\n");


    public static final String SOAP_PREFIX = "SOAP-ENV";
    public static final String XSD_PREFIX = "xsd";

    public static final String XMLNS = "xmlns";

    public static final String NAMESPACES_FEATURE_ID =
              "http://xml.org/sax/features/namespaces";
    public static final String VALIDATION_FEATURE_ID =
              "http://xml.org/sax/features/validation";

    public static final String SCHEMA_VALIDATION_FEATURE_ID =
              "http://apache.org/xml/features/validation/schema";
    public static final String FULL_SCHEMA_VALIDATION_FEATURE_ID =
              "http://apache.org/xml/features/validation/schema-full-checking";

    public static final String DEFER_NODE_EXPANSION =
              "http://apache.org/xml/features/dom/defer-node-expansion";

    public static final String SCHEMA_LOCATION =
                Constants.XERCES_PROPERTY_PREFIX + Constants.SCHEMA_LOCATION;

    /**
     * This is the xslt that can extract the "data" part of a SOAP 
     * XMLA response.
     */
    public static final String SOAP_XMLA_XDS2XD =
        "<?xml version='1.0'?>" + LINE_SEP +
        "<xsl:stylesheet " + LINE_SEP +
            "xmlns:xsl='http://www.w3.org/1999/XSL/Transform' " + LINE_SEP +
            "xmlns:xalan='http://xml.apache.org/xslt' " + LINE_SEP +
            "xmlns:xsd='http://www.w3.org/2001/XMLSchema' " + LINE_SEP +
            "xmlns:ROW='urn:schemas-microsoft-com:xml-analysis:rowset' " + LINE_SEP +
            "xmlns:SOAP-ENV='http://schemas.xmlsoap.org/soap/envelope/' " + LINE_SEP +
            "xmlns:xmla='urn:schemas-microsoft-com:xml-analysis' " + LINE_SEP +
            "version='1.0' " + LINE_SEP +
                "> " + LINE_SEP +
        "<xsl:output method='xml'  " + LINE_SEP +
            "encoding='UTF-8' " + LINE_SEP +
            "indent='yes'  " + LINE_SEP +
            "xalan:indent-amount='2'/> " + LINE_SEP +
            "  " + LINE_SEP +
            "<!-- consume '/' and apply --> " + LINE_SEP +
            "<xsl:template match='/'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'Envelope' and apply --> " + LINE_SEP +
            "<xsl:template match='SOAP-ENV:Envelope'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'Header' --> " + LINE_SEP +
            "<xsl:template match='SOAP-ENV:Header'> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'Body' and apply --> " + LINE_SEP +
            "<xsl:template match='SOAP-ENV:Body'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'DiscoverResponse' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:DiscoverResponse'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'return' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:return'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'xsd:schema' --> " + LINE_SEP +
            "<xsl:template match='xsd:schema'> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- copy everything else --> " + LINE_SEP +
            "<xsl:template match='*|@*'> " + LINE_SEP +
                "<xsl:copy> " + LINE_SEP +
                    "<xsl:apply-templates select='@*|node()'/> " + LINE_SEP +
                "</xsl:copy> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
        "</xsl:stylesheet>";

    /**
     * This is the xslt that can extract the "schema" part of a SOAP
     * XMLA response.
     */
    public static final String SOAP_XMLA_XDS2XS =
        "<?xml version='1.0'?> " + LINE_SEP +
        "<xsl:stylesheet  " + LINE_SEP +
            "xmlns:xsl='http://www.w3.org/1999/XSL/Transform'  " + LINE_SEP +
            "xmlns:xalan='http://xml.apache.org/xslt' " + LINE_SEP +
            "xmlns:xsd='http://www.w3.org/2001/XMLSchema' " + LINE_SEP +
            "xmlns:ROW='urn:schemas-microsoft-com:xml-analysis:rowset' " + LINE_SEP +
            "xmlns:SOAP-ENV='http://schemas.xmlsoap.org/soap/envelope/'  " + LINE_SEP +
            "xmlns:xmla='urn:schemas-microsoft-com:xml-analysis' " + LINE_SEP +
            "version='1.0' " + LINE_SEP +
                "> " + LINE_SEP +
        "<xsl:output method='xml'  " + LINE_SEP +
            "encoding='UTF-8' " + LINE_SEP +
            "indent='yes'  " + LINE_SEP +
            "xalan:indent-amount='2'/> " + LINE_SEP +
            "<!-- consume '/' and apply --> " + LINE_SEP +
            "<xsl:template match='/'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'Envelope' and apply --> " + LINE_SEP +
            "<xsl:template match='SOAP-ENV:Envelope'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'Header' --> " + LINE_SEP +
            "<xsl:template match='SOAP-ENV:Header'> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'Body' and apply --> " + LINE_SEP +
            "<xsl:template match='SOAP-ENV:Body'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'DiscoverResponse' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:DiscoverResponse'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'return' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:return'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'root' and apply --> " + LINE_SEP +
            "<xsl:template match='ROW:root'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'row' --> " + LINE_SEP +
            "<xsl:template match='ROW:row'> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- copy everything else --> " + LINE_SEP +
            "<xsl:template match='*|@*'> " + LINE_SEP +
                "<xsl:copy> " + LINE_SEP +
                    "<xsl:apply-templates select='@*|node()'/> " + LINE_SEP +
                "</xsl:copy> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
        "</xsl:stylesheet>";

    /**
     * This is the xslt that can extract the "data" part of a XMLA response.
     */
    public static final String XMLA_XDS2XD =
        "<?xml version='1.0'?>" + LINE_SEP +
        "<xsl:stylesheet " + LINE_SEP +
            "xmlns:xsl='http://www.w3.org/1999/XSL/Transform' " + LINE_SEP +
            "xmlns:xalan='http://xml.apache.org/xslt' " + LINE_SEP +
            "xmlns:xsd='http://www.w3.org/2001/XMLSchema' " + LINE_SEP +
            "xmlns:ROW='urn:schemas-microsoft-com:xml-analysis:rowset' " + LINE_SEP +
            "xmlns:SOAP-ENV='http://schemas.xmlsoap.org/soap/envelope/' " + LINE_SEP +
            "xmlns:xmla='urn:schemas-microsoft-com:xml-analysis' " + LINE_SEP +
            "version='1.0' " + LINE_SEP +
                "> " + LINE_SEP +
        "<xsl:output method='xml'  " + LINE_SEP +
            "encoding='UTF-8' " + LINE_SEP +
            "indent='yes'  " + LINE_SEP +
            "xalan:indent-amount='2'/> " + LINE_SEP +
            "  " + LINE_SEP +
            "<!-- consume '/' and apply --> " + LINE_SEP +
            "<xsl:template match='/'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'DiscoverResponse' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:DiscoverResponse'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'return' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:return'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'xsd:schema' --> " + LINE_SEP +
            "<xsl:template match='xsd:schema'> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- copy everything else --> " + LINE_SEP +
            "<xsl:template match='*|@*'> " + LINE_SEP +
                "<xsl:copy> " + LINE_SEP +
                    "<xsl:apply-templates select='@*|node()'/> " + LINE_SEP +
                "</xsl:copy> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
        "</xsl:stylesheet>";

    /**
     * This is the xslt that can extract the "schema" part of a XMLA response.
     */
    public static final String XMLA_XDS2XS =
        "<?xml version='1.0'?> " + LINE_SEP +
        "<xsl:stylesheet  " + LINE_SEP +
            "xmlns:xsl='http://www.w3.org/1999/XSL/Transform'  " + LINE_SEP +
            "xmlns:xalan='http://xml.apache.org/xslt' " + LINE_SEP +
            "xmlns:xsd='http://www.w3.org/2001/XMLSchema' " + LINE_SEP +
            "xmlns:ROW='urn:schemas-microsoft-com:xml-analysis:rowset' " + LINE_SEP +
            "xmlns:SOAP-ENV='http://schemas.xmlsoap.org/soap/envelope/'  " + LINE_SEP +
            "xmlns:xmla='urn:schemas-microsoft-com:xml-analysis' " + LINE_SEP +
            "version='1.0' " + LINE_SEP +
                "> " + LINE_SEP +
        "<xsl:output method='xml'  " + LINE_SEP +
            "encoding='UTF-8' " + LINE_SEP +
            "indent='yes'  " + LINE_SEP +
            "xalan:indent-amount='2'/> " + LINE_SEP +
            "<!-- consume '/' and apply --> " + LINE_SEP +
            "<xsl:template match='/'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'DiscoverResponse' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:DiscoverResponse'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'return' and apply --> " + LINE_SEP +
            "<xsl:template match='xmla:return'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'root' and apply --> " + LINE_SEP +
            "<xsl:template match='ROW:root'> " + LINE_SEP +
                "<xsl:apply-templates/> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- consume 'row' --> " + LINE_SEP +
            "<xsl:template match='ROW:row'> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
            "<!-- copy everything else --> " + LINE_SEP +
            "<xsl:template match='*|@*'> " + LINE_SEP +
                "<xsl:copy> " + LINE_SEP +
                    "<xsl:apply-templates select='@*|node()'/> " + LINE_SEP +
                "</xsl:copy> " + LINE_SEP +
            "</xsl:template> " + LINE_SEP +
        "</xsl:stylesheet>";

    /** 
     * Error handler plus helper methods.  
     */
    public static class SAXErrorHandler implements ErrorHandler {
        public static final String WARNING_STRING        = "WARNING";
        public static final String ERROR_STRING          = "ERROR";
        public static final String FATAL_ERROR_STRING    = "FATAL";
        
        public void printErrorInfos(PrintStream out) {
            if (errors != null) {
                Iterator it = errors.iterator();

                while (it.hasNext()) {
                    out.println(formatErrorInfo((ErrorInfo) it.next()));
                }
            }
        }

        public static String formatErrorInfos(SAXErrorHandler saxEH) {
            if (! saxEH.hasErrors()) {
                return "";
            }
            StringBuffer buf = new StringBuffer(512);
            List errors = saxEH.getErrors();
            Iterator it = errors.iterator();
            while (it.hasNext()) {
                ErrorInfo error = (ErrorInfo) it.next();
                buf.append(formatErrorInfo(error));
                buf.append(LINE_SEP);
            }
            return buf.toString();
        }
        public static String formatErrorInfo(ErrorInfo ei) {
            StringBuffer buf = new StringBuffer(128);
            buf.append("[");
            switch (ei.severity) {
            case DOMError.SEVERITY_WARNING :
                buf.append(WARNING_STRING);
                break;
            case DOMError.SEVERITY_ERROR :
                buf.append(ERROR_STRING);
                break;
            case DOMError.SEVERITY_FATAL_ERROR :
                buf.append(FATAL_ERROR_STRING);
                break;
            }
            buf.append(']');
            String systemId = ei.exception.getSystemId();
            if (systemId != null) {
                int index = systemId.lastIndexOf('/');
                if (index != -1) {
                    systemId = systemId.substring(index + 1);
                }
                buf.append(systemId);
            }
            buf.append(':');
            buf.append(ei.exception.getLineNumber());
            buf.append(':');
            buf.append(ei.exception.getColumnNumber());
            buf.append(": ");
            buf.append(ei.exception.getMessage());
            return buf.toString();
        }
        public static class ErrorInfo {
            public SAXParseException exception;
            public short severity;
            ErrorInfo(short severity, SAXParseException exception) {
                this.severity = severity;
                this.exception = exception;
            }
        }
        private List errors;
        public SAXErrorHandler() {
        }
        public List getErrors() {
            return this.errors;
        }
        public boolean hasErrors() {
            return (this.errors != null);
        }
        public void warning(SAXParseException exception) throws SAXException {
            addError(new ErrorInfo(DOMError.SEVERITY_WARNING, exception));
        }
        public void error(SAXParseException exception) throws SAXException {
            addError(new ErrorInfo(DOMError.SEVERITY_ERROR, exception));
        }       
        public void fatalError(SAXParseException exception)
                                                        throws SAXException {
            addError(new ErrorInfo(DOMError.SEVERITY_FATAL_ERROR, exception));
        }   
        protected void addError(ErrorInfo ei) {
            if (this.errors == null) {
                this.errors = new ArrayList();
            }
            this.errors.add(ei);
        }
            
        public String getFirstError() {
            return (hasErrors())
                ? formatErrorInfo((ErrorInfo) errors.get(0))
                : "";
        }   
    }

    public static Document newDocument(Node firstElement, boolean deepcopy) {
        Document newDoc = new DocumentImpl();
        newDoc.appendChild(newDoc.importNode(firstElement, deepcopy));
        return newDoc;
    }


    //////////////////////////////////////////////////////////////////////////
    // parse
    //////////////////////////////////////////////////////////////////////////

    /** 
     * Get your non-cached DOM parser which can be configured to do schema
     * based validation of the instance Document.
     * 
     * @param schemaLocationPropertyValue 
     * @param entityResolver 
     * @param validate 
     * @return 
     * @throws SAXNotRecognizedException 
     * @throws SAXNotSupportedException 
     */
    public static DOMParser getParser(
            String schemaLocationPropertyValue,
            EntityResolver entityResolver,
            boolean validate)
            throws SAXNotRecognizedException, SAXNotSupportedException {

        boolean doingValidation =
            (validate || (schemaLocationPropertyValue != null));

        DOMParser parser = new DOMParser();

        parser.setEntityResolver(entityResolver);
        parser.setErrorHandler(new SAXErrorHandler());
        parser.setFeature(DEFER_NODE_EXPANSION, false);
        parser.setFeature(NAMESPACES_FEATURE_ID, true);
        parser.setFeature(SCHEMA_VALIDATION_FEATURE_ID, doingValidation);
        parser.setFeature(VALIDATION_FEATURE_ID, doingValidation);

        if (schemaLocationPropertyValue != null) {
            parser.setProperty(SCHEMA_LOCATION, 
                schemaLocationPropertyValue.replace('\\', '/'));
        }

        return parser;
    }

    /** 
     * See if the DOMParser after parsing a Document has any errors and,
     * if so, throw a RuntimeException exception containing the errors.
     * 
     * @param parser 
     */
    private static void checkForParseError(final DOMParser parser, Throwable t) {
        final ErrorHandler errorHandler = parser.getErrorHandler();

        if (errorHandler instanceof SAXErrorHandler) {
            final SAXErrorHandler saxEH = (SAXErrorHandler) errorHandler;
            final List errors = saxEH.getErrors();

            if (errors != null && errors.size() > 0) {
                String errorStr = SAXErrorHandler.formatErrorInfos(saxEH);
                throw new RuntimeException(errorStr, t);
            }
        } else {
            System.out.println("errorHandler=" +errorHandler);
        }
    }
    private static void checkForParseError(final DOMParser parser) {
        checkForParseError(parser, null);
    }


    /** 
     * Parse a String into a Document (no validation).
     * 
     * @param s 
     * @return 
     */
    public static Document parseString(String s) 
            throws SAXException, IOException {
        // Hack to workaround bug #622 until 1.4.2_08
        final int length = s.length();
        
        if (length > 16777216 && length % 4 == 1) {
            final StringBuffer buf = new StringBuffer(length + 1);

            buf.append(s);
            buf.append('\n');
            s = buf.toString();
        }   
            
        return XmlUtil.parse(s.getBytes());
    }   

    /** 
     * Parse a byte array into a Document (no validation). 
     * 
     * @param bytes 
     * @return 
     */
    public static Document parse(byte[] bytes) 
            throws SAXException, IOException {
        return XmlUtil.parse(new ByteArrayInputStream(bytes));
    }

    public static Document parse(File file) 
            throws SAXException, IOException {

        return parse(new FileInputStream(file));
    }

    /** 
     * Parse a stream into a Document (no validation). 
     * 
     * @param in 
     * @return 
     * @throws SAXException 
     * @throws IOException 
     */
    public static Document parse(InputStream in) 
            throws SAXException, IOException {

        InputSource source = new InputSource(in);

        DOMParser parser = XmlUtil.getParser(null, null, false);
        try {
            parser.parse(source);
            checkForParseError(parser);
        } catch (SAXParseException ex) {
            checkForParseError(parser, ex);
        }

        Document document = parser.getDocument();
        return document;
    }


    //////////////////////////////////////////////////////////////////////////
    // xpath
    //////////////////////////////////////////////////////////////////////////
    /** 
     * Create a context document for use in performing XPath operations. 
     * An array of prefix/namespace-urls are provided as input. These
     * namespace-urls should be all of those that will appear in the
     * document against which an xpath is to be applied.
     * Importantly, it is, in fact, each element of the Document that
     * has a default namespace these namespaces MUST have 
     * prefix/namespace-urls pairs in the context document and the prefix
     * provided MUST also be used in the xpath. 
     * Elements with explicit namespaces don't have to have pairs in the
     * context Document as long as the xpath uses the same prefixes
     * that appear in the target Document.
     * 
     * @param nsArray 
     * @return 
     * @throws SAXException 
     * @throws IOException 
     */
    public static Document createContextDocument(String[][] nsArray)
            throws SAXException, IOException {
        StringBuffer buf = new StringBuffer(256);
        buf.append("<?xml version='1.0' encoding='utf-8'?>");
        buf.append("<DOES_NOT_MATTER");
        for (int i = 0; i < nsArray.length; i++) {
            String prefix = nsArray[i][0];
            String nsURI = nsArray[i][1];

            buf.append(" xmlns:");
            buf.append(prefix);
            buf.append("=\"");
            buf.append(nsURI);
            buf.append("\"");
        }
        buf.append(" />");

        String docStr = buf.toString();
        return parseString(docStr);
    }
    public static String makeSoapPath() {
        return XmlUtil.makeSoapPath(SOAP_PREFIX);
    }
    // '/soapX:Envelope/soapX:Body/*'
    public static String makeSoapPath(String prefix) {
        StringBuffer buf = new StringBuffer(20);
        buf.append('/');
        if (prefix != null) {
            buf.append(prefix);
            buf.append(':');
        }
        buf.append("Envelope");
        buf.append('/');
        if (prefix != null) {
            buf.append(prefix);
            buf.append(':');
        }
        buf.append("Body");
        buf.append('/');
        buf.append('*');

        return buf.toString();
    }
    public static String makeRootPathInSoapBody() {
        return makeRootPathInSoapBody(XSD_PREFIX);
    }
    // '/xmla:DiscoverResponse/xmla:return/ROW/root/*'
    public static String makeRootPathInSoapBody(String prefix) {
        StringBuffer buf = new StringBuffer(20);
        buf.append("/xmla:DiscoverResponse");
        buf.append("/xmla:return");
        buf.append("/ROW:root");
        buf.append('/');
        buf.append('*');
/*
        if (prefix != null) {
            buf.append(prefix);
            buf.append(':');
        }
        buf.append("schema");
*/

        return buf.toString();
    }

    public static String selectAsString(Node node, String xpath)
                                            throws XPathException {
        return XmlUtil.selectAsString(node, xpath, node);
    }   
    public static String selectAsString(Node node, String xpath,
                        Node namespaceNode) throws XPathException {

        XPathResult xpathResult = XmlUtil.select(node, xpath, namespaceNode);
        return XmlUtil.convertToString(xpathResult, false);
    }

    public static Node[] selectAsNodes(Node node, String xpath)
                                            throws XPathException {
        return XmlUtil.selectAsNodes(node, xpath, node);
    }   
    public static Node[] selectAsNodes(Node node, String xpath,
                        Node namespaceNode) throws XPathException {

        XPathResult xpathResult = XmlUtil.select(node, xpath, namespaceNode);
        return XmlUtil.convertToNodes(xpathResult);
    }

    public static XPathResult select(Node contextNode, String xpath,
                        Node namespaceNode) throws XPathException {
        XPathEvaluator evaluator = new XPathEvaluatorImpl();
        XPathNSResolver resolver = evaluator.createNSResolver(namespaceNode);

        return (XPathResult) evaluator.evaluate(xpath, contextNode, resolver,
                XPathResult.ANY_TYPE, null);
    }

    /** 
     * Convert an XPathResult object to String. 
     * 
     * @param xpathResult 
     * @param prettyPrint 
     * @return 
     */
    public static String convertToString(XPathResult xpathResult,
                                         boolean prettyPrint) {
        switch (xpathResult.getResultType()) {
        case XPathResult.NUMBER_TYPE :
            double d = xpathResult.getNumberValue();
            return Double.toString(d);
            
        case XPathResult.STRING_TYPE :
            String s = xpathResult.getStringValue();
            return s;
            
        case XPathResult.BOOLEAN_TYPE :
            boolean b = xpathResult.getBooleanValue();
            return String.valueOf(b);
            
        case XPathResult.FIRST_ORDERED_NODE_TYPE :
        case XPathResult.ANY_UNORDERED_NODE_TYPE : {
            Node node = xpathResult.getSingleNodeValue();
            return XmlUtil.toString(node, prettyPrint);
        }   
        case XPathResult.ORDERED_NODE_ITERATOR_TYPE :
        case XPathResult.UNORDERED_NODE_ITERATOR_TYPE : {
            StringBuffer buf = new StringBuffer(512); 
            Node node = xpathResult.iterateNext();
            while (node != null) {
                buf.append(XmlUtil.toString(node, prettyPrint));
                node = xpathResult.iterateNext();
            }
            return buf.toString();
        }
        case XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE :
        case XPathResult.ORDERED_NODE_SNAPSHOT_TYPE : {
            StringBuffer buf = new StringBuffer(512);
            int len = xpathResult.getSnapshotLength();
            for (int i = 0; i < len; i++) {
                Node node = xpathResult.snapshotItem(i);
                buf.append(XmlUtil.toString(node, prettyPrint));
            }
            return buf.toString();
        }
        default:
            String msg = "Unknown xpathResult.type = "
                        + xpathResult.getResultType();
            throw new XPathException(XPathException.TYPE_ERR ,msg);
        }
    }

    private static final Node[] NULL_NODE_ARRAY = new Node[0];

    /** 
     * Convert an XPathResult to an array of Nodes. 
     * 
     * @param xpathResult 
     * @return 
     */
    public static Node[] convertToNodes(XPathResult xpathResult) {
        switch (xpathResult.getResultType()) {
        case XPathResult.NUMBER_TYPE :
            return NULL_NODE_ARRAY;
            
        case XPathResult.STRING_TYPE :
            return NULL_NODE_ARRAY;
            
        case XPathResult.BOOLEAN_TYPE :
            return NULL_NODE_ARRAY;
            
        case XPathResult.FIRST_ORDERED_NODE_TYPE :
        case XPathResult.ANY_UNORDERED_NODE_TYPE : {
            Node node = xpathResult.getSingleNodeValue();
            return new Node[] { node };
        }   
        case XPathResult.ORDERED_NODE_ITERATOR_TYPE :
        case XPathResult.UNORDERED_NODE_ITERATOR_TYPE : {
            List list = new ArrayList();
            Node node = xpathResult.iterateNext();
            while (node != null) {
                list.add(node);
                node = xpathResult.iterateNext();
            }
            return (Node[]) list.toArray(NULL_NODE_ARRAY);
        }
        case XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE :
        case XPathResult.ORDERED_NODE_SNAPSHOT_TYPE : {
            int len = xpathResult.getSnapshotLength();
            Node[] nodes = new Node[len];
            for (int i = 0; i < len; i++) {
                Node node = xpathResult.snapshotItem(i);
                nodes[i] = node;
            }
            return nodes;
        }
        default:
            String msg = "Unknown xpathResult.type = "
                        + xpathResult.getResultType();
            throw new XPathException(XPathException.TYPE_ERR ,msg);
        }
    }

    /** 
     * Convert a Node to a String.
     * 
     * @param node 
     * @param prettyPrint add CRs
     * @return 
     */
    public static String toString(Node node, boolean prettyPrint) {
        if (node == null) {
            return null;
        }
        String str = null;
        try {
            Document doc = node.getOwnerDocument();
            OutputFormat format  = null;
            if (doc != null) {
                format  = new OutputFormat(doc, null, true);
            } else {
                format  = new OutputFormat("xml", null, true);
            }
            if (prettyPrint) {
                format.setLineSeparator(LINE_SEP);
            } else {
                format.setLineSeparator("");
            }
            StringWriter writer = new StringWriter(1000);
            XMLSerializer serial = new XMLSerializer(writer, format);
            serial.asDOMSerializer();
            if (node instanceof Document) {
                serial.serialize((Document) node);
            } else if (node instanceof Element) {
                format.setOmitXMLDeclaration(true);
                serial.serialize((Element) node);
            } else if (node instanceof DocumentFragment) {
                format.setOmitXMLDeclaration(true);
                serial.serialize((DocumentFragment) node);
            } else if (node instanceof Text) {
                Text text = (Text) node;
                return text.getData();
            } else if (node instanceof Attr) {
                Attr attr = (Attr) node;
                String name = attr.getName();
                String value = attr.getValue();
                writer.write(name);
                writer.write("=\"");
                writer.write(value);
                writer.write("\"");
                if (prettyPrint) {
                    writer.write(LINE_SEP);
                }
            } else {
                writer.write("node class = " +node.getClass().getName());
                if (prettyPrint) {
                    writer.write(LINE_SEP);
                } else {
                    writer.write(' ');
                }
                writer.write("XmlUtil.toString: fix me: ");
                writer.write(node.toString());
                if (prettyPrint) {
                    writer.write(LINE_SEP);
                }
            }
            str = writer.toString();
        } catch (Exception ex) {
            // ignore
        }
        return str;
    }

    //////////////////////////////////////////////////////////////////////////
    // validate
    //////////////////////////////////////////////////////////////////////////
    
    /** 
     * This can be extened to have a map from publicId/systemId  
     * to InputSource.
     */
    public static class Resolver implements EntityResolver {
        private InputSource source;

        protected Resolver() {
            this((InputSource) null);
        }
        public Resolver(Document doc) {
            this(XmlUtil.toString(doc, false));
        }
        public Resolver(String str) {
            this(new InputSource(new StringReader(str)));
        }
        public Resolver(InputSource source) {
            this.source = source;
        }
        public InputSource resolveEntity(String publicId,
                                         String systemId)
                throws SAXException, IOException {
/*
System.err.println("XmlUtil.Resolver.resolveEntity:");
System.err.println("    publicId="+publicId);
System.err.println("    systemId="+systemId);
*/
            return source;
        }
    }

    /** 
     * Get the Xerces version being used. 
     * 
     * @return 
     */
    public static String getXercesVersion() {
        try {
            return org.apache.xerces.impl.Version.getVersion();
        } catch (java.lang.NoClassDefFoundError ex) {
            return "Xerces-J 2.2.0";
        }
    }
    
    /** 
     * Get the number part of the Xerces Version string. 
     * 
     * @return 
     */
    public static String getXercesVersionNumberString() {
        String version = getXercesVersion();
        int index = version.indexOf(' ');
        return (index == -1)
            ? "0.0.0" : version.substring(index+1);
    }

    protected static int[] versionNumbers = null;

    /** 
     * Get the Xerces numbers as a three part array of ints where 
     * the first element is the major release number, the second is the
     * minor release number, and the third is the patch number.
     * 
     * @return 
     */
    public static int[] getXercesVersionNumbers() {
        if (versionNumbers == null) {
            int[] verNums = new int[3];
            String verNumStr = getXercesVersionNumberString();
            int index = verNumStr.indexOf('.');
            verNums[0] = new Integer(verNumStr.substring(0, index)).intValue();

            verNumStr = verNumStr.substring(index+1);
            index = verNumStr.indexOf('.');
            verNums[1] = new Integer(verNumStr.substring(0, index)).intValue();

            verNumStr = verNumStr.substring(index+1);
            verNums[2] = new Integer(verNumStr).intValue();

            versionNumbers = verNums;
        }

        return versionNumbers;
        
    }
    
    /** 
     * I could not get Xerces 2.2 to validate. So, I hard coded allowing
     * Xerces 2.6 and above to validate and by setting the following
     * System property one can test validating with earlier versions
     * of Xerces.
     */
    private static final String ALWAYS_ATTEMPT_VALIDATION =
                "mondrian.xml.always.attempt.validation";
    private static final boolean alwaysAttemptValidation;
    static {
        alwaysAttemptValidation = 
            Boolean.getBoolean(ALWAYS_ATTEMPT_VALIDATION);
    }
    /** 
     * I could not get validation to work with Xerces 2.2 so I put in
     * this check. If you want to test on an earlier version of Xerces
     * simply define the above property: 
     *   "mondrian.xml.always.attempt.validation",
     * to true.
     * 
     * @return 
     */
    public static boolean supportsValidation() {
        if (alwaysAttemptValidation) {
            return true;
        } else {
            int[] verNums = getXercesVersionNumbers();
            return ((verNums[0] >= 3) || 
                ((verNums[0] == 2) && (verNums[1] >= 6)));
        }
    }
    public static void validate(Document doc, 
                    String schemaLocationPropertyValue,
                    EntityResolver resolver)
            throws IOException ,
                    SAXException,
                    SAXNotRecognizedException {

        OutputFormat format  = new OutputFormat(doc, null, true);
        StringWriter writer = new StringWriter(1000);
        XMLSerializer serial = new XMLSerializer(writer, format);
        serial.asDOMSerializer();
        serial.serialize(doc);
        String docString = writer.toString();

        validate(docString, schemaLocationPropertyValue, resolver);
    }

/*
    public static void validate(String docStr,
                    String schemaLocationPropertyValue,
                    EntityResolver resolver)
            throws IOException ,
                    SAXException,
                    SAXNotRecognizedException {

        if (resolver == null) {
            resolver = new Resolver();
        }
        DOMParser parser =
            getParser(schemaLocationPropertyValue, resolver, true);
                    
        parser.parse(new InputSource(new StringReader(docStr)));
        checkForParseError(parser);

    }
*/

    public static void validate(String docStr,
                    String schemaLocationPropertyValue,
                    EntityResolver resolver)
            throws IOException ,
                    SAXException,
                    SAXNotRecognizedException {
        if (resolver == null) {
            resolver = new Resolver();
        }
        DOMParser parser =
            getParser(schemaLocationPropertyValue, resolver, true);

        try {
            parser.parse(new InputSource(new StringReader(docStr)));
            checkForParseError(parser);
        } catch (SAXParseException ex) {
            checkForParseError(parser, ex);
        }

    }

/*
    public static void validate(Document inDoc, Document xsdDoc)
            throws IOException ,
                    SAXException,
                    SAXNotRecognizedException {
        String xmlns = XmlUtil.getNamespaceAttributeValue(inDoc);
System.out.println("XmlUtil.validate: xmlns=" +xmlns);

        OutputFormat format  = new OutputFormat(inDoc, null, true);
        StringWriter writer = new StringWriter(1000);
        XMLSerializer serial = new XMLSerializer(writer, format);
        serial.asDOMSerializer();
        serial.serialize(inDoc);
        String docString = writer.toString();
System.out.println("XmlUtil.validate: docString=" +docString);

        String schemaStr = XmlUtil.toString(xsdDoc, false);
System.out.println("XmlUtil.validate: schemaStr=" +schemaStr);
        Resolver resolver = 
            new Resolver(new InputSource(new StringReader(schemaStr)));

        String schemaLocationPropertyValue = xmlns + " bar";
System.out.println("XmlUtil.validate: schemaLocationPropertyValue=" +schemaLocationPropertyValue);

        schemaLocationPropertyValue = null;
        DOMParser parser =
            getParser(schemaLocationPropertyValue, resolver, true);
        parser.parse(new InputSource(new StringReader(docString)));
        checkForParseError(parser);
    }
*/

    /** 
     * This is used to get a Document's namespace attribute value. 
     * 
     * @param doc 
     * @return 
     */
    public static String getNamespaceAttributeValue(Document doc) {
        Element el = doc.getDocumentElement(); 
        String prefix = el.getPrefix();
        Attr attr = (prefix == null)
                    ? el.getAttributeNode(XMLNS)
                    : el.getAttributeNode(XMLNS + ':' + prefix);
        return (attr == null) ? null : attr.getValue();
    }   

    //////////////////////////////////////////////////////////////////////////
    // transform
    //////////////////////////////////////////////////////////////////////////

    private static TransformerFactory tfactory = null;
    public static TransformerFactory getTransformerFactory() 
            throws TransformerFactoryConfigurationError {
        if (tfactory == null) {
            tfactory = TransformerFactory.newInstance();
        }
        return tfactory;
    }
    
    /** 
     * Transform a Document and return the transformed Node. 
     * 
     * @param inDoc 
     * @param xslFileName 
     * @return 
     * @throws ParserConfigurationException 
     * @throws SAXException 
     * @throws IOException 
     * @throws TransformerConfigurationException 
     * @throws TransformerException 
     */
    public static Node transform(Document inDoc, 
        String xslFileName,
        String[][] namevalueParameters) 
            throws ParserConfigurationException, 
                SAXException, 
                IOException,
                TransformerConfigurationException,
                TransformerException {

        DocumentBuilderFactory dfactory = DocumentBuilderFactory.newInstance();
        dfactory.setNamespaceAware(true);

        DocumentBuilder docBuilder = dfactory.newDocumentBuilder();
        Node xslDOM = docBuilder.parse(new InputSource(xslFileName));

        TransformerFactory tfactory = getTransformerFactory();
        Templates stylesheet = tfactory.newTemplates(
                            new DOMSource(xslDOM, xslFileName));
        Transformer transformer = stylesheet.newTransformer();                                                                   
        if (namevalueParameters != null) {
            for (int i = 0; i < namevalueParameters.length; i++) {
                String name = namevalueParameters[i][0];
                String value = namevalueParameters[i][1];

                transformer.setParameter(name, value);
            }
        }
        DOMResult domResult = new DOMResult();
        transformer.transform(new DOMSource(inDoc, null), domResult);

        return domResult.getNode();
    }
    public static Node transform(Document inDoc, 
        String xslFileName) 
            throws ParserConfigurationException, 
                SAXException, 
                IOException,
                TransformerConfigurationException,
                TransformerException {
        return transform(inDoc, xslFileName, null);
    }

    /** 
     * Transform a Document and return the transformed Node. 
     * 
     * @param inDoc 
     * @param xslReader 
     * @param namevalueParameters 
     * @return 
     * @throws ParserConfigurationException 
     * @throws SAXException 
     * @throws IOException 
     * @throws TransformerConfigurationException 
     * @throws TransformerException 
     */
    public static Node transform(Document inDoc, 
        Reader xslReader,
        String[][] namevalueParameters) 
            throws ParserConfigurationException, 
                SAXException, 
                IOException,
                TransformerConfigurationException,
                TransformerException {

        DocumentBuilderFactory dfactory = DocumentBuilderFactory.newInstance();
        dfactory.setNamespaceAware(true);

        DocumentBuilder docBuilder = dfactory.newDocumentBuilder();
        Node xslDOM = docBuilder.parse(new InputSource(xslReader));

        TransformerFactory tfactory = getTransformerFactory();
        Templates stylesheet = tfactory.newTemplates(new DOMSource(xslDOM));
        Transformer transformer = stylesheet.newTransformer();                                                                   
        if (namevalueParameters != null) {
            for (int i = 0; i < namevalueParameters.length; i++) {
                String name = namevalueParameters[i][0];
                String value = namevalueParameters[i][1];

                transformer.setParameter(name, value);
            }
        }

        DOMResult domResult = new DOMResult();
        transformer.transform(new DOMSource(inDoc, null), domResult);

        return domResult.getNode();
    }
    public static Node transform(Document inDoc, 
        Reader xslReader) 
            throws ParserConfigurationException, 
                SAXException, 
                IOException,
                TransformerConfigurationException,
                TransformerException {
        return transform(inDoc, xslReader, null);                
    }



/*
    private static TransformerFactory tFactory;
    static {
        // cache the transformer factory.
                        
        tFactory = TransformerFactory.newInstance();
//tFactory.setURIResolver(StandardResolver.INSTANCE);
        if(! tFactory.getFeature(DOMSource.FEATURE)) {
            System.err.println(
                    "XMLUtil: DOM source node processing not supported");
            tFactory = null;
        }
        if(! tFactory.getFeature(DOMResult.FEATURE)) {
            System.err.println(
                "XMLUtil: DOM result node processing not supported");
            tFactory = null;
        }               
    }   

    public static Node transform(
                    Document inDoc, String inDocSystemID,
                    Document xslDoc, String xslDocSystemID,
                    boolean trace, URIResolver resolver)
            throws TransformerConfigurationException,
                   TransformerException,
                   TooManyListenersException {
        DOMSource xslDomSource = new DOMSource(xslDoc, xslDocSystemID);
        DOMSource xmlDomSource = new DOMSource(inDoc, inDocSystemID);
        DOMResult domResult = new DOMResult();

        transform(xslDomSource, resolver, xmlDomSource, domResult, trace);
        
        return domResult.getNode();
    }
    public static void transform(Source xslSource, URIResolver resolver,
             Source xmlSource, Result result, boolean trace)
             throws TransformerConfigurationException, TransformerException,
                        TooManyListenersException {

        TransformerFactory factory = null;
        if (resolver != null) {
            factory = TransformerFactory.newInstance();
            factory.setURIResolver(resolver);
        } else {
            factory = tFactory;
        }

        Transformer transformer = factory.newTransformer(xslSource);

        if (trace) {
            addTracer(transformer);
        }
        transformer.transform(xmlSource, result);
    }

    public static void addTracer(Transformer transformer)
            throws TooManyListenersException {
        addTracer(transformer, new PrintWriter(System.out, true));
    }   

    public static void addTracer(Transformer transformer, PrintWriter pw)
                        throws TooManyListenersException {
        if (transformer instanceof TransformerImpl) {
            TransformerImpl transformerImpl = (TransformerImpl) transformer;
            
            PrintTraceListener ptl = new PrintTraceListener(pw);
            ptl.m_traceElements = true;
            ptl.m_traceGeneration = true;
            ptl.m_traceSelection = true;
            ptl.m_traceTemplates = true;
            
            TraceManager trMgr = transformerImpl.getTraceManager();
            trMgr.addTraceListener(ptl);
        }
    }

*/


    private XmlUtil() {}
}