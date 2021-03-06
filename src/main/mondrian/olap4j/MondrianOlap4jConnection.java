/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2007-2010 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap4j;

import mondrian.mdx.*;
import mondrian.olap.*;
import mondrian.rolap.*;

import org.olap4j.Axis;
import org.olap4j.Cell;
import org.olap4j.*;
import org.olap4j.impl.Olap4jUtil;
import org.olap4j.impl.UnmodifiableArrayList;
import org.olap4j.mdx.*;
import org.olap4j.mdx.parser.*;
import org.olap4j.mdx.parser.impl.DefaultMdxParserImpl;
import org.olap4j.metadata.*;
import org.olap4j.metadata.Schema;
import org.olap4j.type.*;
import org.olap4j.type.DimensionType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;

/**
 * Implementation of {@link org.olap4j.OlapConnection}
 * for the Mondrian OLAP engine.
 *
 * <p>This class has sub-classes which implement JDBC 3.0 and JDBC 4.0 APIs;
 * it is instantiated using {@link Factory#newConnection}.</p>
 *
 * @author jhyde
 * @version $Id$
 * @since May 23, 2007
 */
abstract class MondrianOlap4jConnection implements OlapConnection {
    /**
     * Handler for errors.
     */
    final Helper helper = new Helper();

    /**
     * Underlying mondrian connection. Set on creation, cleared on close.
     */
    mondrian.olap.Connection connection;

    /**
     * Current schema.
     */
    MondrianOlap4jSchema olap4jSchema;

    /**
     * Map from mondrian schema objects to olap4j schemas.
     */
    final Map<mondrian.olap.Schema, MondrianOlap4jSchema> schemaMap =
        new HashMap<mondrian.olap.Schema, MondrianOlap4jSchema>();

    private final MondrianOlap4jDatabaseMetaData olap4jDatabaseMetaData;

    /**
     * The name of the sole catalog.
     */
    static final String LOCALDB_CATALOG_NAME = "LOCALDB";
    private static final String CONNECT_STRING_PREFIX = "jdbc:mondrian:";

    final Factory factory;
    final MondrianOlap4jDriver driver;
    private Locale locale;
    private String roleName;
    private boolean autoCommit;
    private boolean readOnly;

    /**
     * Creates an Olap4j connection to Mondrian.
     *
     * <p>This method is intentionally package-protected. The public API
     * uses the traditional JDBC {@link java.sql.DriverManager}.
     * See {@link mondrian.olap4j.MondrianOlap4jDriver} for more details.
     *
     * @pre acceptsURL(url)
     *
     * @param factory Factory
     * @param driver Driver
     * @param url Connect-string URL
     * @param info Additional properties
     * @throws SQLException if there is an error
     */
    MondrianOlap4jConnection(
        Factory factory,
        MondrianOlap4jDriver driver,
        String url,
        Properties info)
        throws SQLException
    {
        this.factory = factory;
        this.driver = driver;
        if (!acceptsURL(url)) {
            // This is not a URL we can handle.
            // DriverManager should not have invoked us.
            throw new AssertionError(
                "does not start with '" + CONNECT_STRING_PREFIX + "'");
        }
        String x = url.substring(CONNECT_STRING_PREFIX.length());
        Util.PropertyList list = Util.parseConnectString(x);
        for (Map.Entry<String, String> entry : toMap(info).entrySet()) {
            list.put(entry.getKey(), entry.getValue());
        }
        this.connection =
            mondrian.olap.DriverManager.getConnection(list, null);
        this.olap4jDatabaseMetaData =
            factory.newDatabaseMetaData(this);
        this.olap4jSchema = toOlap4j(connection.getSchema());
    }

    static boolean acceptsURL(String url) {
        return url.startsWith(CONNECT_STRING_PREFIX);
    }

    public OlapStatement createStatement() {
        return new MondrianOlap4jStatement(this);
    }

    public ScenarioImpl createScenario() {
        return ((RolapConnection) connection).createScenario();
    }

    public void setScenario(Scenario scenario) {
        ((RolapConnection) connection).setScenario(scenario);
    }

    public Scenario getScenario() {
        return ((RolapConnection) connection).getScenario();
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public String nativeSQL(String sql) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.autoCommit = autoCommit;
    }

    public boolean getAutoCommit() throws SQLException {
        return autoCommit;
    }

    public void commit() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void rollback() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void close() throws SQLException {
        if (connection != null) {
            mondrian.olap.Connection c = connection;
            connection = null;
            c.close();
        }
    }

    public boolean isClosed() throws SQLException {
        return connection == null;
    }

    public OlapDatabaseMetaData getMetaData() {
        return olap4jDatabaseMetaData;
    }

    public NamedList<Catalog> getCatalogs() {
        return olap4jDatabaseMetaData.getCatalogObjects();
    }

    public void setReadOnly(boolean readOnly) throws SQLException {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() throws SQLException {
        return readOnly;
    }

    public void setCatalog(String catalog) throws SQLException {
        if (catalog != null
            && !catalog.equals(LOCALDB_CATALOG_NAME))
        {
            throw new UnsupportedOperationException();
        }
    }

    public String getCatalog() throws SQLException {
        return LOCALDB_CATALOG_NAME;
    }

    public void setTransactionIsolation(int level) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getTransactionIsolation() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public SQLWarning getWarnings() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void clearWarnings() throws SQLException {
    }

    public Statement createStatement(
        int resultSetType, int resultSetConcurrency) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql,
        int resultSetType,
        int resultSetConcurrency) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public CallableStatement prepareCall(
        String sql,
        int resultSetType,
        int resultSetConcurrency) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setHoldability(int holdability) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getHoldability() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Savepoint setSavepoint() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Savepoint setSavepoint(String name) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void rollback(Savepoint savepoint) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Statement createStatement(
        int resultSetType,
        int resultSetConcurrency,
        int resultSetHoldability) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql,
        int resultSetType,
        int resultSetConcurrency,
        int resultSetHoldability) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public CallableStatement prepareCall(
        String sql,
        int resultSetType,
        int resultSetConcurrency,
        int resultSetHoldability) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql, int autoGeneratedKeys) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql, int columnIndexes[]) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    public PreparedStatement prepareStatement(
        String sql, String columnNames[]) throws SQLException
    {
        throw new UnsupportedOperationException();
    }

    // implement Wrapper

    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        } else if (iface.isInstance(connection)) {
            return iface.cast(connection);
        }
        throw helper.createException("does not implement '" + iface + "'");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this)
            || iface.isInstance(connection);
    }

    // implement OlapConnection

    public PreparedOlapStatement prepareOlapStatement(
        String mdx)
        throws OlapException
    {
        return factory.newPreparedStatement(mdx, this);
    }

    public MdxParserFactory getParserFactory() {
        return new MdxParserFactory() {
            public MdxParser createMdxParser(OlapConnection connection) {
                return new DefaultMdxParserImpl();
            }

            public MdxValidator createMdxValidator(OlapConnection connection) {
                return new MondrianOlap4jMdxValidator(connection);
            }
        };
    }

    public Schema getSchema() throws OlapException {
        return olap4jSchema;
    }

    MondrianOlap4jCube toOlap4j(mondrian.olap.Cube cube) {
        MondrianOlap4jSchema schema = toOlap4j(cube.getSchema());
        return new MondrianOlap4jCube(cube, schema);
    }

    MondrianOlap4jDimension toOlap4j(mondrian.olap.Dimension dimension) {
        return new MondrianOlap4jDimension(
            toOlap4j(dimension.getSchema()),
            dimension);
    }

    synchronized MondrianOlap4jSchema toOlap4j(mondrian.olap.Schema schema) {
        MondrianOlap4jSchema olap4jSchema = schemaMap.get(schema);
        if (olap4jSchema == null) {
            final MondrianOlap4jCatalog olap4jCatalog =
                (MondrianOlap4jCatalog) getCatalogs().get(LOCALDB_CATALOG_NAME);
            olap4jSchema =
                new MondrianOlap4jSchema(
                    olap4jCatalog,
                    schema.getSchemaReader(),
                    schema);
            schemaMap.put(schema, olap4jSchema);
        }
        return olap4jSchema;
    }

    Type toOlap4j(mondrian.olap.type.Type type) {
        if (type instanceof mondrian.olap.type.BooleanType) {
            return new BooleanType();
        } else if (type instanceof mondrian.olap.type.CubeType) {
            final mondrian.olap.Cube mondrianCube =
                ((mondrian.olap.type.CubeType) type).getCube();
            return new CubeType(toOlap4j(mondrianCube));
        } else if (type instanceof mondrian.olap.type.DecimalType) {
            mondrian.olap.type.DecimalType decimalType =
                (mondrian.olap.type.DecimalType) type;
            return new DecimalType(
                decimalType.getPrecision(),
                decimalType.getScale());
        } else if (type instanceof mondrian.olap.type.DimensionType) {
            mondrian.olap.type.DimensionType dimensionType =
                (mondrian.olap.type.DimensionType) type;
            return new DimensionType(
                toOlap4j(dimensionType.getDimension()));
        } else if (type instanceof mondrian.olap.type.HierarchyType) {
            return new BooleanType();
        } else if (type instanceof mondrian.olap.type.LevelType) {
            return new BooleanType();
        } else if (type instanceof mondrian.olap.type.MemberType) {
            final mondrian.olap.type.MemberType memberType =
                (mondrian.olap.type.MemberType) type;
            return new MemberType(
                toOlap4j(memberType.getDimension()),
                toOlap4j(memberType.getHierarchy()),
                toOlap4j(memberType.getLevel()),
                toOlap4j(memberType.getMember()));
        } else if (type instanceof mondrian.olap.type.NullType) {
            return new NullType();
        } else if (type instanceof mondrian.olap.type.NumericType) {
            return new NumericType();
        } else if (type instanceof mondrian.olap.type.SetType) {
            final mondrian.olap.type.SetType setType =
                (mondrian.olap.type.SetType) type;
            return new SetType(toOlap4j(setType.getElementType()));
        } else if (type instanceof mondrian.olap.type.StringType) {
            return new StringType();
        } else if (type instanceof mondrian.olap.type.TupleType) {
            mondrian.olap.type.TupleType tupleType =
                (mondrian.olap.type.TupleType) type;
            final Type[] types = toOlap4j(tupleType.elementTypes);
            return new TupleType(types);
        } else if (type instanceof mondrian.olap.type.SymbolType) {
            return new SymbolType();
        } else {
            throw new UnsupportedOperationException();
        }
    }

    MondrianOlap4jMember toOlap4j(mondrian.olap.Member member) {
        if (member == null) {
            return null;
        }
        if (member instanceof RolapMeasure) {
            RolapMeasure measure = (RolapMeasure) member;
            return new MondrianOlap4jMeasure(
                toOlap4j(member.getDimension().getSchema()),
                measure);
        }
        return new MondrianOlap4jMember(
            toOlap4j(member.getDimension().getSchema()),
            member);
    }

    MondrianOlap4jLevel toOlap4j(mondrian.olap.Level level) {
        if (level == null) {
            return null;
        }
        return new MondrianOlap4jLevel(
            toOlap4j(level.getDimension().getSchema()),
            level);
    }

    MondrianOlap4jHierarchy toOlap4j(mondrian.olap.Hierarchy hierarchy) {
        if (hierarchy == null) {
            return null;
        }
        return new MondrianOlap4jHierarchy(
            toOlap4j(hierarchy.getDimension().getSchema()),
            hierarchy);
    }

    Type[] toOlap4j(mondrian.olap.type.Type[] mondrianTypes) {
        final Type[] types = new Type[mondrianTypes.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = toOlap4j(mondrianTypes[i]);
        }
        return types;
    }

    /**
     * Converts a Properties object to a Map with String keys and values.
     *
     * @param properties Properties
     * @return Map backed by the given Properties object
     */
    public static Map<String, String> toMap(final Properties properties) {
        return new AbstractMap<String, String>() {
            public Set<Entry<String, String>> entrySet() {
                return Olap4jUtil.cast(properties.entrySet());
            }
        };
    }

    MondrianOlap4jNamedSet toOlap4j(
        mondrian.olap.Cube cube,
        mondrian.olap.NamedSet namedSet)
    {
        if (namedSet == null) {
            return null;
        }
        return new MondrianOlap4jNamedSet(
            toOlap4j(cube),
            namedSet);
    }

    ParseTreeNode toOlap4j(Exp exp) {
        return new MondrianToOlap4jNodeConverter(this).toOlap4j(exp);
    }

    SelectNode toOlap4j(Query query) {
        return new MondrianToOlap4jNodeConverter(this).toOlap4j(query);
    }

    public void setLocale(Locale locale) {
        if (locale == null) {
            throw new IllegalArgumentException("locale must not be null");
        }
        this.locale = locale;
    }

    public Locale getLocale() {
        if (locale == null) {
            return Locale.getDefault();
        }
        return locale;
    }

    public void setRoleName(String roleName) throws OlapException {
        final Role role;
        if (roleName == null) {
            role = null;
        } else {
            role = this.connection.getSchema().lookupRole(roleName);
            if (role == null) {
                throw helper.createException("Unknown role '" + roleName + "'");
            }
        }
        // Remember the name of the role, because mondrian roles don't know
        // their own name.
        this.roleName = roleName;
        this.connection.setRole(role);
    }

    public String getRoleName() {
        return roleName;
    }

    public List<String> getAvailableRoleNames() {
        return UnmodifiableArrayList.of(
            ((RolapSchema) connection.getSchema()).roleNames());
    }

    // inner classes

    /**
     * Package-private helper class which encapsulates policies which are
     * common throughout the driver. These policies include exception handling
     * and factory methods.
     */
    static class Helper {
        OlapException createException(String msg) {
            return new OlapException(msg);
        }

        /**
         * Creates an exception in the context of a particular Cell.
         *
         * @param context Cell context for exception
         * @param msg Message
         * @return New exception
         */
        OlapException createException(Cell context, String msg) {
            OlapException exception = new OlapException(msg);
            exception.setContext(context);
            return exception;
        }

        /**
         * Creates an exception in the context of a particular Cell and with
         * a given cause.
         *
         * @param context Cell context for exception
         * @param msg Message
         * @param cause Causing exception
         * @return New exception
         */
        OlapException createException(
            Cell context, String msg, Throwable cause)
        {
            OlapException exception = new OlapException(msg, cause);
            exception.setContext(context);
            return exception;
        }

        /**
         * Creates an exception with a given cause.
         *
         * @param msg Message
         * @param cause Causing exception
         * @return New exception
         */
        OlapException createException(
            String msg, Throwable cause)
        {
            return new OlapException(msg, cause);
        }

        /**
         * Converts a SQLException to an OlapException. Casts the exception
         * if it is already an OlapException, wraps otherwise.
         *
         * <p>This method is typically used as an adapter for SQLException
         * instances coming from a base class, where derived interface declares
         * that it throws the more specific OlapException.
         *
         * @param e Exception
         * @return Exception as an OlapException
         */
        public OlapException toOlapException(SQLException e) {
            if (e instanceof OlapException) {
                return (OlapException) e;
            } else {
                return new OlapException(null, e);
            }
        }
    }

    private static class MondrianOlap4jMdxValidator implements MdxValidator {
        private final MondrianOlap4jConnection connection;

        public MondrianOlap4jMdxValidator(OlapConnection connection) {
            this.connection = (MondrianOlap4jConnection) connection;
        }

        public SelectNode validateSelect(SelectNode selectNode)
            throws OlapException
        {
            try {
                // A lot of mondrian's validation happens during parsing.
                // Therefore to do effective validation, we need to go back to
                // the MDX string. Someday we will reshape mondrian's
                // parse/validation process to fit the olap4j model better.
                StringWriter sw = new StringWriter();
                selectNode.unparse(new ParseTreeWriter(new PrintWriter(sw)));
                String mdx = sw.toString();
                Query query =
                    connection.connection
                        .parseQuery(mdx);
                query.resolve();
                return connection.toOlap4j(query);
            } catch (MondrianException e) {
                throw connection.helper.createException("Validation error", e);
            }
        }
    }

    private static class MondrianToOlap4jNodeConverter {
        private final MondrianOlap4jConnection olap4jConnection;

        MondrianToOlap4jNodeConverter(
            MondrianOlap4jConnection olap4jConnection)
        {
            this.olap4jConnection = olap4jConnection;
        }

        public SelectNode toOlap4j(Query query) {
            List<IdentifierNode> list = Collections.emptyList();
            return new SelectNode(
                null,
                toOlap4j(query.getFormulas()),
                toOlap4j(query.getAxes()),
                new CubeNode(
                    null,
                    olap4jConnection.toOlap4j(query.getCube())),
                query.getSlicerAxis() == null
                    ? null
                    : toOlap4j(query.getSlicerAxis()),
                list);
        }

        private AxisNode toOlap4j(QueryAxis axis) {
            return new AxisNode(
                null,
                axis.isNonEmpty(),
                Axis.Factory.forOrdinal(
                    axis.getAxisOrdinal().logicalOrdinal()),
                toOlap4j(axis.getDimensionProperties()),
                toOlap4j(axis.getSet()));
        }

        private List<IdentifierNode> toOlap4j(Id[] dimensionProperties) {
            final List<IdentifierNode> list = new ArrayList<IdentifierNode>();
            for (Id property : dimensionProperties) {
                list.add(toOlap4j(property));
            }
            return list;
        }

        private ParseTreeNode toOlap4j(Exp exp) {
            if (exp instanceof Id) {
                Id id = (Id) exp;
                return toOlap4j(id);
            }
            if (exp instanceof ResolvedFunCall) {
                ResolvedFunCall call = (ResolvedFunCall) exp;
                return toOlap4j(call);
            }
            if (exp instanceof DimensionExpr) {
                DimensionExpr dimensionExpr = (DimensionExpr) exp;
                return new DimensionNode(
                    null,
                    olap4jConnection.toOlap4j(dimensionExpr.getDimension()));
            }
            if (exp instanceof HierarchyExpr) {
                HierarchyExpr hierarchyExpr = (HierarchyExpr) exp;
                return new HierarchyNode(
                    null,
                    olap4jConnection.toOlap4j(hierarchyExpr.getHierarchy()));
            }
            if (exp instanceof LevelExpr) {
                LevelExpr levelExpr = (LevelExpr) exp;
                return new LevelNode(
                    null,
                    olap4jConnection.toOlap4j(levelExpr.getLevel()));
            }
            if (exp instanceof MemberExpr) {
                MemberExpr memberExpr = (MemberExpr) exp;
                return new MemberNode(
                    null,
                    olap4jConnection.toOlap4j(memberExpr.getMember()));
            }
            if (exp instanceof Literal) {
                Literal literal = (Literal) exp;
                final Object value = literal.getValue();
                if (literal.getCategory() == Category.Symbol) {
                    return LiteralNode.createSymbol(
                        null, (String) literal.getValue());
                } else if (value instanceof Double) {
                    return LiteralNode.create(null, (Double) value);
                } else if (value instanceof Integer) {
                    return LiteralNode.create(null, (Integer) value);
                } else if (value instanceof String) {
                    return LiteralNode.createString(null, (String) value);
                } else if (value == null) {
                    return LiteralNode.createNull(null);
                } else {
                    throw new RuntimeException("unknown literal " + literal);
                }
            }
            throw Util.needToImplement(exp.getClass());
        }

        private ParseTreeNode toOlap4j(ResolvedFunCall call) {
            final CallNode callNode = new CallNode(
                null,
                call.getFunName(),
                toOlap4j(call.getSyntax()),
                toOlap4j(Arrays.asList(call.getArgs())));
            if (call.getType() != null) {
                callNode.setType(olap4jConnection.toOlap4j(call.getType()));
            }
            return callNode;
        }

        private List<ParseTreeNode> toOlap4j(List<Exp> exprList) {
            final List<ParseTreeNode> result = new ArrayList<ParseTreeNode>();
            for (Exp expr : exprList) {
                result.add(toOlap4j(expr));
            }
            return result;
        }

        private org.olap4j.mdx.Syntax toOlap4j(mondrian.olap.Syntax syntax) {
            return org.olap4j.mdx.Syntax.valueOf(syntax.name());
        }

        private List<AxisNode> toOlap4j(QueryAxis[] axes) {
            final ArrayList<AxisNode> axisList = new ArrayList<AxisNode>();
            for (QueryAxis axis : axes) {
                axisList.add(toOlap4j(axis));
            }
            return axisList;
        }

        private List<ParseTreeNode> toOlap4j(Formula[] formulas) {
            final List<ParseTreeNode> list = new ArrayList<ParseTreeNode>();
            for (Formula formula : formulas) {
                if (formula.isMember()) {
                    List<PropertyValueNode> memberPropertyList =
                        new ArrayList<PropertyValueNode>();
                    for (Object child : formula.getChildren()) {
                        if (child instanceof MemberProperty) {
                            MemberProperty memberProperty =
                                (MemberProperty) child;
                            memberPropertyList.add(
                                new PropertyValueNode(
                                    null,
                                    memberProperty.getName(),
                                    toOlap4j(memberProperty.getExp())));
                        }
                    }
                    list.add(
                        new WithMemberNode(
                            null,
                            toOlap4j(formula.getIdentifier()),
                            toOlap4j(formula.getExpression()),
                            memberPropertyList));
                }
            }
            return list;
        }

        private IdentifierNode toOlap4j(Id id) {
            List<IdentifierNode.Segment> list =
                new ArrayList<IdentifierNode.Segment>();
            for (Id.Segment segment : id.getSegments()) {
                list.add(
                    new IdentifierNode.NameSegment(
                        null,
                        segment.name,
                        toOlap4j(segment.quoting)));
            }
            return new IdentifierNode(
                list.toArray(
                    new IdentifierNode.Segment[list.size()]));
        }

        private IdentifierNode.Quoting toOlap4j(Id.Quoting quoting) {
            return IdentifierNode.Quoting.valueOf(quoting.name());
        }
    }
}

// End MondrianOlap4jConnection.java
